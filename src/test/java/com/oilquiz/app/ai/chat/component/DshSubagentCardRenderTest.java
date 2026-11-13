package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * 子代理轨迹卡的渲染（2026-11-13，B15 durable）。
 *
 * <p>注册表里必须真的能拿到实现（漏注册 → 掉进 DynamicCardView 原始 JSON 兜底，
 * 用户看到的就是一坨 JSON）。这里直接走 {@link ComponentRegistry#render}，
 * 断言标题、步数与"最多 6 步 + 剩余提示"都按契约出现。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class DshSubagentCardRenderTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private static JSONObject props(int stepCount) {
        JSONObject p = new JSONObject();
        try {
            p.put("label", "子代理A");
            JSONArray steps = new JSONArray();
            for (int i = 0; i < stepCount; i += 1) {
                JSONObject st = new JSONObject();
                st.put("tool", "tool" + i);
                st.put("args", "a" + i);
                st.put("status", i == 0 ? "failed" : "success");
                st.put("result", "r" + i);
                steps.put(st);
            }
            p.put("steps", steps);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return p;
    }

    private static void collectTexts(View v, List<String> out) {
        if (v instanceof TextView) {
            out.add(String.valueOf(((TextView) v).getText()));
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i += 1) {
                collectTexts(g.getChildAt(i), out);
            }
        }
    }

    @Test
    public void rendersLabelStepsAndOverflow() {
        ComponentData data = ComponentData.of("subagent_card", props(8));
        data.source = "dsh";

        View view = ComponentRegistry.getInstance().render(ctx(), data);
        assertNotNull("subagent_card 必须已注册（否则掉进 JSON 兜底）", view);

        List<String> texts = new ArrayList<>();
        collectTexts(view, texts);
        String all = texts.toString();
        assertTrue("应显示子代理标题：" + all, all.contains("子代理 · 子代理A"));
        assertTrue("应显示步数：" + all, all.contains("8 步"));
        assertTrue("应显示第一步工具：" + all, all.contains("tool0"));
        assertTrue("超过 6 步要提示剩余：" + all, all.contains("+2 步"));
        assertTrue("结果摘要应出现：" + all, all.contains("r0"));
    }

    @Test
    public void rendersWithoutSteps() {
        JSONObject p = new JSONObject();
        try {
            p.put("label", "只有标签");
            p.put("lastText", "正在输出");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        View view = ComponentRegistry.getInstance().render(ctx(),
                ComponentData.of("subagent_card", p));
        assertNotNull(view);
        List<String> texts = new ArrayList<>();
        collectTexts(view, texts);
        String all = texts.toString();
        assertTrue(all.contains("只有标签"));
        assertTrue("无 steps 时用 lastText 兜底：" + all, all.contains("正在输出"));
    }
}
