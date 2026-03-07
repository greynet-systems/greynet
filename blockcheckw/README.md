#### Blockcheck Wrapper

Замена классическому `bash`-скрипту `blockcheck2.sh` от `zapret2` для проверки блокировок.
Kotlin/Native, без JVM — один бинарник для x86_64 и arm64 (NanoPi R3S).

## Архитектура

```
src/nativeMain/kotlin/systems/greynet/blockcheckw/
├── Main.kt                        — точка входа, запуск тестов
├── system/ProcessRunner.kt        — fork/exec/pipe, управление процессами
├── network/
│   ├── CurlRunner.kt             — curl-тесты HTTP/HTTPS, интерпретация результатов
│   └── DnsResolver.kt            — DNS-резолвер с кэшированием (getent)
├── worker/Nfqws2Runner.kt         — запуск/остановка nfqws2 (фоновый процесс)
├── firewall/NftablesRunner.kt     — nftables правила для NFQUEUE
└── pipeline/
    ├── StrategyTest.kt            — полный цикл тестирования одной стратегии
    ├── Worker.kt                  — единица параллельной работы (slot + task)
    └── ParallelRunner.kt          — оркестратор параллельного запуска
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
- `addWorkerRule(sport, dport, qnum, ips)` → `RuleHandle` — правило с match по source port
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

Параллельный прогон стратегий через pthreads + атомарную очередь задач с изоляцией по source port.

Каждый воркер полностью изолирован:
- свой `--local-port` для curl (30000 + id)
- своё nftables-правило с match по `tcp sport`
- свой NFQUEUE номер (200 + id) и отдельный nfqws2-процесс

```
Worker 0: curl --local-port 30000 → nftables: sport 30000 → queue 200 → nfqws2 (стратегия A)
Worker 1: curl --local-port 30001 → nftables: sport 30001 → queue 201 → nfqws2 (стратегия B)
...
Worker N: curl --local-port 3000N → nftables: sport 3000N → queue 20N → nfqws2 (стратегия N)
```

Оркестратор (pthreads, без coroutines):
1. DNS-резолв (один раз, до запуска потоков)
2. `prepareTable()` — создать таблицу
3. `pthread_create` × N потоков, каждый берёт задачи из `AtomicInt`-счётчика (lock-free)
4. Поток: `fetchAndAdd(1)` → индекс стратегии → `executeWorker()` → запись результата → повтор
5. `pthread_join` × N — ждём все потоки
6. `dropTable()` — cleanup

В отличие от предыдущей batch-модели с `Dispatchers.Default` (пул = число ядер CPU),
pthreads дают реальную параллельность: 16 потоков ждут сеть одновременно,
ядра CPU не являются bottleneck для IO-bound нагрузки.

#### Потокобезопасность

| Ресурс | Механизм |
|--------|----------|
| `strategies: List<...>` | Read-only, безопасно |
| `nextIndex: AtomicInt` | Lock-free |
| `results[idx]` | Каждый idx пишет ровно один поток |
| `println()` | `pthread_mutex` |
| nftables правила | Уникальный sport+qnum на слот |
| fork/pipe | `FD_CLOEXEC` предотвращает утечку fd |

#### Ключевые моменты POSIX

- **FD_CLOEXEC на pipe**: `runProcess()` (curl) создаёт pipe для stdout/stderr.
  Без `FD_CLOEXEC` дочерние процессы nfqws2 (запущенные через `startBackground`/`fork`)
  наследуют write-end этих pipe. Когда curl завершается, pipe не закрывается,
  потому что nfqws2 держит копию fd → `read()` блокируется навсегда → deadlock.
- **waitpid после kill**: `BackgroundProcess.kill()` отправляет `SIGKILL` и обязательно
  вызывает `waitpid()` для сбора зомби-процесса. Без этого при тысячах стратегий
  зомби забивают таблицу процессов ядра.

## Сборка и запуск

```bash
# сборка (x86_64)
./gradlew :blockcheckw:linkDebugExecutableLinuxX64

# healthcheck
blockcheckw.kexe --healthcheck

# запуск (проверка без bypass + параллельный перебор стратегий для заблокированных протоколов)
blockcheckw.kexe
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

## Что дальше

- Тесты на параллельность и POSIX-корректность
