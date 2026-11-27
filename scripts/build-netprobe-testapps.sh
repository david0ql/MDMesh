#!/usr/bin/env bash
# Builds the test apps scripts/mobile-data-e2e.py installs: three copies of the same tiny app (one HTTP request per
# launch, result in logcat; source in scripts/testapp/netprobe/src) under different package names, so a policy can
# block one, allow another and treat the third as essential:
#   netprobe-blocked.apk    com.dallycontrol.netprobe.blocked
#   netprobe-allowed.apk    com.dallycontrol.netprobe.allowed
#   netprobe-essential.apk  com.dallycontrol.netprobe.essential
# into the directory given (default scripts/testapp/netprobe/out). Needs the Android SDK (build-tools +
# platforms/android-35) and a JDK (javac, apksigner); signs with the debug keystore.
set -euo pipefail
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
JAR="$SDK/platforms/android-35/android.jar"
HERE=$(cd "$(dirname "$0")" && pwd)
SRC="$HERE/testapp/netprobe/src"
OUT=${1:-$HERE/testapp/netprobe/out}
KS=${DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
mkdir -p "$OUT" "$TMP/classes" "$TMP/dex"

javac -source 8 -target 8 -Xlint:-options -cp "$JAR" -d "$TMP/classes" "$SRC"/com/dallycontrol/netprobe/*.java
"$BT/d8" --min-api 23 --lib "$JAR" --output "$TMP/dex" $(find "$TMP/classes" -name '*.class')

for name in blocked allowed essential; do
  W="$TMP/$name"; mkdir -p "$W/res/values"
  cat > "$W/res/values/strings.xml" <<XML
<resources><string name="app_name">NetProbe $name</string></resources>
XML
  "$BT/aapt2" compile --dir "$W/res" -o "$W/res.zip"
  # Cleartext HTTP on purpose: the script's payload server is plain HTTP on the host (10.0.2.2 from an emulator).
  cat > "$W/AndroidManifest.xml" <<XML
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.dallycontrol.netprobe.$name" android:versionCode="1" android:versionName="1.0">
  <uses-sdk android:minSdkVersion="23" android:targetSdkVersion="35" />
  <uses-permission android:name="android.permission.INTERNET" />
  <application android:label="@string/app_name" android:usesCleartextTraffic="true">
    <activity android:name="com.dallycontrol.netprobe.ProbeActivity" android:exported="true" android:launchMode="singleTop">
      <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
      </intent-filter>
    </activity>
  </application>
</manifest>
XML
  "$BT/aapt2" link -o "$W/u.apk" --manifest "$W/AndroidManifest.xml" -I "$JAR" "$W/res.zip"
  (cd "$TMP/dex" && zip -q -j "$W/u.apk" classes.dex)
  "$BT/zipalign" -f 4 "$W/u.apk" "$W/a.apk"
  "$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --out "$OUT/netprobe-$name.apk" "$W/a.apk"
  rm -f "$OUT/netprobe-$name.apk.idsig"
  echo "$OUT/netprobe-$name.apk"
done
