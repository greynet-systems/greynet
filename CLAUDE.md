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
├── firmware/               # OpenWrt прошивка для "умной коробки" (NanoPi R3S)
├── strategies/             # База стратегий antiDPI
├── mcp/                    # zapret2-mcp (MIT) — dev-инструмент
├── docs/                   # Публичная документация
└── site/                   # Лендинг
```

- Kotlin 2.1.10, Gradle 8.12.1, Ktor 3.1.1
- Group: `systems.greynet`, version: `0.1.0-SNAPSHOT`

## Firmware: умная коробка (NanoPi R3S)

### Концепция

Антицензурный прокси-узел по модели **"plug and scan"**. Пользователь подключает коробку
в LAN-порт роутера, она самонастраивается, а устройства подключаются через mesh-сеть
по QR-коду.

### Как это работает (user story)

1. Пользователь подключает NanoPi R3S в LAN-порт своего роутера (eth0 → DHCP)
2. Коробка получает интернет, запускает **самодиагностику**:
   - Проверка интернет-связности (HTTP 204 check)
   - Детект типов DPI-блокировок — **dpi-detector** (быстро, ~3 мин)
   - Точечный подбор стратегии обхода — **zapret2 blockcheck** (тяжёлый, ~5 мин, запускается только при необходимости)
3. Применяет стратегию обхода через **zapret2** (NFQUEUE + nfqws)
4. Поднимает **HTTP/SOCKS прокси** для исходящего трафика
5. Регистрируется в **Headscale** mesh-сети (self-hosted Tailscale координатор)
6. Генерирует **QR-код** — пользователь сканирует, получает настройки подключения
7. Устройства пользователя подключаются через Tailscale → коробка как exit node → zapret2 обходит DPI на выходе

### Почему mesh, а не inline gateway (ADR)

**Решение:** устройства подключаются через Tailscale mesh-сеть, а не через коробку
как inline-шлюз между провайдером и роутером.

**Inline-режим** (коробка между провайдером и роутером) обеспечил бы прозрачный
plug-and-play для всех устройств без установки приложений, но на практике невозможен
из-за количества переменных:

| Проблема | Частота | Суть |
|---|---|---|
| ONT встроен в роутер | Очень часто | GPON-роутер от провайдера — нет ethernet-кабеля, коробку некуда воткнуть inline |
| MAC-привязка | Часто | Провайдер привязывает MAC WAN-порта роутера, при смене устройства интернет отваливается |
| PPPoE | Часто | Нужны логин/пароль от провайдера для поднятия сессии |
| VLAN-тегирование | Иногда | Провайдер требует конкретный VLAN ID на WAN |
| IPTV/телефония | Часто | Мультикаст и SIP через отдельные VLAN — коробка должна прозрачно пропускать |
| Double NAT | Всегда | Ломает UPnP, игры, видеозвонки |
| Single point of failure | Всегда | Коробка зависла → весь дом без интернета (нужен hardware bypass relay) |

**Mesh-режим** требует от пользователя одно действие (сканировать QR → установить
Tailscale), но зато:
- Работает с любым роутером и провайдером без изменения топологии сети
- Работает за пределами дома (мобильный доступ через mesh)
- Коробка не является single point of failure для домашней сети
- Не нужно знать настройки провайдера (PPPoE, VLAN, MAC)

### Архитектура компонентов

```
┌─────────────────────────────────────────────┐
│              NanoPi R3S (OpenWrt)            │
│                                             │
│  ┌──────────┐  ┌──────────┐  ┌───────────┐ │
│  │ zapret2  │  │  прокси  │  │ Tailscale │ │
│  │ (nfqws)  │  │ HTTP/    │  │  client   │ │
│  │          │←─│ SOCKS5   │  │    ↕      │ │
│  │ iptables │  │          │  │ Headscale │ │
│  │ NFQUEUE  │  │ :3128    │  │  (remote) │ │
│  └──────────┘  └──────────┘  └───────────┘ │
│                                             │
│  ┌──────────────────────────────────────┐   │
│  │         self-diagnosis daemon        │   │
│  │  dpi-detector → blockcheck → apply   │   │
│  │  периодический мониторинг блокировок │   │
│  └──────────────────────────────────────┘   │
│                                             │
│  eth0 (WAN) ── DHCP от роутера             │
│  eth1 (LAN) ── dev/management (опционально)│
└─────────────────────────────────────────────┘
```

### Ключевые технологии

- **zapret2** — основной antiDPI движок (NFQUEUE, nfqws2). Универсальный обход DPI для всех заблокированных сайтов
- **youtubeUnblock** (github.com/Waujito/youtubeUnblock) — kernel module для обхода DPI, заточенный под YouTube/Googlevideo
- **dpi-detector** (github.com/Runnin4ik/dpi-detector) — быстрый детект типов блокировок
- **Headscale** (headscale.net) — self-hosted Tailscale координатор, mesh VPN
- **Tailscale client** — на коробке и на устройствах пользователя
- **ByeDPI** (ciadpi) — отложен; потенциально для Android/Desktop приложения

### Самодиагностика (после каждого шага)

Два уровня проверки:
1. **Интернет** — `curl -s --max-time 5 http://cp.cloudflare.com/generate_204` (код 204)
2. **Обход DPI** — проверка доступа к заблокированным доменам (список доменов — внешний источник)

