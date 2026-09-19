package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.oilquiz.app.ai.chat.component.ComponentData;
import com.oilquiz.app.ai.tool.annotation.Tool;
import com.oilquiz.app.ai.util.NetworkUtil;
import com.oilquiz.app.util.AILogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Request;
import okhttp3.Response;

/**
 * 百炼 DashScope 文生图 / 文生视频工具（通义万相 wan2.x）。
 *
 * 对齐 dashscope_t2i_t2v_guide.md（手机实测速查）：
 * - 文生图：text2image，同步返回 output.results[0].url（wan2.2-t2i-flash/plus）
 * - 文生视频：video-synthesis 异步，X-DashScope-Async → output.task_id →
 *   轮询 /api/v1/tasks/{id} 至 SUCCEEDED 取 video_url（wan2.2-t2v-plus）
 * - 视频尺寸白名单：1080*1920, 1920*1080, 1440*1440, 1632*1248, 1248*1632,
 *   480*832, 832*480, 624*624（其他报 InvalidParameter）
 *
 * 工具执行超时 30s，视频生成需数分钟 → 采用"提交/查询"两步：
 * - action=image   提交文生图并短轮询（~25s），完成即下载返回；未完成返回 task_id
 * - action=video   提交文生视频，立即返回 task_id（不阻塞）
 * - action=query   按 task_id 查询进度；SUCCEEDED 时自动下载并返回文件
 *
 * API Key：优先参数 api_key，否则取当前在线模型配置（默认业务空间）的 key。
 */
@Tool(value = "dashscope_media", category = "media")
public class DashscopeMediaTool implements AITool {
    private static final String TAG = "DashscopeMediaTool";

    /** 视频尺寸白名单（wan2.2-t2v-plus 实测，其他报 InvalidParameter） */
    private static final String[] VIDEO_SIZE_WHITELIST = {
            "1080*1920", "1920*1080", "1440*1440", "1632*1248",
            "1248*1632", "480*832", "832*480", "624*624"
    };

    /** 提交后短轮询上限（秒，< 30s 工具超时） */
    private static final int SHORT_POLL_LIMIT_S = 25;
    /** 查询间隔（秒） */
    private static final int POLL_INTERVAL_MS = 3000;

    /** 下载专用客户端：生成结果下载可能较慢 */
    private static final okhttp3.OkHttpClient MEDIA_CLIENT = new okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(150, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    private static final long MAX_FILE_BYTES = 200L * 1024 * 1024; // 视频 200MB 上限

    private final Context context;

    public DashscopeMediaTool(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public String getName() {
        return "dashscope_media";
    }

    @Override
    public String getDescription() {
        return "文生图/文生视频（多提供商）：百炼DashScope(通义万相,默认)走原生接口；其他提供商(scnet/SiliconFlow/智谱等)走OpenAI兼容接口(/images/generations、/videos/generations)。action: image=文生图；video=文生视频(异步提交返回task_id；百炼默认省钱档wan2.1-t2v-turbo约¥0.3/5s，plus/大尺寸弹费用确认)；query=按task_id查询并下载；models=获取可用模型。api_url=指定提供商端点(默认跟随模型管理配置的百炼系端点)；api_key=对应Key。";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("action", "操作: image(文生图)/video(文生视频)/query(按task_id查询并下载)/models(动态获取可用模型)");
        params.put("prompt", "画面/视频描述（必填），中文即可");
        params.put("model", "视频模型: wan2.1-t2v-turbo(默认,省钱约¥0.3/5s)/wan2.2-t2v-plus(画质好更贵)；图片: wan2.2-t2i-flash默认/wan2.2-t2i-plus");
        params.put("size", "尺寸(image: 如1024*1024默认；video: 白名单 1080*1920/1920*1080/1440*1440/1632*1248/1248*1632/480*832/832*480/624*624，默认832*480，大尺寸费用高)");
        params.put("duration", "视频时长秒数(video用，默认5，按秒计费)");
        params.put("task_id", "任务ID(query用)");
        params.put("confirm_cost", "是否弹费用确认框(video用，默认true；UI页传false跳过)");
        params.put("api_url", "百炼端点(可选，默认跟随模型管理配置的百炼系端点：专属空间MaaS/公共；也可自定义，如 https://ws-xxx.cn-beijing.maas.aliyuncs.com/compatible-mode/v1)");
        params.put("api_key", "百炼API Key(可选，默认自动解析)");
        return params;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            String action = parameters.get("action") != null
                    ? String.valueOf(parameters.get("action")).toLowerCase() : "image";
            String prompt = parameters.get("prompt") != null
                    ? String.valueOf(parameters.get("prompt")).trim() : "";
            // query/models 不需要 prompt（models=动态查询模型列表）
            if (prompt.isEmpty() && !"query".equals(action) && !"models".equals(action)) {
                return AIToolResult.fail("缺少参数: prompt（画面/视频描述）");
            }
            String apiKey = parameters.get("api_key") != null
                    ? String.valueOf(parameters.get("api_key")).trim() : null;
            // 文生图/文生视频：功能专用模型优先（单独设置后才消费在线 API，防止无关消费）
            boolean isGenAction = "image".equals(action) || "video".equals(action);
            String genFeature = "video".equals(action)
                    ? com.oilquiz.app.ai.model.OnlineModelManager.FEATURE_VIDEO_GEN
                    : com.oilquiz.app.ai.model.OnlineModelManager.FEATURE_IMAGE_GEN;
            com.oilquiz.app.ai.model.OnlineModelManager.OnlineModelConfig featureCfg =
                    isGenAction ? resolveFeatureConfig(genFeature) : null;
            if (featureCfg != null) {
                // 专用模型已设置：Key / 端点 / 模型名全部以专用配置为准
                if (featureCfg.apiKey != null && !featureCfg.apiKey.trim().isEmpty()) {
                    apiKey = featureCfg.apiKey.trim();
                }
                if (featureCfg.apiUrl != null && !featureCfg.apiUrl.trim().isEmpty()) {
                    parameters.put("api_url", featureCfg.apiUrl.trim());
                }
                if (featureCfg.modelName != null && !featureCfg.modelName.trim().isEmpty()) {
                    parameters.put("model", featureCfg.modelName.trim());
                }
            } else if (isGenAction && (apiKey == null || apiKey.isEmpty())) {
                // 生成类动作未设置专用模型且未显式传 api_key → 拒绝，不使用主模型 Key 消费无关 API
                String label = "video".equals(action) ? "文生视频" : "文生图";
                return AIToolResult.fail("未设置" + label
                        + "专用模型：请先在模型管理中选择" + label + "专用模型（防止无关 API 消费）；或显式传入 api_key");
            }
            if (apiKey == null || apiKey.isEmpty()) {
                apiKey = resolveDefaultApiKey();
            }
            if (apiKey == null || apiKey.isEmpty()) {
                return AIToolResult.fail("未配置 API Key：请在模型管理页配置在线模型，或传入 api_key 参数");
            }
            // 端点：api_url 参数 > 配置端点 > 默认公共；据此判断协议（百炼原生 / OpenAI 兼容）
            String compatBase = resolveCompatBase(parameters);

            switch (action) {
                case "video":
                    if (isDashScopeEndpoint(compatBase)) {
                        return handleVideo(parameters, prompt, apiKey);
                    }
                    return handleOpenAICompatibleVideo(parameters, prompt, apiKey, compatBase);
                case "query":
                    if (isDashScopeEndpoint(compatBase)) {
                        return handleQuery(parameters, apiKey);
                    }
                    return handleOpenAICompatibleQuery(parameters, apiKey, compatBase);
                case "models":
                    if (isDashScopeEndpoint(compatBase)) {
                        return handleModels(apiKey, compatBase);
                    }
                    return handleOpenAICompatibleModels(apiKey, compatBase);
                case "image":
                default:
                    if (isDashScopeEndpoint(compatBase)) {
                        return handleImage(parameters, prompt, apiKey);
                    }
                    return handleOpenAICompatibleImage(parameters, prompt, apiKey, compatBase);
            }
        } catch (Exception e) {
            Log.e(TAG, "百炼媒体工具失败: " + e.getMessage(), e);
            return AIToolResult.fail("百炼媒体工具失败: " + e.getMessage());
        }
    }

    /**
     * 读取功能专用模型配置（文生图/文生视频）。
     * 返回 null 表示用户未单独设置 → 生成类动作不消费任何在线媒体 API。
     */
    private com.oilquiz.app.ai.model.OnlineModelManager.OnlineModelConfig resolveFeatureConfig(String feature) {
        try {
            com.oilquiz.app.ai.model.OnlineModelManager m =
                    com.oilquiz.app.ai.model.OnlineModelManager.getInstance(context);
            if (m == null) return null;
            com.oilquiz.app.ai.model.OnlineModelManager.OnlineModelConfig c = m.getFeatureModel(feature);
            if (c != null && c.apiKey != null && !c.apiKey.isEmpty()) return c;
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "读取功能专用模型失败: " + t.getMessage());
            return null;
        }
    }

