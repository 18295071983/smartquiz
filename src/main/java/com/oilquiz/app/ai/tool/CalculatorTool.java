package com.oilquiz.app.ai.tool;

import android.content.Context;

import java.util.HashMap;
import java.util.Map;

/**
 * 数学计算工具：纯 Java 安全表达式求值（不依赖 Python 运行时）。
 *
 * 支持：数字、+ - * / % ^（幂）、括号、小数、负数。
 * 参数：
 * - expression: 表达式字符串，如 "3.5 * (2 + 4) / 7"
 */
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
                + "支持小数与负数（如 3.5 * (2 + 4) / 7 或 2^10 或 (15-3)%4）。"
                + "除零/非法表达式会返回明确错误。纯 Java 解析，无注入风险，计算即时返回。"
                + "简单数学计算优先用本工具（轻量快速），复杂数据分析用 python_calculate/python_analyze_data。";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("expression", "算术表达式，如 3.5 * (2 + 4) / 7 或 2^10");
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
                throw new IllegalArgumentException("无法解析的字符: " + src.charAt(pos));
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
            throw new IllegalArgumentException("无法解析的字符: " + c);
        }
    }
}
