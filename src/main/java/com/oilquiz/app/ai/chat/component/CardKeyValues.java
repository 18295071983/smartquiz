package com.oilquiz.app.ai.chat.component;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * key_value / compare 两张卡共用的 props 解析（2026-11-15，纯 JVM、无 Android View）。
 *
 * <p>key_value 的 {@code items} 按产品口径收三种形态：</p>
 * <pre>
 * [["名称","值"], …]                 // 二元数组
 * [{"label":"名称","value":"值","tone":"good"}, …]
 * {"名称":"值", …}                    // 对象映射
 * </pre>
 *
 * <p>compare 每项的 {@code rows} 复用同一套解析。解析只做"形状归一"：
 * 非对象/空条目跳过、数字/布尔转文本、tone 只保留字符串；上限与溢出数量交给卡片决定
 * （key_value 最多 24 项、compare 最多 4 项，都是产品口径）。</p>
 */
final class CardKeyValues {

    /** key_value 最多展示 24 项，超出显示「还有 N 项」。 */
    static final int MAX_ITEMS = 24;

    private CardKeyValues() {
    }

    /** 一条键值。 */
    static final class Item {
        final String label;
        final String value;
        final String tone;

        Item(String label, String value, String tone) {
            this.label = label;
            this.value = value;
            this.tone = tone;
        }
    }

    /** 解析结果：前 max 条 + 有效总条数（用于「还有 N 项」）。 */
    static final class Parsed {
        final List<Item> items;
        final int total;

        Parsed(List<Item> items, int total) {
            this.items = items;
            this.total = total;
        }

        int overflow() {
            return Math.max(0, total - items.size());
        }
    }

    static Parsed parse(JSONObject props) {
        return parse(props != null ? props.opt("items") : null, MAX_ITEMS);
    }

    static Parsed parse(Object raw, int maxItems) {
        List<Item> items = new ArrayList<>();
        int total = 0;
        int limit = Math.max(0, maxItems);
        if (raw instanceof JSONArray) {
            JSONArray array = (JSONArray) raw;
            for (int i = 0; i < array.length(); i++) {
                Item item = fromArrayEntry(array.opt(i));
                if (item == null) continue;
                total++;
                if (items.size() < limit) items.add(item);
            }
        } else if (raw instanceof JSONObject) {
            JSONObject object = (JSONObject) raw;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = text(object.opt(key));
                if (key == null || key.trim().isEmpty()) {
                    if (value.isEmpty()) continue;
                }
                total++;
                if (items.size() < limit) {
                    items.add(new Item(key == null ? "" : key, value, ""));
                }
            }
        }
        return new Parsed(items, total);
    }

    private static Item fromArrayEntry(Object entry) {
        if (entry instanceof JSONObject) {
            JSONObject object = (JSONObject) entry;
            String label = firstNonEmpty(object, "label", "key", "name", "title");
            String value = firstNonEmpty(object, "value", "val", "text", "content");
            if (label.isEmpty() && value.isEmpty()) return null;
            return new Item(label, value, firstNonEmpty(object, "tone", "color", "level"));
        }
        if (entry instanceof JSONArray) {
            JSONArray pair = (JSONArray) entry;
            if (pair.length() < 2) return null;
            String label = text(pair.opt(0));
            String value = text(pair.opt(1));
            if (label.isEmpty() && value.isEmpty()) return null;
            String tone = pair.length() > 2 ? text(pair.opt(2)) : "";
            return new Item(label, value, tone);
        }
        return null;
    }

    private static String firstNonEmpty(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = text(object.opt(key));
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private static String text(Object value) {
        if (value == null || value == JSONObject.NULL) return "";
        if (value instanceof String) return ((String) value).trim();
        return String.valueOf(value).trim();
    }

    /** 点击复制用的整段文本：每行「名称：值」。 */
    static String toCopyText(List<Item> items) {
        StringBuilder sb = new StringBuilder();
        if (items == null) return "";
        for (Item item : items) {
            if (item == null) continue;
            if (sb.length() > 0) sb.append('\n');
            if (!item.label.isEmpty()) sb.append(item.label).append('：');
            sb.append(item.value);
        }
        return sb.toString();
    }

    /**
     * 值是否是"数字样"（数字、千分位、小数、百分比、简单单位），用于右侧等宽字体。
     * 纯数字/带单位的数值走 mono，普通文案保持正文字体。
     */
    static boolean looksNumeric(String value) {
        if (value == null) return false;
        String s = value.trim();
        if (s.isEmpty()) return false;
        return s.matches("^[+-]?[0-9][0-9,]*(\\.[0-9]+)?(%|％)?(\\s+\\S{1,8})?$");
    }
}
