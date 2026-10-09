package com.oilquiz.app.ai.speech.asr;

import android.content.Context;
import android.content.res.AssetManager;

import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * 本地离线语音识别（SenseVoice-Small，sherpa-onnx 生态 ONNX 模型）
 *
 * <p>完全端侧离线：App 前台用 AudioRecord 录音（16kHz 单声道）→ 本类完成
 * fbank → LFR → CMVN → ONNX 推理（SenseVoice CTC）→ token 拼接，全程不依赖
 * 系统 RecognitionService / 云端服务，绕开 Android 12+ 后台录音限制。</p>
 *
 * <p>与 sherpa-onnx 参考实现逐项对齐（features.cc / offline-recognizer-sense-voice-impl.h）：</p>
 * <ul>
 *   <li>fbank：kaldi 风格，hamming 窗、25ms 窗/10ms 移、snip_edges=true、preemph=0.97、80 mel bins</li>
 *   <li>LFR：window_size=7 / window_shift=6（读模型 metadata）</li>
 *   <li>CMVN：neg_mean / inv_stddev（读模型 metadata）</li>
 *   <li>CTC greedy：逐帧 argmax，跳过 blank（默认 0），去重</li>
 *   <li>token 拼接：跳过前 4 个（lang/emotion/event 标记），▁ 转空格</li>
 * </ul>
 */
public class SenseVoiceAsr {

    private static final String TAG = "SenseVoiceAsr";

    private static final String MODEL_ASSET = "asr/model.int8.onnx";
    private static final String TOKENS_ASSET = "asr/tokens.txt";
    private static final String VAD_ASSET = "asr/silero_vad.onnx";

    // 音频参数
    public static final int SAMPLE_RATE = 16000;
    private static final int FRAME_LENGTH = 400;   // 25ms @16k
    private static final int FRAME_SHIFT = 160;    // 10ms @16k
    private static final int NFFT = 512;
    private static final int NUM_BINS = 80;
    private static final float PREEMPH = 0.97f;
    private static final float LOW_FREQ = 20.0f;
    private static final float LOG_FLOOR = 1.0e-10f;

    // ==================== VAD（Silero VAD v5，消除静音 + 长音频分段） ====================
    // SenseVoice 是**非流式**模型，按"一段话"设计（sherpa-onnx 参考管线同样先过 VAD）。
    // 不做分段时，录音里所有静音帧都会被算进一次推理：既浪费算力，长录音的识别质量也会下降
    // （超出模型训练时长分布）。这里对齐 sherpa-onnx 的做法，用 Silero VAD 先切出语音段。
    private static final int VAD_CONTEXT = 64;                       // v5：每窗前置的上下文采样数
    private static final int VAD_WINDOW_16K = 512;                   // v5 @16k：每窗采样数
    private static final int VAD_WINDOW = VAD_WINDOW_16K;            // 本类只处理 16k
    private static final float VAD_THRESHOLD = 0.5f;                 // 语音概率阈值
    private static final int VAD_MIN_SPEECH_SAMPLES = SAMPLE_RATE / 4;   // 250ms：短于此不算一段话
    private static final int VAD_MIN_SILENCE_SAMPLES = SAMPLE_RATE / 10; // 100ms：静音持续这么久才断句
    private static final int VAD_PAD_SAMPLES = SAMPLE_RATE / 5;          // 200ms：段首尾各留一点，避免切掉字头字尾
    private static final int VAD_MAX_SEGMENT_SAMPLES = SAMPLE_RATE * 30; // 30s 强制切分，防单段过长
    /** 整段短于此时不做 VAD，直接整段识别（省一次模型推理） */
    private static final int VAD_SKIP_BELOW_SAMPLES = SAMPLE_RATE * 2;

    private static volatile SenseVoiceAsr INSTANCE;

    /** 模型使用计数：识别线程 acquire 占用、识别完 release 释放；并发识别互不打断 */
    private static int inUse = 0;

    /** 空闲卸载延时（ms）：识别完保留模型，连续使用零延迟；超时未用才卸载 */
    private static final long UNLOAD_IDLE_MS = 60_000L;
    private static android.os.Handler ttlHandler;
    private static Runnable unloadTask;
    private static volatile boolean callbacksRegistered = false;

    private static android.os.Handler ttlHandler() {
        if (ttlHandler == null) {
            ttlHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        }
        return ttlHandler;
    }

