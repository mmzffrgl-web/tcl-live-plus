package io.tcllive.overlay;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IPTV M3U listesi. Varsayılan kaynak emrecemal/my-iptv-playlist'tir.
 * Kullanıcı Ayarlar ekranından kendi M3U adresini girebilir (Prefs.iptvUrl).
 * GitHub "blob" sayfası verilirse otomatik olarak ham (raw) adrese çevrilir.
 * Liste internette yoksa son indirilen kopya files/iptv_tr.json içinden okunur.
 * Android'in yerleşik MediaPlayer'ı ile oynatılabilen HLS/MP4 akışları tutulur.
 */
final class IptvStore {
    private IptvStore() {
    }

    static final String URL_TR =
            "https://raw.githubusercontent.com/emrecemal/my-iptv-playlist/main/playlist.m3u";

    /** Ayarlarda özel adres varsa onu, yoksa varsayılanı döndürür. */
    static String currentUrl(Context ctx) {
        String custom = new Prefs(ctx).iptvUrl();
        return normalizeUrl(custom.isEmpty() ? URL_TR : custom);
    }

    /** GitHub blob/HTML adresini raw M3U adresine çevirir. */
    static String normalizeUrl(String u) {
        if (u == null) return "";
        String s = u.trim();
        if (s.startsWith("github.com/")) s = "https://" + s;
        if (s.contains("github.com/") && s.contains("/blob/")) {
            s = s.replace("https://github.com/", "https://raw.githubusercontent.com/");
            s = s.replace("http://github.com/", "https://raw.githubusercontent.com/");
            s = s.replace("/blob/", "/");
        }
        return s;
    }

    static final class Entry {
        final String name;
        final String url;
        final String group;
        final String logo;

        Entry(String name, String url, String group, String logo) {
            this.name = name;
            this.url = url;
            this.group = group;
            this.logo = logo;
        }
    }

    private static final Object LOCK = new Object();
    private static final List<Entry> ITEMS = new ArrayList<>();
    private static boolean loadedCache;
    private static volatile boolean fetching;

    static List<Entry> all() {
        synchronized (LOCK) {
            return new ArrayList<>(ITEMS);
        }
    }

    static int size() {
        synchronized (LOCK) {
            return ITEMS.size();
        }
    }

