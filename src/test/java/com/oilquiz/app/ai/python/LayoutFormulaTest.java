package com.oilquiz.app.ai.python;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * 有界公式求值器（纯 JVM）。
 *
 * <p>合法：四则/括号/一元/数据路径/四个白名单函数；非法：任何白名单外的字符、函数、
 * 字符串、语句；越界：超长、超深、除零、缺路径、非数值。所有非法/越界都必须返回 null，
 * 由渲染层降级成 "—"，不能抛异常。</p>
 */
public class LayoutFormulaTest {

    @Test
    public void evaluatesArithmeticWithPrecedenceAndParens() {
        assertEquals(7d, eval("1 + 2 * 3"), 1e-9);
        assertEquals(9d, eval("(1 + 2) * 3"), 1e-9);
        assertEquals(-4d, eval("-(2 + 2)"), 1e-9);
        assertEquals(2.5d, eval("10 / 4"), 1e-9);
        assertEquals(0d, eval("+3 - 3"), 1e-9);
    }

    @Test
    public void resolvesDataPathsAndNumericStrings() {
        JSONObject data = data();
        assertEquals(6d, eval("data.a * data.b", data), 1e-9);
        assertEquals(6d, eval("a * b", data), 1e-9);
        assertEquals(4d, eval("items[1].price", data), 1e-9);
        assertEquals(12d, eval("data.priceText + 2", data), 1e-9);
        assertNull(LayoutFormula.evaluate("data.items", data)); // 数组不能当数值参与运算
    }

    @Test
    public void arrayAndMissingPathsAreRejectedInFormulas() {
        JSONObject data = data();
        assertNull(LayoutFormula.evaluate("items", data));          // 数组不是数值
        assertNull(LayoutFormula.evaluate("items[9].price", data)); // 越界
        assertNull(LayoutFormula.evaluate("data.missing + 1", data));
        assertNull(LayoutFormula.evaluate("data.name + 1", data));  // 非数字字符串
    }

    @Test
    public void supportsWhitelistedFunctionsOnly() {
        JSONObject data = data();
        assertEquals(3d, eval("min(3, 5)", data), 1e-9);
        assertEquals(5d, eval("max(3, 5)", data), 1e-9);
        assertEquals(3d, eval("round(2.6)", data), 1e-9);
        assertEquals(3d, eval("abs(-3)", data), 1e-9);
        assertEquals(7d, eval("max(min(3, 5), abs(-7))", data), 1e-9);
    }

    @Test
    public void rejectsUnknownFunctionsAndScriptLikeInput() {
        assertNull(LayoutFormula.evaluate("alert(1)", data()));
        assertNull(LayoutFormula.evaluate("java.lang.Runtime.getRuntime()", data()));
        assertNull(LayoutFormula.evaluate("a; b", data()));
        assertNull(LayoutFormula.evaluate("a = 1", data()));
        assertNull(LayoutFormula.evaluate("'abc'", data()));
        assertNull(LayoutFormula.evaluate("a ^ 2", data()));
        assertNull(LayoutFormula.evaluate("$data.a", data()));
        assertNull(LayoutFormula.evaluate("data.a[0", data()));
        assertNull(LayoutFormula.evaluate("", data()));
        assertNull(LayoutFormula.evaluate(null, data()));
    }

    @Test
    public void rejectsOutOfBoundsExpressions() {
        // 超长（>256 字符）
        StringBuilder longExpr = new StringBuilder();
        for (int i = 0; i < 300; i++) longExpr.append("1+");
        longExpr.append("1");
        assertNull(LayoutFormula.evaluate(longExpr.toString(), data()));

        // 超深括号（>24 层）
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 40; i++) deep.append('(');
        deep.append('1');
        for (int i = 0; i < 40; i++) deep.append(')');
        assertNull(LayoutFormula.evaluate(deep.toString(), data()));

        // 除零
        assertNull(LayoutFormula.evaluate("1 / 0", data()));
        assertNull(LayoutFormula.evaluate("1 / (2 - 2)", data()));
    }

    @Test
    public void formatsNumbersForDisplay() {
        assertEquals("7", LayoutFormula.format(7.0));
        assertEquals("2.5", LayoutFormula.format(2.5));
        assertEquals("3.33", LayoutFormula.format(10d / 3d));
        assertEquals("0", LayoutFormula.format(-0.0));
        assertEquals("—", LayoutFormula.format(Double.NaN));
    }

    @Test
    public void resolveDisplayCoversBindingFormulaAndPlainValues() {
        JSONObject data = data();
        JSONObject binding = object("$", "data.a");
        JSONObject formula = object("=", "a * b");
        JSONObject badFormula = object("=", "a ^ b");

        assertTrue(LayoutFormula.isBinding(binding));
        assertTrue(LayoutFormula.isFormula(formula));
        assertFalse(LayoutFormula.isFormula(binding));
        assertEquals("2", LayoutFormula.resolveDisplay(binding, data));
        assertEquals("6", LayoutFormula.resolveDisplay(formula, data));
        assertEquals("—", LayoutFormula.resolveDisplay(badFormula, data));
        assertEquals("", LayoutFormula.resolveDisplay(object("$", "data.nope"), data));
        assertEquals("文本", LayoutFormula.resolveDisplay("文本", data));
        assertEquals("3", LayoutFormula.resolveDisplay(3, data));
    }

    @Test
    public void resolvePathHandlesNestedObjectsAndArrays() {
        JSONObject data = data();
        assertEquals(2, ((Number) LayoutFormula.resolvePath(data, "data.a")).intValue());
        assertEquals(4, ((Number) LayoutFormula.resolvePath(data, "data.items[1].price")).intValue());
        assertEquals("x", LayoutFormula.resolvePath(data, "$.data.nested.name"));
        assertNull(LayoutFormula.resolvePath(data, "data.items[0].price"));
        assertNull(LayoutFormula.resolvePath(data, "bad..path"));
        assertNull(LayoutFormula.resolvePath(null, "data.a"));
    }

    private static double eval(String expr) {
        return eval(expr, new JSONObject());
    }

    private static double eval(String expr, JSONObject data) {
        Double value = LayoutFormula.evaluate(expr, data);
        if (value == null) return Double.NaN;
        return value;
    }

    private static JSONObject data() {
        try {
            JSONObject data = new JSONObject();
            data.put("a", 2);
            data.put("b", 3);
            data.put("priceText", "10");
            data.put("name", "abc");
            JSONArray items = new JSONArray();
            items.put(new JSONObject());
            items.put(new JSONObject().put("price", 4));
            data.put("items", items);
            data.put("nested", new JSONObject().put("name", "x"));
            return data;
        } catch (org.json.JSONException e) {
            throw new RuntimeException(e);
        }
    }

    private static JSONObject object(String key, String value) {
        try {
            return new JSONObject().put(key, value);
        } catch (org.json.JSONException e) {
            throw new RuntimeException(e);
        }
    }
}
