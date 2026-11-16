package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.oilquiz.app.R;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 组件工具卡（DSH 远程 {@code tool_call}）的显示契约（2026-11-16 重设计）。
 *
 * <p>重设计要点：两行结构（图标 + 工具名 + 状态标签 / 参数或结果摘要）；状态用语义色文字，
 * 不再用 emoji（跨 ROM 字形不一致、不能跟主题着色）；长摘要可点击展开；
 * 失败摘要带退出码/错误细节，且连续空白被压缩。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class ToolCallCardViewTest {

    private Context ctx;
    private ToolCallCardView view;

    @Before
    public void setUp() {
        ctx = com.oilquiz.app.testsupport.ThemedAppContext.get();
        view = new ToolCallCardView();
    }

    @Test
    public void successShowsNameStatusAndSummary() {
        View root = render("weather", "success", "", "晴 3.6°");
        assertEquals("完成", textOf(root, "tool_status").getText().toString());
        assertEquals("状态标签应为 success 语义色",
                ComponentColors.success(ctx), textOf(root, "tool_status").getCurrentTextColor());
        assertTrue("摘要应显示在第二行，实际=" + textOf(root, "tool_detail").getText(),
                textOf(root, "tool_detail").getText().toString().contains("晴 3.6°"));
        assertNoEmoji(root);
    }

    @Test
    public void failedShowsExitCodeAndTrimmedDetail() {
        View root = render("shell", "failed",
                "{\"exit_code\":2,\"stderr\":\"line1\\n\\nline2   line3\"}", "");
        assertEquals("失败", textOf(root, "tool_status").getText().toString());
        assertEquals("失败标签应为 error 语义色",
                ComponentColors.error(ctx), textOf(root, "tool_status").getCurrentTextColor());
        String detail = textOf(root, "tool_detail").getText().toString();
        assertTrue("失败详情应包含退出码，实际=" + detail, detail.contains("2"));
        assertTrue("失败详情应包含错误细节，实际=" + detail, detail.contains("line1"));
        assertFalse("连续空白应被压缩（正则 bug 回归）", detail.contains("\n\n"));
        assertNoEmoji(root);
    }

    @Test
    public void runningShowsHintInAccentColorAndArgsWhenNoHint() {
        View root = render("search", "running", "{}", "");
        assertEquals("运行中", textOf(root, "tool_status").getText().toString());
        assertEquals("运行中标签应为 accent 语义色",
                ComponentColors.accent(ctx), textOf(root, "tool_status").getCurrentTextColor());
        assertNoEmoji(root);

        // 没有 statusText 时，第二行退回参数摘要
        View withArgs = renderWithArgs("search", "running", "{\"query\":\"成都天气\"}");
        assertTrue("无 hint 时应显示参数摘要，实际=" + textOf(withArgs, "tool_detail").getText(),
                textOf(withArgs, "tool_detail").getText().toString().contains("query=成都天气"));
    }

    // ---------------- 工具 ----------------

    private View render(String toolName, String status, String result, String summary) {
        JSONObject props = new JSONObject();
        try {
            props.put("toolName", toolName);
            props.put("status", status);
            props.put("result", result);
            if (!summary.isEmpty()) {
                props.put("summary", summary);
            }
        } catch (Exception ignored) {
        }
        return view.createView(ctx, ComponentData.of("tool_call", props));
    }

    private View renderWithArgs(String toolName, String status, String args) {
        JSONObject props = new JSONObject();
        try {
            props.put("toolName", toolName);
            props.put("status", status);
            props.put("args", args);
        } catch (Exception ignored) {
        }
        return view.createView(ctx, ComponentData.of("tool_call", props));
    }

    private TextView textOf(View root, String tag) {
        TextView tv = find(root, tag);
        assertNotNull("应存在 tag=" + tag + " 的 TextView", tv);
        return tv;
    }

    private TextView find(View v, String tag) {
        if (v instanceof TextView && tag.equals(v.getTag())) {
            return (TextView) v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView found = find(g.getChildAt(i), tag);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void assertNoEmoji(View root) {
        String all = collectText(root);
        assertFalse("工具卡不应再出现 emoji，实际=" + all,
                all.contains("🔧") || all.contains("✅") || all.contains("❌") || all.contains("⏳"));
    }

    private String collectText(View v) {
        StringBuilder sb = new StringBuilder();
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null) sb.append(t).append(' ');
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                sb.append(collectText(g.getChildAt(i)));
            }
        }
        return sb.toString();
    }
}
