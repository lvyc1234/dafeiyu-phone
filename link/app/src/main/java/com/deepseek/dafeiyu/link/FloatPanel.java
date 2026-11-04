package com.deepseek.dafeiyu.link;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.List;

/**
 * 悬浮窗状态面板：头像 + 在线状态 + 最后一句（截断 20 字）。
 *
 * 全部走 framework 原生 API（TYPE_APPLICATION_OVERLAY + WindowManager），
 * 不依赖 androidx，也不用 WebView。
 *
 * 交互：
 *   - 拖动：手指移动超过 3dp 即拖动，松手把坐标写进 Prefs
 *   - 点击：打开 ChatActivity（FLAG_ACTIVITY_NEW_TASK）
 *   - 长按：回调 Callback.onDismiss()，由 LinkService 关掉开关并销毁
 *
 * 线程：WindowManager 的操作必须在有 Looper 的线程（主线程）做，
 * 所以对外方法统一 post 到主线程；Listener 回调可能来自网络线程，也在这里收敛。
 */
public class FloatPanel implements LinkService.Listener {

    /** 长按关闭时通知宿主（LinkService） */
    public interface Callback {
        void onDismiss();
    }

    private static final String TAG = "FloatPanel";

    private static final int W_DP = 144;
    private static final int H_DP = 92;
    private static final int AVATAR_DP = 28;
    private static final int PAD_DP = 8;
    private static final int LAST_MAX = 20;
    private static final long LONG_PRESS_MS = 450;

    private final Context app;
    private final Callback cb;
    private final Prefs prefs;
    private final Db db;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private WindowManager wm;
    private View root;
    private ImageView avatarView;
    private TextView stateView;
    private TextView lastView;
    private WindowManager.LayoutParams lp;
    private volatile boolean showing;

    // 拖动 / 点击 / 长按的现场状态
    private float downRawX, downRawY;
    private int downX, downY;
    private boolean dragging, moved, longFired;

    private final Runnable longPressTask;

    public FloatPanel(Context c, Callback cb) {
        this.app = c.getApplicationContext();
        this.cb = cb;
        this.prefs = new Prefs(this.app);
        this.db = new Db(this.app);
        this.longPressTask = () -> {
            longFired = true;
            if (this.cb != null) this.cb.onDismiss();
        };
    }

    public boolean isShowing() {
        return showing;
    }

    // ------------------------------------------------------------ 显示 / 销毁

    /** 建窗并挂上去；没权限或失败返回 false（不抛异常，服务不能因此挂掉） */
    public boolean show() {
        if (showing) return true;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            ui.post(this::show);
            return false;
        }
        if (!Settings.canDrawOverlays(app)) {
            Log.w(TAG, "没有 SYSTEM_ALERT_WINDOW 权限，跳过悬浮面板");
            return false;
        }
        try {
            wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return false;

            root = buildView();
            lp = new WindowManager.LayoutParams(
                    dp(W_DP), dp(H_DP),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;

            int x = prefs.panelX();
            int y = prefs.panelY();
            if (x == Integer.MIN_VALUE) x = screenW() - dp(W_DP) - dp(8);
            if (y == Integer.MIN_VALUE) y = dp(72);
            lp.x = x;
            lp.y = y;
            clampPos();

            wm.addView(root, lp);
            showing = true;
            LinkService.addListener(this);

            LinkService s = LinkService.get();
            refreshState(s == null ? LinkService.ST_OFFLINE : s.state());
            refreshLast();
            Log.i(TAG, "悬浮面板已显示 @ " + lp.x + "," + lp.y);
            return true;
        } catch (Throwable t) {
            showing = false;
            root = null;
            Log.w(TAG, "悬浮面板创建失败: " + t);
            return false;
        }
    }

    public void destroy() {
        // 先置位：紧随其后的 show() 不能因为 posted 的移除还没执行就误判成"还开着"
        showing = false;
        LinkService.removeListener(this);
        Runnable r = () -> {
            ui.removeCallbacksAndMessages(null);
            try {
                if (wm != null && root != null) wm.removeViewImmediate(root);
            } catch (Throwable t) {
                Log.w(TAG, "移除悬浮面板失败: " + t);
            }
            root = null;
        };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else ui.post(r);
    }