    // ==================== 文生图 ====================
    private AIToolResult handleImage(Map<String, Object> parameters, String prompt, String apiKey) {
        String model = strParam(parameters, "model", "wan2.2-t2i-flash");
        String size = strParam(parameters, "size", "1024*1024");
        int n = Math.max(1, Math.min(4, intParam(parameters, "n", 1)));
        try {
            // 端口：api_url 参数 > 配置端点（专属空间/公共）> 默认公共（不再硬编码）
            com.alibaba.dashscope.utils.Constants.baseHttpApiUrl = resolveApiV1Base(parameters);
            com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisParam param =
                    com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisParam.builder()
                            .apiKey(apiKey)
                            .model(model)
                            .prompt(prompt)
                            .size(size)
                            .n(n)
                            .build();
            AILogger.i(TAG, "提交百炼文生图: model=" + model + " size=" + size);
            com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisResult r =
                    new com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesis().asyncCall(param);
            String taskId = r != null && r.getOutput() != null ? r.getOutput().getTaskId() : null;
            if (taskId == null) {
                String msg = r != null ? r.getMessage() : "无返回";
                return AIToolResult.fail("文生图提交失败: " + msg);
            }
            // 短轮询（~25s）：完成即下载，未完成返回 task_id 供 query
            long deadline = System.currentTimeMillis() + SHORT_POLL_LIMIT_S * 1000L;
            com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesis imageSyn =
                    new com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesis();
            while (System.currentTimeMillis() < deadline) {
                try { Thread.sleep(POLL_INTERVAL_MS); } catch (InterruptedException ignored) { break; }
                com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisResult st =
                        imageSyn.fetch(taskId, apiKey);
                String status = st != null && st.getOutput() != null ? st.getOutput().getTaskStatus() : "";
                if ("SUCCEEDED".equalsIgnoreCase(status)) {
                    List<Map<String, String>> results = st.getOutput().getResults();
                    if (results != null && !results.isEmpty()) {
                        String url = results.get(0).get("url");
                        if (url != null && !url.isEmpty()) {
                            return downloadResult(url, null, prompt, true);
                        }
                    }
                    return AIToolResult.fail("文生图完成但无图片URL");
                }
                if ("FAILED".equalsIgnoreCase(status) || "CANCELED".equalsIgnoreCase(status)) {
                    return AIToolResult.fail("文生图失败: " + (st != null ? st.getMessage() : status));
                }
            }
            // 未完成：返回 task_id 供 query
            Map<String, Object> info = new HashMap<>();
            info.put("task_id", taskId);
            info.put("action", "query");
            info.put("type", "image");
            info.put("message", "文生图任务处理中，请调用 dashscope_media action=query task_id=" + taskId + " 查询并获取图片");
            return new AIToolResult(
                    "⏳ 文生图任务提交成功，处理中（task_id=" + taskId + "）\n"
                    + "稍后调用 dashscope_media action=query task_id=" + taskId + " 获取结果", info, true);
        } catch (Exception e) {
            Log.e(TAG, "文生图失败: " + e.getMessage(), e);
            return AIToolResult.fail("文生图失败: " + e.getMessage());
        }
    }

    // ==================== 文生视频 ====================

