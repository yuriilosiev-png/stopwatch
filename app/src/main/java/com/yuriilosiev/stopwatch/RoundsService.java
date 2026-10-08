package com.yuriilosiev.stopwatch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Таймер раундов в нативной части.
 *
 * Зачем: интерфейс живёт в WebView, а система усыпляет его вместе с экраном —
 * JavaScript-таймер замирает, и сигналы фаз пропадают. Здесь расписание фаз
 * считает foreground service с частичным wake lock: процессор не засыпает,
 * звук уходит в наушники и при заблокированном экране, а в шторке видно фазу.
 *
 * Время берётся от системных часов, а не от числа срабатываний обработчика,
 * поэтому даже если система придержит поток, фаза закончится вовремя.
 */
public class RoundsService extends Service {

    public static final String CHANNEL_ID = "rounds_timer";
    private static final int NOTIF_ID = 102;

    public static final String ACTION_START = "rounds.start";
    public static final String ACTION_STOP  = "rounds.stop";

    public static final String EX_PHASES = "phases";   // JSON: [{n,d,s}, ...]
    public static final String EX_CYCLES = "cycles";
    public static final String EX_WARN   = "warn";     // сигнал за 10 секунд
    public static final String EX_WARN_S = "warnSound";
    public static final String EX_VOLUME = "volume";

    /* Состояние читает интерфейс, когда возвращается на передний план. */
    public static volatile boolean RUNNING = false;
    public static volatile int  CUR_PHASE = 0;
    public static volatile int  CUR_CYCLE = 1;
    public static volatile long PHASE_END = 0;
    public static volatile String LAST_ERROR = "";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable ticker;
    private PowerManager.WakeLock wake;

    private String[] names = new String[0];
    private int[]    durs  = new int[0];      // секунды
    private String[] snds  = new String[0];

    private int cycles = 1;
    private boolean warn = true;
    private String warnSound = "warn";
    private int volume = 80;
    private boolean warned = false;

    /* ---------- запуск и остановка ---------- */

