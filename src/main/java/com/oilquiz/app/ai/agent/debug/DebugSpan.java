package com.oilquiz.app.ai.agent.debug;

import org.json.JSONObject;

/**
 * 调试追踪 Span —— 一次 Agent 执行(run)中的最小可观测单元。
 *
 * 类型：
 *  - session : 一次 run 的根节点（模型、总体耗时、汇总 token）
 *  - llm     : 一次大模型推理调用（prompt/completion/cached token、耗时）
 *  - thinking: 思考链阶段
 *  - tool    : 一次工具调用（名称、入参摘要、结果摘要、耗时）
 *  - step    : 执行阶段/状态通知
 *
 * 设计：UI 层（调试控制台）收集并渲染成瀑布图；每次 run 结束序列化落盘，
 * 支持历史回放与导出，不依赖模型/引擎实现。
 */
public class DebugSpan {

    public static final String TYPE_SESSION = "session";
    public static final String TYPE_LLM = "llm";
    public static final String TYPE_THINKING = "thinking";
    public static final String TYPE_TOOL = "tool";
    public static final String TYPE_STEP = "step";

    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_OK = "ok";
    public static final String STATUS_ERROR = "error";
    public static final String STATUS_CANCELLED = "cancelled";

    public String id;          // span 唯一 id
    public String runId;       // 所属 run
    public String parentId;    // 父 span id（session 为根，parentId 为空）
    public String type;        // TYPE_*
    public String name;        // 标题：模型名 / 工具名 / 阶段名
    public String status;      // STATUS_*
    public long startMs;
    public long endMs;         // 0 表示进行中
    public int promptTokens;
    public int completionTokens;
    public int totalTokens;
    public int cachedTokens;
    public String detail;      // 入参/说明（摘要）
    public String result;      // 输出摘要
    public String error;       // 错误信息
    public int depth;          // 树深度（渲染缩进用）

    public DebugSpan() {
    }

    public long durationMs() {
        if (endMs > 0 && endMs >= startMs) return endMs - startMs;
        return System.currentTimeMillis() - startMs;
    }

    public boolean isRunning() {
        return STATUS_RUNNING.equals(status);
    }

    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("id", nz(id));
            o.put("runId", nz(runId));
            o.put("parentId", nz(parentId));
            o.put("type", nz(type));
            o.put("name", nz(name));
            o.put("status", nz(status));
            o.put("startMs", startMs);
            o.put("endMs", endMs);
            o.put("promptTokens", promptTokens);
            o.put("completionTokens", completionTokens);
            o.put("totalTokens", totalTokens);
            o.put("cachedTokens", cachedTokens);
            o.put("detail", nz(detail));
            o.put("result", nz(result));
            o.put("error", nz(error));
            o.put("depth", depth);
            return o.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    public static DebugSpan fromJson(String json) {
        DebugSpan s = new DebugSpan();
        try {
            JSONObject o = new JSONObject(json);
            s.id = o.optString("id", "");
            s.runId = o.optString("runId", "");
            s.parentId = o.optString("parentId", "");
            s.type = o.optString("type", TYPE_STEP);
            s.name = o.optString("name", "");
            s.status = o.optString("status", STATUS_OK);
            s.startMs = o.optLong("startMs", 0);
            s.endMs = o.optLong("endMs", 0);
            s.promptTokens = o.optInt("promptTokens", 0);
            s.completionTokens = o.optInt("completionTokens", 0);
            s.totalTokens = o.optInt("totalTokens", 0);
            s.cachedTokens = o.optInt("cachedTokens", 0);
            s.detail = o.optString("detail", "");
            s.result = o.optString("result", "");
            s.error = o.optString("error", "");
            s.depth = o.optInt("depth", 0);
        } catch (Throwable ignored) {
        }
        return s;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
