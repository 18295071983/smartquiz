package com.oilquiz.app.ai.chat.component;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * code_card 字段放宽的回归测试（2026-11-16）。
 *
 * <p>背景：用户侧真机日志出现
 * {@code registered type data mismatch, using dynamic fallback: code_card} ——
 * 因为旧实现要求 {@code code} 或 {@code command} 必填，而实际数据用的是
 * {@code content} 之类字段，整张卡就退回通用兜底卡（用户看到原始 JSON）。
 * 现在只要求 props 非空，正文按别名取值，并把"不落兜底"钉成测试。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class CodeCardViewTest {

    private ComponentData data(String json) throws Exception {
        return ComponentData.of("code_card", new JSONObject(json));
    }

    @Test
    public void rendersWhenOnlyContentFieldPresent() throws Exception {
        CodeCardView view = new CodeCardView();
        ComponentData d = data("{\"title\":\"命令\",\"content\":\"ls -la /sdcard\"}");
        assertTrue("有 content 也必须由 code_card 渲染（不能落兜底）", view.canRender(d));
    }

    @Test
    public void rendersEachAlias() throws Exception {
        CodeCardView view = new CodeCardView();
        for (String key : new String[]{"code", "command", "cmd", "source", "script",
                "snippet", "content", "text", "body"}) {
            ComponentData d = data("{\"" + key + "\":\"echo hi\"}");
            assertTrue("别名 " + key + " 应被接受", view.canRender(d));
        }
    }

    @Test
    public void rendersEvenWithUnknownFieldsOnly() throws Exception {
        CodeCardView view = new CodeCardView();
        // 完全不认识的字段：仍由本卡渲染，正文退回格式化 props（比裸 JSON 兜底卡可读）
        ComponentData d = data("{\"whatever\":\"x\",\"n\":1}");
        assertTrue(view.canRender(d));
    }

    @Test
    public void emptyPropsDoNotRender() throws Exception {
        CodeCardView view = new CodeCardView();
        assertFalse("空 props 不该抢渲染", view.canRender(data("{}")));
        assertFalse("null data 不该抢渲染", view.canRender(null));
    }
}
