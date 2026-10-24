package io.tcllive.overlay;

import android.content.Context;
import android.content.res.AssetManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Channel list storage: files/channels.json, seeded from assets/channels.json. */
final class ChannelStore {
    private static final Object LOCK = new Object();
    private static final List<Channel> CHANNELS = new ArrayList<>();

    private ChannelStore() {
    }

    static List<Channel> all() {
        synchronized (LOCK) {
            return new ArrayList<>(CHANNELS);
        }
    }

    static int size() {
        synchronized (LOCK) {
            return CHANNELS.size();
        }
    }

    static void load(Context ctx) {
        synchronized (LOCK) {
            CHANNELS.clear();
            File f = new File(ctx.getFilesDir(), "channels.json");
            String json = null;
            if (f.exists()) {
                json = readFile(f);
            }
            if (json == null || json.trim().isEmpty()) {
                json = readAsset(ctx, "channels.json");
            }
            parseInto(json);
            sort();
        }
    }

    static void save(Context ctx) {
        synchronized (LOCK) {
            try {
                JSONArray arr = new JSONArray();
                for (Channel c : CHANNELS) {
                    JSONObject o = new JSONObject();
                    o.put("n", c.number);
                    o.put("name", c.name);
                    if (c.tune != null && !c.tune.equals(c.number)) o.put("t", c.tune);
                    if (c.epgId != null) o.put("epg", c.epgId);
                    if (c.logo != null) o.put("logo", c.logo);
                    if (c.tifId != null) o.put("tid", c.tifId);
                    arr.put(o);
                }
                File f = new File(ctx.getFilesDir(), "channels.json");
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(arr.toString(2).getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** Adds channels, merging on number|name. Returns number of new entries. */
    static int merge(Context ctx, List<Channel> incoming) {
        int added = 0;
        synchronized (LOCK) {
            Map<String, Channel> byKey = new LinkedHashMap<>();
            for (Channel c : CHANNELS) byKey.put(c.key(), c);
            for (Channel c : incoming) {
                if (!byKey.containsKey(c.key())) {
                    byKey.put(c.key(), c);
                    added++;
                }
            }
            CHANNELS.clear();
            CHANNELS.addAll(byKey.values());
            sort();
        }
        if (added > 0) save(ctx);
        return added;
    }

    static void replace(Context ctx, List<Channel> incoming) {
        synchronized (LOCK) {
            CHANNELS.clear();
            CHANNELS.addAll(incoming);
            sort();
        }
        save(ctx);
    }

    private static void sort() {
        Collections.sort(CHANNELS, new Comparator<Channel>() {
            @Override
            public int compare(Channel a, Channel b) {
                long x = num(a.number);
                long y = num(b.number);
                if (x != y) return Long.compare(x, y);
                return a.name.compareToIgnoreCase(b.name);
            }
        });
    }

    private static long num(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    private static void parseInto(String json) {
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String n = o.optString("n", "");
                String name = o.optString("name", "");
                if (n.isEmpty() || name.isEmpty()) continue;
                String t = o.has("t") ? o.optString("t") : n;
                Channel ch = new Channel(n, name,
                        o.has("epg") ? o.optString("epg") : null,
                        o.has("logo") ? o.optString("logo") : null,
                        t);
                if (o.has("tid")) ch.tifId = o.optString("tid");
                CHANNELS.add(ch);
            }
        } catch (Exception ignored) {
        }
    }

    private static String readFile(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static String readAsset(Context ctx, String name) {
        try {
            AssetManager am = ctx.getAssets();
            try (InputStream in = am.open(name)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
                return new String(bos.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return "[]";
        }
    }
}
