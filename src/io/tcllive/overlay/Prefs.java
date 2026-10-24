package io.tcllive.overlay;

import android.content.Context;
import android.content.SharedPreferences;

/** Small typed wrapper around SharedPreferences. */
final class Prefs {
    private static final String FILE = "tcllive";

    static final String DEFAULT_EPG =
            "https://epgshare01.online/epgshare01/epg_ripper_TR1.xml.gz";

    private final SharedPreferences sp;

    Prefs(Context ctx) {
        sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    String epgUrl() {
        String v = sp.getString("epg_url", DEFAULT_EPG);
        return v == null || v.trim().isEmpty() ? DEFAULT_EPG : v.trim();
    }

    void setEpgUrl(String v) {
        sp.edit().putString("epg_url", v == null ? "" : v.trim()).apply();
    }

    /** TCL tuner mode extra: dvbs (satellite), dvbt, dvbc, atsc, isdb. */
    String tunerMode() {
        String v = sp.getString("tuner_mode", "dvbs");
        return v == null || v.trim().isEmpty() ? "dvbs" : v.trim();
    }

    void setTunerMode(String v) {
        sp.edit().putString("tuner_mode", v == null ? "" : v.trim()).apply();
    }

    /** Optional internet channel order (JSON array or one name per line). */
    String tkgsUrl() {
        String v = sp.getString("tkgs_url", "");
        return v == null ? "" : v.trim();
    }

    void setTkgsUrl(String v) {
        sp.edit().putString("tkgs_url", v == null ? "" : v.trim()).apply();
    }

    /** Kullanıcının girdiği IPTV M3U adresi; boşsa varsayılan kullanılır. */
    String iptvUrl() {
        String v = sp.getString("iptv_url", "");
        return v == null ? "" : v.trim();
    }

    void setIptvUrl(String v) {
        sp.edit().putString("iptv_url", v == null ? "" : v.trim()).apply();
    }

    long tkgsFetchedAt() {
        return sp.getLong("tkgs_fetched_at", 0L);
    }

    void setTkgsFetchedAt(long ts) {
        sp.edit().putLong("tkgs_fetched_at", ts).apply();
    }

    boolean importMode() {
        return sp.getBoolean("import_mode", false);
    }

    void setImportMode(boolean on) {
        sp.edit().putBoolean("import_mode", on).apply();
    }

    boolean interceptEnabled() {
        return sp.getBoolean("intercept", true);
    }

    void setInterceptEnabled(boolean on) {
        sp.edit().putBoolean("intercept", on).apply();
    }

    long epgFetchedAt() {
        return sp.getLong("epg_fetched_at", 0L);
    }

    void setEpgFetchedAt(long ts) {
        sp.edit().putLong("epg_fetched_at", ts).apply();
    }

    String lastChannel() {
        String v = sp.getString("last_channel", null);
        return v;
    }

    void setLastChannel(String number) {
        sp.edit().putString("last_channel", number).apply();
    }
}
