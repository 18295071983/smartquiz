package com.oilquiz.app.ai.chat.component.action;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.oilquiz.app.ai.remote.DshComponentIds;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * M4 ActionSink（2026-11-13）——动态落点表。
 *
 * <p>要求：sink 能在**运行时**注册/替换/摘除；顺序由 priority 决定；可按 channel 过滤；
 * 单个 sink 抛异常不影响后续；无人认领返回 false。</p>
 */
public class ActionRouterTest {

    @Before
    public void setUp() {
        DshComponentIds.clearForTest();
    }

    @After
    public void tearDown() {
        DshComponentIds.clearForTest();
    }

    private static final class RecordingSink implements ActionSink {
        final String name;
        final boolean can;
        final boolean succeeds;
        final boolean throwsOnSend;
        final List<String> seen = new ArrayList<>();

        RecordingSink(String name, boolean can, boolean succeeds, boolean throwsOnSend) {
            this.name = name;
            this.can = can;
            this.succeeds = succeeds;
            this.throwsOnSend = throwsOnSend;
        }

        static RecordingSink ok(String name) {
            return new RecordingSink(name, true, true, false);
        }

        @Override
        public String id() {
            return name;
        }

        @Override
        public boolean canHandle(ActionEvent event) {
            return can;
        }

        @Override
        public boolean send(Context context, ActionEvent event) {
            seen.add(event.componentId + "=" + event.value);
            if (throwsOnSend) throw new IllegalStateException("boom");
            return succeeds;
        }
    }

    // ---- 动态注册与顺序 ----

    @Test
    public void registerAtRuntimeTakesEffectImmediately() {
        ActionRouter router = new ActionRouter();
        assertFalse("没有 sink 时无人认领", router.dispatch(null, ActionEvent.of("T1-C1", "v")));

        RecordingSink late = RecordingSink.ok("late");
        router.register("late", 100, late);
        assertTrue(router.dispatch(null, ActionEvent.of("T1-C1", "v")));
        assertEquals(1, late.seen.size());
    }

    @Test
    public void priorityDecidesOrder() {
        RecordingSink low = RecordingSink.ok("low");
        RecordingSink high = RecordingSink.ok("high");
        ActionRouter router = new ActionRouter();
        router.register("low", 900, low);      // 后注册但优先级低（数大）
        router.register("high", 10, high);

        assertTrue(router.dispatch(null, ActionEvent.of("T1-C1", "v")));
        assertEquals(1, high.seen.size());
        assertTrue("优先级高的拿到动作后，低的应跳过", low.seen.isEmpty());
        assertEquals(Arrays.asList("high", "low"), router.sinkIds());
    }

    @Test
    public void samePriorityKeepsRegistrationOrder() {
        RecordingSink first = RecordingSink.ok("first");
        RecordingSink second = RecordingSink.ok("second");
        ActionRouter router = new ActionRouter().register("first", 50, first)
                .register("second", 50, second);
        router.dispatch(null, ActionEvent.of("T1-C1", "v"));
        assertEquals(1, first.seen.size());
        assertTrue(second.seen.isEmpty());
    }

    @Test
    public void unregisterStopsDelivery() {
        RecordingSink sink = RecordingSink.ok("gone");
        ActionRouter router = new ActionRouter().register("gone", 10, sink);
        assertTrue(router.dispatch(null, ActionEvent.of("T1-C1", "v")));

        assertTrue(router.unregister("gone"));
        assertFalse("摘除后应无人认领", router.dispatch(null, ActionEvent.of("T1-C1", "v2")));
        assertEquals(1, sink.seen.size());
        assertFalse("重复摘除是幂等的", router.unregister("gone"));
    }

    @Test
    public void registerSameIdReplacesImplementation() {
        RecordingSink oldSink = RecordingSink.ok("old");
        RecordingSink newSink = RecordingSink.ok("new");
        ActionRouter router = new ActionRouter().register("slot", 10, oldSink);
        router.register("slot", 10, newSink);

        assertEquals(1, router.size());
        assertTrue(router.dispatch(null, ActionEvent.of("T1-C1", "v")));
        assertTrue(oldSink.seen.isEmpty());
        assertEquals(1, newSink.seen.size());
    }

    // ---- 通道过滤 ----

    @Test
    public void channelScopedSinkOnlySeesItsChannel() {
        RecordingSink mcp = RecordingSink.ok("mcp");
        RecordingSink any = RecordingSink.ok("any");
        ActionRouter router = new ActionRouter();
        router.register("mcp", 10, Collections.singleton("mcp"), mcp);
        router.register("any", 900, any);

        router.dispatch(null, ActionEvent.of("T1-C1", "v", "", "chat_ui"));
        assertTrue("channel 不匹配的 sink 不该被调用", mcp.seen.isEmpty());
        assertEquals(1, any.seen.size());

        router.dispatch(null, ActionEvent.of("T1-C2", "v", "", "mcp"));
        assertEquals(1, mcp.seen.size());
    }

