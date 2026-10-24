package io.tcllive.overlay;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Accessibility overlay that replaces the TCL channel list.
 *
 * OK    -> opens the list (when live TV is in front)
 * UP/DOWN -> move, CENTER -> tune, BACK/LEFT -> close
 * RIGHT -> EPG panel for the focused channel
 * UP at the first row -> search field + on-screen keyboard
 */
public class OverlayService extends AccessibilityService {

    private static final String PKG_TCL = "com.tcl.tv";
    private static final String PKG_TCL_PLAYER = "com.tcl.player";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    static OverlayService instance;

    private final android.content.BroadcastReceiver showReceiver =
            new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent != null
                            && "io.tcllive.overlay.SHOW_IPTV".equals(intent.getAction())) {
                        android.util.Log.i("TCLP", "iptv return broadcast");
                        showIptvFromPlayer(intent.getStringExtra("url"));
                        return;
                    }
                    android.util.Log.i("TCLP", "show broadcast received");
                    showList(intent != null && intent.getBooleanExtra("epg", false));
                }
            };

    private WindowManager wm;
    private FrameLayout root;
    private LinearLayout card;
    private TextView header;
    private EditText search;
    private ListView list;
    private TextView hint;
    private TextView empty;

    private final List<Channel> filtered = new ArrayList<>();
    private final List<EpgStore.Prog> epgItems = new ArrayList<>();
    private int selected = 0;
    private boolean showing = false;
    private boolean epgMode = false;
    private boolean searchMode = false;
    private boolean focusable = false;
    private boolean consumedDown = false;
    /** IPTV oynatıcısı açıkken OK'nin DOWN'ını yuttuğumuzu işaretler. */
    private boolean playerOkDownSeen = false;
    /** Oynatıcı, listedeki OK basışıyla mı açıldı? (Açılış UP'ını yutmak için.) */
    private boolean playerOpenedByOk = false;
    private long playerOpenedAt = 0;
    private long lastFallbackAt = 0;
    private long lastViewKeyAt = 0;
    private long shownAt = 0;
    private int cardHeight = 0;
    private String lastPkg = "";
    private String topWin = "";
    private boolean openedFromLiveTv = false;
    private Channel epgChannel;

    // long-press auto repeat
    private int repeatDir = 0;
    private int repeatTicks = 0;
    private int pageRepeatDir = 0;
    private int iptvRepeatDir = 0;
    private int iptvRepeatTicks = 0;
    private long subtitleMinute = -1;

    // OK uzun basma -> EPG
    private boolean okLongPending = false;
    private boolean okLongFired = false;
    private long okLongFiredAt = 0;

    // TKGS / kanal sırası paneli
    private boolean tkgsMode = false;
    private int tkgsSel = 0;
    private boolean tkgsBusy = false;
    private String tkgsStatus = "";
    private TextView tkgsChip;

    // IPTV (emrecemal/my-iptv-playlist) paneli
    private boolean iptvMode = false;
    private int iptvSel = 0;
    private boolean iptvBusy = false;
    private String iptvStatus = "";
    private String iptvWant = null;
    private final List<IptvStore.Entry> iptvItems = new ArrayList<>();
    private TextView iptvChip;
    private final Map<String, String> subtitleCache = new HashMap<>();

    private final Map<String, Bitmap> logoCache = new HashMap<>();
    private final Map<String, String> logoPathCache = new HashMap<>();
    private final SimpleDateFormat hhmm = new SimpleDateFormat("HH:mm", Locale.getDefault());

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        android.util.Log.i("TCLP", "service connected");
        instance = this;
        try {
            android.content.IntentFilter filter =
                    new android.content.IntentFilter("io.tcllive.overlay.SHOW");
            filter.addAction("io.tcllive.overlay.SHOW_IPTV");
            registerReceiver(showReceiver, filter);
        } catch (Exception e) {
            android.util.Log.e("TCLP", "receiver register failed: " + e);
        }
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        prefs = new Prefs(this);
        ChannelStore.load(this);
        EpgStore.I.loadCache(this);
        EitStore.I.ensureLoaded(this);
        // Ilk OK'ta pencereyi sifirdan kurmak ~250 ms suruyor; servis baglanirken
        // hazirla ki liste aninda acilsin.
        try {
            ensureUi();
        } catch (Throwable t) {
            android.util.Log.w("TCLP", "ui warmup failed: " + t);
        }
        BootJobService.ensure(this);
        long age = System.currentTimeMillis() - prefs.epgFetchedAt();
        if (EpgStore.I.count() == 0 || age > 12L * 3600L * 1000L) {
            EpgStore.I.refresh(this, null);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence p = event.getPackageName();
        if (p != null) {
            String s = p.toString();
            if (!getPackageName().equals(s)) lastPkg = s;
        }
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence cn = event.getClassName();
            if (cn != null) topWin = cn.toString();
            android.util.Log.i("TCLP", "win pkg=" + p + " cls=" + cn);
        }
        // Fallback when key filtering is not honoured: TCL's own channel list opens.
        // Close it first (BACK must not reach our own focusable overlay), then show ours.
        // Yalnizca gercek pencere degisiminde ve sadece TCL kanal listesi icin.
        if (prefs != null && !prefs.importMode() && prefs.interceptEnabled() && !showing
                && PKG_TCL.equals(lastPkg)
                && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            String cls = event.getClassName() == null ? "" : event.getClassName().toString();
            android.util.Log.i("TCLP", "tclwin " + cls);
            // Kanal duzenleme/yonetim ekranlarina dokunma.
            if (cls.endsWith("NewChannelListActivity")
                    && !cls.contains("Manager") && !cls.contains("Edit")) {
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastFallbackAt > 1600) {
                    lastFallbackAt = now;
                    android.util.Log.i("TCLP", "fallback: closing TCL list");
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    // Kanal listesi kapandiginda TCL bazen yeni bir pencere olayi
                    // yaymaz; topWin eski aktivitede kalirsa sonraki OK yakalanmaz
                    // ve her seferinde TCL listesi bir an gorunur.
                    topWin = "com.tcl.player.TVActivity";
                    ui.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (!showing && !prefs.importMode()) showList(false);
                        }
                    }, 100);
                }
            }
        }
        if (prefs != null && prefs.importMode()
                && (PKG_TCL.equals(lastPkg) || PKG_TCL_PLAYER.equals(lastPkg))) {
            int t = event.getEventType();
            if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    || t == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    || t == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                ui.removeCallbacks(harvest);
                ui.postDelayed(harvest, 700);
                if (!crawling) {
                    crawling = true;
                    ui.postDelayed(scrollStep, 1500);
                }
            }
        }
    }

    private boolean crawling = false;
    private int crawlSteps = 0;
    private int crawlIdle = 0;
    private int crawlNull = 0;

    private void harvestNow() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;
        List<String> texts = new ArrayList<>();
        collect(rootNode, texts, 0);
        List<Channel> found = pair(texts);
        if (!found.isEmpty()) {
            int added = ChannelStore.merge(OverlayService.this, found);
            android.util.Log.i("TCLP", "import found=" + found.size() + " added=" + added
                    + " total=" + ChannelStore.size());
        }
    }

    private final Runnable scrollStep = new Runnable() {
        @Override
        public void run() {
            if (prefs == null || !prefs.importMode() || crawlSteps > 400) {
                crawling = false;
                crawlSteps = 0;
                crawlIdle = 0;
                return;
            }
            AccessibilityNodeInfo r = getRootInActiveWindow();
            AccessibilityNodeInfo scrollable = findScrollable(r, 0);
            if (scrollable == null) {
                crawlNull++;
                if (crawlNull > 20) {
                    android.util.Log.i("TCLP", "crawl: list closed, total=" + ChannelStore.size());
                    crawling = false;
                    crawlSteps = 0;
                    crawlIdle = 0;
                    crawlNull = 0;
                    return;
                }
                ui.postDelayed(this, 900);
                return;
            }
            crawlNull = 0;
            int before = ChannelStore.size();
            harvestNow();
            boolean moved = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            crawlSteps++;
            if (ChannelStore.size() == before) {
                crawlIdle++;
            } else {
                crawlIdle = 0;
            }
            android.util.Log.i("TCLP", "crawl step=" + crawlSteps + " total="
                    + ChannelStore.size() + " idle=" + crawlIdle + " moved=" + moved);
            ui.postDelayed(harvest, 400);
            if (!moved || crawlIdle > 8) {
                android.util.Log.i("TCLP", "crawl done steps=" + crawlSteps
                        + " total=" + ChannelStore.size());
                crawling = false;
                crawlSteps = 0;
                crawlIdle = 0;
                return;
            }
            ui.postDelayed(this, 1200);
        }
    };

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 30) return null;
        if (n.isScrollable()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = findScrollable(n.getChild(i), depth + 1);
            if (c != null) return c;
        }
        return null;
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(showReceiver);
        } catch (Exception ignored) {
        }
        hide();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    /** Test entry point used by MainActivity while the service is connected. */
    static void showTest() {
        OverlayService s = instance;
        if (s != null) s.showList(false);
    }

    // ------------------------------------------------------------- channel import

    private final Runnable harvest = new Runnable() {
        @Override
        public void run() {
            harvestNow();
        }
    };

    private void collect(AccessibilityNodeInfo n, List<String> out, int depth) {
        if (n == null || depth > 40) return;
        CharSequence t = n.getText();
        if (t != null && t.length() > 0) out.add(t.toString().trim());
        CharSequence d = n.getContentDescription();
        if (d != null && d.length() > 0) out.add(d.toString().trim());
        int c = n.getChildCount();
        for (int i = 0; i < c; i++) collect(n.getChild(i), out, depth + 1);
    }

    private static List<Channel> pair(List<String> texts) {
        List<Channel> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < texts.size() - 1; i++) {
            String a = texts.get(i);
            String b = texts.get(i + 1);
            if (!a.matches("\\d{1,4}")) continue;
            if (b.isEmpty() || b.matches("\\d{1,4}")) continue;
            if (b.length() > 40) continue;
            if (b.matches("(?i)(hd|sd|uhd|4k|fhd)")) continue;
            String key = a + "|" + b;
            if (seen.add(key)) out.add(new Channel(a, b, null, null));
        }
        return out;
    }

    // ------------------------------------------------------------------- keys

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        android.util.Log.i("TCLP", "onKeyEvent code=" + event.getKeyCode() + " scan="
                + event.getScanCode() + " action="
                + event.getAction() + " rep=" + event.getRepeatCount());
        if (prefs == null) return false;

        int code = event.getKeyCode();
        int scan = event.getScanCode();

        // TCL kumandasinin YouTube tusu: Generic.kl'de "key 245 TCL_YOUTUBE".
        // Framework bu ozel etiketi tanimadigi icin bazi yollarda keyCode 0 gelir;
        // bu yuzden scan koduna da bakiyoruz.
        if (YoutubeLauncher.isYoutubeKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                android.util.Log.i("TCLP", "youtube key code=" + code + " scan=" + scan);
                openYouTube();
            }
            return true;
        }

        if (prefs.importMode() && !showing) return false;

        // TCL kumandasinin "ayarlar" tusu: bazi yollarda kernel scan 143 (WAKEUP),
        // bazilarinda KEYCODE_NOTIFICATION (83) / scan 561 olarak geliyor; ROM ikisini de
        // islemiyor. Yakalayip Ayarlar'i aciyoruz.
        boolean settingsKey = scan == 143 || scan == 0xF30
                || code == KeyEvent.KEYCODE_NOTIFICATION;
        if (settingsKey) {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            boolean interactive = pm == null || pm.isInteractive();
            if (!interactive) return false; // ekran kapaliysa uyandirma sistemde kalsin
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                android.util.Log.i("TCLP", "settings key 143 -> Settings");
                hide();
                try {
                    Intent i = new Intent(android.provider.Settings.ACTION_SETTINGS);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Throwable t) {
                    android.util.Log.w("TCLP", "settings start failed: " + t);
                }
            }
            return true;
        }

        // IPTV oynatıcısı açıkken OK'yi burada yutuyoruz. Activity'nin kendi
        // dispatchKeyEvent'i bazı TCL kumandalarında çalışmıyor; OK'nin UP olayı
        // canlı TV'ye ulaşıp arka planda kanal açıyordu.
        if (!showing && IptvPlayerActivity.active) {
            boolean up = code == KeyEvent.KEYCODE_DPAD_UP
                    || code == KeyEvent.KEYCODE_CHANNEL_UP;
            boolean down = code == KeyEvent.KEYCODE_DPAD_DOWN
                    || code == KeyEvent.KEYCODE_CHANNEL_DOWN;
            if (up || down) {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && event.getRepeatCount() == 0) {
                    android.util.Log.i("TCLP", "player zap " + (up ? -1 : 1));
                    IptvPlayerActivity.zap(up ? -1 : 1);
                }
                return true;
            }
            boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER
                    || code == KeyEvent.KEYCODE_ENTER
                    || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
            if (ok) {
                long now = android.os.SystemClock.uptimeMillis();
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    // Yalnızca yeni basış sayılır; kanal seçerken OK basılı
                    // tutulduysa tekrar olayları oynatıcıyı hemen kapatmasın.
                    // Oynatıcıyı açan ilk DOWN de yeni basış sayılmaz.
                    if (event.getRepeatCount() == 0
                            && now - playerOpenedAt > 800) {
                        playerOkDownSeen = true;
                        playerOpenedByOk = false;
                    }
                    android.util.Log.i("TCLP", "player OK down consumed");
                    return true;
                }
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    boolean was = playerOkDownSeen;
                    // Servis yalnızca UP görürse: oynatıcı yeni açıldıysa bu,
                    // kanalı seçen OK'nin bırakılmasıdır; listeye dönme.
                    boolean justOpened = playerOpenedByOk
                            && now - playerOpenedAt < 1500;
                    playerOkDownSeen = false;
                    playerOpenedByOk = false;
                    if (was || !justOpened) {
                        android.util.Log.i("TCLP", "player OK up -> list");
                        IptvPlayerActivity.returnNow();
                    } else {
                        android.util.Log.i("TCLP", "player opening OK up ignored");
                    }
                    return true;
                }
            }
            consumedDown = false;
            return false;
        }
        playerOkDownSeen = false;
        playerOpenedByOk = false;

        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (showing) stopRepeat();
            if (showing && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
                boolean handled = okUp();
                boolean consumed = consumedDown;
                consumedDown = false;
                return handled || consumed;
            }
            if (consumedDown) {
                consumedDown = false;
                return true;
            }
            return false;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;

        if (showing) {
            // OK basili tutulurken gelen tus tekrarlari uzun basma sayacini bozmasin.
            if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)
                    && event.getRepeatCount() > 0) {
                consumedDown = true;
                return true;
            }
            // TKGS panelinde yalnizca panel tuslari islenir; tus tekrarlari yok sayilir.
            if (tkgsMode) {
                if (event.getRepeatCount() > 0) {
                    consumedDown = true;
                    return true;
                }
                stopRepeat();
                consumedDown = handleVisible(code);
                return consumedDown;
            }
            // IPTV panelinde UP/DOWN basili tutulunca liste hizli aksin.
            if (iptvMode) {
                if (event.getRepeatCount() > 0) {
                    consumedDown = true;
                    return true;
                }
                stopRepeat();
                if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    int dir = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                    moveIptv(dir);
                    startIptvRepeat(dir);
                    consumedDown = true;
                    return true;
                }
                consumedDown = handleVisible(code);
                return consumedDown;
            }
            // When the focusable window handles the key, a11y sees a duplicate; skip it.
            if (event.getRepeatCount() > 0
                    && android.os.SystemClock.uptimeMillis() - lastViewKeyAt < 90) return false;
            if (!searchMode
                    && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)) {
                int dir = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                if (event.getRepeatCount() == 0) {
                    boolean handled = handleVisible(code);
                    if (handled && !searchMode) startRepeat(dir);
                    consumedDown = handled;
                    return handled;
                }
                // Let our timer drive the scroll, ignore firmware key repeats.
                if (repeatDir != 0) {
                    consumedDown = true;
                    return true;
                }
                int step = 1 + event.getRepeatCount() / 4;
                if (step > 6) step = 6;
                move(dir * step);
                consumedDown = true;
                return true;
            }
            // SOL/SAĞ: ilk basista sayfa, basili tutunca seri sayfa.
            if (!searchMode && !epgMode
                    && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                if (event.getRepeatCount() == 0) {
                    boolean handled = handleVisible(code);
                    if (handled) {
                        startPageRepeat(code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                        consumedDown = true;
                        return true;
                    }
                }
                consumedDown = true;
                return true;
            }
            stopRepeat();
            consumedDown = handleVisible(code);
            return consumedDown;
        }

        android.util.Log.i("TCLP", "key=" + code + " lastPkg=" + lastPkg + " topWin=" + topWin
                + " live=" + isLiveTvFront() + " import=" + prefs.importMode());
        if (!prefs.interceptEnabled() || !isLiveTvFront()) return false;

        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            consumedDown = true;
            showList(false);
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            consumedDown = true;
            showList(true);
            return true;
        }
        return false;
    }

    private boolean isLiveTvFront() {
        // Sadece gercek canli TV ekrani. TCL'in kanal listesi/yoneticisi de com.tcl.tv
        // paketinde oldugu icin paket kontrolu yetmiyor; ust pencere sinifina bakiyoruz.
        if (topWin != null && !topWin.isEmpty()) {
            if (topWin.endsWith("com.tcl.player.TVActivity")) return true;
            // Canli TV'nin bilgi cubugu (kanal adi/ses) ayni ekranin parcasi; OK
            // basilinca topWin bu sinifa donuyor ve yakalama kayboluyordu.
            if (topWin.contains(".infobar.")) return true;
            return false;
        }
        String pkg = lastPkg;
        if (pkg == null || pkg.isEmpty()) {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null && r.getPackageName() != null) {
                pkg = r.getPackageName().toString();
                lastPkg = pkg;
            }
        }
        return PKG_TCL.equals(pkg) || PKG_TCL_PLAYER.equals(pkg);
    }

    private boolean handleVisible(int code) {
        if (code == KeyEvent.KEYCODE_PROG_RED) {
            if (iptvMode) {
                showList(false);
            } else {
                showIptv();
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_INFO
                || code == KeyEvent.KEYCODE_GUIDE || code == KeyEvent.KEYCODE_PROG_GREEN) {
            if (tkgsMode) {
                showList(false);
            } else {
                showTkgs();
            }
            return true;
        }
        if (tkgsMode) {
            switch (code) {
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    showList(false);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (tkgsSel > 0) {
                        tkgsSel--;
                        refreshRows();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (tkgsSel < 1) {
                        tkgsSel++;
                        refreshRows();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    runTkgs(tkgsSel);
                    return true;
                default:
                    if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                            || code == KeyEvent.KEYCODE_VOLUME_MUTE) return false;
                    return true;
            }
        }
        if (iptvMode) {
            switch (code) {
                case KeyEvent.KEYCODE_BACK:
                    hide();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    pageIptv(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    pageIptv(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    moveIptv(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    moveIptv(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    playIptv();
                    return true;
                default:
                    if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                            || code == KeyEvent.KEYCODE_VOLUME_MUTE) return false;
                    return true;
            }
        }
        if (searchMode) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_LEFT) {
                exitSearch();
                return true;
            }
            return false; // let the EditText / IME handle it
        }
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
                if (epgMode) {
                    showList(false);
                } else {
                    // Swallow a late BACK sent by our own fallback right after opening.
                    if (android.os.SystemClock.uptimeMillis() - shownAt < 700) {
                        android.util.Log.i("TCLP", "ignoring early BACK");
                        return true;
                    }
                    hide();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (epgMode) {
                    showList(false);
                } else {
                    page(-1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!epgMode) page(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (!epgMode && selected == 0 && search != null) {
                    enterSearch();
                } else {
                    move(-1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                move(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (epgMode) {
                    // Uzun basma ile acildiysa ve tus hala birakilmadiysa yok say.
                    if (!okLongFired
                            || android.os.SystemClock.uptimeMillis() - okLongFiredAt > 1500) {
                        showList(false);
                    }
                } else {
                    okDown();
                }
                return true;
            default:
                if (!epgMode && code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
                    jumpToDigit(KeyEvent.keyCodeToString(code));
                    return true;
                }
                return false;
        }
    }

    private void jumpToDigit(String name) {
        char d = name.charAt(name.length() - 1);
        for (int i = 0; i < filtered.size(); i++) {
            if (filtered.get(i).number.startsWith(String.valueOf(d))) {
                selected = i;
                list.setSelection(i);
                refreshRows();
                return;
            }
        }
    }

    /** Fallback typing when the EditText does not own the input connection. */
    private void typeChar(int keyCode) {
        if (search == null) return;
        Editable e = search.getText();
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            if (e.length() > 0) e.delete(e.length() - 1, e.length());
            return;
        }
        String ch = null;
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            ch = String.valueOf((char) ('a' + (keyCode - KeyEvent.KEYCODE_A)));
        } else if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            ch = String.valueOf((char) ('0' + (keyCode - KeyEvent.KEYCODE_0)));
        } else if (keyCode == KeyEvent.KEYCODE_SPACE) {
            ch = " ";
        }
        if (ch != null) search.append(ch);
    }

    private void tuneSelected() {
        if (filtered.isEmpty()) return;
        Channel c = filtered.get(selected);
        android.util.Log.i("TCLP", "tune " + c.number + " (tcl=" + c.tuneNumber() + ") " + c.name);
        prefs.setLastChannel(c.tuneNumber());
        hide();
        Tuner.tune(this, c.tuneNumber());
    }

    private void move(int delta) {
        int max = (epgMode ? epgItems.size() : filtered.size()) - 1;
        if (max < 0) return;
        selected = Math.max(0, Math.min(max, selected + delta));
        list.setSelection(selected);
        refreshRows();
    }

    private void startRepeat(int dir) {
        stopRepeat();
        repeatDir = dir;
        repeatTicks = 0;
        ui.postDelayed(repeatTick, 380);
    }

    /** SOL/SAĞ basılı tutulunca sayfalar kendiliğinden aksın. */
    private void startPageRepeat(int dir) {
        stopRepeat();
        pageRepeatDir = dir;
        ui.postDelayed(pageRepeatTick, 480);
    }

    private void stopPageRepeat() {
        if (pageRepeatDir != 0) ui.removeCallbacks(pageRepeatTick);
        pageRepeatDir = 0;
    }

    /** OK basili tutulunca EPG acilir. */
    private void okDown() {
        if (okLongPending) return; // a11y + pencere ayni olayi iki kez verebilir
        okLongPending = true;
        okLongFired = false;
        ui.removeCallbacks(okLongPressTask);
        ui.postDelayed(okLongPressTask, 450);
    }

    /** OK birakildi: kisa basista kanala gec, uzun basista EPG zaten acik. */
    private boolean okUp() {
        ui.removeCallbacks(okLongPressTask);
        boolean fired = okLongFired;
        boolean pending = okLongPending;
        okLongPending = false;
        okLongFired = false;
        if (fired) return true;
        if (pending) {
            tuneSelected();
            return true;
        }
        return false;
    }

    private final Runnable okLongPressTask = new Runnable() {
        @Override
        public void run() {
            if (!showing || epgMode || searchMode || filtered.isEmpty()) {
                okLongPending = false;
                return;
            }
            okLongPending = false;
            okLongFired = true;
            okLongFiredAt = android.os.SystemClock.uptimeMillis();
            consumedDown = true;
            android.util.Log.i("TCLP", "OK long press -> EPG " + filtered.get(selected).name);
            showEpg(filtered.get(selected));
        }
    };

    /** Gorunen satir sayisi kadar sayfa atla. */
    private int pageStep() {
        if (list != null) {
            int first = list.getFirstVisiblePosition();
            int last = list.getLastVisiblePosition();
            if (last > first) return last - first;
        }
        return 10;
    }

    private void page(int dir) {
        if (filtered.isEmpty()) return;
        int step = Math.max(1, pageStep());
        selected = Math.max(0, Math.min(filtered.size() - 1, selected + dir * step));
        list.setSelection(selected);
        refreshRows();
        android.util.Log.i("TCLP", "page " + dir + " -> " + selected);
    }

    /** IPTV listesinde SOL/SAĞ ile bir ekran (sayfa) atla. */
    private void pageIptv(int dir) {
        if (iptvItems.isEmpty()) return;
        int step = Math.max(1, pageStep());
        iptvSel = Math.max(0, Math.min(iptvItems.size() - 1, iptvSel + dir * step));
        list.setSelection(iptvSel);
        refreshRows();
        android.util.Log.i("TCLP", "iptv page " + dir + " -> " + iptvSel);
    }

    private void stopRepeat() {
        if (repeatDir != 0) ui.removeCallbacks(repeatTick);
        repeatDir = 0;
        repeatTicks = 0;
        stopPageRepeat();
        stopIptvRepeat();
    }

    /** IPTV listesinde UP/DOWN basılı tutulunca hızlı akış. */
    private void startIptvRepeat(int dir) {
        stopIptvRepeat();
        iptvRepeatDir = dir;
        iptvRepeatTicks = 0;
        android.util.Log.i("TCLP", "iptv repeat start dir=" + dir);
        ui.postDelayed(iptvRepeatTick, 300);
    }

    private void stopIptvRepeat() {
        if (iptvRepeatDir != 0) {
            ui.removeCallbacks(iptvRepeatTick);
            android.util.Log.i("TCLP", "iptv repeat stop sel=" + iptvSel
                    + " ticks=" + iptvRepeatTicks);
        }
        iptvRepeatDir = 0;
        iptvRepeatTicks = 0;
    }

    private final Runnable iptvRepeatTick = new Runnable() {
        @Override
        public void run() {
            if (iptvRepeatDir == 0 || !showing || !iptvMode) {
                stopIptvRepeat();
                return;
            }
            iptvRepeatTicks++;
            if (iptvRepeatTicks > 300) {
                stopIptvRepeat();
                return;
            }
            int step = 1;
            if (iptvRepeatTicks > 16) step = 8;
            else if (iptvRepeatTicks > 11) step = 6;
            else if (iptvRepeatTicks > 7) step = 4;
            else if (iptvRepeatTicks > 3) step = 2;
            moveIptv(iptvRepeatDir * step);
            if (iptvRepeatTicks % 10 == 0) {
                android.util.Log.i("TCLP", "iptv repeat sel=" + iptvSel
                        + " tick=" + iptvRepeatTicks + " step=" + step);
            }
            ui.postDelayed(this, 60);
        }
    };

    /** Drives accelerated scrolling while UP/DOWN is held on the remote. */
    private final Runnable repeatTick = new Runnable() {
        @Override
        public void run() {
            if (repeatDir == 0 || !showing) {
                stopRepeat();
                return;
            }
            repeatTicks++;
            if (repeatTicks > 70) {
                stopRepeat();
                return;
            }
            int step = 1;
            if (repeatTicks > 16) step = 6;
            else if (repeatTicks > 11) step = 5;
            else if (repeatTicks > 7) step = 3;
            else if (repeatTicks > 3) step = 2;
            move(repeatDir * step);
            ui.postDelayed(this, 80);
        }
    };

    /** Drives page jumps while LEFT/RIGHT is held on the remote. */
    private final Runnable pageRepeatTick = new Runnable() {
        @Override
        public void run() {
            if (pageRepeatDir == 0 || !showing || epgMode || searchMode || tkgsMode || iptvMode) {
                stopPageRepeat();
                return;
            }
            page(pageRepeatDir);
            ui.postDelayed(this, 300);
        }
    };

    // --------------------------------------------------------------------- UI

    private void showList(boolean withEpg) {
        android.util.Log.i("TCLP", "showList epg=" + withEpg);
        // Katman canli TV uzerinden mi aciliyor? Kapaninca topWin'i geri kurmak icin.
        if (!showing) openedFromLiveTv = isLiveTvFront();
        ensureUi();
        epgMode = false;
        epgChannel = null;
        tkgsMode = false;
        iptvMode = false;
        resetSearchUi();
        resizeCard(false);
        if (empty != null) empty.setVisibility(View.GONE);
        rebuildFiltered(null);
        String last = prefs.lastChannel();
        selected = 0;
        for (int i = 0; i < filtered.size(); i++) {
            if (filtered.get(i).tuneNumber().equals(last)) {
                selected = i;
                break;
            }
        }
        header.setText("KANALLAR");
        list.setAdapter(channelAdapter);
        list.setSelection(selected);
        show();
        if (withEpg && !filtered.isEmpty()) {
            showEpg(filtered.get(selected));
        }
    }

    private void showEpg(Channel c) {
        ensureUi();
        EitStore.I.ensureLoaded(this);
        resetSearchUi();
        epgMode = true;
        tkgsMode = false;
        iptvMode = false;
        epgChannel = c;
        epgItems.clear();
        epgItems.addAll(EpgStore.I.day(c));
        selected = 0;
        long now = System.currentTimeMillis();
        for (int i = 0; i < epgItems.size(); i++) {
            if (epgItems.get(i).start <= now && now < epgItems.get(i).stop) {
                selected = i;
                break;
            }
        }
        header.setText("EPG · " + c.name);
        if (hint != null) hint.setText("OK veya GERİ: kanal listesi");
        android.util.Log.i("TCLP", "epg panel " + c.name + " items=" + epgItems.size()
                + " eit=" + EitStore.I.window(c, System.currentTimeMillis() - 3600000L,
                System.currentTimeMillis() + 36L * 3600000L).size());
        list.setAdapter(epgAdapter);
        list.setSelection(selected);
        if (empty != null) empty.setVisibility(epgItems.isEmpty() ? View.VISIBLE : View.GONE);
        show();
    }

    // ------------------------------------------------------------------- TKGS

    private static final String TKGS_TV = "TV'den TKGS sırasını yenile";
    private static final String TKGS_NET = "İnternetten sıra çek";

    private void showTkgs() {
        ensureUi();
        resetSearchUi();
        epgMode = false;
        tkgsMode = true;
        iptvMode = false;
        tkgsSel = 0;
        android.util.Log.i("TCLP", "showTkgs");
        resizeCard(false);
        header.setText("TKGS · KANAL SIRASI");
        if (hint != null) hint.setText("OK: çalıştır · GERİ: kanal listesi");
        if (empty != null) {
            empty.setVisibility(View.VISIBLE);
            empty.setText(tkgsStatus.isEmpty()
                    ? "TKGS uydu yayınıdır; internet gerekmez.\n"
                    + "TCL TV TKGS sırasını alır, bu düğme TV'deki sırayı içe aktarır."
                    : tkgsStatus);
        }
        list.setAdapter(tkgsAdapter);
        list.setSelection(0);
        show();
    }

    private void updateTkgsStatus() {
        if (empty != null) {
            empty.setVisibility(View.VISIBLE);
            empty.setText(tkgsStatus);
        }
    }

    private void runTkgs(int which) {
        android.util.Log.i("TCLP", "runTkgs action=" + which + " busy=" + tkgsBusy
                + " hasUrl=" + !prefs.tkgsUrl().isEmpty());
        if (tkgsBusy) return;
        if (which == 0) refreshFromTv();
        else fetchInternetOrder();
    }

    /** TV'nin TKGS ile beslendiği kanal tablosunu içe aktarır. */
    private void refreshFromTv() {
        tkgsBusy = true;
        tkgsStatus = "TV'den TKGS sırası alınıyor…";
        updateTkgsStatus();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int n = TvImport.run(OverlayService.this);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        tkgsBusy = false;
                        if (n > 0) {
                            prefs.setTkgsFetchedAt(System.currentTimeMillis());
                            tkgsStatus = "TV'den " + n + " kanal alındı";
                            toast("TKGS: " + n + " kanal TV'den alındı");
                            showList(false);
                        } else {
                            tkgsStatus = "TV'den kanal alınamadı (TV listesi açık mı?)";
                            updateTkgsStatus();
                        }
                    }
                });
            }
        }, "tkgs-tv").start();
    }

    /** İsteğe bağlı internet sıra listesini indirip ada göre eşler. */
    private void fetchInternetOrder() {
        final String url = prefs.tkgsUrl();
        if (url.isEmpty()) {
            tkgsStatus = "İnternet adresi yok.\nAna ekrandaki “TKGS sıra adresi” alanına adres gir.";
            updateTkgsStatus();
            return;
        }
        tkgsBusy = true;
        tkgsStatus = "İnternetten sıra alınıyor…\n" + url;
        updateTkgsStatus();
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<String> names = null;
                String err = null;
                try {
                    names = fetchOrder(url);
                } catch (Throwable t) {
                    err = t.getMessage() == null ? t.toString() : t.getMessage();
                }
                final List<String> got = names;
                final String error = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        tkgsBusy = false;
                        if (got == null || got.isEmpty()) {
                            tkgsStatus = "Liste alınamadı: " + error;
                            updateTkgsStatus();
                            return;
                        }
                        int matched = applyInternetOrder(got);
                        android.util.Log.i("TCLP", "tkgs net rows=" + got.size()
                                + " matched=" + matched + " first=" + got.get(0));
                        if (matched > 0) {
                            prefs.setTkgsFetchedAt(System.currentTimeMillis());
                            tkgsStatus = "İnternetten " + matched + " kanal eşleşti";
                            toast("İnternet sırası: " + matched + " kanal eşleşti");
                            showList(false);
                        } else {
                            tkgsStatus = "Hiç kanal eşleşmedi (" + got.size() + " satır okundu)";
                            updateTkgsStatus();
                        }
                    }
                });
            }
        }, "tkgs-net").start();
    }

    private static List<String> fetchOrder(String url) throws Exception {
        java.net.HttpURLConnection c =
                (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "TCL-Live-Plus");
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new java.io.IOException("HTTP " + code);
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            int total = 0;
            while ((r = in.read(buf)) > 0) {
                total += r;
                if (total > 4 * 1024 * 1024) break;
                bos.write(buf, 0, r);
            }
            in.close();
            String body = new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            return parseOrder(body);
        } finally {
            c.disconnect();
        }
    }

    private static List<String> parseOrder(String body) {
        List<String> out = new ArrayList<>();
        String t = body == null ? "" : body.trim();
        if (t.startsWith("\uFEFF")) t = t.substring(1).trim();
        if (t.startsWith("[") || t.startsWith("{")) {
            try {
                org.json.JSONArray a = null;
                if (t.startsWith("[")) {
                    a = new org.json.JSONArray(t);
                } else {
                    org.json.JSONObject o = new org.json.JSONObject(t);
                    a = o.optJSONArray("channels");
                    if (a == null) a = o.optJSONArray("kanallar");
                }
                if (a != null) {
                    for (int i = 0; i < a.length(); i++) {
                        String n = nameOf(a.opt(i));
                        if (n != null && !n.isEmpty()) out.add(n);
                    }
                }
                if (!out.isEmpty()) return out;
            } catch (Exception ignored) {
                // JSON değilse düz satır listesi olarak dene.
            }
        }
        for (String line : t.split("\\r?\\n")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#") || s.startsWith("//")) continue;
            s = s.replaceFirst("^\\s*\\d{1,4}\\s*[\\.\\)\\-;:,]?\\s*", "").trim();
            if (!s.isEmpty() && s.length() < 80) out.add(s);
        }
        return out;
    }

    private static String nameOf(Object o) {
        if (o == null) return null;
        if (o instanceof String) return ((String) o).trim();
        if (o instanceof org.json.JSONObject) {
            org.json.JSONObject j = (org.json.JSONObject) o;
            for (String k : new String[]{"name", "ad", "title", "kanal", "channel"}) {
                String v = j.optString(k, null);
                if (v != null && !v.trim().isEmpty()) return v.trim();
            }
        }
        return null;
    }

    /** Sıra listesini ada göre uygular; eşleşmeyenler sona eklenir. Eşleşen sayısını döner. */
    private int applyInternetOrder(List<String> names) {
        List<Channel> all = ChannelStore.all();
        java.util.Map<String, Channel> exact = new java.util.HashMap<>();
        java.util.Map<String, Channel> base = new java.util.HashMap<>();
        for (Channel c : all) {
            String n = normName(c.name);
            if (n.isEmpty()) continue;
            if (!exact.containsKey(n)) exact.put(n, c);
            String b = baseName(n);
            if (!base.containsKey(b)) base.put(b, c);
        }
        java.util.Set<String> used = new java.util.HashSet<>();
        List<Channel> ordered = new ArrayList<>();
        for (String n : names) {
            String k = normName(n);
            if (k.isEmpty()) continue;
            Channel c = exact.get(k);
            if (c == null) c = base.get(baseName(k));
            if (c == null) continue;
            if (used.add(c.number)) ordered.add(c);
        }
        int matched = ordered.size();
        for (Channel c : all) {
            if (used.add(c.number)) ordered.add(c);
        }
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).number = String.valueOf(i + 1);
        }
        ChannelStore.replace(this, ordered);
        return matched;
    }

    private static String normName(String s) {
        if (s == null) return "";
        String u = s.toUpperCase(Locale.ROOT);
        u = u.replace('Ç', 'C').replace('Ğ', 'G').replace('İ', 'I').replace('Ö', 'O')
                .replace('Ş', 'S').replace('Ü', 'U');
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < u.length(); i++) {
            char ch = u.charAt(i);
            if ((ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')) sb.append(ch);
        }
        return sb.toString();
    }

    private static String baseName(String n) {
        String u = n;
        for (int guard = 0; guard < 3; guard++) {
            if (u.endsWith("UHD")) u = u.substring(0, u.length() - 3);
            else if (u.endsWith("FHD")) u = u.substring(0, u.length() - 3);
            else if (u.endsWith("HD")) u = u.substring(0, u.length() - 2);
            else if (u.endsWith("SD")) u = u.substring(0, u.length() - 2);
            else break;
        }
        return u;
    }

    private View buildActionRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(12), dp(8));
        TextView title = new TextView(this);
        title.setId(201);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        TextView sub = new TextView(this);
        sub.setId(202);
        sub.setTextColor(0xFF93A4B5);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(title);
        row.addView(sub);
        row.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        return row;
    }

    private final BaseAdapter tkgsAdapter = new BaseAdapter() {
        @Override
        public int getCount() {
            return 2;
        }

        @Override
        public Object getItem(int p) {
            return p == 0 ? TKGS_TV : TKGS_NET;
        }

        @Override
        public long getItemId(int p) {
            return p;
        }

        @Override
        public View getView(int p, View convertView, ViewGroup parent) {
            LinearLayout row = (LinearLayout) (convertView != null ? convertView : buildActionRow());
            TextView title = row.findViewById(201);
            TextView sub = row.findViewById(202);
            title.setText(p == 0 ? TKGS_TV : TKGS_NET);
            if (p == 0) {
                sub.setText("TV'deki TKGS listesini içe aktar (uydu)");
            } else {
                String url = prefs.tkgsUrl();
                sub.setText(url.isEmpty() ? "Adres yok · ana ekrandan girin" : url);
            }
            boolean sel = p == tkgsSel;
            row.setAlpha(sel ? 1f : 0.55f);
            row.setBackground(sel ? selectedRowBg() : null);
            return row;
        }
    };

    // -------------------------------------------------------------------- IPTV

    private void showIptv() {
        String want = null;
        if (!epgMode && !tkgsMode && !searchMode && selected >= 0 && selected < filtered.size()) {
            want = filtered.get(selected).name;
        }
        showIptvInternal(-1, want, true);
    }

    /** Oynatıcıda OK'ye basınca IPTV listesine, aynı kanal seçili olarak dön. */
    private void showIptvFromPlayer(String url) {
        IptvStore.loadCache(this);
        iptvItems.clear();
        iptvItems.addAll(IptvStore.all());
        int idx = -1;
        if (url != null) {
            for (int i = 0; i < iptvItems.size(); i++) {
                if (url.equals(iptvItems.get(i).url)) {
                    idx = i;
                    break;
                }
            }
        }
        android.util.Log.i("TCLP", "iptv return idx=" + idx + " url=" + url);
        if (!showing) openedFromLiveTv = true;
        showIptvInternal(idx >= 0 ? idx : iptvSel, null, false);
    }

    private void showIptvInternal(int sel, String want, boolean refresh) {
        ensureUi();
        stopIptvRepeat();
        iptvWant = want;
        resetSearchUi();
        epgMode = false;
        tkgsMode = false;
        iptvMode = true;
        IptvStore.loadCache(this);
        iptvItems.clear();
        iptvItems.addAll(IptvStore.all());
        if (iptvItems.isEmpty()) {
            iptvSel = 0;
        } else if (sel >= 0) {
            iptvSel = Math.min(sel, iptvItems.size() - 1);
        } else {
            iptvSel = 0;
            if (want != null) {
                int idx = findIptv(want);
                if (idx >= 0) iptvSel = idx;
                android.util.Log.i("TCLP", "iptv want=" + want + " idx=" + idx);
            }
        }
        resizeCard(false);
        header.setText("IPTV · LİSTE");
        setHint("OK: oynat · KANAL LİSTESİ · SOL/SAĞ: sayfa · GERİ: çık");
        if (empty != null) {
            empty.setVisibility(View.VISIBLE);
            empty.setText(iptvStatus.isEmpty()
                    ? (iptvItems.isEmpty()
                    ? "IPTV listesi indiriliyor…"
                    : iptvItems.size() + " kanal · liste güncelleniyor…")
                    : iptvStatus);
        }
        list.setAdapter(iptvAdapter);
        list.setSelection(iptvSel);
        show();
        if (refresh || iptvItems.isEmpty()) refreshIptv();
    }

    /** Aynı kanalın IPTV karşılığını ada göre bulur (HD/720p/Not 24/7 eklerini yok sayar). */
    private int findIptv(String name) {
        String want = iptvKey(name);
        if (want.isEmpty()) return -1;
        for (int i = 0; i < iptvItems.size(); i++) {
            if (want.equals(iptvKey(iptvItems.get(i).name))) return i;
        }
        return -1;
    }

    private static String iptvKey(String name) {
        String n = normName(name);
        n = n.replaceAll("\\d{3,4}P$", "");
        n = n.replace("NOT247", "").replace("GEOBLOCKED", "");
        return baseName(n);
    }

    private void updateIptvStatus() {
        if (iptvMode && empty != null) {
            empty.setVisibility(View.VISIBLE);
            empty.setText(iptvStatus);
        }
    }

    private void refreshIptv() {
        if (iptvBusy) return;
        iptvBusy = true;
        iptvStatus = iptvItems.isEmpty()
                ? "IPTV listesi indiriliyor…"
                : iptvItems.size() + " kanal · güncelleniyor…";
        updateIptvStatus();
        IptvStore.refresh(this, (err, count) -> ui.post(() -> {
            iptvBusy = false;
            if (err == null && count > 0) {
                iptvItems.clear();
                iptvItems.addAll(IptvStore.all());
                if (iptvSel >= iptvItems.size()) iptvSel = 0;
                iptvStatus = count + " kanal · liste güncel";
                if (iptvMode) {
                    if (iptvWant != null && iptvSel == 0) {
                        int idx = findIptv(iptvWant);
                        if (idx >= 0) iptvSel = idx;
                    }
                    iptvWant = null;
                    refreshRows();
                    if (!iptvItems.isEmpty()) list.setSelection(iptvSel);
                }
            } else {
                iptvStatus = iptvItems.isEmpty()
                        ? "Liste alınamadı: " + err
                        : "Liste güncellenemedi (" + err + ") · önbellek "
                        + iptvItems.size() + " kanal";
            }
            updateIptvStatus();
        }));
    }

    private void moveIptv(int dir) {
        if (iptvItems.isEmpty()) return;
        iptvSel = Math.max(0, Math.min(iptvItems.size() - 1, iptvSel + dir));
        list.setSelection(iptvSel);
        refreshRows();
    }

    private void playIptv() {
        if (iptvSel < 0 || iptvSel >= iptvItems.size()) {
            toast("Önce bir IPTV kanalı seçin");
            return;
        }
        IptvStore.Entry e = iptvItems.get(iptvSel);
        android.util.Log.i("TCLP", "iptv play " + e.name + " " + e.url);
        playerOkDownSeen = false;
        playerOpenedByOk = true;
        playerOpenedAt = android.os.SystemClock.uptimeMillis();
        hide();
        try {
            Intent i = new Intent(this, IptvPlayerActivity.class);
            i.putExtra("url", e.url);
            i.putExtra("name", e.name);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
        } catch (Throwable t) {
            android.util.Log.w("TCLP", "iptv start failed: " + t);
            toast("IPTV açılamadı: " + t.getClass().getSimpleName());
        }
    }

    /** TCL kumandasındaki YouTube tuşu (scan 245) YouTube TV'yi açar. */
    private void openYouTube() {
        hide();
        if (!YoutubeLauncher.open(this)) toast("YouTube açılamadı");
    }

    private final BaseAdapter iptvAdapter = new BaseAdapter() {
        @Override
        public int getCount() {
            return iptvItems.size();
        }

        @Override
        public Object getItem(int p) {
            return iptvItems.get(p);
        }

        @Override
        public long getItemId(int p) {
            return p;
        }

        @Override
        public View getView(int p, View convertView, ViewGroup parent) {
            LinearLayout row = (LinearLayout) (convertView != null ? convertView : buildActionRow());
            TextView title = row.findViewById(201);
            TextView sub = row.findViewById(202);
            IptvStore.Entry e = iptvItems.get(p);
            title.setText(e.name);
            sub.setText(e.group == null || e.group.isEmpty() ? "IPTV" : e.group + " · IPTV");
            boolean sel = p == iptvSel;
            row.setAlpha(sel ? 1f : 0.55f);
            row.setBackground(sel ? selectedRowBg() : null);
            return row;
        }
    };

    /** Root view that receives D-pad keys directly while the overlay window has focus. */
    private class OverlayRoot extends FrameLayout {
        OverlayRoot() {
            super(OverlayService.this);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            int code = event.getKeyCode();
            // Katman açıkken YouTube tuşu doğrudan pencereye gelirse de çalışsın.
            if (YoutubeLauncher.isYoutubeKey(event)) {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && event.getRepeatCount() == 0) {
                    openYouTube();
                }
                return true;
            }
            lastViewKeyAt = android.os.SystemClock.uptimeMillis();
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                android.util.Log.i("TCLP", "vkey=" + code + " rep=" + event.getRepeatCount());
                if (searchMode) {
                    stopRepeat();
                    if (code == KeyEvent.KEYCODE_BACK) {
                        exitSearch();
                        return true;
                    }
                    if (code == KeyEvent.KEYCODE_DPAD_UP) {
                        move(-1);
                        return true;
                    }
                    if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                        move(1);
                        return true;
                    }
                    if (code == KeyEvent.KEYCODE_DPAD_CENTER && !filtered.isEmpty()) {
                        tuneSelected();
                        return true;
                    }
                    boolean typing = (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z)
                            || (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9)
                            || code == KeyEvent.KEYCODE_SPACE
                            || code == KeyEvent.KEYCODE_DEL;
                    if (typing && (search == null || !search.hasFocus())) {
                        typeChar(code);
                        return true;
                    }
                    return super.dispatchKeyEvent(event);
                }
                if (tkgsMode) {
                    if (event.getRepeatCount() > 0) {
                        consumedDown = true;
                        return true;
                    }
                    stopRepeat();
                    if (handleVisible(code)) {
                        consumedDown = true;
                        return true;
                    }
                    return super.dispatchKeyEvent(event);
                }
                if (iptvMode) {
                    if (event.getRepeatCount() > 0) {
                        consumedDown = true;
                        return true;
                    }
                    stopRepeat();
                    if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                        int dir = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                        moveIptv(dir);
                        startIptvRepeat(dir);
                        consumedDown = true;
                        return true;
                    }
                    if (handleVisible(code)) {
                        consumedDown = true;
                        return true;
                    }
                    return super.dispatchKeyEvent(event);
                }
                if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (event.getRepeatCount() == 0) {
                        boolean handled = handleVisible(code);
                        if (handled && !searchMode) {
                            startRepeat(code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                        }
                        if (handled) {
                            consumedDown = true;
                            return true;
                        }
                        return super.dispatchKeyEvent(event);
                    }
                    // System repeats only keep the key held; our timer does the scrolling.
                    consumedDown = true;
                    return true;
                }
                // SOL/SAĞ: ilk basista sayfa, basili tutunca seri sayfa.
                if (!epgMode
                        && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                    if (event.getRepeatCount() == 0) {
                        boolean handled = handleVisible(code);
                        if (handled) {
                            startPageRepeat(code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                            consumedDown = true;
                            return true;
                        }
                    }
                    consumedDown = true;
                    return true;
                }
                // OK tekrarlari uzun basma sayacini tetiklemesin.
                if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)
                        && event.getRepeatCount() > 0) {
                    consumedDown = true;
                    return true;
                }
                stopRepeat();
                if (handleVisible(code)) {
                    consumedDown = true;
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
            stopRepeat();
            boolean consumed = consumedDown;
            consumedDown = false;
            if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && okUp()) {
                return true;
            }
            if (consumed) return true;
            return super.dispatchKeyEvent(event);
        }
    }

    private void ensureUi() {
        if (root != null) return;
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;

        root = new OverlayRoot();
        root.setBackgroundColor(0x33000000);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        root.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                showing = true;
                shownAt = android.os.SystemClock.uptimeMillis();
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                showing = false;
                stopRepeat();
                epgMode = false;
                tkgsMode = false;
                tkgsBusy = false;
                iptvMode = false;
                iptvBusy = false;
                searchMode = false;
                focusable = false;
                if (openedFromLiveTv) topWin = "com.tcl.player.TVActivity";
            }
        });

        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(0xF20E1116, 24, 0x33FFFFFF, 1));
        card.setPadding(dp(18), dp(12), dp(18), dp(8));

        int cw = clamp((int) (sw * 0.40f), dp(420), dp(760));
        int chh = clamp((int) (sh * 0.94f), dp(420), dp(900));
        cardHeight = chh;
        android.util.Log.i("TCLP", "ui screen=" + sw + "x" + sh + " card=" + cw + "x" + chh);

        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(cw, chh);
        cp.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
        cp.leftMargin = dp(56);
        root.addView(card, cp);

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        header = new TextView(this);
        header.setTextColor(0xFFFFFFFF);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.setLetterSpacing(0.14f);
        header.setPadding(dp(4), 0, 0, dp(6));
        headerRow.addView(header, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tkgsChip = new TextView(this);
        tkgsChip.setText("TKGS");
        tkgsChip.setTextColor(0xFF9BE8B0);
        tkgsChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tkgsChip.setLetterSpacing(0.08f);
        tkgsChip.setPadding(dp(9), dp(3), dp(9), dp(3));
        tkgsChip.setBackground(round(0x224CD964, 10, 0x664CD964, 1));
        LinearLayout.LayoutParams tkp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tkp.leftMargin = dp(6);
        headerRow.addView(tkgsChip, tkp);

        iptvChip = new TextView(this);
        iptvChip.setText("IPTV");
        iptvChip.setTextColor(0xFFFF9A9A);
        iptvChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        iptvChip.setLetterSpacing(0.08f);
        iptvChip.setPadding(dp(9), dp(3), dp(9), dp(3));
        iptvChip.setBackground(round(0x22FF5A5A, 10, 0x66FF5A5A, 1));
        LinearLayout.LayoutParams ikp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ikp.leftMargin = dp(6);
        headerRow.addView(iptvChip, ikp);

        card.addView(headerRow);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Kanal ara…");
        search.setTextColor(0xFFFFFFFF);
        search.setHintTextColor(0x66FFFFFF);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        search.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                hideKeyboard();
                if (root != null) root.requestFocus();
                return true;
            }
        });
        search.setBackground(round(0x1FFFFFFF, 12, 0x22FFFFFF, 1));
        search.setPadding(dp(12), dp(5), dp(12), dp(5));
        search.setVisibility(View.GONE);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                rebuildFiltered(s == null ? null : s.toString());
            }
        });
        card.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(android.R.color.transparent);
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setVerticalScrollBarEnabled(false);
        list.setFocusable(false);
        list.setFocusableInTouchMode(false);
        list.setItemsCanFocus(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(6);
        card.addView(list, lp);

        hint = new TextView(this);
        hint.setTextColor(0x66FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        hint.setPadding(dp(4), dp(4), 0, 0);
        setHint("OK: seç · SOL/SAĞ: sayfa · OK basılı: EPG · TKGS · IPTV");

        empty = new TextView(this);
        empty.setText("Bu kanal için EPG bilgisi yok");
        empty.setTextColor(0x88FFFFFF);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        empty.setPadding(dp(6), dp(10), 0, 0);
        empty.setVisibility(View.GONE);
        card.addView(empty);
        card.addView(hint);
    }

    /**
     * Alt ipucunda renk adı yazmadan işlev sözcüğünü tuş rengiyle boyar.
     * EPG/TKGS yeşil tuş, IPTV/KANAL LİSTESİ kırmızı tuş.
     */
    private void setHint(String text) {
        if (hint == null) return;
        SpannableString s = new SpannableString(text);
        colorWord(s, "EPG", 0xFF66D98B);
        colorWord(s, "TKGS", 0xFF66D98B);
        colorWord(s, "IPTV", 0xFFFF6B6B);
        colorWord(s, "KANAL LİSTESİ", 0xFFFF6B6B);
        hint.setText(s);
    }

    private static void colorWord(SpannableString s, String word, int color) {
        String text = s.toString();
        int at = text.indexOf(word);
        while (at >= 0) {
            s.setSpan(new ForegroundColorSpan(color), at, at + word.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            at = text.indexOf(word, at + word.length());
        }
    }

    private void resetSearchUi() {
        searchMode = false;
        if (search != null) {
            search.setVisibility(View.GONE);
            search.setText("");
        }
        hideKeyboard();
        setHint("OK: seç · SOL/SAĞ: sayfa · OK basılı: EPG · TKGS · IPTV");
        if (root != null) {
            root.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            root.requestFocus();
        }
    }

    private void hideKeyboard() {
        if (search == null) return;
        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    private void show() {
        android.util.Log.i("TCLP", "show() root=" + (root != null) + " showing=" + showing
                + " attached=" + (root != null && root.isAttachedToWindow()) + " wm=" + (wm != null));
        if (root == null) return;
        if (showing && !root.isAttachedToWindow()) {
            android.util.Log.i("TCLP", "show(): stale flag, window is gone");
            showing = false;
        }
        if (showing) {
            refreshRows();
            root.requestFocus();
            return;
        }
        if (root.isAttachedToWindow()) {
            try {
                wm.removeView(root);
            } catch (Exception ignored) {
            }
        }
        root.setVisibility(View.VISIBLE);
        WindowManager.LayoutParams p = params(false);
        try {
            wm.addView(root, p);
            showing = true;
            android.util.Log.i("TCLP", "overlay added " + p.width + "x" + p.height
                    + " type=" + p.type);
        } catch (Exception e) {
            showing = false;
            android.util.Log.e("TCLP", "addView failed: " + e);
            toast("Katman açılamadı: " + e.getClass().getSimpleName());
        }
        root.setAlpha(1f);
        root.post(new Runnable() {
            @Override
            public void run() {
                if (root != null) root.requestFocus();
            }
        });
    }

    private void hide() {
        stopRepeat();
        if (root == null || !showing) return;
        try {
            if (search != null) {
                InputMethodManager imm =
                        (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
            }
            wm.removeView(root);
        } catch (Exception ignored) {
        }
        showing = false;
        epgMode = false;
        searchMode = false;
        focusable = false;
        // Kendi penceremiz WINDOW_STATE_CHANGED (android.widget.FrameLayout) yayiyor;
        // kapaninca topWin canli TV'ye donmezse sonraki OK yakalanmaz.
        if (openedFromLiveTv) topWin = "com.tcl.player.TVActivity";
        if (root != null) {
            root.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
    }

    private WindowManager.LayoutParams params(boolean focus) {
        boolean appOverlay = android.provider.Settings.canDrawOverlays(this);
        int type = appOverlay
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (!appOverlay) flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                flags,
                android.graphics.PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        return p;
    }

    private void enterSearch() {
        searchMode = true;
        search.setVisibility(View.VISIBLE);
        search.setText("");
        search.setFocusable(true);
        search.setFocusableInTouchMode(true);
        focusable = true;
        resizeCard(true);
        if (hint != null) hint.setText("Ara · ✓ listeye dön · OK seç · GERİ çık");
        if (root != null) {
            root.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        }
        try {
            wm.updateViewLayout(root, params(true));
        } catch (Exception ignored) {
        }
        search.postDelayed(new Runnable() {
            @Override
            public void run() {
                search.requestFocus();
                InputMethodManager imm =
                        (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
                android.util.Log.i("TCLP", "search focus=" + search.hasFocus()
                        + " ime=" + (imm != null && imm.isActive()));
            }
        }, 260);
    }

    private void exitSearch() {
        if (!searchMode) return;
        searchMode = false;
        if (search != null) search.setVisibility(View.GONE);
        focusable = false;
        hideKeyboard();
        setHint("OK: seç · SOL/SAĞ: sayfa · OK basılı: EPG · TKGS · IPTV");
        try {
            if (root != null) wm.updateViewLayout(root, params(false));
        } catch (Exception ignored) {
        }
        if (root != null) {
            root.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            root.requestFocus();
        }
        resizeCard(false);
        rebuildFiltered(null);
    }

    private void resizeCard(boolean compact) {
        if (card == null) return;
        ViewGroup.LayoutParams lp = card.getLayoutParams();
        if (!(lp instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams cp = (FrameLayout.LayoutParams) lp;
        int sh = getResources().getDisplayMetrics().heightPixels;
        if (compact) {
            cp.height = Math.min(cardHeight, (int) (sh * 0.50f));
            cp.gravity = Gravity.TOP | Gravity.START;
            cp.topMargin = dp(28);
        } else {
            cp.height = cardHeight;
            cp.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
            cp.topMargin = 0;
        }
        card.setLayoutParams(cp);
    }

    private void rebuildFiltered(String query) {
        filtered.clear();
        String q = query == null ? "" : EpgStore.normalize(query);
        for (Channel c : ChannelStore.all()) {
            if (q.isEmpty() || EpgStore.normalize(c.name).contains(q) || c.number.startsWith(q)) {
                filtered.add(c);
            }
        }
        if (selected >= filtered.size()) selected = 0;
        android.util.Log.i("TCLP", "filter q=" + q + " n=" + filtered.size());
        if (list != null) {
            list.setAdapter(channelAdapter);
            if (!filtered.isEmpty()) list.setSelection(selected);
        }
    }

    private void refreshRows() {
        if (list != null && list.getAdapter() != null) {
            ((BaseAdapter) list.getAdapter()).notifyDataSetChanged();
        }
    }

    // ------------------------------------------------------------------ adapters

    private final BaseAdapter channelAdapter = new BaseAdapter() {
        @Override
        public int getCount() {
            return filtered.size();
        }

        @Override
        public Object getItem(int position) {
            return filtered.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Channel c = filtered.get(position);
            boolean sel = position == selected;
            LinearLayout row = (LinearLayout) (convertView != null ? convertView : buildRow());
            bindRow(row, c.number, c.name, subtitle(c), c, sel, false);
            return row;
        }
    };

    private final BaseAdapter epgAdapter = new BaseAdapter() {
        @Override
        public int getCount() {
            return epgItems.size();
        }

        @Override
        public Object getItem(int position) {
            return epgItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            EpgStore.Prog p = epgItems.get(position);
            boolean sel = position == selected;
            LinearLayout row = (LinearLayout) (convertView != null ? convertView : buildRow());
            String time = hhmm.format(new Date(p.start)) + "\n" + hhmm.format(new Date(p.stop));
            bindRow(row, time, p.title, null, null, sel, true);
            return row;
        }
    };

    private String subtitle(Channel c) {
        long minute = System.currentTimeMillis() / 60000L;
        if (minute != subtitleMinute) {
            subtitleMinute = minute;
            subtitleCache.clear();
        }
        String hit = subtitleCache.get(c.number);
        if (hit != null) return hit;
        EpgStore.Prog now = EpgStore.I.now(c);
        EpgStore.Prog nxt = EpgStore.I.next(c);
        StringBuilder sb = new StringBuilder();
        if (now != null) {
            sb.append(hhmm.format(new Date(now.start))).append("  ").append(now.title);
            if (nxt != null) sb.append("   ·   ").append(hhmm.format(new Date(nxt.start))).append("  ").append(nxt.title);
        } else if (nxt != null) {
            sb.append(hhmm.format(new Date(nxt.start))).append("  ").append(nxt.title);
        } else {
            sb.append("EPG yok");
        }
        String s = sb.toString();
        subtitleCache.put(c.number, s);
        return s;
    }

    private View buildRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), 0, dp(8), 0);

        TextView num = new TextView(this);
        num.setId(101);
        num.setTextColor(0xFF8FA3B8);
        num.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        num.setTypeface(Typeface.MONOSPACE);
        row.addView(num, new LinearLayout.LayoutParams(dp(46), ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout logo = new FrameLayout(this);
        logo.setId(102);
        row.addView(logo, new LinearLayout.LayoutParams(dp(34), dp(34)));

        ImageView logoImg = new ImageView(this);
        logoImg.setId(105);
        logoImg.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logoImg.setVisibility(View.GONE);
        logo.addView(logoImg, new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER));

        TextView logoText = new TextView(this);
        logoText.setId(106);
        logoText.setTextColor(0xFFFFFFFF);
        logoText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        logoText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        logoText.setGravity(Gravity.CENTER);
        logoText.setVisibility(View.GONE);
        logo.addView(logoText, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(8), 0, 0, 0);
        TextView name = new TextView(this);
        name.setId(103);
        name.setTextColor(0xFFFFFFFF);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setSingleLine(true);
        TextView sub = new TextView(this);
        sub.setId(104);
        sub.setTextColor(0xFF93A4B5);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(name);
        texts.addView(sub);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private void bindRow(LinearLayout row, String number, String name, String sub,
                         Channel logoChannel, boolean selected, boolean epgRow) {
        TextView num = row.findViewById(101);
        TextView title = row.findViewById(103);
        TextView subtitle = row.findViewById(104);
        FrameLayout logoBox = row.findViewById(102);

        num.setText(number);
        title.setText(name);
        subtitle.setText(sub == null ? "" : sub);
        subtitle.setVisibility(sub == null ? View.GONE : View.VISIBLE);

        LinearLayout.LayoutParams nlp = (LinearLayout.LayoutParams) num.getLayoutParams();
        nlp.width = dp(epgRow ? 80 : 46);
        num.setLayoutParams(nlp);
        num.setTextSize(TypedValue.COMPLEX_UNIT_SP, epgRow ? 11 : 13);
        num.setSingleLine(!epgRow);
        num.setGravity(epgRow ? (Gravity.CENTER_VERTICAL) : Gravity.CENTER_VERTICAL);
        num.setLineSpacing(0f, 0.95f);
        logoBox.setVisibility(epgRow ? View.GONE : View.VISIBLE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, epgRow ? 14 : 15);

        row.animate().cancel();
        row.setAlpha(selected ? 1f : 0.52f);
        row.setBackground(selected ? selectedRowBg() : null);
        ViewGroup.LayoutParams lp = row.getLayoutParams();
        if (lp == null) {
            lp = new android.widget.AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(epgRow ? 46 : 40));
            row.setLayoutParams(lp);
        } else {
            lp.height = dp(epgRow ? 46 : 40);
        }

        ImageView iv = row.findViewById(105);
        TextView fallback = row.findViewById(106);
        Bitmap bmp = logoChannel == null ? null : logoFor(logoChannel);
        if (bmp != null) {
            iv.setImageBitmap(bmp);
            iv.setVisibility(View.VISIBLE);
            fallback.setVisibility(View.GONE);
        } else {
            iv.setVisibility(View.GONE);
            fallback.setText(name == null || name.isEmpty() ? "?"
                    : name.substring(0, 1).toUpperCase(Locale.getDefault()));
            fallback.setBackground(colorBg(colorFor(name)));
            fallback.setVisibility(View.VISIBLE);
        }
    }

    private GradientDrawable selectedRowBg;

    private GradientDrawable selectedRowBg() {
        if (selectedRowBg == null) selectedRowBg = round(0x332E7CFF, 14, 0x553B7BFF, 1);
        return selectedRowBg;
    }

    private final Map<Integer, GradientDrawable> colorBgs = new HashMap<>();

    private GradientDrawable colorBg(int color) {
        GradientDrawable g = colorBgs.get(color);
        if (g == null) {
            g = new GradientDrawable();
            g.setShape(GradientDrawable.RECTANGLE);
            g.setCornerRadius(dp(12));
            g.setColor(color);
            colorBgs.put(color, g);
        }
        return g;
    }

    private static int colorFor(String s) {
        int h = s == null ? 0 : s.hashCode();
        float[] hsl = new float[]{Math.abs(h % 360), 0.45f, 0.34f};
        return Color.HSVToColor(hsl);
    }

    private Map<String, String> logoIndex;

    private void buildLogoIndex() {
        logoIndex = new HashMap<>();
        try {
            for (String f : getAssets().list("logos")) {
                if (!f.toLowerCase(Locale.US).endsWith(".png")) continue;
                String base = f.substring(0, f.length() - 4);
                String lower = base.toLowerCase(Locale.US);
                while (lower.endsWith("-tr") || lower.endsWith("-hz")) {
                    int cut = base.lastIndexOf('-');
                    if (cut <= 0) break;
                    base = base.substring(0, cut);
                    lower = base.toLowerCase(Locale.US);
                }
                String key = EpgStore.normalize(base);
                if (key.isEmpty()) continue;
                String prev = logoIndex.get(key);
                if (prev == null || f.length() < prev.length()) logoIndex.put(key, f);
            }
        } catch (Exception ignored) {
        }
    }

    private String findLogoAsset(Channel c) {
        if (logoIndex == null) buildLogoIndex();
        if (c.logo != null) {
            String direct = "logos/" + c.logo + ".png";
            if (assetExists(direct)) return direct;
        }
        String key = EpgStore.normalize(c.name);
        if (key.isEmpty()) return null;
        String exact = logoIndex.get(key);
        if (exact != null) return "logos/" + exact;
        String alias = logoAlias(key);
        if (alias != null) {
            String a = logoIndex.get(alias);
            if (a != null) return "logos/" + a;
        }
        String best = null;
        int bestLen = -1;
        for (Map.Entry<String, String> e : logoIndex.entrySet()) {
            String k = e.getKey();
            if (!(k.startsWith(key) || key.startsWith(k))) continue;
            int len = Math.min(k.length(), key.length());
            if (len > bestLen) {
                bestLen = len;
                best = e.getValue();
            }
        }
        return best == null ? null : "logos/" + best;
    }

    private static String logoAlias(String key) {
        if (key.contains("NATIONALGEOGRAPHIC")) {
            return key.contains("WILD") ? "NATGEOWILD" : "NATGEO";
        }
        if (key.startsWith("WILD")) return "NATGEOWILD";
        return null;
    }

    private boolean assetExists(String path) {
        try (java.io.InputStream in = getAssets().open(path)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Bitmap logoFor(Channel c) {
        String path;
        if (logoPathCache.containsKey(c.number)) {
            path = logoPathCache.get(c.number);
        } else {
            path = findLogoAsset(c);
            logoPathCache.put(c.number, path);
        }
        if (path == null) return null;
        Bitmap b = logoCache.get(path);
        if (b != null) return b;
        try (java.io.InputStream in = getAssets().open(path)) {
            Bitmap d = BitmapFactory.decodeStream(in);
            if (d != null) logoCache.put(path, d);
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ helpers

    private GradientDrawable round(int color, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke(dp(strokeDp), strokeColor);
        return g;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private void toast(String s) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(OverlayService.this, s, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
