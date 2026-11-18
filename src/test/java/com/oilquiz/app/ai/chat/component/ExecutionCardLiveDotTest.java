package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import com.oilquiz.app.ui.widget.LiveDotsView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 执行类卡片的"还在跑"信号（2026-11-18）。
 *
 * <p>口径：**只有运行中才挂呼吸点**（{@link LiveDotsView}），结束态一律不挂——否则历史里
 * 每张卡都永远在动，用户分不清哪条还活着。这里按卡片各钉一条，防止以后改布局时把点漏成
 * 常驻装饰。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class ExecutionCardLiveDotTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = com.oilquiz.app.testsupport.ThemedAppContext.get();
    }

    @Test
    public void runningToolCallHasLiveDotAndCompletedDoesNot() throws Exception {
        JSONObject running = new JSONObject();
        running.put("toolName", "search");
        running.put("status", "running");
        assertTrue("运行中的工具卡应有一颗呼吸点",
                countLiveDots(render("tool_call", running)) >= 1);

        JSONObject done = new JSONObject();
        done.put("toolName", "search");
        done.put("status", "success");
        assertEquals("完成态不应再挂呼吸点（否则历史里全在动）",
                0, countLiveDots(render("tool_call", done)));
    }

    @Test
    public void runningCommandCardHasLiveDot() throws Exception {
        JSONObject running = new JSONObject();
        running.put("command", "ls -la");
        running.put("status", "running");
        assertTrue("命令卡的'运行中'应带一颗呼吸点",
                countLiveDots(render("dsh_command", running)) >= 1);

        JSONObject done = new JSONObject();
        done.put("command", "ls -la");
        done.put("status", "success");
        assertEquals(0, countLiveDots(render("dsh_command", done)));
    }

    @Test
    public void currentStepUsesLiveDotInsteadOfStaticIcon() throws Exception {
        JSONObject p = new JSONObject();
        JSONArray steps = new JSONArray();
        steps.put(new JSONObject().put("title", "已完成").put("status", "done"));
        steps.put(new JSONObject().put("title", "正在跑").put("status", "current"));
        p.put("steps", steps);
        assertEquals("只有 current 那一步是呼吸点",
                1, countLiveDots(render("steps_card", p)));
    }

    @Test
    public void subagentWithoutStepsIsLiveButFinishedOneIsNot() throws Exception {
        JSONObject fresh = new JSONObject();
        fresh.put("label", "子代理A");
        assertTrue("刚起、还没有步骤的子代理必须显示'它还在'",
                countLiveDots(render("subagent_card", fresh)) >= 1);

        JSONObject p = new JSONObject();
        p.put("label", "子代理A");
        JSONArray steps = new JSONArray();
        steps.put(new JSONObject().put("tool", "read").put("status", "success"));
        p.put("steps", steps);
        assertEquals("全部步骤结束 → 落回静态圆点，不再呼吸",
                0, countLiveDots(render("subagent_card", p)));
    }

    // ---------------- 工具 ----------------

    private View render(String type, JSONObject props) {
        return ComponentRegistry.getInstance().render(ctx, ComponentData.of(type, props));
    }

    private static int countLiveDots(View view) {
        if (view == null) {
            return 0;
        }
        int count = view instanceof LiveDotsView ? 1 : 0;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                count += countLiveDots(group.getChildAt(i));
            }
        }
        return count;
    }
}
