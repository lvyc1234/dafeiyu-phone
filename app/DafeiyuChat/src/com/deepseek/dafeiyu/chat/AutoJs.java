package com.deepseek.dafeiyu.chat;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/**
 * Auto.js / AutoX.js 目标探测（4.4 只能装 Auto.js 4.1.1 一类的老版本）。
 */
final class AutoJs {
    private static final String[][] CANDIDATES = new String[][]{
            {"org.autojs.autoxjs.v6", "org.autojs.autojs.external.shortcut.ShortcutActivity", "AutoX.js 6"},
            {"org.autojs.autoxjs", "org.autojs.autojs.external.shortcut.ShortcutActivity", "AutoX.js"},
            {"org.autojs.autojs", "org.autojs.autojs.external.shortcut.ShortcutActivity", "Auto.js 4.1.1"},
            {"com.stardust.autojs", "com.stardust.autojs.external.shortcut.ShortcutActivity", "Auto.js (stardust)"}
    };

    private AutoJs() {
    }

    static String[] find(Context context) {
        PackageManager pm = context.getPackageManager();
        for (String[] item : CANDIDATES) {
            try {
                if (pm.getLaunchIntentForPackage(item[0]) != null) return item;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    static Intent scriptIntent(Context context, String path) {
        String[] item = find(context);
        if (item == null) return null;
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(item[0], item[1]));
        intent.putExtra("path", path);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }
}
