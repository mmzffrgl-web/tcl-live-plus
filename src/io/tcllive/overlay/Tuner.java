package io.tcllive.overlay;

import android.content.Context;
import android.content.Intent;

/** Turns the TCL live TV activity to a channel without root. */
final class Tuner {
    private Tuner() {
    }

    /**
     * TCL's exported TVActivity resolves ACTION_VIEW + displayChannelNumber into a
     * FactoryIntent and calls ChannelModel.getChannelByNumber() + tuneChannel().
     */
    static void tune(Context ctx, String displayNumber) {
        Prefs p = new Prefs(ctx);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setClassName("com.tcl.tv", "com.tcl.player.TVActivity");
        i.putExtra("displayChannelNumber", displayNumber);
        i.putExtra("tuner_mode", p.tunerMode());
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (Exception e) {
            try {
                i.setClassName("com.tcl.tv", "com.tcl.tv.TVActivity");
                ctx.startActivity(i);
            } catch (Exception ignored) {
            }
        }
    }
}
