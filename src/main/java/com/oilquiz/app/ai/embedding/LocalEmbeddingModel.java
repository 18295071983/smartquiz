package com.oilquiz.app.ai.embedding;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 本地向量模型（离线 embedding）：ONNX Runtime 跑 bge-base-zh-v1.5 的 int8 量化版。
 *
 * <p>为什么走 ONNX 而不是 llama.cpp：ONNX Runtime 1.18 本来就在包里（RapidOCR / SenseVoice 在用），
 * 加这个只多一个 ~98MB 模型文件、不加运行时；而 {@code LlamaHelper} 只有 {@code nEmbd} 元数据，
 * 没有 embedding 输出通道，走它得改 native 层。</p>
 *
 * <p>生命周期刻意做成"文件在才启用"：</p>
 * <ul>
 *   <li>模型没下载 → {@link #isAvailable()} false，调用方直接走在线或纯关键词，一次都不尝试加载；</li>
 *   <li>文件在但加载失败（版本不匹配 / 文件残缺）→ 记一次失败，交给上层的熔断处理，不重复加载、不抛给主 agent；</li>
 *   <li>加载/推理都在同步线程上完成，单条 300 字在手机上约几十毫秒，比一次在线调用快。</li>
 * </ul>
 *
 * <p>模型文件放 {@code filesDir/ai_models/embedding/bge-base-zh-v1.5/}（model.onnx + vocab.txt），
 * 不打包进 APK：安装包体积不变，下载沿用项目已有的镜像套路（hf-mirror 优先，HF 官方兜底）。</p>
 */
public final class LocalEmbeddingModel {

    private static final String TAG = "LocalEmbedding";

    public static final String MODEL_ID = "bge-base-zh-v1.5";
    /** bge-base-zh 的向量维度；实际以模型输出为准，这里只用于校验 */
    public static final int EXPECTED_DIM = 768;
    private static final int MAX_SEQ_LEN = 320;
    private static final long MIN_MODEL_BYTES = 1024L;

    private static final String REL_DIR = "ai_models/embedding/" + MODEL_ID;
    private static final String FILE_MODEL = "model.onnx";
    private static final String FILE_VOCAB = "vocab.txt";

    private static final String MODEL_URL_MIRROR =
            "https://hf-mirror.com/Xenova/bge-base-zh-v1.5/resolve/main/onnx/model_quantized.onnx";
    private static final String MODEL_URL_OFFICIAL =
            "https://huggingface.co/Xenova/bge-base-zh-v1.5/resolve/main/onnx/model_quantized.onnx";
    private static final String VOCAB_URL_MIRROR =
            "https://hf-mirror.com/Xenova/bge-base-zh-v1.5/resolve/main/vocab.txt";
    private static final String VOCAB_URL_OFFICIAL =
            "https://huggingface.co/Xenova/bge-base-zh-v1.5/resolve/main/vocab.txt";

    public enum State { MISSING, DOWNLOADING, READY, FAILED }

    private final File dir;
    private final File modelFile;
    private final File vocabFile;

    private volatile boolean downloading;
    private volatile String lastError;
    private volatile boolean loadFailed;

    private OrtEnvironment env;
    private OrtSession session;
    private BertWordPieceTokenizer tokenizer;
    private volatile int dim = EXPECTED_DIM;

    public LocalEmbeddingModel(Context context) {
        this(new File(context.getApplicationContext().getFilesDir(), REL_DIR));
    }

    /** 测试用：指定目录，不碰真实文件布局 */
    public LocalEmbeddingModel(File dir) {
        this.dir = dir;
        this.modelFile = new File(dir, FILE_MODEL);
        this.vocabFile = new File(dir, FILE_VOCAB);
    }

    public static String relativeDir() {
        return REL_DIR;
    }

    public File dir() {
        return dir;
    }

    /** 文件齐了才为 true；"文件在"不等于"能跑"，加载失败由 {@link #embed} 暴露并被上层熔断 */
    public boolean isAvailable() {
        return modelFile.isFile() && modelFile.length() > MIN_MODEL_BYTES
                && vocabFile.isFile() && vocabFile.length() > 0;
    }

    public State state() {
        if (session != null) return State.READY;
        if (downloading) return State.DOWNLOADING;
        if (loadFailed) return State.FAILED;
        if (!isAvailable()) return State.MISSING;
        return State.READY; // 文件齐、尚未懒加载
    }

    public String lastError() {
        return lastError;
    }

    public int dimension() {
        return dim;
    }

    /**
     * 文本 → 归一化向量（L2）。失败抛 IOException，由调用方决定降级；
     * 这里绝不"吞掉异常返回零向量"——零向量会被余弦当无关，反而掩盖问题。
     */
    public float[] embed(String text) throws IOException {
        ensureLoaded();
        BertWordPieceTokenizer.Encoded enc = tokenizer.encode(text);
        int len = enc.ids.length;

        long[] ids = new long[len];
        long[] mask = new long[len];
        long[] types = new long[len];
        for (int i = 0; i < len; i++) {
            ids[i] = enc.ids[i];
            mask[i] = enc.mask[i];
            types[i] = enc.types[i];
        }

        Map<String, OnnxTensor> inputs = new HashMap<>();
        long[] shape = new long[]{1, len};
        try {
            inputs.put("input_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape));
            if (session.getInputNames().contains("attention_mask")) {
                inputs.put("attention_mask", OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape));
            }
            if (session.getInputNames().contains("token_type_ids")) {
                inputs.put("token_type_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(types), shape));
            }
            if (session.getInputNames().contains("position_ids")) {
                long[] pos = new long[len];
                for (int i = 0; i < len; i++) pos[i] = i;
                inputs.put("position_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(pos), shape));
            }

            synchronized (this) {
                try (OrtSession.Result result = session.run(inputs)) {
                    float[] vec = extractVector(result);
                    if (vec == null || vec.length == 0) {
                        throw new IOException("本地向量模型输出为空");
                    }
                    dim = vec.length;
                    return l2normalize(vec);
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("本地向量推理失败: " + t.getMessage(), t);
        } finally {
            for (OnnxTensor t : inputs.values()) {
                try {
                    t.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 取 CLS（bge 官方用 CLS pooling）；兼容 rank 2 / rank 1 的输出 */
    private static float[] extractVector(OrtSession.Result result) throws ai.onnxruntime.OrtException {
        Object value = null;
        for (Map.Entry<String, OnnxValue> e : result) {
            value = e.getValue().getValue();
            break;
        }
        if (value instanceof float[][][]) {
            float[][][] v = (float[][][]) value;
            if (v.length > 0 && v[0].length > 0) return copy(v[0][0]);
        } else if (value instanceof float[][]) {
            float[][] v = (float[][]) value;
            if (v.length > 0) return copy(v[0]);
        } else if (value instanceof float[]) {
            return copy((float[]) value);
        }
        return null;
    }

    private static float[] copy(float[] src) {
        float[] out = new float[src.length];
        System.arraycopy(src, 0, out, 0, src.length);
        return out;
    }

    private static float[] l2normalize(float[] v) {
        double sum = 0;
        for (float f : v) sum += (double) f * f;
        double norm = Math.sqrt(sum);
        if (norm <= 1e-8) return v;
        for (int i = 0; i < v.length; i++) v[i] = (float) (v[i] / norm);
        return v;
    }

    private void ensureLoaded() throws IOException {
        if (session != null) return;
        if (loadFailed) {
            throw new IOException("本地向量模型此前加载失败，不再重试: " + lastError);
        }
        if (!isAvailable()) {
            throw new IOException("本地向量模型未下载（" + dir.getAbsolutePath() + "）");
        }
        synchronized (this) {
            if (session != null) return;
            try {
                tokenizer = BertWordPieceTokenizer.fromVocabFile(vocabFile, MAX_SEQ_LEN);
                OrtEnvironment e = OrtEnvironment.getEnvironment();
                OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
                opts.setIntraOpNumThreads(1);
                opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                OrtSession s = e.createSession(modelFile.getAbsolutePath(), opts);
                env = e;
                session = s;
                Log.i(TAG, "本地向量模型已加载: " + modelFile.getAbsolutePath()
                        + "（输入 " + s.getInputNames() + "，输出 " + s.getOutputNames() + "）");
            } catch (Throwable t) {
                loadFailed = true;
                lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
                Log.w(TAG, "本地向量模型加载失败，本次运行内不再重试: " + lastError);
                throw new IOException("本地向量模型加载失败: " + lastError, t);
            }
        }
    }

    /** 后台下载两个文件（hf-mirror 优先，失败回退 HF 官方）；不阻塞调用线程 */
    public boolean downloadInBackground(Runnable onDone) {
        if (downloading) return false;
        if (isAvailable()) return false;
        downloading = true;
        Thread thread = new Thread(() -> {
            try {
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    throw new IOException("无法创建目录: " + dir.getAbsolutePath());
                }
                fetch(MODEL_URL_MIRROR, MODEL_URL_OFFICIAL, modelFile);
                fetch(VOCAB_URL_MIRROR, VOCAB_URL_OFFICIAL, vocabFile);
                loadFailed = false;
                lastError = null;
                Log.i(TAG, "本地向量模型下载完成: " + dir.getAbsolutePath());
                if (onDone != null) onDone.run();
            } catch (Throwable e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                Log.w(TAG, "本地向量模型下载失败: " + lastError);
            } finally {
                downloading = false;
            }
        }, "kb-embed-download");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    private void fetch(String mirrorUrl, String officialUrl, File target) throws IOException {
        IOException first = null;
        if (tryFetch(mirrorUrl, target)) return;
        first = new IOException("镜像下载失败: " + mirrorUrl);
        if (tryFetch(officialUrl, target)) return;
        throw first;
    }

    private boolean tryFetch(String url, File target) {
        File part = new File(target.getAbsolutePath() + ".part");
        OkHttpClient client;
        try {
            client = com.oilquiz.app.ai.model.ModelDownloadManager.getHttpClient();
        } catch (Throwable t) {
            return false;
        }
        Request req = new Request.Builder().url(url).build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) return false;
            try (InputStream in = resp.body().byteStream();
                 FileOutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            if (part.length() <= 0) return false;
            if (target.exists() && !target.delete()) return false;
            return part.renameTo(target);
        } catch (Throwable t) {
            Log.w(TAG, "下载失败(" + url + "): " + t.getMessage());
            return false;
        } finally {
            // 失败时清掉残片；成功路径已经 rename 掉，part 不存在
            if (part.exists()) part.delete();
        }
    }

    /** 释放 session（换模型 / 测试 / 退出时用） */
    public synchronized void reset() {
        if (session != null) {
            try {
                session.close();
            } catch (Throwable ignored) {
            }
        }
        session = null;
        tokenizer = null;
        loadFailed = false;
        lastError = null;
    }
}
