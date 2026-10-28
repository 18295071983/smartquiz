package com.oilquiz.app.ai.chat.component;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简逐行 diff（2026-10-23，diff 卡用）。
 *
 * <p>为什么自己写而不是引库：只需要"统一格式的 +/- 行"，卡片的输入是模型给的两段文本，
 * 量级很小（代码片段/小文件）。LCS 足够，而且不引入新依赖。
 * 边界：任一侧超过 {@link #MAX_LINES} 行时退化成"整段删除 + 整段新增"，
 * 避免 O(n·m) 把主线程拖住——这张卡在 UI 线程渲染。</p>
 */
public final class SimpleLineDiff {

    /** 单侧最多参与 LCS 的行数；超过就退化为整段替换 */
    static final int MAX_LINES = 400;

    public enum Kind { CONTEXT, ADD, DEL }

    public static final class Line {
        public final Kind kind;
        public final String text;

        Line(Kind kind, String text) {
            this.kind = kind;
            this.text = text;
        }
    }

    private SimpleLineDiff() {
    }

    /** 计算行级差异；输入为 null 视为空文本 */
    public static List<Line> diff(String before, String after) {
        String[] a = splitLines(before);
        String[] b = splitLines(after);
        if (a.length > MAX_LINES || b.length > MAX_LINES) {
            return diffLarge(a, b);
        }
        return lcsLines(a, b);
    }

    /**
     * 带上下文的 hunk 视图（diff 卡用，2026-10-28）。
     *
     * <p>此前卡片把整篇文件铺出来（改一行也要从头看到尾）。这里只保留每处改动前后
     * {@code context} 行，两处改动离得远就折叠成一行「⋯」——这就是 unified diff 的观感，
     * 不需要引入任何依赖：{@link #diff} 本来就在本地算 LCS。</p>
     */
    public static List<Line> hunks(String before, String after, int context) {
        return collapse(diff(before, after), context);
    }

    /** 把完整的行级差异折叠成「改动 ±context 行 + ⋯ 分隔」 */
    static List<Line> collapse(List<Line> lines, int context) {
        if (lines == null || lines.isEmpty()) return new ArrayList<>();
        int ctx = Math.max(0, context);
        List<Integer> changes = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).kind != Kind.CONTEXT) changes.add(i);
        }
        if (changes.isEmpty()) return lines;
        List<Line> out = new ArrayList<>();
        int g = 0;
        while (g < changes.size()) {
            int first = changes.get(g);
            int last = first;
            // 改动之间只隔 2*ctx 行以内就并进同一段，否则中间那段没意义
            while (g + 1 < changes.size() && changes.get(g + 1) - last <= ctx * 2) {
                g++;
                last = changes.get(g);
            }
            int start = Math.max(0, first - ctx);
            int end = Math.min(lines.size(), last + ctx + 1);
            if (!out.isEmpty()) out.add(new Line(Kind.CONTEXT, FOLD_MARK));
            for (int i = start; i < end; i++) out.add(lines.get(i));
            g++;
        }
        return out;
    }

    /** 折叠标记：两处改动之间的省略号（渲染时按上下文行显示） */
    public static final String FOLD_MARK = "⋯";

    private static List<Line> lcsLines(String[] a, String[] b) {
        List<Line> out = new ArrayList<>();
        int n = a.length, m = b.length;
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = a[i].equals(b[j])
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (a[i].equals(b[j])) {
                out.add(new Line(Kind.CONTEXT, a[i]));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                out.add(new Line(Kind.DEL, a[i]));
                i++;
            } else {
                out.add(new Line(Kind.ADD, b[j]));
                j++;
            }
        }
        while (i < n) out.add(new Line(Kind.DEL, a[i++]));
        while (j < m) out.add(new Line(Kind.ADD, b[j++]));
        return out;
    }

    /**
     * 超大文件：先前缀/后缀剥掉没变的行（O(n)，不占内存），中间部分还在上限内就照常算 LCS，
     * 仍然太大才退化成"整段删除 + 整段新增"。
     *
     * <p>这样"4000 行文件改了第 2000 行"卡片上只出现改的那一行，而不是把 8000 行铺满——
     * 原先的超限退化是整篇铺两遍，等同于没有 diff。</p>
     */
    private static List<Line> diffLarge(String[] a, String[] b) {
        int pre = 0;
        while (pre < a.length && pre < b.length && a[pre].equals(b[pre])) pre++;
        int suf = 0;
        while (suf < a.length - pre && suf < b.length - pre
                && a[a.length - 1 - suf].equals(b[b.length - 1 - suf])) suf++;

        List<Line> out = new ArrayList<>();
        for (int i = 0; i < pre; i++) out.add(new Line(Kind.CONTEXT, a[i]));

        int aMid = a.length - pre - suf;
        int bMid = b.length - pre - suf;
        if (aMid <= MAX_LINES && bMid <= MAX_LINES) {
            String[] aSub = new String[aMid];
            String[] bSub = new String[bMid];
            System.arraycopy(a, pre, aSub, 0, aMid);
            System.arraycopy(b, pre, bSub, 0, bMid);
            out.addAll(lcsLines(aSub, bSub));
        } else {
            for (int i = pre; i < a.length - suf; i++) out.add(new Line(Kind.DEL, a[i]));
            for (int i = pre; i < b.length - suf; i++) out.add(new Line(Kind.ADD, b[i]));
        }
        for (int i = a.length - suf; i < a.length; i++) out.add(new Line(Kind.CONTEXT, a[i]));
        return out;
    }

    /** 拆分行：统一换行符，丢掉末尾空行（"a\n" 与 "a" 视为同一内容） */
    static String[] splitLines(String s) {
        if (s == null || s.isEmpty()) return new String[0];
        String normalized = s.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.endsWith("\n")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.split("\n", -1);
    }

    /** 统计新增/删除行数（卡片头用） */
    public static int[] stats(List<Line> lines) {
        int add = 0, del = 0;
        if (lines != null) {
            for (Line l : lines) {
                if (l.kind == Kind.ADD) add++;
                else if (l.kind == Kind.DEL) del++;
            }
        }
        return new int[]{add, del};
    }
}
