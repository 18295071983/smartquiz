package com.oilquiz.app.ai.chat.component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mermaid/数学公式拆分器：检测聊天内容中的 ```mermaid 代码块 与块级数学公式
 * （{@code $$...$$} 或 {@code \[...\]}），生成 HTML 交给 html 组件（WebView）渲染——
 * - mermaid 用本地 mermaid.js 渲染 SVG 图表（流程图/时序图/思维导图等）
 * - 数学用本地 katex 渲染真实公式（完整 LaTeX 支持）
 * 其余文本段保持 Markdown 渲染。仅提取完整结构（闭合的 mermaid 块/配对的 $$ 或 \[...\]）；
 * 普通代码围栏里的同样字符原样保留，不误当公式。
 */
public class MermaidMathSplitter {

    private static final String TAG = "MermaidMathSplitter";

    /** ```mermaid 块开始（行首，可带其它） */
    private static final Pattern MERMAID_OPEN = Pattern.compile("(?m)^\\s*```mermaid\\s*$", Pattern.CASE_INSENSITIVE);

    /** 块级数学公式 $$ ... $$（跨行） */
    private static final Pattern MATH_BLOCK = Pattern.compile("\\$\\$(.+?)\\$\\$", Pattern.DOTALL);

    /** 块级数学公式 \[ ... \]（跨行；KaTeX 与 __qzBoot 的 display 档同口径） */
    private static final Pattern BRACKET_MATH_BLOCK = Pattern.compile(
            Pattern.quote("\\[") + "(.+?)" + Pattern.quote("\\]"), Pattern.DOTALL);

    /** 内容是否含 mermaid 块（不含数学；2026-10-25 起块级数学不再切分） */
    public static boolean containsMermaid(String content) {
        if (content == null || content.isEmpty()) return false;
        try {
            return hasMermaidBlock(content);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "containsMermaid failed: " + t.getMessage());
            return false;
        }
    }

    /** 内容是否含可图形化的片段（mermaid 块或块级数学） */
    public static boolean containsStructure(String content) {
        if (content == null || content.isEmpty()) return false;
        try {
            // 先跳代码围栏再判数学：代码块里的 $$ / \[ 不是公式，不能把整条消息
            // 误路由到 mermaid/math 原生渲染路径。
            return hasMermaidBlock(content) || hasMathOutsideFences(content);
        } catch (Throwable t) {
            // 防御：异常输入不阻断渲染
            android.util.Log.w(TAG, "containsStructure failed: " + t.getMessage());
            return false;
        }
    }

    /** 顶层 mermaid 围栏（代码块里的 ```mermaid 不算）。 */
    private static boolean hasMermaidBlock(String content) {
        String[] lines = splitLines(content);
        Fence fence = null;
        for (String line : lines) {
            if (fence != null) {
                if (fenceClose(line, fence)) fence = null;
                continue;
            }
            if (MERMAID_OPEN.matcher(line).find()) return true;
            Fence opened = fenceOpen(line);
            if (opened != null) fence = opened;
        }
        return false;
    }

    /** 去掉普通代码围栏后再看 $$ / \[...\] 是否成对。 */
    private static boolean hasMathOutsideFences(String content) {
        String stripped = stripFencedCode(content);
        return MATH_BLOCK.matcher(stripped).find()
                || BRACKET_MATH_BLOCK.matcher(stripped).find();
    }

    private static String stripFencedCode(String content) {
        String[] lines = splitLines(content);
        StringBuilder out = new StringBuilder(content.length());
        Fence fence = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (fence != null) {
                if (fenceClose(line, fence)) fence = null;
                continue;
            }
            Fence opened = fenceOpen(line);
            if (opened != null) {
                fence = opened;
                continue;
            }
            out.append(line);
            if (i < lines.length - 1) out.append('\n');
        }
        return out.toString();
    }

    /**
     * 拆分内容为段：文本段 / html 组件段（mermaid 或 katex）。
     * 外层防御：LLM 输出不可控（任意字符/超长文本/异常格式），解析过程任何异常
     * 都降级为单个纯文本段，绝不让聊天界面渲染崩溃。
     */
    public static List<Segment> split(String content, boolean isDark) {
        return split(content, isDark, true);
    }

    /**
     * 只切 mermaid、把块级数学留在正文里（2026-10-25）。
     *
     * <p>为什么不再切数学：切出去后每条公式变成一个独立 WebView 组件，
     * 高度至少 200px（HtmlCardView 的量高下限），一个积分式就撑出一大块空白；
     * 而正文 WebView 的 KaTeX auto-render 本来就支持 {@code $$…$$}，同页渲染、
     * 按内容量高、居中，和行内公式一致。</p>
     */
    public static List<Segment> splitMermaidOnly(String content, boolean isDark) {
        return split(content, isDark, false);
    }

    private static List<Segment> split(String content, boolean isDark, boolean includeMath) {
        List<Segment> segs = new ArrayList<>();
        if (content == null || content.isEmpty()) return segs;
        try {
            return doSplit(content, isDark, includeMath);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "split failed, fallback to plain text: " + t.getMessage());
            segs.clear();
            segs.add(Segment.text(content));
            return segs;
        }
    }

    /** 实际拆分逻辑（被 split 防御包裹） */
    private static List<Segment> doSplit(String content, boolean isDark, boolean includeMath) {
        List<Segment> segs = new ArrayList<>();

        StringBuilder textBuf = new StringBuilder();
        String[] lines = splitLines(content);

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            // 1) mermaid 块：```mermaid 开 → 找闭合
            if (MERMAID_OPEN.matcher(line).find()) {
                StringBuilder code = new StringBuilder();
                int j = i + 1;
                boolean closed = false;
                while (j < lines.length) {
                    if (lines[j].trim().startsWith("```")) {
                        closed = true;
                        break;
                    }
                    code.append(lines[j]).append('\n');
                    j++;
                }
                if (closed) {
                    flushText(textBuf, segs);
                    String mermaidCode = code.toString().trim();
                    if (!mermaidCode.isEmpty()) {
                        // 可信模板：图渲染需要 mermaid.js 执行，标记为内置模板，
                        // HtmlSandbox 才会在"无桥 + 无文件访问"的前提下放开脚本
                        segs.add(Segment.component(ComponentData.ofTrustedTemplate("html",
                                htmlProps(mermaidHtml(mermaidCode, isDark)))));
                    }
                    i = j;
                    continue;
                }
                // 未闭合：按文本处理（fall through）
            }

            // 2) 普通代码围栏：整块按文本保留，别把里面的 $$ / \[ 当公式
            Fence fence = fenceOpen(line);
            if (fence != null) {
                appendLine(textBuf, line, i, lines.length);
                int j = i + 1;
                boolean closed = false;
                while (j < lines.length) {
                    appendLine(textBuf, lines[j], j, lines.length);
                    if (fenceClose(lines[j], fence)) {
                        closed = true;
                        break;
                    }
                    j++;
                }
                i = closed ? j : lines.length - 1;
                continue;
            }

            // 3) 块级数学：$$...$$ 或 \[...\]（跨行；单行闭合也支持）
            String trimmed = line.trim();
            boolean dollarOpen = trimmed.startsWith("$$");
            boolean bracketOpen = trimmed.startsWith("\\[");
            if (includeMath && (dollarOpen || bracketOpen)) {
                String open = dollarOpen ? "$$" : "\\[";
                String close = dollarOpen ? "$$" : "\\]";
                StringBuilder math = new StringBuilder();
                int j = i;
                boolean closed = trimmed.length() > open.length() && trimmed.endsWith(close);
                if (closed) {
                    math.append(line.replace(open, "").replace(close, ""));
                } else {
                    math.append(line.replace(open, "")).append('\n');
                    while (j + 1 < lines.length) {
                        j++;
                        String next = lines[j];
                        if (next.trim().endsWith(close)) {
                            math.append(next.replace(close, ""));
                            closed = true;
                            break;
                        }
                        math.append(next).append('\n');
                    }
                }
                if (closed) {
                    flushText(textBuf, segs);
                    String formula = math.toString().trim();
                    if (!formula.isEmpty()) {
                        segs.add(Segment.component(ComponentData.ofTrustedTemplate("html",
                                htmlProps(mathHtml(formula, true, isDark)))));
                    }
                    // j 恒为最后消费的行号：单行公式 j==i，多行公式 j==闭合行。
                    i = j;
                    continue;
                }
                // 未闭合：按文本处理（fall through）
            }

            appendLine(textBuf, line, i, lines.length);
        }

        flushText(textBuf, segs);
        if (segs.isEmpty()) {
            segs.add(Segment.text(content));
        }
        return segs;
    }

    /**
     * 手工按 '\n' 切分（不走 String.split 正则：彻底规避正则解析异常/非法参数/超长回溯问题，
     * 行为与 split("\n", -1) 一致，保留行尾空行）。
     */
    private static String[] splitLines(String content) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        int start = 0;
        int len = content.length();
        for (int i = 0; i < len; i++) {
            if (content.charAt(i) == '\n') {
                out.add(content.substring(start, i));
                start = i + 1;
            }
        }
        out.add(content.substring(start));
        return out.toArray(new String[0]);
    }

    /** 普通围栏（``` / ~~~）：只用于把代码块整块当文本、防止里面的 $$ / \[ 被抽成公式。 */
    private static final class Fence {
        final char ch;
        final int len;

        Fence(char ch, int len) {
            this.ch = ch;
            this.len = len;
        }
    }

    private static Fence fenceOpen(String line) {
        String t = line.trim();
        if (t.length() < 3) return null;
        char c = t.charAt(0);
        if (c != '`' && c != '~') return null;
        int n = 0;
        while (n < t.length() && t.charAt(n) == c) n++;
        if (n < 3) return null;
        return new Fence(c, n);
    }

    private static boolean fenceClose(String line, Fence fence) {
        String t = line.trim();
        int n = 0;
        while (n < t.length() && t.charAt(n) == fence.ch) n++;
        if (n < fence.len) return false;
        for (int i = n; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c != ' ' && c != '\t') return false;
        }
        return true;
    }

    private static void appendLine(StringBuilder buf, String line, int index, int total) {
        buf.append(line);
        if (index < total - 1) buf.append('\n');
    }

    /** mermaid HTML：引用本地 mermaid.js，渲染 SVG 图表 */
    private static String mermaidHtml(String code, boolean isDark) {
        String theme = isDark ? "dark" : "default";
        String bodyColor = isDark ? "#E5E7EB" : "#333333";
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"/>"
                + "<script src=\"js/mermaid.min.js\"></script></head>"
                + "<body style=\"margin:0;padding:4px;background:transparent;color:" + bodyColor + "\">"
                + "<div class=\"mermaid\" style=\"text-align:center;\">"
                + escapeHtml(code)
                + "</div>"
                + "<script>mermaid.initialize({startOnLoad:true,theme:'" + theme
                + "',securityLevel:'strict'});</script>"
                + "</body></html>";
    }

    /** katex HTML：引用本地 katex，渲染 LaTeX 公式 */
    private static String mathHtml(String formula, boolean displayMode, boolean isDark) {
        String bodyColor = isDark ? "#E5E7EB" : "#333333";
        String enc = encodeURIComponent(formula);
        String align = displayMode ? "text-align:center;font-size:1.15em;" : "";
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"/>"
                + "<link rel=\"stylesheet\" href=\"css/katex.min.css\"/>"
                + "<script src=\"js/katex.min.js\"></script></head>"
                + "<body style=\"margin:0;padding:4px;background:transparent;color:" + bodyColor + "\">"
                + "<div id=\"math\" style=\"" + align + "\"></div>"
                + "<script>var f=decodeURIComponent(\"" + enc + "\");"
                + "katex.render(f,document.getElementById('math'),"
                + "{displayMode:" + displayMode + ",throwOnError:false});</script>"
                + "</body></html>";
    }

    /**
     * 构造 html 组件 props（html 内容 + 高度上限 + 显示档）。
     *
     * <p>{@code display=inline} 是必须的（2026-10-24）：html 卡已从
     * {@code ComponentDisplay.ALWAYS_INLINE_TYPES} 里摘出去跟随用户默认档，而流程图/公式
     * 是**正文本身**——被收成一行就等于图没了。正文型生产者自己声明档位，
     * 产物型（工作区 .html、工具结果里的 html 字段）不声明、交给默认档。</p>
     */
    private static org.json.JSONObject htmlProps(String html) {
        org.json.JSONObject props = new org.json.JSONObject();
        try {
            props.put("html", html);
            props.put("maxHeight", 400);
            props.put("display", "inline");
        } catch (Exception ignored) {
        }
        return props;
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String encodeURIComponent(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    private static void flushText(StringBuilder buf, List<Segment> segs) {
        if (buf.length() == 0) return;
        String t = buf.toString();
        buf.setLength(0);
        if (!t.trim().isEmpty()) {
            segs.add(Segment.text(t));
        }
    }

    /** 段：文本段或组件段 */
    public static class Segment {
        public final boolean isComponent;
        public final String text;
        public final ComponentData component;

        private Segment(boolean isComponent, String text, ComponentData component) {
            this.isComponent = isComponent;
            this.text = text;
            this.component = component;
        }

        public static Segment text(String text) {
            return new Segment(false, text, null);
        }

        public static Segment component(ComponentData component) {
            return new Segment(true, null, component);
        }
    }
}
