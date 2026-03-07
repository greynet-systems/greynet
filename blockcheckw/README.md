#### Blockcheck Wrapper

Замена классическому `bash`-скрипту `blockcheck2.sh` от `zapret2` для проверки блокировок.
Kotlin/Native, без JVM — один бинарник для x86_64 и arm64 (NanoPi R3S).

## Архитектура

```
src/nativeMain/kotlin/systems/greynet/blockcheckw/
├── Main.kt                        — точка входа, запуск тестов
├── system/ProcessRunner.kt        — fork/exec/pipe, управление процессами
├── network/CurlRunner.kt          — curl-тесты HTTP/HTTPS, интерпретация результатов
├── worker/Nfqws2Runner.kt         — запуск/остановка nfqws2 (фоновый процесс)
└── firewall/NftablesRunner.kt     — nftables правила для NFQUEUE
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

### Nfqws2Runner (`worker/`)

Запуск `nfqws2` как фонового процесса с заданной стратегией обхода.
Автодетект архитектуры (arm64/x86_64) для выбора бинарника.

### NftablesRunner (`firewall/`)

Установка/удаление nftables-правил для перенаправления TCP-трафика в NFQUEUE.

## Сборка и запуск

```bash
# сборка (x86_64)
./gradlew :blockcheckw:linkDebugExecutableLinuxX64

# healthcheck
blockcheckw.kexe --healthcheck

# запуск тестов (HTTP + HTTPS TLS1.2 + TLS1.3)
blockcheckw.kexe
```

Пример вывода (заблокированный домен без bypass):
```
* curl_test_http ipv4 rutracker.ru
- checking without DPI bypass
UNAVAILABLE (curl exit code=28)

* curl_test_https_tls12 ipv4 rutracker.ru
- checking without DPI bypass
UNAVAILABLE (curl exit code=56)

* curl_test_https_tls13 ipv4 rutracker.ru
- checking without DPI bypass
UNAVAILABLE (curl exit code=56)
```

## Целевые платформы

| Платформа | Таргет | Статус |
|-----------|--------|--------|
| Desktop Linux | `linuxX64` | работает |
| NanoPi R3S (OpenWrt) | `linuxArm64` | работает |

## Что дальше

- Полный цикл: nftables → nfqws2(стратегия) → curl → kill → cleanup
- Перебор стратегий из `blockcheck2.sh`
- Параллельный запуск (16-32 NFQUEUE) для ускорения
