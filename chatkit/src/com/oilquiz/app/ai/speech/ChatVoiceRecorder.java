package com.oilquiz.app.ai.speech;

import android.content.Context;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.chat.ChatMessage;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 聊天录音器（全局可复用）。
 *
 * 从 AIChatActivity 录音链路抽取：MediaRecorder 生命周期（创建 → prepare → start →
 * stop → release）、临时音频文件创建、录音状态回调。宿主通过 {@link Host}
 * 接收录制状态与成品附件，不依赖具体页面。
 *
 * 用法：
 * <pre>
 * ChatVoiceRecorder recorder = new ChatVoiceRecorder(context, host);
 * recorder.startRecording();     // 开始（失败自动释放并回调 toast）
 * recorder.stopRecording();      // 结束 → 回调 onAttachmentReady（若成功）
 * recorder.release();            // 页面销毁时释放
 * </pre>
 */
public class ChatVoiceRecorder {

    /** 宿主回调 */
    public interface Host {
        void onToast(int resId);
        void onToastString(String message);
        void runOnUi(Runnable r);
        /** 录音成功产出附件（宿主决定加入输入区/历史） */
        void onAttachmentReady(ChatMessage.Attachment attachment);
    }

    private final Context context;
    private final Host host;

    private MediaRecorder mediaRecorder;
    private String recordingFilePath;
    private volatile boolean isRecording;

    public ChatVoiceRecorder(Context context, Host host) {
        this.context = context.getApplicationContext();
        this.host = host;
    }

    public boolean isRecording() { return isRecording; }

    /** 创建临时音频文件 */
    public File createAudioFile() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(new Date());
        String audioFileName = "AUDIO_" + timeStamp + "_";
        File storageDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (storageDir == null) {
            storageDir = context.getCacheDir();
        }
        return File.createTempFile(audioFileName, ".mp4", storageDir);
    }

    /** 开始录音（失败自动释放并回调提示） */
    public void startRecording() {
        try {
            File audioFile = createAudioFile();
            if (audioFile == null) {
                host.onToast(R.string.h_24f55c59);
                return;
            }
            recordingFilePath = audioFile.getAbsolutePath();

            mediaRecorder = new MediaRecorder();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                mediaRecorder.setAudioSamplingRate(44100);
                mediaRecorder.setAudioEncodingBitRate(128000);
            } else {
                mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.DEFAULT);
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.DEFAULT);
            }

            mediaRecorder.setOutputFile(recordingFilePath);
            mediaRecorder.prepare();
            mediaRecorder.start();

            isRecording = true;
            host.onToast(R.string.h_c3f0e02f);
            host.runOnUi(() -> host.onToast(R.string.h_b1c1d48c)); // 录音中 UI 态
        } catch (Exception e) {
            e.printStackTrace();
            host.onToastString("录音启动失败: " + e.getMessage());
            releaseMediaRecorder();
        }
    }

    /** 停止录音并产出附件（成功回调 onAttachmentReady） */
    public void stopRecording() {
        if (!isRecording || mediaRecorder == null) {
            return;
        }
        try {
            mediaRecorder.stop();
            isRecording = false;

            File audioFile = new File(recordingFilePath);
            if (audioFile.exists() && audioFile.length() > 0) {
                Uri audioUri = Uri.fromFile(audioFile);
                ChatMessage.Attachment attachment = new ChatMessage.Attachment(
                        "audio", audioUri.toString(), "语音消息");
                host.runOnUi(() -> host.onAttachmentReady(attachment));
                host.onToast(R.string.h_e2a65c56);
            } else {
                host.onToast(R.string.h_ba8cd317);
            }
        } catch (Exception e) {
            e.printStackTrace();
            host.onToastString("录音保存失败: " + e.getMessage());
        } finally {
            releaseMediaRecorder();
            host.runOnUi(() -> host.onToast(R.string.h_f4854afd)); // 结束 UI 态
        }
    }

    /** 释放 MediaRecorder 资源 */
    public void releaseMediaRecorder() {
        if (mediaRecorder != null) {
            try {
                mediaRecorder.release();
            } catch (Exception e) {
                e.printStackTrace();
            }
            mediaRecorder = null;
        }
    }

    /** 页面销毁时调用 */
    public void release() {
        if (isRecording) {
            try { mediaRecorder.stop(); } catch (Exception ignored) {}
            isRecording = false;
        }
        releaseMediaRecorder();
    }
}
