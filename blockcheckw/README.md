#### Blockcheck Wrapper

Замена классическому `bash`-скрипту `blockcheck2.sh` от `zapret2` для проверки блокировок.
Kotlin/Native, без JVM — один бинарник для x86_64 и arm64 (NanoPi R3S).

## Архитектура

```
src/nativeMain/kotlin/systems/greynet/blockcheckw/
├── Main.kt                        — точка входа, --healthcheck / --count / --test-parallel
├── system/ProcessRunner.kt        — fork/exec/pipe, управление процессами
├── network/
│   ├── CurlRunner.kt             — curl-тесты HTTP/HTTPS, интерпретация результатов
│   └── DnsResolver.kt            — DNS-резолвер с кэшированием (getent)
├── worker/Nfqws2Runner.kt         — запуск/остановка nfqws2 (фоновый процесс)
├── firewall/NftablesRunner.kt     — nftables правила для NFQUEUE
└── pipeline/
    ├── StrategyTest.kt            — полный цикл тестирования одной стратегии
    ├── StrategyGenerator.kt       — генерация всех bypass-стратегий
    ├── Worker.kt                  — единица параллельной работы (slot + task)
    ├── ParallelRunner.kt          — оркестратор параллельного запуска
    └── StressTest.kt              — диагностика параллелизма (--test-parallel)
```

## Компоненты

### ProcessRunner (`system/`)

Управление процессами через POSIX `fork()`/`execvp()`:

- `runProcess(command)` — запуск с ожиданием, захват stdout/stderr через `pipe()`
- `startBackground(command)` — фоновый запуск (stdout/stderr → `/dev/null`)
- `BackgroundProcess` — `kill()`, `isAlive()`, `waitFor()`

### CurlRunner (`network/`)

Типизированные curl-проверки:

- `curlTestHttp(domain, localPort?, maxTime?)` — HTTP с заголовками (`-SsD -`)
- `curlTestHttpsTls12(domain, localPort?, maxTime?)` — HTTPS с принудительным TLS 1.2
- `curlTestHttpsTls13(domain, localPort?, maxTime?)` — HTTPS с принудительным TLS 1.3

Результат интерпретируется через `CurlVerdict`:
- `Available` — ресурс доступен (включая redirect на себя)
- `SuspiciousRedirect` — redirect на чужой домен (DPI-заглушка)
- `ServerReceivesFakes` — HTTP 400 (сервер получает мусор)
- `Unavailable` — timeout/reset (curl exit code 28, 56, etc.)

### DnsResolver (`network/`)

DNS-резолвер через `getent ahostsv4` (fallback: `nslookup`):
- `resolveIpv4(domain)` → `Either<DnsError, List<String>>`
- Кэширование результатов в памяти

### Nfqws2Runner (`worker/`)

Запуск `nfqws2` как фонового процесса с заданной стратегией обхода.
Автодетект архитектуры (arm64/x86_64) для выбора бинарника.
- `startNfqws2(qnum, strategyArgs)` → `Either<Nfqws2Error, BackgroundProcess>`

### NftablesRunner (`firewall/`)

Установка/удаление nftables-правил для перенаправления TCP-трафика в NFQUEUE.

Single-mode (один воркер):
- `prepareTcp(port, qnum, ips)` / `unprepare()` — создание/удаление всей таблицы

Parallel-mode (per-worker правила):
- `prepareTable()` — создать таблицу `zapret` + chain `postnat` (один раз)
- `addWorkerRule(sportRange, dport, qnum, ips)` → `RuleHandle` — правило с match по диапазону source port
- `removeRule(handle)` — удаление конкретного правила по handle
- `dropTable()` — удаление всей таблицы после batch

### StrategyTest (`pipeline/`)

Полный цикл тестирования одной bypass-стратегии (single-mode):

1. DNS-резолв домена → IP-адреса
2. nftables → перенаправление TCP-трафика в NFQUEUE
3. nfqws2 → запуск с заданной стратегией
4. curl-тест → проверка доступности
5. kill nfqws2 + cleanup nftables (гарантированно через `try/finally`)
6. Интерпретация результата → `StrategyTestResult`

### Worker + ParallelRunner (`pipeline/`)

Параллельный прогон стратегий через coroutines с изоляцией по source port.

Каждый воркер полностью изолирован:
- свой диапазон `--local-port` для curl (10 портов на воркер)
- своё nftables-правило с match по диапазону `tcp sport`
- свой NFQUEUE номер (200 + id) и отдельный nfqws2-процесс

```
Worker 0: curl --local-port 30000-30009 → nft: sport 30000-30009 → queue 200 → nfqws2 (стратегия A)
Worker 1: curl --local-port 30010-30019 → nft: sport 30010-30019 → queue 201 → nfqws2 (стратегия B)
...
Worker N: curl --local-port 300N0-300N9 → nft: sport 300N0-300N9 → queue 20N → nfqws2 (стратегия N)
```

Диапазон портов (10 штук на воркер) вместо одного порта решает проблему TCP TIME_WAIT:
после закрытия TCP-соединения порт уходит в TIME_WAIT на ~60с и `bind()` на него фейлит.
curl с `--local-port X-Y` автоматически перебирает порты в диапазоне при bind failure.

Оркестратор:
1. DNS-резолв (один раз, до batch-ей)
2. `prepareTable()` — создать таблицу
3. Разбить стратегии на batch-и по `workerCount` (default: 16)
4. Каждый batch — N воркеров параллельно через `async`
5. `dropTable()` — cleanup

#### Особенности POSIX-параллельности

