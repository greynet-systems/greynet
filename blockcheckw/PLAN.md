# План реализации blockcheckw (Kotlin-порт blockcheck2.sh)

## Контекст

blockcheck2.sh — bash-скрипт (~1920 строк, 100+ функций) для тестирования DPI bypass стратегий через zapret2. Нужен полный 1:1 порт на Kotlin Native (multiplatform) с Arrow-kt ФП, интерактивным + batch CLI, и стратегиями как Kotlin DSL.

**Текущее состояние:** инфраструктура проекта готова (build.gradle.kts, multiplatform targets, Docker-тесты), код — только `Main.kt` с healthcheck заглушкой.

---

## Архитектура модулей

```
src/nativeMain/kotlin/systems/greynet/blockcheckw/
├── Main.kt                     # Точка входа, CLI parsing
├── config/
│   ├── Config.kt               # ZapretConfig, загрузка config.default
│   └── Params.kt               # Параметры из ask_params (domains, ipvs, protocols, scanlevel)
├── system/
│   ├── Platform.kt             # OS detection (Linux/FreeBSD/OpenBSD/Cygwin)
│   ├── Prerequisites.kt        # check_prerequisites — проверка бинарников
│   ├── Virtualization.kt       # check_virt — детект VM/container
│   └── ProcessRunner.kt        # Запуск внешних процессов (posix_spawn / CreateProcess)
├── dns/
│   ├── DnsCheck.kt             # check_dns, check_dns_spoof, IP uniqueness
│   ├── DnsResolve.kt           # mdig_resolve, lookup4, DoH resolve
│   └── DohClient.kt            # doh_find_working, DoH fallback
├── firewall/
│   ├── Firewall.kt             # sealed interface — абстракция FW операций
│   ├── Iptables.kt             # iptables: prepare/unprepare TCP/UDP, NFQUEUE
│   ├── Nftables.kt             # nft реализация
│   ├── Ipfw.kt                 # ipfw (FreeBSD)
│   ├── Pf.kt                   # pf (OpenBSD)
│   └── WinDivert.kt            # windivert (Cygwin)
├── network/
│   ├── CurlRunner.kt           # curl_test, curl_probe, curl_with_subst_ip
│   ├── CurlCapabilities.kt     # Detect TLS1.3, HTTP3, tls-max, connect-to
│   ├── NetcatTest.kt           # netcat_setup, netcat_test, port block check
│   └── PingTest.kt             # pingtest, ping_with_fix
├── worker/
│   ├── PacketWorker.kt         # sealed class: Nfqws2 / Dvtws2 / Winws2
│   ├── WorkerManager.kt        # pktws_start, ws_kill, lifecycle
│   └── WorkerTest.kt           # ws_curl_test, pktws_curl_test, pktws_curl_test_update
├── strategy/
│   ├── Strategy.kt             # DSL интерфейс для стратегий
│   ├── StrategyRunner.kt       # test_runner — итерация по стратегиям
│   ├── StrategyRegistry.kt     # Реестр встроенных стратегий (замена blockcheck2.d/)
│   └── strategies/             # Конкретные стратегии (Kotlin DSL)
│       ├── HttpBasic.kt        # 10-http-basic
│       ├── TcpSeg.kt           # 15-misc (tcpseg)
│       ├── Oob.kt              # 17-oob
│       ├── MultiSplit.kt       # 20-multi
│       ├── SeqOvl.kt           # 23-seqovl
│       ├── SynData.kt          # 24-syndata
│       ├── Fake.kt             # 25-fake
│       ├── FakedSplit.kt       # 30-faked
│       ├── HostFake.kt         # 35-hostfake
│       ├── FakeMulti.kt        # 50-fake-multi
│       ├── FakeFaked.kt        # 55-fake-faked
│       ├── FakeHostFake.kt     # 60-fake-hostfake
│       └── Quic.kt             # 90-quic
├── domain/
│   ├── DomainChecker.kt        # check_domain_http/https_tls12/tls13/http3
│   ├── DomainProlog.kt         # check_domain_prolog — базовый curl тест
│   ├── IpBlockCheck.kt         # check_dpi_ip_block — сравнение blocked vs unblocked
│   └── PortBlockCheck.kt       # check_domain_port_block — netcat probe
├── report/
│   ├── Report.kt               # report_append, report_print
│   └── Intersection.kt         # result_intersection_print — пересечение по доменам
├── cli/
│   ├── InteractivePrompt.kt    # ask_yes_no, ask_list (stdin)
│   └── BatchMode.kt            # Параметры через CLI args / env
└── signal/
    └── SignalHandler.kt        # sigint, sigsilent, cleanup traps (POSIX signals)
```

