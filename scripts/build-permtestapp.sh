#!/usr/bin/env bash
# Builds the permission test app scripts/permissions-e2e.py installs through the console:
# com.dallycontrol.permtest — it declares location (fine, coarse, background), camera, microphone, storage/media,
# contacts and notifications, plus the special accesses (all files, overlay, write settings, unknown sources, battery),
# and on every start ASKS for each runtime permission it does not hold yet, exactly like a real app does. So:
#   - with the permissions fixed by the MDM it opens straight to its screen (no dialog), and
#   - it logs the result:  adb logcat -s PermTest   ->  "PermTest: missing=0 asked=0 FINE=1 CAMERA=1 ..."
# Needs the Android SDK (build-tools with d8, a platforms/android-NN) and a JDK; signs with the debug keystore.
#
#   scripts/build-permtestapp.sh [output-dir]      # default: scripts/testapp   ->  <dir>/permtest.apk
set -euo pipefail
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
JAR=$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar
OUT=${1:-$(cd "$(dirname "$0")" && pwd)/testapp}
KS=${DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
mkdir -p "$OUT" "$TMP/res/values" "$TMP/src/com/dallycontrol/permtest" "$TMP/classes" "$TMP/dex"

cat > "$TMP/res/values/strings.xml" <<'XML'
<resources><string name="app_name">DallyControl Permisos (prueba)</string></resources>
XML

cat > "$TMP/AndroidManifest.xml" <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.dallycontrol.permtest" android:versionCode="1" android:versionName="1.0">
  <uses-sdk android:minSdkVersion="23" android:targetSdkVersion="34" />
  <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
  <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
  <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
  <uses-permission android:name="android.permission.CAMERA" />
  <uses-permission android:name="android.permission.RECORD_AUDIO" />
  <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
  <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="29" />
  <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
  <uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
  <uses-permission android:name="android.permission.READ_CONTACTS" />
  <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
  <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
  <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
  <uses-permission android:name="android.permission.WRITE_SETTINGS" />
  <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
  <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
  <application android:label="@string/app_name" android:requestLegacyExternalStorage="true">
    <activity android:name=".MainActivity" android:exported="true">
      <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
      </intent-filter>
    </activity>
  </application>
</manifest>
XML

cat > "$TMP/src/com/dallycontrol/permtest/MainActivity.java" <<'JAVA'
package com.dallycontrol.permtest;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Asks for every runtime permission it declares and does not hold, like a real app; shows and logs the result. */
public class MainActivity extends Activity {
    private static final String BACKGROUND = "android.permission.ACCESS_BACKGROUND_LOCATION";
    private TextView text;
    private int asked;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        text = new TextView(this);
        text.setTextSize(16);
        text.setPadding(32, 96, 32, 32);
        setContentView(text);
        List<String> missing = missing(false);
        asked = missing.size();
        if (state == null && !missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), 1);
        report();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        // Android asks for background location on its own, after the foreground one.
        if (code == 1 && Build.VERSION.SDK_INT >= 29 && checkSelfPermission(BACKGROUND) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") == PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{BACKGROUND}, 2);
        }
        report();
    }

    @Override
    protected void onResume() {
        super.onResume();
        report();
    }

    private List<String> runtime() {
        List<String> out = new ArrayList<String>();
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_PERMISSIONS);
            if (info.requestedPermissions == null) return out;
            for (String p : info.requestedPermissions) {
                try {
                    PermissionInfo pi = getPackageManager().getPermissionInfo(p, 0);
                    if ((pi.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS) out.add(p);
                } catch (PackageManager.NameNotFoundException ignored) {
                    // not a permission of this Android version
                }
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // cannot happen for our own package
        }
        return out;
    }

    private List<String> missing(boolean withBackground) {
        List<String> out = new ArrayList<String>();
        for (String p : runtime()) {
            if (checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) continue;
            // Background location cannot be asked together with the foreground one (Android 11+).
            if (BACKGROUND.equals(p) && !withBackground && Build.VERSION.SDK_INT >= 30) continue;
            out.add(p);
        }
        return out;
    }

    private void report() {
        StringBuilder screen = new StringBuilder("DallyControl - permisos\n\n");
        StringBuilder log = new StringBuilder();
        int missing = 0;
        for (String p : runtime()) {
            boolean ok = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
            if (!ok) missing++;
            String name = p.substring(p.lastIndexOf('.') + 1);
            screen.append(ok ? "[SI] " : "[NO] ").append(name).append('\n');
            log.append(' ').append(name).append('=').append(ok ? 1 : 0);
        }
        text.setText(screen);
        Log.i("PermTest", "missing=" + missing + " asked=" + asked + log);
    }
}
JAVA

"$BT/aapt2" compile --dir "$TMP/res" -o "$TMP/res.zip"
"$BT/aapt2" link -o "$TMP/u.apk" --manifest "$TMP/AndroidManifest.xml" -I "$JAR" "$TMP/res.zip"
javac -source 8 -target 8 -nowarn -Xlint:-options -bootclasspath "$JAR" -d "$TMP/classes" "$TMP/src/com/dallycontrol/permtest/MainActivity.java"
"$BT/d8" --min-api 23 --lib "$JAR" --output "$TMP/dex" "$TMP"/classes/com/dallycontrol/permtest/*.class
(cd "$TMP/dex" && zip -q -j "$TMP/u.apk" classes.dex)
"$BT/zipalign" -f 4 "$TMP/u.apk" "$TMP/a.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --out "$OUT/permtest.apk" "$TMP/a.apk"
rm -f "$OUT/permtest.apk.idsig"
echo "$OUT/permtest.apk"
