package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.oilquiz.app.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日程 / 时间轴卡（2026-10-23）。
 *
 * <p>补的是"按天 + 时间刻度"这个形态：{@code reminder} / {@code task} / {@code time_date}
 * 这类结果此前只能塞进 list_card（没有日期分组）或 steps_card（没有时间列）。
 * 与 steps 的区别：steps 表达"流程第几步"，本卡表达"某天几点做什么"，
 * 状态点复用同一套语义色（done/current/todo/failed）。</p>
 *
 * <p>数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "title": "本周日程（可选）",
 *   "items": [
 *     {"date":"2026-10-23", "time":"09:30（可选，缺省=全天）",
 *      "title":"标题", "desc":"补充说明（可选）",
 *      "status":"done|current|todo|failed（可选，默认 todo）"}
 *   ]
 * }
 * </pre>
 * 渲染时按 {@code date} 首次出现顺序分组；同一天内按给定顺序排列（调用方负责排序）。
 * 自动生产者（2026-10-24）：reminder 的 set/list 把提醒条目放进
 * {@code additionalInfo.agenda_items}，由 {@code ToolResultCardBinder} 映射成本卡；
 * 模型也可通过 {@code ui_component(create, agenda)} 手动建卡。
 */
public class AgendaCardView implements ChatComponent {

    private static final int MAX_ITEMS = 100;

    @Override
    public String getType() {
        return "agenda";
    }

    @Override
    public boolean canRender(ComponentData data) {
        if (data == null || data.props == null) return false;
        // 有 items 就归本卡处理：条目字段不全时渲染空态/跳过该行，而不是掉进原始 JSON 兜底
        JSONArray items = data.props.optJSONArray("items");
        return items != null && items.length() > 0;
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject props = data.props != null ? data.props : new JSONObject();
        List<Item> items = parseItems(props.optJSONArray("items"));
        String title = props.optString("title", "");

        CardShell shell = CardShell.create(context)
                .setHeaderIcon(R.drawable.ic_schedule)
                .setHeader(TextUtils.isEmpty(title)
                                ? context.getString(R.string.card_agenda_title) : title,
                        items.isEmpty() ? null
                                : context.getString(R.string.card_shell_item_count, items.size()));

        if (items.isEmpty()) {
            shell.setState(CardShell.State.EMPTY, context.getString(R.string.card_agenda_empty));
            return shell;
        }

        List<Group> groups = groupByDate(items);
        int shown = 0;
        for (Group g : groups) {
            if (!TextUtils.isEmpty(g.date)) {
                TextView dateTv = new TextView(context);
                dateTv.setText(g.date);
                dateTv.setTextSize(ComponentTheme.metaTextSize());
                dateTv.setTextColor(ComponentTheme.textSecondary(context));
                dateTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                dateTv.setPadding(0, shown == 0 ? 0 : ComponentTheme.dp(context, 10),
                        0, ComponentTheme.dp(context, 4));
                shell.addBody(dateTv);
            }
            for (Item it : g.items) {
                if (shown >= MAX_ITEMS) break;
                shell.addBody(buildRow(context, it));
                shown++;
            }
        }
        if (items.size() > shown) {
            shell.setState(CardShell.State.TRUNCATED,
                    context.getString(R.string.file_list_truncated, items.size(), shown));
        }
        return shell;
    }

    /** 一行：时间列 + 状态点 + 标题/说明 */
    private static View buildRow(Context context, Item item) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setPadding(0, ComponentTheme.dp(context, 4), 0, ComponentTheme.dp(context, 4));

        TextView timeTv = new TextView(context);
        timeTv.setText(TextUtils.isEmpty(item.time)
                ? context.getString(R.string.card_agenda_all_day) : item.time);
        timeTv.setTextSize(ComponentTheme.metaTextSize());
        timeTv.setTextColor(ComponentTheme.textTertiary(context));
        timeTv.setMinWidth(ComponentTheme.dp(context, 52));
        row.addView(timeTv);

        View dot = new View(context);
        int dotSize = ComponentTheme.dp(context, 8);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dotSize, dotSize);
        dotLp.topMargin = ComponentTheme.dp(context, 5);
        dotLp.setMarginEnd(ComponentTheme.dp(context, 8));
        dot.setLayoutParams(dotLp);
        android.graphics.drawable.GradientDrawable circle =
                new android.graphics.drawable.GradientDrawable();
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circle.setColor(statusColor(context, item.status));
        dot.setBackground(circle);
        row.addView(dot);

        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView titleTv = new TextView(context);
        titleTv.setText(TextUtils.isEmpty(item.title) ? "（未命名）" : item.title);
        titleTv.setTextSize(ComponentTheme.bodyTextSize());
        titleTv.setTextColor(ComponentTheme.textPrimary(context));
        textCol.addView(titleTv);
        if (!TextUtils.isEmpty(item.desc)) {
            TextView descTv = new TextView(context);
            descTv.setText(item.desc);
            descTv.setTextSize(ComponentTheme.metaTextSize());
            descTv.setTextColor(ComponentTheme.textSecondary(context));
            textCol.addView(descTv);
        }
        row.addView(textCol);
        return row;
    }

    private static int statusColor(Context context, String status) {
        if ("done".equals(status)) return ComponentTheme.success(context);
        if ("current".equals(status)) return ComponentTheme.brand(context);
        if ("failed".equals(status)) return ComponentTheme.danger(context);
        return ComponentTheme.textTertiary(context);
    }

    // ---------------------------------------------------------------- 数据（纯函数，可单测）

    /** 解析条目：只收有标题的行，最多 100 条 */
    static List<Item> parseItems(JSONArray arr) {
        List<Item> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length() && out.size() < MAX_ITEMS; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Item it = new Item();
            it.date = o.optString("date", "");
            it.time = o.optString("time", "");
            it.title = o.optString("title", o.optString("name", ""));
            it.desc = o.optString("desc", o.optString("description", ""));
            it.status = o.optString("status", "todo");
            if (it.title.isEmpty() && it.desc.isEmpty()) continue;
            out.add(it);
        }
        return out;
    }

    /** 按 date 首次出现顺序分组（空日期也成一组，渲染时不出标题） */
    static List<Group> groupByDate(List<Item> items) {
        Map<String, Group> map = new LinkedHashMap<>();
        if (items != null) {
            for (Item it : items) {
                String key = it.date == null ? "" : it.date;
                Group g = map.get(key);
                if (g == null) {
                    g = new Group();
                    g.date = key;
                    map.put(key, g);
                }
                g.items.add(it);
            }
        }
        return new ArrayList<>(map.values());
    }

    static final class Item {
        String date;
        String time;
        String title;
        String desc;
        String status;
    }

    static final class Group {
        String date;
        final List<Item> items = new ArrayList<>();
    }
}
