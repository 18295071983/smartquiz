package com.oilquiz.app.ai.chat.render;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Markdown → HTML 转换器（基于 classpath 已有的 commonmark 0.13.0，零新增依赖）。
 *
 * 为什么走 commonmark 而非复用 Markwon：Markwon 把 markdown 渲染成 Android Span，
 * 对"正文里混排裸 HTML/表格、非标准 markdown"这类脏输出容易错位、逐 token 重排闪烁；
 * commonmark 的 {@link HtmlRenderer} 产出标准 HTML，交 WebView（浏览器引擎）渲染，
 * 对混排与容错都更稳。
 *
 * 能力：
 * - GFM 表格、删除线；内联/块级 HTML 原样透传；
 * - 整篇 HTML 文档判定直载；流式未闭合 ``` 围栏兜底；
 * - 输出净化（剥 {@code <script>}/事件属性/{@code javascript:}，防模型吐出的 HTML 执行脚本）；
 * - 任务列表 {@code - [ ]}/{@code - [x]} 转复选框（commonmark 无该扩展，后处理补）；
 * - {@link #wrapDocument} 注入主题色 CSS 变量 + JS 引导（自动链接、代码块复制、图片点击预览）。
 *
 * Parser/HtmlRenderer 均线程安全，可静态复用。
 */
public final class MarkdownHtmlConverter {

    private MarkdownHtmlConverter() {}

    private static final List<Extension> EXTENSIONS = Arrays.asList(
            TablesExtension.create(),
            StrikethroughExtension.create());

    private static final Parser PARSER = Parser.builder()
            .extensions(EXTENSIONS)
            .build();

    private static final HtmlRenderer RENDERER = HtmlRenderer.builder()
            .extensions(EXTENSIONS)
            .build();

    /** 渲染 markdown 为 body 片段 HTML（不含 <html>/<head>）。null/空返回空串。 */
    public static String markdownToBodyHtml(String markdown) {
        if (markdown == null || markdown.isEmpty()) return "";
        String safe = closeUnclosedCodeFence(markdown);
        String html = RENDERER.render(PARSER.parse(safe));
        return renderTaskLists(sanitize(html));
    }

    /**
     * 统一入口：整篇像 HTML 文档则净化后原样返回（交 WebView 直载），否则按 markdown 转 HTML。
     */
    public static String toBodyHtml(String content) {
        if (content == null || content.isEmpty()) return "";
        if (isFullHtmlDocument(content)) return renderTaskLists(sanitize(content));
        return markdownToBodyHtml(content);
    }

    /** 判定整篇是否为完整 HTML 文档（看前若干字符，避免正文偶现 &lt;html 误判）。 */
    public static boolean isFullHtmlDocument(String content) {
        if (content == null) return false;
        String head = content.length() > 256 ? content.substring(0, 256) : content;
        String lower = head.toLowerCase(Locale.ROOT);
        return lower.contains("<!doctype html") || lower.contains("<html");
    }

    /** 流式兜底：行首 ``` 奇数个时末尾补闭合，防半截代码围栏吞掉后续正文。 */
    private static String closeUnclosedCodeFence(String markdown) {
        int fenceCount = 0;
        for (String line : markdown.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("```")) fenceCount++;
        }
        return (fenceCount & 1) == 1 ? markdown + "\n```" : markdown;
    }

    /**
     * 基础 HTML 净化（best-effort，非完备 XSS 防护）：
     * 去 {@code <script>} 块、内联事件属性（on*=）、{@code javascript:} 协议链接。
     */
    static String sanitize(String html) {
        if (html == null || html.isEmpty()) return html;
        String out = html;
        out = out.replaceAll("(?is)<script[^>]*>[\\s\\S]*?</script>", "");
        out = out.replaceAll("(?is)<script[^>]*/?>", "");
        out = out.replaceAll("(?is)<iframe[\\s\\S]*?</iframe>", "");
        out = out.replaceAll("(?is)<object[\\s\\S]*?</object>", "");
        // 内联事件属性 on*=（双引号/单引号/裸值）
        out = out.replaceAll("(?is)\\son[a-z]+\\s*=\\s*\"[^\"]*\"", "");
        out = out.replaceAll("(?is)\\son[a-z]+\\s*=\\s*'[^']*'", "");
        out = out.replaceAll("(?is)\\son[a-z]+\\s*=\\s*[^\\s>]+", "");
        // javascript: 协议
        out = out.replaceAll("(?is)(href|src)\\s*=\\s*\"\\s*javascript:[^\"]*\"", "$1=\"#\"");
        out = out.replaceAll("(?is)(href|src)\\s*=\\s*'\\s*javascript:[^']*'", "$1='#'");
        return out;
    }

    /** 任务列表：commonmark 把 {@code - [ ]} 渲成 {@code <li>[ ] ...}，后处理转复选框。 */
    private static String renderTaskLists(String html) {
        if (html == null || html.isEmpty()) return html;
        String out = html;
        out = out.replaceAll("(?i)<li>\\s*\\[\\s*\\]\\s*",
                "<li class=\"qz-task\"><input type=\"checkbox\" disabled> ");
        out = out.replaceAll("(?i)<li>\\s*\\[[x]\\]\\s*",
                "<li class=\"qz-task\"><input type=\"checkbox\" checked disabled> ");
        return out;
    }

    /**
     * 把 body HTML 包成完整可加载文档：viewport + 主题色 CSS 变量 + 排版 CSS + JS 引导。
     * baseURL 由调用方 WebView 指向 file:///android_asset/。
     *
     * @param night     是否深色模式（决定基础配色）
     * @param themeVars 额外 CSS 变量覆盖（如 "--fg:#..;--link:#.."，来自应用主题 token），可为 null
     */
    public static String wrapDocument(String bodyHtml, boolean night, String themeVars) {
        String theme = night ? "dark" : "light";
        String vars = (themeVars == null || themeVars.trim().isEmpty()) ? "" : themeVars.trim();
        String hljsCss = night ? "css/hljs-github-dark.min.css" : "css/hljs-github.min.css";
        return "<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"UTF-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=1\">"
                + "<link rel=\"stylesheet\" href=\"" + hljsCss + "\">"
                + "<link rel=\"stylesheet\" href=\"css/katex.min.css\">"
                + "<style>" + css(night) + "</style>"
                + "<script src=\"js/highlight.min.js\"></script>"
                + "<script src=\"js/katex.min.js\"></script>"
                + "<script src=\"js/katex-auto-render.min.js\"></script>"
                + "</head>"
                + "<body class=\"" + theme + "\" style=\"" + vars + "\">"
                + (bodyHtml == null ? "" : bodyHtml)
                + "</body><script>" + bootstrapJs() + "</script></html>";
    }

    /** 便捷重载：无自定义主题变量。 */
    public static String wrapDocument(String bodyHtml, boolean night) {
        return wrapDocument(bodyHtml, night, null);
    }

    /**
     * JS 引导：定义 window.__qzBoot（自动链接裸 URL、给 <pre> 加复制按钮、图片点击预览），
     * 首次 DOMContentLoaded 执行；流式换 body 后由原生再调 __qzBoot() 重挂。
     * 通过 QZ.copy / QZ.previewImage 回调 Android。
     */
    private static String bootstrapJs() {
        return ""
                // KaTeX 数学：内联 $…$ / \(…\) 与块 $$…$$ /\[…\]（renderMathInElement 默认跳过 code/pre/script）
                + "window.__qzBoot=function(){"
                + "function math(){if(!window.renderMathInElement)return;try{renderMathInElement(document.body,{"
                + "delimiters:[{left:'$$',right:'$$',display:true},{left:'\\\\[',right:'\\\\]',display:true},"
                + "{left:'$',right:'$',display:false},{left:'\\\\(',right:'\\\\)',display:false}],throwOnError:false});}catch(e){}}"
                // 自动链接：遍历文本节点，把裸 http(s) URL 包成 <a>
                + "function linkify(){if(!document.createTreeWalker)return;"
                + "var re=/(https?:\\/\\/[^\\s<>\"]+)/g;var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null,false);var nodes=[];var n;"
                + "while(n=w.nextNode()){if(n.parentNode&&/^(A|PRE|CODE|SCRIPT|STYLE)$/.test(n.parentNode.tagName))continue;if(re.test(n.nodeValue)){nodes.push(n);}}re.lastIndex=0;"
                + "nodes.forEach(function(node){var frag=document.createDocumentFragment();var txt=node.nodeValue;var last=0,m;"
                + "while((m=re.exec(txt))){frag.appendChild(document.createTextNode(txt.slice(last,m.index)));var a=document.createElement('a');a.href=m[1];a.textContent=m[1];frag.appendChild(a);last=m.index+m[1].length;}"
                + "frag.appendChild(document.createTextNode(txt.slice(last)));node.parentNode.replaceChild(frag,node);});}"
                // 代码块语法高亮（highlight.js）：先上色，再加复制按钮
                + "function hl(){if(!window.hljs)return;var els=document.querySelectorAll('pre code');for(var i=0;i<els.length;i++){try{hljs.highlightElement(els[i]);}catch(e){}}}"
                // 代码块复制按钮
                + "function codeCopy(){var pres=document.querySelectorAll('pre');for(var i=0;i<pres.length;i++){var p=pres[i];if(p.getAttribute('data-qz')==='1')continue;p.setAttribute('data-qz','1');p.style.position='relative';"
                + "var b=document.createElement('button');b.className='qz-copy';b.textContent='复制';b.onclick=(function(pp){return function(){try{QZ.copy(pp.innerText);}catch(e){}};})(p);"
                + "p.appendChild(b);}}"
                // 图片点击预览（事件委托）
                + "function imgPrev(){document.addEventListener('click',function(e){var el=e.target;while(el&&el.tagName!=='IMG'&&el!==document){el=el.parentElement;}if(el&&el.tagName==='IMG'){var s=el.getAttribute('src');if(s&&s.indexOf('data:')!==0){e.preventDefault();try{QZ.previewImage(s);}catch(x){}}}},true);var imgs=document.getElementsByTagName('img');for(var i=0;i<imgs.length;i++){imgs[i].style.cursor='zoom-in';}}"
                + "try{math();}catch(e){}try{linkify();}catch(e){}try{hl();}catch(e){}try{codeCopy();}catch(e){}try{imgPrev();}catch(e){}"
                + "};"
                + "if(document.readyState==='complete'||document.readyState==='interactive'){window.__qzBoot();}"
                + "else{document.addEventListener('DOMContentLoaded',function(){window.__qzBoot();});}";
    }

    /** 排版 CSS：标题/段落/列表/表格/代码/引用/链接/图片/任务列表/复制按钮，深浅两套。 */
    private static String css(boolean night) {
        String fg = night ? "#e5e7eb" : "#1f2937";
        String muted = night ? "#9ca3af" : "#6b7280";
        String border = night ? "#374151" : "#d1d5db";
        String codeBg = night ? "#1f2937" : "#f3f4f6";
        String preBg = night ? "#111827" : "#f6f8fa";
        String link = night ? "#60a5fa" : "#2563eb";
        String quoteBar = night ? "#4b5563" : "#cbd5e1";
        String thBg = night ? "#1f2937" : "#eef2f7";
        String rowAlt = night ? "#0d1420" : "#fafbfc";
        String btnBg = night ? "#374151" : "#e5e7eb";
        return ""
                + "*{box-sizing:border-box}"
                + "html,body{margin:0;padding:0;color:var(--fg," + fg + ");font-size:15px;line-height:1.7;"
                + "font-family:-apple-system,'PingFang SC','Roboto','Noto Sans SC',sans-serif;word-wrap:break-word;-webkit-text-size-adjust:100%}"
                + "body{padding:2px 2px;background:transparent}"
                + "p{margin:0.5em 0}"
                + "h1,h2,h3,h4,h5,h6{margin:0.7em 0 0.4em;line-height:1.35;font-weight:700;color:var(--fg," + fg + ")}"
                + "h1{font-size:1.5em}h2{font-size:1.3em}h3{font-size:1.15em}h4{font-size:1.05em}"
                + "a{color:var(--link," + link + ");text-decoration:none}"
                + "ul,ol{margin:0.4em 0;padding-left:1.6em}li{margin:0.2em 0}"
                + "li.qz-task{list-style:none;margin-left:-1.1em}li.qz-task input{margin-right:0.4em}"
                + "img{max-width:100%;height:auto;border-radius:6px;display:block;margin:0.4em 0}"
                + "code{background:" + codeBg + ";padding:0.12em 0.4em;border-radius:4px;"
                + "font-family:'Roboto Mono','Menlo',monospace;font-size:0.88em}"
                + "pre{position:relative;background:" + preBg + ";padding:12px;padding-top:30px;border-radius:8px;overflow-x:auto;margin:0.6em 0}"
                + "pre code{background:transparent;padding:0;font-size:0.85em;line-height:1.5}"
                + "button.qz-copy{position:absolute;top:5px;right:6px;font-size:11px;color:" + muted
                + ";background:" + btnBg + ";border:none;border-radius:4px;padding:2px 8px;cursor:pointer}"
                + "blockquote{margin:0.6em 0;padding:0.2em 0.9em;border-left:4px solid " + quoteBar
                + ";color:" + muted + ";background:" + (night ? "#111827" : "#f8fafc") + ";border-radius:0 6px 6px 0}"
                + "table{border-collapse:collapse;width:100%;margin:0.6em 0;font-size:0.92em;display:block;overflow-x:auto}"
                + "th,td{border:1px solid " + border + ";padding:7px 10px;text-align:left;vertical-align:top}"
                + "th{background:" + thBg + ";font-weight:700}"
                + "tr:nth-child(even) td{background:" + rowAlt + "}"
                + "hr{border:none;border-top:1px solid " + border + ";margin:1em 0}"
                + "del{color:" + muted + "}";
    }
}
