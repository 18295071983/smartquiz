package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import androidx.test.core.app.ApplicationProvider;

import com.oilquiz.app.ai.chat.component.ComponentData;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.HashMap;
import java.util.Map;

/** ui_component(html, url=...) 的顶层 url 不能丢；否则 HtmlCardView 显示空内容。 */
@RunWith(RobolectricTestRunner.class)
public class SystemUIComponentToolHtmlTest {

    @Test
    public void htmlTopLevelUrlSurvivesToProps() {
        Map<String, Object> params = new HashMap<>();
        params.put("action", "create");
        params.put("component_type", "html");
        params.put("url", "https://example.com/preview.html");
        params.put("title", "HTML 预览");

        AIToolResult result = new SystemUIComponentTool(
                ApplicationProvider.getApplicationContext()).execute(params);
        ComponentData component = result.getComponent();
        assertNotNull("html 组件不应为空", component);
        assertEquals("html", component.type);
        assertEquals("https://example.com/preview.html",
                component.props.optString("url"));
        assertEquals("HTML 预览", component.props.optString("title"));
    }

    @Test
    public void webAliasStillMapsTopLevelUrl() {
        Map<String, Object> params = new HashMap<>();
        params.put("action", "create");
        params.put("component_type", "web");
        params.put("url", "https://example.com/page");

        AIToolResult result = new SystemUIComponentTool(
                ApplicationProvider.getApplicationContext()).execute(params);
        ComponentData component = result.getComponent();
        assertNotNull(component);
        assertEquals("html", component.type);
        assertEquals("https://example.com/page", component.props.optString("url"));
    }
}
