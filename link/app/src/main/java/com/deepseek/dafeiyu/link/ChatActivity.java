package com.deepseek.dafeiyu.link;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.List;

public class ChatActivity extends Activity implements LinkService.Listener {

    private static final int REQ_IMG = 21;
    private static final int REQ_SCAN = 22;
    private static final int REQ_PERM = 23;

    private static final String[] EMOJIS = {
            "😀", "😄", "😆", "😅", "😂", "🙂", "😉", "😊",
            "😍", "🥰", "😘", "😜", "🤪", "🤨", "🧐", "🤓",
            "😎", "🥳", "😏", "😒", "😞", "😔", "😟", "😕",
            "🙁", "😣", "😖", "😫", "😩", "🥺", "😢", "😭",
            "😤", "😠", "😡", "🤬", "🤯", "😳", "🥵", "🥶",
            "😱", "😨", "😰", "😥", "😓", "🤗", "🤔", "🤭",
            "🤫", "🤥", "😶", "😐", "😑", "😬", "🙄", "😯",
            "😦", "😧", "😮", "😲", "🥱", "😴", "🤤", "😪",
            "🐋", "🐟", "🌊", "💙", "❤️", "✨", "🎉", "🔥",
            "👍", "👎", "👏", "🙏", "💪", "🤝", "👀", "💤"
    };

