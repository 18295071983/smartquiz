package com.oilquiz.app.ai.chat.status;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * 插槽文本清洗（2026-11-15 全量体检）。
 *
 * <p>真机证据：甲板出现「目标 · [object Object]」。根因是插件把 goal 事件里的
 * {@code title} 对象直接 {@code String()} 了；同时 {@code phone_ui}/旧插件也可能把
 * {@code undefined}、{@code NaN}、几百字的命令原文塞进 title/detail。服务端已经统一
 * 过一遍 {@code slotText()}；App 侧再兜一层，保证：
 * </p>
 * <ul>
 *   <li>对象做浅提取（title/text/goal/objective/name/label/summary/description）；</li>
 *   <li>数组取前 3 项拼成一行；</li>
 *   <li>剔除 {@code [object Object]}、{@code undefined}、{@code NaN}，压缩空白；</li>
 *   <li>超过 {@link #MAX_LEN} 字截断并加 {@code …}（含省略号刚好 {@link #MAX_LEN} 字）。</li>
 * </ul>
 *
 * <p>与插件 {@code slotText()} 同规则；旧帧（title/detail 都是普通字符串）行为不变。</p>
 */
public final class SlotText {

    /** 甲板一行最多显示的字符数（含截断省略号）。 */
    public static final int MAX_LEN = 80;

    /** 对象浅提取的字段顺序（与插件 slotText 一致）。 */
    private static final String[] KEYS = {
            "title", "text", "goal", "objective", "name", "label", "summary", "description"
    };

    private SlotText() {
    }

    /** 清洗一段文本；null/空/纯脏值 → 空串。 */
    public static String clean(String raw) {
        if (raw == null) return "";
        String s = raw
                .replaceAll("(?i)\\[object Object\\]", " ")
                .replaceAll("(?i)\\bundefined\\b", " ")
                .replaceAll("\\bNaN\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (s.isEmpty()) return "";
        String lower = s.toLowerCase(Locale.ROOT);
        if ("undefined".equals(lower) || "null".equals(lower)
                || "nan".equals(lower) || "[object object]".equals(lower)) {
            return "";
        }
        if (s.length() > MAX_LEN) s = s.substring(0, MAX_LEN - 1) + "…";
        return s;
    }

    /** 从 JSON 帧里取一个文本字段并清洗（对象会浅提取，不再得到 JSON 串）。 */
    public static String cleanField(JSONObject data, String key) {
        if (data == null || key == null) return "";
        Object raw;
        try {
            raw = data.opt(key);
        } catch (Throwable ignored) {
            return "";
        }
        return cleanAny(raw, 0);
    }

    /** 任意 JSON 值的浅提取 + 清洗；异常一律当空串（插槽是瞬态，不能因坏帧崩页面）。 */
    static String cleanAny(Object raw, int depth) {
        if (raw == null || raw == JSONObject.NULL) return "";
        if (depth > 2) return "";
        if (raw instanceof String) return clean((String) raw);
        if (raw instanceof Number) {
            double d = ((Number) raw).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) return "";
            return clean(String.valueOf(raw));
        }
        if (raw instanceof JSONArray) {
            JSONArray arr = (JSONArray) raw;
            StringBuilder sb = new StringBuilder();
            int used = 0;
            for (int i = 0; i < arr.length() && used < 3; i += 1) {
                String got = cleanAny(arr.opt(i), depth + 1);
                if (got.isEmpty()) continue;
                if (sb.length() > 0) sb.append(" / ");
                sb.append(got);
                used += 1;
            }
            return clean(sb.toString());
        }
        if (raw instanceof JSONObject) {
            JSONObject obj = (JSONObject) raw;
            for (String key : KEYS) {
                Object v;
                try {
                    v = obj.opt(key);
                } catch (Throwable ignored) {
                    v = null;
                }
                String got = cleanAny(v, depth + 1);
                if (!got.isEmpty()) return got;
            }
            return "";
        }
        return "";
    }
}
