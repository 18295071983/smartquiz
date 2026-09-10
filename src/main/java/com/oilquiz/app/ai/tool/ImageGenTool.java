package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.net.Uri;

import com.oilquiz.app.ai.chat.component.ComponentData;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.model.ProviderConfigManager;
import com.oilquiz.app.ai.service.OnlineInferenceService;
import com.oilquiz.app.ai.tool.annotation.Tool;
import com.oilquiz.app.ai.util.NetworkUtil;
import com.oilquiz.app.util.AILogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Request;
import okhttp3.Response;

/**
 * 文生图工具：根据提示词生成图片。
 *
 * 双通道（在线优先，自动降级）：
 * 1. 在线 imageGen 端口：当前在线模型配置勾选 supportsImageGen 且配置表声明 imageModel 时，
 *    走 OnlineInferenceService.generateImageAsync（{base}/v1/images/generations，鉴权/SSL 复用引擎），
 *    支持各服务商官方文生图模型（b64_json 或 url 响应）；
 * 2. 免费 API 降级：在线未配置/失败/超时时回退 Pollinations.ai（无需 API Key），
 *    多模型 flux/flux-realism/flux-anime/turbo，失败自动换 flux 重试一次。
 *
 * 增强能力：
 * 1. 中文提示词自动增强（追加英文质量词，提升生成效果）
 * 2. 相同 prompt+model+尺寸 去重缓存（避免重复请求）
 * 3. 生成的图片保存到应用私有目录，通过 FileProvider 提供 content:// URI，
 *    并附带 image_grid 组件数据，对话界面直接内联显示。
 *
 * 参数：
 * - prompt: 图片描述（必填）
 * - width: 宽度（可选，默认 1024）
 * - height: 高度（可选，默认 1024）
 * - model: 模型（可选，默认 flux；在线模式下忽略，使用服务商 imageModel）
 * - style: 风格关键词（可选，如 "photorealistic"/"cartoon"/"watercolor"）
 */
@Tool(value = "image_gen", category = "media")
public class ImageGenTool implements AITool {

    private static final String TAG = "ImageGenTool";
    private static final String API_BASE = "https://image.pollinations.ai/prompt/";
    private static final long MAX_IMAGE_BYTES = 12 * 1024 * 1024; // 12MB 上限
    /** 在线生图最长等待（与引擎 read 120s 对齐，含生成+下载） */
    private static final long ONLINE_GEN_TIMEOUT_MS = 120000;

    /** 可用模型（白名单校验 + 非法回退） */
    private static final String[] SUPPORTED_MODELS = {"flux", "flux-realism", "flux-anime", "turbo"};

    /** 简单结果缓存：key = prompt|model|w|h|style → 已生成的图片文件路径（上限 200 条防内存增长） */
    private static final java.util.concurrent.ConcurrentHashMap<String, String> cache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_CACHE_ENTRIES = 200;

