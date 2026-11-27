package com.dallycontrol.netprobe;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Test app for scripts/mobile-data-e2e.py: makes one HTTP request per launch and logs how it went.
 *
 *   adb shell am start -n <package>/com.dallycontrol.netprobe.ProbeActivity --es url http://10.0.2.2:8214/blob?kb=64 --es id 7
 *   adb logcat -d -s NetProbe   ->   RESULT pkg=<package> id=7 ok=true code=200 bytes=65536 ms=41
 *                                    RESULT pkg=<package> id=7 ok=false error=SocketTimeoutException:… ms=6003
 *
 * It runs in the foreground on purpose: the mobile-data restriction must hold for an app the person is using.
 */
public class ProbeActivity extends Activity {
    private static final String TAG = "NetProbe";
    private TextView view;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        view = new TextView(this);
        view.setTextSize(16);
        view.setPadding(32, 96, 32, 32);
        setContentView(view);
        probe(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        probe(intent);
    }

    private void probe(Intent intent) {
        final String url = intent.getStringExtra("url");
        final String id = String.valueOf(intent.getStringExtra("id"));
        final int timeout = intent.getIntExtra("timeoutMs", 6000);
        if (url == null) {
            view.setText(getPackageName() + "\nsin URL");
            return;
        }
        view.setText(getPackageName() + "\n" + url + "\n…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String line = fetch(url, id, timeout);
                Log.i(TAG, line);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        view.setText(getPackageName() + "\n" + url + "\n" + line);
                    }
                });
            }
        }, "probe").start();
    }

    private String fetch(String url, String id, int timeout) {
        long t0 = System.currentTimeMillis();
        String head = "RESULT pkg=" + getPackageName() + " id=" + id;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(timeout);
            c.setReadTimeout(timeout);
            c.setUseCaches(false);
            c.setRequestProperty("Connection", "close");
            int code = c.getResponseCode();
            long n = 0;
            InputStream in = c.getInputStream();
            byte[] buf = new byte[16384];
            for (int r; (r = in.read(buf)) > 0; ) n += r;
            in.close();
            return head + " ok=true code=" + code + " bytes=" + n + " ms=" + (System.currentTimeMillis() - t0);
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage()).replace(' ', '_');
            return head + " ok=false error=" + e.getClass().getSimpleName() + ":" + msg + " ms=" + (System.currentTimeMillis() - t0);
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
