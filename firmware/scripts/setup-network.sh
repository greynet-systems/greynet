#!/bin/sh
# setup-network.sh — Настройка DNS на коробке (NanoPi R3S)
#
# Коробка подключается одним кабелем (eth0) в LAN-порт роутера.
# Дефолтный OpenWrt br-lan + DHCP уже работает — интернет есть.
# Этот скрипт только настраивает DNS на публичные резолверы.
#
# Запуск:
#   ssh root@g-vpn 'sh -s' < setup-network.sh
#
# Предварительно: ssh-copy-id root@g-vpn

set -e

echo "=== Greynet: Network Setup ==="

# --- DNS: публичные резолверы вместо провайдерских ---
echo ">>> Настройка DNS (1.1.1.1, 8.8.8.8)..."
uci set dhcp.@dnsmasq[0].noresolv='1'
uci add_list dhcp.@dnsmasq[0].server='1.1.1.1'
uci add_list dhcp.@dnsmasq[0].server='8.8.8.8'
uci commit dhcp

/etc/init.d/dnsmasq restart

echo ""
echo "=== DNS настроен ==="
echo "Проверка: nslookup google.com"
nslookup google.com 127.0.0.1 2>/dev/null && echo "OK" || echo "FAIL"
