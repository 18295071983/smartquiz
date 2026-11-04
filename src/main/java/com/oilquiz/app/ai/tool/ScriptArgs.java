package com.oilquiz.app.ai.tool;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 动态工具的参数注入（<b>唯一权威</b>）：把模型传来的 {@code parameters} 拼成脚本能读的
 * {@code script_args}，Python 与 JS 各一份语法，两处共用同一个出口。
 *
 * <p>为什么要抽出来：这段拼装原先在 {@link DynamicToolExecutor}（create_dynamic_tool 的
 * Java/DSL/JS/Python 通路）和 {@link com.oilquiz.app.ai.python.PythonDynamicTool}
 * （ai_create_tool 的 Python 通路）各写了一份，看着一样其实<b>已经漂了</b>：
 * 前者把 Boolean 直接 {@code toString()} 注进 Python，得到 {@code true}/{@code false}
 * ——Python 里那是未定义名，脚本第一行就 NameError，而模型只看到"Python 执行失败"；
 * 后者正确地输出 {@code True}/{@code False}。同一次"给工具传个布尔参数"在两个入口
 * 一个能跑一个必崩，这是本项目反复踩到的并行写者漂移。
 *
 * <p>顺带堵住字符串字面量的注入面：旧写法只做 {@code replace("'", "\\'")}，
 * 于是值里的反斜杠（{@code C:\path}）、换行、以反斜杠结尾的值都会把引号闭合搞坏，
 * 轻则 SyntaxError 在几百行后报（本项目注释里已有同类教训），重则把值里的内容
 * 变成脚本代码。这里改成按字符转义（反斜杠、引号、控制字符），Python 用双引号字面量，
 * JS 交给 {@link JSONObject} 自己序列化。
 */
public final class ScriptArgs {

    private ScriptArgs() {
    }

    // ==================== Python ====================

    /**
     * 完整 Python 脚本 = 固定头 + {@code script_args} dict + 用户逻辑。
     * 与历史产出保持同样的行结构，只是字面量更安全、布尔更正确。
     */
    public static String fullPythonScript(String body, Map<String, Object> parameters) {
        StringBuilder sb = new StringBuilder();
        sb.append("# -*- coding: utf-8 -*-\n");
        sb.append("import sys\n");
        sb.append("sys.path.insert(0, '.')\n\n");
        sb.append("# 脚本参数\n");
        sb.append("script_args = ").append(pythonDict(parameters)).append("\n\n");
        sb.append(body == null ? "" : body);
        return sb.toString();
    }

    /** {@code Map} → Python dict 字面量（{@code {}} 而非 {@code null}，脚本可直接 {@code script_args.get}） */
    public static String pythonDict(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(pythonStringLiteral(String.valueOf(e.getKey())))
              .append(": ")
              .append(pythonLiteral(e.getValue()));
        }
        return sb.append("}").toString();
    }

    /** 任意值 → Python 字面量（None / True / False / 数字 / 字符串 / 列表 / dict） */
    public static String pythonLiteral(Object v) {
        if (v == null || v == JSONObject.NULL) return "None";
        if (v instanceof Boolean) return ((Boolean) v) ? "True" : "False";
        if (v instanceof Number) return v.toString();
        if (v instanceof String) return pythonStringLiteral((String) v);
        if (v instanceof JSONArray) {
            JSONArray ja = (JSONArray) v;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < ja.length(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(pythonLiteral(ja.opt(i)));
            }
            return sb.append("]").toString();
        }
        if (v instanceof JSONObject) {
            JSONObject jo = (JSONObject) v;
            Map<String, Object> m = new LinkedHashMap<>();
            java.util.Iterator<String> keys = jo.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                m.put(k, jo.opt(k));
            }
            return pythonDict(m);
        }
        if (v instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            List<?> list = (List<?>) v;
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(pythonLiteral(list.get(i)));
            }
            return sb.append("]").toString();
        }
        if (v instanceof Map) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                m.put(String.valueOf(e.getKey()), e.getValue());
            }
            return pythonDict(m);
        }
        return pythonStringLiteral(String.valueOf(v));
    }

    /**
     * 字符串 → Python 双引号字面量。逐字符转义：反斜杠先处理，否则会把后续转义再转一遍。
     */
    private static String pythonStringLiteral(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    // ==================== JavaScript ====================

    /**
     * JS 前置：把参数注成 {@code const script_args = {...}}。
     * 无参数时给 {@code {}}——不能是 null，否则脚本里 {@code script_args.a} 直接 TypeError。
     */
    public static String jsPrelude(Map<String, Object> parameters) {
        Object json = toJsonValue(parameters != null ? parameters : new LinkedHashMap<>());
        if (!(json instanceof JSONObject)) {
            json = new JSONObject();
        }
        return "// 动态工具参数\nconst script_args = " + json.toString() + ";\n\n";
    }

    /** 任意值 → org.json 可序列化对象（嵌套 Map/List/JSONObject/JSONArray/原始类型） */
    public static Object toJsonValue(Object v) {
        if (v == null || v == JSONObject.NULL) return JSONObject.NULL;
        if (v instanceof Number || v instanceof Boolean || v instanceof String) return v;
        if (v instanceof JSONObject || v instanceof JSONArray) return v;
        if (v instanceof Map) {
            JSONObject jo = new JSONObject();
            try {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                    jo.put(String.valueOf(e.getKey()), toJsonValue(e.getValue()));
                }
            } catch (JSONException ignored) {
            }
            return jo;
        }
        if (v instanceof List) {
            JSONArray ja = new JSONArray();
            for (Object item : (List<?>) v) {
                ja.put(toJsonValue(item));
            }
            return ja;
        }
        return String.valueOf(v);
    }

    /** 兜底：把参数名列表取出来（日志/错误文案用，绝不能把参数值写进日志） */
    public static String keyNames(Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) return "{}";
        List<String> keys = new ArrayList<>(parameters.keySet());
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(keys.get(i));
        }
        return sb.append("}").toString();
    }
}
