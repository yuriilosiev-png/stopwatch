package com.yuriilosiev.stopwatch;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;

import java.util.Calendar;

/**
 * Единое состояние обратного отсчёта: хранится в SharedPreferences,
 * чтобы переживать перезапуск процесса и перезагрузку устройства.
 * Источник истины по времени — абсолютная метка targetMs (System.currentTimeMillis).
 */
public final class CountdownStore {

    private static final String PREFS = "countdown";
    private static final String K_TARGET = "target";
    private static final String K_TITLE  = "title";
    private static final String K_SHADE  = "shade";
    private static final String K_SOUND  = "sound";
    private static final String K_ACTIVE = "active";

    private CountdownStore() { }

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void save(Context c, long targetMs, String title, boolean shade, boolean sound) {
        p(c).edit()
                .putLong(K_TARGET, targetMs)
                .putString(K_TITLE, title == null ? "" : title)
                .putBoolean(K_SHADE, shade)
                .putBoolean(K_SOUND, sound)
                .putBoolean(K_ACTIVE, true)
                .apply();
    }

    public static void setShade(Context c, boolean shade) { p(c).edit().putBoolean(K_SHADE, shade).apply(); }

    public static void clear(Context c) { p(c).edit().putBoolean(K_ACTIVE, false).apply(); }

    public static boolean isActive(Context c) { return p(c).getBoolean(K_ACTIVE, false); }
    public static long   target(Context c)    { return p(c).getLong(K_TARGET, 0L); }
    public static String title(Context c)     { return p(c).getString(K_TITLE, ""); }
    public static boolean shade(Context c)    { return p(c).getBoolean(K_SHADE, false); }
    public static boolean sound(Context c)    { return p(c).getBoolean(K_SOUND, true); }

    /**
     * Календарное разложение остатка: годы/месяцы/дни считаются по календарю,
     * а не делением на 365 — как и в веб-части.
     */
    public static String formatRemaining(Resources res, long fromMs, long toMs) {
        if (toMs <= fromMs) return "00:00:00";

        Calendar a = Calendar.getInstance(); a.setTimeInMillis(fromMs);
        Calendar b = Calendar.getInstance(); b.setTimeInMillis(toMs);

        int y  = b.get(Calendar.YEAR)         - a.get(Calendar.YEAR);
        int mo = b.get(Calendar.MONTH)        - a.get(Calendar.MONTH);
        int d  = b.get(Calendar.DAY_OF_MONTH) - a.get(Calendar.DAY_OF_MONTH);
        int h  = b.get(Calendar.HOUR_OF_DAY)  - a.get(Calendar.HOUR_OF_DAY);
        int mi = b.get(Calendar.MINUTE)       - a.get(Calendar.MINUTE);
        int s  = b.get(Calendar.SECOND)       - a.get(Calendar.SECOND);

        if (s < 0)  { s += 60; mi--; }
        if (mi < 0) { mi += 60; h--; }
        if (h < 0)  { h += 24; d--; }
        if (d < 0) {
            Calendar prev = (Calendar) b.clone();
            prev.add(Calendar.MONTH, -1);
            d += prev.getActualMaximum(Calendar.DAY_OF_MONTH);
            mo--;
        }
        if (mo < 0) { mo += 12; y--; }

        StringBuilder sb = new StringBuilder();
        if (y > 0)  sb.append(y).append(' ').append(res.getString(R.string.u_y)).append(' ');
        if (y > 0 || mo > 0) sb.append(mo).append(' ').append(res.getString(R.string.u_mo)).append(' ');
        if (y > 0 || mo > 0 || d > 0) sb.append(d).append(' ').append(res.getString(R.string.u_d)).append(' ');
        sb.append(String.format("%02d:%02d:%02d", h, mi, s));
        return sb.toString();
    }

    /** Дольше суток — обновлять шторку раз в минуту, ближе к нулю — раз в секунду. */
    public static long tickInterval(long remainMs) {
        return remainMs > 24L * 60 * 60 * 1000 ? 60000L : 1000L;
    }
}
