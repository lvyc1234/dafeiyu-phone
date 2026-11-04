package com.deepseek.dafeiyu.link;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.MediaRecorder;
import android.os.Build;

import java.io.File;

/** 录音：AAC / MP4，单声道 22.05k，够聊天用且体积小 */
public class VoiceRecorder {

    private MediaRecorder rec;
    private File file;
    private long startMs;

    @SuppressLint("NewApi")
    public boolean start(Context ctx, File out) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                rec = new MediaRecorder(ctx);
            } else {
                rec = new MediaRecorder();
            }
            file = out;
            if (out.getParentFile() != null && !out.getParentFile().exists()) {
                out.getParentFile().mkdirs();
            }
            rec.setAudioSource(MediaRecorder.AudioSource.MIC);
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            rec.setAudioChannels(1);
            rec.setAudioSamplingRate(22050);
            rec.setAudioEncodingBitRate(32000);
            rec.setOutputFile(out.getAbsolutePath());
            rec.prepare();
            rec.start();
            startMs = System.currentTimeMillis();
            return true;
        } catch (Exception e) {
            release();
            return false;
        }
    }

    /** 返回录音秒数；失败返回 0 */
    public int stop() {
        int sec = 0;
        if (rec != null) {
            try {
                rec.stop();
            } catch (Exception ignored) {
            }
            sec = (int) Math.max(1, (System.currentTimeMillis() - startMs) / 1000);
        }
        release();
        return sec;
    }

    public void cancel() {
        if (rec != null) {
            try {
                rec.stop();
            } catch (Exception ignored) {
            }
        }
        release();
        if (file != null) file.delete();
    }

    private void release() {
        if (rec != null) {
            try {
                rec.reset();
                rec.release();
            } catch (Exception ignored) {
            }
            rec = null;
        }
    }

    public File file() { return file; }
}
