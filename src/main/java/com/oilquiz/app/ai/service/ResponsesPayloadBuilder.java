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
        // system / developer 提示走顶层 instructions：两家官方文档给出的系统提示入口都是它，
        // 没有一处示范 input 里的 role=system —— 猜错的表征不是报错，而是"系统提示被当成
        // 普通用户轮次"这种静默劣化。它还不参与逐轮历史，天然是稳定的请求前缀。
        String instructions = extractInstructions(chatMessages);
        if (!instructions.isEmpty()) {
            body.addProperty("instructions", instructions);
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
     * 抽出<b>开头连续</b>那段 system / developer 文本，拼成顶层 {@code instructions}。
     *
     * <p>只收开头连续段：Agent 循环会在中途插入 system 角色的实时引导（格式提醒、
     * 任务清单提示等），那是"此刻说的话"，搬到开头等于改变语义 —— 中段系统消息仍按原位置
     * 留在 {@code input} 里。</p>
     */
    public static String extractInstructions(JsonArray chatMessages) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < leadingSystemCount(chatMessages); i++) {
            JsonElement element = chatMessages.get(i);
            if (element == null || !element.isJsonObject()) continue;
            String content = contentOf(element.getAsJsonObject());
            if (content.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(LF_CONST);
            sb.append(content);
        }
        return sb.toString();
    }

    /** 开头连续的 system / developer 条数（这些条目由 instructions 承载，不再进 input） */
    private static int leadingSystemCount(JsonArray chatMessages) {
        if (chatMessages == null) return 0;
        int n = 0;
        for (JsonElement element : chatMessages) {
            JsonObject msg = asObject(element);
            if (msg == null) break;
            String role = str(msg, "role");
            if (!"system".equals(role) && !"developer".equals(role)) break;
            n++;
        }
        return n;
    }

    private static final String LF_CONST = String.valueOf((char) 10);
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
        int skip = leadingSystemCount(chatMessages);   // 这一段已由顶层 instructions 承载
        int index = 0;
        for (JsonElement element : chatMessages) {
            if (index++ < skip) {
                continue;
            }
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

    // ==================== 图片输入（input_image） ====================

    /**
     * Responses 侧的图片条目：{@code {type:"input_image", image_url:"<字符串>", detail}}。
     *
     * <p>与 chat/completions 的形状差异要记清：chat 侧是
     * {@code {"type":"image_url","image_url":{"url":...,"detail":...}}}（image_url 是对象），
     * Responses 侧 {@code image_url} <b>直接是字符串</b>、{@code detail} 与其同级。写成 chat
     * 形状不会报错，只会被服务商静默忽略图片 —— 表现是"模型说它看不到图"。</p>
     *
     * <p>依据 OpenAI Responses 与 DeepSeek 官方 vision 文档：{@code /chat/completions} 用
     * {@code image_url}、{@code /responses} 用 {@code input_image}，两处都接受
     * {@code data:<真实 media_type>;base64,...} 形式的内联图。</p>
     *
     * @param dataUrl 已按真实字节拼好的 data URI（见 PreparedVisionImage#dataUrl）
     * @return 图片条目；dataUrl 为空时返回 null（调用方跳过该条，不发空条目）
     */
    public static JsonObject inputImageItem(String dataUrl) {
        if (isBlank(dataUrl)) return null;
        JsonObject item = new JsonObject();
        item.addProperty("type", "input_image");
        item.addProperty("image_url", dataUrl);
        // detail 交给服务商默认档：体积已由客户端编码预算控制（长边 1568 / 单图 ≤2.4MB）
        item.addProperty("detail", "auto");
        return item;
    }

    /**
     * 组装带图片的当前轮 user 条目：图片条目在前、文本条目在后。
     *
     * <p>顺序取自各家官方建议（Anthropic 明确 image block 置于 text 前；Gemini 最佳实践同样
     * "图在前、提示在后"，多图用 image 1/image 2 指代）；OpenAI 两种顺序都接受，因此统一到
     * 这一条规则，避免 chat 与 Responses 两条链路形状不一致。</p>
     *
     * @param text     本轮文本（可为空 —— 纯图发送时只有图片条目也合法）
     * @param dataUrls 图片 data URI 列表（按发送顺序；null/空元素跳过）
     * @return {@code {role:"user", content:[input_image..., input_text]}}
     */
    public static JsonObject userMessageWithImages(String text, java.util.List<String> dataUrls) {
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        JsonArray content = new JsonArray();
        if (dataUrls != null) {
            for (String dataUrl : dataUrls) {
                JsonObject image = inputImageItem(dataUrl);
                if (image != null) content.add(image);
            }
        }
        if (!isBlank(text)) {
            JsonObject textItem = new JsonObject();
            textItem.addProperty("type", "input_text");
            textItem.addProperty("text", text);
            content.add(textItem);
        }
        user.add("content", content);
        return user;
    }
}
