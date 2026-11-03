package com.deepseek.dafeiyu.boot;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/**
 * Auto.js / AutoX.js 目标探测。
 *
 * 安卓 4.4（API 19）上 AutoX.js 6 装不上（其 minSdk 为 24），
 * 能跑的是 Auto.js 4.1.1（org.autojs.autojs）之类老版本，
 * 所以这里按顺序探测多个包名，而不是写死 org.autojs.autoxjs.v6。
 */
final class AutoJs {
    /** {包名, ShortcutActivity 类名, 显示名} */
    private static final String[][] CANDIDATES = new String[][]{
            {"org.autojs.autoxjs.v6", "org.autojs.autojs.external.shortcut.ShortcutActivity", "AutoX.js 6"},
            {"org.autojs.autoxjs", "org.autojs.autojs.external.shortcut.ShortcutActivity", "AutoX.js"},
            {"org.autojs.autojs", "org.autojs.autojs.external.shortcut.ShortcutActivity", "Auto.js 4.1.1"},
            {"com.stardust.autojs", "com.stardust.autojs.external.shortcut.ShortcutActivity", "Auto.js (stardust)"}
    };

    private AutoJs() {
    }

    /** 返回已安装的第一个目标，没有则 null。 */
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

    static boolean installed(Context context) {
        return find(context) != null;
    }

    static String label(Context context) {
        String[] item = find(context);
        return item == null ? "未安装" : item[2] + "（" + item[0] + "）";
    }

    /** 构造直接启动脚本的 Intent；找不到目标时返回 null。 */
    static Intent scriptIntent(Context context, String path) {
        String[] item = find(context);
        if (item == null) return null;
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(item[0], item[1]));
        intent.putExtra("path", path);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return intent;
    }
}
