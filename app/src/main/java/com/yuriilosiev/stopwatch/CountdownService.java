package com.yuriilosiev.stopwatch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Живой отсчёт в шторке. Foreground service переживает сворачивание и блокировку экрана;
 * текст пересчитывается от системных часов, поэтому дрейфа не бывает.
 */
public class CountdownService extends Service {

    public static final String CHANNEL_ID = "countdown_shade";
    private static final int NOTIF_ID = 101;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable ticker;

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, CountdownService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, CountdownService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!CountdownStore.isActive(this) || !CountdownStore.shade(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, buildNotification());
        scheduleTick();
        return START_STICKY;   // система вернёт сервис, если убьёт его при нехватке памяти
    }

    private void scheduleTick() {
        if (ticker != null) handler.removeCallbacks(ticker);
        ticker = new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                long remain = CountdownStore.target(CountdownService.this) - now;

                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) nm.notify(NOTIF_ID, buildNotification());

                if (remain <= 0) { stopSelf(); return; }   // звук поднимет AlarmReceiver
                handler.postDelayed(this, CountdownStore.tickInterval(remain));
            }
        };
        handler.postDelayed(ticker, CountdownStore.tickInterval(
                CountdownStore.target(this) - System.currentTimeMillis()));
    }

    private Notification buildNotification() {
        long now = System.currentTimeMillis();
        long target = CountdownStore.target(this);
        String title = CountdownStore.title(this);
        if (title == null || title.trim().isEmpty()) title = getString(R.string.cd_event);

        String body = target > now
                ? CountdownStore.formatRemaining(getResources(), now, target)
                : getString(R.string.cd_done);

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        : PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        b.setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            b.setVisibility(Notification.VISIBILITY_PUBLIC);   // видно на локскрине
        }
        return b.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, getString(R.string.ch_shade), NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.ch_shade_desc));
        ch.setShowBadge(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    @Override
    public void onDestroy() {
        if (ticker != null) handler.removeCallbacks(ticker);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
