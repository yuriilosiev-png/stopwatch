package com.yuriilosiev.stopwatch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Срабатывает точно в момент события (setAlarmClock не откладывается в Doze). */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!CountdownStore.isActive(ctx)) return;
        if (CountdownStore.sound(ctx)) {
            AlarmService.start(ctx);
        } else {
            CountdownStore.clear(ctx);
            CountdownService.stop(ctx);
        }
    }
}
