import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import android.os.PersistableBundle;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Iterator;

/**
 * Test helper (emulator only): starts Android's managed provisioning with the SAME payload a QR carries.
 *
 * The setup wizard's QR scanner turns the QR's JSON into an
 * ACTION_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE intent; `am start` cannot build that intent because the admin
 * extras travel as a PersistableBundle. This class does the same conversion and starts the activity as root
 * (userdebug images only), so everything after the camera - Wi-Fi, clock, download, checksum, install, Device Owner,
 * the DPC's provisioning callbacks - runs exactly as it does after a real scan.
 *
 *   adb root
 *   adb push provision.dex /data/local/tmp/ && adb push payload.json /data/local/tmp/
 *   adb shell CLASSPATH=/data/local/tmp/provision.dex app_process / ProvisionIntent /data/local/tmp/payload.json
 *
 * Built by scripts/qr-matrix/run.sh (javac + d8). Not shipped in any APK.
 */
public final class ProvisionIntent {
    private static final String ACTION = "android.app.action.PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE";
    private static final String P = "android.app.extra.";

    public static void main(String[] args) throws Exception {
        if (args[0].equals("--reset-setup")) {
            resetSetup();
            return;
        }
        JSONObject json = new JSONObject(read(args[0]));
        Intent intent = new Intent(ACTION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        for (Iterator<String> it = json.keys(); it.hasNext(); ) {
            String key = it.next();
            Object value = json.get(key);
            if (key.equals(P + "PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME")) {
                intent.putExtra(key, ComponentName.unflattenFromString(String.valueOf(value)));
            } else if (key.equals(P + "PROVISIONING_ADMIN_EXTRAS_BUNDLE")) {
                PersistableBundle bundle = new PersistableBundle();
                JSONObject o = (JSONObject) value;
                for (Iterator<String> k = o.keys(); k.hasNext(); ) {
                    String name = k.next();
                    bundle.putString(name, String.valueOf(o.get(name)));
                }
                intent.putExtra(key, bundle);
            } else if (key.equals(P + "PROVISIONING_LOCAL_TIME")) {
                intent.putExtra(key, ((Number) value).longValue());
            } else if (value instanceof Boolean) {
                intent.putExtra(key, ((Boolean) value).booleanValue());
            } else if (value instanceof Integer) {
                intent.putExtra(key, ((Integer) value).intValue());
            } else {
                intent.putExtra(key, String.valueOf(value));
            }
        }
        // What the setup wizard adds after a QR scan (API 29+): PROVISIONING_TRIGGER_QR_CODE.
        intent.putExtra(P + "PROVISIONING_TRIGGER", 2);
        System.out.println("starting " + intent + " extras=" + intent.getExtras().keySet());
        System.out.println("result=" + start(intent));
    }

    /**
     * DevicePolicyManagerService keeps its own "the user finished the setup wizard" flag and refuses Device-Owner
     * provisioning once it is set. forceUpdateUserSetupComplete (a platform test API, Android 8+) makes it read the
     * setting again, so an emulator that booted as "set up" goes back to the state of a phone in its wizard - with
     * no reboot, and with its network left as it was.
     */
    private static void resetSetup() throws Exception {
        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
                .invoke(null, "device_policy");
        Object dpm = Class.forName("android.app.admin.IDevicePolicyManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        for (Method m : dpm.getClass().getMethods()) {
            if (m.getName().equals("forceUpdateUserSetupComplete")) {
                Object[] values = new Object[m.getParameterTypes().length];
                for (int i = 0; i < values.length; i++) {
                    values[i] = 0;
                }
                m.invoke(dpm, values);
                System.out.println("setup flag re-read");
                return;
            }
        }
        System.out.println("no forceUpdateUserSetupComplete on this release");
    }

    /** IActivityManager.startActivityAsUser through reflection: its signature differs across Android releases. */
    private static Object start(Intent intent) throws Exception {
        Object am;
        try {
            am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        } catch (NoSuchMethodException e) {
            am = Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
        }
        Method best = null;
        for (Method m : am.getClass().getMethods()) {
            if (m.getName().equals("startActivityAsUser") && (best == null
                    || m.getParameterTypes().length < best.getParameterTypes().length)) {
                best = m;
            }
        }
        if (best == null) {
            throw new IllegalStateException("no startActivityAsUser");
        }
        Class<?>[] types = best.getParameterTypes();
        Object[] values = new Object[types.length];
        boolean packageSet = false;
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            if (t == Intent.class) {
                values[i] = intent;
            } else if (t == String.class && !packageSet) {
                values[i] = "com.android.shell";
                packageSet = true;
            } else if (t == int.class) {
                values[i] = 0;
            } else if (t == boolean.class) {
                values[i] = false;
            } else if (t == IBinder.class) {
                values[i] = null;
            } else {
                values[i] = null;
            }
        }
        return best.invoke(am, values);
    }

    private static String read(String path) throws Exception {
        try (InputStream in = new FileInputStream(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        }
    }
}
