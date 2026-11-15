package com.oilquiz.app.ai.tool;

import org.jeasy.rules.api.Facts;
import org.jeasy.rules.api.Rules;
import org.jeasy.rules.core.BasicRule;
import org.jeasy.rules.core.DefaultRulesEngine;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则引擎工具（2026-11-16）：把 easy-rules 接成 Agent 可调用的能力。
 *
 * <p><b>为什么做这个</b>：项目里有大量手写规则判断（工具前置校验、引导流程、告警分级），
 * 以前只能改代码。接了 easy-rules 之后，Agent 可以<b>用数据描述规则</b>并立即求值 ——
 * 比如"余额低于 10 且今日调用超过 50 次 → 提示降级"，不需要发版。</p>
 *
 * <p><b>为什么自定义 Condition 而不是 SpEL/MVEL</b>：项目只引了
 * {@code easy-rules-core} + {@code easy-rules-support}，没有表达式引擎依赖（离线构建加不了）。
 * 所以这里用一个很小的 JSON 条件 DSL，自己实现 {@link org.jeasy.rules.api.Rule}，
 * 既能跑规则引擎的优先级/匹配语义，又不引入新依赖。</p>
 *
 * <p><b>规则 DSL</b>：</p>
 * <pre>
 * [{"name":"余额告警","priority":1,
 *   "when":{"all":[{"fact":"balance","op":"lt","value":10},
 *                  {"fact":"calls","op":"gt","value":50}]},
 *   "then":"余额偏低且调用频繁，建议降级"}]
 * </pre>
 * 条件支持 {@code all} / {@code any} / 单个条件；op 支持
 * {@code gt gte lt lte eq neq contains exists}。
 */
public class RuleEngineTool implements AITool {

    @Override
    public String getName() {
        return "rule_engine";
    }