    private AIToolResult handleVideo(Map<String, Object> parameters, String prompt, String apiKey) {
        // 默认省钱档：wan2.1-t2v-turbo（约 ¥0.3/5s）；要画质传 model=wan2.2-t2v-plus
        String model = strParam(parameters, "model", "wan2.1-t2v-turbo");
        String size = strParam(parameters, "size", "832*480");
        // 尺寸白名单校验（guide 实测：非白名单报 InvalidParameter）
        boolean sizeOk = false;
        for (String s : VIDEO_SIZE_WHITELIST) {
            if (s.equals(size)) { sizeOk = true; break; }
        }
        if (!sizeOk) {
            return AIToolResult.fail("视频尺寸 " + size + " 不在白名单，仅支持: "
                    + String.join(", ", VIDEO_SIZE_WHITELIST));
        }
        int duration = Math.max(1, Math.min(10, intParam(parameters, "duration", 5)));
        // 费用确认：plus 模型或大尺寸 = 高画质高费用档，先弹原生确认框（用户确认才提交）。
        // UI 页（MediaGenActivity）已有费用提示，传 confirm_cost=false 跳过。
        boolean confirmCost = !"false".equalsIgnoreCase(String.valueOf(parameters.get("confirm_cost")))
                && !"0".equals(String.valueOf(parameters.get("confirm_cost")));
        boolean expensive = !model.toLowerCase().contains("turbo")
                || size.contains("1080*1920") || size.contains("1920*1080")
                || size.contains("1632*1248") || size.contains("1248*1632")
                || size.contains("1440*1440");
        if (confirmCost && expensive) {
            boolean ok = confirmVideoCost(model, size, duration);
            if (!ok) {
                return AIToolResult.fail("已取消：视频生成费用未确认（模型 " + model + "，尺寸 " + size
                        + "，约 ¥" + estimateVideoCost(model, size, duration) + "）");
            }
        }
        try {
            // 端口：api_url 参数 > 配置端点（专属空间/公共）> 默认公共（不再硬编码）
            com.alibaba.dashscope.utils.Constants.baseHttpApiUrl = resolveApiV1Base(parameters);
            com.alibaba.dashscope.aigc.videosynthesis.VideoSynthesisParam param =
                    com.alibaba.dashscope.aigc.videosynthesis.VideoSynthesisParam.builder()
                            .apiKey(apiKey)
                            .model(model)
                            .prompt(prompt)
                            .size(size)
                            .duration(duration)
                            .build();
            AILogger.i(TAG, "提交百炼文生视频: model=" + model + " size=" + size + " duration=" + duration);
            com.alibaba.dashscope.aigc.videosynthesis.VideoSynthesisResult r =
                    new com.alibaba.dashscope.aigc.videosynthesis.VideoSynthesis().asyncCall(param);
            String taskId = r != null && r.getOutput() != null ? r.getOutput().getTaskId() : null;
            if (taskId == null) {
                String msg = r != null ? r.getMessage() : "无返回";
                return AIToolResult.fail("文生视频提交失败: " + msg);
            }
            Map<String, Object> info = new HashMap<>();
            info.put("task_id", taskId);
            info.put("action", "query");
            info.put("type", "video");
            info.put("model", model);
            info.put("size", size);
            info.put("message", "文生视频提交成功，几分钟后调用 query 获取结果");
            return new AIToolResult(
                    "🎬 文生视频任务提交成功（task_id=" + taskId + "，模型 " + model + "，尺寸 " + size + "）\n"
                    + "生成需数分钟，稍后调用 dashscope_media action=query task_id=" + taskId + " 查询并下载", info, true);
        } catch (Exception e) {
            Log.e(TAG, "文生视频失败: " + e.getMessage(), e);
            return AIToolResult.fail("文生视频失败: " + e.getMessage());
        }
    }

    // ==================== OpenAI 兼容提供商（scnet/SiliconFlow/智谱等，自适应容错） ====================

    /** 是否百炼系端点（原生协议）；否则按 OpenAI 兼容协议处理 */
    private boolean isDashScopeEndpoint(String base) {
        if (base == null) return true;
        String b = base.toLowerCase();
        return b.contains("dashscope") || b.contains("maas.aliyuncs.com");
    }

    /** OpenAI 兼容文生图：自适应请求体（先 DashScope 风格 input.prompt+parameters.size(*)，失败回退平铺 OpenAI 风格） */
    private AIToolResult handleOpenAICompatibleImage(Map<String, Object> parameters,
                                                     String prompt, String apiKey, String compatBase) {
        String model = strParam(parameters, "model", "Qwen-Image-2.0");
        String size = strParam(parameters, "size", "1024*1024");
        int n = Math.max(1, Math.min(4, intParam(parameters, "n", 1)));
        try {
            // 1) DashScope 风格（scnet 克隆百炼 qwen-image 接口）：model + input.prompt + parameters
            org.json.JSONObject body = new org.json.JSONObject();
            body.put("model", model);
            org.json.JSONObject input = new org.json.JSONObject();
            input.put("prompt", prompt);
            body.put("input", input);
            org.json.JSONObject params = new org.json.JSONObject();
            params.put("size", size);
            params.put("n", n);
            params.put("prompt_extend", true);
            params.put("watermark", false);
            body.put("parameters", params);
            AILogger.i(TAG, "OpenAI兼容文生图(DashScope风格): " + compatBase + "/images/generations model=" + model);
            String resp = httpPost(compatBase + "/images/generations", apiKey, body, null);
            AIToolResult r = parseImageResult(resp, prompt);
            if (r != null) return r;
            // 2) 回退平铺 OpenAI 风格（部分提供商用 prompt/size 顶层）
            org.json.JSONObject flat = new org.json.JSONObject();
            flat.put("model", model);
            flat.put("prompt", prompt);
            flat.put("size", size);
            flat.put("n", n);
            flat.put("response_format", "url");
            String resp2 = httpPost(compatBase + "/images/generations", apiKey, flat, null);
            r = parseImageResult(resp2, prompt);
            if (r != null) return r;
            return AIToolResult.fail("文生图失败（两种请求体均无图片URL）: "
                    + resp.substring(0, Math.min(300, resp.length())));
        } catch (Exception e) {
            Log.e(TAG, "OpenAI兼容文生图失败: " + e.getMessage(), e);
            return AIToolResult.fail("文生图失败: " + e.getMessage());
        }
    }

