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
        List<Line> out = new ArrayList<>();
        if (a.length > MAX_LINES || b.length > MAX_LINES) {
            for (String s : a) out.add(new Line(Kind.DEL, s));
            for (String s : b) out.add(new Line(Kind.ADD, s));
            return out;
        }
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
