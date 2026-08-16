package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.net.Uri;

import com.oilquiz.app.ai.chat.component.ComponentData;
import com.oilquiz.app.ai.util.NetworkUtil;
import com.oilquiz.app.util.AILogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import okhttp3.Request;
import okhttp3.Response;

/**
 * 文生图工具：根据提示词生成图片（Pollinations.ai 免费 API，无需 API Key）。
 *
 * 生成的图片保存到应用私有目录，通过 FileProvider 提供 content:// URI，
 * 并附带 image_grid 组件数据，对话界面直接内联显示。
 *
 * 参数：
 * - prompt: 图片描述（必填）
 * - width: 宽度（可选，默认 1024）
 * - height: 高度（可选，默认 1024）
 * - model: 模型（可选，默认 flux，如 flux/flux-realism/flux-anime）
 */
public class ImageGenTool implements AITool {

    private static final String TAG = "ImageGenTool";
    private static final String API_BASE = "https://image.pollinations.ai/prompt/";
    private static final int TIMEOUT_MS = 60000;
    private static final long MAX_IMAGE_BYTES = 10 * 1024 * 1024; // 10MB 上限

    private final Context context;

    public ImageGenTool() {
        this.context = null;
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
        return "文生图：根据描述生成图片（免费 API）。参数: prompt(必填), width, height, model";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("prompt", "图片描述（必填），如：一只可爱的橘猫在草地上晒太阳");
        params.put("width", "图片宽度（可选，默认 1024）");
        params.put("height", "图片高度（可选，默认 1024）");
        params.put("model", "模型（可选，默认 flux，可选 flux/flux-realism/flux-anime）");
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
            String model = parameters.get("model") != null ? String.valueOf(parameters.get("model")) : "flux";

            // 校验尺寸范围
            width = Math.max(256, Math.min(2048, width));
            height = Math.max(256, Math.min(2048, height));

            // 构造 Pollinations URL（免费、无需 key）
            String encodedPrompt = java.net.URLEncoder.encode(prompt, "UTF-8");
            String url = API_BASE + encodedPrompt
                    + "?width=" + width + "&height=" + height
                    + "&model=" + model
                    + "&nologo=true&seed=" + java.util.UUID.randomUUID().toString().substring(0, 8);

            AILogger.i(TAG, "Generating image: " + prompt);

            Request request = NetworkUtil.createApiRequestBuilder(url)
                    .get()
                    .build();

            try (Response response = NetworkUtil.getClient().newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return AIToolResult.fail("文生图失败(HTTP " + response.code() + "): " + response.message());
                }
                okhttp3.ResponseBody body = response.body();
                if (body == null) {
                    return AIToolResult.fail("文生图失败: 空响应");
                }

                // 下载图片到应用私有目录
                File dir = new File(context.getFilesDir(), "generated_images");
                if (!dir.exists()) dir.mkdirs();
                File imageFile = new File(dir, "gen_" + System.currentTimeMillis() + ".jpg");

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
                            return AIToolResult.fail("文生图失败: 图片超过大小限制");
                        }
                        output.write(buffer, 0, read);
                    }
                }

                if (!imageFile.exists() || imageFile.length() == 0) {
                    return AIToolResult.fail("文生图失败: 未生成有效图片");
                }

                // 通过 FileProvider 生成 content:// URI
                Uri contentUri = androidx.core.content.FileProvider.getUriForFile(
                        context, "com.oilquiz.app.fileprovider", imageFile);

                // 返回结果 + 组件数据（对话界面内联显示图片）
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
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Image generation failed: " + e.getMessage(), e);
            return AIToolResult.fail("文生图失败: " + e.getMessage());
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