    /** 解析生图响应为结果（URL 字符串 / JSON URL / b64）；解析不出返回 null */
    private AIToolResult parseImageResult(String resp, String nameHint) {
        try {
            String trimmed = resp.trim();
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                return downloadResult(trimmed, null, nameHint, true);
            }
            org.json.JSONObject j = new org.json.JSONObject(resp);
            String url = parseFirstUrl(j);
            if (!url.isEmpty()) {
                return downloadResult(url, null, nameHint, true);
            }
            String b64 = jsonPathString(j, "data[0].b64_json");
            if (b64 == null || b64.isEmpty()) b64 = jsonPathString(j, "b64_json");
            if (b64 != null && !b64.isEmpty()) {
                return downloadBase64(b64, nameHint);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** OpenAI 兼容文生视频：容错提交（input.prompt / 平铺 prompt 两种 body，异步头双发）→ task_id 或直接结果 */
    private AIToolResult handleOpenAICompatibleVideo(Map<String, Object> parameters,
                                                     String prompt, String apiKey, String compatBase) {
        String model = strParam(parameters, "model", "Seedance2.0");
        int duration = Math.max(1, Math.min(10, intParam(parameters, "duration", 5)));
        String size = strParam(parameters, "size", "");
        try {
            // 先按 scnet 风格（input.prompt + parameters）提交
            org.json.JSONObject body = new org.json.JSONObject();
            body.put("model", model);
            org.json.JSONObject input = new org.json.JSONObject();
            input.put("prompt", prompt);
            body.put("input", input);
            org.json.JSONObject p = new org.json.JSONObject();
            p.put("duration", duration);
            if (!size.isEmpty()) p.put("resolution", size);
            body.put("parameters", p);
            String resp = httpPost(compatBase + "/videos/generations", apiKey, body,
                    "X-MultiModal-Async: true\nX-DashScope-Async: enable");
            org.json.JSONObject j = new org.json.JSONObject(resp);
            String taskId = parseTaskId(j);
            if (taskId.isEmpty()) {
                // 兼容平铺 body（部分提供商不支持 input 嵌套）
                org.json.JSONObject flat = new org.json.JSONObject();
                flat.put("model", model);
                flat.put("prompt", prompt);
                flat.put("duration", duration);
                if (!size.isEmpty()) flat.put("resolution", size);
                String resp2 = httpPost(compatBase + "/videos/generations", apiKey, flat,
                        "X-MultiModal-Async: true\nX-DashScope-Async: enable");
                j = new org.json.JSONObject(resp2);
                taskId = parseTaskId(j);
            }
            if (!taskId.isEmpty()) {
                Map<String, Object> info = new HashMap<>();
                info.put("task_id", taskId);
                info.put("action", "query");
                info.put("type", "video");
                info.put("model", model);
                info.put("api_url", compatBase); // 关键：后续 query 复用同一端点
                info.put("message", "文生视频提交成功，稍后调用 query 获取结果");
                return new AIToolResult("🎬 文生视频任务提交成功（task_id=" + taskId + "，模型 " + model + "）\n"
                        + "稍后调用 dashscope_media action=query task_id=" + taskId + " api_url=" + compatBase
                        + " 查询并下载", info, true);
            }
            // 同步返回结果（部分提供商直接给结果）
            String url = parseFirstUrl(j);
            if (!url.isEmpty()) {
                return downloadResult(url, null, "generated_video", false);
            }
            return AIToolResult.fail("文生视频提交失败（无 task_id/结果）: "
                    + resp.substring(0, Math.min(300, resp.length())));
        } catch (Exception e) {
            Log.e(TAG, "OpenAI兼容文生视频失败: " + e.getMessage(), e);
            return AIToolResult.fail("文生视频失败: " + e.getMessage());
        }
    }

    /** OpenAI 兼容查询：自适应任务 URL（tasks/videos-tasks/async-tasks/results）与状态/结果字段布局 */
    private AIToolResult handleOpenAICompatibleQuery(Map<String, Object> parameters,
                                                     String apiKey, String compatBase) {
        String taskId = strParam(parameters, "task_id", "");
        if (taskId.isEmpty()) {
            return AIToolResult.fail("query 操作必须提供 task_id");
        }
        try {
            String[] candidates = {
                    compatBase + "/tasks/" + taskId,
                    compatBase + "/videos/tasks/" + taskId,
                    compatBase + "/async/tasks/" + taskId,
                    compatBase + "/results/" + taskId,
            };
            org.json.JSONObject j = null;
            String resp = "";
            for (String u : candidates) {
                try {
                    resp = httpGet(u, apiKey);
                    j = new org.json.JSONObject(resp);
                    if (!parseStatus(j).isEmpty()) break;
                } catch (Exception e) {
                    // 404 等继续试下一个 URL 模式
                    j = null;
                }
            }
            if (j == null) {
                return AIToolResult.fail("查询失败：所有任务接口均不可用（task_id=" + taskId + "）");
            }
            String status = parseStatus(j);
            String url = parseFirstUrl(j);
            if (isSuccessStatus(status)) {
                if (url.isEmpty()) {
                    return AIToolResult.fail("任务成功但无结果 URL: " + resp.substring(0, Math.min(300, resp.length())));
                }
                return downloadResult(url, taskId, "generated_media_" + taskId,
                        url.contains(".png") || url.contains(".jpg") || url.contains(".jpeg") || url.contains(".webp"));
            }
            if (isFailureStatus(status)) {
                return AIToolResult.fail("任务失败: " + status);
            }
            if (!status.isEmpty()) {
                Map<String, Object> info = new HashMap<>();
                info.put("task_id", taskId);
                info.put("type", "video");
                info.put("status", status);
                info.put("message", "任务处理中，请稍后再查询");
                return new AIToolResult("⏳ 任务处理中（task_id=" + taskId + "，状态=" + status + "）\n"
                        + "稍后再调用 dashscope_media action=query task_id=" + taskId + " api_url=" + compatBase + " 查询", info, true);
            }
            // 状态字段未知：把原始响应给 Agent 判断
            return new AIToolResult("查询响应（状态字段未知，请依据响应判断）:\n"
                    + resp.substring(0, Math.min(400, resp.length())), null, true);
        } catch (Exception e) {
            Log.e(TAG, "OpenAI兼容查询失败: " + e.getMessage(), e);
            return AIToolResult.fail("查询失败: " + e.getMessage());
        }
    }

    /** OpenAI 兼容模型列表：GET {base}/models → 按关键词过滤图片/视频生成模型，
     *  过滤不到时给默认（Seedream/Seedance2.0），全量放 all_models 供参考。 */
    private AIToolResult handleOpenAICompatibleModels(String apiKey, String compatBase) {
        try {
            String resp = httpGet(compatBase + "/models", apiKey);
            String trimmed = resp.trim();
            java.util.List<String> all = new java.util.ArrayList<>();
            try {
                org.json.JSONObject j = new org.json.JSONObject(trimmed);
                Object data = j.opt("data");
                if (data instanceof org.json.JSONArray) {
                    org.json.JSONArray arr = (org.json.JSONArray) data;
                    for (int i = 0; i < arr.length(); i++) {
                        Object o = arr.opt(i);
                        if (o instanceof org.json.JSONObject) {
                            String id = ((org.json.JSONObject) o).optString("id", "");
                            if (id.isEmpty()) id = ((org.json.JSONObject) o).optString("model", "");
                            if (!id.isEmpty()) all.add(id);
                        } else if (o != null) {
                            all.add(String.valueOf(o));
                        }
                    }
                }
            } catch (Exception e) {
                try {
                    org.json.JSONArray arr = new org.json.JSONArray(trimmed);
                    for (int i = 0; i < arr.length(); i++) {
                        Object o = arr.opt(i);
                        if (o != null) all.add(String.valueOf(o));
                    }
                } catch (Exception ignored) {
                }
            }
            // 关键词过滤：图片（seed/image/ti/flux/sd/dall-e/wanx/t2i），视频（seedance/video/t2v/dream）
            java.util.List<String> image = new java.util.ArrayList<>();
            java.util.List<String> video = new java.util.ArrayList<>();
            for (String id : all) {
                String low = id.toLowerCase();
                if (low.contains("seedance") || low.contains("video") || low.contains("t2v")
                        || low.contains("dream") && !low.contains("seedream")) {
                    video.add(id);
                } else if (low.contains("seedream") || low.contains("seed") || low.contains("image")
                        || low.contains("flux") || low.contains("dall") || low.contains("t2i")
                        || low.contains("wanx")) {
                    image.add(id);
                }
            }
            // 过滤不到 → 默认生成模型（列表接口通常不含生成模型）
            if (image.isEmpty()) image.add("Seedream");
            if (video.isEmpty()) video.add("Seedance2.0");
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("count", all.size());
            result.put("image_models", new org.json.JSONArray(image).toString());
            result.put("video_models", new org.json.JSONArray(video).toString());
            result.put("all_models", new org.json.JSONArray(all).toString());
            result.put("message", "提供商模型 " + all.size() + " 个；生图 " + image.size()
                    + " 个，生视频 " + video.size() + " 个（按关键词过滤，不全时已用默认）");
            return AIToolResult.success(result);
        } catch (Exception e) {
            Log.e(TAG, "OpenAI兼容模型列表失败: " + e.getMessage(), e);
            return AIToolResult.fail("获取模型列表失败: " + e.getMessage());
        }
    }

    // ==================== 容错 JSON 解析 ====================

    /** 点路径取值，支持数组下标：data[0].url / output.task_status */
    private static Object jsonPath(org.json.JSONObject root, String path) {
        if (root == null) return null;
        Object cur = root;
        for (String seg : path.split("\\.")) {
            if (cur == null) return null;
            String key = seg;
            String idx = null;
            int b = seg.indexOf('[');
            if (b >= 0) {
                key = seg.substring(0, b);
                int e = seg.indexOf(']');
                if (e > b) idx = seg.substring(b + 1, e);
            }
            if (cur instanceof org.json.JSONObject) {
                cur = key.isEmpty() ? cur : ((org.json.JSONObject) cur).opt(key);
            } else {
                return null;
            }
            if (idx != null) {
                if (cur instanceof org.json.JSONArray) {
                    try {
                        cur = ((org.json.JSONArray) cur).opt(Integer.parseInt(idx));
                    } catch (Exception ex) {
                        return null;
                    }
                } else {
                    return null;
                }
            }
        }
        return cur;
    }

    private static String jsonPathString(org.json.JSONObject j, String path) {
        Object v = jsonPath(j, path);
        return v != null ? String.valueOf(v) : null;
    }

    /** 容错解析 task_id（output.task_id / data.task_id / id / 顶层等） */
    private static String parseTaskId(org.json.JSONObject j) {
        String[] paths = {"output.task_id", "task_id", "data.task_id", "data[0].task_id",
                "result.task_id", "id"};
        for (String p : paths) {
            String v = jsonPathString(j, p);
            if (v != null && !v.isEmpty() && !"null".equals(v)) return v;
        }
        return "";
    }

    /** 容错解析任务状态（task_status / status / state 等） */
    private static String parseStatus(org.json.JSONObject j) {
        String[] paths = {"output.task_status", "task_status", "output.status", "status",
                "state", "output.state", "data.task_status", "data[0].task_status", "result.status"};
        for (String p : paths) {
            String v = jsonPathString(j, p);
            if (v != null && !v.isEmpty() && !"null".equals(v)) return v;
        }
        return "";
    }

    /** 容错解析结果 URL（video_url / results[0].url / results[0]字符串 / data[0].url 等） */
    private static String parseFirstUrl(org.json.JSONObject j) {
        String[] paths = {"output.video_url", "video_url", "output.results[0].url",
                "output.results[0]", "results[0]", "output.videos[0].url",
                "output.videos[0].video_url", "output.videos[0]", "data[0].url",
                "data[0].video_url", "data[0]", "images[0].url", "output.url", "url",
                "result[0].url", "result[0]", "data.url"};
        for (String p : paths) {
            String v = jsonPathString(j, p);
            if (v != null && !v.isEmpty() && v.startsWith("http")) return v;
        }
        return "";
    }

    private static boolean isSuccessStatus(String s) {
        if (s == null) return false;
        String l = s.toLowerCase();
        return l.contains("succeed") || l.contains("success") || l.contains("completed") || l.contains("done");
    }

    private static boolean isFailureStatus(String s) {
        if (s == null) return false;
        String l = s.toLowerCase();
        return l.contains("fail") || l.contains("cancel") || l.contains("error");
    }

    // ==================== HTTP 工具 ====================

    private String httpPost(String url, String apiKey, org.json.JSONObject body, String asyncHeader)
            throws Exception {
        okhttp3.MediaType JSON = okhttp3.MediaType.parse("application/json; charset=utf-8");
        okhttp3.Request.Builder rb = NetworkUtil.createApiRequestBuilder(url)
                .header("Authorization", "Bearer " + apiKey)
                .post(okhttp3.RequestBody.create(JSON, body.toString()));
        if (asyncHeader != null) {
            // 支持多行头："X-MultiModal-Async: true\nX-DashScope-Async: enable"
            for (String line : asyncHeader.split("\n")) {
                String[] kv = line.split(":", 2);
                if (kv.length == 2) rb.header(kv[0].trim(), kv[1].trim());
            }
        }
        try (Response response = MEDIA_CLIENT.newCall(rb.build()).execute()) {
            okhttp3.ResponseBody b = response.body();
            String text = b != null ? b.string() : "";
            if (!response.isSuccessful()) {
                throw new Exception("HTTP " + response.code() + ": " + text.substring(0, Math.min(200, text.length())));
            }
            return text;
        }
    }

    private String httpGet(String url, String apiKey) throws Exception {
        Request request = NetworkUtil.createApiRequestBuilder(url)
                .header("Authorization", "Bearer " + apiKey)
                .get()
                .build();
        try (Response response = MEDIA_CLIENT.newCall(request).execute()) {
            okhttp3.ResponseBody b = response.body();
            String text = b != null ? b.string() : "";
            if (!response.isSuccessful()) {
                throw new Exception("HTTP " + response.code() + ": " + text.substring(0, Math.min(200, text.length())));
            }
            return text;
        }
    }

    /** base64 图片数据 → 保存并返回 */
    private AIToolResult downloadBase64(String b64, String nameHint) {
        try {
            byte[] bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
            File dir = com.oilquiz.app.ai.agent.online.AgentWorkspace.getInstance(context).getFilesDir();
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "gen_" + System.currentTimeMillis() + ".png");
            try (java.io.FileOutputStream os = new java.io.FileOutputStream(f)) {
                os.write(bytes);
            }
            return buildFileResult(f);
        } catch (Exception e) {
            Log.e(TAG, "base64 保存失败: " + e.getMessage(), e);
            return AIToolResult.fail("图片保存失败: " + e.getMessage());
        }
    }

    /** 用已有文件构造成功结果（含组件） */
    private AIToolResult buildFileResult(File f) {
        try {
            Uri contentUri = androidx.core.content.FileProvider.getUriForFile(
                    context, "com.oilquiz.app.fileprovider", f);
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("filePath", f.getAbsolutePath());
            result.put("contentUri", contentUri.toString());
            result.put("size", f.length());
            result.put("message", "文件已生成保存到工作区");
            AIToolResult toolResult = AIToolResult.success(result);
            try {
                org.json.JSONObject props = new org.json.JSONObject();
                props.put("columns", 1);
                org.json.JSONArray images = new org.json.JSONArray();
                images.put(contentUri.toString());
                props.put("images", images);
                toolResult.withComponent(ComponentData.of("image_grid", props));
            } catch (Exception ignored) {
            }
            return toolResult;
        } catch (Exception e) {
            return AIToolResult.fail("文件结果构建失败: " + e.getMessage());
        }
    }

    // ==================== 费用确认 ====================

    /** 弹原生确认框询问是否接受视频生成费用（阻塞等待用户点击，最多 20 秒） */
    private boolean confirmVideoCost(String model, String size, int duration) {
        try {
            String cost = estimateVideoCost(model, size, duration);
            com.oilquiz.app.ai.python.PythonToolManager ptm =
                    com.oilquiz.app.ai.python.PythonToolManager.getInstance(context);
            java.util.Map<String, Object> params = new java.util.HashMap<>();
            params.put("action", "create");
            params.put("component_type", "dialog");
            params.put("dialog_type", "confirm");
            params.put("title", "💰 确认视频生成费用");
            params.put("message", "模型：" + model + "\n尺寸：" + size + "（" + duration + "秒）\n"
                    + "预计费用：约 ¥" + cost + "\n\n确认生成？");
            java.util.Map<String, Object> r = ptm.createUiComponent("dialog", params);
            Object cid = r != null ? r.get("component_id") : null;
            if (cid == null) {
                Log.w(TAG, "无法弹出费用确认框，按已确认处理");
                return true;
            }
            java.util.Map<String, Object> gr = ptm.getUiComponentResult(String.valueOf(cid), 20, false);
            Object res = gr != null ? gr.get("result") : null;
            String s = res != null ? String.valueOf(res) : "";
            return "positive".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s)
                    || "ok".equalsIgnoreCase(s);
        } catch (Throwable t) {
            Log.w(TAG, "费用确认异常，按已确认处理: " + t.getMessage());
            return true;
        }
    }