    static void loadCache(Context ctx) {
        synchronized (LOCK) {
            if (loadedCache) return;
            loadedCache = true;
        }
        File f = new File(ctx.getFilesDir(), "iptv_tr.json");
        if (!f.exists()) return;
        try {
            String body = readFile(f);
            JSONArray a = new JSONArray(body);
            List<Entry> list = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String u = o.optString("u", "");
                String n = o.optString("n", "");
                if (u.isEmpty() || n.isEmpty()) continue;
                list.add(new Entry(n, u, o.optString("g", ""), o.optString("l", "")));
            }
            synchronized (LOCK) {
                ITEMS.clear();
                ITEMS.addAll(list);
            }
            android.util.Log.i("TCLP", "iptv cache n=" + list.size());
        } catch (Exception e) {
            android.util.Log.w("TCLP", "iptv cache failed: " + e);
        }
    }

    interface Callback {
        void done(String error, int count);
    }

    /** Türkiye listesini indirir; sonuç arka planda callback ile bildirilir. */
    static void refresh(final Context ctx, final Callback cb) {
        synchronized (LOCK) {
            if (fetching) {
                if (cb != null) cb.done("zaten indiriliyor", ITEMS.size());
                return;
            }
            fetching = true;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                int n = 0;
                String src = currentUrl(ctx);
                try {
                    String body = http(src);
                    List<Entry> list = parseM3u(body);
                    if (list.isEmpty()) throw new IOException("liste boş");
                    synchronized (LOCK) {
                        ITEMS.clear();
                        ITEMS.addAll(list);
                    }
                    saveCache(ctx);
                    n = list.size();
                    android.util.Log.i("TCLP", "iptv fetched n=" + n + " bytes=" + body.length()
                            + " first=" + list.get(0).name + " src=" + src);
                } catch (Throwable t) {
                    err = t.getMessage() == null ? t.toString() : t.getMessage();
                    android.util.Log.w("TCLP", "iptv fetch failed: " + err);
                } finally {
                    fetching = false;
                }
                if (cb != null) cb.done(err, n);
            }
        }, "iptv-fetch").start();
    }

    // ------------------------------------------------------------------ parsing

    private static final Pattern ATTR =
            Pattern.compile("([a-zA-Z0-9_-]+)=\"([^\"]*)\"");

    static List<Entry> parseM3u(String text) {
        List<Entry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String[] lines = (text == null ? "" : text).split("\\r?\\n");
        String pending = null;
        String group = "";
        String logo = "";
        String extgrp = "";
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTINF:")) {
                int comma = nameComma(line);
                if (comma >= 0) {
                    pending = line.substring(comma + 1).trim();
                } else {
                    // Bozuk #EXTINF satırı (tırnak/koma eksik): son virgülden sonrasını ad say.
                    int last = line.lastIndexOf(',');
                    pending = last >= 0 ? line.substring(last + 1).trim() : "";
                }
                Matcher m = ATTR.matcher(line);
                group = "";
                logo = "";
                extgrp = "";
                while (m.find()) {
                    String k = m.group(1).toLowerCase(Locale.ROOT);
                    if ("group-title".equals(k)) group = m.group(2).trim();
                    else if ("tvg-logo".equals(k)) logo = m.group(2).trim();
                }
                if (pending.isEmpty()) pending = null;
            } else if (line.startsWith("#EXTGRP:")) {
                extgrp = line.substring(8).trim();
            } else if (line.startsWith("#")) {
                continue;
            } else if (pending != null) {
                String url = line;
                String lu = url.toLowerCase(Locale.ROOT);
                boolean playable = (lu.startsWith("http://") || lu.startsWith("https://"))
                        && !lu.contains(".mpd")
                        && !lu.contains("youtube.com")
                        && !lu.contains("youtu.be")
                        && !lu.contains("twitch.tv")
                        && !lu.contains("facebook.com");
                if (playable && seen.add(lu)) {
                    out.add(new Entry(pending, url, group.isEmpty() ? extgrp : group, logo));
                }
                pending = null;
                group = "";
                logo = "";
                extgrp = "";
            }
        }
        return out;
    }

    /** #EXTINF satırındaki, tırnak dışındaki ilk virgül = kanal adının başlangıcı. */
    private static int nameComma(String line) {
        boolean quote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') quote = !quote;
            else if (c == ',' && !quote) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ storage

    private static void saveCache(Context ctx) {
        try {
            List<Entry> list = all();
            JSONArray a = new JSONArray();
            for (Entry e : list) {
                JSONObject o = new JSONObject();
                o.put("n", e.name);
                o.put("u", e.url);
                o.put("g", e.group);
                o.put("l", e.logo);
                a.put(o);
            }
            File f = new File(ctx.getFilesDir(), "iptv_tr.json");
            FileOutputStream fo = new FileOutputStream(f);
            try {
                fo.write(a.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                fo.close();
            }
            android.util.Log.i("TCLP", "iptv cache saved n=" + a.length());
        } catch (Exception e) {
            android.util.Log.w("TCLP", "iptv cache save failed: " + e);
        }
    }

    private static String readFile(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    private static String http(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "TCL-Live-Plus/1.0");
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
            InputStream in = c.getInputStream();
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                int total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 4 * 1024 * 1024) break;
                    bos.write(buf, 0, n);
                }
                return new String(bos.toByteArray(), StandardCharsets.UTF_8);
            } finally {
                in.close();
            }
        } finally {
            c.disconnect();
        }
    }
}
