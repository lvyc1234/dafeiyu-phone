package com.deepseek.dafeiyu.link;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * 把当前屏幕的节点树暴露给服务端（server.js 通过调试口 19528 读取）。
 * 比 input tap 按坐标更准：能按文字/描述找，也能直接 performAction(CLICK)。
 */
public class DafeiyuA11y extends AccessibilityService {

    private static volatile DafeiyuA11y inst;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        inst = this;
    }

    @Override
    public void onDestroy() {
        inst = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    public static boolean ready() { return inst != null; }

    /** 当前窗口的节点树（只保留有文字/可点击的，控数量） */
    public static JSONArray dumpNodes() {
        JSONArray out = new JSONArray();
        DafeiyuA11y s = inst;
        if (s == null) return out;
        try {
            AccessibilityNodeInfo root = s.getRootInActiveWindow();
            if (root == null) return out;
            walk(root, out, 0);
        } catch (Exception ignored) { }
        return out;
    }

    private static void walk(AccessibilityNodeInfo n, JSONArray out, int depth) {
        if (n == null || depth > 40 || out.length() > 400) return;
        try {
            String text = n.getText() == null ? "" : n.getText().toString();
            String desc = n.getContentDescription() == null ? "" : n.getContentDescription().toString();
            String cls = n.getClassName() == null ? "" : n.getClassName().toString();
            String pkg = n.getPackageName() == null ? "" : n.getPackageName().toString();
            boolean clickable = n.isClickable();
            if (!text.isEmpty() || !desc.isEmpty() || clickable) {
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                JSONObject o = new JSONObject();
                o.put("text", text);
                o.put("desc", desc);
                o.put("cls", cls);
                o.put("pkg", pkg);
                o.put("clickable", clickable);
                o.put("x1", r.left);
                o.put("y1", r.top);
                o.put("x2", r.right);
                o.put("y2", r.bottom);
                o.put("cx", (r.left + r.right) / 2);
                o.put("cy", (r.top + r.bottom) / 2);
                out.put(o);
            }
            for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), out, depth + 1);
        } catch (Exception ignored) { }
    }

    /** 按文字直接点节点（无障碍 performAction，比坐标稳） */
    public static boolean clickByText(String key, boolean desc) {
        DafeiyuA11y s = inst;
        if (s == null) return false;
        try {
            AccessibilityNodeInfo root = s.getRootInActiveWindow();
            if (root == null) return false;
            List<AccessibilityNodeInfo> list = desc
                    ? root.findAccessibilityNodeInfosByViewId(key)
                    : root.findAccessibilityNodeInfosByText(key);
            if (list == null) return false;
            for (AccessibilityNodeInfo n : list) {
                AccessibilityNodeInfo cur = n;
                for (int i = 0; i < 6 && cur != null; i++) {
                    if (cur.isClickable()) {
                        return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                    cur = cur.getParent();
                }
            }
        } catch (Exception ignored) { }
        return false;
    }
}
