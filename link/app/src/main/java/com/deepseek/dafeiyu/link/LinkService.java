package com.deepseek.dafeiyu.link;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 唯一的生产者/消费者：一个前台服务持有一条 TCP 长连接。
 * 传输层是裸 socket + AES-256-GCM 帧，没有 HTTP。
 */
public class LinkService extends Service {

    private static final String TAG = "LinkService";
    private static final String CH = "link";
    private static final String CH_MSG = "link-msg";

    /** Activity 是否在前台；在前台就不弹通知 */
    public static volatile boolean appVisible = false;
    public static final int PORT_FALLBACK = Prefs.DEFAULT_PORT;

    public static final int ST_OFFLINE = 0;
    public static final int ST_CONNECTING = 1;
    public static final int ST_ONLINE = 2;
    public static final int ST_ERROR = 3;

    public interface Listener {
        void onState(int state, String detail);
        void onMsg(Msg m);
        void onTyping(boolean on, String text);
        void onLog(String line);
        void onSyncDone(int total);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile LinkService INSTANCE;

    public static void addListener(Listener l) {
        if (l != null && !LISTENERS.contains(l)) LISTENERS.add(l);
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    public static LinkService get() {
        return INSTANCE;
    }

    public static void start(Context c) {
        Intent i = new Intent(c, LinkService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, LinkService.class));
    }

    private final BlockingQueue<Msg> outQueue = new LinkedBlockingQueue<>();
    private static final java.util.ArrayDeque<String> LOGS = new java.util.ArrayDeque<>();
    private static final int LOG_MAX = 400;
    private final Map<String, Asm> assembling = new HashMap<>();

    private volatile boolean alive = true;
    private volatile Socket socket;
    private volatile Session session;
    private volatile DataOutputStream out;
    private long lastPingMs;
    private int state = ST_OFFLINE;

    private Prefs prefs;
    private final List<Integer> pendingShards = new ArrayList<>();
    private JSONArray targetHashes = null;
    private Db db;

    // ------------------------------------------------------------ lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        prefs = new Prefs(this);
        db = new Db(this);
        createChannel();
        startForeground(1, notification("未连接"));
        Thread t = new Thread(this::runLoop, "link-net");
        t.setDaemon(true);
        t.start();
        new DebugServer(this).start();
        log("调试口 127.0.0.1:" + DebugServer.PORT + " 就绪");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        alive = false;
        closeSocket();
        INSTANCE = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------ 对外 API

    public void enqueue(Msg m) {
        db.insert(m);
        outQueue.offer(m);
        if (m.mine) {
            state = state == ST_ONLINE ? ST_ONLINE : ST_CONNECTING;
        }
        notifyMsg(m);
    }

    public int state() {
        return state;
    }

    public List<Msg> history(int n) {
        return db.recent(n);
    }

    public void clearHistory() {
        db.clear();
    }

    // ------------------------------------------------------------ 网络循环

    private void runLoop() {
        while (alive) {
            try {
                connectOnce();
            } catch (Exception e) {
                String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                log("连接中断: " + msg);
                setState(ST_ERROR, msg);
            } finally {
                closeSocket();
            }
            if (!alive) break;
            setState(ST_CONNECTING, "3 秒后重连");
            sleep(3000);
        }
    }

