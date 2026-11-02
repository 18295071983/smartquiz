package com.oilquiz.app.ai.tool;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网页正文抽取（Jsoup DOM + Readability 风格打分）。
 *
 * <p>WHY(2026-11-02)：{@code network_search} 以前用正则把 HTML 切成文本（删标签 → 按换行切片），
 * 于是每个站点都要单独打补丁：脚本没剥、行内硬换行被当成句边界、侧边栏混进正文……
 * 真机复测四轮，问题都出在"用正则处理 HTML"这件事本身。行业做法（Readability / trafilatura）
 * 是解析成 DOM、按文本密度与链接密度定位正文容器，再从块级元素取段落。
 * 这套实现原本在 {@link WebPageReaderTool} 里，这里抽成共用工具，让两个工具走同一条路径。</p>
 *
 * <p>流程：解析 → 去噪（script/style/nav/footer/表单等）→ 语义标签优先 → 密度打分 → 向上合并父容器
 * → 按块级元素输出段落文本。</p>
 */
final class HtmlContentExtractor {

    private HtmlContentExtractor() {
    }

    /** 噪声容器：整块删除，不参与正文打分。 */
    private static final String NOISE_SELECTOR =
            "script,style,noscript,iframe,svg,form,button,select,input,textarea,"
                    + "nav,header,footer,aside,template,object,embed,canvas";

    /** Readability 负向特征：类名/ID 命中即视为噪声容器（评论/导航/广告/推荐等）。 */
    private static final String[] NEGATIVE_HINTS = {
            "comment", "comments", "footer", "sidebar", "related", "breadcrumb",
            "menu", "nav", "share", "social", "advert", "ad-", "ads", "banner",
            "popup", "modal", "newsletter", "subscribe", "login", "signup",
            "recommend", "topbar", "toolbar", "sponsor", "promo", "hidden",
            "tags", "meta", "author", "date", "copyright"
    };

    /** Readability 正向特征：类名/ID 命中即加权（正文容器常见命名）。 */
    private static final String[] POSITIVE_HINTS = {
            "article", "content", "main", "post", "entry", "story", "body", "text"
    };

    /**
     * 参与正文切段的块级标签。div/section 也算：不少站点（尤其政企站）用 div 排版，
     * 只认 p/li 会把整段正文丢掉；靠"只取最内层块"的规则避免重复。
     */
    private static final String BLOCK_SELECTOR =
            "h1,h2,h3,h4,h5,h6,p,li,blockquote,pre,td,dd,div,section,article,figcaption";

