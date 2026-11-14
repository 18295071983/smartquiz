package com.oilquiz.app.ai.chat.status;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 插槽行内动作（④-2，2026-11-15）。
 *
 * <p>帧字段 {@code actions:[{id,label,style?}]}，甲板行/全屏列表右侧渲染成胶囊按钮。
 * 点击只回传 {@code {slotId, actionId}} 走既有动作分发，**不打开全屏列表**。</p>
 *
 * <p>约定：</p>
 * <ul>
 *   <li>每次最多 2 个（多出的截断并回 warn）；</li>
 *   <li>{@code id}/{@code label} 必填，空值项丢弃；同 id 只留第一个（避免点了不知道是哪个）；</li>
 *   <li>{@code style} 只有 {@code primary} / {@code plain}，缺省/写错按 plain 渲染。</li>
 * </ul>
 */
public final class SlotAction {

    public static final String STYLE_PRIMARY = "primary";
    public static final String STYLE_PLAIN = "plain";
    /** 行内按钮最多 2 个：再多会把标题挤没，也失去"一眼可点"的意义。 */
    public static final int MAX_ACTIONS = 2;

    public final String id;
    public final String label;
    public final String style;

    public SlotAction(String id, String label, String style) {
        this.id = id == null ? "" : id.trim();
        this.label = label == null ? "" : label.trim();
        String s = style == null ? "" : style.trim().toLowerCase(Locale.ROOT);
        this.style = STYLE_PRIMARY.equals(s) ? STYLE_PRIMARY : STYLE_PLAIN;
    }

    public boolean isValid() {
        return !id.isEmpty() && !label.isEmpty();
    }

    public boolean isPrimary() {
        return STYLE_PRIMARY.equals(style);
    }

    /**
     * 解析并清洗 {@code actions[]}：丢弃坏项、去重、最多 2 个。
     *
     * @param warnings 可空；校验警告追加到这里（不抛异常，插槽是瞬态）
     */
    public static List<SlotAction> parse(JSONArray array, List<String> warnings) {
        if (array == null) return Collections.emptyList();
        List<SlotAction> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < array.length(); i += 1) {
            if (out.size() >= MAX_ACTIONS) {
                addWarning(warnings, "actions 超过 " + MAX_ACTIONS + " 个，只保留前 " + MAX_ACTIONS + " 个");
                break;
            }
            JSONObject o = array.optJSONObject(i);
            if (o == null) {
                addWarning(warnings, "actions[" + i + "] 不是对象，已丢弃");
                continue;
            }
            String id = o.optString("id", "").trim();
            String label = o.optString("label", "").trim();
            if (id.isEmpty() || label.isEmpty()) {
                addWarning(warnings, "actions[" + i + "] 缺 id/label，已丢弃");
                continue;
            }
            if (!ids.add(id)) {
                addWarning(warnings, "actions[" + i + "] id=" + id + " 重复，已丢弃");
                continue;
            }
            String rawStyle = o.optString("style", "").trim();
            String style = rawStyle.toLowerCase(Locale.ROOT);
            if (!style.isEmpty() && !STYLE_PRIMARY.equals(style) && !STYLE_PLAIN.equals(style)) {
                addWarning(warnings, "actions[" + i + "] style=" + rawStyle + " 未知，按 plain");
                style = STYLE_PLAIN;
            }
            out.add(new SlotAction(id, label, style));
        }
        return Collections.unmodifiableList(out);
    }

    /** 单测/诊断用：稳定 JSON 表示（style 总是带上，接收方无需猜默认值）。 */
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("label", label);
            o.put("style", style);
        } catch (Exception ignored) {
            // JSONObject 不接受的字符极少见；插槽是瞬态，坏一条不扩散
        }
        return o;
    }

    public static JSONArray toJsonArray(List<SlotAction> actions) {
        JSONArray out = new JSONArray();
        if (actions == null) return out;
        for (SlotAction a : actions) {
            if (a != null && a.isValid()) out.put(a.toJson());
        }
        return out;
    }

    private static void addWarning(List<String> warnings, String message) {
        if (warnings != null) warnings.add(message);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SlotAction)) return false;
        SlotAction o = (SlotAction) other;
        return id.equals(o.id) && label.equals(o.label) && style.equals(o.style);
    }

    @Override
    public int hashCode() {
        int h = id.hashCode();
        h = 31 * h + label.hashCode();
        h = 31 * h + style.hashCode();
        return h;
    }

    @Override
    public String toString() {
        return "SlotAction{" + id + "," + label + "," + style + "}";
    }
}