    @Override
    public String getDescription() {
        return "规则引擎：用 JSON 描述条件规则并在给定事实上求值。"
                + "action: evaluate(求值,默认)。参数 rules 为规则数组、facts 为事实对象。"
                + "适合做告警分级、阈值判断、策略匹配，不需要改代码。";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("action", "操作：evaluate(求值，默认)");
        p.put("rules", "规则数组 JSON：[{name,priority?,when:{fact,op,value}|{all:[...]}|{any:[...]},then}]");
        p.put("facts", "事实对象 JSON：{\"balance\":5,\"calls\":80}");
        return p;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        String action = parameters.get("action") == null
                ? "evaluate" : String.valueOf(parameters.get("action"));
        if (!"evaluate".equals(action)) {
            return AIToolResult.fail("未知 action: " + action + "（可用：evaluate）");
        }
        String rulesJson = str(parameters, "rules");
        String factsJson = str(parameters, "facts");
        if (rulesJson == null || rulesJson.trim().isEmpty()) {
            return AIToolResult.fail("缺少参数: rules（规则数组 JSON）");
        }
        try {
            JSONArray ruleArray = new JSONArray(rulesJson);
            Facts facts = new Facts();
            if (factsJson != null && !factsJson.trim().isEmpty()) {
                JSONObject fo = new JSONObject(factsJson);
                for (java.util.Iterator<String> it = fo.keys(); it.hasNext(); ) {
                    String k = it.next();
                    facts.put(k, fo.get(k));
                }
            }
            Rules rules = new Rules();
            for (int i = 0; i < ruleArray.length(); i++) {
                JSONObject ro = ruleArray.getJSONObject(i);
                rules.register(buildRule(ro, i + 1));
            }
            new DefaultRulesEngine().fire(rules, facts);

            List<Map<String, Object>> fired = new ArrayList<>();
            StringBuilder sb = new StringBuilder();
            for (RuleOutcome o : OUTCOMES.get()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("rule", o.name);
                row.put("message", o.message);
                fired.add(row);
                sb.append("· ").append(o.name).append(" → ").append(o.message).append("\n");
            }
            OUTCOMES.remove();
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("fired", fired);
            info.put("firedCount", fired.size());
            info.put("total", ruleArray.length());
            String summary = fired.isEmpty()
                    ? "没有规则命中（共 " + ruleArray.length() + " 条）"
                    : "命中 " + fired.size() + "/" + ruleArray.length() + " 条：\n" + sb.toString().trim();
            return AIToolResult.success(summary, info);
        } catch (Exception e) {
            OUTCOMES.remove();
            return AIToolResult.fail("规则求值失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 规则实现
    // ------------------------------------------------------------------

    /** 命中结果（用 ThreadLocal 收集，便于动作里拿到规则名与消息） */
    private static final class RuleOutcome {
        final String name;
        final String message;

        RuleOutcome(String name, String message) {
            this.name = name;
            this.message = message;
        }
    }

    private static final ThreadLocal<List<RuleOutcome>> OUTCOMES =
            ThreadLocal.withInitial(ArrayList::new);

    private org.jeasy.rules.api.Rule buildRule(JSONObject ro, int index) throws Exception {
        String name = ro.optString("name", "规则" + index);
        int priority = ro.optInt("priority", index);
        final JSONObject when = ro.optJSONObject("when");
        final String message = ro.optString("then", name + " 命中");
        if (when == null) {
            throw new IllegalArgumentException("规则 " + name + " 缺少 when 条件");
        }

        BasicRule rule = new BasicRule(name, "JSON 条件规则", priority) {
            @Override
            public boolean evaluate(Facts facts) {
                return evalCondition(when, facts);
            }

            @Override
            public void execute(Facts facts) {
                OUTCOMES.get().add(new RuleOutcome(name, message));
            }
        };
        return rule;
    }

    /** 递归求值：all / any / 单条件 */
    private static boolean evalCondition(JSONObject cond, Facts facts) {
        JSONArray all = cond.optJSONArray("all");
        if (all != null) {
            for (int i = 0; i < all.length(); i++) {
                if (!evalCondition(all.optJSONObject(i), facts)) {
                    return false;
                }
            }
            return true;
        }
        JSONArray any = cond.optJSONArray("any");
        if (any != null) {
            for (int i = 0; i < any.length(); i++) {
                if (evalCondition(any.optJSONObject(i), facts)) {
                    return true;
                }
            }
            return false;
        }
        // 表达式条件（MVEL）：{"expr":"balance < 10 && calls > 50"}
        // 比结构化 DSL 强的地方：支持字符串方法、算术、三元、正则匹配等
        String expr = cond.optString("expr", null);
        if (expr != null && !expr.trim().isEmpty()) {
            try {
                Object v = org.mvel2.MVEL.eval(expr, facts.asMap());
                return v instanceof Boolean ? (Boolean) v : v != null;
            } catch (Throwable t) {
                throw new IllegalArgumentException("表达式求值失败: " + expr + " → " + t.getMessage());
            }
        }
        String fact = cond.optString("fact", null);
        String op = cond.optString("op", "exists");
        Object expected = cond.opt("value");
        if (fact == null) {
            return false;
        }
        Object actual = facts.get(fact);
        switch (op) {
            case "exists":
                return actual != null;
            case "eq":
                return actual != null && String.valueOf(actual).equals(String.valueOf(expected));
            case "neq":
                return actual == null || !String.valueOf(actual).equals(String.valueOf(expected));
            case "contains":
                return actual != null && String.valueOf(actual).contains(String.valueOf(expected));
            case "gt":
            case "gte":
            case "lt":
            case "lte":
                Double a = num(actual);
                Double b = num(expected);
                if (a == null || b == null) {
                    return false;
                }
                switch (op) {
                    case "gt": return a > b;
                    case "gte": return a >= b;
                    case "lt": return a < b;
                    default: return a <= b;
                }
            default:
                return false;
        }
    }

    private static Double num(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Throwable t) {
            return null;
        }
    }

    private static String str(Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
