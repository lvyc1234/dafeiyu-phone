package com.deepseek.dafeiyu.link;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {

    private static final String F = "link";
    public static final int DEFAULT_PORT = 9527;

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(F, Context.MODE_PRIVATE);
    }

    public String host() { return sp.getString("host", ""); }
    public int port() { return sp.getInt("port", DEFAULT_PORT); }
    public String nick() { return sp.getString("nick", "我"); }
    public String key() { return sp.getString("key", ""); }

    public void save(String host, int port, String nick, String key) {
        sp.edit().putString("host", host).putInt("port", port)
                .putString("nick", nick).putString("key", key).apply();
    }

    public void setKey(String key) {
        sp.edit().putString("key", key).apply();
    }

    /** 是否显示悬浮状态面板（设置页的开关） */
    public boolean overlay() { return sp.getBoolean("overlay", false); }

    public void setOverlay(boolean on) { sp.edit().putBoolean("overlay", on).apply(); }

    /** 悬浮面板左上角坐标；Integer.MIN_VALUE = 没存过（首次用默认位置） */
    public int panelX() { return sp.getInt("panelX", Integer.MIN_VALUE); }
    public int panelY() { return sp.getInt("panelY", Integer.MIN_VALUE); }

    public void savePanelPos(int x, int y) {
        sp.edit().putInt("panelX", x).putInt("panelY", y).apply();
    }

    /** 上次同步时服务端返回的分片 hash 列表（JSON 数组字符串） */
    public String syncHashes() { return sp.getString("syncHashes", ""); }

    public void saveSyncHashes(String json) { sp.edit().putString("syncHashes", json).commit(); }

    public boolean ready() {
        return !host().isEmpty() && Crypto.fromHexOrB64(key()) != null;
    }
}
