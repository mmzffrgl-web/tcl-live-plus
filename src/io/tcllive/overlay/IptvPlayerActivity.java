package io.tcllive.overlay;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tam ekran IPTV oynatıcı.
 * VideoView/SurfaceView bazi TCL TV'lerde canli TV video duzleminin altinda kalip
 * sadece ses verebiliyor; bu yuzden video TextureView ile pencereye cizilir.
 */
public class IptvPlayerActivity extends Activity {

    /** Servis, oynatıcı açıkken tuşları (özellikle OK) yakalasın. */
    static volatile boolean active = false;
    /** Servisin OK tuşunu doğrudan oynatıcıya iletebilmesi için açık örnek. */
    private static volatile IptvPlayerActivity current;

    private TextureView texture;
    private MediaPlayer mp;
    private Surface surface;
    private TextView status;
    private boolean prepared = false;
    private int videoW = 0;
    private int videoH = 0;
    /** 0 = sığdır (letterbox), 1 = doldur (esnet), 2 = kırp (center-crop). */
    private int screenMode = 0;
    private boolean modeToast = false;
    private boolean okPending = false;
    private boolean backPending = false;
    private boolean finishing = false;
    private String url = "";
    private String channelName = "";
    private List<IptvStore.Entry> channels = new ArrayList<>();
    private int channelIndex = -1;
    private long lastZapAt = 0;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable hideModeToast = new Runnable() {
        @Override
        public void run() {
            if (modeToast) {
                modeToast = false;
                if (prepared) status.setVisibility(View.GONE);
            }
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        active = true;
        current = this;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        requestAudioFocus();
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        String rawUrl = getIntent() == null ? null : getIntent().getStringExtra("url");
        url = rawUrl == null ? "" : rawUrl.trim();
        String rawName = getIntent() == null ? null : getIntent().getStringExtra("name");
        channelName = rawName == null ? "" : rawName.trim();
        IptvStore.loadCache(this);
        channels = IptvStore.all();
        channelIndex = findChannel(url, channelName);
        if (url.isEmpty() && !channels.isEmpty()) {
            channelIndex = 0;
            url = channels.get(0).url;
            channelName = channels.get(0).name;
        }
        android.util.Log.i("TCLP", "iptv player channels=" + channels.size()
                + " idx=" + channelIndex + " name=" + channelName);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        texture = new TextureView(this);
        root.addView(texture, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        status = new TextView(this);
        status.setText("Yayın yükleniyor…");
        status.setTextColor(0xFFB9C7D6);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(24), dp(16), dp(24), dp(16));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        sp.gravity = Gravity.CENTER;
        root.addView(status, sp);

        setContentView(root);
        texture.requestFocus();

        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                if (surface != null) surface.release();
                surface = new Surface(st);
                android.util.Log.i("TCLP", "iptv texture available " + w + "x" + h);
                startPlayer();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                updateTransform();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                releasePlayer();
                if (surface != null) {
                    surface.release();
                    surface = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });

        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!prepared) {
                    status.setText("Yayın yükleniyor…\nYavaş veya coğrafi engelli olabilir.");
                }
            }
        }, 20000);
    }

    private void startPlayer() {
        if (url.isEmpty() || surface == null || mp != null) return;
        try {
            mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "TCL-Live-Plus/1.0 (Android TV)");
            mp.setDataSource(this, Uri.parse(url), headers);
            mp.setSurface(surface);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer m) {
                    prepared = true;
                    status.setVisibility(View.GONE);
                    m.setLooping(false);
                    m.start();
                    android.util.Log.i("TCLP", "iptv player prepared " + url);
                    texture.post(new Runnable() {
                        @Override
                        public void run() {
                            updateTransform();
                        }
                    });
                }
            });
            mp.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                @Override
                public void onVideoSizeChanged(MediaPlayer m, int w, int h) {
                    videoW = w;
                    videoH = h;
                    android.util.Log.i("TCLP", "iptv video size " + w + "x" + h);
                    updateTransform();
                }
            });
            mp.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                @Override
                public boolean onInfo(MediaPlayer m, int what, int extra) {
                    if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                        // Yayın başladıktan sonra ekranda bilgi kartı gösterme.
                        if (!prepared) {
                            status.setText("Arabelleğe alınıyor…");
                            status.setVisibility(View.VISIBLE);
                        }
                    } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                        if (prepared) status.setVisibility(View.GONE);
                    }
                    return false;
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer m, int what, int extra) {
                    android.util.Log.w("TCLP", "iptv player error " + what + "/" + extra + " " + url);
                    showError("Yayın açılamadı (" + what + "/" + extra + ")\nBaşka bir kanal deneyin.");
                    return true;
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer m) {
                    showError("Yayın sona erdi");
                }
            });
            android.util.Log.i("TCLP", "iptv player open " + url);
            mp.prepareAsync();
        } catch (Throwable t) {
            android.util.Log.w("TCLP", "iptv player start failed: " + t);
            showError("Yayın açılamadı: " + t.getClass().getSimpleName());
        }
    }

    /**
     * TextureView varsayılan olarak görüntüyü pencereye esnetir.
     * Bu yüzden matris, video pikselleriyle değil pencere (view) oranıyla hesaplanır.
     * 0: sığdır (kenar boşluklu), 1: doldur (esnet), 2: kırp (kenarlardan taşır).
     */
    private void updateTransform() {
        if (texture == null || videoW <= 0 || videoH <= 0) return;
        int vw = texture.getWidth();
        int vh = texture.getHeight();
        if (vw <= 0 || vh <= 0) return;

        if (screenMode == 1) {
            texture.setTransform(new Matrix());
            return;
        }

        float fit = Math.min((float) vw / videoW, (float) vh / videoH);
        float fill = Math.max((float) vw / videoW, (float) vh / videoH);
        float scale = screenMode == 2 ? fill : fit;
        float scaleX = scale * videoW / vw;
        float scaleY = scale * videoH / vh;
        float dx = (vw - videoW * scale) / 2f;
        float dy = (vh - videoH * scale) / 2f;
        Matrix matrix = new Matrix();
        matrix.setScale(scaleX, scaleY);
        matrix.postTranslate(dx, dy);
        texture.setTransform(matrix);
    }

    private void showMode() {
        String[] names = {"Sığdır", "Doldur", "Kırp"};
        modeToast = true;
        android.util.Log.i("TCLP", "iptv screen mode=" + screenMode + " " + names[screenMode]);
        status.setText("Ekran: " + names[screenMode]);
        status.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideModeToast);
        ui.postDelayed(hideModeToast, 1500);
    }

    private void releasePlayer() {
        prepared = false;
        if (mp != null) {
            try {
                mp.reset();
            } catch (Exception ignored) {
            }
            try {
                mp.release();
            } catch (Exception ignored) {
            }
            mp = null;
        }
    }

    private void showError(String msg) {
        modeToast = false;
        ui.removeCallbacks(hideModeToast);
        status.setText(msg);
        status.setVisibility(View.VISIBLE);
    }

    private void toggle() {
        if (mp == null || !prepared) return;
        try {
            modeToast = false;
            ui.removeCallbacks(hideModeToast);
            if (mp.isPlaying()) {
                mp.pause();
                status.setText("Duraklatıldı");
                status.setVisibility(View.VISIBLE);
            } else {
                mp.start();
                status.setVisibility(View.GONE);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Servis OK tuşunu yuttuğunda oynatıcıyı listeye döndürür.
     * Ana iş parçacığına post edilir; Activity yaşamıyor olsa bile güvenlidir.
     */
    static void returnNow() {
        final IptvPlayerActivity a = current;
        if (a == null) return;
        a.ui.post(new Runnable() {
            @Override
            public void run() {
                a.returnToList();
            }
        });
    }

    /** OK: oynatıcıyı kapat, IPTV listesine aynı kanal seçili olarak dön. */
    private void returnToList() {
        if (finishing) return;
        finishing = true;
        okPending = false;
        backPending = false;
        try {
            Intent i = new Intent("io.tcllive.overlay.SHOW_IPTV");
            i.setPackage("io.tcllive.overlay");
            i.putExtra("url", url);
            sendBroadcast(i);
            android.util.Log.i("TCLP", "iptv player -> list");
        } catch (Throwable t) {
            android.util.Log.w("TCLP", "iptv return broadcast failed: " + t);
        }
        // Pencereyi kısa süre daha açık tut: kumandanın OK bırakma (UP) olayı
        // TCL canlı TV'ye ulaşıp arka planda kanal açmasın.
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }, 280);
    }

    private int findChannel(String u, String n) {
        for (int i = 0; i < channels.size(); i++) {
            if (u != null && !u.isEmpty() && u.equals(channels.get(i).url)) return i;
        }
        if (n != null && !n.isEmpty()) {
            for (int i = 0; i < channels.size(); i++) {
                if (n.equals(channels.get(i).name)) return i;
            }
        }
        return -1;
    }

    /** Servis UP/DOWN tuşunu yakaladığında kanalı değiştirir. */
    static void zap(final int dir) {
        final IptvPlayerActivity a = current;
        if (a == null || a.finishing) return;
        a.ui.post(new Runnable() {
            @Override
            public void run() {
                a.changeChannel(dir);
            }
        });
    }

    /** IPTV listesinde bir önceki/sonraki kanala geçer. */
    private void changeChannel(int dir) {
        if (finishing) return;
        if (channels.isEmpty()) {
            IptvStore.loadCache(this);
            channels = IptvStore.all();
            if (channels.isEmpty()) {
                android.util.Log.w("TCLP", "iptv zap: liste boş");
                return;
            }
            channelIndex = findChannel(url, channelName);
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastZapAt < 220) return; // a11y + Activity çift olayını yut
        lastZapAt = now;

        int size = channels.size();
        if (channelIndex < 0) {
            channelIndex = dir > 0 ? 0 : size - 1;
        } else {
            channelIndex = (channelIndex + dir + size) % size;
        }
        IptvStore.Entry e = channels.get(channelIndex);
        url = e.url;
        channelName = e.name;
        android.util.Log.i("TCLP", "iptv zap dir=" + dir + " idx=" + channelIndex
                + " name=" + e.name + " url=" + e.url);

        modeToast = false;
        ui.removeCallbacks(hideModeToast);
        status.setText(e.name.isEmpty() ? "Yayın yükleniyor…" : e.name);
        status.setVisibility(View.VISIBLE);
        videoW = 0;
        videoH = 0;
        releasePlayer();
        startPlayer();
    }

    /**
     * OK ve GERİ tuşlarının hem DOWN hem UP olayını burada tüketiyoruz.
     * Aksi halde oynatıcı kapanınca UP olayı TCL canlı TV'ye ulaşıp kanal açıyor.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();
        android.util.Log.i("TCLP", "player key code=" + keyCode + " scan="
                + event.getScanCode() + " action=" + action + " rep=" + event.getRepeatCount());

        // YouTube tuşu (Generic.kl: key 245 TCL_YOUTUBE)
        if (YoutubeLauncher.isYoutubeKey(event)) {
            if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                YoutubeLauncher.open(this);
            }
            return true;
        }

        // UP/DOWN: IPTV listesinde bir önceki/sonraki kanal.
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_CHANNEL_UP
                || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
            if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                boolean up = keyCode == KeyEvent.KEYCODE_DPAD_UP
                        || keyCode == KeyEvent.KEYCODE_CHANNEL_UP;
                changeChannel(up ? -1 : 1);
            }
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) okPending = true;
            } else if (action == KeyEvent.ACTION_UP) {
                if (okPending) {
                    okPending = false;
                    returnToList();
                }
            }
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) backPending = true;
            } else if (action == KeyEvent.ACTION_UP) {
                if (backPending) {
                    backPending = false;
                    finish();
                }
            }
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
            if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) toggle();
            return true;
        }

        // YEŞİL: ekran modu (Sığdır / Doldur / Kırp)
        if (keyCode == KeyEvent.KEYCODE_PROG_GREEN) {
            if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                screenMode = (screenMode + 1) % 3;
                updateTransform();
                showMode();
            }
            return true;
        }

        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        active = true;
    }

    @Override
    protected void onPause() {
        active = false;
        if (mp != null) {
            try {
                if (mp.isPlaying()) mp.pause();
            } catch (Exception ignored) {
            }
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        active = false;
        if (current == this) current = null;
        ui.removeCallbacksAndMessages(null);
        releasePlayer();
        if (surface != null) {
            surface.release();
            surface = null;
        }
        abandonAudioFocus();
        super.onDestroy();
    }

    private void requestAudioFocus() {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) {
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            }
        } catch (Exception ignored) {
        }
    }

    private void abandonAudioFocus() {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) am.abandonAudioFocus(null);
        } catch (Exception ignored) {
        }
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
