package com.deepseek.dafeiyu.link;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {

    private EditText host, port, nick, key;
    private TextView tip;
    private Prefs prefs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_settings);
        prefs = new Prefs(this);
        host = findViewById(R.id.host);
        port = findViewById(R.id.port);
        nick = findViewById(R.id.nick);
        key = findViewById(R.id.key);
        tip = findViewById(R.id.tip);

        host.setText(prefs.host());
        port.setText(String.valueOf(prefs.port()));
        nick.setText(prefs.nick());
        key.setText(prefs.key());

        findViewById(R.id.btnGenKey).setOnClickListener(v -> {
            byte[] k = Crypto.random(Crypto.PSK_LEN);
            key.setText(Crypto.b64(k));
            toast("已生成新密钥，记得同步到手机端 net_bridge.js");
        });

        findViewById(R.id.btnShowQr).setOnClickListener(v -> showQr());

        findViewById(R.id.btnSave).setOnClickListener(v -> save());
        refreshTip();
    }

    private void refreshTip() {
        byte[] k = Crypto.fromHexOrB64(key.getText().toString());
        String fp = k == null ? "密钥未填写或格式不对" : Crypto.fingerprint(k);
        tip.setText("提示：服务端是手机上的 AutoX 脚本，默认监听 9527。\n"
                + "IPv6 直连用原生 TCP；frp 只做端口转发，填映射后的公网地址即可。\n"
                + "全程 AES-256-GCM，密钥不出本机。\n"
                + "当前密钥指纹：" + fp);
    }

    private void save() {
        String h = host.getText().toString().trim();
        int p;
        try {
            p = Integer.parseInt(port.getText().toString().trim());
        } catch (Exception e) {
            p = Prefs.DEFAULT_PORT;
        }
        String n = nick.getText().toString().trim();
        if (n.isEmpty()) n = "我";
        byte[] k = Crypto.fromHexOrB64(key.getText().toString());
        if (k == null) {
            toast("密钥必须是 32 字节（Base64 或 64 位 hex）");
            return;
        }
        prefs.save(h, p, n, Crypto.b64(k));
        toast("已保存，正在重连");
        LinkService.stop(this);
        LinkService.start(this);
        finish();
    }

    private void showQr() {
        String h = host.getText().toString().trim();
        String n = nick.getText().toString().trim();
        byte[] k = Crypto.fromHexOrB64(key.getText().toString());
        if (h.isEmpty() || k == null) {
            toast("先填地址和密钥");
            return;
        }
        int p;
        try {
            p = Integer.parseInt(port.getText().toString().trim());
        } catch (Exception e) {
            p = Prefs.DEFAULT_PORT;
        }
        String content = "dafeiyu://connect?h=" + Uri.encode(h)
                + "&p=" + p
                + "&k=" + Uri.encode(Crypto.b64(k))
                + "&n=" + Uri.encode(n.isEmpty() ? "我" : n);
        Bitmap qr = QrUtil.make(content, 720);
        if (qr == null) {
            toast("生成二维码失败");
            return;
        }
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(qr);
        new AlertDialog.Builder(this)
                .setTitle("让另一台手机扫这个")
                .setView(iv)
                .setPositiveButton("好", null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
