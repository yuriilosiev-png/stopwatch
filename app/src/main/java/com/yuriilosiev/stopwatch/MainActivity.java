package com.yuriilosiev.stopwatch;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Оболочка WebView. HTML лежит в assets — приложение полностью работает без интернета.
 * Внешних библиотек нет, только системный SDK.
 */
public class MainActivity extends Activity {

    public static final int ALARM_REQUEST = 7001;
    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage — состояние отсчёта
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        web.setWebViewClient(new WebViewClient());
        web.setBackgroundColor(0xFF0A0A0F);
        web.addJavascriptInterface(new Bridge(this), "Android");
        web.loadUrl("file:///android_asset/index.html");

        requestNotificationPermission();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);   // не убиваем процесс — отсчёт продолжает жить
    }

    /* ================= Мост JS -> Android ================= */
    public static class Bridge {
        private final Context ctx;
        Bridge(Context c) { this.ctx = c.getApplicationContext(); }

        /** Вызывается из index.html при старте отсчёта. */
        @JavascriptInterface
        public void startCountdown(String targetMs, String title, boolean shade, boolean sound) {
            long target;
            try { target = Long.parseLong(targetMs); } catch (Exception e) { return; }
            if (target <= System.currentTimeMillis()) return;

            CountdownStore.save(ctx, target, title, shade, sound);
            scheduleAlarm(ctx, target, sound);
            if (shade) CountdownService.start(ctx); else CountdownService.stop(ctx);
        }

        @JavascriptInterface
        public void stopCountdown() {
            cancelAlarm(ctx);
            CountdownStore.clear(ctx);
            CountdownService.stop(ctx);
            AlarmService.stop(ctx);
        }

        @JavascriptInterface
        public void updateShade(boolean shade) {
            CountdownStore.setShade(ctx, shade);
            if (shade && CountdownStore.isActive(ctx)) CountdownService.start(ctx);
            else CountdownService.stop(ctx);
        }

        /** Диагностика для строки состояния под тумблером. */
        @JavascriptInterface
        public String status() {
            boolean notifEnabled = true;
            boolean channelOn = true;
            try {
                NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    notifEnabled = nm.areNotificationsEnabled();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        NotificationChannel ch = nm.getNotificationChannel(CountdownService.CHANNEL_ID);
                        channelOn = ch == null || ch.getImportance() != NotificationManager.IMPORTANCE_NONE;
                    }
                }
            } catch (Exception ignored) { }

            return "{\"active\":" + CountdownStore.isActive(ctx)
                    + ",\"shade\":" + CountdownStore.shade(ctx)
                    + ",\"running\":" + CountdownService.RUNNING
                    + ",\"notifEnabled\":" + notifEnabled
                    + ",\"channelOn\":" + channelOn
                    + ",\"error\":" + (CountdownService.LAST_ERROR.isEmpty()
                            ? "null" : "\"" + CountdownService.LAST_ERROR.replace("\"", "'") + "\"")
                    + "}";
        }
    }

    /* ================= Точный будильник ================= */
    static PendingIntent alarmIntent(Context ctx) {
        Intent i = new Intent(ctx, AlarmReceiver.class);
        return PendingIntent.getBroadcast(ctx, ALARM_REQUEST, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * setAlarmClock — единственный тип, который не откладывается в Doze:
     * сигнал прозвучит даже при заблокированном экране и спящем устройстве.
     */
    static void scheduleAlarm(Context ctx, long targetMs, boolean sound) {
        if (!sound) { cancelAlarm(ctx); return; }
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent show = PendingIntent.getActivity(ctx, ALARM_REQUEST + 1,
                new Intent(ctx, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setAlarmClock(new AlarmManager.AlarmClockInfo(targetMs, show), alarmIntent(ctx));
    }

    static void cancelAlarm(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(alarmIntent(ctx));
    }
}
