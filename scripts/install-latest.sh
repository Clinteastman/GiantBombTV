#!/usr/bin/env bash
# Download the latest debug APK from GitHub Actions and install it via adb.
#
# Usage:
#   scripts/install-latest.sh                              # latest successful build on current branch
#   scripts/install-latest.sh -b master                    # specific branch
#   scripts/install-latest.sh -r v1.2.3                    # release tag (uses release APK)
#   scripts/install-latest.sh -t 192.168.1.42              # adb-connect to host (repeat -t for multiple)
#   scripts/install-latest.sh -t 192.168.1.42 -t shield    # connect to phone + Shield, install to both
#   scripts/install-latest.sh -s <serial>                  # only install to this device
#   scripts/install-latest.sh -u                           # uninstall first (fixes downgrade / signature errors)
#   scripts/install-latest.sh -l                           # also launch the app after install
#
# Installs to every ready adb device by default (e.g. phone + Shield together).
# Requires: gh (authenticated), adb on PATH.

set -euo pipefail

PKG="com.giantbomb.tv"
BRANCH=""
RELEASE_TAG=""
HOSTS=()
TARGET_SERIAL=""
LAUNCH=0
UNINSTALL=0

usage() { sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

while getopts ":b:r:t:s:luh" opt; do
  case "$opt" in
    b) BRANCH="$OPTARG" ;;
    r) RELEASE_TAG="$OPTARG" ;;
    t) HOSTS+=("$OPTARG") ;;
    s) TARGET_SERIAL="$OPTARG" ;;
    l) LAUNCH=1 ;;
    u) UNINSTALL=1 ;;
    h) usage 0 ;;
    *) usage 1 ;;
  esac
done

command -v gh  >/dev/null || { echo "gh CLI not found. Install: https://cli.github.com/"; exit 1; }
command -v adb >/dev/null || { echo "adb not found. Add /opt/android-platform-tools to PATH."; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "gh not authenticated. Run: gh auth login"; exit 1; }

WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

if [[ -n "$RELEASE_TAG" ]]; then
  echo "==> Downloading release $RELEASE_TAG"
  gh release download "$RELEASE_TAG" -p '*.apk' -D "$WORKDIR" --clobber
else
  BRANCH="${BRANCH:-$(git rev-parse --abbrev-ref HEAD)}"
  echo "==> Finding latest successful 'Build & Test' run on branch: $BRANCH"
  RUN_ID="$(gh run list \
    --workflow 'Build & Test' \
    --branch "$BRANCH" \
    --status success \
    --limit 1 \
    --json databaseId \
    --jq '.[0].databaseId')"
  [[ -n "$RUN_ID" && "$RUN_ID" != "null" ]] || { echo "No successful build found for branch '$BRANCH'."; exit 1; }
  echo "==> Downloading debug-apk from run $RUN_ID"
  gh run download "$RUN_ID" -n debug-apk -D "$WORKDIR"
fi

APK="$(find "$WORKDIR" -maxdepth 2 -name '*.apk' | head -1)"
[[ -n "$APK" ]] || { echo "No APK found in artifact."; exit 1; }
echo "==> Got: $(basename "$APK") ($(du -h "$APK" | cut -f1))"

for host in "${HOSTS[@]}"; do
  [[ "$host" == *:* ]] || host="$host:5555"
  echo "==> adb connect $host"
  adb connect "$host" >/dev/null || echo "    (connect failed, continuing)"
done

mapfile -t RAW_SERIALS < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
if [[ -n "$TARGET_SERIAL" ]]; then
  RAW_SERIALS=("$TARGET_SERIAL")
fi
[[ "${#RAW_SERIALS[@]}" -gt 0 ]] || { echo "No adb devices ready. Pair one or pass -t <host>."; exit 1; }

# Dedup: same physical device may appear via IP, mDNS, and USB at once.
declare -A SEEN_HW
SERIALS=()
for s in "${RAW_SERIALS[@]}"; do
  hw="$(adb -s "$s" shell getprop ro.serialno 2>/dev/null | tr -d '\r')"
  hw="${hw:-$s}"
  if [[ -z "${SEEN_HW[$hw]:-}" ]]; then
    SEEN_HW[$hw]=1
    SERIALS+=("$s")
  fi
done

describe() {
  local s="$1" chars model
  chars="$(adb -s "$s" shell getprop ro.build.characteristics 2>/dev/null | tr -d '\r')"
  model="$(adb -s "$s" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
  if [[ "$chars" == *tv* ]]; then echo "TV    $model"; else echo "phone $model"; fi
}

FAILED=0
for s in "${SERIALS[@]}"; do
  desc="$(describe "$s")"
  echo "==> [$s] $desc — installing"
  if [[ "$UNINSTALL" -eq 1 ]]; then
    adb -s "$s" uninstall "$PKG" >/dev/null 2>&1 && echo "    uninstalled existing $PKG" || true
  fi
  if adb -s "$s" install -r -d "$APK"; then
    if [[ "$LAUNCH" -eq 1 ]]; then
      adb -s "$s" shell monkey -p "$PKG" -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1 \
        || adb -s "$s" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 \
        || echo "    (launch failed)"
    fi
  else
    FAILED=$((FAILED + 1))
    echo "    install failed on $s"
  fi
done

[[ "$FAILED" -eq 0 ]] || { echo "==> $FAILED device(s) failed."; exit 1; }
echo "==> Done."
