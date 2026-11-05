package com.deepseek.dafeiyu.server;

import android.content.Context;
import android.content.SharedPreferences;

/** 服务端配置的读写（落地到 filesDir/server/config.json，同时缓存到 prefs 供 UI 快速读） */
public final class Cfg {
    private static final String F = "srv";
    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(F, Context.MODE_PRIVATE);
    }
    private Cfg() { }

    public static String get(Context c, String k, String def) {
        return sp(c).getString(k, def);
    }
    public static void set(Context c, String k, String v) {
        sp(c).edit().putString(k, v).apply();
    }
    public static boolean getBool(Context c, String k, boolean def) {
        return sp(c).getBoolean(k, def);
    }
    public static void setBool(Context c, String k, boolean v) {
        sp(c).edit().putBoolean(k, v).apply();
    }
    public static int getInt(Context c, String k, int def) {
        return sp(c).getInt(k, def);
    }
    public static void setInt(Context c, String k, int v) {
        sp(c).edit().putInt(k, v).apply();
    }
}