    /** 画廊换头像后刷新（面板开着的时候） */
    public void updateAvatar() {
        ui.post(() -> {
            if (avatarView != null) {
                Bitmap b = loadAvatar();
                if (b != null) avatarView.setImageBitmap(b);
            }
        });
    }

    /** 外部（设置页）改了开关后，重新按 Prefs 刷新一次内容 */
    public void refresh() {
        ui.post(() -> {
            LinkService s = LinkService.get();
            refreshState(s == null ? LinkService.ST_OFFLINE : s.state());
            refreshLast();
            updateAvatarNow();
        });
    }

    // ------------------------------------------------------------ 视图

    private View buildView() {
        LinearLayout box = new LinearLayout(app);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(PAD_DP), dp(PAD_DP), dp(PAD_DP), dp(PAD_DP));
        box.setBackground(panelBg());

        LinearLayout top = new LinearLayout(app);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        avatarView = new ImageView(app);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(AVATAR_DP), dp(AVATAR_DP));
        avatarView.setLayoutParams(alp);
        avatarView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Bitmap av = loadAvatar();
        if (av != null) avatarView.setImageBitmap(av);
        top.addView(avatarView);

        stateView = new TextView(app);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        slp.leftMargin = dp(6);
        stateView.setLayoutParams(slp);
        stateView.setTextSize(11);
        stateView.setSingleLine(true);
        stateView.setEllipsize(TextUtils.TruncateAt.END);
        stateView.setTextColor(0xFFB0B0B0);
        stateView.setText("未连接");
        top.addView(stateView);

        box.addView(top);

        lastView = new TextView(app);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(5);
        lastView.setLayoutParams(llp);
        lastView.setTextSize(10);
        lastView.setTextColor(0xFFE6E6E6);
        lastView.setMaxLines(2);
        lastView.setEllipsize(TextUtils.TruncateAt.END);
        lastView.setText("还没有消息");
        box.addView(lastView);

        box.setOnTouchListener(this::onTouch);
        return box;
    }

    private GradientDrawable panelBg() {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(0xE61E1E1E);
        g.setCornerRadius(dp(12));
        g.setStroke(dp(1), 0x44FFFFFF);
        return g;
    }

    /** 圆形头像：优先 filesDir/avatar_current.jpg，其次内置 drawable，最后纯色占位 */
    private Bitmap loadAvatar() {
        Bitmap src = null;
        try {
            File f = new File(app.getFilesDir(), "avatar_current.jpg");
            if (f.exists()) src = Util.decodeFile(f.getAbsolutePath(), 160);
        } catch (Throwable ignored) {
        }
        if (src == null) {
            try {
                src = BitmapFactory.decodeResource(app.getResources(), R.drawable.avatar_her_small);
            } catch (Throwable ignored) {
            }
        }
        if (src == null) {
            try {
                src = BitmapFactory.decodeResource(app.getResources(), R.drawable.avatar_her);
            } catch (Throwable ignored) {
            }
        }
        int size = dp(AVATAR_DP);
        if (src == null) {
            Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.drawColor(0xFFB3E5FC);
            return circle(b, size);
        }
        return circle(src, size);
    }

    /** 中心裁成正方形再画成圆 */
    private static Bitmap circle(Bitmap src, int size) {
        try {
            int s = Math.min(src.getWidth(), src.getHeight());
            if (s <= 0) return src;
            Bitmap sq = (src.getWidth() == s && src.getHeight() == s)
                    ? src
                    : Bitmap.createBitmap(src, (src.getWidth() - s) / 2, (src.getHeight() - s) / 2, s, s);
            Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            BitmapShader sh = new BitmapShader(sq, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            float sc = size / (float) s;
            m.setScale(sc, sc);
            sh.setLocalMatrix(m);
            p.setShader(sh);
            float r = size / 2f;
            c.drawCircle(r, r, r, p);
            return out;
        } catch (Throwable t) {
            return src;
        }
    }

    // ------------------------------------------------------------ 触摸

    private boolean onTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (lp == null) return false;
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                downX = lp.x;
                downY = lp.y;
                dragging = false;
                moved = false;
                longFired = false;
                ui.postDelayed(longPressTask, LONG_PRESS_MS);
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (lp == null) return true;
                float dx = e.getRawX() - downRawX;
                float dy = e.getRawY() - downRawY;
                if (!moved && (Math.abs(dx) > dp(3) || Math.abs(dy) > dp(3))) {
                    moved = true;
                    ui.removeCallbacks(longPressTask);
                }
                if (moved) {
                    dragging = true;
                    lp.x = downX + (int) dx;
                    lp.y = downY + (int) dy;
                    clampPos();
                    try {
                        if (wm != null && root != null) wm.updateViewLayout(root, lp);
                    } catch (Throwable ignored) {
                    }
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
                ui.removeCallbacks(longPressTask);
                if (dragging) {
                    dragging = false;
                    prefs.savePanelPos(lp.x, lp.y);
                    return true;
                }
                if (!longFired) openChat();
                return true;

            case MotionEvent.ACTION_CANCEL:
                ui.removeCallbacks(longPressTask);
                if (dragging && lp != null) {
                    dragging = false;
                    prefs.savePanelPos(lp.x, lp.y);
                }
                return true;
        }
        return false;
    }

    private void clampPos() {
        if (lp == null) return;
        int sw = screenW();
        int sh = screenH();
        int minX = -dp(W_DP) / 2;
        int maxX = Math.max(minX, sw - dp(W_DP) / 2);
        int maxY = Math.max(0, sh - dp(H_DP));
        lp.x = Math.max(minX, Math.min(lp.x, maxX));
        lp.y = Math.max(0, Math.min(lp.y, maxY));
    }

    private void openChat() {
        try {
            Intent i = new Intent(app, ChatActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            app.startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "打开聊天失败: " + t);
        }
    }

    // ------------------------------------------------------------ 内容刷新

    private void refreshState(int s) {
        if (stateView == null) return;
        stateView.setText(stateText(s));
        stateView.setTextColor(stateColor(s));
        stateView.setContentDescription(stateText(s));
    }

    private void updateAvatarNow() {
        if (avatarView == null) return;
        Bitmap b = loadAvatar();
        if (b != null) avatarView.setImageBitmap(b);
    }

    private void refreshLast() {
        if (lastView == null) return;
        String text;
        try {
            List<Msg> list = db.recent(1);
            if (list == null || list.isEmpty()) {
                text = "还没有消息";
            } else {
                text = clip(textOf(list.get(list.size() - 1)), LAST_MAX);
            }
        } catch (Throwable t) {
            text = "";
        }
        lastView.setText(text);
        lastView.setContentDescription(text);
    }

    private static String textOf(Msg m) {
        if (m == null) return "";
        if (Msg.T_IMAGE.equals(m.type)) return "[图片]";
        if (Msg.T_VOICE.equals(m.type)) return "[语音]" + (m.dur > 0 ? m.dur + "″" : "");
        String b = m.body == null ? "" : m.body.trim().replace('\n', ' ');
        return b.isEmpty() ? "…" : b;
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private static String stateText(int s) {
        switch (s) {
            case LinkService.ST_ONLINE:
                return "在线";
            case LinkService.ST_CONNECTING:
                return "连接中";
            default:
                return "未连接";
        }
    }

    private static int stateColor(int s) {
        switch (s) {
            case LinkService.ST_ONLINE:
                return 0xFF7DE38B;
            case LinkService.ST_CONNECTING:
                return 0xFFFFC857;
            case LinkService.ST_ERROR:
                return 0xFFFF8A80;
            default:
                return 0xFFB0B0B0;
        }
    }

    // ------------------------------------------------------------ LinkService 回调

    @Override
    public void onState(int state, String detail) {
        ui.post(() -> refreshState(state));
    }

    @Override
    public void onMsg(Msg m) {
        ui.post(this::refreshLast);
    }

    @Override
    public void onTyping(boolean on, String text) {
        // 悬浮面板不做「正在输入」，保持安静
    }

    @Override
    public void onLog(String line) {
    }

    @Override
    public void onSyncDone(int total) {
        ui.post(this::refreshLast);
    }

    // ------------------------------------------------------------ 尺寸

    private int dp(int v) {
        return Math.round(v * app.getResources().getDisplayMetrics().density);
    }

    private int screenW() {
        try {
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getMetrics(dm);
            return dm.widthPixels;
        } catch (Throwable t) {
            return app.getResources().getDisplayMetrics().widthPixels;
        }
    }

    private int screenH() {
        try {
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getMetrics(dm);
            return dm.heightPixels;
        } catch (Throwable t) {
            return app.getResources().getDisplayMetrics().heightPixels;
        }
    }
}