Порядок при настройке: dpi-detector (быстро) → анализ → zapret2 blockcheck (точечно, если нужно) → apply strategy → verify

### Dev-окружение

```
localhost ── WiFi ──→ XiaomiPhone (мобильный интернет)     # основной, не трогаем!
localhost ── ETH  ──→ [LAN] NanoPi R3S [WAN] ──→ Keenetic # тестовый
                      192.168.2.1  (g-vpn)       (USB-модем Yota)
```

- SSH к коробке: `ssh root@g-vpn` (192.168.2.1, через LAN-порт eth1)
- Два независимых канала: WiFi (dev) и ETH→коробка→Keenetic (тестовый)
- NetworkManager на ноутбуке: `never-default=yes` для eth, WiFi остаётся шлюзом
- zapret2-mcp (`ZAPRET2_MODE=ssh`, `ZAPRET2_SSH_HOST=root@g-vpn`)
- После factory reset: SSH host key сбрасывается → `ssh-keygen -R g-vpn` + `ssh-copy-id`
- Публичные DNS (1.1.1.1) заблокированы DPI — настраивать после zapret2

## AntiDPI движки

### zapret2 (bol-van/zapret2) — основной
- Userspace, NFQUEUE + nfqws
- Универсальный обход DPI для всех заблокированных сайтов (TLS SNI фрагментация, fake-пакеты)
- Обрабатывает только первые N пакетов соединения (connbytes) — низкая нагрузка на CPU
- На прошивке используется оригинальный zapret v1 (не zapret2), т.к. zapret2 (lua-версия)
  не работает на FriendlyWrt — `luaL_newstate()` возвращает NULL на статическом бинарнике

### youtubeUnblock (Waujito/youtubeUnblock) — специализированный для YouTube
- **Kernel module** внутри netfilter — обрабатывает каждый пакет без connbytes-лимита
- Заточен на YouTube/Googlevideo, быстрее и стабильнее zapret для видеостриминга
- Умеет работать с QUIC (UDP 443): `--udp-mode=drop` дропает QUIC, браузер фоллбэчит на TCP
- Дополняет zapret: youtubeUnblock — "снайперская" фиксация для YouTube, zapret — "ковровое" решение для остальных блокировок

### Почему два движка вместе (ADR)
YouTube агрессивно использует QUIC (UDP 443), который zapret обходит хуже, чем TCP.
youtubeUnblock работает на уровне ядра и эффективнее обрабатывает YouTube-трафик,
а zapret покрывает все остальные заблокированные ресурсы (Discord, Instagram и т.д.).

### ByeDPI (C, ciadpi) — отложен
- Потенциально для Android/Desktop приложения
- Android: VpnService + byedpi binary
- Desktop: subprocess + SOCKS transparent proxy

## Внутренняя документация
Контекст проекта находится в private-репо:
- `../greynet-management/docs/product-vision.md`

## Git
- **НИКОГДА** не делай `git add`, `git commit`, `git push` — коммиты делает только пользователь
- Не добавляй `Co-Authored-By` и любые упоминания Claude в коммитах

## Генерация кода
- Всегда используй `context7` для того, чтобы понять, как устроены используемые библиотеки
