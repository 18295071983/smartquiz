package com.oilquiz.app.ai.tool;

import com.oilquiz.app.ai.tool.openai.ParamDefinition;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 动态工具参数定义（标准 function schema 解析结果）。
 *
 * 修复前：create_dynamic_tool 的 parameters 参数未经结构化解析，直接扁平化成
 * "参数名 → 原始字符串"，导致：
 *  1. AI 传完整 JSON Schema（{"type":"object","properties":{...},"required":[...]}）
 *     时被误拆成 "type/properties/required" 三个伪参数，工具列表显示异常；
 *  2. LLM 收到错误参数 schema，带参调用时参数注入失败/崩溃。
 *
 * 本类统一解析以下三种输入格式（AI 创建工具时可任选其一）：
 *  1. 简单格式：{"参数名":"参数描述"}                     → 全部 string 类型、非必填
 *  2. 属性格式：{"参数名":{"type":"...","description":"...","required":true,"default":...,"enum":[...]}}
 *  3. 完整 JSON Schema：{"type":"object","properties":{...},"required":["参数名"]}
 *
 * 解析结果以 List&lt;ParamDefinition&gt; 保存，可驱动：
 *  - getToolDefinition / OpenAI function calling 的标准 schema
 *  - 工具列表展示（名称/类型/必填）
 *  - dynamic_tools.json 持久化（paramSchema 字段）
 */
public class DynamicToolParams {

    private final List<ParamDefinition> definitions;

    public DynamicToolParams(List<ParamDefinition> definitions) {
        this.definitions = definitions != null ? definitions : new ArrayList<>();
    }

    public List<ParamDefinition> getDefinitions() {
        return definitions;
    }

    public boolean isEmpty() {
        return definitions.isEmpty();
    }

    /**
     * 解析 create_dynamic_tool 传入的 parameters 参数。
     *
     * @param raw parameters 参数值：JSON 字符串 或 Map（LLM 可能传对象）
     * @return 结构化参数；JSON 非法时返回 null（由调用方报错，不静默创建参数全丢的工具）
     */
    public static DynamicToolParams parse(Object raw) {
        if (raw == null) return new DynamicToolParams(new ArrayList<>());
        JSONObject root;
        if (raw instanceof Map) {
            root = new JSONObject((Map<?, ?>) raw);
        } else if (raw instanceof JSONObject) {
            root = (JSONObject) raw;
        } else if (raw instanceof String) {
            String s = ((String) raw).trim();
            if (s.isEmpty()) return new DynamicToolParams(new ArrayList<>());
            try {
                root = new JSONObject(s);
            } catch (JSONException e) {
                return null;
            }
        } else {
            return null;
        }
        return parseJson(root);
    }

    private static DynamicToolParams parseJson(JSONObject root) {
        List<ParamDefinition> defs = new ArrayList<>();
        try {
            // 格式3：完整 JSON Schema（顶层带 properties）
            if (root.has("properties")) {
                JSONObject properties = root.optJSONObject("properties");
                JSONArray requiredArr = root.optJSONArray("required");
                List<String> required = new ArrayList<>();
                if (requiredArr != null) {
                    for (int i = 0; i < requiredArr.length(); i++) {
                        required.add(requiredArr.optString(i, ""));
                    }
                }
                if (properties != null) {
                    Iterator<String> keys = properties.keys();
                    while (keys.hasNext()) {
                        String name = keys.next();
                        JSONObject prop = properties.optJSONObject(name);
                        if (prop == null) continue;
                        defs.add(buildParam(name, prop, required.contains(name)));
                    }
                }
            } else {
                // 格式1（简单）/ 格式2（属性级 schema）：逐 key 解析
                Iterator<String> keys = root.keys();
                while (keys.hasNext()) {
                    String name = keys.next();
                    Object value = root.opt(name);
                    if (value instanceof JSONObject) {
                        JSONObject prop = (JSONObject) value;
                        boolean required = prop.optBoolean("required", false);
                        defs.add(buildParam(name, prop, required));
                    } else if (value != null) {
                        // 简单格式：{"参数名":"参数描述"}
                        defs.add(new ParamDefinition(name, "string", String.valueOf(value), false));
                    }
                }
            }
        } catch (Exception e) {
            return null;
        }
        return new DynamicToolParams(defs);
    }

