package com.yuriilosiev.stopwatch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * Сигнал по достижении нуля: «пи-пи, пи-пи» ровно 30 секунд, затем автостоп.
 * Работает при заблокированном экране — WakeLock + канал важности HIGH.
 */
public class AlarmService extends Service {

    public static final String CHANNEL_ID = "countdown_alarm";
    public static final String ACTION_STOP = "com.yuriilosiev.stopwatch.STOP_ALARM";
    private static final int NOTIF_ID = 102;

    private static final long DURATION_MS = 30000L;   // общая длительность сигнала
    private static final long PERIOD_MS   = 1600L;    // период повтора группы «пи-пи, пи-пи»

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ToneGenerator tone;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;
    private long endAt;
    private Runnable beat;

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, AlarmService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, AlarmService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        createChannel();
        startForeground(NOTIF_ID, buildNotification());

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stopwatch:alarm");
            wakeLock.acquire(DURATION_MS + 5000L);
        }

        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        try {
            tone = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
        } catch (RuntimeException e) {
            tone = null;   // аудиосистема занята — останется вибрация и уведомление
        }

        endAt = System.currentTimeMillis() + DURATION_MS;
        beat = new Runnable() {
            @Override
            public void run() {
                if (System.currentTimeMillis() >= endAt) { stopSelf(); return; }
                beepGroup();
                handler.postDelayed(this, PERIOD_MS);
            }
        };
        handler.post(beat);

        // страховочный автостоп ровно через 30 секунд
        handler.postDelayed(this::stopSelf, DURATION_MS);
        return START_NOT_STICKY;
    }

    /** «пи-пи» — пауза — «пи-пи» */
    private void beepGroup() {
        beep(0); beep(180); beep(500); beep(680);
        long[] pattern = {0, 120, 60, 120, 200, 120, 60, 120};
        if (vibrator != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                vibrator.vibrate(pattern, -1);
            }
        }
    }

    private void beep(long delayMs) {
        if (tone == null) return;
        handler.postDelayed(() -> {
            try { tone.startTone(ToneGenerator.TONE_PROP_BEEP, 110); } catch (Exception ignored) { }
        }, delayMs);
    }

    private Notification buildNotification() {
        String title = CountdownStore.title(this);
        if (title == null || title.trim().isEmpty()) title = getString(R.string.cd_event);

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        : PendingIntent.FLAG_UPDATE_CURRENT);

        Intent stopIntent = new Intent(this, AlarmService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        : PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        b.setContentTitle(title)
                .setContentText(getString(R.string.cd_done))
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentIntent(open)
                .setAutoCancel(false)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        null, getString(R.string.btn_stop_alarm), stopPi).build());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            b.setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setFullScreenIntent(open, true);   // показать поверх локскрина
        }
        return b.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, getString(R.string.ch_alarm), NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription(getString(R.string.ch_alarm_desc));
        ch.enableVibration(true);
        ch.setBypassDnd(false);
        ch.setSound(null, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        nm.createNotificationChannel(ch);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (tone != null) { try { tone.release(); } catch (Exception ignored) { } tone = null; }
        if (vibrator != null) vibrator.cancel();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        CountdownStore.clear(this);
        CountdownService.stop(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
