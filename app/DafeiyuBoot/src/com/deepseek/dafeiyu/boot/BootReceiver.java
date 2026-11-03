package com.deepseek.dafeiyu.boot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * 开机广播。4.4 上没有 startForegroundService，直接 startService。
 * 另外兼容部分国产 ROM 的 QUICKBOOT_POWERON。
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "DafeiyuBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action);
        if (!boot) return;
        Log.i(TAG, "boot broadcast: " + action);
        Intent service = new Intent(context, BootService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (Exception e) {
            Log.e(TAG, "start service failed", e);
        }
    }
}
