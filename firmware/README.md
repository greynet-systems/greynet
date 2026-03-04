# Firmware: Умная коробка (NanoPi R3S)

Plug-and-play антицензурный прокси-узел на базе FriendlyWrt (OpenWrt).

## Железо

- **NanoPi R3S** (RockChip RK3566, aarch64)
- 2× Ethernet: WAN (eth0), LAN (eth1)
- FriendlyWrt (OpenWrt), Linux 6.1.57

## Схема подключения

```
Домашний роутер [LAN] ──→ [WAN eth0] NanoPi R3S
                                      │
                              zapret2 (antiDPI)
                              HTTP/SOCKS прокси
                              Tailscale client
                                      │
                              Headscale mesh-сеть
                                      │
                          Устройства пользователя
                           (через Tailscale VPN)
```

Один кабель от роутера в порт **WAN** коробки — всё.
Пользовательские устройства подключаются через **mesh-сеть** (Headscale/Tailscale), а не физически.

## Быстрый старт

### 1. Factory reset (если нужно)

```bash
ssh root@g-vpn 'sh -s' < firmware/scripts/bootstrap.sh
```

После ребута:
- Пароль root = пустой
- SSH host key сброшен — нужно очистить `known_hosts` и заново `ssh-copy-id`

### 2. Физическое подключение

Воткнуть кабель от LAN-порта роутера в порт **WAN** на NanoPi R3S (ближе к USB-C).

Дефолтная конфигурация FriendlyWrt:
- **eth0 (WAN)** — DHCP client, получает IP от роутера
- **eth1 (LAN)** — br-lan, static 192.168.2.1/24

### 3. SSH-доступ

```bash
# Если коробка подключена через LAN-порт (eth1 → ноутбук):
ssh root@192.168.2.1

# Или через /etc/hosts:
#   192.168.2.1  g-vpn
ssh root@g-vpn

# Добавить SSH-ключ:
ssh-copy-id root@g-vpn
```

### 4. Проверка интернета

```bash
ssh root@g-vpn 'sh -s' < firmware/scripts/healthcheck.sh
```

Ожидаемый результат:
```
=== Greynet: Health Check ===
  Internet (HTTP 204)            [OK] 204
  DNS resolution                 [OK] ok
  WAN interface (eth0)           [OK] 192.168.1.x
=== Результат: 3 passed, 0 failed ===
```

### 5. Настройка DNS (после установки zapret2)

```bash
ssh root@g-vpn 'sh -s' < firmware/scripts/setup-network.sh
```

> **Важно:** Публичные DNS (1.1.1.1, 8.8.8.8) могут быть заблокированы DPI.
> Настраивать DNS на публичные резолверы нужно **после** установки zapret2.

## Скрипты

| Скрипт | Назначение |
|--------|------------|
| `scripts/bootstrap.sh` | Factory reset OpenWrt |
| `scripts/setup-network.sh` | Настройка DNS (публичные резолверы) |
| `scripts/healthcheck.sh` | Проверка: интернет, DNS, WAN IP |

## Dev-окружение

```
localhost ── WiFi ──→ XiaomiPhone (мобильный интернет)     # основной, не трогаем
localhost ── ETH  ──→ [LAN] NanoPi R3S [WAN] ──→ Keenetic # тестовый
                      192.168.2.1               192.168.1.x
```

- WiFi — основной канал интернета на ноутбуке
- ETH — подключение к коробке через LAN-порт (192.168.2.1)
- NetworkManager на ноутбуке: `never-default=yes` для eth, чтобы WiFi оставался шлюзом
- zapret2-mcp: `ZAPRET2_MODE=ssh`, `ZAPRET2_SSH_HOST=root@g-vpn`

## Roadmap

См. [DESIGN.md](DESIGN.md) — User Stories в порядке разработки.

- [x] **US-1:** Коробка получает интернет
- [ ] **US-2:** Коробка определяет блокировки
- [ ] **US-3:** Коробка обходит блокировки
- [ ] **US-4:** Коробка раздаёт прокси
- [ ] **US-5:** Коробка подключается к mesh-сети
- [ ] **US-6:** Пользователь подключается по QR-коду
- [ ] **US-7:** Коробка мониторит и адаптируется
