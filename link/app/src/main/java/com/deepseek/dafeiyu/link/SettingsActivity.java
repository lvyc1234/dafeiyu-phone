package com.deepseek.dafeiyu.link;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {

    private static final int REQ_SCAN = 41;

    private EditText host, port, nick, key, linkStr;
    private TextView tip;
    private Switch swOverlay;
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
        linkStr = findViewById(R.id.linkStr);
        tip = findViewById(R.id.tip);
        swOverlay = findViewById(R.id.swOverlay);
        bindOverlaySwitch();

        host.setText(prefs.host());
        port.setText(String.valueOf(prefs.port()));
        nick.setText(prefs.nick());
        key.setText(prefs.key());

        findViewById(R.id.btnGenKey).setOnClickListener(v -> {
            byte[] k = Crypto.random(Crypto.PSK_LEN);
            key.setText(Crypto.b64(k));
            refreshTip();
            toast("已生成新密钥，记得同步到服务端");
        });

        findViewById(R.id.btnShowQr).setOnClickListener(v -> showQr());

        findViewById(R.id.btnScanIn).setOnClickListener(v ->
                startActivityForResult(new Intent(this, ScanActivity.class), REQ_SCAN));

        findViewById(R.id.btnImportStr).setOnClickListener(v ->
                importLink(linkStr.getText().toString()));

        findViewById(R.id.btnPaste).setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData clip = cm == null ? null : cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                toast("剪贴板是空的");
                return;
            }
            CharSequence cs = clip.getItemAt(0).coerceToText(this);
            String s = cs == null ? "" : cs.toString().trim();
            linkStr.setText(s);
            importLink(s);
        });

        findViewById(R.id.btnSave).setOnClickListener(v -> save());
        refreshTip();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_SCAN || res != RESULT_OK || data == null) return;
        String text = data.getStringExtra("result");
        linkStr.setText(text == null ? "" : text);
        importLink(text);
    }

    // ------------------------------------------------------------ 悬浮窗开关

    /** 从系统授权页回来：权限拿到就立刻让服务把面板建出来 */
    @Override
    protected void onResume() {
        super.onResume();
        if (swOverlay != null) swOverlay.setChecked(prefs.overlay());
        LinkService s = LinkService.get();
        if (s != null) s.syncPanel();
    }

    /**
     * 开关只负责写 Prefs，真正的窗口由 LinkService.syncPanel() 建/销毁。
     * 打开时如果还没授权，顺手把用户送到「显示在其他应用上层」的系统页。
     */
    private void bindOverlaySwitch() {
        if (swOverlay == null) return;
        swOverlay.setChecked(prefs.overlay());
        swOverlay.setOnCheckedChangeListener((btn, checked) -> {
            prefs.setOverlay(checked);
            LinkService s = LinkService.get();
            if (s != null) s.syncPanel();
            if (!checked) {
                toast("悬浮窗已关闭");
                return;
            }
            if (!canOverlay()) {
                toast(getString(R.string.overlay_denied));
                openOverlaySettings();
            } else {
                toast("悬浮窗已开启，长按面板可关闭");
            }
        });
    }

    private boolean canOverlay() {
        return Settings.canDrawOverlays(this);
    }

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (Exception ignored) {
            }
        }
    }

    /** 支持三种来源：扫码、粘贴、手输 */
    private void importLink(String s) {
        if (s == null) return;
        s = s.trim();
        if (s.isEmpty()) {
            toast("先粘贴连接串");
            return;
        }
        if (!s.startsWith("dafeiyu://")) {
            toast("连接串要以 dafeiyu:// 开头");
            return;
        }
        try {
            Uri u = Uri.parse(s);
            String h = u.getQueryParameter("h");
            String p = u.getQueryParameter("p");
            String k = u.getQueryParameter("k");
            String n = u.getQueryParameter("n");
            if (h == null || k == null) {
                toast("连接串缺少地址或密钥");
                return;
            }
            host.setText(h);
            port.setText(p == null || p.isEmpty() ? String.valueOf(Prefs.DEFAULT_PORT) : p);
            key.setText(k);
            nick.setText(n == null || n.isEmpty() ? "我" : n);
            refreshTip();
            save();
        } catch (Exception e) {
            toast("解析失败: " + e.getMessage());
        }
    }

    private void refreshTip() {
        byte[] k = Crypto.fromHexOrB64(key.getText().toString());
        String fp = k == null ? "密钥未填写或格式不对" : Crypto.fingerprint(k);
        tip.setText("提示：服务端默认监听 9527。\n"
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

    private String buildLink() {
        String h = host.getText().toString().trim();
        String n = nick.getText().toString().trim();
        byte[] k = Crypto.fromHexOrB64(key.getText().toString());
        if (h.isEmpty() || k == null) return null;
        int p;
        try {
            p = Integer.parseInt(port.getText().toString().trim());
        } catch (Exception e) {
            p = Prefs.DEFAULT_PORT;
        }
        return "dafeiyu://connect?h=" + Uri.encode(h)
                + "&p=" + p
                + "&k=" + Uri.encode(Crypto.b64(k))
                + "&n=" + Uri.encode(n.isEmpty() ? "我" : n);
    }

    private void showQr() {
        String content = buildLink();
        if (content == null) {
            toast("先填地址和密钥");
            return;
        }
        Bitmap qr = QrUtil.make(content, 720);
        if (qr == null) {
            toast("生成二维码失败");
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 16);
        box.setPadding(pad, pad, pad, pad);

        ImageView iv = new ImageView(this);
        iv.setImageBitmap(qr);
        iv.setAdjustViewBounds(true);
        box.addView(iv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tv = new TextView(this);
        tv.setText(content);
        tv.setTextSize(9);
        tv.setTextIsSelectable(true);
        tv.setPadding(0, pad / 2, 0, 0);
        box.addView(tv);

        new AlertDialog.Builder(this)
                .setTitle("对方扫这个，或复制下面的连接串")
                .setView(box)
                .setPositiveButton("关闭", null)
                .setNeutralButton("复制连接串", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("dafeiyu-link", content));
                    toast("连接串已复制");
                })
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
