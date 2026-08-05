package com.oilquiz.app.ai.chat.render;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

/**
 * Mermaid 图表渲染器：将 Mermaid 图表源码渲染为带样式的代码块。
 *
 * 渲染策略：
 * - 显示 "📊 Mermaid 图表" 标签
 * - 图表源码以等宽字体 + 浅色背景显示
 * - 保留原始 Mermaid 语法，用户可复制到 mermaid.live 查看
 *
 * 注意：完整的 Mermaid 图表渲染需要 WebView + mermaid.js，
 * 当前为轻量级文本渲染。后续可通过嵌入 WebView 实现图形化渲染。
 */
public class MermaidContentRenderer implements ContentRenderer {

    @Override
    public int getPriority() {
        return 90; // 高优先级，优先匹配
    }

    @Override
    public boolean canRender(String segment) {
        return segment != null && !segment.isEmpty();
    }

    @Override
    public Spanned render(String segment, Context context) {
        SpannableStringBuilder sb = new SpannableStringBuilder();

        // 标签行
        String label = "📊 Mermaid 图表";
        int labelStart = sb.length();
        sb.append(label).append("\n");

        // 代码内容
        String code = segment.trim();
        int codeStart = sb.length();
        sb.append(code);
        int codeEnd = sb.length();

        // 应用样式
        // 标签：粗体 + 紫色
        sb.setSpan(new StyleSpan(Typeface.BOLD), labelStart, labelStart + label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(0xFF7C3AED), labelStart, labelStart + label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // 代码：等宽字体 + 浅灰背景 + 缩小
        sb.setSpan(new TypefaceSpan("monospace"), codeStart, codeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new BackgroundColorSpan(0x0F000000), codeStart, codeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new RelativeSizeSpan(0.85f), codeStart, codeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(0xFF374151), codeStart, codeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        return sb;
    }

    @Override
    public String getName() {
        return "Mermaid";
    }
}
