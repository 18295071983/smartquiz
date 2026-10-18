package com.oilquiz.app.ai.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * chat 体系 → OpenAI Responses（{@code /responses}）请求体的单向翻译器。
 *
 * <p><b>为什么单独成类</b>：本项目在线 Agent 的整个循环（工具并行、按 id 配对回填、历史裁剪、
 * 取消/超时保护）都建立在 chat/completions 的消息形状上，而 Responses 用的是<b>条目
 * （item）</b>形状，两者不兼容的地方正是最容易静默出错的地方：</p>
 * <ul>
 *   <li>工具调用不是塞在 {@code assistant.tool_calls[]} 里，而是<b>独立的
 *   {@code function_call} item</b>（{@code call_id}/{@code name}/{@code arguments}）；</li>
 *   <li>工具结果是<b>独立的 {@code function_call_output} item</b>，按 {@code call_id} 配对 ——
 *   不是 chat 的 {@code role:"tool"} + {@code tool_call_id}；</li>
 *   <li>{@code tools} 是<b>扁平</b> {@code {type:"function", name, description, parameters}}，
 *   不是 chat 那种把 {@code name/parameters} 再包一层 {@code function} 的嵌套写法。</li>
 * </ul>
 *
 * <p>把这层转换集中在这里，{@code OnlineAgentEngine} 可以继续讲 chat 方言，循环逻辑一行不动；
 * 也让转换本身可被纯 JVM 单测覆盖（不依赖 Android，也不依赖网络）。</p>
 *
 * <p>容错原则：<b>绝不抛异常</b>。字段缺失、条目形状不对、工具 JSON 里混进非对象元素，
 * 一律跳过该条并继续，因为一次坏条目不该让整轮请求失败；但配对必需的
 * {@code call_id} 缺失时会<b>丢弃</b>该条而不是猜一个 —— 错误的配对会让模型把结果接到
 * 别的调用上，比少一条结果更糟。</p>
 */
public final class ResponsesPayloadBuilder {

    private static final Gson GSON = new Gson();
    /** assistant 缺 arguments 时的占位：Responses 要求 arguments 是 JSON 字符串 */
    static final String EMPTY_ARGUMENTS = "{}";

    private ResponsesPayloadBuilder() {
    }

    /**
     * 组装一次 Responses 请求体。
     *
     * @param model           模型名
     * @param chatMessages    chat 方言的消息数组（{@code role/content/tool_calls/tool_call_id}）
     * @param toolsJson       chat 方言的工具定义（可为 null 或空串）
     * @param maxOutputTokens ≤0 时不下发（交给服务商默认值）
     * @param temperature     &lt;0 时不下发
     * @param stream          是否走 SSE
     * @return 可直接 gson.toJson 的请求体
     */
    public static JsonObject build(String model, JsonArray chatMessages, String toolsJson,
                                   int maxOutputTokens, double temperature, boolean stream) {
        return build(model, chatMessages, toolsJson, maxOutputTokens, temperature, stream, null);
    }

    /**
     * 同上，并可带上思考强度。
     *
     * <p>Responses 侧的思考不是 chat 那套 {@code enable_thinking} / {@code thinking.type} /
     * {@code reasoning_effort} 扁平参数，而是嵌套的 {@code reasoning:{effort}} ——
     * 官方（OpenAI 与 DeepSeek）都列了该字段。强度取值一律由调用方按服务商声明的档位给出，
     * 本方法不猜：传 null/空 就整个字段不下发。</p>
     *
     * @param reasoningEffort 思考强度（如 low/medium/high）；null 表示不开启
     */
    public static JsonObject build(String model, JsonArray chatMessages, String toolsJson,
                                   int maxOutputTokens, double temperature, boolean stream,
                                   String reasoningEffort) {
        JsonObject body = new JsonObject();
        if (model != null && !model.isEmpty()) {
            body.addProperty("model", model);
        }
        body.add("input", toInputItems(chatMessages));
        JsonArray tools = toFlatTools(toolsJson);
        if (tools.size() > 0) {
            body.add("tools", tools);
        }
        if (maxOutputTokens > 0) {
            body.addProperty("max_output_tokens", maxOutputTokens);
        }
        if (temperature >= 0) {
            body.addProperty("temperature", temperature);
        }
        if (stream) {
            body.addProperty("stream", true);
        }
        if (reasoningEffort != null && !reasoningEffort.trim().isEmpty()) {
            JsonObject reasoning = new JsonObject();
            reasoning.addProperty("effort", reasoningEffort.trim());
            body.add("reasoning", reasoning);
        }
        return body;
    }

