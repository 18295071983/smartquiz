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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * 目标检测（ONNX Runtime，COCO 80 类）。
 *
 * <p>替代被删掉的 ML Kit object-detection。模型**内置**在 assets/models/，由 {@link #ensureReady}
 * 复制到 filesDir（默认用 ONNX Model Zoo 的 SSD-1200 规格）。
 * 标签表用仓库已有的 {@code assets/models/coco_labels.txt}。</p>
 *
 * <p><b>注意（选型）</b>：ONNX Model Zoo 的 {@code ssd-10.onnx} 是 ResNet34 骨干 + 1200×1200 输入
 * （80 MB，Apache-2.0），在手机上单张推理是秒级、内存也重；如果只是要「拍一张图识别里面的物体」，
 * 更合适的是小模型（如 Apache-2.0 的 NanoDet / MobileNet-SSD 的 ONNX 导出）。
 * 本类对两种常见输出都做了适配，换模型时只要输入形状能读到、输出是下面布局之一即可：</p>
 * <ul>
 *   <li>单输出 {@code [1,1,N,7]}：每行 {@code (batch, classId, score, x1,y1,x2,y2)}；</li>
 *   <li>三输出：{@code boxes [1,N,4]} + {@code scores [1,N,C]} + {@code labels [1,N]}（torchvision 风格）。</li>
 * </ul>
 * 坐标若是像素值会自动按输入尺寸归一化。
 */
public final class OnnxObjectDetector {

    private static final String TAG = "OnnxObjectDetector";

    /** 默认规格：ONNX Model Zoo SSD-12 int8（COCO 80 类 / 1200×1200 / Apache-2.0 / 20 MB；内置在 assets/models/） */
    public static final OnnxModelStore.Spec SPEC_DEFAULT = new OnnxModelStore.Spec(
            "coco_object_detector_int8",
            "ssd-12-int8.onnx",
            "models/ssd-12-int8.onnx",
            20_485_276L,
            "56d2c03a8c74c03f704509ccfcd91763991c6e1b92f63da730fb2b3d07565453");

    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] STD = {0.229f, 0.224f, 0.225f};
    private static final String LABELS_ASSET = "models/coco_labels.txt";

    private static volatile OrtEnvironment env;
    private static volatile OrtSession session;
    private static volatile String inputName;
    private static volatile int inputW = 300;
    private static volatile int inputH = 300;
    private static volatile String[] labels;
    private static volatile String cachedModelPath;

    private OnnxObjectDetector() {
    }

    /** 一个检测框 */
    public static final class Detection {
        public final int classIndex;
        public final String label;
        public final float score;
        /** 归一化坐标（0~1，左上/右下） */
        public final float x1, y1, x2, y2;

        Detection(int classIndex, String label, float score, float x1, float y1, float x2, float y2) {
            this.classIndex = classIndex;
            this.label = label;
            this.score = score;
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "%s(%.1f%%)", label, score * 100f);
        }
    }

    /** 确保模型就绪（首次会下载）；回调在工作线程 */
    public static void ensureReady(Context context, OnnxModelStore.Spec spec, OnnxModelStore.Callback callback) {
        OnnxModelStore.ensure(context, spec, callback);
    }

    public static boolean isReady(Context context, OnnxModelStore.Spec spec) {
        return OnnxModelStore.isReady(context, spec);
    }

    /**
     * 检测图片中的物体（同步，阻塞；只能在后台线程调用）。
     *
     * @param spec       模型规格（需已经 ensureReady 下载好）
     * @param minScore   置信度阈值（建议 0.35~0.5）
     * @param maxResults 最多返回多少个框
     * @return 按置信度倒序；模型未就绪/异常时返回空列表
     */
    public static synchronized List<Detection> detect(Context context, OnnxModelStore.Spec spec,
                                                      Bitmap bitmap, float minScore, int maxResults) {
        List<Detection> empty = Collections.emptyList();
        if (bitmap == null || bitmap.isRecycled()) {
            return empty;
        }
        try {
            ensureSession(context, spec);
            float[] input = preprocess(bitmap, inputW, inputH);

            try (OnnxTensor tensor = OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(input), new long[]{1, 3, inputH, inputW});
                 OrtSession.Result result = session.run(Collections.singletonMap(inputName, tensor))) {
                List<Detection> all = parseResult(result, minScore);
                all.sort(Comparator.comparingDouble((Detection d) -> d.score).reversed());
                if (all.size() > maxResults) {
                    return new ArrayList<>(all.subList(0, maxResults));
                }
                return all;
            }
        } catch (Throwable t) {
            Log.e(TAG, "目标检测失败: " + t.getMessage(), t);
            return empty;
        }
    }

    /** 释放 ORT session */
    public static synchronized void release() {
        try {
            if (session != null) {
                session.close();
            }
        } catch (Exception ignored) {
        }
        session = null;
        inputName = null;
        cachedModelPath = null;
    }

    private static void ensureSession(Context context, OnnxModelStore.Spec spec) throws Exception {
        File model = OnnxModelStore.file(context, spec);
        if (!model.isFile()) {
            throw new IllegalStateException("目标检测模型尚未下载，请先调用 ensureReady()");
        }
        // 换模型时重建 session
        if (session != null && model.getAbsolutePath().equals(cachedModelPath)) {
            return;
        }
        release();
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(2);
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        session = env.createSession(model.getAbsolutePath(), opts);
        inputName = session.getInputNames().iterator().next();
        resolveInputSize();
        labels = loadLabels(context);
        cachedModelPath = model.getAbsolutePath();
        Log.i(TAG, "目标检测模型加载完成: input=" + inputName + " " + inputW + "x" + inputH
                + ", outputs=" + session.getOutputNames().size());
    }

    /** 从模型元信息里读输入的高宽（读不到就按 300×300 兜底） */
    private static void resolveInputSize() {
        inputW = 300;
        inputH = 300;
        try {
            ai.onnxruntime.NodeInfo info = session.getInputInfo().get(inputName);
            if (info != null && info.getInfo() instanceof ai.onnxruntime.TensorInfo) {
                long[] shape = ((ai.onnxruntime.TensorInfo) info.getInfo()).getShape();
                if (shape != null && shape.length == 4) {
                    if (shape[2] > 0) inputH = (int) shape[2];
                    if (shape[3] > 0) inputW = (int) shape[3];
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "读取输入尺寸失败，按 300x300 处理: " + t.getMessage());
        }
    }

    /** 缩放到模型输入尺寸，[0,1] 归一化 + ImageNet mean/std，NCHW */
    private static float[] preprocess(Bitmap src, int width, int height) {
        Bitmap scaled = Bitmap.createScaledBitmap(src, width, height, true);
        int[] pixels = new int[width * height];
        scaled.getPixels(pixels, 0, width, 0, 0, width, height);
        if (scaled != src) {
            scaled.recycle();
        }

        int plane = width * height;
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

    /** 适配两种常见输出布局（用输出的类型/形状判别，不依赖输出顺序） */
    private static List<Detection> parseResult(OrtSession.Result result, float minScore) throws Exception {
        List<Detection> detections = new ArrayList<>();
        int count = (int) result.size();

        if (count == 1) {
            // [1,1,N,7]：(batch, classId, score, x1,y1,x2,y2)
            Object value = result.get(0).getValue();
            float[][][][] out = asFloat4D(value);
            if (out == null) {
                Log.w(TAG, "单输出布局无法解析（期望 [1,1,N,7]）");
                return detections;
            }
            for (float[][] rowBlock : out[0]) {
                for (float[] row : rowBlock) {
                    if (row.length < 7) {
                        continue;
                    }
                    float score = row[2];
                    if (score < minScore) {
                        continue;
                    }
                    int cls = (int) row[1];
                    detections.add(new Detection(cls, labelName(cls), score,
                            clamp01(row[3]), clamp01(row[4]), clamp01(row[5]), clamp01(row[6])));
                }
            }
            return detections;
        }

        // 多输出（ONNX Model Zoo SSD / torchvision 风格）：
        //   bboxes [1,N,4]（float，已归一化到 0~1）、labels [1,N]（int64，COCO 0~79）、scores [1,N]（float）
        float[][][] boxes = null;
        float[] scores = null;
        long[] labels = null;
        for (int i = 0; i < count; i++) {
            Object v = result.get(i).getValue();
            if (boxes == null && v instanceof float[][][]) {
                float[][][] b = (float[][][]) v;
                if (b.length > 0 && b[0].length > 0 && b[0][0].length == 4) {
                    boxes = b;
                }
            } else if (labels == null && v instanceof long[][]) {
                labels = ((long[][]) v)[0];
            } else if (labels == null && v instanceof int[][]) {
                int[] tmp = ((int[][]) v)[0];
                labels = new long[tmp.length];
                for (int k = 0; k < tmp.length; k++) {
                    labels[k] = tmp[k];
                }
            } else if (scores == null && v instanceof float[][]) {
                scores = ((float[][]) v)[0];
            }
        }

        if (boxes == null || scores == null) {
            Log.w(TAG, "多输出布局无法解析（期望 bboxes/scores/labels）");
            return detections;
        }

        int n = boxes[0].length;
        for (int i = 0; i < n && i < scores.length; i++) {
            float score = scores[i];
            if (score < minScore) {
                continue;
            }
            float[] box = boxes[0][i];
            float x1 = box[0], y1 = box[1], x2 = box[2], y2 = box[3];
            // 像素坐标 → 归一化（本模型的 bboxes 已经是 0~1，这里只是兼容别的模型）
            if (Math.max(Math.max(x1, x2), Math.max(y1, y2)) > 1.5f) {
                x1 /= inputW;
                x2 /= inputW;
                y1 /= inputH;
                y2 /= inputH;
            }
            int cls = (labels != null && i < labels.length) ? (int) labels[i] : -1;
            detections.add(new Detection(cls, labelName(cls), score,
                    clamp01(x1), clamp01(y1), clamp01(x2), clamp01(y2)));
        }
        return detections;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float[][][][] asFloat4D(Object v) {
        if (v instanceof float[][][][]) {
            return (float[][][][]) v;
        }
        if (v instanceof float[][][]) {
            return new float[][][][]{(float[][][]) v};
        }
        return null;
    }

    private static float[][][] asFloat3D(Object v) {
        if (v instanceof float[][][]) {
            return (float[][][]) v;
        }
        if (v instanceof float[][]) {
            return new float[][][]{(float[][]) v};
        }
        return null;
    }

    private static long[][] asLong2D(Object v) {
        if (v instanceof long[][]) {
            return (long[][]) v;
        }
        if (v instanceof long[]) {
            return new long[][]{(long[]) v};
        }
        if (v instanceof float[][]) {
            float[][] f = (float[][]) v;
            long[][] out = new long[f.length][];
            for (int i = 0; i < f.length; i++) {
                out[i] = new long[f[i].length];
                for (int j = 0; j < f[i].length; j++) {
                    out[i][j] = (long) f[i][j];
                }
            }
            return out;
        }
        if (v instanceof int[][]) {
            int[][] it = (int[][]) v;
            long[][] out = new long[it.length][];
            for (int i = 0; i < it.length; i++) {
                out[i] = new long[it[i].length];
                for (int j = 0; j < it[i].length; j++) {
                    out[i][j] = it[i][j];
                }
            }
            return out;
        }
        return null;
    }

    private static String labelName(int index) {
        String[] table = labels;
        if (table != null && index >= 0 && index < table.length) {
            String name = table[index];
            if (name != null && !name.trim().isEmpty()) {
                return name.trim();
            }
        }
        return index < 0 ? "object" : "class_" + index;
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
            Log.w(TAG, "读取 COCO 标签表失败: " + e.getMessage());
        }
        return list.toArray(new String[0]);
    }
}
