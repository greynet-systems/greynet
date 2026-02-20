# Greynet

## Build

```bash
./gradlew :app:build       # Сборка
./gradlew :app:run         # Запуск Ktor-сервера на :8080
```

Требуется Java 21+ (Temurin).

## Project structure

```
greynet/                    # Kotlin Multiplatform monorepo
├── app/                    # Приложение (Android, Desktop, PWA — Ktor/Netty, JVM)
├── firmware/               # OpenWrt сборка (NanoPi R3S)
├── strategies/             # База стратегий antiDPI
├── mcp/                    # zapret2-mcp (MIT) — dev-инструмент, fallback
├── docs/                   # Публичная документация
└── site/                   # Лендинг
```

### AntiDPI ядро

- **ByeDPI** (C) — основной движок на всех платформах
  - Android: VpnService + byedpi binary
  - Desktop: subprocess + SOCKS transparent proxy
  - Router: transparent mode + iptables REDIRECT
- **zapret2** — fallback для роутерной прошивки (NFQUEUE, deeper packet manipulation)

- Kotlin 2.1.10, Gradle 8.12.1, Ktor 3.1.1
- Group: `systems.greynet`, version: `0.1.0-SNAPSHOT`

## Внутренняя документация
Контекст проекта находится в private-репо:
- `../greynet-management/docs/product-vision.md`

## Генерация кода
- Всегда используй `context7` для того, чтобы понять, как устроены используемые библиотеки