    public static void start(Context ctx, String phasesJson, int cycles,
                             boolean warn, String warnSound, int volume) {
        Intent i = new Intent(ctx, RoundsService.class)
                .setAction(ACTION_START)
                .putExtra(EX_PHASES, phasesJson)
                .putExtra(EX_CYCLES, cycles)
                .putExtra(EX_WARN, warn)
                .putExtra(EX_WARN_S, warnSound)
                .putExtra(EX_VOLUME, volume);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Exception e) {
            LAST_ERROR = "startService: " + e;
        }
    }

    public static void stop(Context ctx) {
        try { ctx.stopService(new Intent(ctx, RoundsService.class)); } catch (Exception ignored) { }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!readPhases(intent)) { stopSelf(); return START_NOT_STICKY; }

        cycles    = Math.max(1, intent.getIntExtra(EX_CYCLES, 1));
        warn      = intent.getBooleanExtra(EX_WARN, true);
        warnSound = intent.getStringExtra(EX_WARN_S);
        if (warnSound == null) warnSound = "warn";
        volume    = Math.max(0, Math.min(100, intent.getIntExtra(EX_VOLUME, 80)));

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, buildNotification());
            }
            LAST_ERROR = "";
        } catch (Exception e) {
            LAST_ERROR = String.valueOf(e);
            stopSelf();
            return START_NOT_STICKY;
        }

        acquireWake();
        RUNNING = true;
        CUR_PHASE = 0;
        CUR_CYCLE = 1;
        beginPhase();
        return START_STICKY;
    }

    /** Разбор списка фаз. Пустые фазы отбрасываем, иначе таймер зациклится вхолостую. */
    private boolean readPhases(Intent intent) {
        try {
            JSONArray arr = new JSONArray(intent.getStringExtra(EX_PHASES));
            int n = arr.length();
            String[] nm = new String[n];
            int[] dd = new int[n];
            String[] ss = new String[n];
            int k = 0;
            for (int i = 0; i < n; i++) {
                JSONObject o = arr.getJSONObject(i);
                int d = o.optInt("d", 0);
                if (d <= 0) continue;
                nm[k] = o.optString("n", "");
                dd[k] = d;
                ss[k] = o.optString("s", "none");
                k++;
            }
            if (k == 0) return false;
            names = new String[k]; durs = new int[k]; snds = new String[k];
            System.arraycopy(nm, 0, names, 0, k);
            System.arraycopy(dd, 0, durs,  0, k);
            System.arraycopy(ss, 0, snds,  0, k);
            return true;
        } catch (Exception e) {
            LAST_ERROR = "phases: " + e;
            return false;
        }
    }

    /* ---------- ход фаз ---------- */

    private void beginPhase() {
        warned = false;
        PHASE_END = System.currentTimeMillis() + durs[CUR_PHASE] * 1000L;
        SoundPlayer.play(this, snds[CUR_PHASE], volume);   // сигнал в начале фазы
        updateNotification();
        scheduleTick();
    }

    private void nextPhase() {
        CUR_PHASE++;
        if (CUR_PHASE >= durs.length) {
            CUR_PHASE = 0;
            CUR_CYCLE++;
            if (CUR_CYCLE > cycles) { finish(); return; }
        }
        beginPhase();
    }

    private void finish() {
        SoundPlayer.play(this, "gong", volume);
        // даём гонгу доиграть, потом убираем уведомление
        handler.postDelayed(this::stopSelf, 2500);
        RUNNING = false;
    }

    private void scheduleTick() {
        if (ticker != null) handler.removeCallbacks(ticker);
        ticker = new Runnable() {
            @Override
            public void run() {
                if (!RUNNING) return;
                long left = PHASE_END - System.currentTimeMillis();

                if (warn && !warned && left <= 10_000 && left > 0) {
                    warned = true;
                    SoundPlayer.play(RoundsService.this, warnSound, volume);
                }
                if (left <= 0) { nextPhase(); return; }

                updateNotification();
                handler.postDelayed(this, 500);
            }
        };
        handler.postDelayed(ticker, 500);
    }

    /* ---------- система ---------- */

    private void acquireWake() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "roundtrack:rounds");
            wake.setReferenceCounted(false);
            wake.acquire(4 * 60 * 60 * 1000L);   // страховка: не держим дольше четырёх часов
        } catch (Exception e) {
            LAST_ERROR = "wake: " + e;
        }
    }

    private void releaseWake() {
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception ignored) { }
        wake = null;
    }

    private void updateNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification());
        } catch (Exception e) {
            LAST_ERROR = "notify: " + e;
        }
    }

    private Notification buildNotification() {
        long left = Math.max(0, PHASE_END - System.currentTimeMillis());
        int sec = (int) ((left + 999) / 1000);
        String time = String.format("%02d:%02d", sec / 60, sec % 60);

        String name = (names.length > 0 && CUR_PHASE < names.length) ? names[CUR_PHASE] : "";
        String title = name.isEmpty() ? getString(R.string.ch_rounds) : name;
        String body = time + "   ·   " + CUR_CYCLE + " / " + cycles;

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, RoundsService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        b.setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(0, getString(R.string.btn_stop_alarm), stop);

        return b.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, getString(R.string.ch_rounds), NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.ch_rounds_desc));
        ch.setShowBadge(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    /** Состояние для интерфейса: фаза, цикл и момент конца фазы. */
    public static String stateJson() {
        return "{\"running\":" + RUNNING
                + ",\"phase\":" + CUR_PHASE
                + ",\"cycle\":" + CUR_CYCLE
                + ",\"end\":" + PHASE_END + "}";
    }

    @Override
    public void onDestroy() {
        RUNNING = false;
        if (ticker != null) handler.removeCallbacks(ticker);
        handler.removeCallbacksAndMessages(null);
        SoundPlayer.stop();
        releaseWake();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
