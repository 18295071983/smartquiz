package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.agent.online.OnlineToolManager;
import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;
import com.oilquiz.app.ai.agent.online.OnlineToolRegistry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表（类 MCP 协议）：Agent 可自行查找可用工具与参数 schema，按需获取，避免全量注入浪费 token。
 *
 * 用法（类比 MCP tools/list + tools/call）：
 *  - tool_registry(action="list")            → 全部工具名+简短描述（轻量）
 *  - tool_registry(action="search", keyword="ui") → 按关键词找工具
 *  - tool_registry(action="get", tool="ui_component") → 单个工具完整参数 schema
 *
 * 与系统提示词配合：模型先 list 知道有哪些工具，需要细节时再 get 单个 schema，
 * 主流程工具定义按消息意图裁剪注入，进一步省 token。
 */
@Tool(
    value = "tool_registry",
    description = "工具注册表(MCP式工具发现)：列出可用工具(list)、按关键词搜索工具(search)、获取单个工具完整参数schema(get)。模型不确定有哪些工具或需要某工具详细参数时调用，避免猜测工具名/参数。",
    category = "meta",
    actions = {
        @Action(name = "list", description = "列出全部可用工具（名称+简短描述）"),
        @Action(name = "search", description = "按关键词搜索工具"),
        @Action(name = "get", description = "获取单个工具的完整参数schema")
    },
    params = {
        @Param(name = "action", type = "string", description = "list/search/get", required = true),
        @Param(name = "keyword", type = "string", description = "搜索关键词(仅search用)", required = false),
        @Param(name = "tool", type = "string", description = "工具名(仅get用)", required = false)
    }
)
public class ToolRegistryTool implements AITool {
    private static final String TAG = "ToolRegistryTool";
    private final Context context;
    private final OnlineToolManager toolManager;

    public ToolRegistryTool(Context context) {
        this.context = context.getApplicationContext();
        this.toolManager = OnlineToolManager.getInstance(context);
    }

    @Override
    public String getName() {
        return "tool_registry";
    }

    @Override
    public String getDescription() {
        return "工具注册表(MCP式工具发现)：列出可用工具(list)、按关键词搜索工具(search)、获取单个工具完整参数schema(get)。模型不确定有哪些工具或需要某工具详细参数时调用，避免猜测工具名/参数。";
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            Object actionObj = parameters.get("action");
            String action = actionObj != null ? actionObj.toString() : "list";

            switch (action) {
                case "list":
                    return listTools();
                case "search": {
                    Object kw = parameters.get("keyword");
                    String keyword = kw != null ? kw.toString().toLowerCase() : "";
                    return searchTools(keyword);
                }
                case "get": {
                    Object toolObj = parameters.get("tool");
                    if (toolObj == null) {
                        return new AIToolResult("缺少参数: tool（要查的工具名）", parameters);
                    }
                    return getToolSchema(toolObj.toString());
                }
                default:
                    return new AIToolResult("未知操作: " + action + "（支持 list/search/get）", parameters);
            }
        } catch (Exception e) {
            Log.e(TAG, "工具注册表执行失败: " + e.getMessage(), e);
            return new AIToolResult("工具注册表执行失败: " + e.getMessage(), parameters);
        }
    }

    private AIToolResult listTools() {
        try {
            List<Map<String, Object>> descriptions = com.oilquiz.app.ai.tool.AIToolManager
                    .getInstance(context).getToolDescriptions();
            JSONArray tools = new JSONArray();
            for (Map<String, Object> d : descriptions) {
                JSONObject t = new JSONObject();
                t.put("name", d.get("name"));
                Object desc = d.get("description");
                t.put("description", desc != null ? String.valueOf(desc) : "");
                tools.put(t);
            }
            Map<String, Object> result = new HashMap<>();
            result.put("count", tools.length());
            result.put("tools", tools.toString());
            return new AIToolResult(result, null);
        } catch (Exception e) {
            return new AIToolResult("列出工具失败: " + e.getMessage(), null);
        }
    }

    private AIToolResult searchTools(String keyword) {
        try {
            List<Map<String, Object>> descriptions = com.oilquiz.app.ai.tool.AIToolManager
                    .getInstance(context).getToolDescriptions();
            JSONArray tools = new JSONArray();
            for (Map<String, Object> d : descriptions) {
                String name = String.valueOf(d.get("name"));
                String desc = d.get("description") != null ? String.valueOf(d.get("description")) : "";
                if (keyword.isEmpty() || name.toLowerCase().contains(keyword)
                        || desc.toLowerCase().contains(keyword)) {
                    JSONObject t = new JSONObject();
                    t.put("name", name);
                    t.put("description", desc);
                    tools.put(t);
                }
            }
            Map<String, Object> result = new HashMap<>();
            result.put("count", tools.length());
            result.put("tools", tools.toString());
            return new AIToolResult(result, null);
        } catch (Exception e) {
            return new AIToolResult("搜索工具失败: " + e.getMessage(), null);
        }
    }

    private AIToolResult getToolSchema(String toolName) {
        try {
            com.oilquiz.app.ai.tool.openai.ToolDefinition def = com.oilquiz.app.ai.tool.AIToolManager
                    .getInstance(context).getToolDefinition(toolName);
            if (def == null) {
                return new AIToolResult("工具不存在: " + toolName, null);
            }
            JSONObject schema = new JSONObject();
            schema.put("name", def.getName());
            schema.put("description", def.getDescription());
            JSONObject parameters = new JSONObject();
            if (def.getParameters() != null) {
                for (com.oilquiz.app.ai.tool.openai.ParamDefinition p : def.getParameters()) {
                    JSONObject prop = new JSONObject();
                    prop.put("type", p.getType() != null ? p.getType() : "string");
                    prop.put("description", p.getDescription() != null ? p.getDescription() : "");
                    prop.put("required", p.isRequired());
                    if (p.getDefaultValue() != null) prop.put("default", p.getDefaultValue());
                    parameters.put(p.getName(), prop);
                }
            }
            schema.put("parameters", parameters);
            Map<String, Object> result = new HashMap<>();
            result.put("tool", toolName);
            result.put("schema", schema.toString());
            return new AIToolResult(result, null);
        } catch (Exception e) {
            return new AIToolResult("获取工具schema失败: " + e.getMessage(), null);
        }
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> desc = new HashMap<>();
        desc.put("action", "list(列出)/search(搜索)/get(取schema)");
        desc.put("keyword", "搜索关键词(search用)");
        desc.put("tool", "工具名(get用)");
        return desc;
    }
}
