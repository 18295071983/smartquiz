package com.oilquiz.app.ai.tool;

import android.content.Context;

import com.oilquiz.app.ai.model.OnlineModelProfile;

import java.util.HashMap;
import java.util.Map;

/**
 * 读取模型上下文数据表工具：返回当前生效的数据表 JSON（含版本/条目数），
 * 供 Agent 查证官方文档后基于当前表修改，再调 update_models_profile 回写。
 *
 * 参数：无（可选 query: 模型名关键词，只返回匹配该模型的条目做快速查询）
 */
public class GetModelsProfileTool implements AITool {

    private static final String TAG = "GetModelsProfileTool";

    public GetModelsProfileTool() {
    }

    public GetModelsProfileTool(Context context) {
    }

    @Override
    public String getName() {
        return "get_models_profile";
    }

    @Override
    public String getDescription() {
        return "读取模型上下文窗口数据表（当前生效版本，含官方来源）。" +
                "可选参数 query=模型名关键词，只返回匹配条目。" +
                "查证官方文档后可基于本表修改，再用 update_models_profile 更新。";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("query", "可选：模型名关键词，只返回匹配该关键词的条目（如 deepseek、glm-4）");
        return params;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            Context context = com.oilquiz.app.SmartQuizApplication.getInstance() != null
                    ? com.oilquiz.app.SmartQuizApplication.getInstance().getApplicationContext()
                    : null;
            if (context == null) {
                return AIToolResult.fail("应用上下文不可用，无法读取");
            }
            String version = OnlineModelProfile.getTableVersion(context);
            int size = OnlineModelProfile.getTableSize(context);
            String query = parameters.get("query") != null
                    ? String.valueOf(parameters.get("query")).trim().toLowerCase() : null;

            Map<String, Object> info = new HashMap<>();
            info.put("version", version);
            info.put("totalEntries", size);

            if (query != null && !query.isEmpty()) {
                // 只返回匹配条目（供 Agent 快速查证单模型）
                StringBuilder sb = new StringBuilder();
                sb.append("数据表版本: ").append(version).append("，总条目: ").append(size).append("\n\n");
                sb.append("匹配 \"").append(query).append("\" 的条目：\n");
                int count = 0;
                int parseFailed = 0;
                String json = OnlineModelProfile.getTableJson(context);
                if (json != null) {
                    try {
                        com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
                        com.google.gson.JsonArray arr = root.getAsJsonArray("models");
                        for (int i = 0; i < arr.size(); i++) {
                            // 单条容错：畸形条目跳过并计数，不中断整个查询
                            try {
                                com.google.gson.JsonObject o = arr.get(i).getAsJsonObject();
                                String match = o.has("match") ? o.get("match").getAsString() : "";
                                if (match.toLowerCase().contains(query)) {
                                    count++;
                                    sb.append("- ").append(match).append(" | 窗口 ")
                                            .append(o.get("contextWindow").getAsInt())
                                            .append(" | ").append(o.has("provider") ? o.get("provider").getAsString() : "-")
                                            .append(" | ").append(o.has("source") ? o.get("source").getAsString() : "-")
                                            .append("\n");
                                }
                            } catch (Exception entryErr) {
                                parseFailed++;
                            }
                        }
                    } catch (Exception e) {
                        return AIToolResult.fail("数据表 JSON 解析失败: " + e.getMessage());
                    }
                }
                if (count == 0) {
                    sb.append("（无匹配条目" + (parseFailed > 0 ? "，另有 " + parseFailed + " 条解析失败" : "")
                            + "——该模型未收录，可查证官方文档后用 update_models_profile 添加）\n");
                }
                info.put("matched", count);
                if (parseFailed > 0) {
                    info.put("parseFailed", parseFailed);
                }
                return AIToolResult.success(sb.toString(), info);
            }

            info.put("json", OnlineModelProfile.getTableJson(context));
            return AIToolResult.success(
                    "数据表版本: " + version + "，条目: " + size
                            + "（完整 JSON 见 info.json，可修改后调 update_models_profile 回写）", info);
        } catch (Exception e) {
            return AIToolResult.fail("读取数据表失败: " + e.getMessage());
        }
    }
}
