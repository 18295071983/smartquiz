package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.net.Uri;

import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.service.OnlineInferenceService;
import com.oilquiz.app.manager.OCRManager;
import com.oilquiz.app.toolkit.AppToolkit;
import com.oilquiz.app.util.AILogger;
import com.oilquiz.app.util.UriPathResolver;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OCR 文字识别工具（独立注册）：
 * - ocr_recognize      识别图片中的文字（在线视觉模型优先，失败回退本地 ML Kit）
 * - ocr_recognize_pdf  识别 PDF 中的文字（逐页 OCR）
 * - ocr_set_language   设置 OCR 识别语言
 * - ocr_get_language   获取当前 OCR 语言
 * - image_understand   图片理解/视觉问答：看图并回答关于图片的问题（在线视觉模型优先，本地 mmproj 回退）
 */
@Tool(
    value = "ocr_recognize",
    description = "图片理解工具：OCR文字识别 + 视觉问答（看图理解）。识别图片/PDF文字，或看图回答用户问题",
    category = "utility",
    aliases = {"ocr", "OCR", "文字识别", "图片识别", "识别图片文字", "看图", "图片理解"},
    actions = {
        @Action(name = "ocr_recognize", description = "识别图片中的文字",
            params = {
                @Param(name = "image_path", type = "string", description = "图片路径：绝对路径或 content:// 或 file:// URI", required = true),
                @Param(name = "language", type = "string", description = "识别语言: auto/chinese/english/japanese/korean", required = false)
            }),
        @Action(name = "ocr_recognize_pdf", description = "识别PDF中的文字",
            params = {
                @Param(name = "pdf_path", type = "string", description = "PDF路径：绝对路径或 content:// 或 file:// URI", required = true)
            }),
        @Action(name = "ocr_set_language", description = "设置OCR识别语言",
            params = {
                @Param(name = "language", type = "string", description = "语言: auto/chinese/english/japanese/korean", required = true)
            }),
        @Action(name = "ocr_get_language", description = "获取当前OCR语言",
            params = {}),
        @Action(name = "image_understand", description = "图片理解/视觉问答：看图并回答关于图片的问题",
            params = {
                @Param(name = "image_path", type = "string", description = "图片路径：绝对路径或 content:// 或 file:// URI", required = true),
                @Param(name = "question", type = "string", description = "关于图片的问题（如：图里有什么？描述一下这张图）", required = false)
            })
    }
)
public class OCRRecognizeTool implements AITool {

    private static final String TAG = "OCRRecognizeTool";
    private final Context context;
    private AppToolkit toolkit;

    public OCRRecognizeTool(Context context) {
        this.context = context.getApplicationContext();
        this.toolkit = AppToolkit.getInstance(this.context);
    }

    @Override
    public String getName() {
        return "ocr_recognize";
    }

