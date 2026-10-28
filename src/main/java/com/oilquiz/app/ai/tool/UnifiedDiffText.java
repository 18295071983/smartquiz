package com.oilquiz.app.ai.tool;

import java.util.ArrayList;
import java.util.List;

/**
 * 行级 unified diff 文本，给"改文件"类工具把改动打回给模型核对。
 *
 * <p>为什么自带一份 LCS：{@code file_generator(action=edit)} 的结果要脱离 Python 也能说清
 * "到底改了哪几行"。{@code SimpleLineDiff} 在 chat.component 包里、只产 +/- 行不带 hunk 头，
 * 而这里要的是标准 unified 格式（{@code @@ -a,b +c,d @@}），所以单写一份，避免工具层反向依赖 UI 层。
 *
 * <p>边界与 UI 那份一致：任一侧超过 {@link #MAX_LINES} 行就退化成"整段删除 + 整段新增"，
 * 因为 O(n·m) 的 LCS 在大文件上会拖住调用线程。输出截断由调用方负责。
 */
final class UnifiedDiffText {

    /** 单侧参与 LCS 的最大行数 */
    static final int MAX_LINES = 400;

    private static final class Op {
        final char kind; // ' ' 相同 / '-' 删除 / '+' 新增
        final String text;

        Op(char kind, String text) {
            this.kind = kind;
            this.text = text;
        }
    }

    private UnifiedDiffText() {
    }

    /**
     * @param before       改前全文
     * @param after        改后全文
     * @param contextLines hunk 上下文行数（标准是 3）
     * @return unified diff 文本；无差异时返回空串
     */
    static String render(String before, String after, int contextLines) {
        if (before == null) before = "";
        if (after == null) after = "";
        int ctx = Math.max(0, contextLines);
        List<Op> ops = diffOps(splitLines(before), splitLines(after));
        List<Integer> changes = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            if (ops.get(i).kind != ' ') changes.add(i);
        }
        if (changes.isEmpty()) return "";

        // 每个 op 之前所在的行号（1 基），用来算 hunk 头
        int[] oldAt = new int[ops.size() + 1];
        int[] newAt = new int[ops.size() + 1];
        int o = 1;
        int n = 1;
        for (int i = 0; i < ops.size(); i++) {
            oldAt[i] = o;
            newAt[i] = n;
            char k = ops.get(i).kind;
            if (k != '+') o++;
            if (k != '-') n++;
        }
        oldAt[ops.size()] = o;
        newAt[ops.size()] = n;

        StringBuilder sb = new StringBuilder();
        int g = 0;
        while (g < changes.size()) {
            int first = changes.get(g);
            int last = first;
            // 相邻改动之间相隔不超过 2*ctx 行就并进同一个 hunk
            while (g + 1 < changes.size() && changes.get(g + 1) - last <= ctx * 2) {
                g++;
                last = changes.get(g);
            }
            int start = Math.max(0, first - ctx);
            int end = Math.min(ops.size(), last + ctx + 1);
            appendHunk(sb, ops, start, end, oldAt, newAt);
            g++;
        }
        return sb.toString();
    }

    private static void appendHunk(StringBuilder sb, List<Op> ops, int start, int end,
                                   int[] oldAt, int[] newAt) {
        int oldCount = 0;
        int newCount = 0;
        for (int i = start; i < end; i++) {
            char k = ops.get(i).kind;
            if (k != '+') oldCount++;
            if (k != '-') newCount++;
        }
        int oldStart = oldCount == 0 ? Math.max(0, oldAt[start] - 1) : oldAt[start];
        int newStart = newCount == 0 ? Math.max(0, newAt[start] - 1) : newAt[start];
        sb.append("@@ -").append(oldStart).append(',').append(oldCount)
          .append(" +").append(newStart).append(',').append(newCount).append(" @@\n");
        for (int i = start; i < end; i++) {
            Op op = ops.get(i);
            sb.append(op.kind).append(op.text).append('\n');
        }
    }

    private static List<Op> diffOps(String[] a, String[] b) {
        if (a.length > MAX_LINES || b.length > MAX_LINES) {
            return diffOpsLarge(a, b);
        }
        return lcsOps(a, b);
    }

    /**
     * 超大文件：先剥掉公共前后缀（O(n)），中间还在上限内就照常算 LCS，否则才整段替换。
     *
     * <p>不这么做的话，"4000 行文件改一行"会给模型一整篇 -/+（还会被 6000 字截断），
     * 等于没给 diff。</p>
     */
    private static List<Op> diffOpsLarge(String[] a, String[] b) {
        int pre = 0;
        while (pre < a.length && pre < b.length && a[pre].equals(b[pre])) pre++;
        int suf = 0;
        while (suf < a.length - pre && suf < b.length - pre
                && a[a.length - 1 - suf].equals(b[b.length - 1 - suf])) suf++;

        List<Op> out = new ArrayList<>();
        for (int i = 0; i < pre; i++) out.add(new Op(' ', a[i]));

        int aMid = a.length - pre - suf;
        int bMid = b.length - pre - suf;
        if (aMid <= MAX_LINES && bMid <= MAX_LINES) {
            String[] aSub = new String[aMid];
            String[] bSub = new String[bMid];
            System.arraycopy(a, pre, aSub, 0, aMid);
            System.arraycopy(b, pre, bSub, 0, bMid);
            out.addAll(lcsOps(aSub, bSub));
        } else {
            for (int i = pre; i < a.length - suf; i++) out.add(new Op('-', a[i]));
            for (int i = pre; i < b.length - suf; i++) out.add(new Op('+', b[i]));
        }
        for (int i = a.length - suf; i < a.length; i++) out.add(new Op(' ', a[i]));
        return out;
    }

    private static List<Op> lcsOps(String[] a, String[] b) {
        List<Op> out = new ArrayList<>();
        int n = a.length;
        int m = b.length;
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = a[i].equals(b[j])
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a[i].equals(b[j])) {
                out.add(new Op(' ', a[i]));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                out.add(new Op('-', a[i]));
                i++;
            } else {
                out.add(new Op('+', b[j]));
                j++;
            }
        }
        while (i < n) out.add(new Op('-', a[i++]));
        while (j < m) out.add(new Op('+', b[j++]));
        return out;
    }

    /** 统一换行符，丢弃末尾空行（"a\n" 与 "a" 视为同一内容） */
    static String[] splitLines(String s) {
        if (s == null || s.isEmpty()) return new String[0];
        String normalized = s.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.endsWith("\n")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.split("\n", -1);
    }
}