    private void connectOnce() throws Exception {
        String host = prefs.host();
        int port = prefs.port();
        byte[] psk = Crypto.fromHexOrB64(prefs.key());
        if (host == null || host.isEmpty() || psk == null) {
            setState(ST_OFFLINE, "还没配置地址或密钥");
            sleep(5000);
            return;
        }
        setState(ST_CONNECTING, host + ":" + port);
        Socket s = new Socket();
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
        s.connect(new InetSocketAddress(host, port), 15000);
        socket = s;
        DataInputStream in = new DataInputStream(s.getInputStream());
        out = new DataOutputStream(s.getOutputStream());

        // 握手：客户端先发 salt，服务端回 salt，双方用 HKDF 派生会话密钥
        byte[] saltC = Crypto.random(16);
        byte[] hello = new byte[21];
        System.arraycopy("DLK1".getBytes(StandardCharsets.US_ASCII), 0, hello, 0, 4);
        hello[4] = 1;
        System.arraycopy(saltC, 0, hello, 5, 16);
        out.write(hello);
        out.flush();

        byte[] reply = new byte[21];
        in.readFully(reply);
        if (!"DLK2".equals(new String(reply, 0, 4, StandardCharsets.US_ASCII))) {
            throw new IOException("对端不是 Dafeiyu Link 服务端");
        }
        byte[] saltS = Arrays.copyOfRange(reply, 5, 21);
        session = Session.derive(psk, saltC, saltS, true);
        lastPingMs = System.currentTimeMillis();

        setState(ST_ONLINE, Crypto.fingerprint(psk));
        startSync();
        log("已连接 " + host + ":" + port + "  fp=" + Crypto.fingerprint(psk));

        Thread reader = new Thread(() -> readLoop(in), "link-read");
        reader.setDaemon(true);
        reader.start();

        writeLoop();
    }

    private void writeLoop() throws Exception {
        while (alive && socket != null && !socket.isClosed()) {
            Msg m = outQueue.poll(1, TimeUnit.SECONDS);
            if (m != null) send(m);
            long now = System.currentTimeMillis();
            if (now - lastPingMs > 30000) {
                Frame.write(out, session, Frame.T_PING, new byte[0]);
                lastPingMs = now;
            }
        }
    }

    private void readLoop(DataInputStream in) {
        try {
            while (alive && socket != null && !socket.isClosed()) {
                Frame.In f = Frame.read(in, session);
                handle(f);
            }
        } catch (Exception e) {
            log("读线程退出: " + e.getMessage());
        } finally {
            closeSocket();
        }
    }

    // ------------------------------------------------------------ 历史同步（200KB 分片 + MD5）
    private File shardFile(int i) {
        File d = new File(getFilesDir(), "sync");
        if (!d.exists()) d.mkdirs();
        return new File(d, "shard-" + i + ".json");
    }

    private File hashFile() {
        File d = new File(getFilesDir(), "sync");
        if (!d.exists()) d.mkdirs();
        return new File(d, "hashes.json");
    }

