package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.oilquiz.app.R;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 结构化表格卡片组件：表头 + 数据行，列宽均分、边框网格
 * （Track B0-3 已接 {@link CardShell}）。
 *
 * <p>行数超过 {@link #MAX_VISIBLE_ROWS} 时截断并把总数放进状态行——不做纵向内滚，
 * 因为卡片内再放一个纵向 ScrollView 会和聊天列表抢手势，用户划到表格就滚不动会话。
 * 要看全量数据请让 Agent 落成表格文件（file_card 打开）。
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

        shell.addBody(buildRow(context, headers, true, align));
        int visible = Math.min(rowCount, MAX_VISIBLE_ROWS);
        for (int i = 0; i < visible; i++) {
            JSONArray row = rows.optJSONArray(i);
            if (row == null) continue;
            shell.addBody(buildRow(context, row, false, align));
        }
        if (rowCount > visible) {
            shell.setState(CardShell.State.TRUNCATED,
                    context.getString(R.string.card_row_truncated_rows, rowCount, visible));
        } else if (rowCount == 0) {
            shell.setState(CardShell.State.EMPTY);
        }
        return shell;
    }

    /** 构建一行（表头用次级底 + 中等字重） */
    private View buildRow(Context context, JSONArray cells, boolean header, String align) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundColor(header ? ComponentTheme.surfaceVariant(context)
                : ComponentTheme.surface(context));
        int gravity = "center".equals(align) ? Gravity.CENTER : Gravity.START;

        for (int i = 0; i < cells.length(); i++) {
            TextView cell = new TextView(context);
            cell.setText(cells.optString(i, ""));
            cell.setTextSize(ComponentTheme.bodyTextSize());
            cell.setTextColor(ComponentTheme.textPrimary(context));
            if (header) {
                cell.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                        android.graphics.Typeface.NORMAL));
            }
            cell.setGravity(gravity | Gravity.CENTER_VERTICAL);
            cell.setPadding(ComponentTheme.dp(context, 6), ComponentTheme.dp(context, 5),
                    ComponentTheme.dp(context, 6), ComponentTheme.dp(context, 5));
            cell.setBackground(cellBorder(context));
            row.addView(cell, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        return row;
    }

    /** 单元格描边（与外壳 outline token 同源） */
    private static GradientDrawable cellBorder(Context context) {
        GradientDrawable gd = new GradientDrawable();
        gd.setStroke(ComponentTheme.borderWidth(context), ComponentTheme.outline(context));
        return gd;
    }
}
