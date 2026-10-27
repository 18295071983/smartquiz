package com.oilquiz.app.ai.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link ResponsesPayloadBuilder} 的转换契约。
 *
 * <p>这些用例针对的是"翻译错了不会报错、但模型会看不懂或接错结果"的那类静默故障：
 * {@code function_call} 与 {@code function_call_output} 必须按 {@code call_id} 成对、
 * {@code tools} 必须扁平化、{@code arguments} 必须是 JSON 字符串而不是对象。</p>
 */
public class ResponsesPayloadBuilderTest {

    private static JsonObject msg(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    private static JsonObject call(String id, String name, String arguments) {
        JsonObject c = new JsonObject();
        c.addProperty("id", id);
        c.addProperty("type", "function");
        JsonObject fn = new JsonObject();
        fn.addProperty("name", name);
        if (arguments != null) {
            fn.addProperty("arguments", arguments);
        }
        c.add("function", fn);
        return c;
    }

    private static JsonObject type(JsonArray input, int index) {
        return input.get(index).getAsJsonObject();
    }

    private static String fieldType(JsonObject item) {
        JsonElement e = item.get("type");
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    // ---------- input 条目形状 ----------

    @Test
    public void leadingSystemBecomesTopLevelInstructions() {
        // 官方文档给出的系统提示入口是顶层 instructions（没有示范 input 里的 role=system）；
        // 猜错的表征不是 400，而是"系统提示被当成普通轮次"这种静默劣化。
        JsonArray messages = new JsonArray();
        messages.add(msg("system", "你是答题助手"));
        messages.add(msg("user", "给我出三道题"));

        JsonObject body = ResponsesPayloadBuilder.build("m", messages, null, 0, -1, false);
        assertEquals("你是答题助手", body.get("instructions").getAsString());
        JsonArray input = body.getAsJsonArray("input");
        assertEquals("系统提示不再占一个输入条目", 1, input.size());
        assertEquals("user", input.get(0).getAsJsonObject().get("role").getAsString());
    }

    @Test
    public void midLoopSystemHint_staysInPlaceInsteadOfBeingHoisted() {
        // Agent 循环会在中途插 system 角色的实时引导（格式提醒等）。那是"此刻说的话",
        // 搬到开头等于改变语义，所以只收开头连续段。
        JsonArray messages = new JsonArray();
        messages.add(msg("system", "总纲"));
        messages.add(msg("user", "问题"));
        messages.add(msg("assistant", "我先查一下"));
        messages.add(msg("system", "请把工具调用放进 tool_calls 字段"));
        messages.add(msg("user", "继续"));

        JsonObject body = ResponsesPayloadBuilder.build("m", messages, null, 0, -1, false);
        assertEquals("总纲", body.get("instructions").getAsString());
        JsonArray input = body.getAsJsonArray("input");
        java.util.List<String> roles = new java.util.ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            roles.add(input.get(i).getAsJsonObject().get("role").getAsString());
        }
        assertEquals(java.util.Arrays.asList("user", "assistant", "system", "user"), roles);
    }

    @Test
    public void noSystemMessage_meansNoInstructionsField() {
        JsonArray messages = new JsonArray();
        messages.add(msg("user", "只有用户"));
        JsonObject body = ResponsesPayloadBuilder.build("m", messages, null, 0, -1, false);
        assertFalse("不下发空 instructions（部分端点对空串的处理不一致）",
                body.has("instructions"));
        assertEquals("input_text", body.getAsJsonArray("input").get(0).getAsJsonObject()
                .getAsJsonArray("content").get(0).getAsJsonObject().get("type").getAsString());
    }
    @Test
    public void assistantTextAndToolCalls_becomeSeparateItemsInOrder() {
        JsonObject assistant = msg("assistant", "我先查一下天气");
        JsonArray calls = new JsonArray();
        calls.add(call("c1", "ai_weather", "{\"city\":\"北京\"}"));
        calls.add(call("c2", "time_date", "{\"fmt\":\"yyyy\"}"));
        assistant.add("tool_calls", calls);

        JsonArray messages = new JsonArray();
        messages.add(assistant);

        JsonArray input = ResponsesPayloadBuilder.toInputItems(messages);

        // message 条目在前、function_call 条目在后，与 Responses 自己返回 output[] 的顺序同构
        assertEquals(3, input.size());
        assertEquals("message", type(input, 0).get("role").getAsString().isEmpty() ? "none"
                : "message");
        assertEquals("assistant", type(input, 0).get("role").getAsString());
        assertEquals("output_text", type(input, 0).getAsJsonArray("content").get(0)
                .getAsJsonObject().get("type").getAsString());
        assertEquals("function_call", fieldType(type(input, 1)));
        assertEquals("c1", type(input, 1).get("call_id").getAsString());
        assertEquals("ai_weather", type(input, 1).get("name").getAsString());
        assertEquals("{\"city\":\"北京\"}", type(input, 1).get("arguments").getAsString());
        assertEquals("c2", type(input, 2).get("call_id").getAsString());
    }

    @Test
    public void toolResults_becomeFunctionCallOutputPairedByCallId() {
        JsonObject assistant = msg("assistant", "");
        JsonArray calls = new JsonArray();
        calls.add(call("c1", "ai_weather", "{}"));
        calls.add(call("c2", "time_date", "{}"));
        assistant.add("tool_calls", calls);

        JsonObject r1 = new JsonObject();
        r1.addProperty("role", "tool");
        r1.addProperty("tool_call_id", "c2");
        r1.addProperty("content", "2026-10-18");
        JsonObject r2 = new JsonObject();
        r2.addProperty("role", "tool");
        r2.addProperty("tool_call_id", "c1");
        r2.addProperty("content", "晴 12℃");

        JsonArray messages = new JsonArray();
        messages.add(assistant);
        messages.add(r1);
        messages.add(r2);

        JsonArray input = ResponsesPayloadBuilder.toInputItems(messages);

        assertEquals(4, input.size());
        assertEquals("function_call_output", fieldType(type(input, 2)));
        // 回填顺序按数组顺序，配对靠 call_id：错一个字节就会把结果接到别的调用上
        assertEquals("c2", type(input, 2).get("call_id").getAsString());
        assertEquals("2026-10-18", type(input, 2).get("output").getAsString());
        assertEquals("c1", type(input, 3).get("call_id").getAsString());
    }

    @Test
    public void toolResultWithoutCallId_isDroppedNotGuessed() {
        JsonObject broken = new JsonObject();
        broken.addProperty("role", "tool");
        broken.addProperty("content", "没有 id 的结果");

        JsonArray messages = new JsonArray();
        messages.add(broken);
        JsonArray input = ResponsesPayloadBuilder.toInputItems(messages);
        // 猜一个 id 会让模型把这条结果接到不相关的调用上 —— 比少一条结果更糟
        assertEquals(0, input.size());
    }

    @Test
    public void emptyAssistantTurn_isSkipped() {
        JsonArray messages = new JsonArray();
        messages.add(msg("assistant", ""));
        messages.add(msg("user", "  "));
        assertEquals(0, ResponsesPayloadBuilder.toInputItems(messages).size());
    }

    @Test
    public void multimodalContentArray_concludesTextOnly() {
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        JsonArray content = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", "这道题怎么解");
        content.add(text);
        JsonObject image = new JsonObject();
        image.addProperty("type", "image_url");
        content.add(image);
        user.add("content", content);

        JsonArray input = ResponsesPayloadBuilder.toInputItems(
                single(user));
        assertEquals(1, input.size());
        assertEquals("这道题怎么解", input.get(0).getAsJsonObject()
                .getAsJsonArray("content").get(0).getAsJsonObject()
                .get("text").getAsString());
    }

    private static JsonArray single(JsonObject m) {
        JsonArray a = new JsonArray();
        a.add(m);
        return a;
    }

    // ---------- tools 扁平化 ----------

    @Test
    public void chatNestedTools_areFlattenedToResponsesShape() {
        String chatTools = "[{\"type\":\"function\",\"function\":{\"name\":\"ai_weather\","
                + "\"description\":\"查天气\",\"parameters\":{\"type\":\"object\",\"properties\":"
                + "{\"city\":{\"type\":\"string\"}}}}}]";
        JsonArray tools = ResponsesPayloadBuilder.toFlatTools(chatTools);
        assertEquals(1, tools.size());
        JsonObject t = tools.get(0).getAsJsonObject();
        assertEquals("function", t.get("type").getAsString());
        assertEquals("ai_weather", t.get("name").getAsString());
        assertEquals("查天气", t.get("description").getAsString());
        // 关键差异：不能再有 chat 那层 function 嵌套
        assertFalse(t.has("function"));
        assertTrue(t.getAsJsonObject("parameters").has("properties"));
    }

    @Test
    public void flattenedTools_carryExplicitStrictFalse() {
        // 本项目的工具 schema 是刻意宽松的：ui_component 26 个参数只有 action 必填，
        // 还带 5 个自由 object（props/layout/render/style/monitor）和 3 个无 items 的数组，
        // 按严格模式硬规则（全字段进 required + 每层 object 显式 additionalProperties:false +
        // 数组带 items）一条都过不了。缺省值本来就是 false，显式写出来是把
        // "不赌各网关对 strict 的默认值"钉进实际发出的字节，而不是只存在于验证过的样例里。
        String chatTools = "[{\"type\":\"function\",\"function\":{\"name\":\"ui_component\","
                + "\"description\":\"创建UI组件\",\"parameters\":{\"type\":\"object\","
                + "\"properties\":{\"action\":{\"type\":\"string\"}}}}}]";
        JsonArray tools = ResponsesPayloadBuilder.toFlatTools(chatTools);
        assertEquals(1, tools.size());
        JsonObject t = tools.get(0).getAsJsonObject();
        assertTrue("每条工具都要带 strict 字段", t.has("strict"));
        assertFalse(t.get("strict").getAsBoolean());

        // 幂等：把拍平结果再喂一次（自建端点直接给扁平形态时会发生），字段不能丢也不能翻
        JsonArray again = ResponsesPayloadBuilder.toFlatTools(tools.toString());
        JsonObject t2 = again.get(0).getAsJsonObject();
        assertEquals("ui_component", t2.get("name").getAsString());
        assertTrue(t2.has("strict"));
        assertFalse(t2.get("strict").getAsBoolean());
    }

    @Test
    public void declaredStrictTrue_isPreservedNotSilentlyDowngraded() {
        // 默认档是 false，但声明方真给了一个合规工具要加严时，拍平层不擅自抹掉意图：
        // chat 侧写在 function.strict，Responses 侧与 name 同级，两处都认。
        JsonObject nested = ResponsesPayloadBuilder.toFlatTools(
                "[{\"type\":\"function\",\"function\":{\"name\":\"a\",\"strict\":true,"
                        + "\"parameters\":{\"type\":\"object\"}}}]").get(0).getAsJsonObject();
        assertTrue(nested.get("strict").getAsBoolean());

        JsonObject flat = ResponsesPayloadBuilder.toFlatTools(
                "[{\"type\":\"function\",\"name\":\"b\",\"strict\":true,"
                        + "\"parameters\":{\"type\":\"object\"}}]").get(0).getAsJsonObject();
        assertTrue(flat.get("strict").getAsBoolean());

        // 坏值（非布尔）退回默认档 false，不抛异常也不加严
        JsonObject junk = ResponsesPayloadBuilder.toFlatTools(
                "[{\"type\":\"function\",\"name\":\"c\",\"strict\":\"yes\"}]").get(0).getAsJsonObject();
        assertFalse(junk.get("strict").getAsBoolean());
    }

    @Test
    public void alreadyFlatTools_passThroughWithoutDoubleNesting() {
        String flat = "[{\"type\":\"function\",\"name\":\"noop\",\"description\":\"\","
                + "\"parameters\":{\"type\":\"object\"}}]";
        JsonArray tools = ResponsesPayloadBuilder.toFlatTools(flat);
        assertEquals(1, tools.size());
        assertEquals("noop", tools.get(0).getAsJsonObject().get("name").getAsString());
        // 空 description 不下发（服务商对空串的处理不一致，缺省更安全）
        assertFalse(tools.get(0).getAsJsonObject().has("description"));
    }

    @Test
    public void malformedTools_doNotThrowAndFallBackToEmptySchema() {
        // 混进非对象元素、缺 parameters、整体不是数组：都不能让请求构建失败
        assertEquals(1, ResponsesPayloadBuilder.toFlatTools(
                "[\"junk\", {\"type\":\"function\",\"name\":\"a\"}]").size());
        JsonObject kept = ResponsesPayloadBuilder.toFlatTools(
                "[\"junk\", {\"type\":\"function\",\"name\":\"a\"}]").get(0).getAsJsonObject();
        assertEquals("object", kept.getAsJsonObject("parameters").get("type").getAsString());
        assertEquals(0, ResponsesPayloadBuilder.toFlatTools("{not an array").size());
        assertEquals(0, ResponsesPayloadBuilder.toFlatTools(null).size());
        assertEquals(0, ResponsesPayloadBuilder.toFlatTools("   ").size());
    }

    // ---------- 请求体顶层字段 ----------

    @Test
    public void build_setsModelStreamAndTokenLimitsOnlyWhenMeaningful() {
        JsonArray messages = new JsonArray();
        messages.add(msg("user", "hi"));

        JsonObject full = ResponsesPayloadBuilder.build("gpt-5", messages,
                "[{\"type\":\"function\",\"name\":\"a\"}]", 512, 0.7, true);
        assertEquals("gpt-5", full.get("model").getAsString());
        assertTrue(full.get("stream").getAsBoolean());
        assertEquals(512, full.get("max_output_tokens").getAsInt());
        assertEquals(0.7, full.get("temperature").getAsDouble(), 0.0001);
        assertTrue(full.has("tools"));

        JsonObject minimal = ResponsesPayloadBuilder.build("m", messages, null, 0, -1, false);
        assertFalse("≤0 就不下发，交给服务商默认值", minimal.has("max_output_tokens"));
        assertFalse("负数视为未设置", minimal.has("temperature"));
        assertFalse("非流式不带 stream", minimal.has("stream"));
        assertFalse("无工具就不带 tools 字段（部分端点对空数组会报参数错误）",
                minimal.has("tools"));
    }

    @Test
    public void reasoningEffort_isNestedObjectAndOmittedWhenUnset() {
        JsonArray messages = new JsonArray();
        messages.add(msg("user", "hi"));

        JsonObject with = ResponsesPayloadBuilder.build("deepseek-v4-pro", messages, null,
                1024, 0.7, true, "high");
        // Responses 用的是嵌套 reasoning:{effort}，不是 chat 侧的扁平 reasoning_effort
        assertEquals("high", with.getAsJsonObject("reasoning").get("effort").getAsString());
        assertFalse(with.has("reasoning_effort"));

        JsonObject blank = ResponsesPayloadBuilder.build("m", messages, null,
                1024, 0.7, true, "   ");
        assertFalse("空档位不占字段（未声明档位的模型交给服务端默认）",
                blank.has("reasoning"));

        JsonObject legacy = ResponsesPayloadBuilder.build("m", messages, null,
                1024, 0.7, true);
        assertFalse("旧签名保持不带思考参数", legacy.has("reasoning"));
    }
    @Test
    public void assistantWithNoArguments_getsEmptyJsonObjectArguments() {
        JsonObject assistant = msg("assistant", "");
        JsonArray calls = new JsonArray();
        calls.add(call("c1", "time_date", null));
        assistant.add("tool_calls", calls);

        JsonArray input = ResponsesPayloadBuilder.toInputItems(single(assistant));
        assertEquals(1, input.size());
        // Responses 要求 arguments 是 JSON 字符串；缺字段会让某些端点直接 400
        assertEquals(ResponsesPayloadBuilder.EMPTY_ARGUMENTS,
                input.get(0).getAsJsonObject().get("arguments").getAsString());
    }

    // ==================== 图片条目（input_image） ====================

    /**
     * chat 侧 image_url 是对象、Responses 侧是**字符串**：写成 chat 形状服务商不会报错，
     * 只会静默忽略图片，表现是"模型说它看不到图"。这条用例锁住字符串形状与 detail 同级。
     */
    @Test
    public void inputImageItem_isResponsesShape_notChatShape() {
        JsonObject item = ResponsesPayloadBuilder.inputImageItem("data:image/png;base64,AAAA");
        assertEquals("input_image", item.get("type").getAsString());
        assertTrue("image_url 必须是字符串（chat 侧才是嵌套对象）",
                item.get("image_url").isJsonPrimitive());
        assertEquals("data:image/png;base64,AAAA", item.get("image_url").getAsString());
        assertEquals("detail 与 image_url 同级", "auto", item.get("detail").getAsString());
    }

    @Test
    public void inputImageItem_skipsBlankDataUrl() {
        assertEquals(null, ResponsesPayloadBuilder.inputImageItem(null));
        assertEquals(null, ResponsesPayloadBuilder.inputImageItem("   "));
    }

    /** 官方建议图片块排在文本块之前（Anthropic 明写、Gemini 最佳实践同样），两条链路顺序需一致 */
    @Test
    public void userMessageWithImages_putsImagesBeforeText() {
        JsonObject user = ResponsesPayloadBuilder.userMessageWithImages("这两张图差别在哪？",
                java.util.Arrays.asList("data:image/jpeg;base64,AAA",
                        null,
                        "data:image/webp;base64,BBB"));
        assertEquals("user", user.get("role").getAsString());
        JsonArray content = user.getAsJsonArray("content");
        assertEquals("空元素被跳过，只留两张图 + 一段文本", 3, content.size());
        assertEquals("input_image", content.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("input_image", content.get(1).getAsJsonObject().get("type").getAsString());
        assertEquals("input_text", content.get(2).getAsJsonObject().get("type").getAsString());
        assertEquals("这两张图差别在哪？",
                content.get(2).getAsJsonObject().get("text").getAsString());
    }

    /** 纯图发送（无文字）：只有图片条目也合法，但不能留一个空 text 条目占位 */
    @Test
    public void userMessageWithImages_omitsBlankTextItem() {
        JsonObject user = ResponsesPayloadBuilder.userMessageWithImages("   ",
                java.util.Collections.singletonList("data:image/png;base64,AAA"));
        JsonArray content = user.getAsJsonArray("content");
        assertEquals(1, content.size());
        assertEquals("input_image", content.get(0).getAsJsonObject().get("type").getAsString());
    }
}
