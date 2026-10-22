package com.oilquiz.app.ai.python;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.oilquiz.app.ai.python.PythonToolManager.ExecutionResult;

/**
 * {@link PythonExecutionResultText} 的规则钉桩。
 *
 * <p>这个类是六个 Python 工具唯一的"模型可见文本"写者，之前是六份近似复制、各自漏字段。
 * 下面的用例逐条钉住"以前漏掉的东西"，防止哪天又漂回某个工具的私有实现里：
 * 尝试次数成功也要报、失败必须带 stderr、环境错误标记必须浮到文本上、
 * 自动修复要说明"实际跑的不是你提交的那份"。
 *
 * <p>纯 POJO 断言，不需要 Android/Chaquopy，JVM 单测直接跑。
 */
public class PythonExecutionResultTextTest {

    private static ExecutionResult ok() {
        ExecutionResult r = new ExecutionResult();
        r.success = true;
        r.attempts = 2;
        r.stdout = "hello\n";
        r.result = "42";
        return r;
    }

    private static ExecutionResult failed() {
        ExecutionResult r = new ExecutionResult();
        r.success = false;
        r.attempts = 3;
        r.error = "NameError: name 'x' is not defined";
        r.stderr = "Traceback (most recent call last): ...";
        r.stdout = "partially printed\n";
        return r;
    }

    private static Map<String, String> fix(String type, String summary) {
        Map<String, String> m = new HashMap<>();
        m.put("error_type", type);
        m.put("change_summary", summary);
        return m;
    }

    @Test
    public void fullSuccessKeepsSectionsAndAttempts() {
        ExecutionResult r = ok();
        String text = PythonExecutionResultText.render(r, "执行成功", "执行失败");
        assertTrue(text, text.startsWith("执行成功\n\n"));
        // 以前 python_execute 成功出口没有尝试次数，模型判断不了引擎替它试了几轮
        assertTrue(text, text.contains("尝试次数: 2"));
        assertTrue(text, text.contains("=== 输出 ===\nhello"));
        assertTrue(text, text.contains("=== 结果 ===\n42"));
    }

    @Test
    public void failureCarriesErrorStderrAndPartialOutput() {
        ExecutionResult r = failed();
        String text = PythonExecutionResultText.render(r, "执行成功", "执行失败");
        assertTrue(text, text.startsWith("执行失败\n\n"));
        assertTrue(text, text.contains("=== 错误 ==="));
        assertTrue(text, text.contains("NameError"));
        assertTrue(text, text.contains("=== 错误输出 ==="));
        assertTrue(text, text.contains("Traceback"));
        // 失败脚本已经 print 出来的部分不能扔
        assertTrue(text, text.contains("=== 部分输出 ===\npartially printed"));
        assertTrue(text, text.contains("尝试次数: 3"));
    }

    @Test
    public void compactSuccessPrintsAnswerAsIs() {
        ExecutionResult r = ok();
        String text = PythonExecutionResultText.render(r, "计算成功", "计算失败",
                PythonExecutionResultText.Style.COMPACT);
        assertTrue(text, text.startsWith("计算成功\n\n"));
        assertTrue(text, text.contains("hello"));
        // 算式的 stdout 就是答案：不能把同一个数字在两个段落里打两遍
        assertFalse(text, text.contains("=== 输出 ==="));
        assertFalse(text, text.contains("=== 结果 ==="));
    }

    @Test
    public void compactFallsBackToResultVariable() {
        ExecutionResult r = ok();
        r.stdout = "";
        String text = PythonExecutionResultText.render(r, "分析完成", "分析失败",
                PythonExecutionResultText.Style.COMPACT);
        assertTrue(text, text.contains("结果: 42"));
    }

    /** 回归钉：python_analyze_data 的旧失败出口只打 error，traceback 整个丢掉 */
    @Test
    public void compactFailureStillIncludesStderr() {
        ExecutionResult r = failed();
        String text = PythonExecutionResultText.render(r, "分析完成", "分析失败",
                PythonExecutionResultText.Style.COMPACT);
        assertTrue(text, text.contains("错误: NameError"));
        assertTrue(text, text.contains("错误输出: Traceback"));
    }

    /** 回归钉：run_code 的 environment_error 以前在 Java 解析时被丢掉，模型永远以为是自己代码错 */
    @Test
    public void environmentErrorIsSurfacedFirst() {
        ExecutionResult r = failed();
        r.environmentError = true;
        String text = PythonExecutionResultText.render(r, "执行成功", "执行失败");
        int warnAt = text.indexOf("执行器/环境问题");
        int errAt = text.indexOf("=== 错误 ===");
        assertTrue(text, warnAt >= 0);
        assertTrue("环境错误提示要顶在错误正文之前", warnAt < errAt);
        assertTrue(text, text.contains("不要靠反复改写脚本重试"));
    }

    @Test
    public void autoFixesAreReportedWithChangeSummary() {
        ExecutionResult r = ok();
        List<Map<String, String>> fixes = new ArrayList<>();
        fixes.add(fix("SyntaxError", "新增 1 行: import numpy as np"));
        fixes.add(fix("NameError", ""));
        r.fixes = fixes;
        String text = PythonExecutionResultText.render(r, "执行成功", "执行失败");
        assertTrue(text, text.contains("=== 自动修复 ==="));
        assertTrue("必须说清实际执行的不是提交的那份", text.contains("实际执行的代码不是你提交的那一份"));
        assertTrue(text, text.contains("SyntaxError → 新增 1 行: import numpy as np"));
        assertTrue("没有摘要的条目也不能漏报类型", text.contains("- NameError"));
    }

    @Test
    public void nullResultFailsCleanly() {
        String text = PythonExecutionResultText.render(null, "执行成功", "执行失败");
        assertTrue(text, text.startsWith("执行失败\n\n"));
        assertTrue(text, text.contains("没有拿到执行结果"));
    }

    @Test
    public void blankFixesProduceNoSection() {
        ExecutionResult r = ok();
        r.fixes = new ArrayList<>();
        String text = PythonExecutionResultText.render(r, "执行成功", "执行失败");
        assertFalse(text, text.contains("自动修复"));
    }

    @Test
    public void attemptsNeverNegative() {
        ExecutionResult r = ok();
        r.attempts = 0;
        String text = PythonExecutionResultText.render(r, "执行成功", "执行失败");
        assertEquals(true, text.contains("尝试次数: 0"));
    }
}
