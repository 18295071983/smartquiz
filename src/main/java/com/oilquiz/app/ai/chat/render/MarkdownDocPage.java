package com.oilquiz.app.ai.chat.render;

import android.content.Context;
import android.util.Base64;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Markdown 文档页构建器（2026-10-25）。
 *
 * <p>为什么单独抽一层：主气泡的解析器是 commonmark（{@link MarkdownHtmlConverter}），
 * 只带 tables / strikethrough 两个扩展；一旦遇到"正经 markdown 文件"（脚注、定义列表、
 * 任务列表、$公式$）就会退化。卡片侧此前只有 Markwon 版 {@code markdown_card}，能力同样有限。
 * 这个类把"一整篇 markdown → 可交互 HTML 文档"做成可复用产物：markdown-it + footnote /
 * deflist / tasklists 插件 + KaTeX + highlight.js，全部内联成**单文件**，供
 * {@code markdown_doc} 卡片落盘后交给 {@code HtmlCardView} 加载。</p>
 *
 * <p>为什么全内联：卡片 WebView 以 {@code LOCAL_FILE} 档加载，{@code setAllowFileAccessFromFileURLs(false)}
 * 会挡掉 file 页对 {@code file:///android_asset} 子资源的读取，外链 js/css 会静默失败；
 * 内联后页面零依赖，离线可用。KaTeX 只内联常用 6 个 woff2 字体，其余走系统回退。</p>
 */
public final class MarkdownDocPage {

    private MarkdownDocPage() {
    }

    /** markdown-it + 三个插件（只读一次，进程内复用） */
    private static volatile String sMdLibs;
    /** KaTeX + auto-render + highlight.js */
    private static volatile String sMathLibs;
    /** KaTeX CSS（字体已内联） */
    private static volatile String sKatexCss;

    /** 内联字体白名单：公式排版最常用的几款，控制在 ~120KB */
    private static final String[] FONT_WHITELIST = {
            "KaTeX_Main-Regular.woff2",
            "KaTeX_Main-Italic.woff2",
            "KaTeX_Main-Bold.woff2",
            "KaTeX_Math-Italic.woff2",
            "KaTeX_Size1-Regular.woff2",
            "KaTeX_Size2-Regular.woff2",
            "KaTeX_Size3-Regular.woff2",
            "KaTeX_Size4-Regular.woff2",
            "KaTeX_AMS-Regular.woff2",
    };

    /**
     * 生成完整 HTML 文档。
     *
     * @param context  用于读 assets、解析本地图片
     * @param markdown markdown 原文（未净化前先过 {@link MarkdownHtmlConverter#sanitize}）
     * @param night    深色主题
     * @param title    预留标题（当前不渲染进正文，卡片壳已有标题）
     */
    public static String build(Context context, String markdown, boolean night, String title) {
        return build(context, markdown, night, title, false, true);
    }

    /** 全屏阅读页：比卡片更宽的正文栏、更大的左右留白。 */
    public static String buildForScreen(Context context, String markdown, boolean night) {
        return buildForScreen(context, markdown, night, true);
    }

    /**
     * @param resolveWorkspaceImages true=把 md 里的相对图片按工作区解析成绝对 file://；
     *                               false=保留相对地址，交给调用方的 baseUrl 解析
     *                               （文件预览场景：图片就在 .md 同目录）
     */
    public static String buildForScreen(Context context, String markdown, boolean night,
                                        boolean resolveWorkspaceImages) {
        return build(context, markdown, night, "", true, resolveWorkspaceImages);
    }

    private static String build(Context context, String markdown, boolean night, String title,
                                boolean screen, boolean resolveWorkspaceImages) {
        String md = MarkdownHtmlConverter.sanitize(markdown == null ? "" : markdown);
        if (resolveWorkspaceImages) {
            md = rewriteLocalImages(context, md);
        }
        String safeMd = md.replace("</script", "<\\/script").replace("</SCRIPT", "<\\/SCRIPT");

        return "<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=3,user-scalable=yes\">"
                + "<style>" + katexCss(context) + "</style>"
                + "<style>" + pageCss(night) + (screen ? screenCss(night) : "") + "</style>"
                + "<script>" + mdLibs(context) + "</script>"
                + "<script>" + mathLibs(context) + "</script>"
                + "</head><body class=\"" + (night ? "dark" : "light") + "\">"
                + "<div id=\"out\"></div>"
                + "<script id=\"md-src\" type=\"text/plain\">" + safeMd + "</script>"
                + "<script>" + renderJs() + "</script>"
                + "</body></html>";
    }

    // ------------------------------------------------------------------ 渲染脚本

    private static String renderJs() {
        return "(function(){"
                + "var el=document.getElementById('md-src');if(!el)return;"
                + "var src=el.textContent.replace(/^\\n+/,'');"
                + "var md=window.markdownit({html:true,linkify:true,breaks:false,typographer:false});"
                + "md.validateLink=function(u){return !/^\\s*(javascript|vbscript):/i.test(u||'');};"
                + "try{if(window.markdownitFootnote)md.use(window.markdownitFootnote);}catch(e){}"
                + "try{if(window.markdownitDeflist)md.use(window.markdownitDeflist);}catch(e){}"
                + "try{if(window.markdownitTaskLists)md.use(window.markdownitTaskLists,{enabled:false});}catch(e){}"
                + "var out=document.getElementById('out');"
                + "try{out.innerHTML=md.render(src);}catch(e){out.textContent=src;}"
                // 宽表格：包一层横向滚动容器（不能给 table 本身 display:block，会拆掉表格布局）
                + "var tbs=out.querySelectorAll('table');"
                + "for(var q=0;q<tbs.length;q++){var tb=tbs[q];var pn=tb.parentNode;"
                + "if(pn&&pn.className==='table-wrap')continue;var wr=document.createElement('div');"
                + "wr.className='table-wrap';pn.insertBefore(wr,tb);wr.appendChild(tb);}"
                // 公式：$…$ / $$…$$ / \(…\) / \[…\]（KaTeX auto-render 默认跳过 code/pre）
                + "if(window.renderMathInElement){try{renderMathInElement(out,{"
                + "delimiters:[{left:'$$',right:'$$',display:true},{left:'\\\\[',right:'\\\\]',display:true},"
                + "{left:'$',right:'$',display:false},{left:'\\\\(',right:'\\\\)',display:false}],"
                + "throwOnError:false});}catch(e){}}"
                // 代码高亮
                + "if(window.hljs){var cs=out.querySelectorAll('pre code');"
                + "for(var i=0;i<cs.length;i++){try{hljs.highlightElement(cs[i]);}catch(e){}}}"
                // 代码块复制按钮
                + "var pres=out.querySelectorAll('pre');"
                + "for(var j=0;j<pres.length;j++){(function(p){p.setAttribute('data-qz','1');"
                + "var b=document.createElement('button');b.className='md-copy';b.textContent='\\u590d\\u5236';"
                + "b.onclick=function(){try{Android.copy(p.innerText);}catch(e){}};p.appendChild(b);})(pres[j]);}"
                // 图片点击预览（桥只给工作区本地文件档）
                + "out.addEventListener('click',function(e){var t=e.target;while(t&&t.tagName!=='IMG'&&t!==out){t=t.parentElement;}"
                + "if(t&&t.tagName==='IMG'){e.preventDefault();try{Android.previewImage(t.getAttribute('src'));}catch(x){}}},true);"
                + "})();";
    }

    // ------------------------------------------------------------------ 资源内联

    private static String mdLibs(Context ctx) {
        String v = sMdLibs;
        if (v == null) {
            synchronized (MarkdownDocPage.class) {
                v = sMdLibs;
                if (v == null) {
                    v = js(ctx, "js/markdown-it.min.js")
                            + "\n" + js(ctx, "js/markdown-it-footnote.min.js")
                            + "\n" + js(ctx, "js/markdown-it-deflist.min.js")
                            + "\n" + js(ctx, "js/markdown-it-task-lists.min.js");
                    sMdLibs = v;
                }
            }
        }
        return v;
    }

    private static String mathLibs(Context ctx) {
        String v = sMathLibs;
        if (v == null) {
            synchronized (MarkdownDocPage.class) {
                v = sMathLibs;
                if (v == null) {
                    v = js(ctx, "js/katex.min.js")
                            + "\n" + js(ctx, "js/katex-auto-render.min.js")
                            + "\n" + js(ctx, "js/highlight.min.js");
                    sMathLibs = v;
                }
            }
        }
        return v;
    }

    private static String katexCss(Context ctx) {
        String v = sKatexCss;
        if (v == null) {
            synchronized (MarkdownDocPage.class) {
                v = sKatexCss;
                if (v == null) {
                    String css = text(ctx, "css/katex.min.css");
                    StringBuilder sb = new StringBuilder(css.length() + 200_000);
                    int i = 0;
                    while (true) {
                        int start = css.indexOf("url(fonts/", i);
                        if (start < 0) {
                            sb.append(css, i, css.length());
                            break;
                        }
                        int end = css.indexOf(')', start);
                        if (end < 0) {
                            sb.append(css, i, css.length());
                            break;
                        }
                        String name = css.substring(start + "url(fonts/".length(), end).trim();
                        sb.append(css, i, start);
                        String data = fontData(ctx, name);
                        sb.append(data != null ? "url(data:font/woff2;base64," + data + ")" : css.substring(start, end + 1));
                        i = end + 1;
                    }
                    v = sb.toString();
                    sKatexCss = v;
                }
            }
        }
        return v;
    }

    private static String fontData(Context ctx, String name) {
        boolean wanted = false;
        for (String f : FONT_WHITELIST) {
            if (f.equals(name)) {
                wanted = true;
                break;
            }
        }
        if (!wanted) return null;
        try (InputStream in = ctx.getAssets().open("css/fonts/" + name)) {
            byte[] buf = readAll(in);
            return Base64.encodeToString(buf, Base64.NO_WRAP);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String js(Context ctx, String path) {
        return text(ctx, path).replace("</script", "<\\/script").replace("</SCRIPT", "<\\/SCRIPT");
    }

    private static String text(Context ctx, String path) {
        try (InputStream in = ctx.getAssets().open(path)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ 本地图片改写

    /**
     * 把 {@code ![alt](相对路径)} 的图片地址按工作区解析成绝对 {@code file://}。
     * 只处理 markdown 图片语法；http(s)/data/file 原样保留。
     */
    private static String rewriteLocalImages(Context ctx, String md) {
        if (ctx == null || md == null || md.isEmpty()) return md;
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "(!\\[[^\\]]*\\]\\()\\s*([^)\\s]+)\\s*(\\))");
            java.util.regex.Matcher m = p.matcher(md);
            StringBuffer sb = new StringBuffer(md.length() + 64);
            while (m.find()) {
                String url = m.group(2);
                String lower = url.toLowerCase(java.util.Locale.US);
                String rebuilt = m.group(0);
                if (!lower.startsWith("http://") && !lower.startsWith("https://")
                        && !lower.startsWith("data:") && !lower.startsWith("file://")
                        && !lower.startsWith("content://")) {
                    java.io.File f = com.oilquiz.app.ai.chat.component.MediaPathResolver
                            .resolveToFile(ctx, url);
                    if (f != null && f.isFile()) {
                        rebuilt = m.group(1) + "file://" + f.getAbsolutePath() + m.group(3);
                    }
                }
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(rebuilt));
            }
            m.appendTail(sb);
            return sb.toString();
        } catch (Throwable t) {
            return md;
        }
    }

    // ------------------------------------------------------------------ 样式

    /** 全屏阅读页的额外排版：正文栏限宽 + 左右留白，长文档不至于贴边。 */
    private static String screenCss(boolean night) {
        return "body{padding:18px 20px 36px;max-width:860px;margin:0 auto;font-size:16px;"
                + "background:transparent}"
                + "h1{font-size:1.7em}h2{font-size:1.4em}h3{font-size:1.2em}"
                + "table{font-size:.95em}"
                + "pre{font-size:.9em}";
    }

    private static String pageCss(boolean night) {
        String fg = night ? "#E5E7EB" : "#1F2937";
        String muted = night ? "#9CA3AF" : "#6B7280";
        String border = night ? "#374151" : "#D1D5DB";
        String codeBg = night ? "#111827" : "#F3F4F6";
        String thBg = night ? "#1F2937" : "#EEF2F7";
        String rowAlt = night ? "#0D1420" : "#FAFBFC";
        String quoteBar = night ? "#4B5563" : "#CBD5E1";
        String quoteBg = night ? "#111827" : "#F8FAFC";
        String link = night ? "#60A5FA" : "#2563EB";
        return ""
                + "*{box-sizing:border-box}"
                + "html,body{margin:0;padding:0;background:transparent;color:" + fg + ";"
                + "font-size:15px;line-height:1.75;font-family:-apple-system,'PingFang SC','Roboto','Noto Sans SC',sans-serif;"
                + "word-wrap:break-word;-webkit-text-size-adjust:100%}"
                + "body{padding:2px 2px 10px}"
                + "#out>*:first-child{margin-top:0}"
                + "h1,h2,h3,h4,h5,h6{margin:1em 0 .45em;line-height:1.35;font-weight:700;color:" + fg + "}"
                + "h1{font-size:1.55em;border-bottom:1px solid " + border + ";padding-bottom:.25em}"
                + "h2{font-size:1.32em;border-bottom:1px solid " + border + ";padding-bottom:.2em}"
                + "h3{font-size:1.16em}h4{font-size:1.05em}h5{font-size:1em}h6{font-size:.95em;color:" + muted + "}"
                + "p{margin:.55em 0}ul,ol{margin:.45em 0;padding-left:1.55em}li{margin:.2em 0}"
                + "li.task-list-item{list-style:none;margin-left:-1.2em}"
                + "li.task-list-item input{margin-right:.45em;vertical-align:middle}"
                + "a{color:" + link + ";text-decoration:none}"
                + "blockquote{margin:.7em 0;padding:.35em .9em;border-left:4px solid " + quoteBar + ";"
                + "background:" + quoteBg + ";border-radius:0 6px 6px 0;color:" + muted + "}"
                + "code{background:" + codeBg + ";padding:.12em .38em;border-radius:4px;"
                + "font-family:'Roboto Mono','Menlo',monospace;font-size:.88em}"
                + "pre{position:relative;background:" + codeBg + ";padding:12px;padding-top:30px;border-radius:8px;"
                + "overflow-x:auto;margin:.7em 0}"
                + "pre code{background:transparent;padding:0;font-size:.85em;line-height:1.5}"
                + "button.md-copy{position:absolute;top:5px;right:6px;font-size:11px;color:" + muted + ";"
                + "background:rgba(127,127,127,.16);border:none;border-radius:4px;padding:2px 8px}"
                + "table{border-collapse:collapse;width:100%;margin:0;font-size:.92em;table-layout:auto}"
                + ".table-wrap{overflow-x:auto;margin:.7em 0;-webkit-overflow-scrolling:touch}"
                + ".table-wrap table{min-width:100%}"
                + "th,td{border:1px solid " + border + ";padding:7px 10px;vertical-align:top;word-break:break-word}"
                + "th{background:" + thBg + ";font-weight:700}"
                + "tr:nth-child(even) td{background:" + rowAlt + "}"
                + "hr{border:none;border-top:1px solid " + border + ";margin:1.1em 0}"
                + "img{max-width:100%;height:auto;border-radius:6px;display:block;margin:.5em 0}"
                + "kbd{font-size:.82em;padding:.1em .4em;border:1px solid " + border + ";border-bottom-width:2px;"
                + "border-radius:4px;background:" + codeBg + ";font-family:inherit}"
                + "mark{background:#FDE68A;color:#111827;padding:0 .15em;border-radius:3px}"
                + "details{margin:.6em 0;padding:.4em .7em;border:1px solid " + border + ";border-radius:6px}"
                + "summary{cursor:pointer;font-weight:600}"
                + "dl{margin:.6em 0}dt{font-weight:700;margin-top:.4em}dd{margin:.2em 0 .6em 1.4em;color:" + muted + "}"
                + ".katex-display{overflow-x:auto;overflow-y:hidden;padding:.2em 0}"
                + ".footnotes{font-size:.9em;color:" + muted + ";border-top:1px solid " + border + ";margin-top:1.2em;padding-top:.6em}"
                + ".footnotes-list{padding-left:1.2em}"
                + ".footnote-ref a{font-size:.78em;padding:0 .1em}"
                + ".footnote-backref{text-decoration:none}"
                + "del{color:" + muted + "}";
    }
}