    private ListView list;
    private MsgAdapter adapter;
    private EditText input;
    private TextView status;
    private LinearLayout emojiPanel;
    private GridLayout emojiGrid;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Db db;
    private Prefs prefs;
    private VoiceRecorder recorder;
    private MediaPlayer player;
    private boolean emojiOpen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);
        prefs = new Prefs(this);
        db = new Db(this);

        list = findViewById(R.id.list);
        input = findViewById(R.id.input);
        status = findViewById(R.id.status);
        emojiPanel = findViewById(R.id.emojiPanel);
        emojiGrid = findViewById(R.id.emojiGrid);

        adapter = new MsgAdapter(this, new MsgAdapter.OnMediaClick() {
            @Override
            public void onImage(Msg m) {
                showImage(m);
            }

            @Override
            public void onVoice(Msg m) {
                playVoice(m);
            }
        });
        list.setAdapter(adapter);
        adapter.setAll(db.recent(300));

        buildEmojiGrid();
        bindButtons();
        requestNeededPermissions();

        LinkService.addListener(this);
        LinkService.start(this);
        if (!prefs.ready()) {
            status.setText("点右上角「扫码」");
            Toast.makeText(this, "点右上角「扫码」，扫服务端生成的二维码", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        LinkService.removeListener(this);
        stopPlayer();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK) return;
        if (req == REQ_IMG && data != null && data.getData() != null) {
            sendImage(data.getData());
        } else if (req == REQ_SCAN && data != null) {
            String text = data.getStringExtra("result");
            if (text != null && text.startsWith("dafeiyu://")) {
                applyScan(text);
            }
        }
    }

    // ------------------------------------------------------------ 交互

    private void bindButtons() {
        findViewById(R.id.btnSend).setOnClickListener(v -> sendText());
        findViewById(R.id.btnEmoji).setOnClickListener(v -> toggleEmoji());
        findViewById(R.id.btnImage).setOnClickListener(v -> pickImage());
        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btnScan).setOnClickListener(v ->
                startActivityForResult(new Intent(this, ScanActivity.class), REQ_SCAN));

        Button voice = findViewById(R.id.btnVoice);
        voice.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                startRecord();
                return true;
            }
            if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                stopRecord(e.getAction() == MotionEvent.ACTION_UP);
                return true;
            }
            return false;
        });
    }

    private void sendText() {
        String t = input.getText().toString().trim();
        if (t.isEmpty()) return;
        Msg m = new Msg(true, Msg.T_TEXT, t);
        db.insert(m);
        adapter.add(m);
        input.setText("");
        LinkService s = LinkService.get();
        if (s != null) s.enqueue(m);
        scrollBottom();
    }

    private void toggleEmoji() {
        emojiOpen = !emojiOpen;
        emojiPanel.setVisibility(emojiOpen ? View.VISIBLE : View.GONE);
        if (emojiOpen) {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
    }

    private void buildEmojiGrid() {
        int size = (int) (getResources().getDisplayMetrics().density * 40);
        for (String e : EMOJIS) {
            TextView tv = new TextView(this);
            tv.setText(e);
            tv.setTextSize(24);
            tv.setGravity(android.view.Gravity.CENTER);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = size;
            lp.height = size;
            tv.setLayoutParams(lp);
            tv.setOnClickListener(v -> {
                Msg m = new Msg(true, Msg.T_EMOJI, e);
                db.insert(m);
                adapter.add(m);
                LinkService s = LinkService.get();
                if (s != null) s.enqueue(m);
                scrollBottom();
            });
            emojiGrid.addView(tv);
        }
    }

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        startActivityForResult(Intent.createChooser(i, "选一张图"), REQ_IMG);
    }

    private void sendImage(Uri uri) {
        try {
            Bitmap bmp = readBitmap(uri);
            if (bmp == null) {
                Toast.makeText(this, "读不到这张图", Toast.LENGTH_SHORT).show();
                return;
            }
            byte[] jpeg = Util.compressJpeg(bmp, 700 * 1024);
            File dir = Util.mediaDir(this, "img");
            File f = new File(dir, "s" + System.currentTimeMillis() + ".jpg");
            Util.writeAll(f, jpeg);
            Msg m = new Msg(true, Msg.T_IMAGE, "");
            m.path = f.getAbsolutePath();
            db.insert(m);
            adapter.add(m);
            LinkService s = LinkService.get();
            if (s != null) s.enqueue(m);
            scrollBottom();
        } catch (Exception e) {
            Toast.makeText(this, "发送失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private Bitmap readBitmap(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return Util.decodeBytes(bos.toByteArray(), 1600);
        }
    }

    private void startRecord() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERM);
            return;
        }
        try {
            File dir = Util.mediaDir(this, "voice");
            File f = new File(dir, "r" + System.currentTimeMillis() + ".m4a");
            recorder = new VoiceRecorder();
            if (recorder.start(this, f)) {
                Toast.makeText(this, "松开发送", Toast.LENGTH_SHORT).show();
            } else {
                recorder = null;
                Toast.makeText(this, "录音失败", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            recorder = null;
        }
    }

    private void stopRecord(boolean send) {
        if (recorder == null) return;
        int sec = recorder.stop();
        File f = recorder.file();
        recorder = null;
        if (!send) {
            if (f != null) f.delete();
            return;
        }
        if (f == null || !f.exists() || f.length() < 512) {
            Toast.makeText(this, "太短了", Toast.LENGTH_SHORT).show();
            if (f != null) f.delete();
            return;
        }
        Msg m = new Msg(true, Msg.T_VOICE, "");
        m.path = f.getAbsolutePath();
        m.dur = Math.max(1, sec);
        db.insert(m);
        adapter.add(m);
        LinkService s = LinkService.get();
        if (s != null) s.enqueue(m);
        scrollBottom();
    }

    private void showImage(Msg m) {
        if (m.path == null || m.path.isEmpty()) return;
        Bitmap bmp = Util.decodeFile(m.path, 1600);
        if (bmp == null) return;
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(bmp);
        iv.setAdjustViewBounds(true);
        new AlertDialog.Builder(this).setView(iv)
                .setPositiveButton("关闭", null).show();
    }

    private void playVoice(Msg m) {
        if (m.path == null || m.path.isEmpty()) return;
        stopPlayer();
        try {
            player = new MediaPlayer();
            player.setDataSource(m.path);
            player.prepare();
            player.start();
        } catch (Exception e) {
            Toast.makeText(this, "播放失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopPlayer() {
        if (player != null) {
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
    }

    private void applyScan(String text) {
        try {
            Uri u = Uri.parse(text);
            // 注意：dafeiyu://connect?h=… 里 "connect" 是 authority，真正的地址在 h 参数里。
            // 用 u.getHost() 会拿到 "connect" 这个字符串，连出去必然 DNS 失败。
            String host = u.getQueryParameter("h");
            String port = u.getQueryParameter("p");
            String key = u.getQueryParameter("k");
            String nick = u.getQueryParameter("n");
            if (host == null || host.isEmpty()) {
                Toast.makeText(this, "连接串里没有地址（h 参数）", Toast.LENGTH_LONG).show();
                return;
            }
            prefs.save(host == null ? "" : host,
                    port == null ? Prefs.DEFAULT_PORT : Integer.parseInt(port),
                    nick == null ? "我" : nick,
                    key == null ? "" : key);
            Toast.makeText(this, "已导入连接信息，正在重连", Toast.LENGTH_SHORT).show();
            LinkService.stop(this);
            LinkService.start(this);
        } catch (Exception e) {
            Toast.makeText(this, "二维码内容不对", Toast.LENGTH_SHORT).show();
        }
    }

    private void scrollBottom() {
        list.post(() -> list.setSelection(adapter.getCount() - 1));
    }

    private void requestNeededPermissions() {
        if (Build.VERSION.SDK_INT < 23) return;
        java.util.List<String> need = new java.util.ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.CAMERA);
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERM);
    }

    // ------------------------------------------------------------ 服务回调

    @Override
    public void onState(int state, String detail) {
        ui.post(() -> {
            switch (state) {
                case LinkService.ST_ONLINE:
                    status.setText("在线");
                    break;
                case LinkService.ST_CONNECTING:
                    status.setText("连接中");
                    break;
                case LinkService.ST_ERROR:
                    status.setText("出错");
                    break;
                default:
                    status.setText("未连接");
            }
            if (detail != null && state != LinkService.ST_ONLINE) status.setContentDescription(detail);
        });
    }

    @Override
    public void onMsg(Msg m) {
        ui.post(() -> {
            adapter.add(m);
            scrollBottom();
        });
    }

    @Override
    public void onTyping(boolean on) {
        ui.post(() -> status.setText(on ? "正在输入…" : "在线"));
    }

    @Override
    public void onLog(String line) {
        // 需要时打开 logcat 看
    }
}
