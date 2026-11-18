package com.oilquiz.app.ai.chat.parts;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

/** 手机端 file_get 帧（2026-11-18，插件 /ws/file 的请求侧）。 */
public class DshFileClientTest {

    @Test
    public void buildsFileGetFrame() throws Exception {
        JSONObject o = new JSONObject(DshFileClient.buildFrame("D:\\x\\a.png", "f_1"));
        assertEquals("file_get", o.getString("type"));
        assertEquals("D:\\x\\a.png", o.getString("path"));
        assertEquals("f_1", o.getString("id"));
    }
}
