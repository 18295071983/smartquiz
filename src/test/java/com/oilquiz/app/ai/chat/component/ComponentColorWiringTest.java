package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

import com.oilquiz.app.R;
import com.oilquiz.app.testsupport.ThemedAppContext;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * 组件层"色系接入"护栏（2026-11-18）。
 *
 * <p>主题里补齐层级/状态/语义容器之后，组件必须真的用上，否则 token 只是摆设。本类钉住：
 * 卡片表面按 M3 elevation 逐级递进；卡片描边走 outlineVariant（不是深色 outline）；
 * alert 四档用各自的语义容器；图表网格/标签走专用 token。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class ComponentColorWiringTest {

    @Test
    public void surfaceLayersClimbInLightTheme() {
        Context ctx = ThemedAppContext.get();
        int prev = ComponentTheme.surfaceLayer(ctx, 0);
        for (int level = 1; level <= 4; level++) {
            int cur = ComponentTheme.surfaceLayer(ctx, level);
            assertNotEquals("每一级抬高都应该有可分辨的色差", prev, cur);
            assertTrue("浅色下抬高 = 变暗（onSurface 叠色）",
                    luminance(cur) < luminance(prev));
            prev = cur;
        }
    }

    @Test
    public void surfaceLayersLiftInDarkTheme() {
        RuntimeEnvironment.setQualifiers("+night");
        Context ctx = ThemedAppContext.get();
        int prev = ComponentTheme.surfaceLayer(ctx, 0);
        for (int level = 1; level <= 4; level++) {
            int cur = ComponentTheme.surfaceLayer(ctx, level);
            assertTrue("深色下抬高 = 变亮", luminance(cur) > luminance(prev));
            prev = cur;
        }
    }

    @Test
    public void cardOutlineUsesSubtleVariantNotDeepOutline() {
        Context ctx = ThemedAppContext.get();
        int deepOutline = ThemeColorsHolder.attr(ctx, R.attr.colorOutline);
        int subtleVariant = ThemeColorsHolder.attr(ctx, R.attr.colorOutlineVariant);
        assertEquals("卡片/分隔线描边应取 outlineVariant", subtleVariant, ComponentTheme.outline(ctx));
        assertNotEquals("不能再用深色 outline 画卡片框", deepOutline, ComponentTheme.outline(ctx));
    }

    @Test
    public void alertCardUsesSemanticContainer() {
        Context ctx = ThemedAppContext.get();
        JSONObject props = new JSONObject();
        try {
            props.put("type", "success");
            props.put("title", "保存成功");
            props.put("content", "文件已写入");
        } catch (Exception ignored) {
        }
        View view = new AlertCardView().createView(ctx,
                new ComponentData("alert_card", props));
        GradientDrawable bg = (GradientDrawable) view.getBackground();
        ColorStateList colors = bg.getColor();
        assertEquals("alert_card 底色必须是 successContainer",
                ComponentTheme.successContainer(ctx), colors.getDefaultColor());
    }

    @Test
    public void todoAndProgressCardsUseThemeSurfaceAndOutline() {
        Context ctx = ThemedAppContext.get();
        JSONObject progressProps = new JSONObject();
        JSONObject todoProps = new JSONObject();
        try {
            progressProps.put("title", "进度");
            progressProps.put("progress", 42);
            todoProps.put("title", "待办");
            todoProps.put("items", new org.json.JSONArray()
                    .put(new JSONObject().put("done", false).put("text", "a")));
        } catch (Exception ignored) {
        }

        View progress = new ProgressCardView().createView(ctx,
                new ComponentData("progress_card", progressProps));
        assertEquals("进度卡底色必须走 ComponentTheme.surface",
                ComponentTheme.surface(ctx),
                ((GradientDrawable) progress.getBackground()).getColor().getDefaultColor());

        View todo = new TodoCardView().createView(ctx,
                new ComponentData("todo_card", todoProps));
        assertEquals("待办卡底色必须走 ComponentTheme.surface",
                ComponentTheme.surface(ctx),
                ((GradientDrawable) todo.getBackground()).getColor().getDefaultColor());
    }

    @Test
    public void chartTokensAreResolvedAndDistinct() {
        Context ctx = ThemedAppContext.get();
        int grid = ComponentTheme.chartGrid(ctx);
        int axis = ComponentTheme.chartAxis(ctx);
        int label = ComponentTheme.chartLabel(ctx);
        assertNotEquals(0, grid);
        assertNotEquals(0, axis);
        assertNotEquals(0, label);
        assertNotEquals("网格线与标签不能同色", grid, label);
    }

    // ==================== 工具 ====================

    /** 包一层避免静态导入 ThemeColors 与测试类同名方法冲突。 */
    private static final class ThemeColorsHolder {
        static int attr(Context ctx, int attrId) {
            return com.oilquiz.app.theme.ThemeColors.attr(ctx, attrId);
        }
    }

    private static double luminance(int color) {
        return 0.2126 * channel((color >> 16) & 0xFF)
                + 0.7152 * channel((color >> 8) & 0xFF)
                + 0.0722 * channel(color & 0xFF);
    }

    private static double channel(int c) {
        double v = c / 255.0;
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }
}
