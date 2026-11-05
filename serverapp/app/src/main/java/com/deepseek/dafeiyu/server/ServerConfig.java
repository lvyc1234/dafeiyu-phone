package com.deepseek.dafeiyu.server;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/** 把 UI 里的设置写进 config.json（node 读的是这个文件） */
public final class ServerConfig {
    private ServerConfig() { }

    public static JSONObject read(Context c) {
        try {
            File f = new File(c.getFilesDir(), "server/config.json");
            if (!f.exists()) return new JSONObject();
            return new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static void write(Context c, JSONObject j) throws Exception {
        File f = new File(c.getFilesDir(), "server/config.json");
        f.getParentFile().mkdirs();
        try (java.io.FileOutputStream os = new java.io.FileOutputStream(f)) {
            os.write(j.toString(2).getBytes("UTF-8"));
        }
    }

    /** 从 prefs 汇总写回 config.json */
    public static void saveAll(Context c) throws Exception {
        JSONObject j = read(c);
        j.put("port", Cfg.getInt(c, "port", 9527));
        j.put("keyBase64", Cfg.get(c, "keyBase64", ""));
        j.put("nick", Cfg.get(c, "nick", "大肥鱼"));
        j.put("domain", Cfg.get(c, "domain", ""));
        j.put("apiKey", Cfg.get(c, "apiKey", ""));
        j.put("baseUrl", Cfg.get(c, "baseUrl", "https://opencode.ai/zen/go/v1"));
        j.put("model", Cfg.get(c, "model", "deepseek-v4.1-flash"));

        JSONObject pro = j.optJSONObject("proactive");
        if (pro == null) pro = new JSONObject();
        pro.put("minGapMin", Cfg.getInt(c, "proMin", 1));
        pro.put("maxGapMin", Cfg.getInt(c, "proMax", 60));
        pro.put("maxPerDay", Cfg.getInt(c, "proCap", 12));
        pro.put("quietHours", new JSONArray()
                .put(Cfg.getInt(c, "proQuietStart", 1))
                .put(Cfg.getInt(c, "proQuietEnd", 8)));
        j.put("proactive", pro);

        JSONObject life = j.optJSONObject("life");
        if (life == null) life = new JSONObject();
        life.put("enabled", Cfg.getBool(c, "lifeOn", true));
        life.put("minGapMin", Cfg.getInt(c, "lifeMin", 10));
        life.put("maxGapMin", Cfg.getInt(c, "lifeMax", 20));
        life.put("quietHours", new JSONArray()
                .put(Cfg.getInt(c, "lifeQuietStart", 0))
                .put(Cfg.getInt(c, "lifeQuietEnd", 8)));
        j.put("life", life);

        write(c, j);
    }

    /** 首次启动把 prefs 从 config.json 回填一次 */
    public static void loadIntoPrefs(Context c) {
        JSONObject j = read(c);
        if (j.length() == 0) return;
        Cfg.setInt(c, "port", j.optInt("port", 9527));
        Cfg.set(c, "keyBase64", j.optString("keyBase64", ""));
        Cfg.set(c, "nick", j.optString("nick", "大肥鱼"));
        Cfg.set(c, "domain", j.optString("domain", ""));
        Cfg.set(c, "apiKey", j.optString("apiKey", ""));
        Cfg.set(c, "baseUrl", j.optString("baseUrl", "https://opencode.ai/zen/go/v1"));
        Cfg.set(c, "model", j.optString("model", "deepseek-v4.1-flash"));
        JSONObject pro = j.optJSONObject("proactive");
        if (pro != null) {
            Cfg.setInt(c, "proMin", pro.optInt("minGapMin", 1));
            Cfg.setInt(c, "proMax", pro.optInt("maxGapMin", 60));
            Cfg.setInt(c, "proCap", pro.optInt("maxPerDay", 12));
            JSONArray q = pro.optJSONArray("quietHours");
            if (q != null && q.length() >= 2) {
                Cfg.setInt(c, "proQuietStart", q.optInt(0, 1));
                Cfg.setInt(c, "proQuietEnd", q.optInt(1, 8));
            }
        }
        JSONObject life = j.optJSONObject("life");
        if (life != null) {
            Cfg.setBool(c, "lifeOn", life.optBoolean("enabled", true));
            Cfg.setInt(c, "lifeMin", life.optInt("minGapMin", 10));
            Cfg.setInt(c, "lifeMax", life.optInt("maxGapMin", 20));
            JSONArray q = life.optJSONArray("quietHours");
            if (q != null && q.length() >= 2) {
                Cfg.setInt(c, "lifeQuietStart", q.optInt(0, 0));
                Cfg.setInt(c, "lifeQuietEnd", q.optInt(1, 8));
            }
        }
    }
}