    // ---- 故障隔离 / 兜底 ----

    @Test
    public void canHandleFalseIsSkipped() {
        RecordingSink skip = new RecordingSink("skip", false, true, false);
        RecordingSink next = RecordingSink.ok("next");
        ActionRouter router = new ActionRouter().register("skip", 10, skip).register("next", 20, next);

        assertTrue(router.dispatch(null, ActionEvent.of("T1-C1", "ok")));
        assertTrue(skip.seen.isEmpty());
        assertEquals(1, next.seen.size());
    }

    @Test
    public void failingSinkFallsThrough() {
        RecordingSink failing = new RecordingSink("failing", true, false, false);
        RecordingSink fallback = RecordingSink.ok("fallback");
        ActionRouter router = new ActionRouter().register("failing", 10, failing)
                .register("fallback", 20, fallback);

        assertTrue("第一个送不出去应继续问下一个", router.dispatch(null, ActionEvent.of("T1-C1", "v")));
        assertEquals(1, failing.seen.size());
        assertEquals(1, fallback.seen.size());
    }

    @Test
    public void throwingSinkDoesNotBreakChain() {
        RecordingSink throwing = new RecordingSink("throwing", true, true, true);
        RecordingSink fallback = RecordingSink.ok("fallback");
        ActionRouter router = new ActionRouter().register("throwing", 10, throwing)
                .register("fallback", 20, fallback);

        assertTrue("sink 抛异常不能中断路由", router.dispatch(null, ActionEvent.of("T1-C1", "v")));
        assertEquals(1, fallback.seen.size());
    }

    @Test
    public void unhandledReturnsFalse() {
        ActionRouter router = new ActionRouter()
                .register("no", 10, new RecordingSink("no", false, true, false));
        assertFalse(router.dispatch(null, ActionEvent.of("T1-C1", "v")));
        assertFalse("空 componentId 不派发", router.dispatch(null, ActionEvent.of("", "v")));
        assertFalse("null 事件不派发", router.dispatch(null, null));
    }

    // ---- 载荷 ----

    @Test
    public void payloadRoundTrip() {
        JSONObject raw = new JSONObject();
        try {
            raw.put("component_id", "T1-C7");
            raw.put("act", "approve");
            raw.put("channel", "layout");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        ActionEvent ev = ActionEvent.fromPayload(raw);
        assertNotNull(ev);
        assertEquals("T1-C7", ev.componentId);
        assertEquals("approve", ev.value);
        assertEquals("layout", ev.channel);

        JSONObject back = ev.toPayload();
        assertEquals("T1-C7", back.optString("component_id"));
        assertEquals("approve", back.optString("act"));
    }

    @Test
    public void payloadAcceptsLegacyKeys() {
        JSONObject raw = new JSONObject();
        try {
            raw.put("id", "T2-C1");
            raw.put("action", "jobs");
            raw.put("label", "查看任务");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        ActionEvent ev = ActionEvent.fromPayload(raw);
        assertNotNull(ev);
        assertEquals("T2-C1", ev.componentId);
        assertEquals("jobs", ev.value);

        assertNull("没有 id 的载荷应被拒绝", ActionEvent.fromPayload(new JSONObject()));
    }

    // ---- DSH 落点判定 ----

    @Test
    public void dshSinkOnlyClaimsRegisteredIds() {
        DshActionSink sink = new DshActionSink();
        ActionEvent unmarked = ActionEvent.of("T1-C1", "v");
        assertFalse("未登记 id 不该归电脑端", sink.canHandle(unmarked));

        DshComponentIds.mark("T1-C1");
        assertTrue("登记过就该归电脑端", sink.canHandle(unmarked));
        assertTrue("旧前缀仍兼容", sink.canHandle(ActionEvent.of("dsh-7", "v")));

        assertFalse("无 context 时不投递（也不抛）", sink.send(null, unmarked));
    }

    // ---- 默认安装（ComponentActions） ----

    @Test
    public void defaultSinksAreDshThenLocalAndDynamic() {
        com.oilquiz.app.ai.chat.component.ComponentActions.registerSink(
                "page-test", 5, RecordingSink.ok("page-test"));
        List<String> ids = com.oilquiz.app.ai.chat.component.ComponentActions.router().sinkIds();
        assertTrue("动态注册的 sink 应出现在表里：" + ids, ids.contains("page-test"));
        assertTrue("DSH 默认 sink 仍在：" + ids, ids.contains("dsh"));
        assertTrue("本机兜底仍在：" + ids, ids.contains("local"));
        assertTrue("priority 5 应排第一：" + ids, "page-test".equals(ids.get(0)));

        assertTrue(com.oilquiz.app.ai.chat.component.ComponentActions.unregisterSink("page-test"));
        assertFalse("摘除后不应还在：" + com.oilquiz.app.ai.chat.component.ComponentActions.router().sinkIds(),
                com.oilquiz.app.ai.chat.component.ComponentActions.router().has("page-test"));
    }
}
