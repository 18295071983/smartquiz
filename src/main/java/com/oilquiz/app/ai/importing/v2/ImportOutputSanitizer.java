package com.oilquiz.app.ai.importing.v2;

import android.util.Log;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 导入专用引擎 —— 四层后置输出硬过滤。
 * <p>
 * 完全不依赖模型理解力，Java 代码强制清洗修正模型输出：
 * <ol>
 *   <li>文本裁剪：截取第一个 '{' 至最后一个 '}' 之间内容，剔除前后无关文字/注释/代码标记</li>
 *   <li>JSON 语法校验：解析失败判定输出失效（由引擎触发修复重试）</li>
 *   <li>字段合法性校验：比对实时数据库字段列表，剔除模型自创非法字段；核心必填缺失判无效</li>
 *   <li>数据类型强修正：difficulty 强制限定 1/2/3，文本格式难度自动替换默认值 1</li>
 * </ol>
 */
public final class ImportOutputSanitizer {

    private static final String TAG = "ImportOutputSanitizer";

    /** 清洗结果 */
    public static class SanitizedOutput {
        /** 是否通过全部校验 */
        public boolean valid;
        /** 解析后的 JSON 对象（已通过裁剪与语法校验） */
        public JSONObject json;
        /** 失效原因（valid=false 时） */
        public String failReason;
        /** 被剔除的非法字段名 */
        public int removedIllegalFields;

        public static SanitizedOutput fail(String reason) {
            SanitizedOutput o = new SanitizedOutput();
            o.valid = false;
            o.failReason = reason;
            return o;
        }
    }

    private ImportOutputSanitizer() {
    }

    /**
     * 第一层：文本裁剪。截取第一个 '{' 至最后一个 '}' 之间全部内容。
     * 无合法大括号结构返回 null。
     */
    public static String trimToJsonBlock(String raw) {
        if (raw == null) return null;
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return raw.substring(start, end + 1);
    }

    /**
     * 四层过滤主入口（映射任务）。
     *
     * @param raw           模型原始输出
     * @param legalFields   数据库实时字段集合（标准字段名）
     * @param requiredKeys  核心必填字段（缺失则判无效），如 questionText / correctAnswer
     */
    public static SanitizedOutput sanitizeMappingOutput(String raw, Set<String> legalFields,
                                                        String... requiredKeys) {
        // 第一层：文本裁剪
        String block = trimToJsonBlock(raw);
        if (block == null) {
            return SanitizedOutput.fail("无合法JSON结构");
        }

        // 第二层：JSON 语法校验
        JSONObject json;
        try {
            json = new JSONObject(block);
        } catch (Exception e) {
            return SanitizedOutput.fail("JSON解析失败: " + e.getMessage());
        }

        // 第三层：字段合法性校验 —— 剔除模型自创非法字段
        SanitizedOutput out = new SanitizedOutput();
        out.json = json;
        if (legalFields != null && !legalFields.isEmpty()) {
            Iterator<String> it = json.keys();
            java.util.List<String> illegal = new java.util.ArrayList<>();
            while (it.hasNext()) {
                String key = it.next();
                if (!legalFields.contains(key)) {
                    illegal.add(key);
                }
            }
            for (String key : illegal) {
                json.remove(key);
                out.removedIllegalFields++;
            }
        }

        // 核心必填字段缺失 → 输出无效
        if (requiredKeys != null) {
            for (String req : requiredKeys) {
                String v = json.optString(req, "").trim();
                if (v.isEmpty()) {
                    return SanitizedOutput.fail("必填字段缺失: " + req);
                }
            }
        }

        out.valid = true;
        return out;
    }

    /**
     * 四层过滤主入口（缺失字段填充任务）。
     * 第四层强修正：difficulty 强制 1/2/3，文本难度替换默认值 1。
     */
    public static SanitizedOutput sanitizeFillOutput(String raw) {
        String block = trimToJsonBlock(raw);
        if (block == null) {
            return SanitizedOutput.fail("无合法JSON结构");
        }
        JSONObject json;
        try {
            json = new JSONObject(block);
        } catch (Exception e) {
            return SanitizedOutput.fail("JSON解析失败: " + e.getMessage());
        }

        SanitizedOutput out = new SanitizedOutput();
        out.json = json;
        out.valid = true;

        // 第四层：数据类型强修正
        try {
            if (json.has("difficulty")) {
                Object d = json.get("difficulty");
                int dv;
                if (d instanceof Number) {
                    dv = ((Number) d).intValue();
                } else {
                    dv = parseTextDifficulty(String.valueOf(d));
                }
                if (dv < 1 || dv > 3) dv = 1;
                json.put("difficulty", dv);
            }
        } catch (Exception e) {
            Log.w(TAG, "difficulty 强修正异常: " + e.getMessage());
            try {
                json.put("difficulty", 1);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** 文本格式难度 → 数值（简单/中等/困难等），无法识别返回 1 */
    public static int parseTextDifficulty(String text) {
        if (text == null) return 1;
        String t = text.trim();
        try {
            int v = Integer.parseInt(t);
            return (v >= 1 && v <= 3) ? v : 1;
        } catch (NumberFormatException ignored) {
        }
        if (t.contains("易") || t.contains("简单") || t.toLowerCase().contains("easy")) return 1;
        if (t.contains("中") || t.toLowerCase().contains("medium")) return 2;
        if (t.contains("难") || t.toLowerCase().contains("hard")) return 3;
        return 1;
    }

    /** 把 JSON 对象转为 LinkedHashMap（保持顺序） */
    public static Map<String, String> toFlatStringMap(JSONObject json) {
        Map<String, String> map = new LinkedHashMap<>();
        if (json == null) return map;
        Iterator<String> it = json.keys();
        while (it.hasNext()) {
            String key = it.next();
            map.put(key, json.optString(key, ""));
        }
        return map;
    }
}