    /** 图片下载专用客户端：生图首字节常需 10-60s+，需独立长读超时（默认客户端 30s 会导致假失败） */
    private static final okhttp3.OkHttpClient IMAGE_CLIENT = new okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(150, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    private final Context context;

    public ImageGenTool() {
        // 无参构造（反射/动态工具路径）兜底取应用上下文，避免 context=null 潜伏 NPE
        this.context = com.oilquiz.app.SmartQuizApplication.getInstance() != null
                ? com.oilquiz.app.SmartQuizApplication.getInstance().getApplicationContext()
                : null;
    }

    public ImageGenTool(Context context) {
        this.context = context;
    }

    @Override
    public String getName() {
        return "image_gen";
    }

    @Override
    public String getDescription() {
        return "文生图：根据描述生成图片（免费 API）。参数: prompt(必填), width, height, model(flux/flux-realism/flux-anime/turbo), style";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("prompt", "图片描述（必填），如：一只可爱的橘猫在草地上晒太阳");
        params.put("width", "图片宽度（可选，默认 1024）");
        params.put("height", "图片高度（可选，默认 1024）");
        params.put("model", "模型（可选，默认 flux；flux=通用 flux-realism=写实 flux-anime=动漫 turbo=快速）");
        params.put("style", "风格（可选，如 photorealistic/cartoon/watercolor/oil painting）");
        return params;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            Object promptObj = parameters.get("prompt");
            if (promptObj == null || String.valueOf(promptObj).trim().isEmpty()) {
                return AIToolResult.fail("缺少参数: prompt（图片描述）");
            }
            String prompt = String.valueOf(promptObj).trim();

            int width = parseIntParam(parameters, "width", 1024);
            int height = parseIntParam(parameters, "height", 1024);
            String model = normalizeModel(parameters.get("model") != null
                    ? String.valueOf(parameters.get("model")) : "flux");
            String style = parameters.get("style") != null
                    ? String.valueOf(parameters.get("style")).trim() : "";

            // 校验尺寸范围
            width = Math.max(256, Math.min(2048, width));
            height = Math.max(256, Math.min(2048, height));

            // 缓存 key：相同 prompt+model+尺寸+风格 直接复用
            String cacheKey = prompt + "|" + model + "|" + width + "|" + height + "|" + style;
            String cachedPath = cache.get(cacheKey);
            if (cachedPath != null) {
                File cachedFile = new File(cachedPath);
                if (cachedFile.exists() && cachedFile.length() > 0) {
                    AILogger.i(TAG, "Image cache HIT: " + cacheKey);
                    return buildResult(cachedFile, prompt, width, height);
                }
            }

            // 生成：在线 imageGen 端口优先（配置了官方 image 模型时），失败/未配置降级免费 API
            File imageFile = tryOnlineGenerate(prompt, width, height);
            if (imageFile == null) {
                // 增强提示词（中文→英文质量词 + 风格）
                String enhancedPrompt = enhancePrompt(prompt, style);
                imageFile = generateWithFallback(enhancedPrompt, width, height, model);
            }
            if (imageFile == null) {
                return AIToolResult.fail("文生图失败: 多次尝试后仍无法生成，请稍后再试或更换描述");
            }

            // 缓存（上限 200 条，超出清空防内存无限增长）
            if (cache.size() >= MAX_CACHE_ENTRIES) {
                cache.clear();
            }
            cache.put(cacheKey, imageFile.getAbsolutePath());
            return buildResult(imageFile, prompt, width, height);

        } catch (Exception e) {
            AILogger.e(TAG, "Image generation failed: " + e.getMessage(), e);
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return AIToolResult.fail("文生图失败: " + msg);
        }
    }

