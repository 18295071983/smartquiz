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
import android.text.style.SubscriptSpan;
import android.text.style.SuperscriptSpan;
import android.text.style.TypefaceSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数学公式渲染器：将 LaTeX 公式渲染为带样式的 Spanned 文本。
 *
 * 支持的 LaTeX 子集：
 * - 上下标：x^{2}, x_{n}, x^2, x_n
 * - 分数：\frac{a}{b} → a/b
 * - 希腊字母：\alpha, \beta, \gamma, ... → α, β, γ, ...
 * - 常见符号：\sum, \int, \infty, \pm, \times, \div, ...
 * - 平方根：\sqrt{x} → √x
 * - 矩阵：简化为行内展示
 *
 * 渲染策略：
 * - 行内公式（$...$）：等宽字体 + 深色前景，不换行
 * - 块级公式（$$...$$）：居中 + 浅色背景 + 等宽字体 + 放大
 *
 * 注意：这是轻量级渲染，不依赖 JLatexMath 等重量级库。
 * 对于复杂公式（矩阵、多行对齐），会以可读的纯文本形式展示。
 */
public class MathContentRenderer implements ContentRenderer {

    /** 希腊字母和常见符号映射 */
    private static final String[][] SYMBOL_MAP = {
            // 希腊字母
            {"\\alpha", "α"}, {"\\beta", "β"}, {"\\gamma", "γ"}, {"\\delta", "δ"},
            {"\\epsilon", "ε"}, {"\\varepsilon", "ε"}, {"\\zeta", "ζ"}, {"\\eta", "η"},
            {"\\theta", "θ"}, {"\\vartheta", "ϑ"}, {"\\iota", "ι"}, {"\\kappa", "κ"},
            {"\\lambda", "λ"}, {"\\mu", "μ"}, {"\\nu", "ν"}, {"\\xi", "ξ"},
            {"\\pi", "π"}, {"\\varpi", "ϖ"}, {"\\rho", "ρ"}, {"\\varrho", "ϱ"},
            {"\\sigma", "σ"}, {"\\varsigma", "ς"}, {"\\tau", "τ"}, {"\\upsilon", "υ"},
            {"\\phi", "φ"}, {"\\varphi", "φ"}, {"\\chi", "χ"}, {"\\psi", "ψ"},
            {"\\omega", "ω"}, {"\\Gamma", "Γ"}, {"\\Delta", "Δ"}, {"\\Theta", "Θ"},
            {"\\Lambda", "Λ"}, {"\\Xi", "Ξ"}, {"\\Pi", "Π"}, {"\\Sigma", "Σ"},
            {"\\Phi", "Φ"}, {"\\Psi", "Ψ"}, {"\\Omega", "Ω"},
            // 运算符号
            {"\\sum", "∑"}, {"\\prod", "∏"}, {"\\int", "∫"}, {"\\oint", "∮"},
            {"\\infty", "∞"}, {"\\partial", "∂"}, {"\\nabla", "∇"},
            {"\\pm", "±"}, {"\\mp", "∓"}, {"\\times", "×"}, {"\\div", "÷"},
            {"\\cdot", "·"}, {"\\cdots", "⋯"}, {"\\ldots", "…"}, {"\\vdots", "⋮"},
            {"\\ddots", "⋱"},
            // 关系符号
            {"\\leq", "≤"}, {"\\geq", "≥"}, {"\\neq", "≠"}, {"\\approx", "≈"},
            {"\\equiv", "≡"}, {"\\sim", "∼"}, {"\\propto", "∝"},
            {"\\subset", "⊂"}, {"\\supset", "⊃"}, {"\\subseteq", "⊆"}, {"\\supseteq", "⊇"},
            {"\\in", "∈"}, {"\\notin", "∉"}, {"\\ni", "∋"},
            {"\\cup", "∪"}, {"\\cap", "∩"}, {"\\emptyset", "∅"}, {"\\varnothing", "∅"},
            {"\\forall", "∀"}, {"\\exists", "∃"}, {"\\nexists", "∄"},
            {"\\rightarrow", "→"}, {"\\leftarrow", "←"}, {"\\Rightarrow", "⇒"},
            {"\\Leftarrow", "⇐"}, {"\\leftrightarrow", "↔"}, {"\\Leftrightarrow", "⇔"},
            {"\\mapsto", "↦"}, {"\\to", "→"},
            // 其他
            {"\\sqrt", "√"}, {"\\degree", "°"}, {"\\angle", "∠"}, {"\\perp", "⊥"},
            {"\\parallel", "∥"}, {"\\triangle", "△"}, {"\\square", "□"}, {"\\circ", "○"},
            {"\\bullet", "●"}, {"\\star", "★"},
            {"\\mathbb{R}", "ℝ"}, {"\\mathbb{Z}", "ℤ"}, {"\\mathbb{N}", "ℕ"},
            {"\\mathbb{Q}", "ℚ"}, {"\\mathbb{C}", "ℂ"},
            {"\\left(", "("}, {"\\right)", ")"}, {"\\left[", "["}, {"\\right]", "]"},
            {"\\left|", "|"}, {"\\right|", "|"}, {"\\{", "{"}, {"\\}", "}"},
    };

    /** 上标模式：^{...} 或 ^x */
    private static final Pattern SUPERSCRIPT = Pattern.compile("\\^\\{([^}]*)\\}|\\^(\\w)");
    /** 下标模式：_{...} 或 _x */
    private static final Pattern SUBSCRIPT = Pattern.compile("_\\{([^}]*)\\}|_(\\w)");
    /** 分数模式：\frac{a}{b} */
    private static final Pattern FRACTION = Pattern.compile("\\\\frac\\{([^}]*)\\}\\{([^}]*)\\}");

