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

    // 音频参数
    public static final int SAMPLE_RATE = 16000;
    private static final int FRAME_LENGTH = 400;   // 25ms @16k
    private static final int FRAME_SHIFT = 160;    // 10ms @16k
    private static final int NFFT = 512;
    private static final int NUM_BINS = 80;
    private static final float PREEMPH = 0.97f;
    private static final float LOW_FREQ = 20.0f;
    private static final float LOG_FLOOR = 1.0e-10f;

    private static volatile SenseVoiceAsr INSTANCE;

    private final OrtEnvironment env;
    private final OrtSession session;

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

    /** 是否已初始化成功（模型已加载） */
    public static boolean isReady() {
        return INSTANCE != null;
    }

    /** 主动释放（可选） */
    public void release() {
        try {
            if (session != null) session.close();
        } catch (Exception ignored) {
        }
    }

    // ==================== 对外识别入口 ====================

    /**
     * 识别 16kHz 单声道 float PCM（范围 [-1,1]）
     *
     * @return 识别文本；无有效内容返回空串
     */
    public String recognize(float[] pcm, int nSamples) throws Exception {
        if (pcm == null || nSamples <= 0) {
            return "";
        }
        long t0 = System.currentTimeMillis();

        // 1. fbank
        float[] fbank = computeFbank(pcm, nSamples);
        int nFrames = fbank.length / NUM_BINS;
        if (nFrames <= 0) {
            return "";
        }

        // 2. LFR
        float[] lfr = applyLfr(fbank, nFrames, NUM_BINS);
        int lfrFrames = lfr.length / (NUM_BINS * lfrWindowSize);

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
                long ms = System.currentTimeMillis() - t0;
                AILogger.d(TAG, "识别耗时: " + ms + "ms, frames=" + nFrames + " audio=" + nSamples / (float) SAMPLE_RATE + "s");

                // 5. CTC greedy 解码
                List<Integer> tokens = ctcGreedy(logits[0]);

                // 6. token 拼接（跳过前 4 个语言/情感/事件标记）
                return joinTokens(tokens);
            }
        }
    }

    // ==================== fbank（kaldi 风格） ====================

    private float[] computeFbank(float[] x, int n) {
        // normalize_samples=false -> 样本 x32768（kaldi 范围）
        float scale = normalizeSamples ? 1.0f : 32768.0f;

        // remove dc offset + 预加重
        float mean = 0;
        for (int i = 0; i < n; i++) mean += x[i];
        mean /= n;
        float[] w = new float[n];
        for (int i = 0; i < n; i++) {
            float v = (x[i] - mean) * scale;
            w[i] = (i == 0) ? v : v - PREEMPH * ((x[i - 1] - mean) * scale);
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

    /** 从 assets 提取文件到 filesDir（已存在且大小一致则跳过，避免每次启动复制 228MB） */
    private File extractAssetIfNeeded(Context ctx, String assetPath, String fileName) throws Exception {
        File out = new File(ctx.getFilesDir(), "asr_" + fileName);
        AssetManager am = ctx.getAssets();
        long assetLen = 0;
        try (InputStream is = am.open(assetPath)) {
            assetLen = is.available();
        }
        if (out.exists() && out.length() == assetLen) {
            return out;
        }
        try (InputStream is = am.open(assetPath);
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                fos.write(buf, 0, n);
            }
        }
        return out;
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
