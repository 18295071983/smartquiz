package com.oilquiz.app.ai.speech;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import com.oilquiz.app.ai.speech.asr.SenseVoiceAsr;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 本地离线语音识别（SenseVoice）
 *
 * <p>与 {@link SystemSpeechRecognizer} 回调接口完全一致，但识别完全在 App 内完成：
 * App 前台用 {@link AudioRecord} 采集 16kHz 单声道 PCM → {@link SenseVoiceAsr}
 * 端侧离线推理。不依赖系统 RecognitionService，天然绕开 Android 12+ 对后台
 * 服务录音的隐私限制（这正是小米小爱 AsrService 在此设备上不可用的根因）。</p>
 */
public class LocalAsrRecognizer {

    private static final String TAG = "LocalAsrRecognizer";

    private static final int SAMPLE_RATE = SenseVoiceAsr.SAMPLE_RATE;
    private static final int CHUNK_SAMPLES = 3200; // 200ms

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LocalAsr-Worker");
        t.setPriority(Thread.NORM_PRIORITY);
        t.setDaemon(true);
        return t;
    });

    private volatile AudioRecord recorder;
    private volatile boolean recording = false;
    private volatile boolean cancelled = false;
    private Thread recordThread;
    private List<short[]> chunks = new ArrayList<>();
    private SystemSpeechRecognizer.RecognitionCallback currentCallback;

    public LocalAsrRecognizer(Context context) {
        this.context = context.getApplicationContext();
        // 后台预热加载模型（首次加载 228MB 需数秒，避免在 UI 线程阻塞）
        worker.execute(() -> {
            try {
                SenseVoiceAsr.getInstance(this.context);
                AILogger.i(TAG, "本地 ASR 模型已预热");
            } catch (Throwable t) {
                AILogger.w(TAG, "本地 ASR 预热失败: " + t.getMessage());
            }
        });
    }

    public boolean isListening() {
        return recording;
    }

    /** 本地 ASR 是否就绪（模型已加载完成） */
    public boolean isAvailable() {
        return SenseVoiceAsr.isReady();
    }

    /**
     * 开始本地语音识别（监听麦克风）
     * 可在任意线程调用
     */
    public void startListening(SystemSpeechRecognizer.RecognitionCallback callback) {
        currentCallback = callback;
        cancelled = false;
        worker.execute(this::doStart);
    }

    private void doStart() {
        try {
            // 确保模型已加载
            SenseVoiceAsr.getInstance(context);

            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufSize = Math.max(minBuf, CHUNK_SAMPLES * 2);
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                recorder.release();
                recorder = null;
                postError("录音初始化失败（麦克风不可用）");
                return;
            }
            chunks.clear();
            recorder.startRecording();
            recording = true;
            postReady();

            recordThread = new Thread(this::recordLoop, "LocalAsr-Record");
            recordThread.start();
        } catch (Exception e) {
            AILogger.e(TAG, "启动本地录音失败: " + e.getMessage(), e);
            postError("启动本地录音失败: " + e.getMessage());
        }
    }

    private void recordLoop() {
        short[] buf = new short[CHUNK_SAMPLES];
        while (recording && !cancelled) {
            int read = recorder.read(buf, 0, buf.length);
            if (read > 0) {
                short[] chunk = new short[read];
                System.arraycopy(buf, 0, chunk, 0, read);
                synchronized (chunks) {
                    chunks.add(chunk);
                }
            } else if (read < 0) {
                // read 返回负数 = 已 stop/出错，退出录音循环
                break;
            }
        }
    }

    /** 停止录音并触发识别 */
    public void stopListening() {
        worker.execute(this::doStop);
    }

    private void doStop() {
        if (!recording) {
            return;
        }
        recording = false;
        // 先 stop AudioRecord，让阻塞在 read() 的录音线程立即返回（避免 release 竞态导致 native 崩溃）
        try {
            if (recorder != null && recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop();
            }
        } catch (Exception ignored) {
        }
        try {
            if (recordThread != null) {
                recordThread.join(5000);
            }
        } catch (Exception ignored) {
        }
        releaseRecorder();

        if (cancelled) {
            return;
        }

        // 合并 PCM -> float [-1,1]
        long total = 0;
        synchronized (chunks) {
            for (short[] c : chunks) total += c.length;
        }
        if (total < SAMPLE_RATE / 4) {
            postError("录音太短，未检测到有效语音");
            postEnd();
            return;
        }
        float[] pcm = new float[(int) total];
        int idx = 0;
        synchronized (chunks) {
            for (short[] c : chunks) {
                for (short s : c) {
                    pcm[idx++] = s / 32768.0f;
                }
            }
        }

        try {
            String text = SenseVoiceAsr.getInstance(context).recognize(pcm, pcm.length);
            AILogger.i(TAG, "本地识别结果: " + text);
            if (text == null || text.trim().isEmpty()) {
                postError("未识别到语音内容");
            } else {
                postResult(text.trim());
            }
        } catch (Exception e) {
            AILogger.e(TAG, "本地识别失败: " + e.getMessage(), e);
            postError("本地识别失败: " + e.getMessage());
        }
        postEnd();
    }

    /** 取消识别（丢弃结果） */
    public void cancel() {
        cancelled = true;
        recording = false;
        try {
            if (recorder != null && recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop();
            }
        } catch (Exception ignored) {
        }
        try {
            if (recordThread != null) {
                recordThread.join(1500);
            }
        } catch (Exception ignored) {
        }
        releaseRecorder();
        synchronized (chunks) {
            chunks.clear();
        }
    }

    private void releaseRecorder() {
        try {
            if (recorder != null) {
                if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop();
                }
                recorder.release();
            }
        } catch (Exception ignored) {
        }
        recorder = null;
    }

    // ---- 主线程回调 ----

    private void postReady() {
        if (currentCallback != null) {
            mainHandler.post(() -> {
                if (currentCallback != null) currentCallback.onReadyForSpeech();
            });
        }
    }

    private void postResult(String text) {
        if (currentCallback != null) {
            mainHandler.post(() -> {
                if (currentCallback != null) currentCallback.onResult(text);
            });
        }
    }

    private void postError(String error) {
        if (currentCallback != null) {
            mainHandler.post(() -> {
                if (currentCallback != null) currentCallback.onError(error);
            });
        }
    }

    private void postEnd() {
        if (currentCallback != null) {
            mainHandler.post(() -> {
                if (currentCallback != null) currentCallback.onEnd();
            });
        }
    }
}