    /** 从属性级 JSON Schema 构造 ParamDefinition（含类型归一化） */
    private static ParamDefinition buildParam(String name, JSONObject prop, boolean required) {
        String type = normalizeType(prop.optString("type", "string"));
        String desc = prop.optString("description", "");
        Object defaultValue = prop.has("default") ? prop.opt("default") : null;
        List<String> enumValues = null;
        JSONArray enumArr = prop.optJSONArray("enum");
        if (enumArr != null && enumArr.length() > 0) {
            enumValues = new ArrayList<>();
            for (int i = 0; i < enumArr.length(); i++) {
                Object v = enumArr.opt(i);
                enumValues.add(v != null ? String.valueOf(v) : "");
            }
        }
        return new ParamDefinition(name, type, desc, required, defaultValue, enumValues);
    }

    /** 类型归一化：str→string、int→integer、float/double→number、bool→boolean */
    private static String normalizeType(String type) {
        if (type == null || type.trim().isEmpty()) return "string";
        String t = type.trim().toLowerCase();
        switch (t) {
            case "str":
            case "text":
                return "string";
            case "int":
            case "long":
                return "integer";
            case "float":
            case "double":
            case "numeric":
                return "number";
            case "bool":
                return "boolean";
            default:
                return t;
        }
    }

    /** 名称 → 展示用描述（含类型与必填标注），用于 getParameterDescriptions/工具列表 */
    public Map<String, String> toDisplayMap() {
        Map<String, String> display = new LinkedHashMap<>();
        for (ParamDefinition def : definitions) {
            StringBuilder sb = new StringBuilder();
            if (def.getDescription() != null && !def.getDescription().isEmpty()) {
                sb.append(def.getDescription());
                if (!def.getDescription().endsWith("。") && !def.getDescription().endsWith(".")) {
                    sb.append("；");
                }
            }
            sb.append("类型:").append(def.getType() == null ? "string" : def.getType());
            sb.append(def.isRequired() ? ",必填" : ",可选");
            if (def.getDefaultValue() != null) {
                sb.append(",默认:").append(def.getDefaultValue());
            }
            if (def.getEnumValues() != null && !def.getEnumValues().isEmpty()) {
                sb.append(",枚举:").append(String.join("/", def.getEnumValues()));
            }
            display.put(def.getName(), sb.toString());
        }
        return display;
    }

    /** 序列化为持久化 JSON 字符串（dynamic_tools.json 的 paramSchema 字段） */
    public String toJson() {
        try {
            JSONArray arr = new JSONArray();
            for (ParamDefinition def : definitions) {
                JSONObject o = new JSONObject();
                o.put("name", def.getName());
                o.put("type", def.getType() != null ? def.getType() : "string");
                o.put("description", def.getDescription() != null ? def.getDescription() : "");
                o.put("required", def.isRequired());
                if (def.getDefaultValue() != null) {
                    o.put("default", def.getDefaultValue());
                }
                if (def.getEnumValues() != null && !def.getEnumValues().isEmpty()) {
                    o.put("enum", new JSONArray(def.getEnumValues()));
                }
                arr.put(o);
            }
            JSONObject wrap = new JSONObject();
            wrap.put("definitions", arr);
            return wrap.toString();
        } catch (JSONException e) {
            return null;
        }
    }

    /** 从持久化 JSON 恢复；解析失败返回 null */
    public static DynamicToolParams fromJson(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(json);
            JSONArray arr = root.optJSONArray("definitions");
            List<ParamDefinition> defs = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String name = o.optString("name", "");
                    if (name.isEmpty()) continue;
                    String type = o.optString("type", "string");
                    String desc = o.optString("description", "");
                    boolean required = o.optBoolean("required", false);
                    Object defaultValue = o.has("default") ? o.opt("default") : null;
                    List<String> enumValues = null;
                    JSONArray enumArr = o.optJSONArray("enum");
                    if (enumArr != null && enumArr.length() > 0) {
                        enumValues = new ArrayList<>();
                        for (int j = 0; j < enumArr.length(); j++) {
                            Object v = enumArr.opt(j);
                            enumValues.add(v != null ? String.valueOf(v) : "");
                        }
                    }
                    defs.add(new ParamDefinition(name, type, desc, required, defaultValue, enumValues));
                }
            }
            return new DynamicToolParams(defs);
        } catch (JSONException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "DynamicToolParams" + toJson();
    }
}
