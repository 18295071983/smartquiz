package com.oilquiz.app.ai.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link NpuEngineRouter.ThinkStreamer} 的**思考/正文分流**测试。
 *
 * <p>锁定用户实测的故障（2026-10-07）："NPU 思考段与正文没有分开，都渲染到主消息里了"。</p>
 *
 * <p>旧实现只按字面 {@code "<think"} / {@code "</think"} 切分且要求成对出现，而 Qwen3.5 的
 * chat template 尾部**已经带了标签**（{@code assistant\n<think>\n\n</think>\n\n}），模型往往
 * 只输出内容、不输出开场标签 → 分流器永不触发 → 思考内容整段进正文。</p>
 *
 * <p>新语义：标签只是"思考/正文的分界"，不要求成对；且以 {@code enable_thinking} 作为
 * 标记前内容归属的判据，因此不依赖"模型是否会输出标签"这种不可控假设。</p>
 */
public class NpuEngineRouterPhaseTest {

    private NpuEngineState st;

    @Before
    public void setUp() {
        st = NpuEngineState.get();
        st.beginInference();   // 每轮从新的推理开始
    }

    /** 收集 emit 出的事件 */
    private static final class CollectingCallback implements com.oilquiz.app.ai.jni.LlamaHelper.JsonCallback {
        final List<String> events = new ArrayList<>();

        @Override
        public void onJson(String json) {
            events.add(json);
        }

        String joined() {
            return String.join("\n", events);
        }

        boolean hasToken(String content) {
            return joined().contains("\"type\":\"token\"") && joined().contains(content);
        }

        boolean hasThinking(String content) {
            return joined().contains("\"type\":\"thinking\"") && joined().contains(content);
        }

        boolean noBodyContains(String content) {
            for (String e : events) {
                if (e != null && e.contains("\"type\":\"token\"") && e.contains(content)) return false;
            }
            return true;
        }
    }

    // ==================== 阶段推进（历史故障 1） ====================

    @Test
    public void 纯正文输出必须把阶段推进到GENERATING() {
        CollectingCallback cb = new CollectingCallback();
        NpuEngineRouter.ThinkStreamer streamer =
                new NpuEngineRouter.ThinkStreamer(cb, "<think>", "</think>", false);

        streamer.feed("你好，这是一段正文回答。");
        streamer.flush();

        assertEquals("正文 token 到达后阶段必须是 GENERATING（否则状态栏停在「处理提示」）",
                NpuEngineState.InferencePhase.GENERATING, st.getInferencePhase());
        assertTrue("正文事件应当已经发出", cb.hasToken("这是一段正文回答"));
    }

    // ==================== 思考/正文分流（历史故障 2） ====================

    /**
     * 模板已给开场标签 → 模型只输出「思考内容 + 结尾标签」。
     * 这是本机 Qwen3.5 的实际形态，也是"分不开"的根因场景。
     */
    @Test
    public void 只有结尾标签时标记前内容必须归入思考区() {
        CollectingCallback cb = new CollectingCallback();
        NpuEngineRouter.ThinkStreamer streamer =
                new NpuEngineRouter.ThinkStreamer(cb, "<think>", "</think>", true);

        streamer.feed("先算一下 2+3=5。");
        streamer.feed("</think>答案是 5。");
        streamer.flush();

        assertTrue("思考内容必须发 thinking 事件", cb.hasThinking("先算一下"));
        assertTrue("正文必须发 token 事件", cb.hasToken("答案是 5"));
        assertTrue("思考内容**不得**出现在正文事件里", cb.noBodyContains("先算一下"));
    }

    /** 模型自己输出成对标签：开场前无内容，标签内是思考、之后是正文 */
    @Test
    public void 成对标签时思考与正文正确分离() {
        CollectingCallback cb = new CollectingCallback();
        NpuEngineRouter.ThinkStreamer streamer =
                new NpuEngineRouter.ThinkStreamer(cb, "<think>", "</think>", true);

        streamer.feed("<think>推理过程</think>结论如下。");
        streamer.flush();

        assertTrue(cb.hasThinking("推理过程"));
        assertTrue(cb.hasToken("结论如下"));
        assertTrue("标签本身不得下发", !cb.joined().contains("<think>"));
        assertTrue(cb.noBodyContains("推理过程"));
    }

