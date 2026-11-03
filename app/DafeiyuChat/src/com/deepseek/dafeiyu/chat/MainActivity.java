package com.deepseek.dafeiyu.chat;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/**
 * 大肥鱼聊天界面（本地 WebView）。
 *
 * Android 4.4（API 19）兼容点：
 *  - setStatusBarColor / setNavigationBarColor 是 API 21+
 *  - onShowFileChooser 是 API 21+：含 FileChooserParams 的子类只在 21+ 实例化，
 *    4.4 走 openFileChooser(uploadMsg, acceptType, capture)
 *  - 4.4 的 WebView 是 Chromium 30~33，不支持 ES6 / CSS 变量 / grid，
 *    页面已降级为 ES5 + 绝对定位布局
 *  - evaluateJavascript 是 API 19，正好可用，native 回调继续走它
 */
public class MainActivity extends Activity {
    private static final int REQ_PICK_IMAGE = 1001;

    private WebView webView;
    private ValueCallback<Uri[]> pendingArrayCallback;
    private ValueCallback<Uri> pendingCallback;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(Color.parseColor("#0B0E14"));
            getWindow().setNavigationBarColor(Color.parseColor("#0B0E14"));
        }

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#0B0E14"));
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccessFromFileURLs(true);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(Build.VERSION.SDK_INT >= 21
                ? new Api21ChromeClient(this)
                : new LegacyChromeClient(this));
        webView.addJavascriptInterface(new NtfyBridge(this, webView), "Android");

        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void pickImage(boolean forArray) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        try {
            startActivityForResult(Intent.createChooser(intent, "选择图片"), REQ_PICK_IMAGE);
        } catch (Exception e) {
            Toast.makeText(this, "打不开图片选择器：" + e, Toast.LENGTH_SHORT).show();
            if (forArray) {
                if (pendingArrayCallback != null) pendingArrayCallback.onReceiveValue(null);
                pendingArrayCallback = null;
            } else {
                if (pendingCallback != null) pendingCallback.onReceiveValue(null);
                pendingCallback = null;
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_IMAGE) return;
        Uri picked = (resultCode == RESULT_OK && data != null) ? data.getData() : null;

        if (pendingArrayCallback != null) {
            pendingArrayCallback.onReceiveValue(picked == null ? null : new Uri[]{picked});
            pendingArrayCallback = null;
        }
        if (pendingCallback != null) {
            pendingCallback.onReceiveValue(picked);
            pendingCallback = null;
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            try {
                webView.removeJavascriptInterface("Android");
                webView.destroy();
            } catch (Exception ignored) {
            }
            webView = null;
        }
        super.onDestroy();
    }

    /** API 19 及以下的文件选择回调（4.4 走三参数版本）。 */
    private static class LegacyChromeClient extends WebChromeClient {
        private final MainActivity activity;

        LegacyChromeClient(MainActivity activity) {
            this.activity = activity;
        }

        @SuppressWarnings("unused")
        public void openFileChooser(ValueCallback<Uri> uploadMsg, String acceptType,
                                    String capture) {
            if (activity.pendingCallback != null) {
                activity.pendingCallback.onReceiveValue(null);
            }
            activity.pendingCallback = uploadMsg;
            activity.pickImage(false);
        }

        @SuppressWarnings("unused")
        public void openFileChooser(ValueCallback<Uri> uploadMsg, String acceptType) {
            openFileChooser(uploadMsg, acceptType, null);
        }

        @SuppressWarnings("unused")
        public void openFileChooser(ValueCallback<Uri> uploadMsg) {
            openFileChooser(uploadMsg, "image/*", null);
        }
    }

    /** API 21+ 才加载：类体内引用 FileChooserParams，4.4 不会碰它。 */
    private static final class Api21ChromeClient extends WebChromeClient {
        private final MainActivity activity;

        Api21ChromeClient(MainActivity activity) {
            this.activity = activity;
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> filePathCallback,
                                         FileChooserParams params) {
            if (activity.pendingArrayCallback != null) {
                activity.pendingArrayCallback.onReceiveValue(null);
            }
            activity.pendingArrayCallback = filePathCallback;
            activity.pickImage(true);
            return true;
        }
    }
}