    private final boolean isBlock;

    public MathContentRenderer(boolean isBlock) {
        this.isBlock = isBlock;
    }

    @Override
    public int getPriority() {
        return isBlock ? 80 : 75; // 块级公式优先级略高
    }

    @Override
    public boolean canRender(String segment) {
        // 由 ContentTypeDetector 保证类型正确，这里总是返回 true
        return segment != null;
    }

    @Override
    public Spanned render(String segment, Context context) {
        String formula = convertLatexToUnicode(segment.trim());
        SpannableStringBuilder sb = new SpannableStringBuilder();

        if (isBlock) {
            // 块级公式：居中 + 浅色背景 + 放大
            String display = "  " + formula + "  ";
            int start = sb.length();
            sb.append("\n").append(display).append("\n");
            int end = sb.length();
            sb.setSpan(new BackgroundColorSpan(0x0D6C56F0), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new RelativeSizeSpan(1.15f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(0xFF333333), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else {
            // 行内公式：等宽 + 深色前景
            int start = sb.length();
            sb.append(formula);
            int end = sb.length();
            sb.setSpan(new TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(0xFF6C56F0), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        return sb;
    }

    @Override
    public String getName() {
        return isBlock ? "MathBlock" : "MathInline";
    }

    /**
     * 将 LaTeX 命令转换为 Unicode 字符。
     * 处理顺序：符号替换 → 分数 → 上标 → 下标。
     */
    private String convertLatexToUnicode(String latex) {
        String result = latex;

        // 1. 替换符号
        for (String[] entry : SYMBOL_MAP) {
            result = result.replace(entry[0], entry[1]);
        }

        // 2. 处理分数 \frac{a}{b} → a/(b)
        Matcher fracMatcher = FRACTION.matcher(result);
        StringBuffer fracSb = new StringBuffer();
        while (fracMatcher.find()) {
            String num = fracMatcher.group(1);
            String den = fracMatcher.group(2);
            fracMatcher.appendReplacement(fracSb, num + "/(" + den + ")");
        }
        fracMatcher.appendTail(fracSb);
        result = fracSb.toString();

        // 3. 处理上标 ^{...} 或 ^x → 使用 Unicode 上标
        result = processSuperscript(result);

        // 4. 处理下标 _{...} 或 _x → 使用 Unicode 下标
        result = processSubscript(result);

        // 5. 清理残留的 LaTeX 命令
        result = result.replaceAll("\\\\text\\{([^}]*)\\}", "$1");
        result = result.replaceAll("\\\\mathrm\\{([^}]*)\\}", "$1");
        result = result.replaceAll("\\\\mathbf\\{([^}]*)\\}", "$1");
        result = result.replaceAll("\\\\left|\\\\right|\\\\displaystyle|\\\\limits", "");
        result = result.replaceAll("\\\\[a-zA-Z]+", ""); // 移除未识别的命令

        return result.trim();
    }

    /** 将上标转为 Unicode 上标字符 */
    private String processSuperscript(String text) {
        Matcher m = SUPERSCRIPT.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String sup = m.group(1) != null ? m.group(1) : m.group(2);
            String unicode = toSuperscript(sup);
            m.appendReplacement(sb, unicode);
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 将下标转为 Unicode 下标字符 */
    private String processSubscript(String text) {
        Matcher m = SUBSCRIPT.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String sub = m.group(1) != null ? m.group(1) : m.group(2);
            String unicode = toSubscript(sub);
            m.appendReplacement(sb, unicode);
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 数字和常见字母的 Unicode 上标 */
    private String toSuperscript(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '0': sb.append('⁰'); break;
                case '1': sb.append('¹'); break;
                case '2': sb.append('²'); break;
                case '3': sb.append('³'); break;
                case '4': sb.append('⁴'); break;
                case '5': sb.append('⁵'); break;
                case '6': sb.append('⁶'); break;
                case '7': sb.append('⁷'); break;
                case '8': sb.append('⁸'); break;
                case '9': sb.append('⁹'); break;
                case '+': sb.append('⁺'); break;
                case '-': sb.append('⁻'); break;
                case '=': sb.append('⁼'); break;
                case '(': sb.append('⁽'); break;
                case ')': sb.append('⁾'); break;
                case 'n': sb.append('ⁿ'); break;
                case 'i': sb.append('ⁱ'); break;
                default: sb.append(c); break;
            }
        }
        return sb.toString();
    }

    /** 数字和常见字母的 Unicode 下标 */
    private String toSubscript(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '0': sb.append('₀'); break;
                case '1': sb.append('₁'); break;
                case '2': sb.append('₂'); break;
                case '3': sb.append('₃'); break;
                case '4': sb.append('₄'); break;
                case '5': sb.append('₅'); break;
                case '6': sb.append('₆'); break;
                case '7': sb.append('₇'); break;
                case '8': sb.append('₈'); break;
                case '9': sb.append('₉'); break;
                case '+': sb.append('₊'); break;
                case '-': sb.append('₋'); break;
                case '=': sb.append('₌'); break;
                case '(': sb.append('₍'); break;
                case ')': sb.append('₎'); break;
                case 'a': sb.append('ₐ'); break;
                case 'e': sb.append('ₑ'); break;
                case 'o': sb.append('ₒ'); break;
                case 'x': sb.append('ₓ'); break;
                case 'i': sb.append('ᵢ'); break;
                case 'n': sb.append('ₙ'); break;
                default: sb.append(c); break;
            }
        }
        return sb.toString();
    }
}
