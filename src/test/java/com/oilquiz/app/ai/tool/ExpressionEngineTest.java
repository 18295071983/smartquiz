package com.oilquiz.app.ai.tool;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * ExpressionEngine 单元测试（2026-11-16）。
 *
 * <p>重点验证三类行为：<b>求值正确</b>（算术/逻辑/字符串）、<b>缓存生效</b>、
 * <b>安全边界</b>（赋值/new/超长/系统类调用都必须被拒）。</p>
 */
public class ExpressionEngineTest {

    private Map<String, Object> vars() {
        Map<String, Object> v = new HashMap<>();
        v.put("balance", 5);
        v.put("calls", 80);
        v.put("status", "error-500");
        return v;
    }

    @Test
    public void evaluatesArithmeticAndLogic() {
        assertTrue(ExpressionEngine.evalBoolean("balance < 10 && calls > 50", vars()));
        assertFalse(ExpressionEngine.evalBoolean("balance > 10", vars()));
        assertEquals(85, ((Number) ExpressionEngine.eval("balance + calls", vars())).intValue());
    }

    @Test
    public void evaluatesStringOperations() {
        assertTrue(ExpressionEngine.evalBoolean("status.contains('error')", vars()));
        assertEquals("ERROR-500",
                ExpressionEngine.eval("status.toUpperCase()", vars()));
    }

    @Test
    public void cacheReusesCompiledExpression() {
        ExpressionEngine.clearCache();
        String expr = "balance + 1";
        ExpressionEngine.eval(expr, vars());
        ExpressionEngine.eval(expr, vars());
        assertEquals("同一表达式应只编译一次", 1, ExpressionEngine.cacheSize());
    }

    @Test
    public void rejectsAssignment() {
        try {
            ExpressionEngine.eval("balance = 10", vars());
            fail("赋值应被拒绝");
        } catch (IllegalArgumentException e) {
            assertTrue("错误信息应说明不允许赋值: " + e.getMessage(),
                    e.getMessage().contains("不允许赋值"));
        }
    }

    @Test
    public void rejectsDangerousConstructs() {
        for (String bad : new String[]{
                "new java.io.File('/tmp')",
                "Runtime.getRuntime()",
                "System.exit(0)",
                "this.getClass()"}) {
            try {
                ExpressionEngine.eval(bad, vars());
                fail("应拒绝: " + bad);
            } catch (IllegalArgumentException e) {
                // 期望：被安全边界拦下
            }
        }
    }

    @Test
    public void rejectsTooLongExpression() {
        StringBuilder sb = new StringBuilder("balance");
        while (sb.length() <= 2000) {
            sb.append(" + 1");
        }
        try {
            ExpressionEngine.eval(sb.toString(), vars());
            fail("超长表达式应被拒绝");
        } catch (IllegalArgumentException e) {
            assertTrue("应提示过长: " + e.getMessage(), e.getMessage().contains("过长"));
        }
    }

    @Test
    public void emptyExpressionRejected() {
        try {
            ExpressionEngine.eval("  ", vars());
            fail("空表达式应被拒绝");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("为空"));
        }
    }
}