    /**
     * 在线 imageGen 端口生成（优先通道，仅当用户单独设置了文生图专用模型时启用）。
     * 条件：FEATURE_IMAGE_GEN 功能专用模型已设置（apiUrl/apiKey 非空）+ 配置表声明 imageModel。
     * 未单独设置 → 返回 null，走免费 Pollinations，不消费任何在线生图 API（防止无关消费）。
     */
    private File tryOnlineGenerate(String prompt, int width, int height) {
        try {
            if (context == null) return null;
            OnlineModelManager omm = OnlineModelManager.getInstance(context);
            if (omm == null) return null;
            // 只读文生图功能专用模型：用户单独设置后才走在线端口
            OnlineModelManager.OnlineModelConfig cfg = omm.getFeatureModel(OnlineModelManager.FEATURE_IMAGE_GEN);
            if (cfg == null || cfg.apiUrl == null || cfg.apiUrl.trim().isEmpty()
                    || cfg.apiKey == null || cfg.apiKey.trim().isEmpty()) {
                return null;
            }
            ProviderConfigManager pcm = ProviderConfigManager.get();
            String imgModel = pcm.getServiceModel(cfg.apiUrl, "imageGen");
            if (imgModel == null || imgModel.trim().isEmpty()) return null; // 配置表未声明 imageModel

            OnlineInferenceService ois = OnlineInferenceService.getInstance(context);
            if (ois == null) return null;
            AILogger.i(TAG, "在线 imageGen 端口生成（专用模型=" + cfg.name + " / " + imgModel + "）");
            String result = ois.generateImageAsync(prompt, cfg)
                    .get(ONLINE_GEN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (result == null || result.trim().isEmpty()) return null;
            File file = saveOnlineImage(result, width, height);
            if (file == null) AILogger.w(TAG, "在线生图返回但图片保存失败，降级免费 API");
            return file;
        } catch (Exception e) {
            AILogger.w(TAG, "在线生图不可用，降级免费 API: " + e.getMessage());
            return null;
        }
    }

    /**
     * 保存在线生图结果：data URL（base64）直接解码落盘；http(s) URL 下载落盘。
     * 保存到 Agent 工作区目录（与免费通道一致），供 FileProvider 预览。
     */
    private File saveOnlineImage(String result, int width, int height) {
        try {
            if (result.startsWith("data:image/")) {
                int comma = result.indexOf(',');
                if (comma < 0) return null;
                String mime = result.substring(5, comma); // image/png;base64 形式
                String b64 = result.substring(comma + 1);
                byte[] bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                if (bytes == null || bytes.length == 0) return null;
                String ext = ".png";
                if (mime.contains("jpeg") || mime.contains("jpg")) ext = ".jpg";
                else if (mime.contains("webp")) ext = ".webp";
                File dir = com.oilquiz.app.ai.agent.online.AgentWorkspace.getInstance(context).getWorkspaceDir();
                if (!dir.exists()) dir.mkdirs();
                File imageFile = new File(dir, "gen_" + System.currentTimeMillis() + ext);
                try (FileOutputStream output = new FileOutputStream(imageFile)) {
                    output.write(bytes);
                }
                return (imageFile.exists() && imageFile.length() > 0) ? imageFile : null;
            }
            if (result.startsWith("http://") || result.startsWith("https://")) {
                return downloadToFile(result);
            }
            return null;
        } catch (Exception e) {
            AILogger.e(TAG, "在线图片保存失败: " + e.getMessage(), e);
            return null;
        }
    }

    /** 下载远程图片到工作区（带 Content-Type 校验与体积上限） */
    private File downloadToFile(String urlStr) {
        try {
            Request request = NetworkUtil.createApiRequestBuilder(urlStr)
                    .get()
                    .build();
            try (Response response = IMAGE_CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    AILogger.w(TAG, "在线图片下载 HTTP " + response.code() + ": " + response.message());
                    return null;
                }
                okhttp3.ResponseBody body = response.body();
                if (body == null) return null;
                String contentType = body.contentType() != null ? body.contentType().toString() : "";
                if (!contentType.toLowerCase().startsWith("image/")) {
                    AILogger.w(TAG, "在线图片非图片 Content-Type: " + contentType);
                    return null;
                }
                File dir = com.oilquiz.app.ai.agent.online.AgentWorkspace.getInstance(context).getWorkspaceDir();
                if (!dir.exists()) dir.mkdirs();
                String ext = ".jpg";
                if (contentType.toLowerCase().contains("png")) ext = ".png";
                else if (contentType.toLowerCase().contains("webp")) ext = ".webp";
                File imageFile = new File(dir, "gen_" + System.currentTimeMillis() + ext);
                try (InputStream input = body.byteStream();
                     FileOutputStream output = new FileOutputStream(imageFile)) {
                    byte[] buffer = new byte[8192];
                    long total = 0;
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        total += read;
                        if (total > MAX_IMAGE_BYTES) {
                            output.close();
                            imageFile.delete();
                            return null;
                        }
                        output.write(buffer, 0, read);
                    }
                }
                if (!imageFile.exists() || imageFile.length() == 0) {
                    imageFile.delete();
                    return null;
                }
                return imageFile;
            }
        } catch (Exception e) {
            AILogger.e(TAG, "在线图片下载失败: " + e.getMessage(), e);
            return null;
        }
    }

    /** 生成图片；指定模型失败时降级为 flux 重试一次 */
    private File generateWithFallback(String enhancedPrompt, int width, int height, String model) {
        File result = doGenerate(enhancedPrompt, width, height, model);
        if (result == null && !"flux".equals(model)) {
            AILogger.w(TAG, "Model " + model + " failed, falling back to flux");
            result = doGenerate(enhancedPrompt, width, height, "flux");
        }
        return result;
    }

    /** 单次生成：请求 Pollinations 下载图片 */
    private File doGenerate(String prompt, int width, int height, String model) {
        try {
            String encodedPrompt = java.net.URLEncoder.encode(prompt, "UTF-8");
            String url = API_BASE + encodedPrompt
                    + "?width=" + width + "&height=" + height
                    + "&model=" + model
                    + "&nologo=true&seed=" + java.util.UUID.randomUUID().toString().substring(0, 8);

            AILogger.i(TAG, "Generating image [model=" + model + "]: " + prompt);

            Request request = NetworkUtil.createApiRequestBuilder(url)
                    .get()
                    .build();

            // 图片下载用独立客户端（长读超时 150s，避免 30s 默认超时导致生图假失败）
            try (Response response = IMAGE_CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    AILogger.w(TAG, "Image gen HTTP " + response.code() + ": " + response.message());
                    return null;
                }
                okhttp3.ResponseBody body = response.body();
                if (body == null) return null;

                // 校验响应确实是图片（防止 200 但返回 HTML/JSON 错误页）
                String contentType = body.contentType() != null ? body.contentType().toString() : "";
                if (!contentType.toLowerCase().startsWith("image/")) {
                    AILogger.w(TAG, "非图片响应 Content-Type: " + contentType);
                    return null;
                }

                File dir = com.oilquiz.app.ai.agent.online.AgentWorkspace.getInstance(context).getWorkspaceDir();
                if (!dir.exists()) dir.mkdirs();
                String ext = ".jpg";
                if (contentType.toLowerCase().contains("png")) ext = ".png";
                else if (contentType.toLowerCase().contains("webp")) ext = ".webp";
                File imageFile = new File(dir, "gen_" + System.currentTimeMillis() + ext);

                try (InputStream input = body.byteStream();
                     FileOutputStream output = new FileOutputStream(imageFile)) {
                    byte[] buffer = new byte[8192];
                    long total = 0;
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        total += read;
                        if (total > MAX_IMAGE_BYTES) {
                            output.close();
                            imageFile.delete();
                            return null;
                        }
                        output.write(buffer, 0, read);
                    }
                }

                if (!imageFile.exists() || imageFile.length() == 0) {
                    imageFile.delete();
                    return null;
                }
                return imageFile;
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Image gen error: " + e.getMessage(), e);
            return null;
        }
    }

    /** 提示词增强：中文追加英文质量词，附加风格关键词 */
    private String enhancePrompt(String prompt, String style) {
        StringBuilder sb = new StringBuilder(prompt);
        // 风格词
        if (style != null && !style.isEmpty()) {
            sb.append(", ").append(style);
        }
        // 中文提示词追加英文质量词（Flux 对英文理解更好）
        if (containsCjk(prompt)) {
            sb.append(", high quality, detailed, 8k, professional");
        }
        return sb.toString();
    }

    private boolean containsCjk(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) return true;
        }
        return false;
    }

    /** 模型白名单归一化 */
    private String normalizeModel(String model) {
        if (model == null) return "flux";
        String m = model.trim().toLowerCase();
        for (String supported : SUPPORTED_MODELS) {
            if (supported.equals(m)) return m;
        }
        // 别名
        if (m.contains("real") || m.contains("photo")) return "flux-realism";
        if (m.contains("anime") || m.contains("cartoon")) return "flux-anime";
        if (m.contains("turbo") || m.contains("fast") || m.contains("quick")) return "turbo";
        return "flux";
    }

    /** 构建成功结果（含 image_grid 组件） */
    private AIToolResult buildResult(File imageFile, String prompt, int width, int height) {
        try {
            Uri contentUri = androidx.core.content.FileProvider.getUriForFile(
                    context, "com.oilquiz.app.fileprovider", imageFile);

            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("prompt", prompt);
            result.put("imagePath", imageFile.getAbsolutePath());
            result.put("contentUri", contentUri.toString());
            result.put("width", width);
            result.put("height", height);
            result.put("message", "图片已生成，可直接查看/分享");

            AIToolResult toolResult = AIToolResult.success(result);
            // 组件：单图内联显示
            try {
                JSONObject props = new JSONObject();
                props.put("columns", 1);
                JSONArray images = new JSONArray();
                images.put(contentUri.toString());
                props.put("images", images);
                toolResult.withComponent(ComponentData.of("image_grid", props));
            } catch (Exception ignored) {
            }
            return toolResult;
        } catch (Exception e) {
            // FileProvider 失败（如工作区目录未导出）明确报错，不再静默返回"成功但无图"
            AILogger.e(TAG, "Build result failed: " + e.getMessage(), e);
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return AIToolResult.fail("图片已生成（" + imageFile.getAbsolutePath() + "）但无法提供预览: " + msg);
        }
    }

    private static int parseIntParam(Map<String, Object> parameters, String key, int defaultValue) {
        Object value = parameters.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
}
