package com.oilquiz.app.ai.sessionlog;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 会话事件载荷构造器 —— 统一各事件类型的 JSON payload 结构。
 *
 * <p>纯逻辑、无 Android 依赖，可单元测试。所有 put 走 {@link #safePut}
 * 捕获 JSONException（本项目 org.json 为受检异常），构造不失败。
 */
public final class SessionEventPayload {

    private SessionEventPayload() {
    }

    private static void safePut(JSONObject o, String key, Object value) {
        try {
            o.put(key, value);
        } catch (JSONException e) {
            // key/value 均为合法 JSON 类型，理论上不触发；忽略
        }
    }

    /** 用户消息：{"role":"user","content":...} */
    public static JSONObject userMessage(String content) {
        JSONObject o = new JSONObject();
        safePut(o, "role", "user");
        safePut(o, "content", content == null ? "" : content);
        return o;
    }

    /** 助手消息：{"role":"assistant","content":...} */
    public static JSONObject assistantMessage(String content) {
        JSONObject o = new JSONObject();
        safePut(o, "role", "assistant");
        safePut(o, "content", content == null ? "" : content);
        return o;
    }

    /** 思考链：{"kind":"thinking","content":...} */
    public static JSONObject thinking(String content) {
        JSONObject o = new JSONObject();
        safePut(o, "kind", "thinking");
        safePut(o, "content", content == null ? "" : content);
        return o;
    }

    /** 工具调用开始：{"toolCallId":...,"name":...,"args":...} */
    public static JSONObject toolCallStarted(String toolCallId, String name, String args) {
        JSONObject o = new JSONObject();
        safePut(o, "toolCallId", toolCallId == null ? "" : toolCallId);
        safePut(o, "name", name == null ? "" : name);
        safePut(o, "args", args == null ? "" : args);
        return o;
    }

    /** 工具调用完成：{"toolCallId":...,"name":...,"success":...,"result":...} */
    public static JSONObject toolCallCompleted(String toolCallId, String name,
                                               boolean success, String result) {
        JSONObject o = new JSONObject();
        safePut(o, "toolCallId", toolCallId == null ? "" : toolCallId);
        safePut(o, "name", name == null ? "" : name);
        safePut(o, "success", success);
        safePut(o, "result", result == null ? "" : result);
        return o;
    }

    /** 错误：{"error":...} */
    public static JSONObject error(String message) {
        JSONObject o = new JSONObject();
        safePut(o, "error", message == null ? "" : message);
        return o;
    }

    /** 模型信息（可选附在 TURN_STARTED）：{"model":...} */
    public static JSONObject model(String modelId) {
        JSONObject o = new JSONObject();
        safePut(o, "model", modelId == null ? "" : modelId);
        return o;
    }

    /** 会话结束原因：{"reason":...} */
    public static JSONObject endReason(String reason) {
        JSONObject o = new JSONObject();
        safePut(o, "reason", reason == null ? "" : reason);
        return o;
    }
}
