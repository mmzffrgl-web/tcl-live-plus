package io.tcllive.overlay;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Setup screen: accessibility permission, EPG source, channel import. */
public class MainActivity extends Activity {

    private Prefs prefs;
    private TextView status;
    private EditText epgUrl;
    private EditText tunerMode;
    private EditText tkgsUrl;
    private EditText iptvUrl;
    private CheckBox intercept;
    private Button importBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        ChannelStore.load(this);
        IptvStore.loadCache(this);
        EpgStore.I.loadCache(this);
        // TCL clears the accessibility flag on boot; restore it whenever the app is opened.
        BootReceiver.enable(this);
        BootJobService.ensure(this);
        setContentView(build());
        refreshStatus();
        if (getIntent() != null && getIntent().getBooleanExtra("overlay", false)) {
            new android.os.Handler(getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (OverlayService.instance == null) {
                        toast("Erişilebilirlik servisi bağlı değil");
                    } else {
                        sendBroadcast(new Intent("io.tcllive.overlay.SHOW"));
                    }
                }
            }, 800);
        }
        if (getIntent() != null && getIntent().getBooleanExtra("import", false)) {
            new android.os.Handler(getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    int n = TvImport.run(MainActivity.this);
                    android.util.Log.i("TCLP", "import extra n=" + n);
                    ChannelStore.load(MainActivity.this);
                    refreshStatus();
                }
            }, 600);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        BootReceiver.enable(this);
        ChannelStore.load(this);
        IptvStore.loadCache(this);
        refreshStatus();
        importBtn.setText(prefs.importMode() ? "İçe aktarmayı bitir" : "Kanalları içe aktar");
    }

    private View build() {
        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(0xFF0B0F14);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(28), dp(28), dp(28), dp(40));
        sc.addView(col);

        TextView title = new TextView(this);
        title.setText("TCL Live+");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        col.addView(title);

        TextView sub = new TextView(this);
        sub.setText("TCL canlı TV üzerine kanal listesi, EPG ve arama katmanı. Root gerekmez.");
        sub.setTextColor(0xFF8FA3B8);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sub.setPadding(0, dp(6), 0, dp(20));
        col.addView(sub);

        status = new TextView(this);
        status.setTextColor(0xFFB9C7D6);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setPadding(0, 0, 0, dp(18));
        col.addView(status);

        LinearLayout tkgs = new LinearLayout(this);
        tkgs.setOrientation(LinearLayout.VERTICAL);
        tkgs.setPadding(dp(16), dp(14), dp(16), dp(14));
        tkgs.setBackgroundColor(0xFF121A23);

        TextView tkgsTitle = new TextView(this);
        tkgsTitle.setText("TKGS · kanal sırası");
        tkgsTitle.setTextColor(0xFFFFFFFF);
        tkgsTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tkgsTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tkgs.addView(tkgsTitle);

        TextView tkgsInfo = new TextView(this);
        tkgsInfo.setText("TKGS uydu yayınıdır; internet gerekmez. TCL TV TKGS sırasını uydudan alır. "
                + "“TV'den yenile” bu sırayı içe aktarır.\n"
                + "İsterseniz aşağıya bir internet sıra listesi adresi girin "
                + "(JSON dizi veya satır satır kanal adları). Liste, kanal adları eşleştirilerek uygulanır.");
        tkgsInfo.setTextColor(0xFF8FA3B8);
        tkgsInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tkgsInfo.setPadding(0, dp(6), 0, dp(2));
        tkgs.addView(tkgsInfo);

        tkgs.addView(button("TV'den TKGS sırasını yenile", v -> {
            toast("TV'den alınıyor…");
            new Thread(() -> {
                int n = TvImport.run(MainActivity.this);
                runOnUiThread(() -> {
                    refreshStatus();
                    toast(n > 0 ? ("TKGS: " + n + " kanal TV'den alındı")
                            : "TV'den kanal alınamadı");
                });
            }, "tkgs-main").start();
        }));

        tkgs.addView(label("İnternet sıra adresi (isteğe bağlı)"));
        tkgsUrl = input(prefs.tkgsUrl());
        tkgsUrl.setHint("https://…/sira.json veya .txt");
        tkgs.addView(tkgsUrl);
        tkgs.addView(button("İnternet adresini kaydet", v -> {
            prefs.setTkgsUrl(tkgsUrl.getText().toString());
            toast("TKGS internet adresi kaydedildi");
        }));
        col.addView(tkgs);

        col.addView(label("IPTV M3U adresi (boş = varsayılan)"));
        iptvUrl = input(prefs.iptvUrl());
        iptvUrl.setHint(IptvStore.URL_TR);
        iptvUrl.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        col.addView(iptvUrl);
        col.addView(button("IPTV adresini kaydet", v -> {
            String u = iptvUrl.getText().toString().trim();
            prefs.setIptvUrl(u);
            toast(u.isEmpty() ? "Varsayılan IPTV adresi kullanılacak"
                    : "IPTV adresi kaydedildi");
            IptvStore.refresh(this, (err, count) -> runOnUiThread(() -> {
                refreshStatus();
                toast(err == null ? (count + " IPTV kanalı hazır")
                        : ("IPTV hatası: " + err));
            }));
        }));
        col.addView(button("IPTV listesini şimdi indir", v -> {
            toast("IPTV listesi indiriliyor…");
            IptvStore.refresh(this, (err, count) -> runOnUiThread(() -> {
                refreshStatus();
                toast(err == null ? (count + " IPTV kanalı hazır")
                        : ("IPTV hatası: " + err));
            }));
        }));

        intercept = new CheckBox(this);
        intercept.setText("Canlı TV'de OK tuşunu yakala");
        intercept.setTextColor(0xFFFFFFFF);
        intercept.setChecked(prefs.interceptEnabled());
        intercept.setOnCheckedChangeListener((b, v) -> prefs.setInterceptEnabled(v));
        col.addView(intercept);

        col.addView(label("EPG (XMLTV) adresi"));
        epgUrl = input(prefs.epgUrl());
        col.addView(epgUrl);
        col.addView(button("Kaydet", v -> {
            prefs.setEpgUrl(epgUrl.getText().toString());
            toast("EPG adresi kaydedildi");
        }));

        col.addView(label("Tuner modu (dvbs = uydu, dvbt = karasal)"));
        tunerMode = input(prefs.tunerMode());
        col.addView(tunerMode);
        col.addView(button("Kaydet", v -> {
            prefs.setTunerMode(tunerMode.getText().toString());
            toast("Tuner modu kaydedildi");
        }));

        col.addView(button("EPG'yi şimdi indir", v -> {
            toast("EPG indiriliyor…");
            EpgStore.I.refresh(this, (error, count) -> {
                refreshStatus();
                toast(error == null ? ("EPG hazır: " + count + " program") : ("EPG hatası: " + error));
            });
        }));

        col.addView(button("Ekran üstü izni ver (kumanda için)", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                toast("Ayarlar açılamadı");
            }
        }));

        col.addView(button("Erişilebilirlik ayarlarını aç", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception e) {
                toast("Ayarlar açılamadı");
            }
        }));

        col.addView(button("Erişilebilirlik servisini şimdi aç", v -> {
            boolean ok = BootReceiver.enable(this);
            toast(ok ? "Servis açıldı (birkaç saniye içinde bağlanır)"
                    : "Açılamadı: WRITE_SECURE_SETTINGS izni yok");
            refreshStatus();
        }));

        col.addView(button("Katmanı test et", v -> {
            if (OverlayService.instance == null) {
                toast("Önce erişilebilirlik servisini aç");
            } else {
                sendBroadcast(new Intent("io.tcllive.overlay.SHOW"));
                toast("Katman açılıyor (TV ekranına bak)");
            }
        }));

        col.addView(button("Kanalları TV veritabanından al", v -> {
            int n = TvImport.run(this);
            toast(n > 0 ? ("TV veritabanından " + n + " kanal alındı")
                    : "Kanallar okunamadı (READ_TV_LISTINGS izni gerekli)");
            refreshStatus();
        }));

        importBtn = button("Kanalları içe aktar", v -> toggleImport());
        col.addView(importBtn);

        col.addView(button("Kanalları fabrika listesine döndür", v -> {
            prefs.setImportMode(false);
            try {
                getFilesDir();
                java.io.File f = new java.io.File(getFilesDir(), "channels.json");
                if (f.exists()) f.delete();
            } catch (Exception ignored) {
            }
            ChannelStore.load(this);
            refreshStatus();
            toast("Kanal listesi sıfırlandı");
        }));

        TextView help = new TextView(this);
        help.setText("Kurulum\n"
                + "1) Erişilebilirlik ayarlarından “TCL Live+” servisini aç.\n"
                + "2) TV'de canlı yayına dön, OK'a bas: liste açılır.\n"
                + "   SOL/SAĞ: sayfa (basılı tut: seri sayfa) · OK basılı: EPG · YEŞİL: TKGS · KIRMIZI: IPTV.\n"
                + "3) Kanal seç: TCL o kanala geçer.\n"
                + "4) TV yeniden başlarsa uygulamayı bir kez aç; servisi kendi açar.\n\n"
                + "TKGS\n"
                + "TKGS uydu üzerinden gelir, resmî internet servisi yoktur. TV'de TKGS açıksa\n"
                + "kanal sırası TV veritabanına yazılır; “TV'den yenile” bunu içe aktarır.\n"
                + "İnternet sıra adresi verirseniz liste ada göre eşleştirilip yeniden sıralanır.\n\n"
                + "IPTV\n"
                + "KIRMIZI: IPTV listesi. M3U adresini yukarıdaki alandan değiştirebilirsiniz.\n"
                + "OK ile oynat · oynatıcıda OK: listeye dön · GERİ: canlı TV.\n"
                + "Oynatıcıda YEŞİL: ekran modu (Sığdır / Doldur / Kırp).\n"
                + "IPTV listesinde SOL/SAĞ: sayfa atla.\n"
                + "Uydu kanalı kaybolursa aynı kanalı IPTV listesinden açabilirsiniz.\n\n"
                + "Kanalları içe aktarma\n"
                + "1) “Kanalları içe aktar”a bas.\n"
                + "2) TV'de OK ile TCL'in kendi kanal listesini aç, birkaç saniye gez.\n"
                + "3) GERİ'ye bas, sonra bu uygulamada “İçe aktarmayı bitir”e bas.\n\n"
                + "EPG: Türkiye XMLTV (varsayılan epgshare01 TR1). Logolar APK içinde gömülü.");
        help.setTextColor(0xFF7E8C9B);
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        help.setPadding(0, dp(24), 0, 0);
        col.addView(help);
        return sc;
    }

    private void toggleImport() {
        boolean on = !prefs.importMode();
        prefs.setImportMode(on);
        importBtn.setText(on ? "İçe aktarmayı bitir" : "Kanalları içe aktar");
        toast(on ? "TV'de kanal listesini aç (OK), sonra GERİ'ye bas"
                : "İçe aktarma kapandı · " + ChannelStore.size() + " kanal");
    }

    private void refreshStatus() {
        SimpleDateFormat f = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault());
        long ts = prefs.epgFetchedAt();
        String when = ts == 0 ? "hiç" : f.format(new Date(ts));
        boolean a11y = false;
        try {
            a11y = Settings.Secure.getInt(getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED) == 1;
        } catch (Exception ignored) {
        }
        status.setText("Kanal: " + ChannelStore.size()
                + "   ·   EPG programı: " + EpgStore.I.count()
                + "   ·   IPTV: " + IptvStore.size()
                + "\nErişilebilirlik servisi: " + (a11y ? "açık" : "kapalı")
                + "   ·   Son EPG indirmesi: " + when
                + "\nSon TKGS sıra alımı: " + (prefs.tkgsFetchedAt() == 0 ? "hiç"
                : f.format(new Date(prefs.tkgsFetchedAt()))));
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xFF8FA3B8);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setPadding(0, dp(18), 0, dp(6));
        return t;
    }

    private EditText input(String value) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setSingleLine(true);
        e.setTextColor(0xFFFFFFFF);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        e.setBackgroundColor(0xFF161C24);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        return e;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
