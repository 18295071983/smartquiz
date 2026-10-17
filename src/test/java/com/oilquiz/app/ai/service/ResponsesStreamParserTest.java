package com.oilquiz.app.ai.service;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link ResponsesStreamParser} 的回归：SSE 事件语义 + 真 HTTP 传输。
 *
 * <p>事件序列按 OpenAI Responses 公开规范构造（{@code delta} 是字符串、工具调用走
 * {@code output_item.added → function_call_arguments.delta → output_item.done}、终态是
 * {@code response.completed/failed/incomplete}）。这能证明解析与聚合逻辑自洽；<b>不能</b>
 * 证明各家兼容层的事件顺序与私有字段 —— 那仍需连真实端点对拍（见 ROADMAP 7.1）。</p>
 *
 * <p>HTTP 侧不用 JDK 的 {@code com.sun.net.httpserver}（不在 android.jar 里，Android 单元测试
 * 编译不过），也没引 {@code mockwebserver}（不在离线依赖缓存里）。手搓 {@link ServerSocket}
 * + chunked 帧反而能精确制造两种真机上才会遇到的形态：UTF-8 半包、终态后不收帧。</p>
 */
public class ResponsesStreamParserTest {

    /** 记录回调的 Sink，便于断言"增量是否真的逐块外发过"。 */
    private static class RecordingSink implements ResponsesStreamParser.Sink {
        final List<String> text = new ArrayList<>();
        final List<String> reasoning = new ArrayList<>();
        final List<String> readyCalls = new ArrayList<>();
        int usageCalls;
        int input;
        int output;
        int reasoningTokens;
        int cachedTokens;

        @Override
        public void onTextDelta(String delta) {
            text.add(delta);
        }

        @Override
        public void onReasoningDelta(String delta) {
            reasoning.add(delta);
        }

        @Override
        public void onFunctionCallReady(ResponsesStreamParser.FunctionCall call) {
            readyCalls.add(call.name + "|" + call.arguments);
        }

        @Override
        public void onUsage(int inputTokens, int outputTokens, int reasoningTokens,
                            int cachedTokens) {
            usageCalls++;
            input = inputTokens;
            output = outputTokens;
            this.reasoningTokens = reasoningTokens;
            this.cachedTokens = cachedTokens;
        }
    }

