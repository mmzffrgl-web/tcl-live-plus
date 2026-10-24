package io.tcllive.overlay;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Uydu/karasal EIT rehberi: TCL tuner'inin TvProvider program tablosundan okunur.
 * XMLTV'de bulunmayan kanallar icin yedek EPG kaynagidir.
 *
 * Not: TCL bu saglayicida "selection" parametresine izin vermiyor
 * ("Selection not allowed"), bu yuzden tablo bir kez tumuyle okunup
 * yalnizca bizim kanallarimiza ait satirlar bellekte tutulur.
 */
final class EitStore {
    static final EitStore I = new EitStore();

    private final Map<String, List<EpgStore.Prog>> byTifId = new HashMap<>();
    private volatile boolean loaded;
    private volatile boolean loading;
    private volatile boolean pending;

    private EitStore() {
    }

    /** Arka planda bir kez yukler; zaten yukluyorsa hemen doner. */
    void ensureLoaded(final Context ctx) {
        synchronized (this) {
            if (loading) {
                pending = true;
                return;
            }
            if (loaded) return;
            loading = true;
            pending = false;
        }
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                load(app);
            }
        }, "eit-load").start();
    }

    /** Kanal listesi degistiginde (import) yeniden okunmasi icin. */
    void invalidate() {
        synchronized (this) {
            loaded = false;
            byTifId.clear();
            if (loading) pending = true;
        }
    }

    boolean isLoaded() {
        return loaded;
    }

    private void load(Context ctx) {
        long t0 = System.currentTimeMillis();
        Map<String, List<EpgStore.Prog>> map = new HashMap<>();
        Set<String> want = new HashSet<>();
        for (Channel c : ChannelStore.all()) {
            if (c.tifId != null) want.add(c.tifId);
        }
        long now = System.currentTimeMillis();
        long min = now - 6L * 3600L * 1000L;
        long max = now + 72L * 3600L * 1000L;
        int total = 0;
        int kept = 0;
        Cursor p = null;
        try {
            ContentResolver cr = ctx.getContentResolver();
            p = cr.query(Uri.parse("content://android.media.tv/program"),
                    new String[]{"channel_id", "title",
                            "start_time_utc_millis", "end_time_utc_millis"},
                    null, null, null);
            if (p != null) {
                total = p.getCount();
                while (p.moveToNext()) {
                    String cid = p.getString(0);
                    if (cid == null || !want.contains(cid)) continue;
                    long start = p.getLong(2);
                    long stop = p.getLong(3);
                    if (start <= 0 || stop <= start) continue;
                    if (stop <= min || start >= max) continue;
                    String title = p.getString(1);
                    if (title == null) continue;
                    String tt = title.trim();
                    if (tt.isEmpty()) continue;
                    // EIT bazi kanallarda 1-2 dakikalik "Az sonra..." dolgu kayitlari
                    // tasiyor; rehberi bunlarla doldurmayalim.
                    String up = tt.toUpperCase(new Locale("tr", "TR"));
                    if (up.startsWith("AZ SONRA") || up.startsWith("YAKINDA")
                            || up.contains("PROGRAM BILGISI YOK")) continue;
                    if (stop - start < 60000L) continue;
                    List<EpgStore.Prog> l = map.get(cid);
                    if (l == null) {
                        l = new ArrayList<>();
                        map.put(cid, l);
                    }
                    l.add(new EpgStore.Prog(start, stop, tt));
                    kept++;
                }
            }
        } catch (Exception e) {
            android.util.Log.w("TCLP", "eit load failed: " + e);
        } finally {
            if (p != null) p.close();
        }
        for (List<EpgStore.Prog> l : map.values()) {
            Collections.sort(l, new Comparator<EpgStore.Prog>() {
                @Override
                public int compare(EpgStore.Prog a, EpgStore.Prog b) {
                    return Long.compare(a.start, b.start);
                }
            });
        }
        boolean again;
        synchronized (this) {
            byTifId.clear();
            byTifId.putAll(map);
            loaded = true;
            loading = false;
            again = pending;
            pending = false;
        }
        android.util.Log.i("TCLP", "eit loaded total=" + total + " kept=" + kept
                + " chans=" + map.size() + " ms=" + (System.currentTimeMillis() - t0));
        debugProbe(map);
        if (again) {
            invalidate();
            ensureLoaded(ctx);
        }
    }

    private void debugProbe(Map<String, List<EpgStore.Prog>> map) {
        int lines = 0;
        for (Channel c : ChannelStore.all()) {
            if (lines >= 10) break;
            String un = c.name == null ? "" : c.name.toUpperCase(Locale.ROOT);
            if (!(un.contains("KANAL D") || un.contains("BEIN"))) continue;
            List<EpgStore.Prog> l = c.tifId == null ? null : map.get(c.tifId);
            if (l == null) {
                android.util.Log.i("TCLP", "eit probe " + c.name + " tid=" + c.tifId + " n=0");
            } else {
                android.util.Log.i("TCLP", "eit probe " + c.name + " tid=" + c.tifId
                        + " n=" + l.size() + " first=" + l.get(0).start + " " + l.get(0).title
                        + " last=" + l.get(l.size() - 1).start);
            }
            lines++;
        }
    }

    /** [from, to) araligindaki EIT programlari (baslangica gore sirali). */
    synchronized List<EpgStore.Prog> window(Channel c, long from, long to) {
        if (!loaded || c == null || c.tifId == null) return Collections.emptyList();
        List<EpgStore.Prog> l = byTifId.get(c.tifId);
        if (l == null) return Collections.emptyList();
        List<EpgStore.Prog> out = new ArrayList<>();
        for (EpgStore.Prog p : l) {
            if (p.stop > from && p.start < to) out.add(p);
        }
        return out;
    }
}
