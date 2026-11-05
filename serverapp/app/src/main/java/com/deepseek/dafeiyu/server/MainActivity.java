package com.deepseek.dafeiyu.server;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

/** 服务端控制台：启动/停止 node、看状态、出二维码 */
public class MainActivity extends Activity {

    private TextView status, logView, addrView;
    private ImageView qr;
    private EditText apiKey;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("大肥鱼 · 服务端");
        title.setTextSize(22);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(status);

        addrView = new TextView(this);
        addrView.setTextSize(12);
        addrView.setTextIsSelectable(true);
        root.addView(addrView);

        LinearLayout btns = new LinearLayout(this);
        Button start = new Button(this);
        start.setText("启动");
        start.setOnClickListener(v -> startServer());
        Button stop = new Button(this);
        stop.setText("停止");
        stop.setOnClickListener(v -> stopServer());
        Button mkQr = new Button(this);
        mkQr.setText("生成二维码");
        mkQr.setOnClickListener(v -> buildQr());
        btns.addView(start);
        btns.addView(stop);
        btns.addView(mkQr);
        root.addView(btns);

        apiKey = new EditText(this);
        apiKey.setHint("模型 API Key（填完点保存）");
        apiKey.setTextSize(13);
        root.addView(apiKey);
        Button saveKey = new Button(this);
        saveKey.setText("保存配置");
        saveKey.setOnClickListener(v -> saveConfig());
        root.addView(saveKey);

        qr = new ImageView(this);
        root.addView(qr, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView sc = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTextIsSelectable(true);
        sc.addView(logView);
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 11);
        }
        startServer();
        tick();
    }

    private void startServer() {
        Intent i = new Intent(this, ServerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
        status.setText("启动中…");
    }

    private void stopServer() {
        stopService(new Intent(this, ServerService.class));
        NodeRunner.stop();
        status.setText("已停止");
    }

    private void saveConfig() {
        try {
            JSONObject c = NodeRunner.readServerConfig(this);
            String key = apiKey.getText().toString().trim();
            NodeRunner.writeConfig(this,
                    key.isEmpty() ? (c == null ? "" : c.optString("apiKey", "")) : key,
                    c == null ? "https://opencode.ai/zen/go/v1" : c.optString("baseUrl", "https://opencode.ai/zen/go/v1"),
                    c == null ? "deepseek-v4.1-flash" : c.optString("model", "deepseek-v4.1-flash"),
                    c == null ? "" : c.optString("keyBase64", ""),
                    c == null ? 9527 : c.optInt("port", 9527),
                    c == null ? "大肥鱼" : c.optString("nick", "大肥鱼"));
            logView.setText("配置已保存，重启服务端生效");
        } catch (Exception e) {
            logView.setText("保存失败: " + e.getMessage());
        }
    }

    private void tick() {
        ui.postDelayed(() -> {
            boolean run = NodeRunner.isRunning();
            status.setText(run ? "● 运行中" : "○ 未运行");
            JSONObject c = NodeRunner.readServerConfig(this);
            if (c != null) {
                String dom = c.optString("domain", "");
                int p = c.optInt("port", 9527);
                String host = dom.isEmpty() ? firstIpv6() : dom;
                addrView.setText("地址: " + host + "\n端口: " + p
                        + "\n网页: http://" + host + ":9528/?t=" + c.optString("keyBase64", ""));
                if (apiKey.getText().toString().isEmpty()) {
                    apiKey.setText(c.optString("apiKey", ""));
                }
            }
            List<String> ls = NodeRunner.logs(25);
            StringBuilder sb = new StringBuilder();
            for (String s : ls) sb.append(s).append('\n');
            logView.setText(sb.toString());
            tick();
        }, 2000);
    }

    private void buildQr() {
        JSONObject c = NodeRunner.readServerConfig(this);
        String host = c == null ? "" : c.optString("domain", "").trim();
        if (host.isEmpty()) host = firstIpv6();
        if (host.isEmpty()) host = firstIpv4();
        if (host.isEmpty()) { logView.setText("拿不到本机地址"); return; }
        String kb = c == null ? "" : c.optString("keyBase64", "");
        int p = c == null ? 9527 : c.optInt("port", 9527);
        String nick = c == null ? "大肥鱼" : c.optString("nick", "大肥鱼");
        String link = "dafeiyu://connect?h=" + Uri.encode(host)
                + "&p=" + p + "&k=" + Uri.encode(kb) + "&n=" + Uri.encode(nick);
        Bitmap bm = QrUtil.make(link, 720);
        if (bm != null) qr.setImageBitmap(bm);
        logView.setText(link);
    }

    private String firstIpv6() {
        try {
            List<NetworkInterface> all = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : all) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet6Address) {
                        String s = a.getHostAddress();
                        if (s != null && !s.startsWith("fe80")) {
                            int pct = s.indexOf('%');
                            return pct > 0 ? s.substring(0, pct) : s;
                        }
                    }
                }
            }
        } catch (Exception ignored) { }
        return "";
    }

    private String firstIpv4() {
        try {
            List<NetworkInterface> all = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : all) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return "";
    }
}
