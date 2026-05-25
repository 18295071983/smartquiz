package com.oilquiz.app.ai.mcp;

import androidx.annotation.NonNull;

import com.oilquiz.app.ai.tool.AIToolResult;
import com.oilquiz.app.util.AILogger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * MCP 工具封装
 *
 * 将 MCP 服务器的工具封装为应用内可调用的工具
 */
public class MCPTool {
    private static final String TAG = "MCPTool";

    private final String name;
    private final String description;
    private final JSONObject inputSchema;
    private final MCPServer server;

    public MCPTool(@NonNull JSONObject toolDef, @NonNull MCPServer server) throws JSONException {
        this.name = toolDef.getString("name");
        this.description = toolDef.optString("description", "");
        this.inputSchema = toolDef.optJSONObject("inputSchema");
        this.server = server;
    }

    /**
     * 执行工具调用
     */
    public CompletableFuture<AIToolResult> execute(Map<String, Object> arguments) {
        // 转换参数格式
        JSONObject params = new JSONObject();
        try {
            for (Map.Entry<String, Object> entry : arguments.entrySet()) {
                params.put(entry.getKey(), entry.getValue());
            }
        } catch (JSONException e) {
            return CompletableFuture.completedFuture(
                AIToolResult.fail("Failed to build arguments: " + e.getMessage())
            );
        }

        return server.callTool(name, params)
            .thenApply(result -> {
                try {
                    return parseResult(result);
                } catch (JSONException e) {
                    return AIToolResult.fail("Failed to parse result: " + e.getMessage());
                }
            })
            .exceptionally(throwable -> {
                AILogger.e(TAG, "Tool execution failed: " + name, throwable);
                return AIToolResult.fail("Tool execution failed: " + throwable.getMessage());
            });
    }

    /**
     * 解析 MCP 工具结果为 AIToolResult
     */
    private AIToolResult parseResult(JSONObject result) throws JSONException {
        JSONArray content = result.optJSONArray("content");
        if (content == null || content.length() == 0) {
            return AIToolResult.success("");
        }

        StringBuilder textBuilder = new StringBuilder();
        boolean isError = result.optBoolean("isError", false);

        for (int i = 0; i < content.length(); i++) {
            JSONObject item = content.getJSONObject(i);
            String type = item.optString("type", "text");

            switch (type) {
                case "text":
                    textBuilder.append(item.optString("text", ""));
                    break;
                case "image":
                    // 图片资源暂不处理，记录日志
                    AILogger.d(TAG, "Image content received, not supported yet");
                    break;
                case "resource":
                    // 嵌入式资源
                    JSONObject resource = item.optJSONObject("resource");
                    if (resource != null) {
                        String text = resource.optString("text", "");
                        if (!text.isEmpty()) {
                            textBuilder.append(text);
                        }
                    }
                    break;
            }
        }

        String text = textBuilder.toString();
        if (isError) {
            return AIToolResult.fail(text);
        }

        return AIToolResult.success(text);
    }

    /**
     * 转换为 AITool 可用的参数定义
     */
    public Map<String, Object> toAIToolDefinition() {
        Map<String, Object> def = new HashMap<>();
        def.put("name", name);
        def.put("description", description);

        // 转换 JSON Schema
        if (inputSchema != null) {
            Map<String, Object> parameters = jsonToMap(inputSchema);
            def.put("parameters", parameters);
        }

        return def;
    }

    /**
     * 获取工具签名（用于显示）
     */
    public String getSignature() {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append("(");

        if (inputSchema != null) {
            JSONObject properties = inputSchema.optJSONObject("properties");
            JSONArray required = inputSchema.optJSONArray("required");

            if (properties != null) {
                List<String> params = new ArrayList<>();
                Iterator<String> keys = properties.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    boolean isRequired = required != null && required.toString().contains(key);
                    params.add(key + (isRequired ? "" : "?"));
                }
                sb.append(String.join(", ", params));
            }
        }

        sb.append(")");
        return sb.toString();
    }

    private Map<String, Object> jsonToMap(JSONObject json) {
        Map<String, Object> map = new HashMap<>();
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = json.opt(key);
            if (value instanceof JSONObject) {
                map.put(key, jsonToMap((JSONObject) value));
            } else if (value instanceof JSONArray) {
                map.put(key, jsonToList((JSONArray) value));
            } else {
                map.put(key, value);
            }
        }
        return map;
    }

    private List<Object> jsonToList(JSONArray array) {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Object value = array.opt(i);
            if (value instanceof JSONObject) {
                list.add(jsonToMap((JSONObject) value));
            } else if (value instanceof JSONArray) {
                list.add(jsonToList((JSONArray) value));
            } else {
                list.add(value);
            }
        }
        return list;
    }

    // Getters
    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getServerName() {
        return server.getServerName();
    }

    @Override
    public String toString() {
        return "MCPTool{" +
            "name='" + name + '\'' +
            ", server='" + server.getServerName() + '\'' +
            '}';
    }
}