Параллельность построена на `fork()`/`execvp()`, а не на корутинах.
`Dispatchers.Default` в Kotlin/Native использует пул потоков = числу ядер CPU,
но каждый воркер блокирует поток на POSIX-вызовах (`fork`, `read`, `waitpid`, `usleep`),
поэтому реальная параллельность ограничена числом потоков в пуле, а не значением `workerCount`.
Batch из 16 воркеров на 4-ядерном NanoPi выполняется по ~4 штуки за раз.

Ключевые моменты:
- **FD_CLOEXEC на pipe**: `runProcess()` (curl) создаёт pipe для stdout/stderr.
  Без `FD_CLOEXEC` дочерние процессы nfqws2 (запущенные через `startBackground`/`fork`)
  наследуют write-end этих pipe. Когда curl завершается, pipe не закрывается,
  потому что nfqws2 держит копию fd → `read()` блокируется навсегда → deadlock.
- **waitpid после kill**: `BackgroundProcess.kill()` отправляет `SIGKILL` и обязательно
  вызывает `waitpid()` для сбора зомби-процесса. Без этого при тысячах стратегий
  зомби забивают таблицу процессов ядра.

### StressTest (`pipeline/`)

Режим `--test-parallel [workers] [iterations]` для диагностики проблем параллелизма.
Повторяет одну и ту же стратегию N раз с подробным логированием каждого шага:

```
[0.113s] [iter 1] START batch of 4 workers
[0.117s] [iter 1][w0] nft add rule sport=30000-30009 qnum=200
[0.142s] [iter 1][w0] nfqws2 start pid=6223
[0.194s] [iter 1][w0] curl start
[0.355s] [iter 1][w0] curl done exitCode=0 httpCode=307 (160ms)
[0.356s] [iter 1][w0] nfqws2 kill pid=6223
[0.485s] [iter 1] DONE batch (371ms) ok=0 fail=4
```

Таймаут на batch (30с) — при фризе убивает все nfqws2 и логирует где застряло.

Что искать в выводе:
- **Фриз на curl** → deadlock в `readFdToString` (pipe/fd)
- **Фриз после 4 воркеров** → thread starvation (`Dispatchers.Default` = числу ядер)
- **Фриз через N итераций** → утечка fd/зомби
- **Всё ок** → проблема в объёме стратегий, не в механике

## Сборка и запуск

```bash
# сборка (x86_64)
./gradlew :blockcheckw:linkDebugExecutableLinuxX64

# healthcheck
blockcheckw.kexe --healthcheck

# запуск (проверка без bypass + параллельный перебор стратегий для заблокированных протоколов)
blockcheckw.kexe

# стресс-тест параллелизма (4 воркера, 3 итерации — default)
blockcheckw.kexe --test-parallel

# 8 воркеров, 5 итераций (ищем thread starvation)
blockcheckw.kexe --test-parallel 8 5
```

Пример вывода:
```
=== blockcheckw: тестирование rutracker.org ===
ISP: AS31163 PJSC MegaFon | 188.170.204.13 | Lipetsk, Lipetsk Oblast, RU

resolved rutracker.org -> 104.21.32.39, 172.67.182.196

* curl_test_http ipv4 rutracker.org
- checking without DPI bypass
suspicious redirection 307 to: http://forbidden.yota.ru/

* curl_test_https_tls12 ipv4 rutracker.org
- checking without DPI bypass
UNAVAILABLE (curl exit code=35)

=== bypass test: HTTP (parallel, 4 workers) ===
  [worker] --payload=http_req --lua-desync=hostfakesplit:ip_ttl=7:repeats=1
    !!!!! AVAILABLE with strategy: ... !!!!!
  [worker] --payload=http_req --lua-desync=hostfakesplit:tcp_md5:repeats=1
    !!!!! AVAILABLE with strategy: ... !!!!!
  [worker] --payload=http_req --lua-desync=fake:blob=fake_default_http:tcp_md5:repeats=1 ...
    FAILED: UNAVAILABLE (curl exit code=28)
  [worker] --in-range=-s1 --lua-desync=oob:urp=b
    FAILED: UNAVAILABLE (curl exit code=28)
  => working strategy for HTTP: --payload=http_req --lua-desync=hostfakesplit:ip_ttl=7:repeats=1
```

## Целевые платформы

| Платформа | Таргет | Статус |
|-----------|--------|--------|
| Desktop Linux | `linuxX64` | работает |
| NanoPi R3S (OpenWrt) | `linuxArm64` | работает |

## Почему curl, а не ktor-client

HTTP-запросы делаются через `fork()`+`execvp("curl", ...)` — отдельный процесс на каждый запрос.
Рассматривался переход на ktor-client (CIO или Curl engine), но он не подходит:

1. **Нет `--local-port`** — ни CIO, ни Curl engine ktor не поддерживают `CURLOPT_LOCALPORT`.
   Привязка к локальному порту критична для маршрутизации через NFQUEUE в nftables.
2. **Нет выбора TLS-версии** — `CURLOPT_SSLVERSION` не экспонируется.
   Нужно тестировать TLS 1.2 и TLS 1.3 отдельно.
3. **+3-8 MB к бинарнику** — текущий бинарник ~2 MB (linuxArm64 release).

fork+exec curl:
- Полный контроль над всеми curl-опциями
- OS-уровневый параллелизм (каждый curl — отдельный процесс)
- Минимальный бинарник, зависимость только от curl CLI (уже есть на OpenWrt)

Альтернатива на будущее: прямой cinterop с libcurl (`curl_easy_init`/`curl_easy_setopt`)
для in-process HTTP без fork overhead.

## Что дальше

- Переход на pipeline-модель (пул слотов вместо batch-ей) для реальной параллельности
