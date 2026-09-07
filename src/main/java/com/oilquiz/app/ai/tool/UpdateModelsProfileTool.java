package com.oilquiz.app.ai.tool;

import android.content.Context;

import com.oilquiz.app.ai.model.OnlineModelProfile;
import com.oilquiz.app.ai.tool.annotation.Tool;

import java.util.HashMap;
import java.util.Map;

/**
 * 更新模型上下文数据表工具：让 Agent 用官方 API 文档查证后更新
 * assets/models_profile.json 的覆盖表（写入 filesDir，优先于内置表加载）。
 *
 * 参数：
 * - json: 完整的新数据表 JSON（结构同 assets/models_profile.json，
 *   顶层含 version/updated/models[]，每条含 match/contextWindow/provider/source/date）
 *
 * 用法（Agent）：
 * 1. 用 network_search 查证某模型官方文档的上下文窗口
 * 2. 调本工具传入完整新表 JSON（可先调 get_models_profile 读当前表，改后回传）
 * 3. 返回更新结果（条目数/版本），后续匹配立即生效
 */
@Tool(value = "update_models_profile", category = "meta")
public class UpdateModelsProfileTool implements AITool {

    private static final String TAG = "UpdateModelsProfileTool";
    private final Context context;

    public UpdateModelsProfileTool() {
        this.context = com.oilquiz.app.SmartQuizApplication.getInstance() != null
                ? com.oilquiz.app.SmartQuizApplication.getInstance().getApplicationContext()
                : null;
    }

    public UpdateModelsProfileTool(Context context) {
        this.context = context;
    }

    @Override
    public String getName() {
        return "update_models_profile";
    }

    @Override
    public String getDescription() {
        return "更新模型上下文窗口数据表（用官方文档查证的值）。参数 json=完整新表JSON。" +
                "条目结构: {match: 模型名关键词, contextWindow: 窗口tokens, provider: 服务商, " +
                "apiUrl?: 可选端点关键词(有值时仅该端点生效,用于聚合商限窗场景), source?: 官方文档URL, date?: 查证日期}。" +
                "校验通过立即生效并持久化。Agent 可先用 network_search 查证官方文档、get_models_profile 读当前表再更新。";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("json", "完整的新数据表 JSON（必须含 models 数组，每条 match+contextWindow 必填，apiUrl 可选）");
        return params;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            Object jsonObj = parameters.get("json");
            if (jsonObj == null) {
                return AIToolResult.fail("缺少 json 参数：请传入完整的新数据表 JSON");
            }
            // 非 String 的 JSON 对象用 Gson 序列化（String.valueOf(Map) 会得到无效的 Java toString）
            String json;
            if (jsonObj instanceof String) {
                json = (String) jsonObj;
            } else {
                try {
                    json = new com.google.gson.Gson().toJson(jsonObj);
                } catch (Exception e) {
                    return AIToolResult.fail("json 参数序列化失败: " + e.getMessage());
                }
            }
            if (context == null) {
                return AIToolResult.fail("应用上下文不可用，无法更新");
            }
            boolean ok = OnlineModelProfile.updateFromJson(context, json);
            if (!ok) {
                return AIToolResult.fail("数据表格式非法：必须是合法 JSON，且 models 数组非空" +
                        "（每条至少含 match 和 contextWindow>0）。已放弃本次更新，原表保持不变。");
            }
            Map<String, Object> info = new HashMap<>();
            info.put("version", OnlineModelProfile.getTableVersion(context));
            info.put("entries", OnlineModelProfile.getTableSize(context));
            return AIToolResult.success(
                    "数据表更新成功：版本=" + OnlineModelProfile.getTableVersion(context)
                            + "，条目=" + OnlineModelProfile.getTableSize(context) + "，立即生效", info);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return AIToolResult.fail("更新数据表失败: " + msg);
        }
    }
}
