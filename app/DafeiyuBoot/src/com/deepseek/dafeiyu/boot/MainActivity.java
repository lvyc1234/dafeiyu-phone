package com.deepseek.dafeiyu.boot;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;

/**
 * 大肥鱼启动器。
 *
 * 最低支持 Android 4.4（API 19）：
 *  - setStatusBarColor / setNavigationBarColor 是 API 21+，必须判版本调用
 *  - Settings.canDrawOverlays 是 API 23+，4.4 上悬浮窗权限安装即授予
 *  - ACTION_MANAGE_OVERLAY_PERMISSION 是 API 23+，4.4 退回应用详情页
 *  - startForegroundService 是 API 26+，4.4 用 startService
 *  - AutoX.js 6 在 4.4 装不上，改探测 Auto.js 4.1.1 等老包名
 */
public class MainActivity extends Activity {
    private static final int REQ_NOTIFY = 10;

    private TextView status;
    private LinearLayout root;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applySystemBars();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#0B0E14"));
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(30));
        scroll.addView(root);

        ImageView hero = new ImageView(this);
        hero.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(220));
        hero.setLayoutParams(heroLp);
        InputStream is = null;
        try {
            is = getAssets().open("main-visual.png");
            Bitmap bm = BitmapFactory.decodeStream(is);
            if (bm != null) hero.setImageBitmap(bm);
        } catch (Exception ignored) {
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception ignored) {
                }
            }
        }
        root.addView(hero);

        TextView title = text("大肥鱼启动器", 26, Color.WHITE, true);
        title.setPadding(0, dp(12), 0, 0);
        root.addView(title);

        TextView sub = text("开机后先启动这个服务，再由它打开 AutoX 里的大肥鱼。", 14, 0xFF9AA7B8, false);
        sub.setPadding(0, dp(6), 0, dp(16));
        root.addView(sub);

        status = text("", 13, 0xFFB8C4D4, false);
        status.setPadding(dp(14), dp(14), dp(14), dp(14));
        status.setBackgroundColor(0xFF141A24);
        root.addView(status);

        addButton("1. 允许悬浮窗 / 后台弹出界面", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openOverlay();
            }
        });
        addButton("2. 允许通知（前台服务）", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestNotifications();
            }
        });
        addButton("3. 电池 → 无限制", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openBattery();
            }
        });
        addButton("4. 打开 MIUI 自启动管理", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAutostart();
            }
        });
        addButton("打开 AutoX / Auto.js", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAutoJs();
            }
        });
        addButton("测试启动大肥鱼", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchAgent();
            }
        });
        addButton("测试开机服务（10 秒后启动）", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testService();
            }
        });

        TextView note = text(
                "说明：\n" +
                        "• 自动启动依赖系统 BOOT_COMPLETED 广播\n" +
                        "• MIUI 必须允许本应用自启动\n" +
                        "• 悬浮窗权限用于从后台打开 AutoX\n" +
                        "• 如果 MIUI 还有“后台弹出界面”开关，也请打开\n\n" +
                        "Android 4.4 说明：\n" +
                        "• 悬浮窗权限安装即生效，按钮 1 打不开系统页属正常\n" +
                        "• 4.4 没有通知权限与电池优化开关，按钮 2/3 可忽略\n" +
                        "• AutoX.js 6 要求 Android 7+，4.4 请装 Auto.js 4.1.1\n\n" +
                        "目标脚本：/sdcard/PhoneAgent/agent.js",
                13, 0xFF8B98A9, false);
        note.setPadding(0, dp(18), 0, 0);
        root.addView(note);

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void applySystemBars() {
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(Color.parseColor("#0B0E14"));
            getWindow().setNavigationBarColor(Color.parseColor("#0B0E14"));
        }
    }

    private boolean canOverlay() {
        if (Build.VERSION.SDK_INT >= 23) return Settings.canDrawOverlays(this);
        // 4.4：manifest 声明 SYSTEM_ALERT_WINDOW 后安装即授权
        return true;
    }

    private boolean canNotify() {
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void refreshStatus() {
        boolean overlay = canOverlay();
        boolean notify = canNotify();
        boolean autoJs = AutoJs.installed(this);
        String s = "悬浮窗：" + (overlay ? "已授权" : "未授权") +
                "\n通知：" + (notify ? "已授权" : "未授权") +
                "\nAutoX / Auto.js：" + AutoJs.label(this) +
                "\n开机广播：已注册（系统触发）" +
                "\n系统：Android " + Build.VERSION.RELEASE + "（API " + Build.VERSION.SDK_INT + "）";
        status.setText(s);
        status.setTextColor(overlay && autoJs ? 0xFF7EE2A8 : 0xFFFFC36B);
    }

    private void addButton(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(12);
        b.setLayoutParams(lp);
        b.setOnClickListener(listener);
        root.addView(b);
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private void openOverlay() {
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
                return;
            } catch (Exception e) {
                toast("打不开悬浮窗设置：" + e);
            }
        }
        // 4.4 及部分定制系统没有该页面，退回应用详情
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
            toast("Android 4.4 上悬浮窗权限安装即生效，无需额外授予");
        } catch (Exception e) {
            toast("打不开应用详情：" + e);
        }
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFY);
        } else {
            toast("当前系统不需要单独通知权限");
        }
    }

    private void openBattery() {
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
                return;
            } catch (Exception ignored) {
            }
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                return;
            } catch (Exception ignored) {
            }
        }
        toast("Android 4.4 没有电池优化开关，忽略即可");
    }

    private void openAutostart() {
        String[][] targets = new String[][]{
                {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                {"com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity"},
                {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"}
        };
        for (String[] target : targets) {
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName(target[0], target[1]));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            toast("打不开自启动设置：" + e);
        }
    }

    private void openAutoJs() {
        String[] item = AutoJs.find(this);
        if (item == null) {
            toast("没装 AutoX / Auto.js，先装 Auto.js 4.1.1（4.4 用）或 AutoX.js 6（7.0+）");
            return;
        }
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(item[0]);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            }
        } catch (Exception e) {
            toast("打开失败：" + e);
        }
    }

    private void launchAgent() {
        Intent intent = AutoJs.scriptIntent(this, "/sdcard/PhoneAgent/agent.js");
        if (intent == null) {
            toast("没装 AutoX / Auto.js");
            return;
        }
        try {
            startActivity(intent);
            toast("已发送启动请求");
        } catch (Exception e) {
            toast("启动失败：" + e);
        }
    }

    private void testService() {
        try {
            Intent i = new Intent(this, BootService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            toast("服务已启动，约 10 秒后打开 AutoX");
        } catch (Exception e) {
            toast("服务启动失败：" + e);
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshStatus();
    }
}
