package com.oilquiz.app.ai.python;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Layout 有界公式求值器（2026-11-15）。
 *
 * <p>插件可以给 {@code text}/{@code metric}（{@code stat}）的取值位置写
 * {@code {"=":"a * b"}}，渲染时用 {@code props.data} 求值。这里刻意<b>不是</b>通用表达式
 * 引擎：没有变量赋值、没有字符串、没有属性访问、没有反射/脚本，只有</p>
 * <ul>
 *   <li>数字（整数/小数）；</li>
 *   <li>{@code + - * /}、一元正负号、括号；</li>
 *   <li>{@code data.} 路径引用（{@code data.a.b}、{@code items[0].price}）；</li>
 *   <li>{@code min / max / round / abs} 四个函数。</li>
 * </ul>
 *
 * <p>其余字符、未知函数、未知路径、除零、超长/超深表达式一律返回 {@code null}，
 * 由调用方降级成 {@link #FALLBACK}（"&mdash;"），绝不抛异常、绝不执行脚本。</p>
 *
 * <p>纯 JVM 类（不依赖 Android），单测直接调 {@link #evaluate(String, JSONObject)}。</p>
 */
public final class LayoutFormula {

    /** 公式算不出来时的降级文案（与 dsh-Go 的占位口径一致）。 */
    public static final String FALLBACK = "—";

    /** 表达式最大长度：256 字符足够写常见指标，也挡住把整段文本当公式塞进来。 */
    private static final int MAX_EXPRESSION_LENGTH = 256;
    /** 最多 128 个 token，避免深层括号/长链把解析器拖住。 */
    private static final int MAX_TOKENS = 128;
    /** 递归深度上限（括号/一元运算）。 */
    private static final int MAX_DEPTH = 24;
    /** 函数最多 8 个参数。 */
    private static final int MAX_CALL_ARGS = 8;

    private LayoutFormula() {
    }

    // ---------------------------------------------------------------- 值形态

    /** 是否是绑定对象 {@code {"$":"data.a.b"}}。 */
    public static boolean isBinding(Object value) {
        return value instanceof JSONObject && ((JSONObject) value).has("$");
    }

    /** 是否是公式对象 {@code {"=":"a * b"}}。 */
    public static boolean isFormula(Object value) {
        return value instanceof JSONObject && ((JSONObject) value).has("=");
    }

    /** 绑定对象里的路径；不是绑定返回空串。 */
    public static String bindingPath(Object value) {
        if (!isBinding(value)) return "";
        return ((JSONObject) value).optString("$", "").trim();
    }

    /** 公式对象里的表达式；不是公式返回空串。 */
    public static String expression(Object value) {
        if (!isFormula(value)) return "";
        return ((JSONObject) value).optString("=", "").trim();
    }

    // ---------------------------------------------------------------- 求值

    /**
     * 求值。
     *
     * @param expression 公式文本（不含外层 {"="}）
     * @param data       {@code props.data}；可为 null
     * @return 有限数值；任何非法/越界/除零都返回 null
     */
    public static Double evaluate(String expression, JSONObject data) {
        if (expression == null) return null;
        String expr = expression.trim();
        if (expr.isEmpty() || expr.length() > MAX_EXPRESSION_LENGTH) return null;
        try {
            Double result = new Parser(expr, data).parse();
            if (result == null || !Double.isFinite(result)) return null;
            return result;
        } catch (Throwable t) {
            // 非法字符/未知函数/除零/超深：一律降级，不把异常抛给渲染链路
            return null;
        }
    }

    /** 求值并格式化；算不出来返回 {@link #FALLBACK}。 */
    public static String evaluateText(String expression, JSONObject data) {
        Double value = evaluate(expression, data);
        return value == null ? FALLBACK : format(value);
    }

    /**
     * 解析一个"取值位置"的值：绑定 / 公式 / 普通值。
     *
     * <ul>
     *   <li>绑定 {@code {"$":"data.a"}} → 从 data 取值；取不到返回空串；</li>
     *   <li>公式 {@code {"=":"a+1"}} → 算出来格式化，算不出来返回 {@link #FALLBACK}；</li>
     *   <li>普通值 → 字符串。</li>
     * </ul>
     */
    public static String resolveDisplay(Object value, JSONObject data) {
        if (isBinding(value)) {
            Object resolved = resolvePath(data, bindingPath(value));
            return displayValue(resolved);
        }
        if (isFormula(value)) {
            return evaluateText(expression(value), data);
        }
        return displayValue(value);
    }

    /** 普通值 → 展示文本（数字统一去掉多余 0；对象/数组用 JSON 文本）。 */
    public static String displayValue(Object value) {
        if (value == null || value == JSONObject.NULL) return "";
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (Double.isFinite(d)) return format(d);
        }
        if (value instanceof Boolean) return String.valueOf(value);
        return String.valueOf(value);
    }

    /**
     * 按 {@code data.a.b} / {@code items[0].price} 从 data 里取值。
     *
     * @return 取到的值；路径非法/不存在返回 null（显式 JSON null 也当不存在）
     */
    public static Object resolvePath(JSONObject data, String path) {
        if (data == null || path == null) return null;
        String p = path.trim();
        if (p.startsWith("$.")) p = p.substring(2);
        if (p.equals("data")) return data;
        if (p.startsWith("data.")) p = p.substring("data.".length());
        if (p.isEmpty()) return null;
        List<Object> segments = splitPath(p);
        if (segments == null || segments.isEmpty()) return null;
        try {
            Object current = data;
            for (Object segment : segments) {
                if (current instanceof JSONObject) {
                    if (!(segment instanceof String)) return null;
                    JSONObject obj = (JSONObject) current;
                    if (!obj.has((String) segment)) return null;
                    current = obj.opt((String) segment);
                } else if (current instanceof JSONArray) {
                    if (!(segment instanceof Integer)) return null;
                    JSONArray arr = (JSONArray) current;
                    int index = (Integer) segment;
                    if (index < 0 || index >= arr.length()) return null;
                    current = arr.opt(index);
                } else {
                    return null;
                }
                if (current == null || current == JSONObject.NULL) return null;
            }
            return current;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 把路径拆成 ["a","b",0]；含非法字符返回 null。 */
    private static List<Object> splitPath(String path) {
        List<Object> out = new ArrayList<>();
        int i = 0;
        int len = path.length();
        while (i < len) {
            char c = path.charAt(i);
            if (c == '[') {
                int close = path.indexOf(']', i + 1);
                if (close < 0) return null;
                String num = path.substring(i + 1, close).trim();
                if (num.isEmpty() || num.length() > 6) return null;
                for (int k = 0; k < num.length(); k++) {
                    if (!Character.isDigit(num.charAt(k))) return null;
                }
                out.add(Integer.parseInt(num));
                i = close + 1;
            } else if (isIdentStart(c)) {
                int start = i;
                i++;
                while (i < len && isIdentPart(path.charAt(i))) i++;
                out.add(path.substring(start, i));
            } else {
                return null;
            }
            if (i >= len) break;
            char sep = path.charAt(i);
            if (sep == '.') {
                i++;
                if (i >= len || !isIdentStart(path.charAt(i))) return null;
            } else if (sep != '[') {
                return null;
            }
        }
        return out;
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || (c >= '0' && c <= '9');
    }

    /** 数字展示：整数不带小数，小数最多 2 位并去掉尾随 0。 */
    public static String format(double value) {
        if (!Double.isFinite(value)) return FALLBACK;
        double rounded = Math.rint(value);
        if (Math.abs(value - rounded) < 1e-9 && Math.abs(rounded) < 1e15) {
            return String.valueOf((long) rounded);
        }
        String s = String.format(Locale.US, "%.2f", value);
        if (s.indexOf('.') >= 0) {
            int end = s.length();
            while (end > 0 && s.charAt(end - 1) == '0') end--;
            if (end > 0 && s.charAt(end - 1) == '.') end--;
            s = s.substring(0, end);
        }
        return "-0".equals(s) ? "0" : s;
    }

    // ---------------------------------------------------------------- 解析器

    private static final class Parser {
        private final String src;
        private final JSONObject data;
        private int pos;
        private int tokens;

        Parser(String src, JSONObject data) {
            this.src = src;
            this.data = data;
        }

        Double parse() {
            double value = expression(0);
            skipSpaces();
            if (pos != src.length()) return null; // 有剩余字符（;、^、字符串…）一律拒绝
            return value;
        }

        private double expression(int depth) {
            checkDepth(depth);
            double value = term(depth + 1);
            while (true) {
                skipSpaces();
                char c = peek();
                if (c == '+') {
                    advanceToken();
                    value += term(depth + 1);
                } else if (c == '-') {
                    advanceToken();
                    value -= term(depth + 1);
                } else {
                    return value;
                }
                if (!Double.isFinite(value)) throw invalid();
            }
        }

        private double term(int depth) {
            checkDepth(depth);
            double value = unary(depth + 1);
            while (true) {
                skipSpaces();
                char c = peek();
                if (c == '*') {
                    advanceToken();
                    value *= unary(depth + 1);
                } else if (c == '/') {
                    advanceToken();
                    double divisor = unary(depth + 1);
                    if (divisor == 0d) throw invalid(); // 除零：降级，不给 Infinity/NaN
                    value /= divisor;
                } else {
                    return value;
                }
                if (!Double.isFinite(value)) throw invalid();
            }
        }

        private double unary(int depth) {
            checkDepth(depth);
            skipSpaces();
            char c = peek();
            if (c == '+') {
                advanceToken();
                return unary(depth + 1);
            }
            if (c == '-') {
                advanceToken();
                return -unary(depth + 1);
            }
            return primary(depth + 1);
        }

        private double primary(int depth) {
            checkDepth(depth);
            skipSpaces();
            char c = peek();
            if (c == '(') {
                advanceToken();
                double value = expression(depth + 1);
                skipSpaces();
                if (peek() != ')') throw invalid();
                advanceToken();
                return value;
            }
            if (c >= '0' && c <= '9') {
                return number();
            }
            if (isIdentStart(c)) {
                return identifier();
            }
            throw invalid(); // 空表达式、$、引号、分号、^、中文…
        }

        private double number() {
            int start = pos;
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            if (pos < src.length() && src.charAt(pos) == '.') {
                pos++;
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            }
            countToken();
            try {
                return Double.parseDouble(src.substring(start, pos));
            } catch (NumberFormatException e) {
                throw invalid();
            }
        }

        private double identifier() {
            int start = pos;
            pos++;
            while (pos < src.length() && isIdentPart(src.charAt(pos))) pos++;
            String name = src.substring(start, pos);
            int afterName = pos;
            skipSpaces();
            if (peek() == '(') {
                // 只认四个白名单函数；其它名字后面跟括号直接拒绝（不许脚本/反射）
                if (!"min".equals(name) && !"max".equals(name)
                        && !"round".equals(name) && !"abs".equals(name)) {
                    throw invalid();
                }
                pos = afterName; // 回到名字末尾，函数参数从 '(' 开始解析
                return function(name);
            }
            // 路径引用：a / data.a / items[0].price
            StringBuilder path = new StringBuilder(name);
            while (true) {
                if (pos >= src.length()) break;
                char c = src.charAt(pos);
                if (c == '.') {
                    int dot = pos;
                    pos++;
                    if (pos >= src.length() || !isIdentStart(src.charAt(pos))) {
                        pos = dot;
                        break;
                    }
                    int segStart = pos;
                    pos++;
                    while (pos < src.length() && isIdentPart(src.charAt(pos))) pos++;
                    path.append('.').append(src, segStart, pos);
                } else if (c == '[') {
                    int close = src.indexOf(']', pos + 1);
                    if (close < 0) throw invalid();
                    String idx = src.substring(pos + 1, close).trim();
                    if (idx.isEmpty() || idx.length() > 6) throw invalid();
                    for (int k = 0; k < idx.length(); k++) {
                        if (!Character.isDigit(idx.charAt(k))) throw invalid();
                    }
                    path.append('[').append(Integer.parseInt(idx)).append(']');
                    pos = close + 1;
                } else {
                    break;
                }
            }
            countToken();
            Object value = resolvePath(data, path.toString());
            if (value instanceof Number) return ((Number) value).doubleValue();
            if (value instanceof String) {
                try {
                    return Double.parseDouble(((String) value).trim());
                } catch (NumberFormatException ignored) {
                    throw invalid();
                }
            }
            // Boolean/对象/数组/不存在：不是数值，公式算不出来
            throw invalid();
        }

        private double function(String name) {
            skipSpaces();
            if (peek() != '(') throw invalid();
            advanceToken();
            List<Double> args = new ArrayList<>();
            skipSpaces();
            if (peek() != ')') {
                while (true) {
                    args.add(expression(0));
                    if (args.size() > MAX_CALL_ARGS) throw invalid();
                    skipSpaces();
                    char c = peek();
                    if (c == ',') {
                        advanceToken();
                        continue;
                    }
                    if (c == ')') break;
                    throw invalid();
                }
            }
            skipSpaces();
            if (peek() != ')') throw invalid();
            advanceToken();
            if (args.isEmpty()) throw invalid();
            switch (name) {
                case "round":
                    if (args.size() != 1) throw invalid();
                    return Math.round(args.get(0));
                case "abs":
                    if (args.size() != 1) throw invalid();
                    return Math.abs(args.get(0));
                case "min": {
                    double v = args.get(0);
                    for (int i = 1; i < args.size(); i++) v = Math.min(v, args.get(i));
                    return v;
                }
                case "max": {
                    double v = args.get(0);
                    for (int i = 1; i < args.size(); i++) v = Math.max(v, args.get(i));
                    return v;
                }
                default:
                    throw invalid();
            }
        }

        private void checkDepth(int depth) {
            if (depth > MAX_DEPTH) throw invalid();
        }

        private char peek() {
            return pos < src.length() ? src.charAt(pos) : '\0';
        }

        private void skipSpaces() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
        }

        /** 数字/标识符已经自己把 pos 推到末尾，只记 token 预算。 */
        private void countToken() {
            tokens++;
            if (tokens > MAX_TOKENS) throw invalid();
        }

        /** 运算符/括号/逗号：吃掉一个字符并记 token 预算。 */
        private void advanceToken() {
            pos++;
            countToken();
        }

        private IllegalArgumentException invalid() {
            return new IllegalArgumentException("invalid formula");
        }
    }
}
