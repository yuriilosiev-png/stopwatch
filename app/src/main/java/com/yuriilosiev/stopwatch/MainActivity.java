package com.yuriilosiev.stopwatch;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Оболочка WebView. Интерфейс живёт на GitHub Pages и обновляется без пересборки APK;
 * офлайн обеспечивает service worker, который кэширует страницу при первом запуске.
 */
public class MainActivity extends Activity {

    public static final int ALARM_REQUEST = 7001;

    /** Единственный адрес интерфейса. Origin всегда один — localStorage не теряется. */
    private static final String APP_URL = "https://yuriilosiev-png.github.io/stopwatch/index.html";
    private static final String APP_HOST = "yuriilosiev-png.github.io";

    private static final int PICK_SOUND = 9100;

    private WebView web;
    /** Слот пользовательского звука, для которого сейчас открыт выбор файла. */
    private String pendingSlot = null;

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
        s.setAllowFileAccess(false);           // assets при этом остаются доступны
        s.setAllowContentAccess(false);

        web.setWebViewClient(new WebViewClient() {
            /** Наружные ссылки уводим в браузер, внутри WebView живёт только своя страница. */
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (u != null && APP_HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }

            /** Сети нет и в кэше пусто — показываем заглушку из пакета. */
            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req != null && req.isForMainFrame()) {
                    view.loadUrl("file:///android_asset/offline.html");
                }
            }
        });

        web.setBackgroundColor(0xFF0A0A0F);
        web.addJavascriptInterface(new Bridge(this), "Android");
        web.loadUrl(APP_URL);

        SoundPlayer.init(this);

        requestNotificationPermission();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    /* ================= Выбор своего звука ================= */

    /** Открывает системный выбор файла. Слот запоминаем, чтобы знать, куда записать результат. */
    void openSoundPicker(String slot) {
        pendingSlot = slot;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(i, PICK_SOUND); } catch (Exception e) { pendingSlot = null; }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_SOUND || pendingSlot == null) return;

        final String slot = pendingSlot;
        pendingSlot = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;

        Uri uri = data.getData();
        try {
            // Без этого доступ к файлу пропадёт после перезапуска приложения.
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { }

        SoundPlayer.saveCustom(this, slot, uri.toString(), displayName(uri));
        // Сообщаем интерфейсу, что список звуков изменился.
        web.post(() -> web.evaluateJavascript(
                "window.onCustomSoundsChanged && window.onCustomSoundsChanged();", null));
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String n = c.getString(idx);
                    if (n != null) return n.length() > 28 ? n.substring(0, 28) + "…" : n;
                }
            }
        } catch (Exception ignored) { }
        return "Свой звук";
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);   // не убиваем процесс — отсчёт продолжает жить
    }

    /* ================= Мост JS -> Android ================= */
    public static class Bridge {
        private final Context ctx;
        private final MainActivity activity;
        Bridge(MainActivity a) { this.activity = a; this.ctx = a.getApplicationContext(); }

        /** Открыть системный выбор аудиофайла для слота 1..3. */
        @JavascriptInterface
        public void pickSound(final String slot) {
            activity.runOnUiThread(() -> activity.openSoundPicker(slot));
        }

        /** Сигнал фазы: поток будильника + приглушение чужой музыки. */
        @JavascriptInterface
        public void playCue(String kind, int volume) {
            SoundPlayer.play(ctx, kind, volume);
        }

        @JavascriptInterface
        public void stopCue() {
            SoundPlayer.stop();
        }

        /** Имена выбранных пользователем звуков по слотам, JSON. */
        @JavascriptInterface
        public String customSounds() {
            return SoundPlayer.customListJson(ctx);
        }

        /** Версия моста: страница может проверить, что умеет установленный APK. */
        @JavascriptInterface
        public int bridgeVersion() { return 3; }

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