    @Override
    public String getDescription() {
        return "图片理解工具：OCR文字识别 + 视觉问答（看图理解）。"
                + "action=ocr_recognize(图片文字)、ocr_recognize_pdf(PDF文字)、"
                + "image_understand(看图回答)、ocr_set_language(设置语言)、ocr_get_language(获取语言)";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> desc = new HashMap<>();
        desc.put("action", "操作类型: ocr_recognize(识别图片文字,默认)/ocr_recognize_pdf(识别PDF文字)/image_understand(图片理解视觉问答)/ocr_set_language(设置语言)/ocr_get_language(获取语言)");
        desc.put("image_path", "图片路径(ocr_recognize/image_understand用)：绝对路径或 content:// 或 file:// URI");
        desc.put("pdf_path", "PDF路径(ocr_recognize_pdf用)：绝对路径或 content:// 或 file:// URI");
        desc.put("question", "关于图片的问题(image_understand用，如：图里有什么？描述一下这张图)");
        desc.put("language", "识别语言: auto/chinese/english/japanese/korean(可选)");
        return desc;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            String action = parameters.get("action") != null
                    ? parameters.get("action").toString() : "ocr_recognize";

            switch (action) {
                case "ocr_recognize":
                    return ocrRecognize(parameters);
                case "ocr_recognize_pdf":
                    return ocrRecognizePdf(parameters);
                case "ocr_set_language":
                    return ocrSetLanguage(parameters);
                case "ocr_get_language":
                    return ocrGetLanguage();
                case "image_understand":
                case "image_understand_local":
                case "image_understand_online":
                    return imageUnderstand(parameters, action);
                default:
                    return new AIToolResult("未知操作: " + action + "（支持 ocr_recognize/ocr_recognize_pdf/image_understand/ocr_set_language/ocr_get_language）", parameters);
            }
        } catch (Exception e) {
            AILogger.e(TAG, "OCR tool execute failed: " + e.getMessage(), e);
            return new AIToolResult("OCR执行失败: " + e.getMessage(), parameters);
        }
    }

    private AIToolResult ocrRecognize(Map<String, Object> parameters) {
        String imagePath = getStringParam(parameters, "image_path", null);
        String language = getStringParam(parameters, "language", null);

        if (imagePath == null || imagePath.isEmpty()) {
            return new AIToolResult("缺少参数: image_path（图片路径：绝对路径或 content:// 或 file:// URI）", parameters);
        }
        File imageFile = resolveToFile(imagePath);
        if (imageFile == null) {
            return new AIToolResult("图片文件不存在或无法访问: " + imagePath + "（仅支持绝对路径、content:// 或 file:// URI）", parameters);
        }

        try {
            OCRManager ocrManager = toolkit.getOcrManager();
            String resultText = ocrManager.recognizeFileOnlineFirst(imageFile.getAbsolutePath(), language)
                    .get(60, TimeUnit.SECONDS);

            if (resultText == null || resultText.isEmpty()) {
                return new AIToolResult("未识别到文字", parameters);
            }

            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("status", "success");
            resultMap.put("text", resultText);
            resultMap.put("engine", "online_vision");
            resultMap.put("language", language != null ? language : "auto");
            return new AIToolResult(resultMap, parameters);
        } catch (java.util.concurrent.TimeoutException e) {
            return new AIToolResult("OCR识别超时，请稍后重试", parameters);
        } catch (Exception e) {
            AILogger.e(TAG, "OCR recognize failed: " + e.getMessage(), e);
            return new AIToolResult("OCR识别失败: " + e.getMessage(), parameters);
        }
    }

    private AIToolResult ocrRecognizePdf(Map<String, Object> parameters) {
        String pdfPath = getStringParam(parameters, "pdf_path", null);
        if (pdfPath == null || pdfPath.isEmpty()) {
            // 兼容 file_path/path 别名
            if (pdfPath == null) pdfPath = getStringParam(parameters, "file_path", null);
            if (pdfPath == null) pdfPath = getStringParam(parameters, "path", null);
        }
        if (pdfPath == null || pdfPath.isEmpty()) {
            return new AIToolResult("缺少参数: pdf_path（PDF路径：绝对路径或 content:// 或 file:// URI）", parameters);
        }
        File pdfFile = resolveToFile(pdfPath);
        if (pdfFile == null) {
            return new AIToolResult("PDF文件不存在或无法访问: " + pdfPath + "（仅支持绝对路径、content:// 或 file:// URI）", parameters);
        }

        try {
            Uri pdfUri = Uri.fromFile(pdfFile);
            final String[] result = new String[1];
            final String[] error = new String[1];
            final Object lock = new Object();

            synchronized (lock) {
                toolkit.recognizePdf(pdfUri, new OCRManager.OCRCallback() {
                    @Override
                    public void onSuccess(String text) {
                        synchronized (lock) {
                            result[0] = text;
                            lock.notify();
                        }
                    }

                    @Override
                    public void onFailure(String err) {
                        synchronized (lock) {
                            error[0] = err;
                            lock.notify();
                        }
                    }
                });

                try {
                    lock.wait(120000); // 120秒超时
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            if (error[0] != null) {
                return new AIToolResult("PDF识别失败: " + error[0], parameters);
            }
            if (result[0] == null || result[0].isEmpty()) {
                return new AIToolResult("PDF中未识别到文字", parameters);
            }

            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("status", "success");
            resultMap.put("text", result[0]);
            resultMap.put("pageCount", result[0].split("--- 第 ").length - 1);
            return new AIToolResult(resultMap, parameters);
        } catch (Exception e) {
            AILogger.e(TAG, "OCR PDF failed: " + e.getMessage(), e);
            return new AIToolResult("PDF识别失败: " + e.getMessage(), parameters);
        }
    }

    private AIToolResult ocrSetLanguage(Map<String, Object> parameters) {
        String language = getStringParam(parameters, "language", null);
        if (language == null || language.isEmpty()) {
            return new AIToolResult("缺少参数: language（auto/chinese/english/japanese/korean）", parameters);
        }
        toolkit.setOcrLanguage(language);

        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("status", "success");
        resultMap.put("language", language);
        resultMap.put("message", "OCR语言已设置为: " + language);
        return new AIToolResult(resultMap, parameters);
    }

    private AIToolResult ocrGetLanguage() {
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("status", "success");
        resultMap.put("language", toolkit.getCurrentOcrLanguage());
        resultMap.put("available_languages", new String[]{
            AppToolkit.OCR_LANG_AUTO,
            AppToolkit.OCR_LANG_CHINESE,
            AppToolkit.OCR_LANG_ENGLISH,
            AppToolkit.OCR_LANG_JAPANESE,
            AppToolkit.OCR_LANG_KOREAN
        });
        return new AIToolResult(resultMap, new HashMap<>());
    }

    /**
     * 图片理解/视觉问答：看图并回答关于图片的问题。
     * 通道优先级（与 AIChatActivity 发图一致）：
     * 1. 在线视觉模型（base64 注入 OpenAI 兼容消息）—— action=image_understand_online 强制
     * 2. 本地多模态（mmproj 已加载的 vision 模型）—— action=image_understand_local 强制
     * 3. 默认 image_understand：在线优先，失败回退本地，再失败回退 OCR 文字提取
     */
    private AIToolResult imageUnderstand(Map<String, Object> parameters, String action) {
        String imagePath = getStringParam(parameters, "image_path", null);
        if (imagePath == null || imagePath.isEmpty()) {
            // 兼容 file_path/path 别名
            if (imagePath == null) imagePath = getStringParam(parameters, "file_path", null);
            if (imagePath == null) imagePath = getStringParam(parameters, "path", null);
        }
        if (imagePath == null || imagePath.isEmpty()) {
            return new AIToolResult("缺少参数: image_path（图片路径：绝对路径或 content:// 或 file:// URI）", parameters);
        }
        File imageFile = resolveToFile(imagePath);
        if (imageFile == null) {
            return new AIToolResult("图片文件不存在或无法访问: " + imagePath + "（仅支持绝对路径、content:// 或 file:// URI）", parameters);
        }

        String question = getStringParam(parameters, "question", null);
        if (question == null || question.trim().isEmpty()) {
            question = "请描述这张图片的内容";
        }

        boolean forceOnline = "image_understand_online".equals(action);
        boolean forceLocal = "image_understand_local".equals(action);

        // 1) 在线视觉模型优先（除非强制本地）
        if (!forceLocal) {
            AIToolResult online = imageUnderstandOnline(imageFile, question, parameters);
            if (online != null && online.isSuccess()) {
                return online;
            }
            if (forceOnline) {
                return online != null ? online
                        : new AIToolResult("图片理解失败：没有可用的在线视觉模型（可在模型设置中配置支持视觉的模型，如 Qwen-VL/GPT-4o）", parameters);
            }
        }

        // 2) 本地多模态回退（mmproj 已加载）
        if (!forceOnline) {
            AIToolResult local = imageUnderstandLocal(imageFile, question, parameters);
            if (local != null && local.isSuccess()) {
                return local;
            }
            if (forceLocal) {
                return local != null ? local
                        : new AIToolResult("图片理解失败：本地视觉模型未加载（需 vision 模型 + mmproj 投影文件）", parameters);
            }
        }

        // 3) 最终回退：OCR 文字提取（纯识别，非理解）
        Map<String, Object> ocrParams = new HashMap<>();
        ocrParams.put("image_path", imageFile.getAbsolutePath());
        ocrParams.put("language", getStringParam(parameters, "language", null));
        AIToolResult ocr = ocrRecognize(ocrParams);
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("status", "fallback_ocr");
        fallback.put("message", "无可用视觉模型，已回退为 OCR 文字提取（仅识别文字，非图片理解）");
        fallback.put("ocr_text", ocr != null ? ocr.getResult() : "OCR失败");
        return new AIToolResult(fallback, parameters);
    }

    /** 在线视觉问答：base64 注入 OpenAI 兼容消息 */
    private AIToolResult imageUnderstandOnline(File imageFile, String question, Map<String, Object> parameters) {
        try {
            OnlineModelManager modelManager = OnlineModelManager.getInstance(context);
            OnlineModelManager.OnlineModelConfig active = modelManager != null ? modelManager.getActiveModel() : null;
            if (active == null || !active.enabled) {
                AILogger.d(TAG, "imageUnderstandOnline: no active online model");
                return null;
            }
            // 图片转 base64（≤4MB，与 AIChatActivity 在线多模态一致）
            if (imageFile.length() > 4L * 1024 * 1024) {
                AILogger.d(TAG, "imageUnderstandOnline: image > 4MB, skip online");
                return null;
            }
            byte[] bytes;
            try (FileInputStream fis = new FileInputStream(imageFile)) {
                bytes = new byte[(int) imageFile.length()];
                int off = 0, n;
                while (off < bytes.length && (n = fis.read(bytes, off, bytes.length - off)) > 0) {
                    off += n;
                }
            }
            String b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);

            OnlineInferenceService ois = OnlineInferenceService.getInstance(context);
            if (ois == null) return null;

            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<String> resultRef = new AtomicReference<>();
            final AtomicReference<String> errorRef = new AtomicReference<>();
            final StringBuilder full = new StringBuilder();

            ois.generateStreamWithImages(question, java.util.Collections.singletonList(b64),
                    active, new java.util.ArrayList<>(), 1024,
                    new com.oilquiz.app.ai.callback.StreamCallback() {
                        @Override public void onToken(String token) {
                            if (token != null) full.append(token);
                        }
                        @Override public void onComplete(String fullText) {
                            resultRef.set(fullText != null && !fullText.isEmpty() ? fullText : full.toString());
                            latch.countDown();
                        }
                        @Override public void onError(String error) {
                            errorRef.set(error);
                            latch.countDown();
                        }
                    });

            if (!latch.await(90, TimeUnit.SECONDS)) {
                return null;
            }
            if (errorRef.get() != null) {
                AILogger.d(TAG, "imageUnderstandOnline error: " + errorRef.get());
                return null;
            }
            String answer = resultRef.get();
            if (answer == null || answer.trim().isEmpty()) {
                return null;
            }
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("status", "success");
            resultMap.put("answer", answer);
            resultMap.put("engine", "online_vision");
            resultMap.put("model", active.selectedModel != null ? active.selectedModel : active.modelName);
            return new AIToolResult(resultMap, parameters);
        } catch (Exception e) {
            AILogger.d(TAG, "imageUnderstandOnline failed: " + e.getMessage());
            return null;
        }
    }

    /** 本地多模态视觉问答：mmproj 已加载的 vision 模型 */
    private AIToolResult imageUnderstandLocal(File imageFile, String question, Map<String, Object> parameters) {
        try {
            boolean multimodalReady;
            try {
                multimodalReady = LlamaHelper.isMultimodalLoaded() && LlamaHelper.isModelInitialized();
            } catch (Throwable t) {
                multimodalReady = false;
            }
            if (!multimodalReady) {
                AILogger.d(TAG, "imageUnderstandLocal: multimodal not loaded");
                return null;
            }

            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<String> resultRef = new AtomicReference<>();
            final AtomicReference<String> errorRef = new AtomicReference<>();
            final StringBuilder full = new StringBuilder();

            LlamaHelper.generateWithImage(new java.util.ArrayList<>(),
                    question, imageFile.getAbsolutePath(),
                    1024, 0.7f, 0.9f, 40, false,
                    new LlamaHelper.TokenCallback() {
                        @Override public void onToken(String token) {
                            if (token != null) full.append(token);
                        }
                        @Override public void onComplete(String fullText) {
                            resultRef.set(fullText != null && !fullText.isEmpty() ? fullText : full.toString());
                            latch.countDown();
                        }
                        @Override public void onError(String error) {
                            errorRef.set(error);
                            latch.countDown();
                        }
                    });

            if (!latch.await(120, TimeUnit.SECONDS)) {
                return null;
            }
            if (errorRef.get() != null) {
                AILogger.d(TAG, "imageUnderstandLocal error: " + errorRef.get());
                return null;
            }
            String answer = resultRef.get();
            if (answer == null || answer.trim().isEmpty()) {
                return null;
            }
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("status", "success");
            resultMap.put("answer", answer);
            resultMap.put("engine", "local_multimodal");
            return new AIToolResult(resultMap, parameters);
        } catch (Exception e) {
            AILogger.d(TAG, "imageUnderstandLocal failed: " + e.getMessage());
            return null;
        }
    }

    private static String getStringParam(Map<String, Object> params, String key, String def) {
        Object v = params != null ? params.get(key) : null;
        return v != null ? v.toString() : def;
    }

    /**
     * 把 image_path / pdf_path 参数解析为可直接访问的真实文件。
     * 兼容绝对路径 / content:// / file:// 三种形式，详见 {@link UriPathResolver#resolveToFile(Context, String)}。
     */
    private File resolveToFile(String pathOrUri) {
        return UriPathResolver.resolveToFile(context, pathOrUri);
    }
}
