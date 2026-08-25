package com.oilquiz.app.ai.chat.component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mermaid/数学公式拆分器：检测聊天内容中的 ```mermaid 代码块 与 $$ 块级数学公式，
 * 生成 HTML 交给 html 组件（WebView）渲染——
 * - mermaid 用本地 mermaid.js 渲染 SVG 图表（流程图/时序图/思维导图等）
 * - 数学用本地 katex 渲染真实公式（完整 LaTeX 支持）
 * 其余文本段保持 Markdown 渲染。仅提取完整结构（闭合的 mermaid 块/配对的 $$）。
 */
public class MermaidMathSplitter {

    /** ```mermaid 块开始（行首，可带其它） */
    private static final Pattern MERMAID_OPEN = Pattern.compile("(?m)^\\s*```mermaid\\s*$", Pattern.CASE_INSENSITIVE);

    /** 块级数学公式 $$ ... $$（跨行） */
    private static final Pattern MATH_BLOCK = Pattern.compile("\\$\\$(.+?)\\$\\$", Pattern.DOTALL);

    /** 内容是否含可图形化的片段（mermaid 块或块级数学） */
    public static boolean containsStructure(String content) {
        if (content == null || content.isEmpty()) return false;
        return MERMAID_OPEN.matcher(content).find() || MATH_BLOCK.matcher(content).find();
    }

    /** 拆分内容为段：文本段 / html 组件段（mermaid 或 katex） */
    public static List<Segment> split(String content, boolean isDark) {
        List<Segment> segs = new ArrayList<>();
        if (content == null || content.isEmpty()) return segs;

        StringBuilder textBuf = new StringBuilder();
        String[] lines = content.split("\n", -1);

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
                        segs.add(Segment.component(new ComponentData("html",
                                htmlProps(mermaidHtml(mermaidCode, isDark)))));
                    }
                    i = j;
                    continue;
                }
                // 未闭合：按文本处理（fall through）
            }

            // 2) 块级数学 $$...$$（跨行累积）
            if (line.trim().startsWith("$$")) {
                StringBuilder math = new StringBuilder();
                math.append(line.replace("$$", "")).append('\n');
                int j = i + 1;
                boolean closed = line.trim().endsWith("$$") && line.trim().length() > 2;
                while (!closed && j < lines.length) {
                    if (lines[j].trim().endsWith("$$")) {
                        math.append(lines[j].replace("$$", ""));
                        closed = true;
                        break;
                    }
                    math.append(lines[j]).append('\n');
                    j++;
                }
                if (closed) {
                    flushText(textBuf, segs);
                    String formula = math.toString().trim();
                    if (!formula.isEmpty()) {
                        segs.add(Segment.component(new ComponentData("html",
                                htmlProps(mathHtml(formula, true, isDark)))));
                    }
                    i = j;
                    continue;
                }
                // 未闭合：按文本处理（fall through）
            }

            textBuf.append(line);
            if (i < lines.length - 1) textBuf.append('\n');
        }

        flushText(textBuf, segs);
        if (segs.isEmpty()) {
            segs.add(Segment.text(content));
        }
        return segs;
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
                + "',securityLevel:'loose'});</script>"
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

    /** 构造 html 组件 props（html 内容 + 高度上限） */
    private static org.json.JSONObject htmlProps(String html) {
        org.json.JSONObject props = new org.json.JSONObject();
        try {
            props.put("html", html);
            props.put("maxHeight", 400);
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