    /**
     * chat 消息数组 → Responses {@code input[]} 条目数组。
     *
     * <p>顺序即数组顺序：一条 assistant 若同时有正文与工具调用，先出 message 条目再出
     * {@code function_call} 条目，与 Responses 自己返回的 {@code output[]} 顺序一致，
     * 这样下一轮回填的形状和模型上一轮看到的形状同构。</p>
     */
    public static JsonArray toInputItems(JsonArray chatMessages) {
        JsonArray input = new JsonArray();
        if (chatMessages == null) {
            return input;
        }
        for (JsonElement element : chatMessages) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject msg = element.getAsJsonObject();
            String role = str(msg, "role");
            String toolCallId = str(msg, "tool_call_id");
            if ("tool".equals(role)) {
                JsonObject output = new JsonObject();
                output.addProperty("type", "function_call_output");
                // call_id 缺失就整条丢弃：宁可让模型少看到一条结果，也不能把结果接到错的调用上
                if (toolCallId.isEmpty()) {
                    continue;
                }
                output.addProperty("call_id", toolCallId);
                output.addProperty("output", contentOf(msg));
                input.add(output);
                continue;
            }

            if ("assistant".equals(role)) {
                String content = contentOf(msg);
                if (!isBlank(content)) {
                    input.add(messageItem("assistant", "output_text", content));
                }
                JsonArray calls = msg.has("tool_calls") && msg.get("tool_calls").isJsonArray()
                        ? msg.getAsJsonArray("tool_calls") : null;
                if (calls != null) {
                    for (JsonElement callElement : calls) {
                        JsonObject call = asObject(callElement);
                        if (call == null) {
                            continue;
                        }
                        JsonObject function = asObject(call.get("function"));
                        String id = firstNonEmpty(str(call, "id"), str(call, "call_id"));
                        if (id.isEmpty()) {
                            continue; // 同上：无 id 的工具调用无法与结果配对
                        }
                        JsonObject item = new JsonObject();
                        item.addProperty("type", "function_call");
                        item.addProperty("call_id", id);
                        item.addProperty("name", function != null
                                ? str(function, "name") : str(call, "name"));
                        String arguments = function != null ? str(function, "arguments")
                                : str(call, "arguments");
                        item.addProperty("arguments",
                                arguments == null || arguments.isEmpty() ? EMPTY_ARGUMENTS : arguments);
                        input.add(item);
                    }
                }
                if (isBlank(content) && (calls == null || calls.size() == 0)) {
                    continue; // 空气泡（含只有空白的）：既无正文也无调用，别占一个空条目
                }
                continue;
            }

            // system / developer / user 及其它：一律按输入文本条目下发
            // 本路径只搬文本，所以三种角色都用 input_text（assistant 才是 output_text）
            String content = contentOf(msg);
            if (isBlank(content)) {
                continue; // 只有空白的消息同样不占条目
            }
            input.add(messageItem(role.isEmpty() ? "user" : role, "input_text", content));
        }
        return input;
    }

    /**
     * chat 方言的 tools JSON → Responses 的扁平 tools 数组。
     *
     * <p>同时接受已经是扁平形态的输入（有的服务商/自建端点直接给扁平），这样本方法可以
     * 安全地重复调用而不产生二次嵌套。</p>
     */
    public static JsonArray toFlatTools(String toolsJson) {
        JsonArray flat = new JsonArray();
        if (toolsJson == null || toolsJson.trim().isEmpty()) {
            return flat;
        }
        JsonElement parsed;
        try {
            parsed = GSON.fromJson(toolsJson, JsonElement.class);
        } catch (RuntimeException malformed) {
            return flat; // 工具定义坏了就退化成"无工具"，不阻塞对话
        }
        if (parsed == null || !parsed.isJsonArray()) {
            return flat;
        }
        for (JsonElement element : parsed.getAsJsonArray()) {
            JsonObject tool = asObject(element);
            if (tool == null) {
                continue;
            }
            JsonObject function = asObject(tool.get("function"));
            JsonObject out = new JsonObject();
            out.addProperty("type", firstNonEmpty(str(tool, "type"), "function"));
            out.addProperty("name", function != null ? str(function, "name") : str(tool, "name"));
            String description = function != null ? str(function, "description")
                    : str(tool, "description");
            if (!description.isEmpty()) {
                out.addProperty("description", description);
            }
            JsonElement parameters = function != null ? function.get("parameters")
                    : tool.get("parameters");
            out.add("parameters", parameters != null && parameters.isJsonObject()
                    ? parameters.getAsJsonObject() : emptySchema());
            flat.add(out);
        }
        return flat;
    }

    /** Responses 侧 function 工具的参数槽：没有 schema 时给一个空 object schema 而不是缺字段 */
    private static JsonObject emptySchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        return schema;
    }

    private static JsonObject messageItem(String role, String contentType, String text) {
        JsonObject item = new JsonObject();
        item.addProperty("role", role);
        JsonArray content = new JsonArray();
        JsonObject part = new JsonObject();
        part.addProperty("type", contentType);
        part.addProperty("text", text);
        content.add(part);
        item.add("content", content);
        return item;
    }

    /** content 允许是字符串或 OpenAI 多模态数组，这里统一取成文本 */
    private static String contentOf(JsonObject msg) {
        if (!msg.has("content") || msg.get("content").isJsonNull()) {
            return "";
        }
        JsonElement content = msg.get("content");
        if (content.isJsonPrimitive()) {
            try {
                return content.getAsString();
            } catch (RuntimeException e) {
                return "";
            }
        }
        if (content.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement part : content.getAsJsonArray()) {
                JsonObject obj = asObject(part);
                if (obj == null) {
                    continue;
                }
                String text = str(obj, "text");
                if (text.isEmpty()) {
                    continue;
                }
                // 只搬文本片段：图片等模态在 Responses 里是另一套条目类型，本路径不猜
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(text);
            }
            return sb.toString();
        }
        return "";
    }

    /** 空白内容（含全空格）按"没有内容"处理：占位条目会让服务商看到无意义的轮次 */
    private static boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }

    private static JsonObject asObject(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String str(JsonObject obj, String key) {
        if (obj == null) return "";
        JsonElement e = obj.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return "";
        try {
            return e.getAsString();
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private static String firstNonEmpty(String a, String b) {
        String x = a == null ? "" : a;
        return !x.isEmpty() ? x : (b == null ? "" : b);
    }
}
