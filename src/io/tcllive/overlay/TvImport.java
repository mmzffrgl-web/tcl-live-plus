package io.tcllive.overlay;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads the TCL/TIF channel table through the system TV provider. */
final class TvImport {
    private TvImport() {
    }

    static int run(Context ctx) {
        ContentResolver cr = ctx.getContentResolver();
        Uri uri = Uri.parse("content://android.media.tv/channel");
        String[] cols = null; // tum kolonlar: internal_provider_flag2 ile gizli/skip tespiti
        List<Channel> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = cr.query(uri, cols, null, null, "display_number");
        } catch (Exception e) {
            android.util.Log.e("TCLP", "tvimport query failed: " + e);
        }
        if (c != null) {
            int iNum = c.getColumnIndex("display_number");
            int iName = c.getColumnIndex("display_name");
            int iSvc = c.getColumnIndex("service_type");
            int iType = c.getColumnIndex("type");
            int iVis = c.getColumnIndex("visible");
            int iBro = c.getColumnIndex("browsable");
            int iIn = c.getColumnIndex("input_id");
            int iFlag2 = c.getColumnIndex("internal_provider_flag2");
            int iFlag1 = c.getColumnIndex("internal_provider_flag1");
            int iId = c.getColumnIndex("_id");
            android.util.Log.i("TCLP", "tvimport rows=" + c.getCount()
                    + " cols vis=" + iVis + " bro=" + iBro + " in=" + iIn);
            java.util.LinkedHashMap<String, Channel> byNumber = new java.util.LinkedHashMap<>();
            Map<String, String> inputByNumber = new LinkedHashMap<>();
            Map<String, Integer> byInput = new LinkedHashMap<>();
            Map<Integer, Integer> byFlag2 = new LinkedHashMap<>();
            StringBuilder f2hidden = new StringBuilder();
            StringBuilder skipped = new StringBuilder();
            int invisible = 0;
            int unbrowsable = 0;
            int visNull = 0;
            int visZero = 0;
            int visOne = 0;
            StringBuilder nullVis = new StringBuilder();
            List<String> debugIds = new ArrayList<>();
            while (c.moveToNext()) {
                String num = iNum >= 0 ? c.getString(iNum) : null;
                String name = iName >= 0 ? c.getString(iName) : null;
                int serviceType = iSvc < 0 || c.isNull(iSvc) ? -1 : c.getInt(iSvc);
                int type = iType < 0 || c.isNull(iType) ? -1 : c.getInt(iType);
                int visible = iVis < 0 || c.isNull(iVis) ? 1 : c.getInt(iVis);
                int browsable = iBro < 0 || c.isNull(iBro) ? 1 : c.getInt(iBro);
                String input = iIn >= 0 ? c.getString(iIn) : null;
                if (input == null) input = "?";
                Integer cnt = byInput.get(input);
                byInput.put(input, cnt == null ? 1 : cnt + 1);
                if (visible == 0) invisible++;
                if (browsable == 0) unbrowsable++;
                int f2 = iFlag2 < 0 || c.isNull(iFlag2) ? Integer.MIN_VALUE : c.getInt(iFlag2);
                Integer fc = byFlag2.get(f2);
                byFlag2.put(f2, fc == null ? 1 : fc + 1);
                if ((f2 & 0x80000) != 0 && f2hidden.length() < 300) f2hidden.append(num).append(',');
                if (iVis >= 0 && c.isNull(iVis)) {
                    visNull++;
                    if (nullVis.length() < 400) nullVis.append(num).append(',');
                } else if (visible == 0) {
                    visZero++;
                } else {
                    visOne++;
                }
                if ("14".equals(num == null ? null : num.trim())) {
                    android.util.Log.i("TCLP", "tvimport ch14 name=" + name + " input=" + input
                            + " type=" + type + " st=" + serviceType
                            + " visible=" + visible + " browsable=" + browsable);
                    StringBuilder sb = new StringBuilder("tvimport ch14 row:");
                    for (int k = 0; k < c.getColumnCount(); k++) {
                        sb.append(' ').append(c.getColumnName(k)).append('=')
                                .append(c.isNull(k) ? "null" : c.getString(k));
                    }
                    android.util.Log.i("TCLP", sb.toString());
                }
                if (name != null && name.toUpperCase(java.util.Locale.ROOT).contains("WILD")) {
                    android.util.Log.i("TCLP", "tvimport wild num=" + num + " name=" + name
                            + " input=" + input + " vis=" + visible + " bro=" + browsable);
                }
                if (("14".equals(num == null ? "" : num.trim())
                        || "25".equals(num == null ? "" : num.trim())) && iNum >= 0) {
                    String id = c.getString(0);
                    if (id != null) debugIds.add(id);
                }
                if (num == null || num.trim().isEmpty()) continue;
                if (name == null || name.trim().isEmpty()) continue;
                num = num.trim();
                if (!num.matches("\\d+")) continue;
                if (visible == 0 || browsable == 0) continue;
                boolean hidden = (f2 & 0x80000) != 0;
                if (hidden) {
                    if (skipped.length() < 200) skipped.append(num).append(',');
                    continue;
                }
                boolean tuner = input != null && input.startsWith("com.tcl.tvinput");
                String rowId = iId >= 0 ? c.getString(iId) : null;
                String prevInput = inputByNumber.get(num);
                if (prevInput == null) {
                    byNumber.put(num, channel(num, name.trim(), rowId));
                    inputByNumber.put(num, input);
                } else if (tuner && !prevInput.startsWith("com.tcl.tvinput")) {
                    byNumber.put(num, channel(num, name.trim(), rowId));
                    inputByNumber.put(num, input);
                }
                if (byNumber.size() <= 5) {
                    android.util.Log.i("TCLP", "tvimport ch " + num + " " + name
                            + " st=" + serviceType + " type=" + type);
                }
            }
            c.close();
            out.addAll(byNumber.values());
            // Numaralari 1..N olarak kesintisiz goster; gercek TCL numarasini tune icin sakla.
            java.util.Collections.sort(out, new java.util.Comparator<Channel>() {
                @Override
                public int compare(Channel a, Channel b) {
                    return Long.compare(asLong(a.number), asLong(b.number));
                }
            });
            for (int i = 0; i < out.size(); i++) {
                Channel ch = out.get(i);
                ch.tune = ch.number;
                ch.number = String.valueOf(i + 1);
            }
            if (!out.isEmpty()) {
                Channel f = out.get(0);
                Channel l = out.get(out.size() - 1);
                android.util.Log.i("TCLP", "tvimport renum first=" + f.number + "/" + f.tune
                        + " last=" + l.number + "/" + l.tune);
            }
            List<Channel> probe = new ArrayList<>();
            for (Channel ch : out) {
                String un = ch.name == null ? "" : ch.name.toUpperCase(java.util.Locale.ROOT);
                if (un.contains("KANAL D") || un.contains("BEIN")) {
                    android.util.Log.i("TCLP", "tvimport dbg display=" + ch.number
                            + " tune=" + ch.tune + " tid=" + ch.tifId + " name=" + ch.name);
                    if (probe.size() < 6) probe.add(ch);
                }
            }
            programDiag(cr, probe);
            android.util.Log.i("TCLP", "tvimport invisible=" + invisible
                    + " unbrowsable=" + unbrowsable + " unique=" + out.size());
            android.util.Log.i("TCLP", "tvimport vis null=" + visNull + " zero=" + visZero
                    + " one=" + visOne + " nullNums=" + nullVis);
            for (String id : debugIds) {
                Cursor d = null;
                try {
                    d = cr.query(Uri.withAppendedPath(uri, id), null, null, null, null);
                    if (d != null && d.moveToFirst()) {
                        StringBuilder sb = new StringBuilder("tvimport full " + id + ":");
                        for (int k = 0; k < d.getColumnCount(); k++) {
                            sb.append(' ').append(d.getColumnName(k)).append('=');
                            if (d.isNull(k)) {
                                sb.append("null");
                            } else if (d.getType(k) == Cursor.FIELD_TYPE_BLOB) {
                                byte[] b = d.getBlob(k);
                                sb.append("blob").append(b == null ? 0 : b.length);
                            } else {
                                sb.append(d.getString(k));
                            }
                        }
                        android.util.Log.i("TCLP", sb.toString());
                    }
                } catch (Exception e) {
                    android.util.Log.w("TCLP", "tvimport full failed: " + e);
                } finally {
                    if (d != null) d.close();
                }
            }
            for (Map.Entry<String, Integer> e : byInput.entrySet()) {
                android.util.Log.i("TCLP", "tvimport input " + e.getKey() + " = " + e.getValue());
            }
            for (Map.Entry<Integer, Integer> e : byFlag2.entrySet()) {
                android.util.Log.i("TCLP", "tvimport flag2 " + e.getKey() + " = " + e.getValue());
            }
            android.util.Log.i("TCLP", "tvimport f2=524288 nums: " + f2hidden);
            android.util.Log.i("TCLP", "tvimport skipped(hidden) nums: " + skipped);
        }
        if (out.isEmpty()) return 0;
        ChannelStore.replace(ctx, out);
        EitStore.I.invalidate();
        EitStore.I.ensureLoaded(ctx);
        return out.size();
    }

    private static long asLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    private static Channel channel(String number, String name, String tifId) {
        Channel ch = new Channel(number, name, null, null);
        ch.tifId = tifId;
        return ch;
    }

    /** Satellite (EIT) guide availability: does TvProvider carry programme rows? */
    private static void programDiag(ContentResolver cr, List<Channel> probe) {
        Uri prog = Uri.parse("content://android.media.tv/program");
        Cursor p = null;
        try {
            p = cr.query(prog, new String[]{"channel_id", "title",
                    "start_time_utc_millis", "end_time_utc_millis"}, null, null, null);
            if (p == null) {
                android.util.Log.i("TCLP", "tvimport programs: null cursor");
                return;
            }
            android.util.Log.i("TCLP", "tvimport programs total=" + p.getCount());
            int shown = 0;
            while (p.moveToNext() && shown < 8) {
                android.util.Log.i("TCLP", "tvimport program ch=" + p.getString(0)
                        + " start=" + p.getLong(2) + " end=" + p.getLong(3)
                        + " title=" + p.getString(1));
                shown++;
            }
        } catch (Exception e) {
            android.util.Log.w("TCLP", "tvimport programs failed: " + e);
        } finally {
            if (p != null) p.close();
        }
        for (Channel ch : probe) {
            if (ch.tifId == null) continue;
            Cursor q = null;
            try {
                q = cr.query(prog, new String[]{"title", "start_time_utc_millis",
                        "end_time_utc_millis"}, "channel_id=?",
                        new String[]{ch.tifId}, null);
                int n = q == null ? -1 : q.getCount();
                StringBuilder sb = new StringBuilder("tvimport program count tid=")
                        .append(ch.tifId).append(" name=").append(ch.name).append(" n=").append(n);
                if (q != null && q.moveToNext()) {
                    sb.append(" first=").append(q.getLong(1)).append(" ").append(q.getString(0));
                }
                android.util.Log.i("TCLP", sb.toString());
            } catch (Exception e) {
                android.util.Log.w("TCLP", "tvimport program count failed tid=" + ch.tifId + ": " + e);
            } finally {
                if (q != null) q.close();
            }
        }
    }

    static boolean hasPermission(Context ctx) {
        return ctx.checkSelfPermission("android.permission.READ_TV_LISTINGS")
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
}
