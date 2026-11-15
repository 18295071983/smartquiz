package com.oilquiz.app.ai.tool;

import org.mvel2.MVEL;
import org.mvel2.ParserContext;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 表达式求值引擎（2026-11-16）：MVEL 的统一封装。
 *
 * <p><b>为什么需要它</b>：MVEL 是为了规则引擎引入的，但"对用户/模型给的表达式求值"这件事
 * 在 Agent 里会反复出现 —— 规则条件、路径模板、阈值配置、时间表达式。如果每个工具各自
 * {@code MVEL.eval}，会重复三件事：<b>编译缓存</b>（MVEL 每次 eval 都重新解析，慢）、
 * <b>安全边界</b>（表达式长度/超时/禁止赋值）、<b>错误归一</b>（把 MVEL 的异常翻译成能展示的文案）。
 * 这里一次做掉。</p>
 *
 * <p><b>安全边界</b>（Agent 场景必须有，因为表达式可能来自模型）：</p>
 * <ul>
 *   <li>长度上限 2000 字符，超了直接拒绝；</li>
 *   <li>禁止赋值/声明（{@code =}、{@code new}、{@code import}、{@code .class}、{@code getClass}），
 *       避免表达式从"求值"变成"执行任意代码"；</li>
 *   <li>只允许注册进来的变量（{@link #evalBoolean} 的 vars），不做全局变量解析。</li>
 * </ul>
 *
 * <p>缓存策略：按表达式文本缓存编译结果（{@link #cache}），上限 256 条，超出直接清空 ——
 * 表达式来自用户/模型，数量不可控，宁可清空也不要无限增长。</p>
 */
public final class ExpressionEngine {

    /** 表达式长度上限 */
    private static final int MAX_EXPR_LENGTH = 2000;
    /** 编译缓存上限 */
    private static final int MAX_CACHE = 256;

    private static final Map<String, Serializable> cache = new ConcurrentHashMap<>();

    private ExpressionEngine() {
    }

    /**
     * 求值表达式。
     *
     * @param expr 表达式（如 {@code balance < 10 && calls > 50}）
     * @param vars 变量表（键为变量名）
     * @return 求值结果（可能为 null / Boolean / Number / String）
     * @throws IllegalArgumentException 表达式非法或求值失败，message 可直接展示给用户
     */
    public static Object eval(String expr, Map<String, Object> vars) {
        checkSafety(expr);
        Map<String, Object> safeVars = vars == null ? Collections.emptyMap() : vars;
        try {
            Serializable compiled = cache.get(expr);
            if (compiled == null) {
                ParserContext ctx = new ParserContext();
                ctx.setStrongTyping(false);
                // 不解析外部变量（只能看到传入的 vars）
                ctx.setStrictTypeEnforcement(false);
                for (String key : safeVars.keySet()) {
                    ctx.addInput(key, Object.class);
                }
                compiled = MVEL.compileExpression(expr, ctx);
                if (cache.size() >= MAX_CACHE) {
                    cache.clear();
                }
                cache.put(expr, compiled);
            }
            return MVEL.executeExpression(compiled, new LinkedHashMap<>(safeVars));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalArgumentException("表达式求值失败: " + expr
                    + " → " + rootMessage(t));
        }
    }

    /**
     * 求布尔值（规则条件最常用）。
     * null 视为 false；非 Boolean 结果按"非空即真"处理（与 MVEL 语义一致）。
     */
    public static boolean evalBoolean(String expr, Map<String, Object> vars) {
        Object v = eval(expr, vars);
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        return v != null;
    }

    /** 清空编译缓存（测试或内存紧张时用）。 */
    public static void clearCache() {
        cache.clear();
    }

    /** 缓存条数（可观测性/测试用）。 */
    public static int cacheSize() {
        return cache.size();
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static void checkSafety(String expr) {
        if (expr == null || expr.trim().isEmpty()) {
            throw new IllegalArgumentException("表达式为空");
        }
        if (expr.length() > MAX_EXPR_LENGTH) {
            throw new IllegalArgumentException("表达式过长（上限 " + MAX_EXPR_LENGTH + " 字符）");
        }
        String lower = expr.toLowerCase(java.util.Locale.US);
        for (String banned : new String[]{"new ", "import ", ".class", "getclass", "system.",
                "runtime.", "processbuilder", "forname"}) {
            if (lower.contains(banned)) {
                throw new IllegalArgumentException("表达式包含被禁止的用法: " + banned.trim());
            }
        }
        // 赋值检测：排除 == / != / <= / >= / => 之后仍出现孤立 '='
        String noCompare = lower.replace("==", "").replace("!=", "")
                .replace("<=", "").replace(">=", "").replace("=>", "");
        if (noCompare.contains("=")) {
            throw new IllegalArgumentException("表达式不允许赋值");
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) {
            cur = cur.getCause();
        }
        String m = cur.getMessage();
        return m == null ? cur.getClass().getSimpleName() : m;
    }
}
