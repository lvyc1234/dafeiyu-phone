package com.deepseek.dafeiyu.server;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;

/** 编辑 /sdcard/DeepSeekGirl 下的文本文件（人设、事实、自我） */
public class TextEditActivity extends Activity {

    private File target;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String rel = getIntent().getStringExtra("rel");
        target = new File("/sdcard/DeepSeekGirl/" + rel);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 12);
        root.setPadding(pad, pad, pad, pad);

        TextView t = new TextView(this);
        t.setText(rel);
        t.setTextSize(15);
        root.addView(t);

        EditText e = new EditText(this);
        e.setGravity(android.view.Gravity.TOP);
        e.setTextSize(14);
        e.setMinLines(12);
        try {
            if (target.exists()) e.setText(new String(java.nio.file.Files.readAllBytes(target.toPath()), "UTF-8"));
        } catch (Exception ex) {
            e.setText("");
        }
        root.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button save = new Button(this);
        save.setText("保存");
        save.setOnClickListener(v -> {
            try {
                target.getParentFile().mkdirs();
                try (java.io.FileOutputStream os = new java.io.FileOutputStream(target)) {
                    os.write(e.getText().toString().getBytes("UTF-8"));
                }
                android.widget.Toast.makeText(this, "已保存，下一句话就生效", android.widget.Toast.LENGTH_SHORT).show();
                finish();
            } catch (Exception ex) {
                android.widget.Toast.makeText(this, "保存失败: " + ex.getMessage(), android.widget.Toast.LENGTH_LONG).show();
            }
        });
        root.addView(save);
        setContentView(root);
    }
}
