#!/usr/bin/env bash
# Download the pinned droidVNC-NG release (GPL-2.0, https://github.com/bk138/droidVNC-NG) used for remote
# view/control (ADR 0010) and verify its sha256. Prints the APK path.
#   scripts/fetch-droidvnc.sh [DEST_DIR]      then: scripts/adb-enroll.sh ... --remote --vnc-apk <path>
set -euo pipefail
VERSION=2.22.0
SHA256=$(awk '{print $1}' "$(dirname "$0")/droidvnc-ng.sha256")
DEST="${1:-.}"; OUT="$DEST/droidvnc-ng-$VERSION.apk"
mkdir -p "$DEST"
if [ ! -f "$OUT" ]; then
  curl -fsSL -o "$OUT.part" "https://github.com/bk138/droidVNC-NG/releases/download/v$VERSION/droidvnc-ng-$VERSION.apk"
  mv "$OUT.part" "$OUT"
fi
if command -v sha256sum >/dev/null 2>&1; then GOT=$(sha256sum "$OUT" | awk '{print $1}'); else GOT=$(shasum -a 256 "$OUT" | awk '{print $1}'); fi
[ "$GOT" = "$SHA256" ] || { echo "sha256 mismatch for $OUT: $GOT" >&2; rm -f "$OUT"; exit 1; }
echo "$OUT"
