package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.oilquiz.app.R;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 结构化表格卡片组件：表头 + 数据行，列宽按内容估算、横向可滚、整表一条描边。
 * （Track B0-3 已接 {@link CardShell}）
 *
 * <p>行数超过 {@link #MAX_VISIBLE_ROWS} 时截断并把总数放进状态行——不做纵向内滚，
 * 因为卡片内再放一个纵向 ScrollView 会和聊天列表抢手势，用户划到表格就滚不动会话。
 * 横向滚不受这条限制（轴不同，不与列表的纵向手势冲突），所以宽表用它兜底。</p>
 *
 * <p>2026-10-28 改版（真机反馈"表格散架"）：</p>
 * <ul>
 *   <li><b>列宽不再均分</b>：按该列最长内容的显示宽度估算（CJK 记 2、ASCII 记 1），
 *       夹在 {@link #MIN_COL_WIDTH_DP}~{@link #MAX_COL_WIDTH_DP} 之间。从前 4 列各占 25%，
 *       {@code ONLINE_VISION_QUALITY_LADDER} 这种常量只能被逐字符硬拆成
 *       {@code ONLINE_VIS ION_QUALI TY_LADDER}，一行七拐八弯。</li>
 *   <li><b>整表一条描边 + 行间分隔线</b>：从前每个单元格自己画一圈 stroke，相邻两格之间
 *       是两条线叠出来的，看起来像一堆独立的小盒子；现在只有外框和横向分隔。</li>
 *   <li><b>内容超出可用宽度时横向滚动</b>：宁可左右滑，也不把每列压到 60dp 再硬折行。</li>
 *   <li>正文单元格顶端对齐（多行时不再垂直居中，几行的行高不再错位）。</li>
 * </ul>
 *
 * 数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "title": "数据表",
 *   "headers": ["城市","气温","天气"],
 *   "rows": [["北京","26℃","晴"], ["上海","29℃","多云"]],
 *   "align": "left" | "center"
 * }
 * </pre>
 */
public class TableCardView implements ChatComponent {

    /** 卡片内最多显示的数据行数（表头不计），超出走截断态 */
    private static final int MAX_VISIBLE_ROWS = 24;

    /** 单列最小/最大宽度（dp）：小于最小值中文标签会被拆成两行，大于最大值一列就吃掉整屏 */
    private static final int MIN_COL_WIDTH_DP = 64;
    private static final int MAX_COL_WIDTH_DP = 220;
    /** 估算字宽：13sp 下 ASCII 约 7dp、CJK 约 14dp */
    private static final int ASCII_CHAR_DP = 7;
    private static final int CJK_CHAR_DP = 14;
    private static final int CELL_PAD_H_DP = 8;
    private static final int CELL_PAD_V_DP = 6;

    @Override
    public String getType() {
        return "table_card";
    }

    @Override
    public boolean canRender(ComponentData data) {
        return data != null && data.props != null
                && data.props.optJSONArray("headers") != null
                && data.props.optJSONArray("rows") != null;
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props != null ? data.props : new JSONObject();
        String align = p.optString("align", "left");
        JSONArray headers = p.optJSONArray("headers");
        JSONArray rows = p.optJSONArray("rows");
        int colCount = headers != null ? headers.length() : 0;
        int rowCount = rows != null ? rows.length() : 0;
        if (colCount <= 0) return null;

        CardShell shell = CardShell.create(context)
                .setHeaderIcon(R.drawable.ic_home_database)
                .setHeader(p.optString("title", ""),
                        rowCount > 0 ? context.getString(R.string.card_row_count, rowCount) : null);

        int[] colWidths = measureColumns(context, headers, rows, colCount);
        LinearLayout table = new LinearLayout(context);
        table.setOrientation(LinearLayout.VERTICAL);
        table.setBackground(tableOutline(context));
        table.addView(buildRow(context, headers, true, align, colWidths));

        int visible = Math.min(rowCount, MAX_VISIBLE_ROWS);
        for (int i = 0; i < visible; i++) {
            JSONArray row = rows.optJSONArray(i);
            if (row == null) continue;
            table.addView(rowSeparator(context));
            table.addView(buildRow(context, row, false, align, colWidths));
        }

        // 表比卡片宽时给头部一句"左右滑动"，否则用户只会以为右边两列丢了。
        int totalPx = 0;
        for (int w : colWidths) totalPx += w;
        int availPx = Math.max(ComponentTheme.dp(context, 200),
                context.getResources().getDisplayMetrics().widthPixels
                        - ComponentTheme.dp(context, 72));
        boolean overflow = colCount > 1 && totalPx > availPx;
        if (rowCount > 0) {
            String meta = context.getString(R.string.card_row_count, rowCount);
            if (overflow) {
                meta = meta + " · " + context.getString(R.string.card_table_scroll_hint);
            }
            shell.setHeader(p.optString("title", ""), meta);
        }

        // 横向滚动兜底：宽表（≥4 列或含长标识符）左右滑，不在窄屏里硬折行。
        HorizontalScrollView scroller = new HorizontalScrollView(context);
        scroller.setHorizontalScrollBarEnabled(overflow);
        scroller.addView(table, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        shell.addBody(scroller);

        if (rowCount > visible) {
            shell.setState(CardShell.State.TRUNCATED,
                    context.getString(R.string.card_row_truncated_rows, rowCount, visible));
        } else if (rowCount == 0) {
            shell.setState(CardShell.State.EMPTY);
        }
        return shell;
    }

    /**
     * 按每列最长内容的估算宽度分配列宽（dp）。
     * 表头 + 前 {@link #MAX_VISIBLE_ROWS} 行都参与估算，避免"表头短、内容长"时列被压扁。
     */
    private static int[] measureColumns(Context context, JSONArray headers, JSONArray rows, int colCount) {
        int[] chars = new int[colCount];
        for (int c = 0; c < colCount; c++) {
            chars[c] = displayUnits(headers.optString(c, ""));
        }
        int rowCount = rows != null ? rows.length() : 0;
        int scan = Math.min(rowCount, MAX_VISIBLE_ROWS);
        for (int r = 0; r < scan; r++) {
            JSONArray row = rows.optJSONArray(r);
            if (row == null) continue;
            for (int c = 0; c < colCount; c++) {
                chars[c] = Math.max(chars[c], displayUnits(row.optString(c, "")));
            }
        }
        int[] widths = new int[colCount];
        for (int c = 0; c < colCount; c++) {
            int est = chars[c] * ASCII_CHAR_DP + CELL_PAD_H_DP * 2;
            widths[c] = ComponentTheme.dp(context,
                    Math.max(MIN_COL_WIDTH_DP, Math.min(MAX_COL_WIDTH_DP, est)));
        }
        return widths;
    }

    /** 显示宽度估算：CJK/全角字符记 2，其余记 1（与等宽视觉近似） */
    private static int displayUnits(String text) {
        if (text == null || text.isEmpty()) return 0;
        int units = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            units += isWide(ch) ? 2 : 1;
        }
        return units;
    }

    private static boolean isWide(char ch) {
        return (ch >= 0x1100 && ch <= 0x115F)      // Hangul Jamo
                || (ch >= 0x2E80 && ch <= 0xA4CF)   // CJK 部首/汉字/日文
                || (ch >= 0xAC00 && ch <= 0xD7A3)   // Hangul 音节
                || (ch >= 0xF900 && ch <= 0xFAFF)   // CJK 兼容
                || (ch >= 0xFE30 && ch <= 0xFE4F)   // CJK 兼容标点
                || (ch >= 0xFF00 && ch <= 0xFF60)   // 全角
                || (ch >= 0xFFE0 && ch <= 0xFFE6);
    }

    /** 构建一行（表头用次级底 + 中等字重；列宽由 {@link #measureColumns} 给定） */
    private View buildRow(Context context, JSONArray cells, boolean header, String align, int[] colWidths) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundColor(header ? ComponentTheme.surfaceVariant(context)
                : ComponentTheme.surface(context));
        int horizontal = "center".equals(align) ? Gravity.CENTER : Gravity.START;

        for (int i = 0; i < colWidths.length; i++) {
            TextView cell = new TextView(context);
            cell.setText(cells != null ? cells.optString(i, "") : "");
            cell.setTextSize(ComponentTheme.bodyTextSize());
            cell.setTextColor(ComponentTheme.textPrimary(context));
            if (header) {
                cell.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                        android.graphics.Typeface.NORMAL));
            }
            // 表头单行居中对齐；正文多行时顶端对齐，几行的行高就不会互相错开。
            cell.setGravity(header
                    ? (horizontal | Gravity.CENTER_VERTICAL)
                    : (horizontal | Gravity.TOP));
            cell.setPadding(ComponentTheme.dp(context, CELL_PAD_H_DP),
                    ComponentTheme.dp(context, CELL_PAD_V_DP),
                    ComponentTheme.dp(context, CELL_PAD_H_DP),
                    ComponentTheme.dp(context, CELL_PAD_V_DP));
            row.addView(cell, new LinearLayout.LayoutParams(colWidths[i],
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        return row;
    }

    /** 行间分隔线（只有横向线；竖线由列宽的留白分隔，不再每个格子各画一圈） */
    private static View rowSeparator(Context context) {
        View divider = new View(context);
        divider.setBackgroundColor(ComponentTheme.outline(context));
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, ComponentTheme.borderWidth(context))));
        return divider;
    }

    /** 整表外框（一条描边，替代原先每格一圈导致的"双线"） */
    private static GradientDrawable tableOutline(Context context) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(ComponentTheme.surface(context));
        gd.setStroke(Math.max(1, ComponentTheme.borderWidth(context)), ComponentTheme.outline(context));
        gd.setCornerRadius(ComponentTheme.dp(context, 8));
        return gd;
    }
}
