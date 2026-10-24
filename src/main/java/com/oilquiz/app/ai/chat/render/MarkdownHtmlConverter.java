package com.oilquiz.app.ai.chat.render;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        String withFootnotes = preprocessFootnotes(markdown);
        String safe = closeUnclosedCodeFence(withFootnotes);
        String html = RENDERER.render(PARSER.parse(safe));
        return renderTaskLists(sanitize(html));
    }

    private static final Pattern FOOTNOTE_DEF =
            Pattern.compile("^\\[\\^([^\\]]+)\\]:[ \\t]*(.*)$");

    /**
     * 主气泡的 commonmark 没有脚注扩展，这里做最小可用的脚注预处理：
     * 抽出 {@code [^id]: 文本}（含四空格续行），把正文引用换成 sup 链接，
     * 末尾追加脚注区。代码围栏内原样保留；脚注文本交后续 HTML 净化。
     */
    static String preprocessFootnotes(String markdown) {
        if (markdown == null || markdown.indexOf("[^") < 0) return markdown;
        Map<String, String> defs = new LinkedHashMap<>();
        StringBuilder body = new StringBuilder(markdown.length());
        String[] lines = markdown.split("\n", -1);
        boolean inFence = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence;
                body.append(line).append('\n');
                continue;
            }
            if (!inFence) {
                Matcher m = FOOTNOTE_DEF.matcher(line);
                if (m.matches()) {
                    String id = m.group(1).trim();
                    StringBuilder text = new StringBuilder(m.group(2).trim());
                    while (i + 1 < lines.length) {
                        String next = lines[i + 1];
                        if (next.startsWith("    ")) {
                            text.append('\n').append(next.substring(4));
                            i++;
                        } else if (next.startsWith("\t")) {
                            text.append('\n').append(next.substring(1));
                            i++;
                        } else {
                            break;
                        }
                    }
                    if (!id.isEmpty()) defs.put(id, text.toString());
                    continue;
                }
            }
            body.append(line).append('\n');
        }
        if (defs.isEmpty()) return markdown;

        String text = body.toString();
        for (Map.Entry<String, String> e : defs.entrySet()) {
            String id = e.getKey();
            text = text.replace("[^" + id + "]",
                    "<sup class=\"qz-fn-ref\" id=\"fnref-" + footnoteId(id)
                            + "\"><a href=\"#fn-" + footnoteId(id) + "\">" + id + "</a></sup>");
        }
        StringBuilder out = new StringBuilder(text);
        out.append("\n<section class=\"qz-footnotes\"><ol>");
        for (Map.Entry<String, String> e : defs.entrySet()) {
            String id = footnoteId(e.getKey());
            out.append("<li id=\"fn-").append(id).append("\">")
                    .append(e.getValue())
                    .append(" <a class=\"qz-fn-back\" href=\"#fnref-").append(id)
                    .append("\">↩</a></li>");
        }
        out.append("</ol></section>\n");
        return out.toString();
    }

    private static String footnoteId(String id) {
        return id.replaceAll("[^A-Za-z0-9_-]", "-");
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
    static String closeUnclosedCodeFence(String markdown) {
        int fenceCount = 0;
        for (String line : markdown.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("```")) fenceCount++;
        }
        return (fenceCount & 1) == 1 ? markdown + "\n```" : markdown;
    }

    /**
     * 流式块切分专用：按整篇定稿同一套“未闭合围栏兜底”解析成 AST。
     *
     * <p>块切分器用顶层节点切块、逐节点渲染；这里补闭合是为了让“尾巴”里未闭合围栏的
     * HTML 与 {@link #toBodyHtml(String)} 完全一致（否则 commonmark 原生到 EOF 会少一个
     * 换行，定稿时出现一次可见跳变）。是否真的算“已闭合”由切分器看原始串的行尾决定，
     * 不看这个补出来的闭合围栏。</p>
     */
    static Node parseForBlocks(String markdown) {
        return PARSER.parse(closeUnclosedCodeFence(markdown == null ? "" : markdown));
    }

    /**
     * 单个顶层节点的正文 HTML：与 {@link #toBodyHtml(String)} 同一条净化 + 任务列表后处理，
     * 供流式切分器逐块渲染。只接受 Parser 产出的顶层块节点。
     */
    static String renderBlock(Node node) {
        if (node == null) return "";
        return renderTaskLists(sanitize(RENDERER.render(node)));
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
    /**
     * 把正文 HTML 里<b>相对/裸文件名</b>的图片地址改写为 {@code file://} 绝对地址。
     *
     * <p>为什么必须在 Java 侧做：本 WebView 用 {@code file:///android_asset/} 作 baseUrl，
     * 相对 src 会被 Chromium 解析到 assets 目录下（那里只有 js/css，没有图片）→ 必然裂图；
     * 而 JS 侧也拿不到工作区路径。所以只能在建 HTML 时按 {@code AgentWorkspace} 解析后替换。</p>
     *
     * <p>只动 {@code <img src=…>}：http(s)/data/content/file 一律原样保留。</p>
     */
    public static String resolveLocalImageSrcs(String bodyHtml, android.content.Context context) {
        if (bodyHtml == null || bodyHtml.isEmpty() || context == null) return bodyHtml;
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "(<img[^>]*?\\bsrc=)([\"'])([^\"']*)\\2",
                    java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher mt = p.matcher(bodyHtml);
            StringBuffer out = new StringBuffer(bodyHtml.length() + 64);
            while (mt.find()) {
                String url = mt.group(3).trim();
                String rebuilt = mt.group(0);
                if (!url.isEmpty()
                        && !url.startsWith("http://") && !url.startsWith("https://")
                        && !url.startsWith("data:") && !url.startsWith("content://")
                        && !url.startsWith("file://")) {
                    java.io.File f = com.oilquiz.app.ai.chat.component.MediaPathResolver
                            .resolveToFile(context, url);
                    if (f != null) {
                        rebuilt = mt.group(1) + mt.group(2) + "file://" + f.getAbsolutePath() + mt.group(2);
                    }
                }
                mt.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(rebuilt));
            }
            mt.appendTail(out);
            return out.toString();
        } catch (Throwable t) {
            android.util.Log.w("MarkdownHtmlConverter",
                    "图片地址解析失败，按原文渲染: " + t.getMessage());
            return bodyHtml;
        }
    }

    /**
     * 把正文 HTML 里<b>相对/裸文件名</b>的链接地址（{@code <a href=…>}）改写为 {@code file://} 绝对地址。
     *
     * <p>与 {@link #resolveLocalImageSrcs} 同一个道理，只是对象换成锚点：Agent 正文里常写
     * {@code [报告](files/报告.md)} 这种工作区相对路径，浏览器会把它补成
     * {@code file:///android_asset/files/报告.md}（assets 目录下没有业务文件），点击后
     * 路由器拿到的就是个不存在的路径。这里能解析到真实文件的直接换成绝对 {@code file://}；
     * 解析不到就<b>原样保留</b>，让 {@code MarkdownLinkRouter} 去给"工作区相似文件"这种
     * 有操作性的提示，而不是静默失败。</p>
     *
     * <p>只动锚点：http(s)、data:、content://、mailto:、tel:、#锚点一律原样保留。</p>
     */
    public static String resolveLocalLinkHrefs(String bodyHtml, android.content.Context context) {
        if (bodyHtml == null || bodyHtml.isEmpty() || context == null) return bodyHtml;
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "(<a[^>]*?\\bhref=)([\"'])([^\"']*)\\2",
                    java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher mt = p.matcher(bodyHtml);
            StringBuffer out = new StringBuffer(bodyHtml.length() + 64);
            while (mt.find()) {
                String url = mt.group(3).trim();
                String rebuilt = mt.group(0);
                if (!url.isEmpty() && !url.startsWith("#")
                        && !url.startsWith("http://") && !url.startsWith("https://")
                        && !url.startsWith("data:") && !url.startsWith("content://")
                        && !url.startsWith("file://") && !url.startsWith("mailto:")
                        && !url.startsWith("tel:") && !url.contains("://")) {
                    java.io.File f = com.oilquiz.app.ai.chat.component.MediaPathResolver
                            .resolveToFile(context, url);
                    if (f != null) {
                        rebuilt = mt.group(1) + mt.group(2) + "file://" + f.getAbsolutePath() + mt.group(2);
                    }
                }
                mt.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(rebuilt));
            }
            mt.appendTail(out);
            return out.toString();
        } catch (Throwable t) {
            android.util.Log.w("MarkdownHtmlConverter",
                    "链接地址解析失败，按原文渲染: " + t.getMessage());
            return bodyHtml;
        }
    }

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
     * JS 引导：整篇路径保留 {@code window.__qzBoot}（自动链接、复制按钮、图片预览、锚点归一化、
     * hljs/katex）；轮内块级路径额外提供 {@code window.__qzStream(payload)}：
     * <ul>
     *   <li>{@code __qzReset(prefix, blocks, tail)}：清空 body，按块重建；</li>
     *   <li>{@code __qzApply(basePrefix, prefix, append, tail)}：前缀没变时只追加新闭块、
     *       替换尾巴；新块插在旧尾巴节点之前。</li>
     * </ul>
     * 只有新闭合块跑 hljs/katex/复制按钮；尾巴只做 linkify/图片/锚点装饰，闭合后才高亮。
     * 通过 QZ.copy / QZ.previewImage 回调 Android；不新增桥方法。
     */
    private static String bootstrapJs() {
        return ""
                // 块级流式状态：每个已闭合块的顶层节点数组 + 尾巴节点数组 + JS 侧 prefixHash。
                // __qzBoot（整篇路径）会清空它们，避免整篇 swap 后还残留旧块状态。
                + "window.__qzPrefix=null;window.__qzBlocks=[];window.__qzTailNodes=[];"
                + "function qzEach(root,sel,fn){if(!root||!root.querySelectorAll)return;"
                + "var els=root.querySelectorAll(sel);for(var i=0;i<els.length;i++){fn(els[i]);}"
                + "if(root.nodeType===1&&root.matches&&root.matches(sel)){fn(root);}}"
                // KaTeX 数学：内联 $…$ / \(…\) 与块 $$…$$ /\[…\]（renderMathInElement 默认跳过 code/pre/script）
                + "function qzMath(root){if(!window.renderMathInElement)return;try{renderMathInElement(root,{"
                + "delimiters:[{left:'$$',right:'$$',display:true},{left:'\\\\[',right:'\\\\]',display:true},"
                + "{left:'$',right:'$',display:false},{left:'\\\\(',right:'\\\\)',display:false}],throwOnError:false});}catch(e){}}"
                // 自动链接：遍历文本节点，把裸 http(s) URL 包成 <a>
                + "function qzLinkify(root){if(!document.createTreeWalker)return;"
                + "var re=/(https?:\\/\\/[^\\s<>\"]+)/g;var w=document.createTreeWalker(root,NodeFilter.SHOW_TEXT,null,false);var nodes=[];var n;"
                + "while(n=w.nextNode()){if(n.parentNode&&/^(A|PRE|CODE|SCRIPT|STYLE)$/.test(n.parentNode.tagName))continue;if(re.test(n.nodeValue)){nodes.push(n);}}re.lastIndex=0;"
                + "nodes.forEach(function(node){var frag=document.createDocumentFragment();var txt=node.nodeValue;var last=0,m;"
                + "while((m=re.exec(txt))){frag.appendChild(document.createTextNode(txt.slice(last,m.index)));var a=document.createElement('a');a.href=m[1];a.textContent=m[1];frag.appendChild(a);last=m.index+m[1].length;}"
                + "frag.appendChild(document.createTextNode(txt.slice(last)));node.parentNode.replaceChild(frag,node);});}"
                // 代码块语法高亮（highlight.js）：先上色，再加复制按钮；data-qz-hl 防同一块重复高亮
                + "function qzHl(root){if(!window.hljs)return;qzEach(root,'pre code',function(el){"
                + "if(el.getAttribute('data-qz-hl')==='1')return;try{hljs.highlightElement(el);}catch(e){}el.setAttribute('data-qz-hl','1');});}"
                // 代码块复制按钮
                + "function qzCopy(root){qzEach(root,'pre',function(p){if(p.getAttribute('data-qz')==='1')return;p.setAttribute('data-qz','1');p.style.position='relative';"
                + "var b=document.createElement('button');b.className='qz-copy';b.textContent='复制';b.onclick=(function(pp){return function(){try{QZ.copy(pp.innerText);}catch(e){}};})(p);"
                + "p.appendChild(b);});}"
                // 图片点击预览（事件委托）：只在 document 上绑一次，避免流式 N 个分片后叠 N 个预览窗
                + "function qzBindImgs(){if(window.__qzImgBound)return;window.__qzImgBound=1;"
                + "document.addEventListener('click',function(e){var el=e.target;while(el&&el.tagName!=='IMG'&&el!==document){el=el.parentElement;}"
                + "if(el&&el.tagName==='IMG'){var s=el.currentSrc||el.src||el.getAttribute('src');"
                + "if(s&&s.indexOf('data:')!==0){e.preventDefault();try{try{s=decodeURIComponent(s);}catch(y){}QZ.previewImage(s);}catch(x){}}}},true);}"
                // 图片样式 + 加载失败可读原因（取 currentSrc；相对路径原样传 Java 会命中“无法识别”Toast）
                + "function qzImgs(root){qzBindImgs();qzEach(root,'img',function(g){g.style.cursor='zoom-in';"
                + "if(!g.__qzErr){g.__qzErr=1;g.addEventListener('error',function(){try{this.style.display='none';"
                + "var t=document.createElement('div');t.className='qz-imgfail';"
                + "t.textContent='\\u26a0 \\u56fe\\u7247\\u65e0\\u6cd5\\u52a0\\u8f7d\\uff1a'+(this.getAttribute('src')||'\\uff08\\u7a7a\\u5730\\u5740\\uff09');"
                + "this.parentNode.insertBefore(t,this);}catch(e){}});"
                + "if(g.complete&&g.naturalWidth===0){g.dispatchEvent(new Event('error'));}}});}"
                // 锚点归一化（不调桥）：去 target=_blank、补 cursor；真正路由在原生 WebViewClient
                + "function qzAnchors(root){qzEach(root,'a',function(a){a.style.cursor='pointer';"
                + "if(a.target){a.removeAttribute('target');}a.rel='noopener';});}"
                // 装饰开关：闭块 true/true/true；尾巴 false/false/false（linkify/图片/锚点仍做）
                // 宽表格：包一层横向滚动容器（不能给 table 本身 display:block，会拆掉表格布局）
                + "function qzTables(root){var tbs=root.querySelectorAll?root.querySelectorAll('table'):[];"
                + "for(var i=0;i<tbs.length;i++){var tb=tbs[i];var pn=tb.parentNode;"
                + "if(pn&&pn.className==='table-wrap')continue;"
                + "if(!pn)continue;var wr=document.createElement('div');wr.className='table-wrap';"
                + "pn.insertBefore(wr,tb);wr.appendChild(tb);}}"
                + "function qzDecorate(root,doHl,doMath,doCopy){"
                + "try{qzTables(root);}catch(e){}"
                + "if(doMath){try{qzMath(root);}catch(e){}}"
                + "try{qzLinkify(root);}catch(e){}"
                + "if(doHl){try{qzHl(root);}catch(e){}}"
                + "if(doCopy){try{qzCopy(root);}catch(e){}}"
                + "try{qzImgs(root);}catch(e){}"
                + "try{qzAnchors(root);}catch(e){}}"
                // 解析块 HTML：template 保住 <tr> 等被 div 解析会丢的节点；失败回退 div。
                + "function qzHolder(html,doHl,doMath,doCopy){var holder=document.createElement('div');"
                + "if(window.HTMLTemplateElement){var t=document.createElement('template');t.innerHTML=html;holder.appendChild(t.content);}"
                + "else{holder.innerHTML=html;}qzDecorate(holder,doHl,doMath,doCopy);return holder;}"
                + "function qzAppendBlock(html){var holder=qzHolder(html,true,true,true);var nodes=[];"
                + "var ref=window.__qzTailNodes.length?window.__qzTailNodes[0]:null;"
                + "while(holder.firstChild){var n=holder.firstChild;holder.removeChild(n);document.body.insertBefore(n,ref);nodes.push(n);}"
                + "if(nodes.length){window.__qzBlocks.push({nodes:nodes});}}"
                + "function qzAppendTail(html){if(!html)return;var holder=qzHolder(html,false,false,false);"
                + "while(holder.firstChild){var n=holder.firstChild;holder.removeChild(n);document.body.appendChild(n);window.__qzTailNodes.push(n);}}"
                + "function qzReset(prefix,blocks,tail){document.body.innerHTML='';window.__qzPrefix=null;"
                + "window.__qzBlocks=[];window.__qzTailNodes=[];for(var i=0;i<blocks.length;i++){qzAppendBlock(blocks[i]);}"
                + "qzAppendTail(tail||'');window.__qzPrefix=prefix;}"
                + "function qzApply(base,prefix,blocks,tail){if(window.__qzPrefix!==base)return false;"
                + "for(var i=0;i<blocks.length;i++){qzAppendBlock(blocks[i]);}"
                + "for(var j=0;j<window.__qzTailNodes.length;j++){var n=window.__qzTailNodes[j];if(n.parentNode)n.parentNode.removeChild(n);}"
                + "window.__qzTailNodes=[];qzAppendTail(tail||'');window.__qzPrefix=prefix;return true;}"
                // 整篇路径：清空块状态并全量装饰（定稿/回退/主题重载仍走这里）
                + "window.__qzBoot=function(){window.__qzPrefix=null;window.__qzBlocks=[];window.__qzTailNodes=[];"
                + "qzDecorate(document.body,true,true,true);};"
                // Java -> JS 的唯一块级入口；返回 false 让 Java 下一 tick 整体重建
                + "window.__qzStream=function(p){if(!p)return false;"
                + "if(p.mode==='reset'){qzReset(p.prefix,p.blocks||[],p.tail||'');return true;}"
                + "if(p.mode==='apply'){return qzApply(p.base,p.prefix,p.blocks||[],p.tail||'');}return false;};"
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
                // 图片：限宽 + **限高**（旧 Markwon+Glide 路径有 override(屏宽88%,屏高60%) 硬约束，
                // WebView 路径原先只有 max-width → 超长截图整幅撑进气泡、配合 ResizeObserver 回跳高度
                // 造成列表抖动；这里用固定 px 上限而非 vh，避免"视口高依赖内容高"形成测量回环）
                + "img{max-width:100%;max-height:420px;object-fit:contain;height:auto;border-radius:6px;display:block;margin:0.4em 0}"
                // 图片加载失败的可读占位（JS 在 onerror 时插入）
                + ".qz-imgfail{font-size:12px;line-height:1.5;color:#8A8F98;background:rgba(138,143,152,0.14);"
                + "border-radius:6px;padding:8px 10px;margin:0.4em 0;word-break:break-all}"
                + "code{background:" + codeBg + ";padding:0.12em 0.4em;border-radius:4px;"
                + "font-family:'Roboto Mono','Menlo',monospace;font-size:0.88em}"
                + "pre{position:relative;background:" + preBg + ";padding:12px;padding-top:30px;border-radius:8px;overflow-x:auto;margin:0.6em 0}"
                + "pre code{background:transparent;padding:0;font-size:0.85em;line-height:1.5}"
                + "button.qz-copy{position:absolute;top:5px;right:6px;font-size:11px;color:" + muted
                + ";background:" + btnBg + ";border:none;border-radius:4px;padding:2px 8px;cursor:pointer}"
                + "blockquote{margin:0.6em 0;padding:0.2em 0.9em;border-left:4px solid " + quoteBar
                + ";color:" + muted + ";background:" + (night ? "#111827" : "#f8fafc") + ";border-radius:0 6px 6px 0}"
                + "table{border-collapse:collapse;width:100%;margin:0;font-size:0.92em;table-layout:auto}"
                + ".table-wrap{overflow-x:auto;margin:0.6em 0;-webkit-overflow-scrolling:touch}"
                + ".table-wrap table{min-width:100%}"
                + "th,td{border:1px solid " + border + ";padding:7px 10px;text-align:left;vertical-align:top;word-break:break-word}"
                + "th{background:" + thBg + ";font-weight:700}"
                + "tr:nth-child(even) td{background:" + rowAlt + "}"
                + "hr{border:none;border-top:1px solid " + border + ";margin:1em 0}"
                + "del{color:" + muted + "}"
                + ".qz-footnotes{margin-top:1.2em;border-top:1px solid " + border + ";padding-top:.6em;font-size:.9em;color:" + muted + "}"
                + ".qz-footnotes ol{margin:.4em 0;padding-left:1.4em}"
                + ".qz-fn-ref{font-size:.75em;vertical-align:super;line-height:0}"
                + ".qz-fn-back{text-decoration:none;margin-left:.3em}";
    }
}
