package io.tcllive.overlay;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.GZIPInputStream;

/** XMLTV guide: download, parse, cache, lookup. */
final class EpgStore {
    static final EpgStore I = new EpgStore();

    static final class Prog {
        final long start;
        final long stop;
        final String title;

        Prog(long start, long stop, String title) {
            this.start = start;
            this.stop = stop;
            this.title = title;
        }
    }

    interface Callback {
        void done(String error, int programmeCount);
    }

    private final Map<String, List<Prog>> byId = new HashMap<>();
    private final Map<String, String> nameToId = new HashMap<>();
    private final Map<String, String> resolved = new HashMap<>();
    private volatile boolean loading;
    private final Handler main = new Handler(Looper.getMainLooper());

    private EpgStore() {
    }

    boolean isLoading() {
        return loading;
    }

    void loadCache(Context ctx) {
        File f = new File(ctx.getFilesDir(), "epg.json");
        if (!f.exists()) return;
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
            JSONObject root = new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
            JSONObject ch = root.optJSONObject("channels");
            JSONObject names = root.optJSONObject("names");
            synchronized (this) {
                byId.clear();
                nameToId.clear();
                resolved.clear();
                if (ch != null) {
                    for (java.util.Iterator<String> it = ch.keys(); it.hasNext(); ) {
                        String id = it.next();
                        JSONArray arr = ch.optJSONArray(id);
                        if (arr == null) continue;
                        List<Prog> list = new ArrayList<>();
                        for (int i = 0; i < arr.length(); i++) {
                            JSONArray p = arr.optJSONArray(i);
                            if (p == null || p.length() < 3) continue;
                            list.add(new Prog(p.optLong(0), p.optLong(1), p.optString(2)));
                        }
                        byId.put(id, list);
                    }
                }
                if (names != null) {
                    for (java.util.Iterator<String> it = names.keys(); it.hasNext(); ) {
                        String n = it.next();
                        nameToId.put(n, names.optString(n));
                    }
                }
                // Programi olmayan kanal kimlikleri (or. beIN.SPORTS.1.tr) isim
                // eslesmesini caliyor; bunlari birakinca onek eslesmesi gercek
                // programa sahip kimligi buluyor.
                for (java.util.Iterator<Map.Entry<String, String>> it =
                        nameToId.entrySet().iterator(); it.hasNext(); ) {
                    if (!byId.containsKey(it.next().getValue())) it.remove();
                }
            }
        } catch (Exception ignored) {
        }
    }

    void refresh(Context ctx, final Callback cb) {
        if (loading) {
            if (cb != null) cb.done("zaten indiriliyor", count());
            return;
        }
        loading = true;
        final Context app = ctx.getApplicationContext();
        final Prefs prefs = new Prefs(app);
        final String url = prefs.epgUrl();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                int n = 0;
                android.util.Log.i("TCLP", "epg fetch start: " + url);
                try {
                    final Map<String, List<Prog>> parsedId = new HashMap<>();
                    final Map<String, String> parsedName = new HashMap<>();
                    InputStream in = open(url);
                    try {
                        parseXmltv(in, parsedId, parsedName);
                    } finally {
                        in.close();
                    }
                    synchronized (EpgStore.this) {
                        byId.clear();
                        byId.putAll(parsedId);
                        for (java.util.Iterator<Map.Entry<String, String>> it =
                                parsedName.entrySet().iterator(); it.hasNext(); ) {
                            if (!parsedId.containsKey(it.next().getValue())) it.remove();
                        }
                        nameToId.clear();
                        nameToId.putAll(parsedName);
                        resolved.clear();
                        n = count();
                    }
                    saveCache(app);
                    prefs.setEpgFetchedAt(System.currentTimeMillis());
                } catch (Exception e) {
                    err = e.getClass().getSimpleName() + ": " + e.getMessage();
                    android.util.Log.e("TCLP", "epg fetch failed: " + err);
                }
                loading = false;
                final String ferr = err;
                final int fn = n;
                android.util.Log.i("TCLP", "epg fetch end n=" + n + " err=" + err);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (cb != null) cb.done(ferr, fn);
                    }
                });
            }
        }, "epg-fetch").start();
    }

    private static InputStream open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "TCL-Live-Plus/0.1");
        c.setInstanceFollowRedirects(true);
        c.connect();
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
        BufferedInputStream bis = new BufferedInputStream(c.getInputStream(), 65536);
        bis.mark(2);
        int b0 = bis.read();
        int b1 = bis.read();
        bis.reset();
        if (b0 == 0x1f && b1 == 0x8b) return new GZIPInputStream(bis, 65536);
        return bis;
    }

    private static void parseXmltv(InputStream in,
                                    Map<String, List<Prog>> outId,
                                    Map<String, String> outName) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(in, "UTF-8");

        String curChannel = null;
        StringBuilder curDisplay = null;
        String progChannel = null;
        long start = 0;
        long stop = 0;
        StringBuilder title = null;

        int ev = p.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT) {
            String tag = p.getName();
            if (ev == XmlPullParser.START_TAG) {
                if ("channel".equals(tag)) {
                    curChannel = p.getAttributeValue(null, "id");
                    curDisplay = null;
                } else if ("display-name".equals(tag) && curChannel != null) {
                    curDisplay = new StringBuilder();
                    nextTextOrEmpty(p, curDisplay);
                    if (curDisplay.length() > 0) {
                        outName.put(normalize(curDisplay.toString()), curChannel);
                    }
                } else if ("programme".equals(tag)) {
                    progChannel = p.getAttributeValue(null, "channel");
                    start = parseTime(p.getAttributeValue(null, "start"));
                    stop = parseTime(p.getAttributeValue(null, "stop"));
                    title = null;
                } else if ("title".equals(tag) && progChannel != null && title == null) {
                    title = new StringBuilder();
                    nextTextOrEmpty(p, title);
                }
            } else if (ev == XmlPullParser.END_TAG) {
                if ("programme".equals(tag)) {
                    if (progChannel != null && title != null && title.length() > 0) {
                        List<Prog> list = outId.get(progChannel);
                        if (list == null) {
                            list = new ArrayList<>();
                            outId.put(progChannel, list);
                        }
                        list.add(new Prog(start, stop, title.toString().trim()));
                    }
                    progChannel = null;
                    title = null;
                } else if ("channel".equals(tag)) {
                    curChannel = null;
                    curDisplay = null;
                }
            }
            ev = p.next();
        }
        for (List<Prog> l : outId.values()) {
            Collections.sort(l, new Comparator<Prog>() {
                @Override
                public int compare(Prog a, Prog b) {
                    return Long.compare(a.start, b.start);
                }
            });
        }
    }

    private static boolean nextTextOrEmpty(XmlPullParser p, StringBuilder out) {
        try {
            String t = p.nextText();
            out.append(t == null ? "" : t);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static long parseTime(String v) {
        if (v == null || v.length() < 14) return 0L;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
            f.setTimeZone(TimeZone.getDefault());
            return f.parse(v.substring(0, 14)).getTime();
        } catch (Exception e) {
            return 0L;
        }
    }

    static String normalize(String s) {
        if (s == null) return "";
        String u = s.toUpperCase(new Locale("tr", "TR"));
        u = u.replace('İ', 'I').replace('I', 'I')
                .replace('Ş', 'S').replace('Ğ', 'G')
                .replace('Ü', 'U').replace('Ö', 'O').replace('Ç', 'C');
        u = u.replaceAll("\\b(UHD|FHD|HD|SD|4K|8K)\\b", " ");
        u = u.replaceAll("[^A-Z0-9]", "");
        return u;
    }

    synchronized int count() {
        int n = 0;
        for (List<Prog> l : byId.values()) n += l.size();
        return n;
    }

    private static final Map<String, String> ALIAS = new HashMap<>();

    static {
        // TV channel name -> guide name (normalized)
        ALIAS.put("NOW", "FOX");
        ALIAS.put("NOWTV", "FOX");
        ALIAS.put("FOXTV", "FOX");
        ALIAS.put("TRTCOCUK", "TRTCOCUK");
        ALIAS.put("TRTMUZIK", "TRTMUZIK");
        ALIAS.put("TRTSPOR", "TRTSPOR");
        ALIAS.put("WILD", "NATGEOWILD");
        ALIAS.put("NATIONALGEOGRAPHICWILD", "NATGEOWILD");
        ALIAS.put("NATIONALGEOGRAPHIC", "NATGEO");
    }

    private synchronized String resolveId(Channel c) {
        if (c.epgId != null && byId.containsKey(c.epgId)) return c.epgId;
        String n = normalize(c.name);
        String key = "n:" + n;
        if (resolved.containsKey(key)) return resolved.get(key);
        String id = nameToId.get(n);
        if (id == null) {
            String alias = ALIAS.get(n);
            if (alias != null) {
                id = nameToId.get(alias);
            }
        }
        if (id == null) {
            for (Map.Entry<String, String> e : nameToId.entrySet()) {
                if (e.getKey().equals(n) || e.getKey().startsWith(n) || n.startsWith(e.getKey())) {
                    id = e.getValue();
                    break;
                }
            }
        }
        resolved.put(key, id);
        if (n.contains("KANAL") || n.contains("BEIN")) {
            List<Prog> pl = id == null ? null : byId.get(id);
            android.util.Log.i("TCLP", "epg resolve name=" + c.name + " norm=" + n
                    + " id=" + id + " progs=" + (pl == null ? -1 : pl.size()));
        }
        return id;
    }

    /** XMLTV + uydu (EIT) birlesik rehber; uydu verisi varsa oncelikli. */
    synchronized List<Prog> window(Channel c, long from, long to) {
        List<Prog> xml = xmlWindow(c, from, to);
        List<Prog> eit = EitStore.I.window(c, from, to);
        if (xml.isEmpty()) return eit;
        // Uydunun kendi EIT rehberi yayinciya ait ve daha isabetli. Yalnizca
        // cok az kayit varsa (now/next) XMLTV'ye don.
        if (eit.size() >= 4) return eit;
        return xml;
    }

    private List<Prog> xmlWindow(Channel c, long from, long to) {
        String id = resolveId(c);
        if (id == null) return Collections.emptyList();
        List<Prog> l = byId.get(id);
        if (l == null) return Collections.emptyList();
        List<Prog> out = new ArrayList<>();
        for (Prog p : l) {
            if (p.stop > from && p.start < to) out.add(p);
        }
        return out;
    }

    synchronized Prog now(Channel c) {
        long now = System.currentTimeMillis();
        for (Prog p : window(c, now - 6L * 3600000L, now + 6L * 3600000L)) {
            if (p.start <= now && now < p.stop) return p;
        }
        return null;
    }

    synchronized Prog next(Channel c) {
        long now = System.currentTimeMillis();
        for (Prog p : window(c, now, now + 36L * 3600000L)) {
            if (p.start > now) return p;
        }
        return null;
    }

    synchronized List<Prog> day(Channel c) {
        long now = System.currentTimeMillis();
        return window(c, now - 3600000L, now + 36L * 3600000L);
    }

    private void saveCache(Context ctx) {
        try {
            JSONObject root = new JSONObject();
            JSONObject ch = new JSONObject();
            JSONObject names = new JSONObject();
            synchronized (this) {
                for (Map.Entry<String, List<Prog>> e : byId.entrySet()) {
                    JSONArray arr = new JSONArray();
                    for (Prog p : e.getValue()) {
                        JSONArray one = new JSONArray();
                        one.put(p.start);
                        one.put(p.stop);
                        one.put(p.title);
                        arr.put(one);
                    }
                    ch.put(e.getKey(), arr);
                }
                for (Map.Entry<String, String> e : nameToId.entrySet()) {
                    names.put(e.getKey(), e.getValue());
                }
            }
            root.put("channels", ch);
            root.put("names", names);
            File f = new File(ctx.getFilesDir(), "epg.json");
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }
}
