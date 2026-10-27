package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.core.app.ApplicationProvider;

import com.oilquiz.app.ai.chat.status.AgentProgressBus;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.HashMap;
import java.util.Map;

/**
 * ui_component(action=progress) 的参数口径（2026-10-27）。
 *
 * <p>schema 告诉模型"参数可放顶层或 props 内""max_value 也可传 max"，真机让模型复述时它确实是
 * 这么理解的；但实现原先只读顶层 max_value/progress，模型按 schema 传 props/max 会静默丢进度
 * （进度条停在旧值/0）。这里把两条口径钉死：props 能读、max 别名能认、顶层优先。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class SystemUIComponentProgressTest {

    @After
    public void tearDown() {
        AgentProgressBus.clear();
    }

    @Test
    public void progressReadsPropsAndMaxAlias() {
        Map<String, Object> props = new HashMap<>();
        props.put("title", "阶段2/4");
        props.put("message", "读取正文");
        props.put("progress", 25);
        props.put("max", 40);
        Map<String, Object> params = new HashMap<>();
        params.put("action", "progress");
        params.put("props", props);

        AIToolResult result = new SystemUIComponentTool(
                ApplicationProvider.getApplicationContext()).execute(params);

        assertTrue("progress 应执行成功: " + result.getErrorMessage(), result.isSuccess());
        AgentProgressBus.Snapshot s = AgentProgressBus.current();
        assertNotNull("进度插槽应收到快照", s);
        assertEquals("阶段2/4", s.title);
        assertEquals("读取正文", s.message);
        assertEquals(25, s.progress);
        assertEquals(40, s.max);
        assertEquals(62, s.percent());
    }

    @Test
    public void topLevelWinsOverProps() {
        Map<String, Object> props = new HashMap<>();
        props.put("progress", 10);
        props.put("max_value", 100);
        Map<String, Object> params = new HashMap<>();
        params.put("action", "progress");
        params.put("title", "顶层标题");
        params.put("progress", 50);
        params.put("props", props);

        new SystemUIComponentTool(ApplicationProvider.getApplicationContext()).execute(params);

        AgentProgressBus.Snapshot s = AgentProgressBus.current();
        assertNotNull(s);
        assertEquals("顶层标题", s.title);
        assertEquals(50, s.progress);
        assertEquals(100, s.max);
        assertEquals(50, s.percent());
    }
}
