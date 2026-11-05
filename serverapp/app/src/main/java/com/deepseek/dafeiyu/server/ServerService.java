package com.deepseek.dafeiyu.server;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/** 前台服务：把 node 挂着，别被系统回收 */
public class ServerService extends Service {

    private static final String CH = "dafeiyu-server";
    private static final int NID = 7301;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CH) == null) {
            NotificationChannel c = new NotificationChannel(CH, "服务端", NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            nm.createNotificationChannel(c);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = new Notification.Builder(this, CH)
                .setContentTitle("大肥鱼服务端运行中")
                .setContentText("聊天通道已就绪")
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setOngoing(true)
                .build();
        startForeground(NID, n);
        NodeRunner.start(this);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        NodeRunner.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