    /** 标签跨 chunk 拆分：半个标签不得漏进正文 */
    @Test
    public void 标签跨chunk拆分时不漏碎片() {
        CollectingCallback cb = new CollectingCallback();
        NpuEngineRouter.ThinkStreamer streamer =
                new NpuEngineRouter.ThinkStreamer(cb, "<think>", "</think>", true);

        streamer.feed("思考中");
        streamer.feed("</thi");      // 半个结尾标签，必须被保留而不是当内容发出去
        streamer.feed("nk>正文");
        streamer.flush();

        assertTrue("思考内容应完整归入思考区", cb.hasThinking("思考中"));
        assertTrue("正文应正确", cb.hasToken("正文"));
        // 标签碎片不得出现在任何事件内容里（注意不能拿 "thi" 当判据：
        // 它虽是 "</think" 的前缀，却也是正常词"思考中"的拼音片段）
        assertFalse("不得出现标签碎片", cb.joined().contains("</thi"));
        assertFalse("标签本身不得下发", cb.joined().contains("<think>"));
    }

    /** 未请求思考时：即便没有标签，内容也必须是正文（不能被当成思考段吞掉） */
    @Test
    public void 未请求思考时内容全部为正文() {
        CollectingCallback cb = new CollectingCallback();
        NpuEngineRouter.ThinkStreamer streamer =
                new NpuEngineRouter.ThinkStreamer(cb, "<think>", "</think>", false);

        streamer.feed("这是一个直接回答，没有思考段。");
        streamer.flush();

        assertTrue("无思考请求时内容必须是正文", cb.hasToken("直接回答"));
        assertFalse("不应产生思考事件", cb.joined().contains("\"type\":\"thinking\""));
    }

    /** 只有开场标签、被截断（无结尾）：其后内容仍属思考段 */
    @Test
    public void 只有开场标签时其后为思考段() {
        CollectingCallback cb = new CollectingCallback();
        NpuEngineRouter.ThinkStreamer streamer =
                new NpuEngineRouter.ThinkStreamer(cb, "<think>", "</think>", true);

        streamer.feed("<think>还没写完就被截断了");
        streamer.flush();

        assertTrue("被截断的思考内容应归入思考区", cb.hasThinking("还没写完"));
        assertFalse("不应漏进正文", cb.hasToken("还没写完"));
    }

    /** 标签大小写不敏感 */
    @Test
    public void 标签大小写不敏感() {
        CollectingCallback cb = new CollectingCallback();
        NpuEngineRouter.ThinkStreamer streamer =
                new NpuEngineRouter.ThinkStreamer(cb, "<think>", "</think>", true);

        streamer.feed("<THINK>大写标签思考</THINK>正文内容");
        streamer.flush();

        assertTrue(cb.hasThinking("大写标签思考"));
        assertTrue(cb.hasToken("正文内容"));
    }

    /** 进入正文后不再识别标签：正文里出现 "<" 之类字符不应被误切 */
    @Test
    public void 正文段内的尖括号不被误判为标签() {
        CollectingCallback cb = new CollectingCallback();
        NpuEngineRouter.ThinkStreamer streamer =
                new NpuEngineRouter.ThinkStreamer(cb, "<think>", "</think>", true);

        streamer.feed("思考</think>正文里写 a<b 和 x>y");
        streamer.flush();

        assertTrue("正文中的比较符号应原样保留", cb.hasToken("a<b"));
    }

    /** 契约层（UI 唯一读到的接口）必须与状态机一致 */
    @Test
    public void 阶段推进后契约上报GENERATING() {
        CollectingCallback cb = new CollectingCallback();
        NpuEngineRouter.ThinkStreamer streamer =
                new NpuEngineRouter.ThinkStreamer(cb, "<think>", "</think>", false);
        streamer.feed("正文");
        streamer.flush();

        assertEquals(com.oilquiz.app.ai.engine.contract.GenSignal.Phase.GENERATING,
                com.oilquiz.app.ai.engine.contract.NpuSignalAdapter.current().phase);
    }
}
