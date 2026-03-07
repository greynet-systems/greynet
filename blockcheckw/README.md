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
└── pipeline/StrategyTest.kt       — полный цикл тестирования одной стратегии
```

## Компоненты

### ProcessRunner (`system/`)

Управление процессами через POSIX `fork()`/`execvp()`:

- `runProcess(command)` — запуск с ожиданием, захват stdout/stderr через `pipe()`
- `startBackground(command)` — фоновый запуск (stdout/stderr → `/dev/null`)
- `BackgroundProcess` — `kill()`, `isAlive()`, `waitFor()`

### CurlRunner (`network/`)

Типизированные curl-проверки:

- `curlTestHttp(domain)` — HTTP с заголовками (`-SsD -`)
- `curlTestHttpsTls12(domain)` — HTTPS с принудительным TLS 1.2
- `curlTestHttpsTls13(domain)` — HTTPS с принудительным TLS 1.3

Результат интерпретируется через `CurlVerdict`:
- `Available` — ресурс доступен (включая redirect на себя)
- `SuspiciousRedirect` — redirect на чужой домен (DPI-заглушка)
- `ServerReceivesFakes` — HTTP 400 (сервер получает мусор)
- `Unavailable` — timeout/reset (curl exit code 28, 56, etc.)

### DnsResolver (`network/`)

DNS-резолвер через `getent ahostsv4`:
- `resolveIpv4(domain)` → `Either<DnsError, List<String>>`
- Кэширование результатов в памяти

### Nfqws2Runner (`worker/`)

Запуск `nfqws2` как фонового процесса с заданной стратегией обхода.
Автодетект архитектуры (arm64/x86_64) для выбора бинарника.
- `startNfqws2(qnum, strategyArgs)` → `Either<Nfqws2Error, BackgroundProcess>`

### NftablesRunner (`firewall/`)

Установка/удаление nftables-правил для перенаправления TCP-трафика в NFQUEUE.
- `prepareTcp(port, qnum, ips)` → `Either<NftablesError, Unit>`
- `unprepare()` → `Either<NftablesError, Unit>`

### StrategyTest (`pipeline/`)

Полный цикл тестирования одной bypass-стратегии:

1. DNS-резолв домена → IP-адреса
2. nftables → перенаправление TCP-трафика в NFQUEUE
3. nfqws2 → запуск с заданной стратегией
4. curl-тест → проверка доступности
5. kill nfqws2 + cleanup nftables (гарантированно через `try/finally`)
6. Интерпретация результата → `StrategyTestResult`

```kotlin
testStrategy(StrategyTestParams(
    domain = "rutracker.ru",
    strategyArgs = listOf("--dpi-desync=split2"),
    protocol = Protocol.HTTPS_TLS12,
)) // → Either<StrategyTestError, StrategyTestResult>
```

## Сборка и запуск

```bash
# сборка (x86_64)
./gradlew :blockcheckw:linkDebugExecutableLinuxX64

# healthcheck
blockcheckw.kexe --healthcheck

# запуск (проверка без bypass + bypass-стратегия для заблокированных протоколов)
blockcheckw.kexe
```

Пример вывода (заблокированный домен, bypass через split2):
```
=== blockcheckw: тестирование rutracker.ru ===

* curl_test_http ipv4 rutracker.ru
- checking without DPI bypass
UNAVAILABLE (curl exit code=28)

* curl_test_https_tls12 ipv4 rutracker.ru
- checking without DPI bypass
UNAVAILABLE (curl exit code=56)

* curl_test_https_tls13 ipv4 rutracker.ru
- checking without DPI bypass
UNAVAILABLE (curl exit code=56)

=== bypass test: HTTP с --dpi-desync=split2 ===
!!!!! AVAILABLE with strategy: --dpi-desync=split2 !!!!!

=== bypass test: HTTPS TLS1.2 с --dpi-desync=split2 ===
FAILED: UNAVAILABLE (curl exit code=56)
```

## Целевые платформы

| Платформа | Таргет | Статус |
|-----------|--------|--------|
| Desktop Linux | `linuxX64` | работает |
| NanoPi R3S (OpenWrt) | `linuxArm64` | работает |

## Что дальше

- Перебор стратегий из `blockcheck2.sh`
- Параллельный запуск (16-32 NFQUEUE) для ускорения
