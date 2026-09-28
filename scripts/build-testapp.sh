#!/usr/bin/env bash
# Builds the two code-less test APKs scripts/device-func-test.py installs and upgrades
# (com.dallycontrol.testapp, versionCode 1 and 2) into scripts/testapp/. Needs the Android SDK
# (build-tools + platforms/android-35) and a JDK for apksigner; signs with the debug keystore.
set -euo pipefail
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
JAR="$SDK/platforms/android-35/android.jar"
OUT=$(cd "$(dirname "$0")" && pwd)/testapp
KS=${DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
mkdir -p "$OUT"
# The label is a string resource: the server's APK parser rejects an empty resource table.
mkdir -p "$TMP/res/values"
cat > "$TMP/res/values/strings.xml" <<'XML'
<resources><string name="app_name">DallyControl Test App</string></resources>
XML
"$BT/aapt2" compile --dir "$TMP/res" -o "$TMP/res.zip"
for v in 1 2; do
  cat > "$TMP/AndroidManifest.xml" <<XML
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.dallycontrol.testapp" android:versionCode="$v" android:versionName="1.0.$v">
  <uses-sdk android:minSdkVersion="21" android:targetSdkVersion="35" />
  <application android:label="@string/app_name" android:hasCode="false" />
</manifest>
XML
  "$BT/aapt2" link -o "$TMP/u.apk" --manifest "$TMP/AndroidManifest.xml" -I "$JAR" "$TMP/res.zip"
  "$BT/zipalign" -f 4 "$TMP/u.apk" "$TMP/a.apk"
  "$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --out "$OUT/testapp-v$v.apk" "$TMP/a.apk"
  echo "$OUT/testapp-v$v.apk"
done
