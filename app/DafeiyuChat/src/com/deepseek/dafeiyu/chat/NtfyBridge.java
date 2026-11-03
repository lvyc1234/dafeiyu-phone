package com.deepseek.dafeiyu.chat;

import android.app.Activity;
import android.content.Intent;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * JS <-> 原生 ntfy 桥。
 *
 * 4.4 兼容点：
 *  - HTTPS 走 TlsCompat：API<24 强制 TLS1.2 并补 ISRG Root X1 信任锚
 *  - 所有请求都在线程池里跑，回调通过 evaluateJavascript（API 19）回到页面
 *  - AutoX 目标改多包名探测，4.4 上 AutoX.js 6 装不上，会落到 Auto.js 4.1.1
 */
public class NtfyBridge {
    private static final String TAG = "DafeiyuBridge";
    private final Activity activity;
    private final WebView webView;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private static final String BASE = "https://ntfy.envs.net/";

    public NtfyBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        Log.i(TAG, "bridge ready, tlsPatch=" + TlsCompat.needsPatch());
    }

    /** Launch AutoX/Auto.js ShortcutActivity to run a small local script. */
    @JavascriptInterface
    public void runScript(final String path) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent intent = AutoJs.scriptIntent(activity, path);
                    if (intent == null) {
                        Log.w(TAG, "no AutoX/Auto.js installed");
                        return;
                    }
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(intent);
                } catch (Exception e) {
                    Log.w(TAG, "runScript failed: " + e);
                }
            }
        });
    }

    @JavascriptInterface
    public void poll(final String topic, String since, final String callbackId) {
        final String fixedSince = since;
        pool.execute(new Runnable() {
            @Override
            public void run() {
                Result result;
                try {
                    String t = URLEncoder.encode(topic, "UTF-8");
                    String s = URLEncoder.encode(fixedSince == null || fixedSince.length() == 0
                            ? "10m" : fixedSince, "UTF-8");
                    result = request("GET", BASE + t + "/json?poll=1&since=" + s, null);
                } catch (Exception e) {
                    result = new Result(false, 0, "poll error: " + e);
                }
                deliver(callbackId, result);
            }
        });
    }

    @JavascriptInterface
    public void publish(final String topic, final String text, final String callbackId) {
        pool.execute(new Runnable() {
            @Override
            public void run() {
                Result result;
                try {
                    String t = URLEncoder.encode(topic, "UTF-8");
                    result = request("POST", BASE + t, text == null ? "" : text);
                } catch (Exception e) {
                    result = new Result(false, 0, "publish error: " + e);
                }
                deliver(callbackId, result);
            }
        });
    }

    /** Upload a picked image as an ntfy attachment; the phone agent downloads it into its inbox. */
    @JavascriptInterface
    public void uploadImage(final String topic, final String dataUrl, final String filename,
                            final String callbackId) {
        pool.execute(new Runnable() {
            @Override
            public void run() {
                Result result;
                HttpURLConnection conn = null;
                try {
                    String raw = dataUrl == null ? "" : dataUrl;
                    int comma = raw.indexOf(',');
                    if (raw.startsWith("data:") && comma >= 0) raw = raw.substring(comma + 1);
                    byte[] bytes = Base64.decode(raw, Base64.DEFAULT);
                    if (bytes.length == 0) throw new Exception("empty image");
                    if (bytes.length > 12 * 1024 * 1024) throw new Exception("image too large");

                    String name = filename == null ? "upload.jpg"
                            : filename.replaceAll("[^A-Za-z0-9._-]", "_");
                    if (!name.matches("(?i).*\\.(jpg|jpeg|png|webp|gif)$")) name += ".jpg";
                    String lower = name.toLowerCase();
                    String mime = lower.endsWith(".png") ? "image/png"
                            : lower.endsWith(".webp") ? "image/webp"
                            : lower.endsWith(".gif") ? "image/gif" : "image/jpeg";

                    String t = URLEncoder.encode(topic, "UTF-8");
                    conn = (HttpURLConnection) new URL(BASE + t).openConnection();
                    TlsCompat.prepare(activity, conn);
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(60000);
                    conn.setRequestMethod("PUT");
                    conn.setDoOutput(true);
                    conn.setRequestProperty("User-Agent", "DafeiyuChat/1.2");
                    conn.setRequestProperty("Filename", name);
                    conn.setRequestProperty("Title", "Master");
                    conn.setRequestProperty("Tags", "phone");
                    conn.setRequestProperty("Message", "uploaded image");
                    conn.setRequestProperty("Content-Type", mime);
                    conn.setFixedLengthStreamingMode(bytes.length);
                    OutputStream os = conn.getOutputStream();
                    try {
                        os.write(bytes);
                    } finally {
                        os.close();
                    }
                    int code = conn.getResponseCode();
                    InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                    String response = stream == null ? "" : readAll(stream);
                    result = new Result(code >= 200 && code < 300, code, response);
                } catch (Exception e) {
                    result = new Result(false, 0, "upload error: " + e);
                } finally {
                    if (conn != null) conn.disconnect();
                }
                deliver(callbackId, result);
            }
        });
    }

    private void deliver(final String callbackId, final Result result) {
        final String js = "window.__nativeResult(" +
                JSONObject.quote(callbackId) + "," +
                (result.ok ? "true" : "false") + "," +
                result.status + "," +
                JSONObject.quote(result.body) + ")";
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    webView.evaluateJavascript(js, null);
                } catch (Throwable t) {
                    Log.w(TAG, "evaluateJavascript failed: " + t);
                }
            }
        });
    }

    private Result request(String method, String urlText, String body) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlText);
            conn = (HttpURLConnection) url.openConnection();
            TlsCompat.prepare(activity, conn);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod(method);
            conn.setRequestProperty("User-Agent", "DafeiyuChat/1.2");
            if ("POST".equals(method)) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
                conn.setRequestProperty("Title", "Master");
                conn.setRequestProperty("Tags", "phone");
                byte[] data = body.getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(data.length);
                OutputStream os = conn.getOutputStream();
                try {
                    os.write(data);
                } finally {
                    os.close();
                }
            }
            int code = conn.getResponseCode();
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String response = stream == null ? "" : readAll(stream);
            return new Result(code >= 200 && code < 300, code, response);
        } catch (Exception e) {
            return new Result(false, 0, String.valueOf(e));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String readAll(InputStream stream) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = stream.read(buffer)) != -1) out.write(buffer, 0, n);
        return out.toString("UTF-8");
    }

    private static class Result {
        final boolean ok;
        final int status;
        final String body;

        Result(boolean ok, int status, String body) {
            this.ok = ok;
            this.status = status;
            this.body = body == null ? "" : body;
        }
    }
}
