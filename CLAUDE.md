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
├── app/                    # Десктоп-приложение (Ktor/Netty, JVM)
├── firmware/               # OpenWrt сборка
├── strategies/             # База стратегий antiDPI
├── mcp/                    # zapret2-mcp (MIT)
├── docs/                   # Публичная документация
└── site/                   # Лендинг
```

- Kotlin 2.1.10, Gradle 8.12.1, Ktor 3.1.1
- Group: `systems.greynet`, version: `0.1.0-SNAPSHOT`

## Внутренняя документация
Контекст проекта находится в private-репо:
- `../greynet-management/docs/product-vision.md`
