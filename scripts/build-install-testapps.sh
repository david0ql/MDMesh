#!/usr/bin/env bash
# Builds the code-less test APKs scripts/app-install-e2e.py uploads and installs (package com.dallycontrol.bigapp)
# into the directory given (default scripts/testapp/install):
#   bigapp-v1.apk            versionCode 1, padded with an incompressible asset to BIG_MB megabytes (default 120: over
#                            the 100 MB a request may carry through the tunnel, so it must be uploaded in parts)
#   bigapp-v2.apk            versionCode 2, same signing key (a silent update)
#   bigapp-v3-otherkey.apk   versionCode 3, signed with ANOTHER key (Android refuses it over v1/v2)
#   bigapp-corrupt.apk       v2 cut in half (not a readable APK)
# Needs the Android SDK (build-tools + platforms/android-35) and a JDK (apksigner, keytool).
set -euo pipefail
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
JAR="$SDK/platforms/android-35/android.jar"
OUT=${1:-$(cd "$(dirname "$0")" && pwd)/testapp/install}
BIG_MB=${BIG_MB:-120}
KS=${DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
mkdir -p "$OUT" "$TMP/res/values" "$TMP/assets" "$TMP/noassets"
cat > "$TMP/res/values/strings.xml" <<'XML'
<resources><string name="app_name">DallyControl Big Test App</string></resources>
XML
"$BT/aapt2" compile --dir "$TMP/res" -o "$TMP/res.zip"
# A second key, made on the spot: what "the developer lost the keystore / signed with the debug key" looks like.
OTHER="$TMP/other.keystore"
keytool -genkeypair -keystore "$OTHER" -storepass android -keypass android -alias other -keyalg RSA -keysize 2048 \
  -validity 3650 -dname "CN=Otra Firma, O=DallyControl Test" >/dev/null 2>&1
# Random bytes do not compress: the APK really weighs BIG_MB.
dd if=/dev/urandom of="$TMP/assets/filler.bin" bs=1048576 count="$BIG_MB" 2>/dev/null

build() { # <versionCode> <assets dir> <keystore> <alias> <output>
  cat > "$TMP/AndroidManifest.xml" <<XML
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.dallycontrol.bigapp" android:versionCode="$1" android:versionName="1.0.$1">
  <uses-sdk android:minSdkVersion="21" android:targetSdkVersion="35" />
  <application android:label="@string/app_name" android:hasCode="false" />
</manifest>
XML
  "$BT/aapt2" link -o "$TMP/u.apk" --manifest "$TMP/AndroidManifest.xml" -I "$JAR" -A "$2" "$TMP/res.zip"
  "$BT/zipalign" -f 4 "$TMP/u.apk" "$TMP/a.apk"
  "$BT/apksigner" sign --ks "$3" --ks-key-alias "$4" --ks-pass pass:android --key-pass pass:android --out "$5" "$TMP/a.apk"
  rm -f "$5.idsig"
  echo "$5"
}
build 1 "$TMP/assets" "$KS" androiddebugkey "$OUT/bigapp-v1.apk"
build 2 "$TMP/noassets" "$KS" androiddebugkey "$OUT/bigapp-v2.apk"
build 3 "$TMP/noassets" "$OTHER" other "$OUT/bigapp-v3-otherkey.apk"
SIZE=$(wc -c < "$OUT/bigapp-v2.apk")
head -c $((SIZE / 2)) "$OUT/bigapp-v2.apk" > "$OUT/bigapp-corrupt.apk"
echo "$OUT/bigapp-corrupt.apk"
