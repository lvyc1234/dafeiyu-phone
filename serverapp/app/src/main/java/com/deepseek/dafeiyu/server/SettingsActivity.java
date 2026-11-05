package com.deepseek.dafeiyu.server;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/** 服务端完整设置 */
public class SettingsActivity extends Activity {

    private LinearLayout root;
    private int pad;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        pad = (int) (getResources().getDisplayMetrics().density * 16);
        ScrollView sc = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        sc.addView(root);
        setContentView(sc);

        ServerConfig.loadIntoPrefs(this);

        group("模型");
        edit("baseUrl", "接口地址", "https://opencode.ai/zen/go/v1");
        edit("apiKey", "API Key", "");
        edit("model", "模型名", "deepseek-v4.1-flash");

        group("聊天通道");
        editNum("port", "端口", 9527);
        edit("domain", "对外域名（客户端用它连，换 IP 不用重扫）", "");
        edit("nick", "昵称", "大肥鱼");
        row("密钥", Cfg.get(this, "keyBase64", "（未生成，点下面按钮）"));
        button("随机生成新密钥", v -> {
            byte[] k = new byte[32];
            new java.security.SecureRandom().nextBytes(k);
            String s = android.util.Base64.encodeToString(k, android.util.Base64.NO_WRAP);
            Cfg.set(this, "keyBase64", s);
            toast("已生成，保存后生效（客户端需重扫）");
        });
        button("查看连接串", v -> {
            String host = Cfg.get(this, "domain", "");
            if (host.isEmpty()) host = NodeRunner.localIpv6();
            String link = "dafeiyu://connect?h=" + android.net.Uri.encode(host)
                    + "&p=" + Cfg.getInt(this, "port", 9527)
                    + "&k=" + android.net.Uri.encode(Cfg.get(this, "keyBase64", ""))
                    + "&n=" + android.net.Uri.encode(Cfg.get(this, "nick", "大肥鱼"));
            toast(link);
        });

        group("自主生活（她自己做事）");
        check("lifeOn", "启用自主生活循环", true);
        editNum("lifeMin", "最短间隔（分钟）", 10);
        editNum("lifeMax", "最长间隔（分钟）", 20);
        editNum("lifeQuietStart", "安静时段开始（时）", 0);
        editNum("lifeQuietEnd", "安静时段结束（时）", 8);

        group("主动搭话（她自己找你）");
        editNum("proMin", "最短间隔（分钟）", 1);
        editNum("proMax", "最长间隔（分钟）", 60);
        editNum("proCap", "每天最多几条", 12);
        editNum("proQuietStart", "安静时段开始（时）", 1);
        editNum("proQuietEnd", "安静时段结束（时）", 8);

        group("人格与记忆");
        button("编辑人设 persona.md", v -> openText("persona.md"));
        button("编辑长期事实 facts.md", v -> openText("memory/facts.md"));
        button("编辑自我认知 self.md", v -> openText("memory/self.md"));
        button("查看记忆统计", v -> {
            File base = new File("/sdcard/DeepSeekGirl");
            int chatDays = 0;
            File cd = new File(base, "memory/chat");
            if (cd.isDirectory()) chatDays = cd.list().length;
            long selfB = new File(base, "memory/self.md").length();
            int facts = countLines(new File(base, "memory/facts.md"));
            int sums = 0;
            File sd = new File(base, "summaries");
            if (sd.isDirectory()) sums = sd.list().length;
            toast("对话天数 " + chatDays + " · 事实 " + facts + " 条 · 自我 " + selfB + " 字节 · 日记 " + sums + " 天");
        });
        button("立即生成今日日记", v -> {
            toast("已提交，稍后看 summaries/ 目录");
            NodeRunner.runOnce(this, "dailyReview");
        });

        group("工具");
        button("无障碍状态", v -> toast(DafeiyuA11y.ready() ? "已开启，节点树可用" : "未开启（设置→无障碍 里手动打开）"));
        button("测试读屏（打印前 5 个控件）", v -> {
            org.json.JSONArray a = DafeiyuA11y.dumpNodes();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(5, a.length()); i++) {
                sb.append(i).append(". ").append(a.optJSONObject(i).optString("text", "")).append('\n');
            }
            toast(a.length() == 0 ? "读不到（无障碍没开或当前页面无控件）" : "共 " + a.length() + " 个\n" + sb);
        });

        group("保存");
        button("保存并重启服务端", v -> {
            try {
                ServerConfig.saveAll(this);
                stopService(new Intent(this, ServerService.class));
                NodeRunner.stop();
                startForegroundService(new Intent(this, ServerService.class));
                toast("已保存并重启");
                finish();
            } catch (Exception e) {
                toast("保存失败: " + e.getMessage());
            }
        });
        button("清除聊天记录（本地 + 服务端）", v -> {
            try {
                new File(getFilesDir(), "server/messages.jsonl").delete();
                NodeRunner.clearHistory(this);
                toast("已清空");
            } catch (Exception e) {
                toast("失败: " + e.getMessage());
            }
        });
    }

    private int countLines(File f) {
        if (!f.exists()) return 0;
        try {
            return (int) java.nio.file.Files.readAllLines(f.toPath()).stream().filter(s -> s.trim().startsWith("- ")).count();
        } catch (Exception e) { return 0; }
    }

    private void openText(String rel) {
        startActivity(new Intent(this, TextEditActivity.class).putExtra("rel", rel));
    }

    // ---- UI 小工具 ----
    private void group(String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(17);
        t.setPadding(0, pad, 0, pad / 3);
        root.addView(t);
    }

    private void label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        root.addView(t);
    }

    private EditText edit(String key, String hint, String def) {
        label(hint);
        EditText e = new EditText(this);
        e.setText(Cfg.get(this, key, def));
        e.setTextSize(14);
        e.setSingleLine(true);
        root.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        e.setOnFocusChangeListener((v, has) -> { if (!has) Cfg.set(SettingsActivity.this, key, e.getText().toString().trim()); });
        return e;
    }

    private EditText editNum(String key, String hint, int def) {
        label(hint);
        EditText e = new EditText(this);
        e.setText(String.valueOf(Cfg.getInt(this, key, def)));
        e.setTextSize(14);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        root.addView(e);
        e.setOnFocusChangeListener((v, has) -> {
            if (!has) {
                try { Cfg.setInt(SettingsActivity.this, key, Integer.parseInt(e.getText().toString().trim())); } catch (Exception ignored) { }
            }
        });
        return e;
    }

    private void row(String k, String v) {
        label(k + "：" + v);
    }

    private void check(String key, String text, boolean def) {
        CheckBox c = new CheckBox(this);
        c.setText(text);
        c.setChecked(Cfg.getBool(this, key, def));
        c.setOnCheckedChangeListener((v, on) -> Cfg.setBool(SettingsActivity.this, key, on));
        root.addView(c);
    }

    private void button(String text, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        root.addView(b);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