    private static ResponsesStreamParser.Result parse(ResponsesStreamParser.Sink sink,
                                                      String sse) throws IOException {
        return new ResponsesStreamParser(sink).parse(new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(
                        sse.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8)));
    }

    private static String ev(String json) {
        return "data: " + json + "\n\n";
    }

    // ---------- 正文 ----------

    @Test
    public void stringDeltaTokens_areEmittedInOrderAndAccumulated() throws Exception {
        // 这一条就是旧内联解析的死因：它要求 delta 是 JsonObject，字符串片段一律被丢弃
        StringBuilder sse = new StringBuilder();
        sse.append(ev("{\"type\":\"response.created\"}"));
        sse.append(ev("{\"type\":\"response.output_text.delta\",\"delta\":\"你\"}"));
        sse.append(ev("{\"type\":\"response.output_text.delta\",\"delta\":\"好，中\"}"));
        sse.append(ev("{\"type\":\"response.output_text.delta\",\"delta\":\"文\"}"));
        sse.append(ev("{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}"));

        RecordingSink sink = new RecordingSink();
        ResponsesStreamParser.Result r = parse(sink, sse.toString());

        assertEquals("你好，中文", r.text);
        assertEquals(3, sink.text.size());
        assertEquals(ResponsesStreamParser.Result.STATUS_COMPLETED, r.status);
        assertEquals("stop", r.finishReason());
        assertFalse(r.isFailed());
    }

    @Test
    public void objectDeltaShape_isAlsoTolerated() throws Exception {
        // 各家兼容层确有分歧（存在对象形态），两种形状都吃，不因此丢字
        String sse = ev("{\"type\":\"response.output_text.delta\",\"delta\":{\"text\":\"甲\"}}")
                + ev("{\"type\":\"response.completed\"}");
        assertEquals("甲", parse(new RecordingSink(), sse).text);
    }

    @Test
    public void outputTextDoneBackfillsMissingTail() throws Exception {
        // 帧被截断导致最后一个增量丢失时，用 done 的整串补齐差额（且不重复发字）
        String sse = ev("{\"type\":\"response.output_text.delta\",\"delta\":\"AB\"}")
                + ev("{\"type\":\"response.output_text.done\",\"text\":\"ABC\"}")
                + ev("{\"type\":\"response.completed\"}");
        RecordingSink sink = new RecordingSink();
        ResponsesStreamParser.Result r = parse(sink, sse);
        assertEquals("ABC", r.text);
        assertEquals("只补差额，不重发已收片段", 2, sink.text.size());
        assertEquals("C", sink.text.get(1));
    }

    // ---------- 思考与用量 ----------

    @Test
    public void reasoningDeltasAndUsage_areSeparated() throws Exception {
        String sse = ev("{\"type\":\"response.reasoning_text.delta\",\"delta\":\"先想\"}")
                + ev("{\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"一下\"}")
                + ev("{\"type\":\"response.output_text.delta\",\"delta\":\"答案\"}")
                + ev("{\"type\":\"response.completed\",\"response\":{\"usage\":{"
                + "\"input_tokens\":120,\"output_tokens\":34,"
                + "\"output_tokens_details\":{\"reasoning_tokens\":9},"
                + "\"input_tokens_details\":{\"cached_tokens\":50}}}}");
        RecordingSink sink = new RecordingSink();
        ResponsesStreamParser.Result r = parse(sink, sse);

        assertEquals("答案", r.text);
        assertEquals("先想一下", r.reasoning);
        assertEquals(1, sink.usageCalls);
        assertEquals(120, sink.input);
        assertEquals(34, sink.output);
        assertEquals(9, sink.reasoningTokens);
        assertEquals(50, sink.cachedTokens);
    }

    // ---------- 工具调用聚合（第 2 步要用的骨架，本轮先把聚合本身验掉） ----------

    @Test
    public void functionCallArguments_aggregatePerItemIdAndFireOnce() throws Exception {
        String sse = ev("{\"type\":\"response.output_item.added\",\"item_id\":\"item_a\","
                + "\"item\":{\"id\":\"item_a\",\"type\":\"function_call\",\"call_id\":\"call_1\","
                + "\"name\":\"get_weather\"}}")
                + ev("{\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"item_a\","
                + "\"delta\":\"{\\\"city\\\":\")")
                + ev("{\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"item_a\","
                + "\"delta\":\"\\\"北京\\\"}\"}")
                + ev("{\"type\":\"response.function_call_arguments.done\",\"item_id\":\"item_a\","
                + "\"arguments\":\"{\\\"city\\\":\\\"北京\\\"}\"}")
                + ev("{\"type\":\"response.output_item.done\",\"item_id\":\"item_a\","
                + "\"item\":{\"id\":\"item_a\",\"type\":\"function_call\",\"call_id\":\"call_1\","
                + "\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"北京\\\"}\"}}")
                + ev("{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}");

        RecordingSink sink = new RecordingSink();
        ResponsesStreamParser.Result r = parse(sink, sse);

        assertEquals(1, r.functionCalls.size());
        ResponsesStreamParser.FunctionCall call = r.functionCalls.get(0);
        assertEquals("call_1", call.callId);
        assertEquals("get_weather", call.name);
        assertEquals("{\"city\":\"北京\"}", call.arguments);
        assertTrue(call.argumentsFinalized);
        assertEquals("tool_calls", r.finishReason());
        // arguments.done 与 output_item.done 描述的是同一个 call：只许外发一次，
        // 否则接入 Agent 循环后同一工具会被执行两遍（工具是有真实副作用的）
        assertEquals(1, sink.readyCalls.size());
    }

    @Test
    public void parallelFunctionCalls_keepAppearanceOrder() throws Exception {
        String sse = ev("{\"type\":\"response.output_item.added\",\"item_id\":\"i1\","
                + "\"item\":{\"id\":\"i1\",\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"a\"}}")
                + ev("{\"type\":\"response.output_item.added\",\"item_id\":\"i2\","
                + "\"item\":{\"id\":\"i2\",\"type\":\"function_call\",\"call_id\":\"c2\",\"name\":\"b\"}}")
                + ev("{\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"i2\",\"delta\":\"{}\"}")
                + ev("{\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"i1\",\"delta\":\"{}\"}")
                + ev("{\"type\":\"response.completed\"}");
        ResponsesStreamParser.Result r = parse(new RecordingSink(), sse);
        assertEquals(2, r.functionCalls.size());
        // 交错到达也要按出现顺序回填，否则 UI 与 call_id 配对会错乱
        assertEquals("c1", r.functionCalls.get(0).callId);
        assertEquals("c2", r.functionCalls.get(1).callId);
    }

    @Test
    public void functionCallWithoutAddedEvent_isStillCollected() throws Exception {
        // 兼容层可能跳过 added 直接给 delta/done：不能因为没开槽就把工具丢掉
        String sse = ev("{\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"z\","
                + "\"delta\":\"{\\\"q\\\":1}\"}")
                + ev("{\"type\":\"response.output_item.done\",\"item_id\":\"z\","
                + "\"item\":{\"id\":\"z\",\"type\":\"function_call\",\"call_id\":\"cz\",\"name\":\"search\"}}")
                + ev("{\"type\":\"response.completed\"}");
        ResponsesStreamParser.Result r = parse(new RecordingSink(), sse);
        assertEquals(1, r.functionCalls.size());
        assertEquals("search", r.functionCalls.get(0).name);
        assertEquals("{\"q\":1}", r.functionCalls.get(0).arguments);
    }

    // ---------- 终态与容错 ----------

    @Test
    public void failedEvent_marksFailureWithReason() throws Exception {
        String sse = ev("{\"type\":\"response.output_text.delta\",\"delta\":\"半句\"}")
                + ev("{\"type\":\"response.failed\",\"response\":{\"status\":\"failed\","
                + "\"error\":{\"message\":\"上下文超限\"}}}")
                + ev("{\"type\":\"response.output_text.delta\",\"delta\":\"不该再出现\"}");
        RecordingSink sink = new RecordingSink();
        ResponsesStreamParser.Result r = parse(sink, sse);
        assertTrue(r.isFailed());
        assertEquals("上下文超限", r.error);
        assertEquals("半句", r.text);
        assertEquals("终态后不再读取后续事件", 1, sink.text.size());
    }

    @Test
    public void errorEvent_marksFailure() throws Exception {
        ResponsesStreamParser.Result r = parse(new RecordingSink(),
                ev("{\"type\":\"error\",\"error\":{\"code\":\"rate_limit\",\"message\":\"稍后再试\"}}"));
        assertTrue(r.isFailed());
        assertEquals("稍后再试", r.error);
    }

    @Test
    public void errorEventWithoutMessage_fallsBackToCode() throws Exception {
        ResponsesStreamParser.Result r = parse(new RecordingSink(),
                ev("{\"type\":\"error\",\"error\":{\"code\":\"server_error\"}}"));
        assertTrue(r.isFailed());
        assertEquals("server_error", r.error);
    }

    @Test
    public void incomplete_keepsPartialTextAndFlagsTruncation() throws Exception {
        String sse = ev("{\"type\":\"response.output_text.delta\",\"delta\":\"写了一半\"}")
                + ev("{\"type\":\"response.incomplete\",\"response\":{\"status\":\"incomplete\"}}");
        ResponsesStreamParser.Result r = parse(new RecordingSink(), sse);
        assertEquals(ResponsesStreamParser.Result.STATUS_INCOMPLETE, r.status);
        assertEquals("写了一半", r.text);
        assertFalse("截断不算失败（上层按已产出内容收尾）", r.isFailed());
    }

    @Test
    public void streamEndedWithoutTerminalEvent_isNotFailure() throws Exception {
        // 连接被回收/代理截帧：已产出的内容仍然可用，不能判成错误
        String sse = ev("{\"type\":\"response.output_text.delta\",\"delta\":\"尾流缺失\"}");
        ResponsesStreamParser.Result r = parse(new RecordingSink(), sse);
        assertEquals(ResponsesStreamParser.Result.STATUS_ENDED, r.status);
        assertEquals("尾流缺失", r.text);
        assertFalse(r.isFailed());
    }

    @Test
    public void heartbeatsCommentsAndJunkLines_areSkipped() throws Exception {
        String sse = ": keep-alive\n\n"
                + "id: 12\n"
                + ": ping\n"
                + ev("{\"type\":\"response.output_text.delta\",\"delta\":\"ok\"}")
                + "data: not-json-at-all\n\n"
                + "data: \n\n"
                + "data: [DONE]\n\n";
        ResponsesStreamParser.Result r = parse(new RecordingSink(), sse);
        assertEquals("ok", r.text);
        assertFalse(r.isFailed());
    }

    @Test
    public void eventTypeFromEventLine_whenDataHasNoType() throws Exception {
        String sse = "event: response.output_text.delta\ndata: {\"delta\":\"靠 event 行\"}\n\n"
                + "event: response.completed\ndata: {}\n\n";
        assertEquals("靠 event 行", parse(new RecordingSink(), sse).text);
    }

    @Test
    public void nullAndEmptyDeltas_doNotBreakAccumulation() throws Exception {
        String sse = ev("{\"type\":\"response.output_text.delta\",\"delta\":null}")
                + ev("{\"type\":\"response.output_text.delta\"}")
                + ev("{\"type\":\"response.output_text.delta\",\"delta\":\"x\"}")
                + ev("{\"type\":\"response.completed\"}");
        RecordingSink sink = new RecordingSink();
        assertEquals("x", parse(sink, sse).text);
        assertEquals(1, sink.text.size());
    }

    @Test
    public void terminalMessageArray_backfillsTextWhenNoDeltasArrive() throws Exception {
        // 增量全被吞（极少数代理）时，用 completed 里的 output 数组兜底正文
        String sse = ev("{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\","
                + "\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\","
                + "\"text\":\"兜底正文\"}]}]}}");
        RecordingSink sink = new RecordingSink();
        ResponsesStreamParser.Result r = parse(sink, sse);
        assertEquals("兜底正文", r.text);
        assertEquals("兜底也走一次增量外发，UI 不会空屏", 1, sink.text.size());
    }

    // ---------- 传输形态：半包与"终态后即收敛" ----------
    //
    // 这一组不用 socket：本机环回端口被安全软件的 LSP 拦掉（实测 accept 成功但写入被中止），
    // 那只会把"解析对不对"变成"环境通不通"。改用自定义流直接构造真机上才会出现的两种形状：
    // 一次只交付 1 字节（TCP 半包）、以及终态之后还挂着长尾数据（服务端不关连接）。

    @Test
    public void multibyteUtf8SplitOneBytePerRead_isNotCorrupted() throws Exception {
        // 一个中文字符的 3 个字节被分到多次 read()：解析器走 InputStreamReader(UTF_8) + 行读取，
        // 必须自己把半包拼回来，不能出乱码也不能丢字
        String body = ev("{\"type\":\"response.output_text.delta\",\"delta\":\"密钥存储改造完成\"}")
                + ev("{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}");
        final byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        final AtomicInteger byteReads = new AtomicInteger();

        InputStream oneByteAtATime = new InputStream() {
            private int pos;

            @Override
            public int read() {
                byteReads.incrementAndGet();
                return pos < raw.length ? raw[pos++] & 0xFF : -1;
            }

            @Override
            public int read(byte[] buf, int off, int len) {
                // 最多交付 1 字节，强制把多字节字符切开
                byteReads.incrementAndGet();
                if (pos >= raw.length) return -1;
                buf[off] = raw[pos++];
                return 1;
            }
        };

        ResponsesStreamParser.Result r = new ResponsesStreamParser(new RecordingSink())
                .parse(new BufferedReader(new InputStreamReader(
                        oneByteAtATime, StandardCharsets.UTF_8)));

        assertEquals("密钥存储改造完成", r.text);
        assertEquals(ResponsesStreamParser.Result.STATUS_COMPLETED, r.status);
        assertTrue("确实是被切碎着交付的", byteReads.get() > raw.length / 2);
    }

    @Test
    public void terminalEventStopsReading_soCallerNeverHangs() throws Exception {
        // completed 之后流还挂着（服务端不关连接）：解析器必须按终态返回，
        // 不再向下游要任何数据。否则 readLine() 会阻塞，界面永远停在"生成中"
        // —— 这正是旧实现的行为缺陷之一。
        String body = ev("{\"type\":\"response.output_text.delta\",\"delta\":\"够了\"}")
                + ev("{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}")
                + ev("{\"type\":\"response.output_text.delta\",\"delta\":\"不该被读到\"}");

        TailTrackingReader reader = new TailTrackingReader(body);
        // 缓冲区取 1：让"是否还要数据"这件事暴露在每次 read() 上，而不是被大缓冲吞掉
        ResponsesStreamParser.Result r = new ResponsesStreamParser(new RecordingSink())
                .parse(new BufferedReader(reader, 1));

        assertEquals("够了", r.text);
        assertEquals(ResponsesStreamParser.Result.STATUS_COMPLETED, r.status);
        assertEquals("终态之后不该再向流要数据", 0, reader.readsAfterTerminal);
    }

    @Test
    public void streamWithoutTerminalEvent_isReadToEOFAndNotTreatedAsFailure() throws Exception {
        // 只有增量、没有 completed：要读到流结束为止，用量留空、不算失败
        String sse = ev("{\"type\":\"response.output_text.delta\",\"delta\":\"只有正文\"}");
        RecordingSink sink = new RecordingSink();
        ResponsesStreamParser.Result r = parse(sink, sse);
        assertEquals("只有正文", r.text);
        assertEquals(0, sink.usageCalls);
        assertEquals(ResponsesStreamParser.Result.STATUS_ENDED, r.status);
        assertFalse(r.isFailed());
    }

    @Test
    public void nonEventBody_isNotMistakenForContent() throws Exception {
        // 状态码归调用方：非 200 的错误体应在读流前由 getErrorStream 处理。
        // 万一它被当成流喂进来，解析器也不能把 JSON 错误体当正文吐给用户。
        ResponsesStreamParser.Result r = parse(new RecordingSink(),
                "{\"error\":{\"message\":\"bad key\"}}\n\n");
        assertEquals("", r.text);
        assertFalse(r.isFailed());
    }

    /** completed 事件的整条尾巴（含结尾空行） */
    /** 换行常量（避免源码里出现转义歧义） */
    private static final String LF = String.valueOf((char) 10);

    /** completed 事件的整条尾巴（含结尾空行） */
    private static final String EVENT_COMPLETED_TAIL =
            "\"status\":\"completed\"}}" + LF + LF;

    /** 交付完 completed 事件之后，任何一次再读取都会被计数（用于证明解析器已收敛） */
    private static final class TailTrackingReader extends java.io.Reader {
        private final BufferedReader delegate;
        private final String body;
        private int delivered;
        private boolean terminalDelivered;
        int readsAfterTerminal;

        TailTrackingReader(String body) {
            this.body = body;
            this.delegate = new BufferedReader(new InputStreamReader(
                    new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),
                    StandardCharsets.UTF_8));
        }

        @Override
        public int read(char[] cbuf, int off, int len) throws IOException {
            if (terminalDelivered) {
                readsAfterTerminal++;
                return -1;
            }
            int n = delegate.read(cbuf, off, len);
            if (n > 0) {
                delivered += n;
                // 只在 completed 事件**整条**交付完（含结尾空行）之后才算"进入长尾"，
                // 否则截走半条 JSON 会让解析器根本收不到终态，测的就不是收敛了
                String seen = body.substring(0, Math.min(delivered, body.length()));
                if (seen.contains(EVENT_COMPLETED_TAIL)) {
                    terminalDelivered = true;
                }
            } else if (n < 0) {
                terminalDelivered = true;
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
    // ---------- 非流式响应体（webSearch 重路由到 /responses 时走这条路） ----------

    @Test
    public void nonStreamBody_extractsTextAndUsage() {
        String body = "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":["
                + "{\"type\":\"output_text\",\"text\":\"第一段\"},"
                + "{\"type\":\"output_text\",\"text\":\"第二段\"}]}],"
                + "\"usage\":{\"input_tokens\":11,\"output_tokens\":5}}";
        RecordingSink sink = new RecordingSink();
        ResponsesStreamParser.Result r = new ResponsesStreamParser(sink).parseFullResponse(body);

        assertEquals("第一段第二段", r.text);
        assertEquals(ResponsesStreamParser.Result.STATUS_COMPLETED, r.status);
        assertEquals(11, r.inputTokens);
        assertEquals(5, r.outputTokens);
        assertEquals("非流式也走同一处用量出口", 1, sink.usageCalls);
        assertEquals("整段作为一次增量外发", 1, sink.text.size());
    }

    @Test
    public void nonStreamBody_toleratesNullsAndJunkShapes() {
        // 旧实现就是在这里对 JsonNull 抛 UnsupportedOperationException：
        // output[] 里混进字符串/null、content[].type 为 null、字段缺失，
        // 都不能让一句无法解释的"生成失败"取代正文
        String body = "{\"status\":\"completed\",\"output\":[\"junk\",null,"
                + "{\"type\":\"message\",\"content\":["
                + "{\"type\":\"output_text\",\"text\":null},"
                + "{\"type\":\"output_text\"},"
                + "{\"type\":\"other\",\"text\":\"不该进正文\"},"
                + "{\"type\":\"output_text\",\"text\":\"保留\"}]}]}";
        ResponsesStreamParser.Result r =
                new ResponsesStreamParser(new RecordingSink()).parseFullResponse(body);
        assertEquals("保留", r.text);
        assertFalse(r.isFailed());
    }

    @Test
    public void nonStreamBody_collectsFunctionCallItems() {
        // arguments 是"字符串里再装 JSON"，所以这里要两层转义：内层 \\\" 落到 JSON 里才是 \"
        String body = "{\"status\":\"completed\",\"output\":[{"
                + "\"type\":\"function_call\",\"id\":\"i1\",\"call_id\":\"c1\","
                + "\"name\":\"lookup\",\"arguments\":\"{\\\"q\\\":\\\"油藏\\\"}\"}]}";
        ResponsesStreamParser.Result r =
                new ResponsesStreamParser(new RecordingSink()).parseFullResponse(body);
        assertEquals(1, r.functionCalls.size());
        assertEquals("c1", r.functionCalls.get(0).callId);
        assertEquals("lookup", r.functionCalls.get(0).name);
        assertEquals("{\"q\":\"油藏\"}", r.functionCalls.get(0).arguments);
        assertEquals("tool_calls", r.finishReason());
    }

    @Test
    public void nonStreamBody_failedStatusBecomesError() {
        String body = "{\"status\":\"failed\",\"error\":{\"message\":\"余额不足\"}}";
        ResponsesStreamParser.Result r =
                new ResponsesStreamParser(new RecordingSink()).parseFullResponse(body);
        assertTrue(r.isFailed());
        assertEquals("余额不足", r.error);
    }

    @Test
    public void nonStreamBody_incompleteKeepsPartialText() {
        String body = "{\"status\":\"incomplete\",\"output\":[{\"type\":\"message\",\"content\":["
                + "{\"type\":\"output_text\",\"text\":\"写到一半\"}]}]}";
        ResponsesStreamParser.Result r =
                new ResponsesStreamParser(new RecordingSink()).parseFullResponse(body);
        assertEquals("写到一半", r.text);
        assertEquals(ResponsesStreamParser.Result.STATUS_INCOMPLETE, r.status);
        assertFalse("截断不算失败：调用方按已产出内容返回", r.isFailed());
    }

    @Test
    public void nonStreamBody_onlyOutputTextFieldIsUsed() {
        // 少数实现只回整串 output_text，不回 output[] 明细
        String body = "{\"status\":\"completed\",\"output_text\":\"整串正文\"}";
        ResponsesStreamParser.Result r =
                new ResponsesStreamParser(new RecordingSink()).parseFullResponse(body);
        assertEquals("整串正文", r.text);
    }

    @Test
    public void nonStreamBody_invalidJsonFailsLoudlyInsteadOfReturningEmptyText() {
        // 返回空串会被上层当成"模型没说话"；明确判失败才能给出可行动的错误
        ResponsesStreamParser.Result r = new ResponsesStreamParser(new RecordingSink())
                .parseFullResponse("<html>502</html>");
        assertTrue(r.isFailed());
        assertEquals("响应不是合法 JSON", r.error);

        ResponsesStreamParser.Result empty = new ResponsesStreamParser(new RecordingSink())
                .parseFullResponse("");
        assertTrue(empty.isFailed());
    }

}
