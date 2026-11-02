package com.oilquiz.app.ai.agent.online.mcp;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * JSON-RPC 2.0 消息构造与解析工具（MCP 协议底座）。
 *
 * <p>MCP 的每一次客户端→服务端交互都是一条 JSON-RPC 消息：带 {@code id} 的请求、
 * 不带 {@code id} 的通知、以及带 {@code id} 的响应（含 {@code result} 或 {@code error}）。</p>
 */
public final class McpJsonRpc {

    private McpJsonRpc() {}

    /** 构造一个带 id 的请求（id 由调用方分配，保证会话内唯一递增）。 */
    public static JSONObject request(String method, JSONObject params, long id) throws JSONException {
        JSONObject msg = new JSONObject();
        msg.put("jsonrpc", McpConstants.JSON_RPC_VERSION);
        msg.put("id", id);
        msg.put("method", method);
        if (params != null) msg.put("params", params);
        return msg;
    }

    /** 构造一个通知（无 id，服务端不回响应）。 */
    public static JSONObject notification(String method, JSONObject params) throws JSONException {
        JSONObject msg = new JSONObject();
        msg.put("jsonrpc", McpConstants.JSON_RPC_VERSION);
        msg.put("method", method);
        if (params != null) msg.put("params", params);
        return msg;
    }

    /** 消息是否携带 error 字段（协议级错误）。 */
    public static boolean isError(JSONObject msg) {
        return msg != null && msg.has("error");
    }

    /** 取协议级错误文本。 */
    public static String errorMessage(JSONObject msg) {
        if (msg == null) return "空响应";
        JSONObject err = msg.optJSONObject("error");
        if (err == null) return "未知错误";
        String code = String.valueOf(err.optInt("code", -1));
        String message = err.optString("message", "未知错误");
        return "MCP错误[" + code + "]: " + message;
    }

    /** 取响应中的 result 对象（无则返回空对象）。 */
    public static JSONObject result(JSONObject msg) {
        if (msg == null || !msg.has("result")) return new JSONObject();
        Object r = msg.opt("result");
        if (r instanceof JSONObject) return (JSONObject) r;
        JSONObject wrapper = new JSONObject();
        try {
            wrapper.put("value", r);
        } catch (JSONException ignored) {
        }
        return wrapper;
    }

    /** 响应是否与指定 id 匹配。 */
    public static boolean matchesId(JSONObject msg, long id) {
        return msg != null && msg.has("id") && msg.optLong("id", -1) == id;
    }
}
