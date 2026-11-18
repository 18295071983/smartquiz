package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * 画布内置模板（2026-11-18）：{@code template=form|settings|dashboard} 起手版式。
 *
 * <p>钉住三件事：模板能生成合法 layout；显式 layout 不被覆盖；默认尺寸只补缺。</p>
 */
public class CanvasTemplatesTest {

    @Test
    public void formTemplateInjectsLayoutAndDefaults() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("template", "form");

        assertEquals("form", CanvasTemplates.apply(params));
        String layout = String.valueOf(params.get("layout"));
        JSONObject json = new JSONObject(layout);
        assertTrue("模板必须带 root", json.has("root"));
        assertTrue("表单模板要有提交按钮", layout.contains("\"type\":\"button\""));
        assertEquals("auto", params.get("canvas_height"));
        assertEquals("80%", params.get("canvas_max_height"));
    }

    @Test
    public void explicitLayoutWinsOverTemplate() {
        Map<String, Object> params = new HashMap<>();
        params.put("template", "settings");
        params.put("layout", "{\"root\":{\"type\":\"column\",\"children\":[]}}");

        CanvasTemplates.apply(params);
        assertEquals("显式 layout 优先，模板不能覆盖",
                "{\"root\":{\"type\":\"column\",\"children\":[]}}", params.get("layout"));
    }

    @Test
    public void explicitSizeWinsOverDefaults() {
        Map<String, Object> params = new HashMap<>();
        params.put("template", "dashboard");
        params.put("canvas_height", "60%");
        params.put("canvas_max_height", "90%");

        CanvasTemplates.apply(params);
        assertEquals("60%", params.get("canvas_height"));
        assertEquals("90%", params.get("canvas_max_height"));
    }

    @Test
    public void unknownTemplateIsNoop() {
        Map<String, Object> params = new HashMap<>();
        params.put("template", "nope");
        assertNull(CanvasTemplates.apply(params));
        assertTrue("未知模板不应注入 layout", params.get("layout") == null);
    }

    @Test
    public void everyTemplateIsValidJson() throws Exception {
        for (String name : CanvasTemplates.names()) {
            String layout = CanvasTemplates.layoutOf(name);
            assertNotNull(name + " 必须有 layout", layout);
            JSONObject json = new JSONObject(layout);
            assertTrue(name + " 必须带 root", json.has("root"));
        }
    }
}