    /** 解析 HTML。解析失败返回 null（调用方回退到纯文本清洗）。 */
    static Document parse(String html, String baseUri) {
        if (html == null || html.trim().isEmpty()) return null;
        try {
            return Jsoup.parse(html, baseUri == null ? "" : baseUri);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 按声明/内容判断编码后解码 HTML 字节。
     *
     * <p>CHARSET(2026-11-02, 真站验证)：OkHttp 的 {@code body().string()} 只看
     * {@code Content-Type} 里的 charset，缺省按 UTF-8 —— 国内大量老站（people.com.cn、很多政务站）
     * 是 GBK 且头里不带 charset，结果整页中文变成乱码，抽取长度和关键词全失真。
     * 顺序：响应头 charset → meta charset → 严格 UTF-8 试解 → GB18030 兜底。</p>
     */
    static String decodeHtml(byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0) return "";

        String declared = charsetFromContentType(contentType);
        if (declared == null) declared = charsetFromMeta(bytes);
        if (declared != null) {
            try {
                return new String(bytes, Charset.forName(normalizeCharset(declared)));
            } catch (Throwable ignored) {
                // 声明不可用，继续走探测
            }
        }

        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (Throwable ignored) {
            try {
                return new String(bytes, Charset.forName("GB18030"));
            } catch (Throwable ignored2) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
        }
    }

    private static String charsetFromContentType(String contentType) {
        if (contentType == null) return null;
        Matcher m = Pattern.compile("(?i)charset\\s*=\\s*[\"']?([a-z0-9_\\-]+)").matcher(contentType);
        return m.find() ? m.group(1) : null;
    }

    /** 从 {@code <meta charset>} / {@code <meta http-equiv content=...charset=...>} 里找声明。 */
    private static String charsetFromMeta(byte[] bytes) {
        int len = Math.min(bytes.length, 4096);
        String head = new String(bytes, 0, len, StandardCharsets.ISO_8859_1);
        Matcher m = Pattern.compile("(?i)charset\\s*=\\s*[\"']?([a-z0-9_\\-]+)").matcher(head);
        return m.find() ? m.group(1) : null;
    }

    /** gb2312/gbk 一律用 GB18030（超集），避免生僻字解码失败。 */
    private static String normalizeCharset(String name) {
        String n = name.toLowerCase();
        if (n.equals("gb2312") || n.equals("gbk") || n.equals("gb18030") || n.equals("x-gbk")) {
            return "GB18030";
        }
        return name;
    }

    /**
     * 正文容器定位（Readability 风格）：
     * 1. 语义标签优先：article / main / [role=main]
     * 2. 否则文本密度评分：块级元素按"正文长度 × 标点密度 ÷ 链接密度"打分，取最高
     * 3. 小元素向上提升父容器（合并多段落）
     * 4. 得分过低回退 body
     */
    static Element mainContent(Document doc) {
        if (doc == null) return null;
        Document clone = doc.clone();
        clone.select(NOISE_SELECTOR).remove();
        Element body = clone.body();
        if (body == null) return clone;

        Element semantic = clone.selectFirst("article, [role=main], main");
        // 语义标签也不能盲信：不少站点把"相关阅读/推荐"标成 <article>，直接返回会抽出侧栏。
        // 实测新浪正文页就这样（只抽到 433 字的页脚），所以仍要过一遍"像不像正文"。
        if (semantic != null && isPlausibleContent(semantic)) return semantic;

        Element best = body;
        double bestScore = 0;
        for (Element el : body.select("div, section, td, blockquote, article")) {
            double score = scoreContentElement(el) * hintFactor(el);
            if (score > bestScore) {
                bestScore = score;
                best = el;
            }
        }
        if (bestScore < 50) return body;

        Element parent = best.parent();
        while (parent != null && parent != body) {
            String pt = parent.text();
            if (pt == null || pt.trim().length() < best.text().trim().length() * 1.5) break;
            double parentScore = scoreContentElement(parent) * hintFactor(parent);
            if (parentScore < bestScore * 0.8) break;
            best = parent;
            bestScore = parentScore;
            parent = parent.parent();
        }

        // EMPTY-GUARD(2026-11-02)：门户首页那种"链接密度高、正文很短"的页面，打分可能选中一个
        // 几乎没字的容器（实测新浪首页抽出来是 0 字）——这时退回 body。
        // 但反过来不能乱退：税务局那类页面正文容器很短、body 却是一屏导航，退到 body 等于把导航
        // 当正文（真机实测：149 字页脚 → 3705 字导航+新闻列表）。所以按"谁更像正文"比链接密度。
        String chosen = best.text().trim();
        if (chosen.isEmpty()) return body;
        if (linkRatio(best) > 0.5 && linkRatio(body) < linkRatio(best)) return body;
        return best;
    }

    /** 直接把 HTML 转成正文文本（一行一步，内部已去噪 + 定位正文）。 */
    static String mainText(String html) {
        Document doc = parse(html, null);
        if (doc == null) return null;
        return toText(mainContent(doc));
    }

    /**
     * 容器 → 文本：按块级元素逐段输出，段间空行。
     *
     * <p>关键点：{@code Element.text()} 会把源码里的硬换行折成空格（英文站每 ~70 字符一个换行），
     * 所以"一句英文被拆成三段"这类问题在 DOM 层面就不存在——这正是正则方案做不到的。</p>
     */
    static String toText(Element root) {
        if (root == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Element el : root.getAllElements()) {
            if (!isBlock(el)) continue;
            // 只取"最内层"块：含子块的容器交给子块输出，避免整段重复。
            // 注意 Jsoup 的 select 会包含元素自身，这里用"有没有自身以外的匹配"来判断，
            // 不能对 Elements 调 remove（那会连带把节点从 DOM 里摘掉，后面的 headings 就空了）。
            boolean hasBlockChild = false;
            for (Element inner : el.select(BLOCK_SELECTOR)) {
                if (inner != el) {
                    hasBlockChild = true;
                    break;
                }
            }
            if (hasBlockChild) continue;
            String t = el.text().trim();
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(t);
        }
        if (sb.length() == 0) {
            String t = root.text().trim();
            if (!t.isEmpty()) sb.append(t);
        }
        return sb.toString().trim();
    }

    /** 正文段落列表（供 contentSlices 用）。 */
    static List<String> paragraphs(Element root) {
        List<String> out = new ArrayList<>();
        String text = toText(root);
        if (text.isEmpty()) return out;
        for (String p : text.split("\\n{2,}")) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** 标题结构：从 DOM 取 h1/h2 文本（已去噪，不含 script 里伪造的标签串）。 */
    static List<String> headings(Element root) {
        List<String> out = new ArrayList<>();
        if (root == null) return out;
        for (Element h : root.select("h1, h2")) {
            String t = h.text().trim();
            if (!t.isEmpty()) out.add(h.tagName().toUpperCase() + ": " + t);
        }
        return out;
    }

    private static boolean isBlock(Element el) {
        switch (el.tagName()) {
            case "h1": case "h2": case "h3": case "h4": case "h5": case "h6":
            case "p": case "li": case "blockquote": case "pre": case "td": case "dd":
            case "div": case "section": case "article": case "figcaption":
                return true;
            default:
                return false;
        }
    }

    /** 候选容器"像不像正文"：至少 200 字，且链接占比不超过 50%（导航/列表都是链接）。 */
    private static boolean isPlausibleContent(Element el) {
        String text = el.text();
        if (text == null) return false;
        int len = text.trim().length();
        if (len < 200) return false;
        int linkLen = 0;
        for (Element a : el.select("a")) {
            String at = a.text();
            linkLen += at == null ? 0 : at.length();
        }
        return (double) linkLen / len <= 0.5;
    }

    /**
     * Readability 密度评分：
     * - 文本过短(&lt;80字)或链接占比&gt;50%（导航/列表）直接淘汰
     * - 得分 = (正文文本长度) × (0.3 + 标点密度×0.8) × (1 - 链接密度)
     */
    private static double scoreContentElement(Element el) {
        String text = el.text();
        if (text == null) return 0;
        int textLen = text.trim().length();
        if (textLen < 80) return 0;

        int linkLen = 0;
        for (Element a : el.select("a")) {
            String at = a.text();
            linkLen += at == null ? 0 : at.length();
        }
        double linkDensity = (double) linkLen / textLen;
        if (linkDensity > 0.5) return 0;

        int punct = 0;
        for (int i = 0; i < textLen; i++) {
            char c = text.charAt(i);
            if (c == '，' || c == '。' || c == ',' || c == '.' || c == '；' || c == ';'
                    || c == '：' || c == ':' || c == '!' || c == '！' || c == '?' || c == '？') {
                punct++;
            }
        }
        double punctDensity = (double) punct / Math.max(1, textLen) * 100;
        return (textLen - linkLen) * (0.3 + punctDensity * 0.8) * (1 - linkDensity);
    }

    private static double hintFactor(Element el) {
        String id = el.id() == null ? "" : el.id().toLowerCase();
        String[] classes = el.className().toLowerCase().split("\\s+");

        for (String neg : NEGATIVE_HINTS) {
            if (idMatches(id, neg)) return 0.05;
        }
        for (String cls : classes) {
            if (cls.isEmpty()) continue;
            for (String neg : NEGATIVE_HINTS) {
                if (wordMatches(cls, neg)) return 0.05;
            }
        }
        for (String pos : POSITIVE_HINTS) {
            if (idMatches(id, pos) || id.contains(pos)) return 1.5;
            for (String cls : classes) {
                if (wordMatches(cls, pos) || cls.contains(pos)) return 1.5;
            }
        }
        return 1.0;
    }

    private static boolean idMatches(String id, String hint) {
        return id.equals(hint) || id.contains(hint + "-") || id.contains("-" + hint) || id.contains("_" + hint);
    }

    private static boolean wordMatches(String word, String hint) {
        return word.equals(hint) || word.contains(hint + "-") || word.contains("-" + hint) || word.contains("_" + hint);
    }

    // ==================== 质量评估（page_type / quality / verdict） ====================

    /**
     * 一页的质量结论：让上层能判断"这页能不能当来源"，而不是只看 status=success。
     *
     * <p>WHY(2026-11-02, 真机)：税局那篇文章失效后 301 回首页，工具把 3705 字导航当正文返回，
     * 还标成"新闻资讯"，任何上层都会把它当文章用。质量分不是为了好看，是为了让这种页面
     * 在字段层面就露馅（verdict=not_article/degraded），而不是靠调用方自己数正文长度。</p>
     */
    static final class Quality {
        final double score;
        final String pageType;   // article / list / empty / error
        final String verdict;    // pass / degraded / rejected / not_article / fail
        final List<String> flags;

        Quality(double score, String pageType, String verdict, List<String> flags) {
            this.score = Math.round(score * 100) / 100.0;
            this.pageType = pageType;
            this.verdict = verdict;
            this.flags = flags;
        }
    }

    /**
     * 评估正文质量。
     *
     * <p>打分只用可解释的硬指标：正文长度、标点密度、链接占比、重复段落占比、是否被重定向。
     * 阈值参考 Readability / trafilatura 的常见口径，不追求"懂语义"，只求错了能解释。</p>
     */
    static Quality assess(Document doc, Element main, String text, boolean redirected, int httpStatus) {
        List<String> flags = new ArrayList<>();
        String body = text == null ? "" : text.trim();
        int len = body.length();

        Element root = main != null ? main : (doc == null ? null : doc.body());
        double linkRatio = root == null ? 1.0 : linkRatio(root);

        String pageType;
        if (httpStatus >= 400) {
            pageType = "error";
            flags.add("http_" + httpStatus);
        } else if (len < 100) {
            pageType = "empty";
        } else if (linkRatio > 0.5) {
            pageType = "list";
            flags.add("link_density_" + Math.round(linkRatio * 100) + "%");
        } else {
            pageType = "article";
        }

        double score = 0;
        if (len >= 200) score += 0.30;
        if (len >= 600) score += 0.20;
        if (len >= 1500) score += 0.15;
        if (punctuationPer100(body) >= 1.0) score += 0.15;
        if (linkRatio < 0.2) score += 0.10;

        double dup = duplicateRatio(body);
        if (dup > 0.3) {
            score -= 0.15;
            flags.add("duplicated_" + Math.round(dup * 100) + "%");
        }
        if (redirected) {
            score -= 0.15;
            flags.add("redirected");
        }
        if (len < 100) score -= 0.40;
        score = Math.max(0, Math.min(1, score));

        String verdict;
        if ("error".equals(pageType)) {
            verdict = "fail";
        } else if (!"article".equals(pageType)) {
            verdict = "not_article";
        } else if (score < 0.35) {
            verdict = "rejected";
        } else if (redirected || score < 0.5) {
            verdict = "degraded";
        } else {
            verdict = "pass";
        }
        return new Quality(score, pageType, verdict, flags);
    }

    /** 链接文本占全文比例。 */
    static double linkRatio(Element el) {
        String text = el.text();
        if (text == null || text.trim().isEmpty()) return 1.0;
        int len = text.trim().length();
        int linkLen = 0;
        for (Element a : el.select("a")) {
            String at = a.text();
            linkLen += at == null ? 0 : at.length();
        }
        return Math.min(1.0, (double) linkLen / len);
    }

    /** 每 100 字符的标点数：成文正文通常在 1~8，导航/列表接近 0。 */
    private static double punctuationPer100(String text) {
        if (text.isEmpty()) return 0;
        int punct = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '，' || c == '。' || c == ',' || c == '.' || c == '；' || c == ';'
                    || c == '：' || c == ':' || c == '!' || c == '！' || c == '?' || c == '？') {
                punct++;
            }
        }
        return (double) punct / text.length() * 100;
    }

