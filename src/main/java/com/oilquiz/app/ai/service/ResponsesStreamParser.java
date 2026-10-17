package com.oilquiz.app.ai.service;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Responses（{@code /responses}）SSE 事件流解析器 —— <b>不依赖任何 Android 类</b>。
 *
 * <p><b>为什么单独成类</b>：Responses 的流语义与 {@code /chat/completions} 差异不小
 * （正文在 {@code response.output_text.delta}，思考在 {@code response.reasoning_*}，
 * 工具调用不是 choices[].delta.tool_calls 而是<b>按 item 生命周期下发</b>：
 * {@code output_item.added} → {@code function_call_arguments.delta}* → {@code output_item.done}，
 * 终态由 {@code response.completed / failed / incomplete} 表达）。
 * 这段逻辑此前内联在 {@code OnlineInferenceService.readResponsesStream} 里，
 * 无法离线测试，结果带着两处硬伤躺了很久（见下）。抽出来后可用本地假 SSE 服务器端到端验证。</p>
 *
 * <p><b>修掉的两处硬伤</b>：① 旧实现把 {@code delta} 当 JsonObject 取
 * （{@code json.get("delta").isJsonObject()}），而 OpenAI 下发的是<b>字符串</b>
 * ——于是每个正文片段都被判空丢弃，用户看不到任何输出；② 旧实现读到
 * {@code response.completed} 只是 return，从不投递完成回调，流式界面停在"生成中"。
 * 本实现对 {@code delta} 同时接受字符串与对象两种形态（各家兼容层确有分歧，成本为零），
 * 并把终态显式建模成 {@link Result#status}。</p>
 *
 * <p><b>工具聚合只做规范内的事</b>：参数按 {@code item_id} 归槽拼接、call_id 与 name
 * 从 added/done 任一处取到即可。它<b>尚未接入</b> Agent 循环（那需要连真实端点验证
 * 事件顺序与错误语义，见 ROADMAP 7.1），因此这里不猜任何厂商私有字段。</p>
 */
public final class ResponsesStreamParser {

    /** 增量事件的外发出口（生产由 {@code OnlineInferenceService} 桥接到 StreamCallback）。 */
    public interface Sink {
        /** 正文片段 */
        void onTextDelta(String delta);

        /** 思考/推理片段 */
        void onReasoningDelta(String delta);

        /** 一条 function_call 的参数聚合完成（done 事件或终态兜底） */
        default void onFunctionCallReady(FunctionCall call) {}

        /**
         * 用量（Responses 的字段名与 chat 不同，这里按 Responses 语义给）。
         *
         * @param inputTokens     input_tokens
         * @param outputTokens    output_tokens
         * @param reasoningTokens 思考输出 token（可为 0）
         * @param cachedTokens    命中缓存的输入 token（可为 0）
         */
        default void onUsage(int inputTokens, int outputTokens, int reasoningTokens,
                             int cachedTokens) {}
    }

    /** 一条工具调用（与 chat 体系的 {@code ToolCallInfo} 同语义，但不引用它以便纯 JVM 测试）。 */
    public static final class FunctionCall {
        /** Responses 的 item id */
        public String itemId;
        /** 与后续 function_call_output 配对用的 call_id（配对靠它，不是数组下标） */
        public String callId;
        public String name;
        /** 增量拼接出来的 JSON 参数串 */
        public String arguments = "";
        /** 参数是否已随 done 事件定稿 */
        public boolean argumentsFinalized;

        @Override
        public String toString() {
            return "FunctionCall{callId=" + callId + ", name=" + name + ", args=" + arguments + "}";
        }
    }

    /** 一次流解析的结果。 */
    public static final class Result {
        public static final String STATUS_COMPLETED = "completed";
        public static final String STATUS_FAILED = "failed";
        public static final String STATUS_INCOMPLETE = "incomplete";
        /** 流自然结束但没有终态事件（代理截断、连接被回收等） */
        public static final String STATUS_ENDED = "ended";

        public String text = "";
        public String reasoning = "";
        public final List<FunctionCall> functionCalls = new ArrayList<>();
        public String status = STATUS_ENDED;
        /** 失败原因（{@link #STATUS_FAILED} 或传输异常时） */
        public String error;
        public int inputTokens;
        public int outputTokens;
        public int reasoningTokens;
        public int cachedTokens;

        public boolean isFailed() {
            return STATUS_FAILED.equals(status);
        }

        public boolean hasFunctionCalls() {
            return !functionCalls.isEmpty();
        }

        /**
         * 对齐 chat 体系的 finish_reason 语义，便于上层不改判断逻辑。
         *
         * @return {@code "tool_calls"} / {@code "stop"} / {@code "error"}
         */
        public String finishReason() {
            if (isFailed()) return "error";
            return hasFunctionCalls() ? "tool_calls" : "stop";
        }
    }

    private final Sink sink;
    private final Gson gson = new Gson();
    /** 按出现顺序保留，便于多工具时与 UI 展示顺序一致 */
    private final Map<String, FunctionCall> callsByItem = new LinkedHashMap<>();
    /** SSE 的 {@code event:} 行；部分兼容层不在 data 里带 type，只能靠它 */
    private String lastEventType = "";
    /** 正文兜底：若只收到 output_item.done 的最终 message 而无增量，用它补齐 */
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    /** 终态（默认 ended：流自然读完但没有 completed/failed 事件） */
    private String status = Result.STATUS_ENDED;
    private String error;
    private int usageInput;
    private int usageOutput;
    private int usageReasoningTokens;
    private int usageCachedTokens;

    public ResponsesStreamParser(Sink sink) {
        this.sink = sink == null ? NO_SINK : sink;
    }

    private static final Sink NO_SINK = new Sink() {
        @Override
        public void onTextDelta(String delta) {
        }

        @Override
        public void onReasoningDelta(String delta) {
        }
    };

    /** 从字节流解析（Responses 流恒为 UTF-8）。 */
    public Result parse(InputStream in) throws IOException {
        return parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
    }

    /**
     * 解析整条 SSE 流直到终态事件或流结束。
     *
     * @return 聚合结果；<b>不抛业务异常</b>（失败以 {@link Result#status} 表达），
     *         只有 I/O 问题才抛 {@link IOException}，由调用方决定如何转成错误回调
     */
    public Result parse(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                continue; // 事件分隔空行
            }
            if (line.charAt(0) == ':') {
                continue; // SSE 注释/心跳
            }
            if (line.startsWith("event:")) {
                lastEventType = line.substring(6).trim();
                continue;
            }
            if (!line.startsWith("data:")) {
                continue; // id:/retry: 等与本流无关
            }
            String data = line.substring(5).trim();
            if (data.isEmpty()) {
                continue;
            }
            if ("[DONE]".equals(data)) {
                break; // Responses 正常不发，兼容层可能发
            }
            JsonObject json;
            try {
                json = gson.fromJson(data, JsonObject.class);
            } catch (RuntimeException notJson) {
                continue; // 半截 JSON（极少数代理按字节切帧）跳过
            }
            if (json == null) {
                continue;
            }
            String type = text(json, "type");
            if (type.isEmpty()) {
                type = lastEventType; // data 里没带 type 时退回 event: 行
            }
            if (handle(type, json)) {
                break; // 终态
            }
        }
        return finish();
    }

    /** @return true 表示已到终态，不必再读后续行 */
    private boolean handle(String type, JsonObject json) {
        switch (type) {
            case "response.output_text.delta": {
                String delta = deltaText(json);
                if (!delta.isEmpty()) {
                    text.append(delta);
                    sink.onTextDelta(delta);
                }
                return false;
            }
            case "response.output_text.done": {
                // 终稿片段：增量若有丢失（心跳切帧）以它为准
                String done = text(json, "text");
                if (!done.isEmpty() && done.length() > text.length()) {
                    String missing = done.substring(text.length());
                    text.append(missing);
                    sink.onTextDelta(missing);
                }
                return false;
            }
            case "response.reasoning_text.delta":
            case "response.reasoning_summary_text.delta": {
                String delta = deltaText(json);
                if (!delta.isEmpty()) {
                    reasoning.append(delta);
                    sink.onReasoningDelta(delta);
                }
                return false;
            }
            case "response.output_item.added": {
                JsonObject item = object(json, "item");
                if (item != null && "function_call".equals(text(item, "type"))) {
                    openCall(item);
                }
                return false;
            }
            case "response.function_call_arguments.delta": {
                appendArgs(text(json, "item_id"), deltaText(json), false);
                return false;
            }
            case "response.function_call_arguments.done": {
                appendArgs(text(json, "item_id"), deltaText(json), true);
                return false;
            }
            case "response.output_item.done": {
                JsonObject item = object(json, "item");
                if (item != null && "function_call".equals(text(item, "type"))) {
                    closeCall(item);
                }
                return false;
            }
            case "response.completed": {
                JsonObject response = object(json, "response");
                applyUsage(response);
                applyTerminalOutput(response);
                status = Result.STATUS_COMPLETED;
                return true;
            }
            case "response.failed": {
                status = Result.STATUS_FAILED;
                error = failureReason(object(json, "response"), "generation failed");
                return true;
            }
            case "response.incomplete": {
                status = Result.STATUS_INCOMPLETE;
                error = failureReason(object(json, "response"), "incomplete");
                return true;
            }
            case "error": {
                status = Result.STATUS_FAILED;
                JsonObject err = object(json, "error");
                error = err != null ? firstNonEmpty(text(err, "message"), text(err, "code"))
                        : firstNonEmpty(text(json, "message"), "unknown error");
                if (error.isEmpty()) error = "unknown error";
                return true;
            }
            default:
                // response.created / in_progress / content_part.* / reference.* 等：无需处理
                return false;
        }
    }

    // ---------- 结果落地 ----------

    /**
     * 解析 <b>非流式</b> Responses 响应体（同一套字段口径与容错规则）。
     *
     * <p>存在的理由：{@code readResponsesFull} 此前自己遍历 {@code output[]}，遇到
     * {@code JsonNull}（如 {@code content[].type} 为 null、数组里混进字符串）就抛
     * {@code UnsupportedOperationException} —— 一个"内容形状不合我预期"的响应会变成
     * 一句无法解释的生成失败。语义与流式同源，就只该有一处实现。</p>
     *
     * @param body 响应原文
     * @return 与 {@link #parse} 同形状的 Result（{@code status} 取响应体的 {@code status}）
     */
    public Result parseFullResponse(String body) {
        JsonObject json;
        try {
            json = body == null || body.isEmpty() ? null : gson.fromJson(body, JsonObject.class);
        } catch (RuntimeException notJson) {
            json = null;
        }
        if (json == null) {
            status = Result.STATUS_FAILED;
            error = "响应不是合法 JSON";
            return finish();
        }
        applyUsage(json);
        JsonElement output = json.get("output");
        if (output != null && output.isJsonArray()) {
            for (JsonElement element : output.getAsJsonArray()) {
                if (element == null || !element.isJsonObject()) {
                    continue; // 数组里混进字符串/null：跳过而不是崩
                }
                JsonObject item = element.getAsJsonObject();
                String itemType = text(item, "type");
                if ("function_call".equals(itemType)) {
                    closeCall(item);
                } else if ("message".equals(itemType)) {
                    String joined = joinMessageText(item);
                    if (!joined.isEmpty()) {
                        emitText(joined);
                    }
                }
            }
        }
        String whole = text(json, "output_text");
        if (!whole.isEmpty() && text.length() == 0) {
            emitText(whole);
        }
        String st = text(json, "status");
        if ("failed".equals(st)) {
            status = Result.STATUS_FAILED;
            error = failureReason(json, "generation failed");
        } else if ("incomplete".equals(st)) {
            status = Result.STATUS_INCOMPLETE;
            error = failureReason(json, "incomplete");
        } else {
            status = Result.STATUS_COMPLETED;
        }
        return finish();
    }

    /** 追加正文并外发一次增量（非流式即整段一次） */
    private void emitText(String delta) {
        text.append(delta);
        sink.onTextDelta(delta);
    }

    private Result finish() {
        Result r = new Result();
        r.text = text.toString();
        r.reasoning = reasoning.toString();
        r.functionCalls.addAll(callsByItem.values());
        r.status = status;
        r.error = error;
        r.inputTokens = usageInput;
        r.outputTokens = usageOutput;
        r.reasoningTokens = usageReasoningTokens;
        r.cachedTokens = usageCachedTokens;
        return r;
    }

    private void applyUsage(JsonObject response) {
        JsonObject usage = response == null ? null : object(response, "usage");
        if (usage == null) return;
        usageInput = intOf(usage, "input_tokens");
        usageOutput = intOf(usage, "output_tokens");
        usageReasoningTokens = intOf(object(usage, "output_tokens_details"), "reasoning_tokens");
        usageCachedTokens = intOf(object(usage, "input_tokens_details"), "cached_tokens");
        sink.onUsage(usageInput, usageOutput, usageReasoningTokens, usageCachedTokens);
    }

    /**
     * 失败原因：Responses 把原因放在 {@code response.error.message}，
     * 兼容层也可能只给 {@code status_details}。取第一个非空，全空则用兜底文案。
     */
    private static String failureReason(JsonObject response, String fallback) {
        if (response == null) return fallback;
        JsonObject err = object(response, "error");
        if (err != null) {
            String msg = firstNonEmpty(text(err, "message"), text(err, "code"));
            if (!msg.isEmpty()) return msg;
        }
        String fromDetails = text(object(response, "status_details"), "message");
        if (!fromDetails.isEmpty()) return fromDetails;
        String status = text(response, "status");
        return status.isEmpty() ? fallback : fallback + " (" + status + ")";
    }

    /**
     * 终态 response 对象里可能带着完整 output 数组：用它兜底两件事
     * —— 增量事件全被代理吞掉时补正文；tool call 只有终稿时补参数。
     */
    private void applyTerminalOutput(JsonObject response) {
        if (response == null) return;
        JsonElement output = response.get("output");
        if (output == null || !output.isJsonArray()) {
            // 无 output 数组时，退到 response.output_text（OpenAI 在部分场景直接给整串）
            String whole = text(response, "output_text");
            if (!whole.isEmpty() && whole.length() > text.length()) {
                String missing = whole.substring(text.length());
                text.append(missing);
                sink.onTextDelta(missing);
            }
            return;
        }
        for (JsonElement element : output.getAsJsonArray()) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String itemType = text(item, "type");
            if ("function_call".equals(itemType)) {
                closeCall(item);
            } else if ("message".equals(itemType) && text.length() == 0) {
                String joined = joinMessageText(item);
                if (!joined.isEmpty()) {
                    text.append(joined);
                    sink.onTextDelta(joined);
                }
            }
        }
    }

    private String joinMessageText(JsonObject item) {
        JsonElement content = item.get("content");
        if (content == null || !content.isJsonArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonElement part : content.getAsJsonArray()) {
            if (part == null || !part.isJsonObject()) continue;
            JsonObject p = part.getAsJsonObject();
            if ("output_text".equals(text(p, "type"))) {
                sb.append(text(p, "text"));
            }
        }
        return sb.toString();
    }

    // ---------- function_call 聚合 ----------

    private FunctionCall openCall(JsonObject item) {
        String itemId = firstNonEmpty(text(item, "item_id"), text(item, "id"));
        if (itemId.isEmpty()) {
            itemId = "anon-" + callsByItem.size(); // 无 id 时按顺序占位，仍保持配对顺序
        }
        FunctionCall call = callsByItem.get(itemId);
        if (call == null) {
            call = new FunctionCall();
            call.itemId = itemId;
            callsByItem.put(itemId, call);
        }
        String callId = text(item, "call_id");
        if (!callId.isEmpty()) call.callId = callId;
        String name = text(item, "name");
        if (!name.isEmpty()) call.name = name;
        String args = text(item, "arguments");
        if (!args.isEmpty()) {
            call.arguments = args;
            finalizeCall(call); // added/done 自带完整参数时也算定稿
        }
        return call;
    }

    /**
     * 定稿一条工具调用，并且<b>只外发一次</b>。
     *
     * <p>为什么关键：{@code function_call_arguments.done} 之后紧跟的
     * {@code output_item.done} 描述的是同一个 call，若两处都外发，第 2 步接入 Agent 循环后
     * 同一个工具会被执行两次（本项目的工具执行是真实副作用，重复执行不可接受）。</p>
     */
    private void finalizeCall(FunctionCall call) {
        if (call.argumentsFinalized) return;
        call.argumentsFinalized = true;
        sink.onFunctionCallReady(call);
    }

    private void appendArgs(String itemId, String delta, boolean finalized) {
        if (itemId == null) itemId = "";
        FunctionCall call = callsByItem.get(itemId);
        if (call == null) {
            // 没有 added 事件也必须能收：兼容层可能直接给 delta/done
            call = new FunctionCall();
            call.itemId = itemId.isEmpty() ? "anon-" + callsByItem.size() : itemId;
            callsByItem.put(call.itemId, call);
        }
        if (delta != null && !delta.isEmpty()) {
            // 已定稿还来增量 = 事件序列异常（兼容层乱序）：追加但不重复外发，交由调用方按终值取用
            call.arguments = call.arguments + delta;
        }
        if (finalized) {
            finalizeCall(call);
        }
    }

    private void closeCall(JsonObject item) {
        FunctionCall call = openCall(item);
        String args = text(item, "arguments");
        if (!args.isEmpty()) {
            call.arguments = args;
        }
        finalizeCall(call);
    }

    // ---------- JSON 取值小工具（一律容忍 null / 缺字段 / 类型不符） ----------

    private static String deltaText(JsonObject json) {
        JsonElement delta = json.get("delta");
        if (delta == null || delta.isJsonNull()) return "";
        if (delta.isJsonPrimitive()) {
            try {
                return delta.getAsString();
            } catch (RuntimeException e) {
                return "";
            }
        }
        if (delta.isJsonObject()) {
            return text(delta.getAsJsonObject(), "text");
        }
        return "";
    }

    private static JsonObject object(JsonObject json, String key) {
        if (json == null) return null;
        JsonElement e = json.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static String text(JsonObject json, String key) {
        if (json == null) return "";
        JsonElement e = json.get(key);
        if (e == null || e.isJsonNull()) return "";
        try {
            return e.isJsonPrimitive() ? e.getAsString() : "";
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private static int intOf(JsonObject json, String key) {
        if (json == null) return 0;
        JsonElement e = json.get(key);
        if (e == null || e.isJsonNull()) return 0;
        try {
            return e.getAsInt();
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private static String firstNonEmpty(String a, String b) {
        return a != null && !a.isEmpty() ? a : (b == null ? "" : b);
    }
}
