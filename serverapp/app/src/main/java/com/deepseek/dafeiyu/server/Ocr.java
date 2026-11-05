package com.deepseek.dafeiyu.server;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 本地离线 OCR（ML Kit 中文）。用于画布、图片、WebView 等节点树读不到文字的场景。 */
public final class Ocr {

    private static TextRecognizer rec;

    private Ocr() { }

    private static synchronized TextRecognizer get() {
        if (rec == null) {
            rec = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        }
        return rec;
    }

    /** 对图片文件识别，返回 [{text, x, y}]（坐标为块中心） */
    public static JSONArray recognize(String imagePath) {
        JSONArray out = new JSONArray();
        try {
            Bitmap bm = BitmapFactory.decodeFile(imagePath);
            if (bm == null) return out;
            return recognize(bm);
        } catch (Exception e) {
            return out;
        }
    }

    public static JSONArray recognize(Bitmap bm) {
        JSONArray out = new JSONArray();
        final CountDownLatch latch = new CountDownLatch(1);
        try {
            InputImage img = InputImage.fromBitmap(bm, 0);
            get().process(img)
                    .addOnSuccessListener(visionText -> {
                        try {
                            for (com.google.mlkit.vision.text.Text.TextBlock block : visionText.getTextBlocks()) {
                                for (com.google.mlkit.vision.text.Text.Line line : block.getLines()) {
                                    String t = line.getText();
                                    if (t == null || t.trim().isEmpty()) continue;
                                    android.graphics.Rect r = line.getBoundingBox();
                                    JSONObject o = new JSONObject();
                                    o.put("text", t.trim());
                                    if (r != null) {
                                        o.put("x1", r.left);
                                        o.put("y1", r.top);
                                        o.put("x2", r.right);
                                        o.put("y2", r.bottom);
                                        o.put("cx", (r.left + r.right) / 2);
                                        o.put("cy", (r.top + r.bottom) / 2);
                                    }
                                    out.put(o);
                                }
                            }
                        } catch (Exception ignored) { }
                        latch.countDown();
                    })
                    .addOnFailureListener(e -> latch.countDown());
            latch.await(8, TimeUnit.SECONDS);
        } catch (Exception ignored) { }
        return out;
    }

    /** 纯文本（多行合并） */
    public static String text(String imagePath) {
        JSONArray arr = recognize(imagePath);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) sb.append(o.optString("text", "")).append('\n');
        }
        return sb.toString().trim();
    }
}
