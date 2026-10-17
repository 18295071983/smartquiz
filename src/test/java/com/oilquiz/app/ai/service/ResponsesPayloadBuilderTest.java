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
    public void systemAndUserBecomeInputTextItems() {
        JsonArray messages = new JsonArray();
        messages.add(msg("system", "你是答题助手"));
        messages.add(msg("user", "给我出三道题"));

        JsonArray input = ResponsesPayloadBuilder.toInputItems(messages);

        assertEquals(2, input.size());
        assertEquals("system", type(input, 0).get("role").getAsString());
        assertEquals("你是答题助手", type(input, 0).getAsJsonArray("content").get(0)
                .getAsJsonObject().get("text").getAsString());
        assertEquals("input_text", type(input, 0).getAsJsonArray("content").get(0)
                .getAsJsonObject().get("type").getAsString());
        assertEquals("user", type(input, 1).get("role").getAsString());
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
}
