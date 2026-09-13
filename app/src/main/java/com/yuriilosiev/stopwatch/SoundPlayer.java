
package com.yuriilosiev.stopwatch;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/**
 * Проигрывание сигналов таймера раундов.
 *
 * Почему не Web Audio: WebView играет в поток музыки и микшируется с плеером на равных —
 * в наушниках сигнал тонет. Здесь звук идёт потоком будильника (своя громкость, свой приоритет),
 * а на время сигнала запрашивается аудиофокус с приглушением, поэтому музыка сама делается тише.
 */
public final class SoundPlayer {

    private static final String PREFS = "sounds";
    private static final String K_CUSTOM = "custom_uri_";     // + слот
    private static final String K_NAME   = "custom_name_";

    private static MediaPlayer player;
    private static AudioFocusRequest focusRequest;
    private static final Handler handler = new Handler(Looper.getMainLooper());

    private SoundPlayer() { }

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /* ---------- Пользовательские звуки ---------- */

    public static void saveCustom(Context c, String slot, String uri, String name) {
        p(c).edit().putString(K_CUSTOM + slot, uri).putString(K_NAME + slot, name).apply();
    }

    public static String customUri(Context c, String slot) {
        return p(c).getString(K_CUSTOM + slot, null);
    }

    public static String customName(Context c, String slot) {
        return p(c).getString(K_NAME + slot, "");
    }

    /** JSON со слотами пользовательских звуков — читает интерфейс, чтобы подписать пункты списка. */
    public static String customListJson(Context c) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 1; i <= 3; i++) {
            String slot = String.valueOf(i);
            if (i > 1) sb.append(',');
            sb.append('"').append(slot).append("\":\"")
              .append(customName(c, slot).replace("\\", "").replace("\"", "'"))
              .append('"');
        }
        return sb.append('}').toString();
    }

    /* ---------- Воспроизведение ---------- */

    /**
     * @param kind   gong | whistle | beep | warn | custom1 | custom2 | custom3
     * @param volume 0..100, громкость сигнала относительно потока будильника
     */
    public static synchronized void play(Context ctx, String kind, int volume) {
        if (kind == null || "none".equals(kind)) return;
        final Context app = ctx.getApplicationContext();
        appCtx = app;   // чтобы stop() всегда мог отпустить аудиофокус

        stop();   // предыдущий сигнал обрываем, иначе фазы наложатся друг на друга

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)                    // поток будильника
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(attrs);

            if (kind.startsWith("custom")) {
                String uri = customUri(app, kind.substring(6));
                if (uri == null) { mp.release(); return; }
                mp.setDataSource(app, Uri.parse(uri));
            } else {
                int res = resFor(app, kind);
                if (res == 0) { mp.release(); return; }
                android.content.res.AssetFileDescriptor afd = app.getResources().openRawResourceFd(res);
                if (afd == null) { mp.release(); return; }
                mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                afd.close();
            }

            float v = Math.max(0f, Math.min(100, volume)) / 100f;
            mp.setVolume(v, v);
            mp.setOnCompletionListener(m -> stop());
            mp.setOnErrorListener((m, what, extra) -> { stop(); return true; });
            mp.prepare();

            requestDuck(app, attrs);
            mp.start();
            player = mp;

            // страховка: если плеер зависнет, фокус всё равно вернётся музыке
            handler.postDelayed(SoundPlayer::stop, 8000);
        } catch (Exception e) {
            stop();
        }
    }

    private static int resFor(Context c, String kind) {
        switch (kind) {
            case "gong":    return R.raw.cue_gong;
            case "bell":    return R.raw.cue_bell;
            case "whistle": return R.raw.cue_whistle;
            case "beep":    return R.raw.cue_beep;
            case "warn":    return R.raw.cue_warn;
            default:        return 0;
        }
    }

    /** Просим систему приглушить чужую музыку на время сигнала. */
    private static void requestDuck(Context c, AudioAttributes attrs) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(attrs)
                        .setWillPauseWhenDucked(false)
                        .build();
                am.requestAudioFocus(focusRequest);
            } else {
                am.requestAudioFocus(null, AudioManager.STREAM_ALARM,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            }
        } catch (Exception ignored) { }
    }

    private static void abandonFocus(Context c) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (focusRequest != null) { am.abandonAudioFocusRequest(focusRequest); focusRequest = null; }
            } else {
                am.abandonAudioFocus(null);
            }
        } catch (Exception ignored) { }
    }

    /** Останавливает сигнал и возвращает музыке прежнюю громкость. */
    public static synchronized void stop() {
        handler.removeCallbacksAndMessages(null);
        MediaPlayer mp = player;
        player = null;
        if (mp != null) {
            try { if (mp.isPlaying()) mp.stop(); } catch (Exception ignored) { }
            try { mp.release(); } catch (Exception ignored) { }
        }
        if (appCtx != null) abandonFocus(appCtx);
    }

    /** Контекст для отпускания фокуса из статического stop(). */
    private static Context appCtx;
    public static void init(Context c) { appCtx = c.getApplicationContext(); }
}