    /** 估算视频费用（元）：turbo 约 0.06/秒（5秒≈0.3）；plus 约 0.5/秒，大尺寸上浮 */
    private String estimateVideoCost(String model, String size, int duration) {
        double perSecond;
        if (model.toLowerCase().contains("turbo")) {
            perSecond = 0.06;
        } else {
            perSecond = 0.5;
            if (size.contains("1080*1920") || size.contains("1920*1080")
                    || size.contains("1632*1248") || size.contains("1248*1632")) {
                perSecond = 1.8;
            } else if (size.contains("1440*1440")) {
                perSecond = 1.2;
            }
        }
        return String.format(java.util.Locale.US, "%.1f", perSecond * duration);
    }

    // ==================== 查询（image/video 通用） ====================

    private AIToolResult handleQuery(Map<String, Object> parameters, String apiKey) {
        String taskId = strParam(parameters, "task_id", "");
        String type = strParam(parameters, "type", "video");
        if (taskId.isEmpty()) {
            return AIToolResult.fail("query 操作必须提供 task_id");
        }
        try {
            // 端口跟随配置（api_url 参数 > 配置端点 > 默认公共），查询任务同样走用户端点
            com.alibaba.dashscope.utils.Constants.baseHttpApiUrl = resolveApiV1Base(parameters);
            String status;
            String url = null;
            String msg = "";
            if ("image".equalsIgnoreCase(type)) {
                com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisResult r =
                        new com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesis().fetch(taskId, apiKey);
                if (r == null) return AIToolResult.fail("查询失败（无返回）");
                status = r.getOutput() != null ? r.getOutput().getTaskStatus() : "";
                msg = r.getMessage() != null ? r.getMessage() : "";
                if ("SUCCEEDED".equalsIgnoreCase(status) && r.getOutput() != null
                        && r.getOutput().getResults() != null && !r.getOutput().getResults().isEmpty()) {
                    url = r.getOutput().getResults().get(0).get("url");
                }
            } else {
                com.alibaba.dashscope.aigc.videosynthesis.VideoSynthesisResult r =
                        new com.alibaba.dashscope.aigc.videosynthesis.VideoSynthesis().fetch(taskId, apiKey);
                if (r == null) return AIToolResult.fail("查询失败（无返回）");
                status = r.getOutput() != null ? r.getOutput().getTaskStatus() : "";
                msg = r.getMessage() != null ? r.getMessage() : "";
                if ("SUCCEEDED".equalsIgnoreCase(status) && r.getOutput() != null) {
                    url = r.getOutput().getVideoUrl();
                }
            }
            if ("SUCCEEDED".equalsIgnoreCase(status) && url != null && !url.isEmpty()) {
                return downloadResult(url, taskId, "generated_media_" + taskId, "image".equalsIgnoreCase(type));
            }
            if ("FAILED".equalsIgnoreCase(status) || "CANCELED".equalsIgnoreCase(status)) {
                return AIToolResult.fail("任务失败: " + (msg.isEmpty() ? status : msg));
            }
            // 处理中
            Map<String, Object> info = new HashMap<>();
            info.put("task_id", taskId);
            info.put("type", type);
            info.put("status", status);
            info.put("message", "任务处理中，请稍后再查询");
            return new AIToolResult("⏳ 任务处理中（task_id=" + taskId + "，状态=" + status + "）\n"
                    + "稍后再调用 dashscope_media action=query task_id=" + taskId + " 查询", info, true);
        } catch (Exception e) {
            Log.e(TAG, "查询失败: " + e.getMessage(), e);
            return AIToolResult.fail("查询失败: " + e.getMessage());
        }
    }

