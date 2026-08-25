package com.oilquiz.app.ai.chat.component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 结构拆分器：把聊天内容中的结构化片段（代码块/表格）拆出来，
 * 交给内置 UI 组件（code_card/table_card）渲染，其余文本段保持 Markdown 渲染。
 *
 * 利用内置组件：代码块 → 深色代码卡（语言标签+复制）；表格 → 结构化表格卡。
 * 仅提取"完整"结构（成对 fence 的代码块、含分隔行的表格），
 * 不完整部分（流式中未闭合）留在文本段按 Markdown 渲染，避免半截组件。
 */
public class MarkdownStructureSplitter {

    /** 代码块开始标记：行首 ``` 可带语言名 */
    private static final Pattern FENCE_OPEN = Pattern.compile("(?m)^\\s*```([A-Za-z0-9_+\\-.]*)\\s*$");

    /** 表格分隔行（|---|、|:---:| 等，仅由 - : 空格 | 组成且含 -） */
    private static boolean isTableSeparatorLine(String line) {
        if (line == null || !line.startsWith("|")) return false;
        String body = line.substring(1, line.endsWith("|") ? line.length() - 1 : line.length()).trim();
        if (body.isEmpty()) return false;
        boolean hasDash = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '-') {
                hasDash = true;
            } else if (c != ':' && c != ' ' && c != '|') {
                return false;
            }
        }
        return hasDash;
    }

    /** 是否为表格行（| 开头且 | 结尾） */
    private static boolean isTableLine(String line) {
        if (line == null) return false;
        String t = line.trim();
        return t.startsWith("|") && t.endsWith("|");
    }

    /** 拆分行到单元格（去掉首尾 | 和列内空格） */
    private static String[] parseCells(String line) {
        String t = line.trim();
        if (t.startsWith("|")) t = t.substring(1);
        if (t.endsWith("|")) t = t.substring(0, t.length() - 1);
        String[] parts = t.split("\\|");
        String[] cells = new String[parts.length];
        for (int i = 0; i < parts.length; i++) {
            cells[i] = parts[i].trim();
        }
        return cells;
    }

    /** 内容是否包含可结构化的片段（代码块或表格） */
    public static boolean containsStructure(String content) {
        if (content == null || content.isEmpty()) return false;
        if (FENCE_OPEN.matcher(content).find()) return true;
        String[] lines = content.split("\n", -1);
        for (int i = 0; i + 1 < lines.length; i++) {
            if (isTableLine(lines[i]) && isTableSeparatorLine(lines[i + 1])) return true;
        }
        return false;
    }

    /**
     * 拆分内容为段序列：文本段 / 代码段（code_card）/ 表格段（table_card）。
     *
     * 返回的 Segment：isComponent=true 时 component 为可直接渲染的 ComponentData。
     */
    public static List<Segment> split(String content) {
        List<Segment> segs = new ArrayList<>();
        if (content == null || content.isEmpty()) return segs;

        int pos = 0;
        int len = content.length();
        StringBuilder textBuf = new StringBuilder();

        // 逐行扫描
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineStart = lineStartOffset(content, i);

            // 1) 代码块：行首 ``` 开 → 找闭合
            Matcher fm = FENCE_OPEN.matcher(line);
            if (fm.find()) {
                String lang = fm.group(1).trim();
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
                    // 代码块完整：之前文本入段，代码入 code_card
                    flushText(textBuf, segs);
                    org.json.JSONObject props = new org.json.JSONObject();
                    try {
                        props.put("language", lang);
                        // 只去尾部空白（保留代码缩进，避免 trim 误删首行缩进）
                        props.put("code", code.toString().replaceAll("\\s+$", ""));
                    } catch (Exception ignored) {
                    }
                    segs.add(Segment.component(new ComponentData("code_card", props)));
                    i = j; // 跳过代码块内容（含闭合行）
                    continue;
                }
                // 未闭合：按文本处理（不提取），继续
            }

            // 2) 表格块：表格行 + 分隔行 + 至少一行数据行（流式中数据行未到时不转组件，
            //    表头/分隔行先按文本渲染，避免"空表格卡"闪现）
            if (isTableLine(lines[i]) && i + 1 < lines.length && isTableSeparatorLine(lines[i + 1])) {
                String[] headers = parseCells(lines[i]);
                List<String[]> rows = new ArrayList<>();
                int j = i + 2;
                while (j < lines.length && isTableLine(lines[j])) {
                    rows.add(parseCells(lines[j]));
                    j++;
                }
                if (rows.isEmpty()) {
                    // 尚无数据行：不提取为表格组件，按文本继续（等数据行到达后下次成型）
                    textBuf.append(lines[i]);
                    if (i + 1 < lines.length) textBuf.append('\n');
                    i++; // 表头行已处理
                    continue;
                }
                flushText(textBuf, segs);
                org.json.JSONObject props = new org.json.JSONObject();
                try {
                    org.json.JSONArray hArr = new org.json.JSONArray();
                    for (String h : headers) hArr.put(h);
                    props.put("headers", hArr);
                    org.json.JSONArray rArr = new org.json.JSONArray();
                    for (String[] row : rows) {
                        org.json.JSONArray r = new org.json.JSONArray();
                        for (String c : row) r.put(c);
                        rArr.put(r);
                    }
                    props.put("rows", rArr);
                } catch (Exception ignored) {
                }
                segs.add(Segment.component(new ComponentData("table_card", props)));
                i = j - 1;
                continue;
            }

            // 普通行：加入文本缓冲
            textBuf.append(line);
            if (i < lines.length - 1) textBuf.append('\n');
        }

        flushText(textBuf, segs);
        if (segs.isEmpty()) {
            segs.add(Segment.text(content));
        }
        return segs;
    }

    /** 计算第 index 行的起始偏移（用于对齐，暂未用） */
    private static int lineStartOffset(String content, int index) {
        int off = 0;
        int cur = 0;
        while (cur < index) {
            int nl = content.indexOf('\n', off);
            if (nl < 0) break;
            off = nl + 1;
            cur++;
        }
        return off;
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
