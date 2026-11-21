package com.oilquiz.app.ai.onnx;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * 图像标签识别（ONNX Runtime + MobileNetV2 / ImageNet-1000）。
 *
 * <p>替代被删掉的 ML Kit image-labeling。模型**不内置**：首次调用 {@link #ensureReady} 时从
 * {@link OnnxModelStore} 复制到 {@code filesDir/onnx_models/}（模型内置在 assets）；标签表用仓库里已有的
 * {@code assets/models/imagenet_labels.txt}。</p>
 *
 * <p>用法（全部在工作线程）：</p>
 * <pre>
 *   OnnxImageClassifier.ensureReady(ctx, new OnnxModelStore.Callback() {
 *       onReady  -> List&lt;Label&gt; labels = OnnxImageClassifier.classify(ctx, bitmap, 5);
 *       onError  -> 提示用户网络/磁盘问题
 *   });
 * </pre>
 */
public final class OnnxImageClassifier {

    private static final String TAG = "OnnxImageClassifier";

    /** ImageNet 分类模型（ONNX Model Zoo，Apache-2.0；内置在 assets/models/，首次使用复制到 filesDir） */
    public static final OnnxModelStore.Spec SPEC = new OnnxModelStore.Spec(
            "imagenet_classifier",
            "mobilenetv2-7.onnx",
            "models/mobilenetv2-7.onnx",
            14_246_826L,
            "c1c513582d56afceff8516c73804e484c81c6a830712ab6d682253f4a3cd042f");

    private static final int INPUT_SIZE = 224;
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] STD = {0.229f, 0.224f, 0.225f};
    private static final String LABELS_ASSET = "models/imagenet_labels.txt";

    private static volatile OrtEnvironment env;
    private static volatile OrtSession session;
    private static volatile String inputName;
    private static volatile String[] labels;

    private OnnxImageClassifier() {
    }

    /** 一条识别结果 */
    public static final class Label {
        public final int classIndex;
        public final String name;
        public final float score;

        Label(int classIndex, String name, float score) {
            this.classIndex = classIndex;
            this.name = name;
            this.score = score;
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "%s(%.1f%%)", name, score * 100f);
        }
    }

    /** 确保模型就绪（首次会下载）；回调在工作线程 */
    public static void ensureReady(Context context, OnnxModelStore.Callback callback) {
        OnnxModelStore.ensure(context, SPEC, callback);
    }

    /** 模型是否已下载 */
    public static boolean isReady(Context context) {
        return OnnxModelStore.isReady(context, SPEC);
    }

    /**
     * 识别图片标签（同步，阻塞；只能在后台线程调用）。
     *
     * @param topK 返回前 K 个标签
     * @return 按置信度倒序；模型未就绪/异常时返回空列表
     */
    public static synchronized List<Label> classify(Context context, Bitmap bitmap, int topK) {
        List<Label> empty = Collections.emptyList();
        if (bitmap == null || bitmap.isRecycled() || topK <= 0) {
            return empty;
        }
        try {
            ensureSession(context);
            float[] input = preprocess(bitmap);

            try (OnnxTensor tensor = OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(input), new long[]{1, 3, INPUT_SIZE, INPUT_SIZE});
                 OrtSession.Result result = session.run(Collections.singletonMap(inputName, tensor))) {
                float[] scores = flatten(result.get(0).getValue());
                if (scores.length == 0) {
                    return empty;
                }
                // MobileNetV2-7 输出的是 logits（实测随机输入范围 -5.6~5.8、总和 2.6，不是概率），
                // 必须先 softmax 再取 topK，否则展示出来的"百分比"是错的。
                scores = softmax(scores);
                return topK(scores, Math.min(topK, scores.length));
            }
        } catch (Throwable t) {
            Log.e(TAG, "图像标签识别失败: " + t.getMessage(), t);
            return empty;
        }
    }

    /** 释放 ORT session（例如清理模型后） */
    public static synchronized void release() {
        try {
            if (session != null) {
                session.close();
            }
        } catch (Exception ignored) {
        }
        session = null;
        inputName = null;
    }

    private static void ensureSession(Context context) throws Exception {
        if (session != null) {
            return;
        }
        File model = OnnxModelStore.file(context, SPEC);
        if (!model.isFile()) {
            throw new IllegalStateException("图像标签模型尚未下载，请先调用 ensureReady()");
        }
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(2);
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        session = env.createSession(model.getAbsolutePath(), opts);
        inputName = session.getInputNames().iterator().next();
        labels = loadLabels(context);
        Log.i(TAG, "图像标签模型加载完成: input=" + inputName + ", labels=" + (labels == null ? 0 : labels.length));
    }

    /** 中心裁剪 + 缩放到 224，按 ImageNet mean/std 归一化成 NCHW */
    private static float[] preprocess(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int side = Math.min(w, h);
        int x = (w - side) / 2;
        int y = (h - side) / 2;

        Bitmap crop = Bitmap.createBitmap(src, x, y, side, side);
        Bitmap scaled = Bitmap.createScaledBitmap(crop, INPUT_SIZE, INPUT_SIZE, true);
        if (scaled != crop) {
            crop.recycle();
        }

        int[] pixels = new int[INPUT_SIZE * INPUT_SIZE];
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);
        scaled.recycle();

        int plane = INPUT_SIZE * INPUT_SIZE;
        float[] data = new float[3 * plane];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            float r = ((p >> 16) & 0xFF) / 255f;
            float g = ((p >> 8) & 0xFF) / 255f;
            float b = (p & 0xFF) / 255f;
            data[i] = (r - MEAN[0]) / STD[0];
            data[plane + i] = (g - MEAN[1]) / STD[1];
            data[2 * plane + i] = (b - MEAN[2]) / STD[2];
        }
        return data;
    }

    /** ORT 输出适配：float[] / float[][] / float[][][][] 都拍平成 [C] */
    private static float[] flatten(Object value) {
        if (value instanceof float[]) {
            return (float[]) value;
        }
        if (value instanceof float[][]) {
            return ((float[][]) value)[0];
        }
        if (value instanceof float[][][]) {
            return ((float[][][]) value)[0][0];
        }
        if (value instanceof float[][][][]) {
            return ((float[][][][]) value)[0][0][0];
        }
        if (value instanceof OnnxTensor) {
            try {
                return flatten(((OnnxTensor) value).getValue());
            } catch (Exception e) {
                return new float[0];
            }
        }
        return new float[0];
    }

    /** 数值稳定的 softmax：logits → 概率（总和 1） */
    private static float[] softmax(float[] logits) {
        float max = -Float.MAX_VALUE;
        for (float v : logits) {
            if (v > max) {
                max = v;
            }
        }
        float[] out = new float[logits.length];
        double sum = 0;
        for (int i = 0; i < logits.length; i++) {
            out[i] = (float) Math.exp(logits[i] - max);
            sum += out[i];
        }
        if (sum > 0) {
            for (int i = 0; i < out.length; i++) {
                out[i] = (float) (out[i] / sum);
            }
        }
        return out;
    }

    private static List<Label> topK(float[] scores, int k) {
        int[] idx = new int[k];
        for (int i = 0; i < k; i++) {
            int best = -1;
            float bestScore = -Float.MAX_VALUE;
            for (int j = 0; j < scores.length; j++) {
                boolean used = false;
                for (int t = 0; t < i; t++) {
                    if (idx[t] == j) {
                        used = true;
                        break;
                    }
                }
                if (!used && scores[j] > bestScore) {
                    bestScore = scores[j];
                    best = j;
                }
            }
            if (best < 0) {
                break;
            }
            idx[i] = best;
        }

        List<Label> out = new ArrayList<>(k);
        for (int i = 0; i < k; i++) {
            int classIndex = idx[i];
            if (classIndex < 0) {
                continue;
            }
            out.add(new Label(classIndex, labelName(classIndex), scores[classIndex]));
        }
        return out;
    }

    private static String labelName(int index) {
        String[] table = labels;
        if (table != null && index >= 0 && index < table.length) {
            String name = table[index];
            if (name != null && !name.trim().isEmpty()) {
                return name.trim();
            }
        }
        return "class_" + index;
    }

    private static String[] loadLabels(Context context) {
        List<String> list = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(context.getAssets().open(LABELS_ASSET), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                list.add(line);
            }
        } catch (Exception e) {
            Log.w(TAG, "读取 ImageNet 标签表失败: " + e.getMessage());
        }
        return list.toArray(new String[0]);
    }
}