    /** 重复段落占比：采集站/PC+移动双 DOM 会把同一段复制多遍。 */
    private static double duplicateRatio(String text) {
        String[] paragraphs = text.split("\\n{2,}");
        if (paragraphs.length < 2) return 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        int dup = 0;
        for (String p : paragraphs) {
            String t = p.trim();
            if (t.length() < 20) continue;
            String fp = t.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
            if (fp.length() > 120) fp = fp.substring(0, 120);
            if (!seen.add(fp)) dup++;
        }
        return (double) dup / Math.max(1, paragraphs.length);
    }

    // ==================== query 命中段落 ====================

    /** query 切词：顿号/逗号/空白；英文保留整词，中文按两字片段打分（同 network_search 的口径）。 */
    static List<String> queryTerms(String query) {
        List<String> terms = new ArrayList<>();
        if (query == null) return terms;
        for (String t : query.split("[、,，;；\\s]+")) {
            String term = t.trim();
            if (term.length() >= 2) terms.add(term);
        }
        return terms;
    }

    /** 一句话与 query 的相关度：整词 2 分；中文两字片段每个 1 分、单句最多记 2 分；英文只认整词。 */
    static int queryScore(String sentence, List<String> terms) {
        if (sentence == null || terms.isEmpty()) return 0;
        String lower = sentence.toLowerCase(java.util.Locale.ROOT);
        int hit = 0;
        for (String term : terms) {
            String t = term.toLowerCase(java.util.Locale.ROOT);
            if (!containsCjk(t)) {
                if (containsWord(lower, t)) hit += 2;
                continue;
            }
            if (lower.contains(t)) {
                hit += 2;
                continue;
            }
            int grams = 0;
            for (int i = 0; i + 2 <= t.length() && grams < 2; i++) {
                if (lower.contains(t.substring(i, i + 2))) grams++;
            }
            hit += grams;
        }
        return hit;
    }

