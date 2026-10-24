package io.tcllive.overlay;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * TCL's firmware clears ACCESSIBILITY_ENABLED on boot, which leaves the overlay
 * service unbound until the user opens the app. This receiver writes the setting
 * back (needs WRITE_SECURE_SETTINGS, granted once over adb) and retries a few
 * times while the TV finishes booting.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "TCLP";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean scheduling = false;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        Log.i(TAG, "boot receiver action=" + action);
        final Context app = context.getApplicationContext();
        final PendingResult pending = goAsync();
        for (long delay : new long[]{0, 2500, 7000}) {
            MAIN.postDelayed(new Runnable() {
                @Override
                public void run() {
                    boolean ok = enable(app);
                    Log.i(TAG, "boot enable -> " + ok);
                }
            }, delay);
        }
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                pending.finish();
            }
        }, 12000);
    }

    /**
     * Re-enables the accessibility service. The writes are spaced out because
     * AccessibilityManagerService only rebinds when it observes the services
     * list change while accessibility is off; four writes in one tick coalesce
     * and are ignored.
     */
    static boolean enable(Context ctx) {
        final String comp = ctx.getPackageName() + "/" + OverlayService.class.getName();
        final ContentResolver cr = ctx.getContentResolver();
        try {
            String cur = Settings.Secure.getString(
                    cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (cur == null) cur = "";
            int enabled = Settings.Secure.getInt(
                    cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            boolean listed = false;
            List<String> others = new ArrayList<>();
            for (String s : cur.split(":")) {
                s = s.trim();
                if (s.isEmpty()) continue;
                if (s.equals(comp)) listed = true;
                else others.add(s);
            }
            if (enabled == 1 && listed) return true;

            StringBuilder sb = new StringBuilder();
            for (String s : others) {
                if (sb.length() > 0) sb.append(':');
                sb.append(s);
            }
            final String without = sb.toString();
            final String with = without.isEmpty() ? comp : without + ":" + comp;
            synchronized (BootReceiver.class) {
                if (scheduling) return true;
                scheduling = true;
            }
            step(0, new Runnable() {
                @Override
                public void run() {
                    Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0);
                }
            });
            step(350, new Runnable() {
                @Override
                public void run() {
                    Settings.Secure.putString(
                            cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, without);
                }
            });
            step(700, new Runnable() {
                @Override
                public void run() {
                    Settings.Secure.putString(
                            cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, with);
                }
            });
            step(1050, new Runnable() {
                @Override
                public void run() {
                    Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                    synchronized (BootReceiver.class) {
                        scheduling = false;
                    }
                    Log.i(TAG, "a11y sequence done");
                }
            });
            return true;
        } catch (Exception e) {
            Log.e(TAG, "a11y enable failed: " + e);
            return false;
        }
    }

    private static void step(long delay, final Runnable r) {
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    r.run();
                } catch (Exception e) {
                    Log.e(TAG, "a11y step failed: " + e);
                }
            }
        }, delay);
    }
}
