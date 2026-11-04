package com.deepseek.dafeiyu.link;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** Camera2 + zxing 扫码；也支持从相册选图识别 */
public class ScanActivity extends Activity implements TextureView.SurfaceTextureListener {

    private static final int W = 1280;
    private static final int H = 720;
    private static final int REQ_CAM = 31;
    private static final int REQ_ALBUM = 32;

    private TextureView preview;
    private HandlerThread bgThread;
    private Handler bg;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private final AtomicBoolean done = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_scan);
        preview = findViewById(R.id.preview);
        preview.setSurfaceTextureListener(this);
        bgThread = new HandlerThread("scan");
        bgThread.start();
        bg = new Handler(bgThread.getLooper());

        findViewById(R.id.btnAlbum).setOnClickListener(v -> pickFromAlbum());
        findViewById(R.id.btnCancel).setOnClickListener(v -> finish());

        // 调试入口：am start -n .../.ScanActivity --ez pick true 直接拉起相册
        if (getIntent() != null && getIntent().getBooleanExtra("pick", false)) {
            preview.postDelayed(this::pickFromAlbum, 300);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        done.set(false);
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        bgThread.quitSafely();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        if (req == REQ_CAM) {
            if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) {
                openCamera();
            } else {
                Toast.makeText(this, "没相机权限，可以从相册选图", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ------------------------------------------------------------ 相册

    private void pickFromAlbum() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        try {
            startActivityForResult(Intent.createChooser(i, "选一张有二维码的图"), REQ_ALBUM);
        } catch (Exception e) {
            Toast.makeText(this, "打不开相册: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_ALBUM || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            byte[] raw;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                raw = bos.toByteArray();
            }
            Bitmap bmp = Util.decodeBytes(raw, 2000);
            String text = QrUtil.decodeBitmap(bmp);
            if (text == null || text.isEmpty()) {
                Toast.makeText(this, "这张图里没找到二维码，换一张试试", Toast.LENGTH_LONG).show();
                done.set(false);
                return;
            }
            deliver(text);
        } catch (Exception e) {
            Toast.makeText(this, "读图失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------ TextureView

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {}

    // ------------------------------------------------------------ Camera2

    private void openCamera() {
        if (device != null) return;
        CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            String pick = null;
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics c = cm.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    pick = id;
                    break;
                }
            }
            if (pick == null) {
                String[] ids = cm.getCameraIdList();
                if (ids.length == 0) return;
                pick = ids[0];
            }
            cm.openCamera(pick, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice d) {
                    device = d;
                    startPreview();
                }

                @Override
                public void onDisconnected(CameraDevice d) {
                    d.close();
                    device = null;
                }

                @Override
                public void onError(CameraDevice d, int err) {
                    d.close();
                    device = null;
                }
            }, bg);
        } catch (Exception e) {
            Toast.makeText(this, "打不开相机，可以从相册选图", Toast.LENGTH_LONG).show();
        }
    }

    private void startPreview() {
        try {
            SurfaceTexture st = preview.getSurfaceTexture();
            if (st == null) return;
            st.setDefaultBufferSize(W, H);
            Surface previewSurface = new Surface(st);

            reader = ImageReader.newInstance(W, H, ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(r -> {
                Image img = null;
                try {
                    img = r.acquireLatestImage();
                    if (img == null || done.get()) return;
                    byte[] y = toY(img);
                    String text = QrUtil.decodeYuv(y, img.getWidth(), img.getHeight());
                    if (text != null && !text.isEmpty() && done.compareAndSet(false, true)) {
                        runOnUiThread(() -> deliver(text));
                    }
                } catch (Exception ignored) {
                } finally {
                    if (img != null) img.close();
                }
            }, bg);

            device.createCaptureSession(Arrays.asList(previewSurface, reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            try {
                                CaptureRequest.Builder b =
                                        device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                b.addTarget(previewSurface);
                                b.addTarget(reader.getSurface());
                                b.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                s.setRepeatingRequest(b.build(), null, bg);
                            } catch (Exception e) {
                                Toast.makeText(ScanActivity.this, "预览失败", Toast.LENGTH_SHORT).show();
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            Toast.makeText(ScanActivity.this, "相机配置失败", Toast.LENGTH_SHORT).show();
                        }
                    }, bg);
        } catch (CameraAccessException e) {
            Toast.makeText(this, "相机访问失败", Toast.LENGTH_SHORT).show();
        }
    }

    private byte[] toY(Image img) {
        ByteBuffer buf = img.getPlanes()[0].getBuffer();
        byte[] out = new byte[buf.remaining()];
        buf.get(out);
        return out;
    }

    private void deliver(String text) {
        Intent i = new Intent();
        i.putExtra("result", text);
        setResult(RESULT_OK, i);
        finish();
    }

    private void closeCamera() {
        try {
            if (session != null) session.close();
        } catch (Exception ignored) {
        }
        session = null;
        try {
            if (device != null) device.close();
        } catch (Exception ignored) {
        }
        device = null;
        try {
            if (reader != null) reader.close();
        } catch (Exception ignored) {
        }
        reader = null;
    }
}
