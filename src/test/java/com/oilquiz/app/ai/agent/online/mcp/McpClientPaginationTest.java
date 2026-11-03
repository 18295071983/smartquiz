package com.oilquiz.app.ai.agent.online.mcp;

import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.List;

/**
 * tools/list 分页：服务端返回 nextCursor 时必须继续拉，不能只取第一页。
 * 用假 transport 验证协议层，不连网。
 */
public class McpClientPaginationTest {

    @Test
    public void followsNextCursorUntilEmpty() throws Exception {
        FakeTransport fake = new FakeTransport();
        McpClient client = new McpClient(new McpServerConfig(
                "test", "Test", "https://example.com/mcp",
                McpServerConfig.Transport.STREAMABLE_HTTP, "", true, false));
        Field f = McpClient.class.getDeclaredField("transport");
        f.setAccessible(true);
        f.set(client, fake);

        List<McpClient.McpToolInfo> tools = client.listTools();
        assertEquals(3, tools.size());
        assertEquals("a", tools.get(0).name);
        assertEquals("b", tools.get(1).name);
        assertEquals("c", tools.get(2).name);
        assertEquals("应拉两页（第一页带 nextCursor）", 2, fake.listCalls);
        assertEquals("p2", fake.lastCursor);
        assertEquals("2025-06-18", fake.protocolVersion);
    }

    @Test
    public void stopsWhenCursorRepeats() throws Exception {
        FakeTransport fake = new FakeTransport();
        fake.repeatCursor = true;
        McpClient client = new McpClient(new McpServerConfig(
                "test", "Test", "https://example.com/mcp",
                McpServerConfig.Transport.STREAMABLE_HTTP, "", true, false));
        Field f = McpClient.class.getDeclaredField("transport");
        f.setAccessible(true);
        f.set(client, fake);

        List<McpClient.McpToolInfo> tools = client.listTools();
        assertEquals("重复游标应停止，不应无限翻页", 3, tools.size());
        assertEquals(2, fake.listCalls);
    }

    /** 假传输：按方法返回初始化与分页结果。 */
    private static class FakeTransport implements McpTransport {
        int listCalls;
        String lastCursor;
        boolean repeatCursor;
        String protocolVersion;

        @Override
        public void setProtocolVersion(String version) {
            protocolVersion = version;
        }

        @Override
        public void connect() {
        }

        @Override
        public JSONObject send(JSONObject request, long timeoutMs) throws Exception {
            String method = request.optString("method");
            if ("initialize".equals(method)) {
                JSONObject r = new JSONObject();
                r.put("protocolVersion", "2025-06-18");
                r.put("serverInfo", new JSONObject().put("name", "fake"));
                JSONObject resp = new JSONObject();
                resp.put("id", request.optLong("id"));
                resp.put("result", r);
                return resp;
            }
            if ("notifications/initialized".equals(method)) return null;
            if ("tools/list".equals(method)) {
                listCalls++;
                JSONObject params = request.optJSONObject("params");
                lastCursor = params != null ? params.optString("cursor", "") : "";
                JSONArray arr = new JSONArray();
                if (listCalls == 1) {
                    arr.put(new JSONObject().put("name", "a"));
                    arr.put(new JSONObject().put("name", "b"));
                } else {
                    arr.put(new JSONObject().put("name", repeatCursor ? "a" : "c"));
                }
                JSONObject result = new JSONObject();
                result.put("tools", arr);
                if (listCalls == 1 || (repeatCursor && listCalls < 3)) {
                    result.put("nextCursor", "p2");
                }
                JSONObject resp = new JSONObject();
                resp.put("id", request.optLong("id"));
                resp.put("result", result);
                return resp;
            }
            throw new IllegalArgumentException("unexpected method: " + method);
        }

        @Override
        public void close() {
        }

        @Override
        public boolean isConnected() {
            return true;
        }
    }
}
