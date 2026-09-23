#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(pwd -P)"
case "$repo_dir" in
  /mnt/*)
    echo "For fast WSL2 builds, clone the repository inside the Linux filesystem (for example ~/src/GiantBombTV), not under /mnt/c." >&2
    exit 2
    ;;
esac

if ! command -v java >/dev/null 2>&1; then
  echo "Java is missing. Install JDK 17 before building." >&2
  exit 2
fi

android_sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$android_sdk" || ! -d "$android_sdk/platforms/android-36" ]]; then
  echo "Set ANDROID_SDK_ROOT to a Linux Android SDK containing platform android-36." >&2
  exit 2
fi

if (($# == 0)); then
  set -- testDebugUnitTest lintDebug assembleDebug
fi

exec ./gradlew --build-cache "$@"