    private static void cancelUnloadTask() {
        if (unloadTask != null) {
            ttlHandler().removeCallbacks(unloadTask);
            unloadTask = null;
        }
    }

    private static void scheduleUnloadTask() {
        cancelUnloadTask();
        unloadTask = SenseVoiceAsr::releaseIfIdle;
        ttlHandler().postDelayed(unloadTask, UNLOAD_IDLE_MS);
    }

    /** 空闲卸载：无人使用且模型存在时卸载（TTL 到期或系统低内存时调用）；识别中不打断 */
    private static void releaseIfIdle() {
        synchronized (SenseVoiceAsr.class) {
            if (unloadTask != null) {
                ttlHandler().removeCallbacks(unloadTask);
                unloadTask = null;
            }
            if (inUse == 0 && INSTANCE != null) {
                try {
                    INSTANCE.session.close();
                } catch (Exception ignored) {
                }
                // VAD 会话一并释放：它是懒加载的，不关就会在空闲卸载后仍常驻
                try {
                    if (INSTANCE.vadSession != null) {
                        INSTANCE.vadSession.close();
                        INSTANCE.vadSession = null;
                    }
                } catch (Exception ignored) {
                }
                INSTANCE = null;
                AILogger.i(TAG, "本地 ASR 模型空闲超时已卸载，释放内存（下次识别时重新初始化）");
            }
        }
    }

