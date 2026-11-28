#!/usr/bin/env bash
# Builds the tiny "work app" scripts/kiosk-escape-test.py puts in the kiosk (com.dallycontrol.escapetest) into
# scripts/testapp/escapetest.apk. Its only screen fires the intent it is told to — the way an allowed app would try
# to open the system Settings from inside the kiosk:
#   am start -n com.dallycontrol.escapetest/.Main --es action android.settings.SETTINGS
# and logs the outcome (logcat tag EscapeTest). Needs the Android SDK (build-tools + platforms/android-35) and a JDK;
# signs with the debug keystore.
set -euo pipefail
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
JAR="$SDK/platforms/android-35/android.jar"
OUT=$(cd "$(dirname "$0")" && pwd)/testapp
KS=${DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
mkdir -p "$OUT" "$TMP/res/values" "$TMP/src/com/dallycontrol/escapetest" "$TMP/classes" "$TMP/dex"
cat > "$TMP/res/values/strings.xml" <<'XML'
<resources><string name="app_name">Escape Test</string></resources>
XML
cat > "$TMP/AndroidManifest.xml" <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.dallycontrol.escapetest" android:versionCode="1" android:versionName="1.0">
  <uses-sdk android:minSdkVersion="23" android:targetSdkVersion="35" />
  <application android:label="@string/app_name">
    <activity android:name=".Main" android:exported="true" android:launchMode="singleTop">
      <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
      </intent-filter>
    </activity>
  </application>
</manifest>
XML
cat > "$TMP/src/com/dallycontrol/escapetest/Main.java" <<'JAVA'
package com.dallycontrol.escapetest;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

/** A stand-in for a work app: shows a label and, when asked, tries to open another screen by intent. */
public class Main extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        TextView t = new TextView(this);
        t.setText("Escape Test");
        t.setTextSize(28f);
        setContentView(t);
        fire(getIntent());
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        fire(i);
    }

    private void fire(Intent in) {
        String action = in == null ? null : in.getStringExtra("action");
        if (action == null) return;
        try {
            Intent out = new Intent(action);
            String pkg = in.getStringExtra("pkg");
            if (pkg != null) out.setPackage(pkg);
            if (in.getBooleanExtra("newTask", false)) out.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(out);
            Log.i("EscapeTest", "start " + action + ": sent");
        } catch (Exception e) {
            Log.i("EscapeTest", "start " + action + ": refused " + e);
        }
    }
}
JAVA
"$BT/aapt2" compile --dir "$TMP/res" -o "$TMP/res.zip"
"$BT/aapt2" link -o "$TMP/u.apk" --manifest "$TMP/AndroidManifest.xml" -I "$JAR" "$TMP/res.zip"
javac -source 11 -target 11 -Xlint:-options -cp "$JAR" -d "$TMP/classes" "$TMP/src/com/dallycontrol/escapetest/Main.java"
"$BT/d8" --lib "$JAR" --min-api 23 --output "$TMP/dex" "$TMP"/classes/com/dallycontrol/escapetest/*.class
(cd "$TMP/dex" && zip -q -j "$TMP/u.apk" classes.dex)
"$BT/zipalign" -f 4 "$TMP/u.apk" "$TMP/a.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --out "$OUT/escapetest.apk" "$TMP/a.apk"
rm -f "$OUT/escapetest.apk.idsig"
echo "$OUT/escapetest.apk"
