package com.deepseek.dafeiyu.link;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 立绘画廊：扫目录里的图，网格展示，点一张就复制成 filesDir/avatar_current.jpg。
 *
 * 扫描两个目录（并集，按文件名排序）：
 *   1. getFilesDir()/gallery/                                  —— App 私有目录
 *   2. getExternalFilesDir(null)/gallery/                      —— /sdcard/Android/data/<pkg>/files/gallery/
 * 第 2 个就是空目录提示里让用户丢文件的地方（adb push / 文件管理器都能进）。
 *
 * 没有 androidx，所以用 GridView + BaseAdapter，不引 RecyclerView。
 */
public class GalleryActivity extends Activity {

    private static final String[] EXT = {".jpg", ".jpeg", ".png", ".webp"};
    private static final String AVATAR_FILE = "avatar_current.jpg";
    private static final int COLS = 3;

    private GridView grid;
    private TextView emptyView;
    private TextView countView;
    private ThumbAdapter adapter;

    private final List<File> items = new ArrayList<>();
    private LruCache<String, Bitmap> cache;
    private int cell;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_gallery);

        grid = findViewById(R.id.grid);
        emptyView = findViewById(R.id.empty);
        countView = findViewById(R.id.count);

        int w = getResources().getDisplayMetrics().widthPixels;
        int pad = dp(6) * 2;
        int spacing = dp(4) * (COLS - 1);
        cell = Math.max(dp(72), (w - pad - spacing) / COLS);

        // 缩略图缓存，按 KB 计，最多占 1/8 可用内存
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        cache = new LruCache<String, Bitmap>(Math.max(2048, maxKb)) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }
        };

        adapter = new ThumbAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < items.size()) setAvatar(items.get(position));
        });

        findViewById(R.id.btnRefresh).setOnClickListener(v -> reload());
        findViewById(R.id.btnClose).setOnClickListener(v -> finish());

        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 用 adb push 传完图回到前台时能看见
        if (items.isEmpty()) reload();
    }

    // ------------------------------------------------------------ 扫描

    private void reload() {
        items.clear();
        collect(new File(getFilesDir(), "gallery"), items);
        File ext = getExternalFilesDir(null);
        if (ext != null) collect(new File(ext, "gallery"), items);

        File[] arr = items.toArray(new File[0]);
        Arrays.sort(arr, (a, c) -> a.getName().compareToIgnoreCase(c.getName()));
        items.clear();
        items.addAll(Arrays.asList(arr));

        boolean none = items.isEmpty();
        grid.setVisibility(none ? View.GONE : View.VISIBLE);
        emptyView.setVisibility(none ? View.VISIBLE : View.GONE);
        countView.setText(items.isEmpty() ? "" : items.size() + " 张");
        cache.evictAll();
        adapter.notifyDataSetChanged();
    }

    private static void collect(File dir, List<File> out) {
        if (dir == null || !dir.isDirectory()) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f == null || !f.isFile()) continue;
            String n = f.getName().toLowerCase(Locale.ROOT);
            for (String e : EXT) {
                if (n.endsWith(e)) {
                    out.add(f);
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------ 设头像

    private void setAvatar(File f) {
        Bitmap bmp = Util.decodeFile(f.getAbsolutePath(), 720);
        if (bmp == null) {
            Toast.makeText(this, R.string.gallery_pick_fail, Toast.LENGTH_SHORT).show();
            return;
        }
        byte[] jpg = Util.compressJpeg(bmp, 600 * 1024);
        if (jpg == null || jpg.length == 0) {
            Toast.makeText(this, R.string.gallery_pick_fail, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Util.writeAll(new File(getFilesDir(), AVATAR_FILE), jpg);
            Toast.makeText(this, R.string.gallery_saved, Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        } catch (Exception e) {
            Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------ 适配器

    private class ThumbAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public File getItem(int i) {
            return items.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {
            ImageView iv;
            if (convertView instanceof ImageView) {
                iv = (ImageView) convertView;
            } else {
                iv = new ImageView(GalleryActivity.this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setPadding(dp(2), dp(2), dp(2), dp(2));
                iv.setBackgroundColor(0x22888888);
            }
            iv.setLayoutParams(new android.widget.AbsListView.LayoutParams(cell, cell));

            File f = items.get(pos);
            String key = f.getAbsolutePath();
            Bitmap bmp = cache.get(key);
            if (bmp == null) {
                bmp = Util.decodeFile(key, Math.max(cell, dp(96)) * 2);
                if (bmp != null) cache.put(key, bmp);
            }
            if (bmp != null) {
                iv.setImageBitmap(bmp);
            } else {
                iv.setImageResource(android.R.drawable.ic_menu_gallery);
            }
            return iv;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
