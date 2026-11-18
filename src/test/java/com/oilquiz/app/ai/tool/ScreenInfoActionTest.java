package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.oilquiz.app.testsupport.ThemedAppContext;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code ui_component(action=screen_info)}（2026-11-18）。
 *
 * <p>Agent 在创建 layout_canvas 前先拿屏幕尺寸，用它算 canvas_width/height，
 * 避免"写死 420dp 在 360dp 机器上被夹"或"只开个半屏小弹窗"。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class ScreenInfoActionTest {

    @Test
    public void screenInfoReturnsMetricsAndCanvasRecommendations() throws Exception {
        Context ctx = ThemedAppContext.get();
        SystemUIComponentTool tool = new SystemUIComponentTool(ctx);
        Map<String, Object> args = new HashMap<>();
        args.put("action", "screen_info");
        AIToolResult result = tool.execute(args);

        assertTrue("screen_info 必须成功: " + result.getErrorMessage(), result.isSuccess());
        JSONObject out = new JSONObject(String.valueOf(result.getResult()));
        assertTrue("屏幕宽（dp）必须为正", out.getInt("width_dp") > 0);
        assertTrue("屏幕高（dp）必须为正", out.getInt("height_dp") > 0);
        assertTrue("density 必须为正", out.getDouble("density") > 0);

        int usableW = out.getInt("usable_width_dp");
        int usableH = out.getInt("usable_height_dp");
        assertEquals("推荐宽 = 可用宽 - 32dp（浮层左右各 16dp）",
                Math.max(240, usableW - 32), out.getInt("recommended_canvas_width_dp"));
        assertEquals("推荐高默认吃可用高的 65%",
                Math.max(320, Math.round(usableH * 0.65f)),
                out.getInt("recommended_canvas_height_dp"));
        assertEquals("auto 上限 = 可用高 - 32dp",
                Math.max(320, usableH - 32), out.getInt("recommended_canvas_max_height_dp"));
        assertTrue(out.has("hint"));
    }
}
