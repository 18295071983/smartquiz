package com.oilquiz.app.ai.chat.parts;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.oilquiz.app.ai.chat.turn.AssistantTurn;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 工具详情专用卡（2026-11-18）。
 *
 * <p>钉三件事：读文件类参数渲染成"标签 + 值"而不是整段 JSON；editing 类把
 * old_string/new_string 渲染成 +/- diff；运行中不铺结果区。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class ToolDetailCardViewTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = com.oilquiz.app.testsupport.ThemedAppContext.get();
    }

    @Test
    public void readFileCardShowsMetaAndResult() {
        AssistantTurn.Part p = AssistantTurn.Part.toolCall("t1", "c1", "read_file",
                "{\"file_path\":\"src/main/App.java\"}", "success", 1L);
        p.result = "line one\nline two";
        p.durationMs = 1_200L;

        String text = allText(ToolDetailCardView.build(ctx, p));
        assertTrue("工具展示名：" + text, text.contains("读取文件"));
        assertTrue("路径要以标签+值出现：" + text, text.contains("src/main/App.java"));
        assertTrue("状态：" + text, text.contains("已完成"));
        assertTrue("结果正文：" + text, text.contains("line one"));
        assertTrue("耗时要显示：" + text, text.contains("1.2s"));
    }

    @Test
    public void editCardRendersDiff() {
        AssistantTurn.Part p = AssistantTurn.Part.toolCall("t2", "c2", "edit",
                "{\"file_path\":\"a.java\",\"old_string\":\"alpha\\nbeta\","
                        + "\"new_string\":\"alpha\\ngamma\"}", "success", 1L);
        p.result = "ok";

        String text = allText(ToolDetailCardView.build(ctx, p));
        assertTrue("改动段：" + text, text.contains("改动"));
        assertTrue("删除行带 - 前缀：" + text, text.contains("- beta"));
        assertTrue("新增行带 + 前缀：" + text, text.contains("+ gamma"));
        assertFalse("diff 里不该再重复 old_string 原文", text.contains("old_string"));
    }

    @Test
    public void runningCardOmitsResultSection() {
        AssistantTurn.Part p = AssistantTurn.Part.toolCall("t3", "c3", "pwsh",
                "{\"command\":\"git status\"}", "running", 1L);

        String text = allText(ToolDetailCardView.build(ctx, p));
        assertTrue("运行中状态：" + text, text.contains("运行中"));
        assertTrue("命令要显示：" + text, text.contains("git status"));
        assertFalse("还没有结果，不铺结果区标题：" + text, text.contains("结果"));
    }

    @Test
    public void imageResultTagsAreUnwrapped() {
        AssistantTurn.Part p = AssistantTurn.Part.toolCall("t4", "c4", "read_image",
                "{}", "success", 1L);
        p.result = "<path>build/card0.png</path><type>image</type>"
                + "<content>image/webp image, 1220x2656 px, 135132 bytes</content>";

        String text = allText(ToolDetailCardView.build(ctx, p));
        assertTrue("图片走文件卡：" + text, text.contains("图片"));
        assertTrue("加载中占位：" + text, text.contains("加载中"));
        assertFalse("不能把 XML 标签平铺出来：" + text, text.contains("<path>"));
        assertFalse(text.contains("</content>"));
    }

    @Test
    public void pythonToolGetsTerminalSkin() {
        AssistantTurn.Part p = AssistantTurn.Part.toolCall("t5", "c5", "python_execute",
                "{\"command\":\"print(1)\"}", "success", 1L);
        p.result = "1";

        String text = allText(ToolDetailCardView.build(ctx, p));
        assertTrue("类型徽标：" + text, text.contains("终端"));
        assertTrue("终端提示行：" + text, text.contains("$ print(1)"));
        assertTrue(text.contains("1"));
    }

    @Test
    public void readFileGetsNumberedContent() {
        AssistantTurn.Part p = AssistantTurn.Part.toolCall("t6", "c6", "read_file",
                "{\"file_path\":\"a.txt\"}", "success", 1L);
        p.result = "alpha\nbeta";

        String text = allText(ToolDetailCardView.build(ctx, p));
        assertTrue("类型徽标：" + text, text.contains("文件"));
        assertTrue("行号：" + text, text.contains("1  alpha"));
        assertTrue(text.contains("2  beta"));
    }

    @Test
    public void grepResultsBecomeMatchRows() {
        AssistantTurn.Part p = AssistantTurn.Part.toolCall("t7", "c7", "grep",
                "{\"pattern\":\"TODO\"}", "success", 1L);
        p.result = "src/A.java:12: // TODO fix\nsrc/B.kt:7: TODO later";

        String text = allText(ToolDetailCardView.build(ctx, p));
        assertTrue("路径:行号：" + text, text.contains("src/A.java:12"));
        assertTrue("匹配片段：" + text, text.contains("// TODO fix"));
        assertTrue(text.contains("src/B.kt:7"));
    }

    @Test
    public void networkImageUrlRendersImageCard() {
        AssistantTurn.Part p = AssistantTurn.Part.toolCall("t8", "c8", "read_image",
                "{}", "success", 1L);
        p.result = "https://example.com/a.png";
        String text = allText(ToolDetailCardView.build(ctx, p));
        assertTrue("有网络图片段：" + text, text.contains("图片"));
    }

    private static String allText(View view) {
        StringBuilder sb = new StringBuilder();
        collect(view, sb);
        return sb.toString();
    }

    private static void collect(View view, StringBuilder sb) {
        if (view instanceof TextView) {
            CharSequence t = ((TextView) view).getText();
            if (t != null) {
                sb.append(t).append('\n');
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                collect(g.getChildAt(i), sb);
            }
        }
    }
}
