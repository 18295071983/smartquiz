package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;
import java.util.Map;

/**
 * 通道 state 解析：服务端下发的是 JSON，界面要的是 Map/List。
 *
 * <p>钉住嵌套结构（execution 是对象、caps.missing 是数组），避免界面侧再写一遍转换。</p>
 */
public class RemoteChannelJsonTest {

    @Test
    public void nestedStateBecomesMapsAndLists() {
        JSONObject o = new JSONObject();
        try {
            o.put("session_id", "session-1");
            o.put("live", true);
            o.put("execution", new JSONObject().put("kind", "host").put("policy", "danger-full-access"));
            o.put("caps", new JSONObject().put("missing",
                    new org.json.JSONArray().put("parts.stream")));
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        Map<String, Object> map = RemoteChannel.jsonObjectToMap(o);

        assertEquals("session-1", map.get("session_id"));
        assertEquals(Boolean.TRUE, map.get("live"));
        Object execution = map.get("execution");
        assertTrue(execution instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> ex = (Map<String, Object>) execution;
        assertEquals("host", ex.get("kind"));
        Object caps = map.get("caps");
        assertTrue(caps instanceof Map);
        Object missing = ((Map<?, ?>) caps).get("missing");
        assertTrue(missing instanceof List);
        assertEquals("parts.stream", ((List<?>) missing).get(0));
    }
}
