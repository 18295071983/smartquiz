package com.oilquiz.app.ai.tool;

import android.content.Context;

import com.oilquiz.app.ai.tool.annotation.Tool;

import java.util.HashMap;
import java.util.Map;

/**
 * 数学计算工具：纯 Java 安全表达式求值（不依赖 Python 运行时）。
 *
 * 支持：数字、+ - * / % ^（幂）、括号、小数、负数。
 * 参数：
 * - expression: 表达式字符串，如 "3.5 * (2 + 4) / 7"
 */
@Tool(value = "calculator", category = "utility")
public class CalculatorTool implements AITool {

    private static final String TAG = "CalculatorTool";

    public CalculatorTool() {
    }

    public CalculatorTool(Context context) {
    }

    @Override
    public String getName() {
        return "calculator";
    }

    @Override
    public String getDescription() {
        return "数学计算器：计算算术表达式，返回计算结果。"
                + "支持运算符: + 加 / - 减 / * 乘 / / 除 / % 取余 / ^ 幂 / ( ) 括号；"
                + "支持小数、负数与常用函数：sqrt/abs/round/floor/ceil/sin/cos/tan/log(以10为底)/"
                + "ln/exp/pow(a,b)/min(a,b)/max(a,b)，常量 pi、e"
                + "（如 sqrt(1024) 或 pow(2,10) 或 3.5*(2+4)/7）。"
                + "除零/非法表达式会返回明确错误。纯 Java 解析，无注入风险，计算即时返回。"
                + "简单数学计算用本工具（轻量快速），复杂数据分析用 python_calculate/python_analyze_data。";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("expression", "算术表达式，如 3.5 * (2 + 4) / 7、2^10、sqrt(1024)、pow(2,10)");
        return params;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            Object exprObj = parameters.get("expression");
            if (exprObj == null) {
                exprObj = parameters.get("expr");
            }
            if (exprObj == null) {
                return AIToolResult.fail("缺少参数: expression");
            }
            String expression = String.valueOf(exprObj).trim();
            if (expression.isEmpty()) {
                return AIToolResult.fail("表达式为空");
            }
            double result = new Evaluator().evaluate(expression);
            String resultStr = formatResult(result);
            Map<String, Object> info = new HashMap<>();
            info.put("expression", expression);
            info.put("result", resultStr);
            return AIToolResult.success(resultStr, info);
        } catch (Exception e) {
            return AIToolResult.fail("计算失败: " + e.getMessage());
        }
    }

    private static String formatResult(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        // 保留合理精度
        java.math.BigDecimal bd = new java.math.BigDecimal(value);
        return bd.stripTrailingZeros().toPlainString();
    }

    /** 递归下降表达式求值器（仅接受数字与运算符，天然防注入） */
    private static class Evaluator {
        private String src;
        private int pos;

        double evaluate(String expression) {
            this.src = expression.replaceAll("\\s+", "");
            this.pos = 0;
            double value = parseExpression();
            if (pos < src.length()) {
                throw new IllegalArgumentException(unsupportedCharHint(src.charAt(pos)));
            }
            return value;
        }

        private double parseExpression() {
            double value = parseTerm();
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == '+') { pos++; value += parseTerm(); }
                else if (c == '-') { pos++; value -= parseTerm(); }
                else break;
            }
            return value;
        }

        private double parseTerm() {
            double value = parseFactor();
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == '*') { pos++; value *= parseFactor(); }
                else if (c == '/') { pos++; double d = parseFactor(); if (d == 0) throw new IllegalArgumentException("除数为 0"); value /= d; }
                else if (c == '%') { pos++; double d = parseFactor(); if (d == 0) throw new IllegalArgumentException("除数为 0"); value %= d; }
                else break;
            }
            return value;
        }

        private double parseFactor() {
            double value = parsePrimary();
            if (pos < src.length() && src.charAt(pos) == '^') {
                pos++;
                double exp = parseFactor();
                return Math.pow(value, exp);
            }
            return value;
        }

        private double parsePrimary() {
            if (pos >= src.length()) throw new IllegalArgumentException("表达式不完整");
            char c = src.charAt(pos);
            if (c == '(') {
                pos++;
                double value = parseExpression();
                if (pos >= src.length() || src.charAt(pos) != ')') {
                    throw new IllegalArgumentException("缺少右括号");
                }
                pos++;
                return value;
            }
            if (c == '-') { pos++; return -parsePrimary(); }
            if (c == '+') { pos++; return parsePrimary(); }
            if (Character.isDigit(c) || c == '.') {
                int start = pos;
                while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
                    pos++;
                }
                try {
                    return Double.parseDouble(src.substring(start, pos));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("数字格式错误");
                }
            }
            if (Character.isLetter(c) || c == '_') {
                int start = pos;
                while (pos < src.length()
                        && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
                    pos++;
                }
                String name = src.substring(start, pos);
                if (pos < src.length() && src.charAt(pos) == '(') {
                    pos++; // 吃掉 '('
                    java.util.List<Double> args = new java.util.ArrayList<>();
                    if (pos < src.length() && src.charAt(pos) != ')') {
                        args.add(parseExpression());
                        while (pos < src.length() && src.charAt(pos) == ',') {
                            pos++;
                            args.add(parseExpression());
                        }
                    }
                    if (pos >= src.length() || src.charAt(pos) != ')') {
                        throw new IllegalArgumentException("函数 " + name + " 缺少右括号");
                    }
                    pos++; // 吃掉 ')'
                    return callFunction(name, args);
                }
                return constantOf(name);
            }
            throw new IllegalArgumentException(unsupportedCharHint(c));
        }

        /** 无法解析的字符：两处抛错共用同一条可操作提示（含可用字符集与替代工具） */
        private String unsupportedCharHint(char c) {
            return "无法解析的字符: " + c
                    + "（仅支持数字、+ - * / % ^、括号、函数与常量 pi/e；其他表达式请用 python_calculate）";
        }

        /** 常量：pi / e（大小写不敏感） */
        private double constantOf(String name) {
            String n = name.toLowerCase(java.util.Locale.ROOT);
            if ("pi".equals(n)) return Math.PI;
            if ("e".equals(n)) return Math.E;
            throw new IllegalArgumentException("未知的标识符: " + name
                    + "（若为函数请加括号，如 sqrt(16)；支持的函数见工具说明）");
        }

        /** 常用数学函数：只接受纯计算，白名单外明确报错并指路 */
        private double callFunction(String rawName, java.util.List<Double> a) {
            String n = rawName.toLowerCase(java.util.Locale.ROOT);
            switch (n) {
                case "sqrt": {
                    requireArgs(n, a, 1);
                    if (a.get(0) < 0) throw new IllegalArgumentException("sqrt 的参数不能为负数: " + a.get(0));
                    return Math.sqrt(a.get(0));
                }
                case "abs": requireArgs(n, a, 1); return Math.abs(a.get(0));
                case "round": requireArgs(n, a, 1); return Math.round(a.get(0));
                case "floor": requireArgs(n, a, 1); return Math.floor(a.get(0));
                case "ceil": requireArgs(n, a, 1); return Math.ceil(a.get(0));
                case "sin": requireArgs(n, a, 1); return Math.sin(a.get(0));
                case "cos": requireArgs(n, a, 1); return Math.cos(a.get(0));
                case "tan": requireArgs(n, a, 1); return Math.tan(a.get(0));
                case "log": { // 以 10 为底（与计算器习惯一致）
                    requireArgs(n, a, 1);
                    if (a.get(0) <= 0) throw new IllegalArgumentException("log 的参数必须大于 0: " + a.get(0));
                    return Math.log10(a.get(0));
                }
                case "ln": {
                    requireArgs(n, a, 1);
                    if (a.get(0) <= 0) throw new IllegalArgumentException("ln 的参数必须大于 0: " + a.get(0));
                    return Math.log(a.get(0));
                }
                case "exp": requireArgs(n, a, 1); return Math.exp(a.get(0));
                case "pow": requireArgs(n, a, 2); return Math.pow(a.get(0), a.get(1));
                case "min": requireArgs(n, a, 2); return Math.min(a.get(0), a.get(1));
                case "max": requireArgs(n, a, 2); return Math.max(a.get(0), a.get(1));
                default:
                    throw new IllegalArgumentException("不支持的函数: " + rawName
                            + "（支持 sqrt/abs/round/floor/ceil/sin/cos/tan/log/ln/exp/pow/min/max；"
                            + "其他函数请用 python_calculate）");
            }
        }

        private void requireArgs(String name, java.util.List<Double> a, int expected) {
            if (a.size() != expected) {
                throw new IllegalArgumentException(name + " 需要 " + expected + " 个参数，实际 " + a.size());
            }
        }
    }
}
