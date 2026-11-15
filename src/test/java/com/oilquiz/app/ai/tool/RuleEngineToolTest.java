package com.oilquiz.app.ai.tool;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertTrue;

/**
 * RuleEngineTool 单元测试（2026-11-16）。
 *
 * <p>easy-rules 是纯 JVM 实现、org.json 在单测里由 testImplementation 提供，
 * 所以整套"JSON 规则 + 事实 → 求值"链路可以在本地单测里跑，不用真机。</p>
 */
public class RuleEngineToolTest {

    @Test
    public void singleCondition_fires() {
        RuleEngineTool tool = new RuleEngineTool();
        Map<String, Object> args = new HashMap<>();
        args.put("rules", "[{\"name\":\"余额告警\",\"when\":{\"fact\":\"balance\",\"op\":\"lt\",\"value\":10},\"then\":\"余额偏低\"}]");
        args.put("facts", "{\"balance\":5}");
        AIToolResult r = tool.execute(args);
        assertTrue("应成功: " + r.getResult(), r.isSuccess());
        assertTrue("应命中: " + r.getResult(), String.valueOf(r.getResult()).contains("余额告警"));
    }

    @Test
    public void allCondition_requiresBoth() {
        RuleEngineTool tool = new RuleEngineTool();
        Map<String, Object> args = new HashMap<>();
        args.put("rules", "[{\"name\":\"降级建议\",\"when\":{\"all\":["
                + "{\"fact\":\"balance\",\"op\":\"lt\",\"value\":10},"
                + "{\"fact\":\"calls\",\"op\":\"gt\",\"value\":50}]},\"then\":\"建议降级\"}]");
        // 只满足一个条件 → 不应命中
        args.put("facts", "{\"balance\":5,\"calls\":10}");
        AIToolResult r1 = tool.execute(args);
        assertTrue(r1.isSuccess());
        assertTrue("不应命中: " + r1.getResult(),
                String.valueOf(r1.getResult()).contains("没有规则命中"));

        // 两个都满足 → 命中
        args.put("facts", "{\"balance\":5,\"calls\":80}");
        AIToolResult r2 = tool.execute(args);
        assertTrue(r2.isSuccess());
        assertTrue("应命中: " + r2.getResult(), String.valueOf(r2.getResult()).contains("降级建议"));
    }

    @Test
    public void anyCondition_firesOnOne() {
        RuleEngineTool tool = new RuleEngineTool();
        Map<String, Object> args = new HashMap<>();
        args.put("rules", "[{\"name\":\"异常\",\"when\":{\"any\":["
                + "{\"fact\":\"status\",\"op\":\"eq\",\"value\":\"error\"},"
                + "{\"fact\":\"retry\",\"op\":\"gt\",\"value\":3}]},\"then\":\"需要处理\"}]");
        args.put("facts", "{\"status\":\"error\",\"retry\":0}");
        AIToolResult r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertTrue("应命中: " + r.getResult(), String.valueOf(r.getResult()).contains("异常"));
    }

    @Test
    public void mvelExpression_fires() {
        RuleEngineTool tool = new RuleEngineTool();
        Map<String, Object> args = new HashMap<>();
        // 表达式条件：MVEL 求值，支持算术/逻辑/方法调用
        args.put("rules", "[{\"name\":\"组合条件\",\"when\":"
                + "{\"expr\":\"balance < 10 && calls > 50 && status.contains('error')\"},"
                + "\"then\":\"三重条件全中\"}]");
        args.put("facts", "{\"balance\":5,\"calls\":80,\"status\":\"error-500\"}");
        AIToolResult r = tool.execute(args);
        assertTrue("应成功: " + r.getResult(), r.isSuccess());
        assertTrue("表达式应命中: " + r.getResult(),
                String.valueOf(r.getResult()).contains("三重条件全中"));
    }

    @Test
    public void mvelExpression_notFiredWhenFalse() {
        RuleEngineTool tool = new RuleEngineTool();
        Map<String, Object> args = new HashMap<>();
        args.put("rules", "[{\"name\":\"组合条件\",\"when\":"
                + "{\"expr\":\"balance < 10 && calls > 50\"},\"then\":\"命中\"}]");
        args.put("facts", "{\"balance\":5,\"calls\":10}");
        AIToolResult r = tool.execute(args);
        assertTrue(r.isSuccess());
        assertTrue("条件不满足不应命中: " + r.getResult(),
                String.valueOf(r.getResult()).contains("没有规则命中"));
    }

    @Test
    public void badRules_fails() {
        RuleEngineTool tool = new RuleEngineTool();
        Map<String, Object> args = new HashMap<>();
        args.put("rules", "not-json");
        AIToolResult r = tool.execute(args);
        assertTrue("坏 JSON 应失败", !r.isSuccess());
    }
}
