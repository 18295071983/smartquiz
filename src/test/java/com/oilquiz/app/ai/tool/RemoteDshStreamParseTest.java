package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * `/events` 解析：控制帧与"合成事件"要分开处理。
 *
 * <p>插件会直接下发 `approval` / `question` / `stream/delta` / `subagent/activity` /
 * `schedule/changed` 这类顶层事件（不是 `{type:'event', event:…}` 包装）；
 * 以前解析器遇到不认识的 type 直接 return，把它们全吞了。这里钉住两条路径都能 dispatch。</p>
 */
public class RemoteDshStreamParseTest {

    private static final class Capture implements RemoteDshTool.DshEventListener {
        final List<JSONObject> events = new ArrayList<>();
        int readySeq = -1;
        int snapshot = 0;
        boolean ended = false;

        @Override public void onReady(int seq) {
            readySeq = seq;
        }

        @Override public void onSnapshot(int seq, org.json.JSONArray turns) {
            snapshot += 1;
        }

        @Override public void onEvent(JSONObject event) {
            events.add(event);
        }

        @Override public void onEnd() {
            ended = true;
        }

        @Override public void onError(String message) {
        }
    }

    @Test
    public void wrappedEventFrameDispatchesAndAdvancesCursor() {
        Capture c = new Capture();
        int cursor = RemoteDshTool.handleStreamMessage(
                "{\"type\":\"event\",\"event\":{\"seq\":7,\"type\":\"assistant/message\",\"data\":{}}}",
                3, true, c);

        assertEquals(7, cursor);
        assertEquals(1, c.events.size());
        assertEquals("assistant/message", c.events.get(0).optString("type"));
    }

    @Test
    public void syntheticTopLevelEventDispatches() {
        Capture c = new Capture();
        RemoteDshTool.handleStreamMessage(
                "{\"v\":2,\"type\":\"stream/delta\",\"data\":{\"kind\":\"text\",\"text\":\"你\"}}",
                3, true, c);
        RemoteDshTool.handleStreamMessage(
                "{\"type\":\"question\",\"id\":\"q-1\",\"request\":{\"questions\":[]}}",
                3, true, c);
        RemoteDshTool.handleStreamMessage(
                "{\"type\":\"schedule/changed\"}", 3, true, c);

        assertEquals(3, c.events.size());
        assertEquals("stream/delta", c.events.get(0).optString("type"));
        assertEquals("你", c.events.get(0).optJSONObject("data").optString("text"));
        assertEquals("question", c.events.get(1).optString("type"));
        assertEquals("schedule/changed", c.events.get(2).optString("type"));
    }

    @Test
    public void controlFramesStayControl() {
        Capture c = new Capture();
        RemoteDshTool.handleStreamMessage("{\"type\":\"ready\",\"seq\":9}", -1, true, c);
        RemoteDshTool.handleStreamMessage("{\"type\":\"pulse\",\"seq\":10}", 9, true, c);
        RemoteDshTool.handleStreamMessage("{\"type\":\"snapshot\",\"seq\":11,\"turns\":[]}", 9, true, c);

        assertEquals(9, c.readySeq);
        assertEquals(1, c.snapshot);
        assertEquals("控制帧不能当业务事件派发", 0, c.events.size());
        assertTrue(c.snapshot == 1);
    }
}
