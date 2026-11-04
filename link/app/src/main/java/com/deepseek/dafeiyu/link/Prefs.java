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

    /** 上次同步时服务端返回的分片 hash 列表（JSON 数组字符串） */
    public String syncHashes() { return sp.getString("syncHashes", ""); }

    public void saveSyncHashes(String json) { sp.edit().putString("syncHashes", json).commit(); }

    public boolean ready() {
        return !host().isEmpty() && Crypto.fromHexOrB64(key()) != null;
    }
}