    // ==================== 动态模型查询 ====================

    /**
     * 动态获取账号可用的文生图/文生视频模型（compatible-mode/v1/models，免费用）。
     * 端口：优先用用户配置的百炼系端点（专属空间 MaaS / 公共），未配置则默认公共端点。
     * 注意：百炼 compatible-mode models 列表**不含 t2v/t2i 模型**（视频模型只能走
     * video-synthesis 原生接口，列表查不到；t2i 同理）→ 内置已知模型兜底 + API 列表补充。
     * 只走多模态接口的 qwen-image/wan2.7-image 不属于本工具（text2image/video-synthesis），不列入。
     */
    private AIToolResult handleModels(String apiKey, String baseUrl) {
        try {
            java.util.LinkedHashSet<String> imageModels = new java.util.LinkedHashSet<>(
                    java.util.Arrays.asList("wan2.2-t2i-flash", "wan2.2-t2i-plus",
                            "qwen-image-max", "qwen-image-plus"));
            java.util.LinkedHashSet<String> videoModels = new java.util.LinkedHashSet<>(
                    java.util.Arrays.asList("wan2.1-t2v-turbo", "wan2.2-t2v-plus"));
            String base = (baseUrl != null && !baseUrl.trim().isEmpty())
                    ? baseUrl.trim().replaceAll("/+$", "") : "https://dashscope.aliyuncs.com/compatible-mode/v1";
            String url = base + "/models";
            Request request = NetworkUtil.createApiRequestBuilder(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .get()
                    .build();
            try (Response response = MEDIA_CLIENT.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    org.json.JSONObject j = new org.json.JSONObject(response.body().string());
                    org.json.JSONArray data = j.optJSONArray("data");
                    if (data != null) {
                        for (int i = 0; i < data.length(); i++) {
                            org.json.JSONObject m = data.optJSONObject(i);
                            if (m == null) continue;
                            String id = m.optString("id", "");
                            if (id.isEmpty()) continue;
                            String low = id.toLowerCase();
                            // 视频：t2v / video / seedance / wanx 视频 / dream（排除 seedream 归图片）
                            if (low.contains("t2v") || low.contains("video") || low.contains("seedance")
                                    || (low.contains("dream") && !low.contains("seedream"))) {
                                videoModels.add(id);
                            } else if (low.contains("t2i") || low.contains("image")
                                    || low.contains("seedream") || low.contains("seed")
                                    || low.contains("flux") || low.contains("dall")
                                    || low.contains("wanx")) {
                                imageModels.add(id);
                            }
                        }
                    }
                }
            }
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("count", imageModels.size() + videoModels.size());
            result.put("image_models",
                    new org.json.JSONArray(new java.util.ArrayList<>(imageModels)).toString());
            result.put("video_models",
                    new org.json.JSONArray(new java.util.ArrayList<>(videoModels)).toString());
            result.put("message", "文生图 " + imageModels.size() + " 个，文生视频 " + videoModels.size()
                    + " 个（qwen-image/seed 系列归文生图，t2v/video/seedance 归文生视频）");
            return AIToolResult.success(result);
        } catch (Exception e) {
            Log.e(TAG, "获取模型列表失败: " + e.getMessage(), e);
            // 列表接口失败仍返回内置清单，保证 UI/Agent 可用
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("image_models", "[\"wan2.2-t2i-flash\",\"wan2.2-t2i-plus\",\"qwen-image-max\",\"qwen-image-plus\"]");
            result.put("video_models", "[\"wan2.1-t2v-turbo\",\"wan2.2-t2v-plus\"]");
            result.put("message", "模型列表接口暂不可用，已用内置清单（wan2.2-t2i-flash/plus, qwen-image-max/plus, wan2.1-t2v-turbo）");
            return AIToolResult.success(result);
        }
    }