    private String loadHashes() {
        try {
            return hashFile().exists()
                    ? new String(Util.readAll(hashFile()), StandardCharsets.UTF_8) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private void saveHashes(String json) {
        try {
            Util.writeAll(hashFile(), json.getBytes(StandardCharsets.UTF_8));
            log("分片 hash 已保存: " + json.length() + " 字节");
        } catch (Exception e) {
            log("hash 保存失败: " + e.getMessage());
        }
    }

    private void startSync() {
        try {
            JSONObject j = new JSONObject();
            j.put("t", "sync_index");
            Frame.write(out, session, Frame.T_JSON, j.toString().getBytes(StandardCharsets.UTF_8));
            log("请求历史索引");
        } catch (Exception e) {
            log("同步请求失败: " + e.getMessage());
        }
    }

    /** 对比分片 hash，只下载差异片；本地多出的片删除 */
    private void onSyncIndex(JSONObject j) {
        try {
            JSONArray shards = j.optJSONArray("shards");
            if (shards == null) return;
            JSONArray local = null;
            String saved = loadHashes();
            if (saved != null && !saved.isEmpty()) {
                try { local = new JSONArray(saved); } catch (Exception ignored) { }
            }
            pendingShards.clear();
            for (int i = 0; i < shards.length(); i++) {
                String h = shards.optString(i, "");
                boolean same = local != null && i < local.length() && h.equals(local.optString(i, ""));
                if (!same) pendingShards.add(i);
            }
            // 服务端片数变少 -> 删除本地多余的片
            for (int i = shards.length(); ; i++) {
                File f = shardFile(i);
                if (!f.exists()) break;
                f.delete();
            }
            targetHashes = shards;
            log("同步: 服务端 " + j.optInt("total") + " 条 / " + shards.length()
                    + " 片，需下载 " + pendingShards.size() + " 片");
            if (pendingShards.isEmpty()) {
                rebuildFromShards();
            } else {
                requestNextShard();
            }
        } catch (Exception e) {
            log("同步索引处理失败: " + e.getMessage());
        }
    }

    private void requestNextShard() {
        try {
            if (pendingShards.isEmpty()) {
                saveHashes(targetHashes == null ? "[]" : targetHashes.toString());
                rebuildFromShards();
                return;
            }
            int i = pendingShards.remove(0);
            JSONObject j = new JSONObject();
            j.put("t", "sync_get");
            j.put("i", i);
            Frame.write(out, session, Frame.T_JSON, j.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log("请求分片失败: " + e.getMessage());
        }
    }

    private void onSyncShard(JSONObject j) {
        try {
            int i = j.optInt("i");
            JSONArray items = j.optJSONArray("items");
            Util.writeAll(shardFile(i),
                    (items == null ? "[]" : items.toString()).getBytes(StandardCharsets.UTF_8));
            requestNextShard();
        } catch (Exception e) {
            log("分片落盘失败: " + e.getMessage());
        }
    }

    /** 用本地所有分片重建消息表（以服务端为准，多删少补） */
    private void rebuildFromShards() {
        try {
            List<Msg> all = new ArrayList<>();
            for (int i = 0; ; i++) {
                File f = shardFile(i);
                if (!f.exists()) break;
                JSONArray arr = new JSONArray(new String(Util.readAll(f), StandardCharsets.UTF_8));
                for (int k = 0; k < arr.length(); k++) {
                    JSONObject o = arr.optJSONObject(k);
                    if (o == null) continue;
                    Msg m = new Msg();
                    m.ts = o.optLong("ts", Util.nowSec());
                    m.type = o.optString("type", Msg.T_TEXT);
                    m.body = o.optString("body", "");
                    m.mine = "app".equals(o.optString("from"));
                    m.state = Msg.S_SENT;
                    all.add(m);
                }
            }
            db.clear();
            for (Msg m : all) db.insert(m);
            log("历史已重建: " + all.size() + " 条");
            for (Listener li : LISTENERS) li.onSyncDone(all.size());
        } catch (Exception e) {
            log("重建失败: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ 编解码

    private void send(Msg m) throws Exception {
        JSONObject j = new JSONObject();
        j.put("id", String.valueOf(m.ts) + "-" + (int) (Math.random() * 100000));
        j.put("ts", m.ts);
        j.put("from", prefs.nick());
        if (Msg.T_TEXT.equals(m.type) || Msg.T_EMOJI.equals(m.type)) {
            j.put("t", m.type);
            j.put("body", m.body);
            Frame.write(out, session, Frame.T_JSON, j.toString().getBytes(StandardCharsets.UTF_8));
        } else {
            byte[] data = m.path.isEmpty() ? new byte[0] : Util.readAll(new File(m.path));
            int chunk = 24 * 1024;
            int total = Math.max(1, (data.length + chunk - 1) / chunk);
            j.put("t", m.type);
            j.put("name", new File(m.path).getName());
            j.put("size", data.length);
            j.put("chunks", total);
            if (m.dur > 0) j.put("dur", m.dur);
            Frame.write(out, session, Frame.T_JSON, j.toString().getBytes(StandardCharsets.UTF_8));
            String id = j.getString("id");
            for (int i = 0; i < total; i++) {
                int from = i * chunk;
                int to = Math.min(data.length, from + chunk);
                JSONObject c = new JSONObject();
                c.put("t", "chunk");
                c.put("id", id);
                c.put("seq", i);
                c.put("data", Crypto.b64(Arrays.copyOfRange(data, from, to)));
                Frame.write(out, session, Frame.T_JSON, c.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        m.state = Msg.S_SENT;
        db.updateState(m.rowId, Msg.S_SENT);
    }

    private void handle(Frame.In f) throws Exception {
        if (f.type == Frame.T_PING) {
            Frame.write(out, session, Frame.T_PONG, new byte[0]);
            return;
        }
        if (f.type == Frame.T_PONG) {
            return;
        }
        if (f.type == Frame.T_BYE) {
            closeSocket();
            return;
        }
        String text = new String(f.plain, StandardCharsets.UTF_8);
        JSONObject j = new JSONObject(text);
        String t = j.optString("t");

        if ("sync_index".equals(t)) { onSyncIndex(j); return; }
        if ("sync_shard".equals(t)) { onSyncShard(j); return; }
        if ("text".equals(t) || "emoji".equals(t)) {
            Msg m = new Msg(false, t, j.optString("body"));
            m.ts = j.optLong("ts", Util.nowSec());
            db.insert(m);
            notifyMsg(m);
            return;
        }
        if ("typing".equals(t)) {
            boolean on = j.optBoolean("on", false);
            String tt = j.optString("text", "");
            for (Listener l : LISTENERS) l.onTyping(on, tt);
            return;
        }
        if ("ack".equals(t)) {
            return;
        }
        if ("image".equals(t) || "voice".equals(t)) {
            Asm a = new Asm();
            a.type = t;
            a.id = j.optString("id");
            a.chunks = j.optInt("chunks", 1);
            a.size = j.optInt("size", 0);
            a.dur = j.optInt("dur", 0);
            a.name = j.optString("name", t.equals("image") ? "x.jpg" : "x.m4a");
            a.parts = new byte[a.chunks][];
            assembling.put(a.id, a);
            return;
        }
        if ("chunk".equals(t)) {
            String id = j.optString("id");
            Asm a = assembling.get(id);
            if (a == null) return;
            int seq = j.optInt("seq", 0);
            if (seq < 0 || seq >= a.parts.length) return;
            a.parts[seq] = Crypto.unb64(j.optString("data"));
            a.got++;
            if (a.got >= a.chunks) {
                assembling.remove(id);
                finishMedia(a);
            }
        }
    }

    private void finishMedia(Asm a) throws Exception {
        int total = 0;
        for (byte[] p : a.parts) total += p == null ? 0 : p.length;
        byte[] data = new byte[total];
        int pos = 0;
        for (byte[] p : a.parts) {
            if (p == null) continue;
            System.arraycopy(p, 0, data, pos, p.length);
            pos += p.length;
        }
        File dir = Util.mediaDir(this, a.type.equals("image") ? "img" : "voice");
        String ext = a.type.equals("image") ? ".jpg" : ".m4a";
        File f = new File(dir, a.id.replaceAll("[^0-9A-Za-z_-]", "_") + ext);
        if (a.type.equals("image")) {
            // 统一转成 JPEG，方便 ImageView 直接吃
            android.graphics.Bitmap bmp = Util.decodeBytes(data, 1600);
            if (bmp != null) {
                data = Util.compressJpeg(bmp, 700 * 1024);
            }
        }
        Util.writeAll(f, data);

        Msg m = new Msg(false, a.type, "");
        m.path = f.getAbsolutePath();
        m.dur = a.dur;
        db.insert(m);
        notifyMsg(m);
    }

    // ------------------------------------------------------------ 杂项

    private static class Asm {
        String type;
        String id;
        String name;
        int chunks;
        int size;
        int dur;
        int got;
        byte[][] parts;
    }

    // ------------------------------------------------------------ 调试口
    public String debugState() {
        JSONObject j = new JSONObject();
        try {
            j.put("state", state);
            j.put("stateName", state == ST_ONLINE ? "ONLINE" : state == ST_CONNECTING ? "CONNECTING" : state == ST_ERROR ? "ERROR" : "OFFLINE");
            j.put("host", prefs.host());
            j.put("port", prefs.port());
            j.put("tcpOpen", socket != null && !socket.isClosed());
            byte[] psk = Crypto.fromHexOrB64(prefs.key());
            j.put("fp", psk == null ? "" : Crypto.fingerprint(psk));
            j.put("queue", outQueue.size());
        } catch (Exception ignored) {
        }
        return j.toString();
    }

    public void debugSend(String text) {
        if (text == null || text.isEmpty()) return;
        Msg m = new Msg(true, Msg.T_TEXT, text);
        db.insert(m);
        outQueue.offer(m);
        notifyMsg(m);
    }

    public void debugSet(String host, int port, String key, String nick) {
        prefs.save(host, port, nick, key);
        log("调试口改配置 -> " + host + ":" + port);
        closeSocket();
    }

    public void debugReconnect() {
        closeSocket();
    }

    public void debugClear() {
        db.clear();
    }

    public String debugLogs(int n) {
        StringBuilder sb = new StringBuilder("[");
        synchronized (LOGS) {
            int skip = Math.max(0, LOGS.size() - n);
            int i = 0;
            for (String l : LOGS) {
                if (i++ < skip) continue;
                if (sb.length() > 1) sb.append(',');
                sb.append(JSONObject.quote(l));
            }
        }
        return sb.append(']').toString();
    }

    public String debugHistory(int n) {
        StringBuilder sb = new StringBuilder("[");
        java.util.List<Msg> list = db.recent(n);
        for (Msg m : list) {
            if (sb.length() > 1) sb.append(',');
            try {
                JSONObject j = new JSONObject();
                j.put("mine", m.mine);
                j.put("type", m.type);
                j.put("body", m.body);
                j.put("ts", m.ts);
                j.put("state", m.state);
                sb.append(j);
            } catch (Exception ignored) {
            }
        }
        return sb.append(']').toString();
    }

    private void closeSocket() {
        if (state != ST_ERROR) setState(ST_CONNECTING, "连接已断开");
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }
        socket = null;
        out = null;
        session = null;
        assembling.clear();
    }

    private void setState(int s, String detail) {
        state = s;
        for (Listener l : LISTENERS) l.onState(s, detail);
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(1, notification(detail));
        } catch (Exception ignored) {
        }
    }

    private void notifyMsg(Msg m) {
        for (Listener l : LISTENERS) l.onMsg(m);
        if (m != null && !m.mine) notifyIncoming(m);
    }

    /** 后台时像 QQ 一样弹通知 */
    private void notifyIncoming(Msg m) {
        if (appVisible) return;
        try {
            String text;
            if (Msg.T_IMAGE.equals(m.type)) text = "[图片]";
            else if (Msg.T_VOICE.equals(m.type)) text = "[语音] " + m.dur + "″";
            else text = m.body == null ? "" : m.body;
            if (text.isEmpty()) return;

            Intent open = new Intent(this, ChatActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);

            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CH_MSG)
                    : new Notification.Builder(this);
            b.setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentTitle(prefs.nick().isEmpty() ? "大肥鱼" : prefs.nick())
                    .setContentText(text.length() > 80 ? text.substring(0, 80) + "…" : text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .setDefaults(Notification.DEFAULT_ALL)
                    .setPriority(Notification.PRIORITY_HIGH);
            if (Build.VERSION.SDK_INT >= 21) b.setCategory(Notification.CATEGORY_MESSAGE);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify((int) (System.currentTimeMillis() & 0x7fffffff), b.build());
            log("通知已弹出: " + text.substring(0, Math.min(40, text.length())));
        } catch (Exception e) {
            log("通知失败: " + e.getMessage());
        }
    }

    private void log(String s) {
        Log.i(TAG, s);
        String line = (System.currentTimeMillis() / 1000) + " " + s;
        synchronized (LOGS) {
            LOGS.addLast(line);
            while (LOGS.size() > LOG_MAX) LOGS.removeFirst();
        }
        for (Listener l : LISTENERS) l.onLog(s);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel c = new NotificationChannel(CH, "连接状态",
                    NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            nm.createNotificationChannel(c);

            NotificationChannel m2 = new NotificationChannel(CH_MSG, "新消息",
                    NotificationManager.IMPORTANCE_HIGH);
            m2.setDescription("对端发来消息时提醒");
            m2.enableVibration(true);
            m2.setVibrationPattern(new long[]{0, 120, 80, 120});
            m2.setShowBadge(true);
            m2.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            nm.createNotificationChannel(m2);
        }
    }

    private Notification notification(String detail) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH)
                : new Notification.Builder(this);
        return b.setContentTitle("大肥鱼 Link")
                .setContentText(detail)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .build();
    }
}
