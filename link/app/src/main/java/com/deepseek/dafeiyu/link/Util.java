package com.deepseek.dafeiyu.link;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class Util {

    private Util() {}

    public static long nowSec() {
        return System.currentTimeMillis() / 1000;
    }

    public static String fmtTime(long sec) {
        SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        return f.format(new Date(sec * 1000));
    }

    public static byte[] readAll(File f) throws Exception {
        try (InputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    public static void writeAll(File f, byte[] data) throws Exception {
        File p = f.getParentFile();
        if (p != null && !p.exists()) p.mkdirs();
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }

    /** 压到 maxBytes 以内，优先保比例，质量从 85 往下退 */
    public static byte[] compressJpeg(Bitmap bmp, int maxBytes) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int maxSide = 1600;
        if (Math.max(w, h) > maxSide) {
            float s = maxSide / (float) Math.max(w, h);
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, Math.round(w * s), Math.round(h * s), true);
            if (scaled != bmp) bmp = scaled;
        }
        byte[] out = null;
        for (int q = 85; q >= 35; q -= 10) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, q, bos);
            out = bos.toByteArray();
            if (out.length <= maxBytes) return out;
        }
        return out == null ? new byte[0] : out;
    }

    public static Bitmap decodeFile(String path, int maxW) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        int scale = 1;
        while (o.outWidth / scale > maxW) scale *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = scale;
        return BitmapFactory.decodeFile(path, o2);
    }

    public static Bitmap decodeBytes(byte[] data, int maxW) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int scale = 1;
        while (o.outWidth / scale > maxW) scale *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = scale;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
    }

    public static File mediaDir(android.content.Context c, String sub) {
        File d = new File(c.getFilesDir(), sub);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static String humanSize(int bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
        return String.format(Locale.CHINA, "%.1fMB", bytes / 1048576f);
    }
}