    /** 系统低内存回调：内存紧张时立即释放 ASR 模型（优先保住常驻的本地 LLM） */
    private static void registerTrimMemoryCallback(Context context) {
        if (callbacksRegistered) {
            return;
        }
        try {
            context.getApplicationContext().registerComponentCallbacks(
                    new android.content.ComponentCallbacks2() {
                        @Override
                        public void onTrimMemory(int level) {
                            if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
                                    || level == android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                                releaseIfIdle();
                            }
                        }

                        @Override
                        public void onLowMemory() {
                            releaseIfIdle();
                        }

                        @Override
                        public void onConfigurationChanged(android.content.res.Configuration newConfig) {
                        }
                    });
            callbacksRegistered = true;
        } catch (Exception e) {
            AILogger.w(TAG, "注册低内存回调失败: " + e.getMessage());
        }
    }

    private final OrtEnvironment env;
    private final OrtSession session;

    /** Silero VAD 会话。懒加载：短音频不需要它，省一次模型加载与常驻内存。 */
    private OrtSession vadSession;
    private Context appContext;

    // 模型 metadata 参数
    private final int blankId;
    private final int lfrWindowSize;
    private final int lfrWindowShift;
    private final boolean normalizeSamples;
    private final int withoutItnId;
    private final int langAutoId;
    private final float[] negMean;
    private final float[] invStddev;

    // tokens.txt: id -> token
    private final HashMap<Integer, String> tokenMap = new HashMap<>();

    // 预计算 mel 滤波器组 (NUM_BINS x (NFFT/2+1))
    private final float[][] melFilters;

    private SenseVoiceAsr(Context context) throws Exception {
        appContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        File modelFile = extractAssetIfNeeded(context, MODEL_ASSET, "model.int8.onnx");
        File tokensFile = extractAssetIfNeeded(context, TOKENS_ASSET, "tokens.txt");

        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        // 推理线程 4：平衡速度与内存（cores=7 会增加线程级内存开销，内存紧张设备易触发 LMK）
        opts.setIntraOpNumThreads(4);
        opts.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
        session = env.createSession(modelFile.getAbsolutePath(), opts);
        AILogger.i(TAG, "模型加载完成: " + modelFile.length() / 1048576 + "MB, threads=4");

        // 读取 metadata
        ai.onnxruntime.OnnxModelMetadata meta = session.getMetadata();
        Map<String, String> md = meta.getCustomMetadata();
        blankId = parseInt(md, "blank_id", 0);
        lfrWindowSize = parseInt(md, "lfr_window_size", 7);
        lfrWindowShift = parseInt(md, "lfr_window_shift", 6);
        normalizeSamples = parseInt(md, "normalize_samples", 0) != 0;
        withoutItnId = parseInt(md, "without_itn", 15);
        langAutoId = parseInt(md, "lang_auto", 0);
        negMean = parseFloatVec(md, "neg_mean");
        invStddev = parseFloatVec(md, "inv_stddev");
        AILogger.i(TAG, "blank=" + blankId + " lfr=" + lfrWindowSize + "/" + lfrWindowShift
                + " norm=" + normalizeSamples + " itn=" + withoutItnId + " lang=" + langAutoId);

        // 加载 tokens.txt
        loadTokens(tokensFile);
        AILogger.i(TAG, "tokens 加载: " + tokenMap.size());

        // 预计算 mel 滤波器组
        melFilters = buildMelFilters();
    }

    public static SenseVoiceAsr getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (SenseVoiceAsr.class) {
                if (INSTANCE == null) {
                    try {
                        INSTANCE = new SenseVoiceAsr(context.getApplicationContext());
                    } catch (Exception e) {
                        AILogger.e(TAG, "本地 ASR 初始化失败: " + e.getMessage(), e);
                        throw new RuntimeException("本地语音识别模型加载失败: " + e.getMessage(), e);
                    }
                }
            }
        }
        return INSTANCE;
    }

    /**
     * 获取模型实例并占用（识别前必须调用，识别完必须 {@link #release()}）。
     * 与 {@link #release()} 配对使用：并发识别各自 acquire，模型在空闲 TTL
     * 到期或系统低内存时才卸载，识别中途不会被卸载（session 关闭竞态已消除）。
     */
    public static SenseVoiceAsr acquire(Context context) {
        synchronized (SenseVoiceAsr.class) {
            cancelUnloadTask(); // 有使用：取消待卸载
            if (INSTANCE == null) {
                try {
                    INSTANCE = new SenseVoiceAsr(context.getApplicationContext());
                    registerTrimMemoryCallback(context);
                } catch (Exception e) {
                    AILogger.e(TAG, "本地 ASR 初始化失败: " + e.getMessage(), e);
                    throw new RuntimeException("本地语音识别模型加载失败: " + e.getMessage(), e);
                }
            }
            inUse++;
            return INSTANCE;
        }
    }

    /** 识别结束释放占用：模型保留（空闲 60s 无使用才卸载，连续识别零延迟） */
    public static void release() {
        synchronized (SenseVoiceAsr.class) {
            if (inUse > 0) {
                inUse--;
            }
            if (inUse == 0) {
                scheduleUnloadTask(); // 空闲开始计时，超时未用自动卸载
            }
        }
    }

    /** 是否已初始化成功（模型已加载） */
    public static boolean isReady() {
        return INSTANCE != null;
    }

    /**
     * 立即请求卸载（无人使用时卸载；有识别在进行时保持，由其 release 后空闲卸载）。
     * 识别入口请使用 {@link #acquire(Context)} / {@link #release()}，勿在识别中途直接调用本方法。
     */
    public static void releaseInstance() {
        releaseIfIdle();
    }

    // ==================== 对外识别入口 ====================

    /**
     * 识别 16kHz 单声道 float PCM（范围 [-1,1]）
     *
     * <p>先用 Silero VAD 切出语音段（丢静音、长录音分段），再逐段跑 SenseVoice，
     * 最后按顺序拼接。VAD 不可用或音频很短时退化为整段识别，保证行为与以前一致。</p>
     *
     * @return 识别文本；无有效内容返回空串
     */
    public String recognize(float[] pcm, int nSamples) throws Exception {
        if (pcm == null || nSamples <= 0) {
            return "";
        }
        long t0 = System.currentTimeMillis();

        // 0. VAD 分段（省静音算力 / 控制单段长度）
        List<int[]> segments = vadSegments(pcm, nSamples);
        if (segments.isEmpty()) {
            AILogger.d(TAG, "VAD 未检测到语音: " + (nSamples / (float) SAMPLE_RATE) + "s");
            return "";
        }

        StringBuilder merged = new StringBuilder();
        int speechSamples = 0;
        for (int[] seg : segments) {
            int start = seg[0];
            int len = seg[1];
            if (len <= 0) continue;
            speechSamples += len;
            String part = recognizeRange(pcm, start, len);
            if (part == null || part.isEmpty()) continue;
            if (merged.length() > 0) merged.append(' ');
            merged.append(part);
        }
        long ms = System.currentTimeMillis() - t0;
        AILogger.d(TAG, "识别耗时: " + ms + "ms, 段数=" + segments.size()
                + ", 语音=" + (speechSamples / (float) SAMPLE_RATE) + "s"
                + ", 总=" + (nSamples / (float) SAMPLE_RATE) + "s");
        return merged.toString().trim();
    }

    /**
     * 识别 PCM 的一个区间（单段话）：fbank → LFR → CMVN → ONNX → CTC → token 拼接。
     */
    private String recognizeRange(float[] pcm, int offset, int length) throws Exception {
        // 1. fbank
        float[] fbank = computeFbank(pcm, offset, length);
        int nFrames = fbank.length / NUM_BINS;
        if (nFrames <= 0) {
            return "";
        }

        // 2. LFR
        float[] lfr = applyLfr(fbank, nFrames, NUM_BINS);
        int lfrFrames = lfr.length / (NUM_BINS * lfrWindowSize);
        if (lfrFrames <= 0) {
            return "";
        }

        // 3. CMVN
        applyCmvn(lfr);

        // 4. ONNX 推理
        int featDim = NUM_BINS * lfrWindowSize; // 560
        long[] shape = {1, lfrFrames, featDim};
        try (OnnxTensor x = OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(lfr), shape);
             OnnxTensor xLen = OnnxTensor.createTensor(env, new int[]{lfrFrames});
             OnnxTensor lang = OnnxTensor.createTensor(env, new int[]{langAutoId});
             OnnxTensor textNorm = OnnxTensor.createTensor(env, new int[]{withoutItnId})) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("x", x);
            inputs.put("x_length", xLen);
            inputs.put("language", lang);
            inputs.put("text_norm", textNorm);

            try (OrtSession.Result result = session.run(inputs)) {
                OnnxTensor logitsTensor = (OnnxTensor) result.get(0);
                float[][][] logits = (float[][][]) logitsTensor.getValue();

                // 5. CTC greedy 解码
                List<Integer> tokens = ctcGreedy(logits[0]);

                // 6. token 拼接（跳过前 4 个语言/情感/事件标记）
                return joinTokens(tokens);
            }
        }
    }

    // ==================== fbank（kaldi 风格） ====================

    private float[] computeFbank(float[] x, int n) {
        return computeFbank(x, 0, n);
    }

    /**
     * 计算 [offset, offset+length) 区间的 fbank 特征（kaldi 风格）。
     *
     * <p>范围参数供 VAD 分段后逐段识别使用；均值归一化与预加重都在段内独立计算，
     * 与 sherpa-onnx 对"一段话"的处理一致（每段是独立输入）。</p>
     */
    private float[] computeFbank(float[] x, int offset, int length) {
        // normalize_samples=false -> 样本 x32768（kaldi 范围）
        float scale = normalizeSamples ? 1.0f : 32768.0f;

        // remove dc offset + 预加重（段内独立）
        float mean = 0;
        for (int i = 0; i < length; i++) mean += x[offset + i];
        mean /= length;
        int n = length;
        float[] w = new float[n];
        for (int i = 0; i < n; i++) {
            float v = (x[offset + i] - mean) * scale;
            w[i] = (i == 0) ? v : v - PREEMPH * ((x[offset + i - 1] - mean) * scale);
        }

        // 分帧（snip_edges=true）
        int nFrames = (n - FRAME_LENGTH) / FRAME_SHIFT + 1;
        if (nFrames < 1) {
            nFrames = 1;
        }

        // hamming 窗
        float[] win = new float[FRAME_LENGTH];
        for (int i = 0; i < FRAME_LENGTH; i++) {
            win[i] = (float) (0.54 - 0.46 * Math.cos(2 * Math.PI * i / (FRAME_LENGTH - 1)));
        }

        // 每帧 FFT 功率谱 + mel
        float[] out = new float[nFrames * NUM_BINS];
        float[] frame = new float[NFFT];
        float[] re = new float[NFFT];
        float[] im = new float[NFFT];
        int nfftHalf = NFFT / 2 + 1;
        float[] spec = new float[nfftHalf];

        for (int f = 0; f < nFrames; f++) {
            int start = f * FRAME_SHIFT;
            java.util.Arrays.fill(frame, 0f);
            for (int i = 0; i < FRAME_LENGTH; i++) {
                frame[i] = w[start + i] * win[i];
            }
            fft(frame, re, im);
            for (int i = 0; i < nfftHalf; i++) {
                spec[i] = re[i] * re[i] + im[i] * im[i];
            }
            // mel 滤波 + log
            for (int b = 0; b < NUM_BINS; b++) {
                float sum = 0;
                float[] fb = melFilters[b];
                for (int i = 0; i < nfftHalf; i++) {
                    sum += spec[i] * fb[i];
                }
                out[f * NUM_BINS + b] = (float) Math.log(Math.max(sum, LOG_FLOOR));
            }
        }
        return out;
    }

    // ==================== LFR ====================

    private float[] applyLfr(float[] feats, int nFrames, int dim) {
        int outFrames = 1 + (nFrames - 1) / lfrWindowShift;
        int outDim = dim * lfrWindowSize;
        float[] out = new float[outFrames * outDim];
        int leftCtx = (lfrWindowSize - 1) / 2;
        for (int i = 0; i < outFrames; i++) {
            int center = i * lfrWindowShift;
            int leftPad = Math.max(0, leftCtx - center);
            int first = Math.max(0, center - leftCtx);
            int maxOff = nFrames - 1 - first;
            int dst = i * outDim;
            for (int j = 0; j < lfrWindowSize; j++) {
                int idx = 0;
                if (j >= leftPad) {
                    int off = j - leftPad;
                    idx = off > maxOff ? nFrames - 1 : first + off;
                }
                System.arraycopy(feats, idx * dim, out, dst, dim);
                dst += dim;
            }
        }
        return out;
    }

    // ==================== CMVN ====================

    private void applyCmvn(float[] v) {
        int dim = negMean.length;
        int numFrames = v.length / dim;
        for (int f = 0; f < numFrames; f++) {
            int base = f * dim;
            for (int i = 0; i < dim; i++) {
                v[base + i] = (v[base + i] + negMean[i]) * invStddev[i];
            }
        }
    }

    // ==================== CTC greedy 解码 ====================

    private List<Integer> ctcGreedy(float[][] logits) {
        List<Integer> tokens = new ArrayList<>();
        int prev = -1;
        int T = logits.length;
        int V = logits[0].length;
        for (int t = 0; t < T; t++) {
            int best = 0;
            float maxv = logits[t][0];
            for (int v = 1; v < V; v++) {
                if (logits[t][v] > maxv) {
                    maxv = logits[t][v];
                    best = v;
                }
            }
            if (best != blankId && best != prev) {
                tokens.add(best);
            }
            prev = best;
        }
        return tokens;
    }

    // ==================== token 拼接 ====================

    private String joinTokens(List<Integer> tokens) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            if (i < 4) continue; // 跳过 lang/emotion/event 标记
            String sym = tokenMap.get(tokens.get(i));
            if (sym == null) continue;
            if (sym.startsWith("\u2581")) {
                sb.append(' ').append(sym.substring(1));
            } else {
                sb.append(sym);
            }
        }
        return sb.toString().trim();
    }

    // ==================== FFT（迭代 radix-2） ====================

    private void fft(float[] input, float[] re, float[] im) {
        int n = NFFT;
        System.arraycopy(input, 0, re, 0, n);
        java.util.Arrays.fill(im, 0, n, 0f);

        // bit reversal
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                float tr = re[i];
                re[i] = re[j];
                re[j] = tr;
                float ti = im[i];
                im[i] = im[j];
                im[j] = ti;
            }
        }

        for (int len = 2; len <= n; len <<= 1) {
            float ang = (float) (-2 * Math.PI / len);
            float wRe = (float) Math.cos(ang);
            float wIm = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float curRe = 1f, curIm = 0f;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k;
                    int b = i + k + len / 2;
                    float tRe = re[b] * curRe - im[b] * curIm;
                    float tIm = re[b] * curIm + im[b] * curRe;
                    re[b] = re[a] - tRe;
                    im[b] = im[a] - tIm;
                    re[a] += tRe;
                    im[a] += tIm;
                    float nRe = curRe * wRe - curIm * wIm;
                    curIm = curRe * wIm + curIm * wRe;
                    curRe = nRe;
                }
            }
        }
    }

    // ==================== mel 滤波器组 ====================

    private float[][] buildMelFilters() {
        float highFreq = SAMPLE_RATE / 2.0f; // high_freq=0 -> Nyquist
        float melLow = melScale(LOW_FREQ);
        float melHigh = melScale(highFreq);
        int nfftHalf = NFFT / 2 + 1;
        float[][] filters = new float[NUM_BINS][nfftHalf];

        float[] hzPoints = new float[NUM_BINS + 2];
        for (int m = 0; m < NUM_BINS + 2; m++) {
            hzPoints[m] = invMelScale(melLow + (melHigh - melLow) * m / (NUM_BINS + 1));
        }

        for (int m = 0; m < NUM_BINS; m++) {
            float fLeft = hzPoints[m];
            float fCenter = hzPoints[m + 1];
            float fRight = hzPoints[m + 2];
            for (int i = 0; i < nfftHalf; i++) {
                float f = i * (SAMPLE_RATE / 2.0f) / (NFFT / 2);
                if (f >= fLeft && f < fCenter) {
                    filters[m][i] = (f - fLeft) / (fCenter - fLeft);
                } else if (f >= fCenter && f <= fRight) {
                    filters[m][i] = (fRight - f) / (fRight - fCenter);
                }
            }
        }
        return filters;
    }

    private static float melScale(float hz) {
        return (float) (1127.0 * Math.log(1 + hz / 700.0));
    }

    private static float invMelScale(float mel) {
        return (float) (700.0 * (Math.exp(mel / 1127.0) - 1));
    }

    // ==================== 模型/tokens 加载 ====================

    /**
     * 从 assets 释放模型到应用目录（filesDir）：仅首次复制，之后直接用缓存文件。
     * 用 ".ok" 标记文件保证只复制一次；模型文件被系统清理时自动重新释放。
     * 避免每次启动/识别都打开 assets 重复处理 228MB 大文件。
     */
    private File extractAssetIfNeeded(Context ctx, String assetPath, String fileName) throws Exception {
        File out = new File(ctx.getFilesDir(), "asr_" + fileName);
        File marker = new File(ctx.getFilesDir(), "asr_" + fileName + ".ok");
        // 已释放（标记存在且文件有效）：直接返回缓存文件，不再打开 assets
        if (marker.exists() && out.exists() && out.length() > 1024) {
            return out;
        }
        AssetManager am = ctx.getAssets();
        try (InputStream is = am.open(assetPath);
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) != -1) {
                fos.write(buf, 0, n);
            }
        }
        // 复制完成写标记，保证只释放一次
        try (FileOutputStream mf = new FileOutputStream(marker)) {
            mf.write(0x01);
        }
        AILogger.i(TAG, "模型释放到应用目录: " + out.getName() + " (" + out.length() / 1048576 + "MB)");
        return out;
    }

    // ==================== VAD（Silero VAD v5） ====================

    /**
     * 懒加载 VAD 会话。短音频不需要它，因此不放进构造函数，
     * 避免每次加载识别模型都多付一次 VAD 模型加载与常驻内存。
     */
    private synchronized OrtSession vadSession() throws Exception {
        if (vadSession == null) {
            File f = extractAssetIfNeeded(appContext, VAD_ASSET, "silero_vad.onnx");
            OrtSession.SessionOptions o = new OrtSession.SessionOptions();
            o.setIntraOpNumThreads(1);                 // VAD 很小，单线程足够
            o.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
            vadSession = env.createSession(f.getAbsolutePath(), o);
            AILogger.i(TAG, "VAD 加载完成: " + f.length() / 1024 + "KB");
        }
        return vadSession;
    }

    /**
     * 对 16k PCM 做静音检测，返回语音段 {@code [startSample, lengthSamples]} 列表（按时间序、互不重叠）。
     *
     * <p>规则对齐 sherpa-onnx 的 silero-vad 用法：512 采样/窗 + 64 采样上下文，
     * 状态张量 h/c 逐窗传递；连续静音超过 100ms 才断句，语音短于 250ms 丢弃，
     * 段首尾各留 200ms 余量防切字。</p>
     */
    private List<int[]> vadSegments(float[] pcm, int nSamples) throws Exception {
        if (nSamples < VAD_SKIP_BELOW_SAMPLES) {
            // 太短，不值得为它加载 VAD：整段当一段话（与未接 VAD 时行为一致）
            List<int[]> one = new ArrayList<>();
            one.add(new int[]{0, nSamples});
            return one;
        }

        OrtSession vad;
        try {
            vad = vadSession();
        } catch (Throwable t) {
            // VAD 加载失败不能让识别整体失败：退化为整段识别
            AILogger.w(TAG, "VAD 不可用，退化为整段识别: " + t.getMessage());
            List<int[]> one = new ArrayList<>();
            one.add(new int[]{0, nSamples});
            return one;
        }

        long[] stateShape = {2, 1, 64};
        java.nio.FloatBuffer hBuf = java.nio.FloatBuffer.wrap(new float[2 * 64]);
        java.nio.FloatBuffer cBuf = java.nio.FloatBuffer.wrap(new float[2 * 64]);

        List<int[]> segs = new ArrayList<>();
        boolean inSpeech = false;
        int speechStart = 0;
        int silenceRun = 0;
        int segMaxEnd = 0;   // 本段因超长被强制切分时的终点

        final int stride = VAD_WINDOW;
        for (int pos = 0; pos < nSamples; pos += stride) {
            int take = Math.min(stride, nSamples - pos);
            if (take < VAD_WINDOW) {
                // 尾部不足一窗：丢弃不足 100ms 的残料，其余静音补零，与 sherpa 的 tail 处理一致
                if (take < SAMPLE_RATE / 10) break;
            }

            float[] chunk = new float[VAD_CONTEXT + VAD_WINDOW];
            // 滚动上下文：窗口 i 的输入是 [i*WINDOW-CONTEXT, i*WINDOW+WINDOW)。
            // v5 要求每窗前置**上一窗的真实尾 64 采样**（不是零填充），起始窗左补零即可。
            // 这里此前把 context 段整段留 0，等于丢掉了滚动上下文 —— 概率会失真。
            int ctxStart = pos - VAD_CONTEXT;
            for (int i = 0; i < VAD_CONTEXT; i++) {
                int idx = ctxStart + i;
                chunk[i] = (idx >= 0 && idx < nSamples) ? pcm[idx] : 0f;
            }
            int copy = Math.min(VAD_WINDOW, nSamples - pos);
            if (copy > 0) {
                System.arraycopy(pcm, pos, chunk, VAD_CONTEXT, copy);
            }

            float prob = vadProb(vad, chunk, stateShape, hBuf, cBuf);

            if (prob >= VAD_THRESHOLD) {
                if (!inSpeech) {
                    inSpeech = true;
                    speechStart = pos;
                    segMaxEnd = pos + VAD_MAX_SEGMENT_SAMPLES;
                }
                silenceRun = 0;
            } else if (inSpeech) {
                silenceRun += take;
                if (silenceRun >= VAD_MIN_SILENCE_SAMPLES) {
                    addSegment(segs, speechStart, pos + take, nSamples);
                    inSpeech = false;
                    silenceRun = 0;
                }
            }

            // 单段过长：强制在此断开，避免超出模型训练时长分布
            if (inSpeech && pos + take >= segMaxEnd) {
                addSegment(segs, speechStart, pos + take, nSamples);
                speechStart = pos + take;
                segMaxEnd = speechStart + VAD_MAX_SEGMENT_SAMPLES;
                silenceRun = 0;
            }
        }

        if (inSpeech) {
            addSegment(segs, speechStart, nSamples, nSamples);
        }

        // 丢弃过短段（咳嗽、键盘声等），并合并相交/相邻段
        List<int[]> kept = new ArrayList<>();
        for (int[] s : segs) {
            if (s[1] >= VAD_MIN_SPEECH_SAMPLES) kept.add(s);
        }
        if (kept.isEmpty() && !segs.isEmpty()) {
            // 有语音但都很短：保留最长的一段，避免"有说话却识别为空"
            int[] best = segs.get(0);
            for (int[] s : segs) {
                if (s[1] > best[1]) best = s;
            }
            kept.add(best);
        }
        kept = mergeOverlapping(kept);
        return kept;
    }

    /**
     * 合并相交或紧邻的语音段。
     *
     * <p>正常情况下 VAD 产出的段互不重叠，但 200ms 余量扩边可能让相邻段相接甚至相交；
     * 若不去重，同一段音频会被识别两次，拼接后文本重复。这里按起点排序后合并。</p>
     */
    private static List<int[]> mergeOverlapping(List<int[]> segs) {
        if (segs.size() <= 1) return segs;
        List<int[]> sorted = new ArrayList<>(segs);
        sorted.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> out = new ArrayList<>();
        int[] cur = new int[]{sorted.get(0)[0], sorted.get(0)[1]};
        for (int i = 1; i < sorted.size(); i++) {
            int[] s = sorted.get(i);
            int curEnd = cur[0] + cur[1];
            if (s[0] <= curEnd) {
                int newEnd = Math.max(curEnd, s[0] + s[1]);
                cur[1] = newEnd - cur[0];
            } else {
                out.add(cur);
                cur = new int[]{s[0], s[1]};
            }
        }
        out.add(cur);
        return out;
    }

    /** 把 [start,end) 按 200ms 余量扩边后加入结果集（限幅到音频范围） */
    private static void addSegment(List<int[]> out, int start, int end, int nSamples) {
        int s = Math.max(0, start - VAD_PAD_SAMPLES);
        int e = Math.min(nSamples, end + VAD_PAD_SAMPLES);
        if (e <= s) return;
        out.add(new int[]{s, e - s});
    }

    /**
     * 单窗 VAD 推理：输入 [1, 512+64]，返回语音概率。
     * 状态张量 h/c 通过缓冲区就地更新（逐窗传递，等价于流式调用）。
     */
    private float vadProb(OrtSession vad, float[] chunk, long[] stateShape,
                          java.nio.FloatBuffer hBuf, java.nio.FloatBuffer cBuf) throws Exception {
        long[] inputShape = {1, chunk.length};
        long[] srShape = {};
        // 显式 rewind：不依赖 OnnxTensor.createTensor 是"读当前位置"还是"从 0 读"，
        // 两种语义下都从头读满。
        hBuf.rewind();
        cBuf.rewind();
        try (OnnxTensor in = OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(chunk), inputShape);
             OnnxTensor sr = OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(new long[]{SAMPLE_RATE}), srShape);
             OnnxTensor h = OnnxTensor.createTensor(env, hBuf, stateShape);
             OnnxTensor c = OnnxTensor.createTensor(env, cBuf, stateShape)) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input", in);
            inputs.put("sr", sr);
            inputs.put("h", h);
            inputs.put("c", c);
            try (OrtSession.Result r = vad.run(inputs)) {
                OnnxTensor out = (OnnxTensor) r.get(0);
                float[][] p = (float[][]) out.getValue();
                // hn/cn 的 Java 视图是 [2,1,64]（float[][][]），**不能**直接当 float[] 取
                // （真机首测即 ClassCastException: float[][][] cannot be cast to float[]）。
                // 这里按实际形状展平回 128 个 float 再写回缓冲区供下一窗使用。
                OnnxTensor hn = (OnnxTensor) r.get(1);
                OnnxTensor cn = (OnnxTensor) r.get(2);
                hBuf.rewind();
                hBuf.put(flattenToFloatArray(hn.getValue(), 2 * 64, "hn"));
                hBuf.rewind();
                cBuf.rewind();
                cBuf.put(flattenToFloatArray(cn.getValue(), 2 * 64, "cn"));
                cBuf.rewind();
                return (p != null && p.length > 0 && p[0] != null && p[0].length > 0) ? p[0][0] : 0f;
            }
        }
    }

    /**
     * 把 ONNX 输出的嵌套数组展平为长度 {@code expect} 的 float[]。
     *
     * <p>同一张量在不同 ONNX Runtime 版本/形状下可能返回 float[]、float[][] 或
     * float[][][]，因此按类型逐层展开，而不是硬转某一种形状。</p>
     */
    private static float[] flattenToFloatArray(Object v, int expect, String what) {
        float[] out = new float[expect];
        int[] idx = {0};
        fillFloats(v, out, idx);
        if (idx[0] != expect) {
            // 形状不符时不能让状态静默错位：宁可抛错暴露问题
            throw new IllegalStateException(
                    "VAD 状态张量 " + what + " 展平后长度=" + idx[0] + "，期望 " + expect);
        }
        return out;
    }

    private static void fillFloats(Object v, float[] out, int[] idx) {
        if (v instanceof float[]) {
            for (float f : (float[]) v) {
                if (idx[0] < out.length) out[idx[0]++] = f;
            }
        } else if (v instanceof Object[]) {
            for (Object o : (Object[]) v) {
                fillFloats(o, out, idx);
            }
        } else if (v instanceof Number) {
            if (idx[0] < out.length) out[idx[0]++] = ((Number) v).floatValue();
        } else {
            throw new IllegalStateException("VAD 状态张量元素类型未知: "
                    + (v == null ? "null" : v.getClass().getName()));
        }
    }

    private void loadTokens(File file) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new java.io.FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                int sp = line.lastIndexOf(' ');
                if (sp <= 0 || sp == line.length() - 1) continue;
                String token = line.substring(0, sp);
                String idStr = line.substring(sp + 1).trim();
                int id;
                try {
                    id = Integer.parseInt(idStr);
                } catch (NumberFormatException e) {
                    continue;
                }
                tokenMap.put(id, token);
            }
        }
    }

    // ==================== 工具 ====================

    private static int parseInt(Map<String, String> md, String key, int def) {
        try {
            String v = md.get(key);
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static float[] parseFloatVec(Map<String, String> md, String key) {
        String v = md.get(key);
        if (v == null || v.isEmpty()) return new float[0];
        String[] parts = v.split(",");
        float[] out = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Float.parseFloat(parts[i].trim());
        }
        return out;
    }
}