---

## Этапы реализации

### Этап 1 — Фундамент (system + config + CLI)
**Цель:** запускается бинарник, определяет ОС, загружает конфиг, парсит CLI аргументы.

1. `Config.kt` — загрузка/создание конфига (config.default → config)
2. `Platform.kt` — check_system(): определение ОС, firewall type, выбор packet worker
3. `Prerequisites.kt` — check_prerequisites(): наличие curl, nfqws2/dvtws2/winws2, mdig
4. `Main.kt` — пока что ручной парсинг аргументов
5. `ProcessRunner.kt` — базовый запуск внешних процессов (posix_spawn / CreateProcess)

**Проверка:** `blockcheckw --healthcheck` работает, `blockcheckw` без аргументов определяет ОС и проверяет prerequisites.

### Этап 2 — DNS + сеть
**Цель:** DNS резолюция, спуф-детект, базовые сетевые проверки.

1. `PingTest.kt` — ping IPv4/IPv6
2. `DnsResolve.kt` — mdig resolve, lookup4 (nslookup/host)
3. `DnsCheck.kt` — check_dns_spoof, IP uniqueness check
4. `DohClient.kt` — DoH fallback (doh_find_working)
5. `NetcatTest.kt` — netcat setup/test, port block detection
6. `CurlCapabilities.kt` — определение возможностей curl (TLS1.3, HTTP3, tls-max, connect-to)

**Проверка:** `blockcheckw` проходит DNS check, показывает curl capabilities.

### Этап 3 — Firewall абстракция
**Цель:** подготовка/очистка firewall rules для перенаправления трафика.

1. `Firewall.kt` — sealed interface с операциями: prepareTcp, rollbackTcp, prepareUdp, rollbackUdp, cleanup
2. `Iptables.kt` — iptables/ip6tables реализация (NFQUEUE)
3. `Nftables.kt` — nft реализация
4. `Ipfw.kt` — ipfw (FreeBSD divert)
5. `Pf.kt` — pf anchors (OpenBSD)
6. `WinDivert.kt` — WinDivert (Windows/Cygwin)
7. `SignalHandler.kt` — обработка SIGINT/SIGPIPE/SIGHUP с cleanup

**Проверка:** firewall rules создаются и корректно убираются при Ctrl+C.

### Этап 4 — Packet worker + curl тестирование
**Цель:** запуск nfqws2/dvtws2/winws2, curl-тесты через него.

1. `PacketWorker.kt` — sealed class с параметрами запуска для каждой платформы
2. `WorkerManager.kt` — start/kill lifecycle, PID tracking
3. `CurlRunner.kt` — curl_test, curl_probe, curl_with_subst_ip, параллельный режим
4. `WorkerTest.kt` — ws_curl_test, pktws_curl_test, pktws_curl_test_update

**Проверка:** ручной тест — запуск nfqws2 с простой стратегией, curl через него, kill.

### Этап 5 — Strategy DSL + встроенные стратегии
**Цель:** Kotlin DSL для описания стратегий, порт всех 13 стратегий из blockcheck2.d/.

