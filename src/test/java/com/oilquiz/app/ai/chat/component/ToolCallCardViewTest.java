package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
 * 工具卡（DSH 远程/组件路径 {@code tool_call}）的显示契约（2026-11-16 重设计）。
 *
 * <p>原实现用 emoji 当状态符号（🔧/✅/❌/⏳）：跨 ROM 字形不一致、不能跟主题着色，
 * 且失败摘要里的空白压缩正则写错（{@code "\\\\s+"}）导致不生效。重设计后钉住：</p>
 * <ul>
 *   <li>不再出现 emoji，状态用语义色文字表达；</li>
 *   <li>成功/失败/运行中分别用 success/error/accent 语义色；</li>
 *   <li>失败时带出退出码/错误细节，且空白被压缩成单空格。</li>
 * </ul>
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
    public void successShowsSummaryInSuccessColorWithoutEmoji() {
        TextView status = statusOf(render("weather", "success", "{}", ""));
        String text = status.getText().toString();
        assertFalse("状态文本不应再出现 emoji", containsEmoji(text));
        assertEquals("成功状态应为 success 语义色",
                ComponentColors.success(ctx), status.getCurrentTextColor());
    }

    @Test
    public void failedShowsExitCodeAndTrimmedDetailInErrorColor() {
        JSONObject p = new JSONObject();
        TextView status = statusOf(render("shell", "failed",
                "{\"exit_code\":2,\"stderr\":\"line1\\n\\nline2   line3\"}", ""));
        String text = status.getText().toString();
        assertFalse("失败文本不应出现 emoji", containsEmoji(text));
        assertTrue("失败摘要应包含退出码，实际=" + text, text.contains("2"));
        assertTrue("失败摘要应包含错误细节，实际=" + text, text.contains("line1"));
        assertFalse("错误细节里的连续空白应被压缩（正则 bug 回归）", text.contains("\n\n"));
        assertEquals("失败状态应为 error 语义色",
                ComponentColors.error(ctx), status.getCurrentTextColor());
    }

    @Test
    public void runningUsesAccentColorAndHint() {
        TextView status = statusOf(render("search", "running", "{}", "正在联网搜索"));
        String text = status.getText().toString();
        assertFalse("运行中文本不应出现 emoji", containsEmoji(text));
        assertTrue("运行中应显示引擎给的 hint，实际=" + text, text.contains("正在联网搜索"));
        assertEquals("运行中状态应为 accent 语义色",
                ComponentColors.accent(ctx), status.getCurrentTextColor());
    }

    // ---------------- 工具 ----------------

    private View render(String toolName, String status, String result, String statusText) {
        JSONObject props = new JSONObject();
        try {
            props.put("toolName", toolName);
            props.put("status", status);
            props.put("result", result);
            if (!statusText.isEmpty()) {
                props.put("statusText", statusText);
            }
        } catch (Exception ignored) {
        }
        ComponentData data = ComponentData.of("tool_call", props);
        return view.createView(ctx, data);
    }

    /** 结构：横向行，最后一个带权重的 TextView 是状态文本。 */
    private TextView statusOf(View root) {
        assertTrue("工具卡根应是 ViewGroup", root instanceof ViewGroup);
        ViewGroup row = (ViewGroup) root;
        TextView last = null;
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child instanceof TextView && child.getId() == View.NO_ID) {
                last = (TextView) child;
            }
        }
        assertTrue("应存在状态文本 TextView", last != null);
        return last;
    }

    private static boolean containsEmoji(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x2600 && c <= 0x27BF) {
                return true;
            }
        }
        return text.contains("🔧") || text.contains("✅") || text.contains("❌") || text.contains("⏳");
    }
}
