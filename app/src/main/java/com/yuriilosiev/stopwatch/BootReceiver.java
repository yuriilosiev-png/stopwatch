package com.yuriilosiev.stopwatch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** После перезагрузки восстанавливаем и будильник, и шторку — отсчёт не теряется. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!CountdownStore.isActive(ctx)) return;

        long target = CountdownStore.target(ctx);
        long now = System.currentTimeMillis();

        if (target <= now) {          // событие прошло, пока устройство было выключено
            CountdownStore.clear(ctx);
            return;
        }
        MainActivity.scheduleAlarm(ctx, target, CountdownStore.sound(ctx));
        if (CountdownStore.shade(ctx)) CountdownService.start(ctx);
    }
}
