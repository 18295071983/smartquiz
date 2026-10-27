package com.oilquiz.app.ai.tool;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * calculator 的函数支持与报错口径。
 *
 * <p>背景（真机日志）：模型写 {@code sqrt(1024)+12*3} 被拒，报的是"无法解析的字符: s"——
 * 既没说清楚支持什么，也没指路，模型只能换工具再试。这里钉住两件事：
 * 常用函数直接可用；不支持的函数/字符必须给出<b>可操作</b>的提示。</p>
 */
public class CalculatorToolTest {

    private static AIToolResult run(String expr) {
        Map<String, Object> p = new HashMap<>();
        p.put("expression", expr);
        return new CalculatorTool().execute(p);
    }

    private static String text(AIToolResult r) {
        Object v = r.getResult();
        return v == null ? r.getErrorMessage() : String.valueOf(v);
    }

    @Test
    public void functionsAndConstantsWork() {
        assertTrue(text(run("sqrt(1024)+12*3")).equals("68"));
        assertTrue(text(run("pow(2,10)")).equals("1024"));
        assertTrue(text(run("2^10")).equals("1024"));
        assertTrue(text(run("abs(-3)+max(1,2)+min(4,9)")).equals("9"));
        assertTrue(text(run("round(2.6)+floor(2.9)+ceil(2.1)")).equals("8"));
        // 三角函数/对数
        assertEquals(1.0, Double.parseDouble(text(run("sin(pi/2)"))), 1e-9);
        assertEquals(2.0, Double.parseDouble(text(run("log(100)"))), 1e-9);
        assertEquals(7.0, Double.parseDouble(text(run("ln(e^7)"))), 1e-6);
    }

    @Test
    public void unsupportedFunctionSaysWhatIsSupported() {
        AIToolResult r = run("cbrt(27)");
        assertFalse(r.isSuccess());
        String msg = r.getErrorMessage();
        assertTrue(msg, msg.contains("不支持的函数"));
        assertTrue("要指路到 python_calculate", msg.contains("python_calculate"));
        assertTrue("要列出支持的函数", msg.contains("sqrt"));
    }

    @Test
    public void unknownCharPointsToPythonInsteadOfCrypticLetter() {
        // 旧行为：无法解析的字符: s —— 模型不知道该怎么改
        AIToolResult r = run("2@3");
        assertFalse(r.isSuccess());
        String msg = r.getErrorMessage();
        assertTrue(msg, msg.contains("无法解析的字符"));
        assertTrue("要给出可用字符集", msg.contains("仅支持数字"));
        assertTrue("要指路", msg.contains("python_calculate"));
    }

    @Test
    public void bareFunctionNameWithoutParensSaysToAddThem() {
        // 去掉空白后 "sqrt 16" 会被读成标识符 sqrt16；提示必须告诉它加括号/写全
        AIToolResult r = run("sqrt 16");
        assertFalse(r.isSuccess());
        String msg = r.getErrorMessage();
        assertTrue(msg, msg.contains("未知的标识符"));
        assertTrue("要提示加括号", msg.contains("加括号") || msg.contains("sqrt(16)"));
    }

    @Test
    public void argumentAndDomainErrorsAreExplicit() {
        assertTrue(run("sqrt(-1)").getErrorMessage().contains("不能为负数"));
        assertTrue(run("log(0)").getErrorMessage().contains("必须大于 0"));
        assertTrue(run("pow(2)").getErrorMessage().contains("需要 2 个参数"));
        assertTrue(run("1/0").getErrorMessage().contains("除数为 0"));
    }
}
