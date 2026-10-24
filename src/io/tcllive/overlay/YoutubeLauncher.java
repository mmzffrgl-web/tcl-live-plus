package io.tcllive.overlay;

import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

/** TCL kumandasındaki YouTube tuşu için ortak başlatıcı. */
final class YoutubeLauncher {

    /** Generic.kl: key 245 TCL_YOUTUBE */
    static final int KEYCODE_TCL_YOUTUBE = 245;

    private YoutubeLauncher() {
    }

    /**
     * IR kumanda 245/TCL_YOUTUBE gönderir; bazı Bluetooth kumandalarda
     * YouTube tuşu BUTTON_3 olarak eşlenir.
     */
    static boolean isYoutubeKey(KeyEvent event) {
        int code = event.getKeyCode();
        int scan = event.getScanCode();
        return scan == KEYCODE_TCL_YOUTUBE
                || code == KEYCODE_TCL_YOUTUBE
                || code == KeyEvent.KEYCODE_BUTTON_3;
    }

    static boolean open(Context ctx) {
        try {
            // YouTube TV'nin launcher activity'si ShellActivity; MainActivity
            // exported olmadığı için doğrudan başlatılamıyor.
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            i.setClassName("com.google.android.youtube.tv",
                    "com.google.android.apps.youtube.tv.activity.ShellActivity");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(i);
                android.util.Log.i("TCLP", "youtube launched");
                return true;
            } catch (Throwable t) {
                android.util.Log.w("TCLP", "youtube tv start failed: " + t);
            }

            Intent music = ctx.getPackageManager()
                    .getLaunchIntentForPackage("com.google.android.youtube.tvmusic");
            if (music != null) {
                music.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(music);
                android.util.Log.i("TCLP", "youtube music launched");
                return true;
            }
            return false;
        } catch (Throwable t) {
            android.util.Log.w("TCLP", "youtube launch failed: " + t);
            return false;
        }
    }
}
