package com.oilquiz.app.ai.chat.render;

import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.ListBlock;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.ThematicBreak;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 流式正文的块级切分（轮内流式 P7，2026-10-25）。
 *
 * <p>直接拿 commonmark 0.13.0 的 <b>AST 顶层节点</b>当块边界，不再手写 CommonMark 行扫描。
 * 0.13.0 没有 {@code Node.getSourceSpans()}（source span 是 0.16.1 才加的），但这不影响切块：
 * 顶层节点本身就是解析器给出的、与整篇语义一致的块序列；每个节点用
 * {@link MarkdownHtmlConverter#renderBlock(Node)} 单独渲染，PoC 已确认“逐顶层节点 HTML 拼接
 * == 整篇 HTML”（列表松紧、表格、围栏、引用、HTML、setext、任务列表、引用式链接都一致）。</p>
 *
 * <p><b>闭合口径</b>：除最后一个顶层节点外，前面的节点都已由“后面还存在节点”这件事证明闭合；
 * 最后一个节点默认是开放尾巴。只有拿到明确的“追加不可能再改它”的证据才把尾巴也闭合：</p>
 * <ul>
 *   <li>围栏代码：原始串最后一个非空行是对应字符/长度的闭合围栏，且该行带换行，
 *       并且它前面确实有一组同字符/同长度的、尚未闭合的开栏；</li>
 *   <li>标题 / 分隔线：最后一个非空行带换行；</li>
 *   <li>段落 / 引用 / 表格：最后一个非空行之后存在<b>带换行</b>的空行（列表、缩进代码不算——
 *       空行后还可能再来一项/一段代码）。</li>
 * </ul>
 *
 * <p>围栏的“前面确实有开栏”是必须的：内容只有一行 {@code ```\n} 时，这一行既像开栏也像闭栏，
 * 但它其实是未闭合的空代码块，下一 token 追加代码仍属于它，不能在这里切。</p>
 *
 * <p><b>尾巴的 HTML</b>用 {@link MarkdownHtmlConverter#parseForBlocks(String)} 解析：它和定稿
 * {@link MarkdownHtmlConverter#toBodyHtml(String)} 一样会对未闭合围栏补一个闭合，保证流式尾巴与
 * 定稿的 HTML 一致（否则 commonmark 原生到 EOF 会少一个换行，定稿时跳一下）。是否真的闭合仍看
 * <b>原始串</b>，不看补出来的闭合围栏。</p>
 *
 * <p><b>引用式链接</b>不再整条回退：commonmark 解析时已经把它们解析成 Link 节点，逐节点渲染仍然
 * 正确；如果后面新增的定义改变了前面已闭合块的 HTML，块 key/前缀哈希会变，{@link #diff} 返回
 * reset，前端整体重建一次即可。显示公式 {@code $$…$$}/{@code \[…\]} 跨块仍无法按块渲染，检测到
 * 某一块里公式不成对时整条回退，交给原来的整篇 swap。</p>
 *
 * <p>本类是纯函数：无 Android 依赖、无共享可变状态，可并发调用，单测在纯 JVM 跑。</p>
 */
public final class MarkdownStreamSplitter {

    /** 顶层块类型；只用于诊断、单测与“公式护栏跳过代码/HTML 块”。 */
    public enum Kind {
        PARAGRAPH,
        HEADING,
        THEMATIC_BREAK,
        FENCED_CODE,
        LIST,
        BLOCKQUOTE,
        HTML_BLOCK,
        INDENTED_CODE,
        TABLE,
        OTHER
    }

    /** 不能走块级流式的原因；{@link #NONE} 表示可以走。 */
    public enum Fallback {
        NONE,
        FULL_HTML_DOCUMENT,
        UNBALANCED_MATH,
        PARSE_ERROR
    }

    /** 一个块：只带渲染后的 HTML，不再需要源码切片。 */
    public static final class Block {
        public final String html;
        public final Kind kind;
        public final boolean closed;
        /** 逐节点渲染出的 HTML 的 SHA-256；HTML 不变则 key 不变，用于 diff。 */
        public final String key;

        private Block(String html, Kind kind, boolean closed) {
            this.html = html == null ? "" : html;
            this.kind = kind;
            this.closed = closed;
            this.key = sha256(this.html);
        }

        @Override
        public String toString() {
            String body = html.length() > 64 ? html.substring(0, 64) + "…" : html;
            return kind + (closed ? "[closed]" : "[tail]")
                    + "{" + body.replace("\n", "\\n") + "}";
        }
    }

    /** 一次切分：已闭合块 + 开放尾巴；没有可显示内容时 tail 为 null。 */
    public static final class Split {
        public final List<Block> closed;
        public final Block tail;
        /**
         * 已闭合块 key 序列的 SHA-256。key 只由块 HTML 派生，所以本值与逐块 key 同源，
         * 不含任何原文切片；任何一块 HTML 变化都会同时改 key 和 prefixHash。
         * JS 侧只认它判“前缀没变，只换尾巴”。
         */
        public final String prefixHash;
        /** false 时不要走块级路径，调用方继续整篇渲染。 */
        public final boolean streamable;
        public final Fallback fallback;

        private Split(List<Block> closed, Block tail, String prefixHash,
                      boolean streamable, Fallback fallback) {
            this.closed = closed;
            this.tail = tail;
            this.prefixHash = prefixHash;
            this.streamable = streamable;
            this.fallback = fallback;
        }

        @Override
        public String toString() {
            return "Split{closed=" + closed + ", tail=" + tail
                    + ", streamable=" + streamable + ", fallback=" + fallback + '}';
        }
    }

    /**
     * 两次切分之间的增量计划。
     *
     * <p>{@code reset=false} 时，{@code closed[0, fromIndex)} 与上一次完全相同，
     * 调用方只需渲染/追加 {@code closed[fromIndex, size)} 并替换尾巴；{@code reset=true}
     * 时页面上的块要整体重建（首帧、主题重载、内容被替换、后缀定义改了前缀、回退切换）。</p>
     */
    public static final class Update {
        public final boolean reset;
        public final int fromIndex;
        public final List<Block> closed;
        public final Block tail;
        public final String prefixHash;

        private Update(boolean reset, int fromIndex, List<Block> closed,
                       Block tail, String prefixHash) {
            this.reset = reset;
            this.fromIndex = fromIndex;
            this.closed = closed;
            this.tail = tail;
            this.prefixHash = prefixHash;
        }

        @Override
        public String toString() {
            return "Update{reset=" + reset + ", fromIndex=" + fromIndex
                    + ", closed=" + closed.size() + ", tail=" + tail + '}';
        }
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private MarkdownStreamSplitter() {
    }

    /**
     * 切分当前累积 markdown。null 按空串处理；返回值永不为 null。
     *
     * <p>解析用的是与定稿相同的 commonmark 扩展（GFM 表格/删除线）和“未闭合围栏补闭合”口径；
     * 是否闭合的判断只看原始串。</p>
     */
    public static Split split(String markdown) {
        final String src = markdown == null ? "" : markdown;
        if (MarkdownHtmlConverter.isFullHtmlDocument(src)) {
            return fallback(src, Fallback.FULL_HTML_DOCUMENT);
        }
        try {
            Node document = MarkdownHtmlConverter.parseForBlocks(src);
            List<Node> nodes = topLevel(document);
            List<Block> closed = new ArrayList<>();
            Block tail = null;
            for (int i = 0; i < nodes.size(); i++) {
                Node node = nodes.get(i);
                boolean last = i == nodes.size() - 1;
                boolean closedNode = !last || isLastNodeClosed(node, src);
                Block block = new Block(MarkdownHtmlConverter.renderBlock(node),
                        kindOf(node), closedNode);
                if (block.html.isEmpty()) {
                    // 链接引用定义等不产出可见 HTML 的节点：不占块节点，但解析器已经用它们
                    // 解析了全文的引用链接；后续定义变化会改前面块 HTML，由 diff reset 兜底。
                    continue;
                }
                if (closedNode) {
                    closed.add(block);
                } else {
                    tail = block;
                }
            }
            List<Block> ro = Collections.unmodifiableList(closed);
            if (hasUnbalancedMath(ro, tail)) {
                return fallback(src, Fallback.UNBALANCED_MATH);
            }
            return new Split(ro, tail, prefixHash(ro), true, Fallback.NONE);
        } catch (Throwable t) {
            return fallback(src, Fallback.PARSE_ERROR);
        }
    }

    /**
     * 比较前后两次切分，得出“只追加几个块 + 换尾巴”还是“整体重建”。
     *
     * <p>前一次已闭合块不是新一次前缀（内容被替换、后缀引用定义改了前缀 HTML）时 reset；
     * 追加式增长永远不 reset，只换尾巴/追加新块。</p>
     */
    public static Update diff(Split previous, Split next) {
        if (previous == null || next == null
                || !previous.streamable || !next.streamable) {
            return reset(next);
        }
        if (previous.closed.size() > next.closed.size()) {
            return reset(next);
        }
        for (int i = 0; i < previous.closed.size(); i++) {
            if (!previous.closed.get(i).key.equals(next.closed.get(i).key)) {
                return reset(next);
            }
        }
        return new Update(false, previous.closed.size(), next.closed,
                next.tail, next.prefixHash);
    }

    private static Update reset(Split next) {
        if (next == null) {
            return new Update(true, 0, Collections.<Block>emptyList(), null, "");
        }
        return new Update(true, 0, next.closed, next.tail, next.prefixHash);
    }

    private static Split fallback(String src, Fallback why) {
        Block tail = null;
        if (!src.isEmpty()) {
            String html = "";
            try {
                html = MarkdownHtmlConverter.toBodyHtml(src);
            } catch (Throwable ignore) {
                // 整篇渲染也失败时只保留“不可流式”信号，调用方会回退到自己的整篇路径
            }
            if (!html.isEmpty()) {
                tail = new Block(html, Kind.OTHER, false);
            }
        }
        return new Split(Collections.<Block>emptyList(), tail, prefixHash(
                Collections.<Block>emptyList()), false, why);
    }

    // ==== 最后一个节点是否已闭合 ====

    private static boolean isLastNodeClosed(Node node, String raw) {
        if (node instanceof FencedCodeBlock) {
            FencedCodeBlock fence = (FencedCodeBlock) node;
            Line last = lastNonBlankLine(raw);
            if (last == null || !last.terminated) return false;
            if (!fenceClose(last.text, fence.getFenceChar(), fence.getFenceLength())) {
                return false;
            }
            // 最后一行像闭栏，还必须在它前面存在同字符/同长度的未闭合开栏；
            // 否则它其实是 ` ```\n ` 这种“只有开栏”的空代码块。
            return isInsideMatchingFenceBefore(raw, last.start,
                    fence.getFenceChar(), fence.getFenceLength());
        }
        if (node instanceof Heading || node instanceof ThematicBreak) {
            Line last = lastNonBlankLine(raw);
            return last != null && last.terminated;
        }
        if (node instanceof Paragraph || node instanceof BlockQuote
                || node instanceof TableBlock) {
            return hasTerminatedBlankAfterLastNonBlank(raw);
        }
        // 列表：空行后还可能再来一项；缩进代码：空行是代码内容；HTML 块：1-5 型可能
        // 在未闭合的 <pre> 里含空行；定义节点无可见 HTML。这些都继续当尾巴。
        return false;
    }

    /**
     * 在 {@code beforeOffset} 之前按“开栏/闭栏”状态机走一遍，判断当前是否处在一个
     * 与目标字符/长度匹配的未闭合围栏里。用来区分“最后一行是闭栏”与“最后一行其实是开栏”。
     */
    private static boolean isInsideMatchingFenceBefore(String raw, int beforeOffset,
                                                       char ch, int len) {
        char openCh = 0;
        int openLen = 0;
        int p = 0;
        while (p < beforeOffset) {
            Line line = lineAt(raw, p);
            if (openCh == 0) {
                Fence opened = fenceOpen(line.text);
                if (opened != null) {
                    openCh = opened.ch;
                    openLen = opened.len;
                }
            } else if (fenceClose(line.text, openCh, openLen)) {
                openCh = 0;
            }
            p = line.end;
        }
        return openCh == ch && openLen == len;
    }

    /** 最后一个非空行之后是否存在“带换行”的空行；末尾只有空格且无换行不算。 */
    private static boolean hasTerminatedBlankAfterLastNonBlank(String raw) {
        Line last = lastNonBlankLine(raw);
        if (last == null) return false;
        int p = last.end;
        while (p < raw.length()) {
            Line line = lineAt(raw, p);
            if (!line.blank) return false;
            if (line.terminated) return true;
            p = line.end;
        }
        return false;
    }

    private static Line lastNonBlankLine(String raw) {
        Line result = null;
        int p = 0;
        while (p < raw.length()) {
            Line line = lineAt(raw, p);
            if (!line.blank) result = line;
            p = line.end;
        }
        return result;
    }

    // ==== 类型 / 公式护栏 ====

    private static Kind kindOf(Node node) {
        if (node instanceof Paragraph) return Kind.PARAGRAPH;
        if (node instanceof Heading) return Kind.HEADING;
        if (node instanceof ThematicBreak) return Kind.THEMATIC_BREAK;
        if (node instanceof FencedCodeBlock) return Kind.FENCED_CODE;
        if (node instanceof IndentedCodeBlock) return Kind.INDENTED_CODE;
        if (node instanceof BlockQuote) return Kind.BLOCKQUOTE;
        if (node instanceof HtmlBlock) return Kind.HTML_BLOCK;
        if (node instanceof ListBlock) return Kind.LIST;
        if (node instanceof TableBlock) return Kind.TABLE;
        return Kind.OTHER;
    }

    /**
     * 显示公式跨块无法按块渲染：某一块 HTML 里 {@code $$} 为奇数，或 {@code \[}/{@code \]} 数量不等，
     * 说明公式被切开了（或者还没闭合），整条回退。代码/HTML 块里的同样字符跳过。
     */
    private static boolean hasUnbalancedMath(List<Block> closed, Block tail) {
        for (Block block : closed) {
            if (unbalancedMath(block)) return true;
        }
        return tail != null && unbalancedMath(tail);
    }

    private static boolean unbalancedMath(Block block) {
        if (block.kind == Kind.FENCED_CODE
                || block.kind == Kind.INDENTED_CODE
                || block.kind == Kind.HTML_BLOCK) {
            return false;
        }
        int dollars = count(block.html, "$$");
        if ((dollars & 1) == 1) return true;
        return count(block.html, "\\[") != count(block.html, "\\]");
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0;
             i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    // ==== 行 / 围栏 ====

    private static final class Line {
        final int start;
        final int end;
        final String text;
        final boolean blank;
        /** 行尾是否有 '\n'。 */
        final boolean terminated;

        Line(int start, int end, String text, boolean blank, boolean terminated) {
            this.start = start;
            this.end = end;
            this.text = text;
            this.blank = blank;
            this.terminated = terminated;
        }
    }

    private static Line lineAt(String s, int start) {
        int nl = s.indexOf('\n', start);
        int end = nl < 0 ? s.length() : nl + 1;
        int textEnd = nl < 0 ? s.length() : nl;
        if (textEnd > start && s.charAt(textEnd - 1) == '\r') textEnd--;
        String text = s.substring(start, textEnd);
        return new Line(start, end, text, text.trim().isEmpty(), nl >= 0);
    }

    private static final class Fence {
        final char ch;
        final int len;

        Fence(char ch, int len) {
            this.ch = ch;
            this.len = len;
        }
    }

    /** 顶层围栏：最多 3 个前导空格；反引号围栏的 info string 不能再含反引号。 */
    private static Fence fenceOpen(String text) {
        int i = 0;
        while (i < text.length() && i < 3 && text.charAt(i) == ' ') i++;
        if (i >= text.length()) return null;
        char c = text.charAt(i);
        if (c != '`' && c != '~') return null;
        int j = i;
        while (j < text.length() && text.charAt(j) == c) j++;
        int len = j - i;
        if (len < 3) return null;
        if (c == '`' && text.indexOf('`', j) >= 0) return null;
        return new Fence(c, len);
    }

    private static boolean fenceClose(String text, char ch, int len) {
        int i = 0;
        while (i < text.length() && i < 3 && text.charAt(i) == ' ') i++;
        int j = i;
        while (j < text.length() && text.charAt(j) == ch) j++;
        if (j - i < len) return false;
        for (int k = j; k < text.length(); k++) {
            char c = text.charAt(k);
            if (c != ' ' && c != '\t') return false;
        }
        return true;
    }

    // ==== 顶层节点 / 哈希 ====

    private static List<Node> topLevel(Node document) {
        List<Node> nodes = new ArrayList<>();
        if (document == null) return nodes;
        for (Node n = document.getFirstChild(); n != null; n = n.getNext()) {
            nodes.add(n);
        }
        return nodes;
    }

    private static String prefixHash(List<Block> closed) {
        StringBuilder sb = new StringBuilder();
        for (Block block : closed) {
            // block.key 本身只由块 HTML 派生；这里再哈希 key 序列，保证 prefixHash
            // 与逐块 key 同源。任何一块 HTML 变化都会同时改 key 和 prefixHash。
            sb.append(block.key).append('\n');
        }
        return sha256(sb.toString());
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                int v = b & 0xff;
                sb.append(HEX[v >>> 4]).append(HEX[v & 0x0f]);
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
