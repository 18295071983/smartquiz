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
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * M4 ActionSink 抽象（2026-11-13）。
 *
 * <p>以前"动作往哪送"的判断散在 {@code ComponentActions.notifyResult} 里，加一种去处就要改那个大方法。
 * 这里钉住路由器契约：顺序即优先级、故障隔离、无人认领要返回 false、载荷能互转。</p>
 */
public class ActionRouterTest {

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

    @Test
    public void firstEligibleSinkWins() {
        RecordingSink first = new RecordingSink("first", true, true, false);
        RecordingSink second = new RecordingSink("second", true, true, false);
        ActionRouter router = new ActionRouter().addSink(first).addSink(second);

        assertTrue(router.dispatch(null, ActionEvent.of("T1-C1", "ok")));
        assertEquals(1, first.seen.size());
        assertTrue("后面的 sink 不该再被调用", second.seen.isEmpty());
    }

    @Test
    public void canHandleFalseIsSkipped() {
        RecordingSink skip = new RecordingSink("skip", false, true, false);
        RecordingSink next = new RecordingSink("next", true, true, false);
        ActionRouter router = new ActionRouter().addSink(skip).addSink(next);

        assertTrue(router.dispatch(null, ActionEvent.of("T1-C1", "ok")));
        assertTrue(skip.seen.isEmpty());
        assertEquals(1, next.seen.size());
    }

    @Test
    public void failingSinkFallsThrough() {
        RecordingSink failing = new RecordingSink("failing", true, false, false);
        RecordingSink fallback = new RecordingSink("fallback", true, true, false);
        ActionRouter router = new ActionRouter().addSink(failing).addSink(fallback);

        assertTrue("第一个送不出去应继续问下一个", router.dispatch(null, ActionEvent.of("T1-C1", "v")));
        assertEquals(1, failing.seen.size());
        assertEquals(1, fallback.seen.size());
    }

    @Test
    public void throwingSinkDoesNotBreakChain() {
        RecordingSink throwing = new RecordingSink("throwing", true, true, true);
        RecordingSink fallback = new RecordingSink("fallback", true, true, false);
        ActionRouter router = new ActionRouter().addSink(throwing).addSink(fallback);

        assertTrue("sink 抛异常不能中断路由", router.dispatch(null, ActionEvent.of("T1-C1", "v")));
        assertEquals(1, fallback.seen.size());
    }

    @Test
    public void unhandledReturnsFalse() {
        RecordingSink no = new RecordingSink("no", false, true, false);
        ActionRouter router = new ActionRouter().addSink(no);

        assertFalse(router.dispatch(null, ActionEvent.of("T1-C1", "v")));
        assertFalse("空 componentId 不派发", router.dispatch(null, ActionEvent.of("", "v")));
        assertFalse("null 事件不派发", router.dispatch(null, null));
    }

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

    @Test
    public void dshSinkOnlyClaimsRegisteredIds() {
        DshActionSink sink = new DshActionSink();
        ActionEvent unmarked = ActionEvent.of("T1-C1", "v");
        assertFalse("未登记 id 不该归电脑端", sink.canHandle(unmarked));

        DshComponentIds.mark("T1-C1");
        assertTrue("登记过就该归电脑端", sink.canHandle(unmarked));
        assertTrue("旧前缀仍兼容", sink.canHandle(ActionEvent.of("dsh-7", "v")));

        // send 在无 context 时不投递（也不抛）
        assertFalse(sink.send(null, unmarked));
    }
}