    static boolean containsCjk(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) >= 0x4E00) return true;
        }
        return false;
    }

    /** 整词匹配：两侧不能紧邻字母/数字（避免 "art" 命中 "article"）。 */
    static boolean containsWord(String lowerText, String word) {
        int from = 0;
        while (true) {
            int idx = lowerText.indexOf(word, from);
            if (idx < 0) return false;
            int end = idx + word.length();
            boolean leftOk = idx == 0 || !Character.isLetterOrDigit(lowerText.charAt(idx - 1));
            boolean rightOk = end >= lowerText.length() || !Character.isLetterOrDigit(lowerText.charAt(end));
            if (leftOk && rightOk) return true;
            from = idx + 1;
        }
    }

    /**
     * 按 query 从正文里挑命中段落（段落级检索，不是摘要）。
     * 返回 [{index, score, content}]，按得分降序；没有命中返回空列表。
     */
    static List<Map<String, Object>> passages(String text, String query, int max) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return out;
        List<String> terms = queryTerms(query);
        if (terms.isEmpty()) return out;

        String[] paragraphs = text.split("\\n{2,}");
        for (int i = 0; i < paragraphs.length; i++) {
            String p = paragraphs[i].trim();
            if (p.length() < 12) continue;
            int score = queryScore(p, terms);
            if (score < 2) continue;
            Map<String, Object> one = new java.util.HashMap<>();
            one.put("index", i);
            one.put("score", score);
            one.put("content", p.length() > 600 ? p.substring(0, 600) + "…" : p);
            out.add(one);
        }
        out.sort((a, b) -> Integer.compare((Integer) b.get("score"), (Integer) a.get("score")));
        if (out.size() > max) return new ArrayList<>(out.subList(0, max));
        return out;
    }
}