1. `Strategy.kt` — DSL интерфейс:
   ```kotlin
   interface Strategy {
       val name: String
       val order: Int  // порядок выполнения (10, 15, 17, ...)
       context(Raise<BlockcheckwError>)
       suspend fun checkHttp(testFn: TestFunction, domain: String)
       context(Raise<BlockcheckwError>)
       suspend fun checkHttpsTls12(testFn: TestFunction, domain: String)
       // ... tls13, http3
   }
   ```
2. `StrategyRunner.kt` — test_runner: итерация по зарегистрированным стратегиям
3. `StrategyRegistry.kt` — реестр с порядком выполнения
4. Порт 13 стратегий в `strategies/` (HttpBasic, TcpSeg, Oob, MultiSplit, SeqOvl, SynData, Fake, FakedSplit, 
   HostFake, FakeMulti, FakeFaked, FakeHostFake, QUICK)

**Проверка:** стратегии загружаются, test_runner итерирует по ним.

### Этап 6 — Domain checker (основной цикл)
**Цель:** полный цикл domain × IPV × protocol.

1. `DomainProlog.kt` — check_domain_prolog: базовый curl тест без bypass
2. `IpBlockCheck.kt` — check_dpi_ip_block: тест blocked IP vs unblocked
3. `PortBlockCheck.kt` — check_domain_port_block: netcat probe
4. `DomainChecker.kt` — check_domain_http/https_tls12/tls13/http3:
   - prolog → ip block check → fw prepare → strategy runner → fw unprepare
5. Основной цикл в Main.kt: domains × ipvs → configure_ip_version → protocol checks

**Проверка:** полный прогон на одном домене — HTTP + HTTPS TLS1.2.

### Этап 7 — Интерактивный режим + отчёты
**Цель:** ask_params, report, intersection.

1. `InteractivePrompt.kt` — ask_yes_no, ask_list (stdin readline)
2. `BatchMode.kt` — параметры через CLI/env
3. `Params.kt` — ask_params flow: test suite → domains → ipvs → protocols → repeats → parallel → scanlevel
4. `Virtualization.kt` — check_virt
5. `Report.kt` — report_append, report_print (SUMMARY)
6. `Intersection.kt` — result_intersection_print (COMMON для множества доменов)

**Проверка:** полный end-to-end прогон идентичный `blockcheck2.sh --batch`.

### Этап 8 — Полировка
1. Parallel scan (корутины для REPEATS > 1)
2. Custom стратегии из файлов (аналог blockcheck2.d/custom/10-list.sh)
3. Docker-тесты для всех платформ
4. Edge cases: IPv6 defrag, autottl, multidisorder_legacy

---

## Ключевые архитектурные решения

| Решение | Подход |
|---------|--------|
| Error handling | `Either<BlockcheckwError, T>`, `Raise<E>`, `either {}` — без exceptions |
| Внешние процессы | `posix_spawn` (POSIX) / `CreateProcess` (Windows) через `ProcessRunner` |
| Firewall | `sealed interface Firewall` с реализацией per-platform |
| Packet worker | `sealed class PacketWorker` (Nfqws2/Dvtws2/Winws2) |
| Стратегии | `interface Strategy` — встроенные Kotlin-объекты в реестре, порядок через `order: Int` |
| Параллелизм | `kotlinx.coroutines` для parallel scan и async DNS |
| CLI | Clikt (уже в зависимостях) для аргументов, stdin для интерактива |
| Конфиг | Файл key=value (как в bash), парсится в data class |

## Файлы-референсы

- `blockcheckw/blockcheck2.sh` — исходный скрипт (весь файл)
- `blockcheckw/build.gradle.kts` — текущая сборка
- `blockcheckw/src/nativeMain/kotlin/systems/greynet/blockcheckw/Main.kt` — текущая точка входа
- `.claude/skills/kotlin-arrow-fp.md` — стиль кода Arrow-kt
- `/opt/zapret2/blockcheck2.d/standard/` — стратегии для порта
