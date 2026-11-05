#!/usr/bin/env bash
# Bundles rule sets and a snapshot of the server lists into the APK, so the
# first connect works offline / with GitHub blocked. Keep the subscription
# list in sync with app/src/main/java/app/butone/vpn/data/Sources.kt.
set -euo pipefail
cd "$(dirname "$0")/.."
A=app/src/main/assets
mkdir -p "$A/rules" "$A/subs"

RULES=https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/meta/geo
curl -fsSL --retry 3 -o "$A/rules/category-ru.mrs" "$RULES/geosite/category-ru.mrs"
curl -fsSL --retry 3 -o "$A/rules/geoip-ru.mrs"    "$RULES/geoip/ru.mrs"

IG=https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main
fetch() {  # id path
  curl -fsSL --retry 3 -o "$A/subs/$1.yaml" "$IG/$2"
  grep -q '^proxies:' "$A/subs/$1.yaml" || { echo "bad list: $1" >&2; exit 1; }
  echo "$1: $(grep -c '^  - name:\|^- name:' "$A/subs/$1.yaml" || true) entries"
}
fetch ru_mobile "Export/Clash/RU_DIRECT/BLACK_VLESS_RUS_mobile_clash_rudirect.yaml"
fetch ru_other  "Export/Clash/RU_DIRECT/BLACK_SS%2BAll_RUS_clash_rudirect.yaml"
fetch ru_white  "Export/Clash/GLOBAL/WHITE-CIDR-RU-checked-clash-global.yaml"
