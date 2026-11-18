package com.oilquiz.app.ai.tool;

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
 * 新版画布工具 {@code ui_canvas}（2026-11-18）。
 *
 * <p>它与 {@code ui_component} 完全隔离，这里只钉住"入口/参数校验/屏幕信息"这三层，
 * 真实弹窗在真机回归（debug 入口 {@code --es mode canvas_v2}）。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class UICanvasToolTest {

    @Test
    public void toolIsRegisteredUnderItsOwnName() {
        Context ctx = ThemedAppContext.get();
        AIToolManager manager = AIToolManager.getInstance(ctx);
        assertTrue("ui_canvas 必须注册成独立工具", manager.hasTool("ui_canvas"));
        assertTrue("ui_component 不能被顶掉", manager.hasTool("ui_component"));
    }

    @Test
    public void screenInfoReturnsCanvasRecommendations() throws Exception {
        Context ctx = ThemedAppContext.get();
        UICanvasTool tool = new UICanvasTool(ctx);
        Map<String, Object> args = new HashMap<>();
        args.put("action", "screen_info");
        AIToolResult r = tool.execute(args);
        assertTrue(r.isSuccess());
        JSONObject out = new JSONObject(String.valueOf(r.getResult()));
        assertTrue(out.getInt("recommended_canvas_width_dp") >= 240);
        assertTrue(out.getInt("recommended_canvas_max_height_dp") >= 320);
    }

    @Test
    public void createWithoutBoardRejectsMissingLayout() {
        Context ctx = ThemedAppContext.get();
        UICanvasTool tool = new UICanvasTool(ctx);
        Map<String, Object> args = new HashMap<>();
        args.put("action", "create");
        args.put("title", "x");
        AIToolResult r = tool.execute(args);
        assertTrue("既没 layout 也没 template 必须报错", !r.isSuccess());
        assertTrue(String.valueOf(r.getErrorMessage()).contains("layout"));
    }

    @Test
    public void createWithUnknownTemplateFails() {
        Context ctx = ThemedAppContext.get();
        UICanvasTool tool = new UICanvasTool(ctx);
        Map<String, Object> args = new HashMap<>();
        args.put("action", "create");
        args.put("template", "nope");
        AIToolResult r = tool.execute(args);
        assertTrue(!r.isSuccess());
        assertTrue(String.valueOf(r.getErrorMessage()).contains("模板"));
    }

    @Test
    public void getResultOnUnknownComponentFails() {
        Context ctx = ThemedAppContext.get();
        UICanvasTool tool = new UICanvasTool(ctx);
        Map<String, Object> args = new HashMap<>();
        args.put("action", "get_result");
        args.put("component_id", "cv2_not_exist");
        AIToolResult r = tool.execute(args);
        assertTrue(!r.isSuccess());
    }
}
