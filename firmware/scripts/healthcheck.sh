#!/bin/sh
# healthcheck.sh — Проверка сетевой связности
#
# Запуск: sh healthcheck.sh
# Возвращает exit code 0 если все проверки пройдены.

PASS=0
FAIL=0

check() {
  name="$1"
  shift
  printf "  %-30s " "$name"
  if result=$("$@" 2>&1); then
    echo "[OK] $result"
    PASS=$((PASS + 1))
  else
    echo "[FAIL] $result"
    FAIL=$((FAIL + 1))
  fi
}

echo "=== Greynet: Health Check ==="
echo ""

# 1. Интернет-связность (Cloudflare captive portal check)
check "Internet (HTTP 204)" \
  sh -c 'code=$(curl -s --max-time 5 -o /dev/null -w "%{http_code}" http://cp.cloudflare.com/generate_204); [ "$code" = "204" ] && echo "$code" || { echo "$code"; false; }'

# 2. DNS resolution
check "DNS resolution" \
  sh -c 'nslookup google.com 127.0.0.1 >/dev/null 2>&1 && echo "ok" || { nslookup google.com >/dev/null 2>&1 && echo "ok (system dns)" || { echo "failed"; false; }; }'

# 3. WAN interface status (OpenWrt-specific)
if command -v ifstatus >/dev/null 2>&1; then
  check "WAN interface (eth0)" \
    sh -c 'addr=$(ifstatus wan 2>/dev/null | jsonfilter -e "@[\"ipv4-address\"][0].address" 2>/dev/null); [ -n "$addr" ] && echo "$addr" || { echo "no IP"; false; }'
fi

echo ""
echo "=== Результат: $PASS passed, $FAIL failed ==="

[ "$FAIL" -eq 0 ] && exit 0 || exit 1
