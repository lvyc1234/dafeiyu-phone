package com.deepseek.dafeiyu.server;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 在 App 内拉起内置的 node（jniLibs/arm64-v8a/libnode.so）跑 server.js。
 * 装完就有完整服务端：加密通道 + Web + 记忆 + 自主生活 + 节点树工具。
 */
public final class NodeRunner {

    private static final String TAG = "NodeRunner";
    private static Process proc;
    private static final List<String> LOGS = new ArrayList<>();
    private static volatile boolean running = false;

    private NodeRunner() { }

    public static boolean isRunning() { return running && proc != null && proc.isAlive(); }

    public static List<String> logs(int n) {
        synchronized (LOGS) {
            int from = Math.max(0, LOGS.size() - n);
            return new ArrayList<>(LOGS.subList(from, LOGS.size()));
        }
    }

    private static void log(String s) {
        Log.i(TAG, s);
        synchronized (LOGS) {
            LOGS.add(s);
            while (LOGS.size() > 300) LOGS.remove(0);
        }
    }

    /** 首次运行把 assets/server 解到 filesDir/server */
    private static File ensureFiles(Context ctx) throws Exception {
        File dir = new File(ctx.getFilesDir(), "server");
        File marker = new File(dir, ".ready");
        if (!marker.exists()) {
            copyAssetDir(ctx, "server", dir);
            // 默认 config
            File cfg = new File(dir, "config.json");
            if (!cfg.exists()) {
                File def = new File(dir, "config.default.json");
                if (def.exists()) {
                    java.nio.file.Files.copy(def.toPath(), cfg.toPath());
                }
            }
            marker.createNewFile();
            log("已解压服务端到 " + dir.getAbsolutePath());
        }
        // 目录可能被系统清过，补齐关键文件
        if (!new File(dir, "server.js").exists()) {
            copyAssetDir(ctx, "server", dir);
        }
        return dir;
    }

    private static void copyAssetDir(Context ctx, String assetPath, File outDir) throws Exception {
        String[] list = ctx.getAssets().list(assetPath);
        if (list == null) return;
        if (!outDir.exists()) outDir.mkdirs();
        for (String name : list) {
            String childAsset = assetPath + "/" + name;
            File childOut = new File(outDir, name);
            String[] sub = ctx.getAssets().list(childAsset);
            if (sub != null && sub.length > 0) {
                copyAssetDir(ctx, childAsset, childOut);
            } else {
                try (InputStream in = ctx.getAssets().open(childAsset);
                     OutputStream os = new FileOutputStream(childOut)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
            }
        }
    }

    /** 写 config.json（API key、端口、密钥） */
    public static void writeConfig(Context ctx, String apiKey, String baseUrl, String model,
                                   String keyBase64, int port, String nick) throws Exception {
        File dir = ensureFiles(ctx);
        File cfg = new File(dir, "config.json");
        org.json.JSONObject j;
        try {
            j = new org.json.JSONObject(readAll(cfg));
        } catch (Exception e) {
            j = new org.json.JSONObject();
        }
        j.put("port", port);
        j.put("keyBase64", keyBase64);
        j.put("nick", nick);
        j.put("apiKey", apiKey);
        j.put("baseUrl", baseUrl);
        j.put("model", model);
        if (!j.has("proactive")) {
            j.put("proactive", new org.json.JSONObject()
                    .put("minGapMin", 1).put("maxGapMin", 60)
                    .put("quietHours", new org.json.JSONArray().put(1).put(8)).put("maxPerDay", 12));
        }
        if (!j.has("life")) {
            j.put("life", new org.json.JSONObject()
                    .put("minGapMin", 10).put("maxGapMin", 20)
                    .put("quietHours", new org.json.JSONArray().put(0).put(8)).put("enabled", true));
        }
        try (FileOutputStream os = new FileOutputStream(cfg)) {
            os.write(j.toString(2).getBytes("UTF-8"));
        }
        log("config.json 已更新");
    }

    /** 读服务端 config.json：域名、密钥、端口、昵称 */
    public static org.json.JSONObject readServerConfig(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), "server/config.json");
            if (!f.exists()) return null;
            return new org.json.JSONObject(new String(
                    java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    /** 本机当前对外 IPv6（拿不到返回空） */
    public static String localIpv6() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifs = java.net.NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                java.net.NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    java.net.InetAddress a = as.nextElement();
                    String s = a.getHostAddress();
                    if (s != null && s.indexOf(':') > 0 && !s.startsWith("fe80")) {
                        int pct = s.indexOf('%');
                        return pct > 0 ? s.substring(0, pct) : s;
                    }
                }
            }
        } catch (Exception ignored) { }
        return "";
    }

    /** 让服务端跑一个一次性动作（比如生成日记）—— 通过调试口发指令 */
    public static void runOnce(Context ctx, String action) {
        new Thread(() -> {
            try (java.net.Socket sk = new java.net.Socket("127.0.0.1", 19528)) {
                sk.setSoTimeout(3000);
                sk.getOutputStream().write((action + "\n").getBytes("UTF-8"));
            } catch (Exception e) {
                log("runOnce 失败: " + e.getMessage());
            }
        }, "run-once").start();
    }

    /** 清空服务端消息库 */
    public static void clearHistory(Context ctx) throws Exception {
        File f = new File(ctx.getFilesDir(), "server/messages.jsonl");
        if (f.exists()) f.delete();
        File dir = new File(ctx.getFilesDir(), "server/history");
        File[] all = dir.listFiles();
        if (all != null) for (File x : all) x.delete();
    }

    private static String readAll(File f) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
    }

    /** 启动 node server.js */
    public static synchronized boolean start(Context ctx) {
        if (isRunning()) return true;
        try {
            File dir = ensureFiles(ctx);
            String nativeDir = ctx.getApplicationInfo().nativeLibraryDir;
            File nodeBin = new File(nativeDir, "libnode.so");
            if (!nodeBin.exists()) {
                log("找不到 " + nodeBin + "，本机架构可能不是 arm64");
                return false;
            }
            ProcessBuilder pb = new ProcessBuilder(nodeBin.getAbsolutePath(), "server.js");
            pb.directory(dir);
            pb.redirectErrorStream(true);
            java.util.Map<String, String> env = pb.environment();
            env.put("LD_LIBRARY_PATH", nativeDir);
            env.put("HOME", ctx.getFilesDir().getAbsolutePath());
            env.put("PATH", "/system/bin:/system/xbin");
            env.put("TMPDIR", ctx.getCacheDir().getAbsolutePath());
            proc = pb.start();

            final Process p = proc;
            Thread t = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = r.readLine()) != null) log(line);
                } catch (Exception ignored) { }
                log("node 进程退出");
                running = false;
            }, "node-log");
            t.setDaemon(true);
            t.start();
            running = true;
            log("node 已启动: " + nodeBin.getAbsolutePath());
            return true;
        } catch (Exception e) {
            log("启动失败: " + e.getMessage());
            return false;
        }
    }

    public static synchronized void stop() {
        running = false;
        try { if (proc != null) proc.destroy(); } catch (Exception ignored) { }
        proc = null;
    }
}