    // ==================== 下载与结果 ====================

    /** 下载生成结果到工作区 files/，返回文件 + 组件 */
    private AIToolResult downloadResult(String url, String taskId, String nameHint, boolean isImage) {
        try {
            if (url == null || url.isEmpty()) {
                return AIToolResult.fail("结果 URL 为空");
            }
            AILogger.i(TAG, "下载生成结果: " + url);
            Request request = NetworkUtil.createApiRequestBuilder(url)
                    .get()
                    .build();
            try (Response response = MEDIA_CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return AIToolResult.fail("下载失败 HTTP " + response.code());
                }
                okhttp3.ResponseBody body = response.body();
                if (body == null) return AIToolResult.fail("下载失败（无响应体）");

                String contentType = body.contentType() != null ? body.contentType().toString() : "";
                String ext = isImage ? ".jpg" : ".mp4";
                if (isImage) {
                    if (contentType.toLowerCase().contains("png")) ext = ".png";
                    else if (contentType.toLowerCase().contains("webp")) ext = ".webp";
                }
                String base = (taskId != null && !taskId.isEmpty()) ? taskId
                        : String.valueOf(System.currentTimeMillis());
                File dir = com.oilquiz.app.ai.agent.online.AgentWorkspace.getInstance(context).getFilesDir();
                if (!dir.exists()) dir.mkdirs();
                File target = new File(dir, base + ext);

                try (InputStream input = body.byteStream();
                     FileOutputStream output = new FileOutputStream(target)) {
                    byte[] buffer = new byte[8192];
                    long total = 0;
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        total += read;
                        if (total > MAX_FILE_BYTES) {
                            output.close();
                            target.delete();
                            return AIToolResult.fail("文件超过 200MB 上限");
                        }
                        output.write(buffer, 0, read);
                    }
                }
                if (!target.exists() || target.length() == 0) {
                    target.delete();
                    return AIToolResult.fail("下载结果为空文件");
                }

                Uri contentUri = androidx.core.content.FileProvider.getUriForFile(
                        context, "com.oilquiz.app.fileprovider", target);
                Map<String, Object> result = new HashMap<>();
                result.put("status", "success");
                result.put("filePath", target.getAbsolutePath());
                result.put("contentUri", contentUri.toString());
                result.put("size", target.length());
                result.put("message", (isImage ? "图片" : "视频") + "已生成保存到工作区");
                AIToolResult toolResult = AIToolResult.success(result);
                try {
                    if (isImage) {
                        JSONObject props = new JSONObject();
                        props.put("columns", 1);
                        JSONArray images = new JSONArray();
                        images.put(contentUri.toString());
                        props.put("images", images);
                        toolResult.withComponent(ComponentData.of("image_grid", props));
                    } else {
                        JSONObject props = new JSONObject();
                        props.put("name", target.getName());
                        props.put("size", formatSize(target.length()));
                        props.put("type", "mp4");
                        props.put("path", target.getAbsolutePath());
                        props.put("uri", contentUri.toString());
                        toolResult.withComponent(ComponentData.of("file_card", props));
                    }
                } catch (Exception ignored) {
                }
                return toolResult;
            }
        } catch (Exception e) {
            Log.e(TAG, "下载失败: " + e.getMessage(), e);
            return AIToolResult.fail("下载失败: " + e.getMessage());
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 取百炼 API Key（优先级）：
     * 1) 应用配置表中百炼系配置（dashscope.aliyuncs.com 或 maas.aliyuncs.com 专属空间，Key 通用）
     * 2) 当前在线模型 / 第一个可用配置
     * 3) 手机 video_api_config.json 兜底
     * 修复1：用户在线模型为 DeepSeek 时会把 DeepSeek Key 发往百炼 → 401。
     * 修复2：百炼专属空间是 maas.aliyuncs.com，不含 "dashscope" 字样，
     *        需按百炼系（dashscope OR maas）识别，不能只看 dashscope。
     */
    private String resolveDefaultApiKey() {
        try {
            com.oilquiz.app.ai.model.OnlineModelManager m =
                    com.oilquiz.app.ai.model.OnlineModelManager.getInstance(context);
            // 1) 百炼系配置（公共端点或专属空间）
            com.oilquiz.app.ai.model.OnlineModelManager.OnlineModelConfig bailian =
                    m.getBailianConfig();
            if (bailian != null && bailian.apiKey != null && !bailian.apiKey.isEmpty()) {
                return bailian.apiKey.trim();
            }
            // 2) 当前在线模型 → 第一个可用
            com.oilquiz.app.ai.model.OnlineModelManager.OnlineModelConfig cfg = m.getActiveModel();
            if (cfg == null || cfg.apiKey == null || cfg.apiKey.isEmpty()) {
                cfg = m.getFirstAvailableOnlineModel();
            }
            if (cfg != null && cfg.apiKey != null && !cfg.apiKey.isEmpty()) {
                return cfg.apiKey.trim();
            }
        } catch (Throwable t) {
            Log.w(TAG, "解析默认API Key失败: " + t.getMessage());
        }
        // 3) 手机 video_api_config.json 兜底（用户实测百炼 Key）
        try {
            java.io.File[] candidates = {
                    new java.io.File("/storage/emulated/0/Download/OilQuiz/video_api_config.json"),
                    new java.io.File("/storage/emulated/0/Download/OilQuiz/agent_workspace/files/video_api_config.json"),
            };
            for (java.io.File f : candidates) {
                if (f.exists()) {
                    org.json.JSONObject jo = new org.json.JSONObject(
                            readFileText(f));
                    String key = jo.optString("api_key", "");
                    if (!key.isEmpty()) {
                        return key.trim();
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "读取 video_api_config.json 失败: " + t.getMessage());
        }
        return null;
    }

    /** 百炼系端点根（models 列表查询用）：用户配置的专属空间(MaaS)/公共端点，未配置则默认公共 */
    private String resolveBailianBaseUrl() {
        return resolveCompatBase(null);
    }

    /** compatible-mode 基址：api_url 参数 > 配置端点（专属空间/公共）> 默认公共 */
    private String resolveCompatBase(Map<String, Object> parameters) {
        String explicit = parameters != null && parameters.get("api_url") != null
                ? String.valueOf(parameters.get("api_url")).trim() : "";
        if (!explicit.isEmpty()) {
            return explicit.replaceAll("/+$", "");
        }
        try {
            com.oilquiz.app.ai.model.OnlineModelManager m =
                    com.oilquiz.app.ai.model.OnlineModelManager.getInstance(context);
            com.oilquiz.app.ai.model.OnlineModelManager.OnlineModelConfig bailian =
                    m.getBailianConfig();
            if (bailian != null && bailian.apiUrl != null && !bailian.apiUrl.trim().isEmpty()) {
                return bailian.apiUrl.trim().replaceAll("/+$", "");
            }
        } catch (Throwable t) {
            Log.w(TAG, "解析百炼端点失败: " + t.getMessage());
        }
        return "https://dashscope.aliyuncs.com/compatible-mode/v1";
    }

    /** 原生 API 根（SDK 用，host + /api/v1）：api_url 参数 > 配置端点 > 默认公共 */
    private String resolveApiV1Base(Map<String, Object> parameters) {
        String compatBase = resolveCompatBase(parameters);
        try {
            java.net.URI uri = new java.net.URI(compatBase);
            String scheme = uri.getScheme() != null ? uri.getScheme() : "https";
            String host = uri.getHost();
            if (host != null && !host.isEmpty()) {
                return scheme + "://" + host + "/api/v1";
            }
        } catch (Exception e) {
            Log.w(TAG, "解析原生端点失败，用默认公共: " + e.getMessage());
        }
        return "https://dashscope.aliyuncs.com/api/v1";
    }

    private String readFileText(java.io.File f) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private String strParam(Map<String, Object> p, String key, String def) {
        Object v = p.get(key);
        return v == null ? def : String.valueOf(v).trim();
    }

    private int intParam(Map<String, Object> p, String key, int def) {
        Object v = p.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v != null) {
            try {
                return Integer.parseInt(String.valueOf(v).trim());
            } catch (Exception ignored) {
            }
        }
        return def;
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0);
        }
        return String.format(java.util.Locale.US, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0);
    }
}
