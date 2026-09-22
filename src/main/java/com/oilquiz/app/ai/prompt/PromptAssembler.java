package com.oilquiz.app.ai.prompt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 系统提示组装器 —— dsh {@code SystemPrompt} 服务的 Java 移植（核心机制，非 Cordis 全量）。
 * <p>
 * 机制对照：
 * <ul>
 *   <li><b>命名分段</b>：{@link PromptSection} 按 name 注册，order 升序 + name 字典序确定性排序；</li>
 *   <li><b>scope 覆盖</b>：注册可带 scope（模式），assemble(scope) 时 scoped 段覆盖 global 同名段（shadow），
 *       变量同理（scoped 优先）；</li>
 *   <li><b>动态上下文</b>：{@link PromptContext} 注册为独立快照段，渲染为"当前运行时上下文"消息；</li>
 *   <li><b>严格变量插值</b>：仅 {@code {{name}}}（name 匹配 {@code [a-z][a-z0-9_]*}），未知/畸形/无值抛错并带
 *       段名诊断；interpolate=false 的段保留原文；</li>
 *   <li><b>complete 段</b>：active 的 complete 段唯一，组装后恢复为唯一 prompt 段（contexts 照常保留）；</li>
 *   <li><b>注册返回 disposer</b>：所有 register 方法返回 {@link Runnable}，可幂等卸载。</li>
 * </ul>
 * 线程安全：注册/组装均持内部锁（注册频率极低，组装每轮一次，锁开销可忽略）。
 */
public final class PromptAssembler {

    /** 变量名校验：与 dsh 一致，小写字母开头 + 小写字母/数字/下划线 */
    private static final java.util.regex.Pattern VARIABLE_NAME =
            java.util.regex.Pattern.compile("^[a-z][a-z0-9_]*$");
    /** 匹配一个完整 {@code {{name}}} 组 */
    private static final java.util.regex.Pattern GROUP_AT =
            java.util.regex.Pattern.compile("^\\{\\{([^{}]*)\\}\\}");

    private final Object lock = new Object();

    /** name -> section（global 层） */
    private final Map<String, PromptSection> globalSections = new TreeMap<>();
    /** scope -> (name -> section) */
    private final Map<String, Map<String, PromptSection>> scopedSections = new HashMap<>();
    /** name -> context（global 层） */
    private final Map<String, PromptContext> globalContexts = new TreeMap<>();
    /** scope -> (name -> context) */
    private final Map<String, Map<String, PromptContext>> scopedContexts = new HashMap<>();
    /** name -> provider（global 层；scoped 同名覆盖 global） */
    private final Map<String, VariableProvider> globalVariables = new HashMap<>();
    private final Map<String, Map<String, VariableProvider>> scopedVariables = new HashMap<>();

    /** 变量提供者：返回 null 表示"本组装无值"（引用它的段渲染时报错） */
    public interface VariableProvider {
        String value(AssembleContext ctx);
    }

    // ==================== 注册（返回 disposer） ====================

    /** 注册命名段；同层（global 或同 scope）重复名抛错。返回可幂等卸载的 disposer。 */
    public Runnable registerSection(PromptSection section) {
        return registerSection(section, null);
    }

    /** 注册命名段到指定 scope；null scope = 全局。scoped 段覆盖 global 同名段。 */
    public Runnable registerSection(PromptSection section, String scope) {
        if (section == null) throw new IllegalArgumentException("section must not be null");
        synchronized (lock) {
            Map<String, PromptSection> map = layer(scope, true);
            if (map.containsKey(section.name)) {
                throw new IllegalArgumentException("prompt section \"" + section.name + "\" is already registered"
                        + (scope == null ? "" : " in scope \"" + scope + "\""));
            }
            map.put(section.name, section);
            return disposer(() -> unregister(section.name, scope, true));
        }
    }

    /** 注册动态上下文段；同层重复名抛错。 */
    public Runnable registerContext(PromptContext context) {
        return registerContext(context, null);
    }

    /** 注册动态上下文段到指定 scope。 */
    public Runnable registerContext(PromptContext context, String scope) {
        if (context == null) throw new IllegalArgumentException("context must not be null");
        synchronized (lock) {
            Map<String, PromptContext> map = ctxLayer(scope, true);
            if (map.containsKey(context.name)) {
                throw new IllegalArgumentException("prompt context \"" + context.name + "\" is already registered"
                        + (scope == null ? "" : " in scope \"" + scope + "\""));
            }
            map.put(context.name, context);
            return disposer(() -> unregisterCtx(context.name, scope, true));
        }
    }

    /** 注册变量提供者；scoped 同名覆盖 global。 */
    public Runnable registerVariable(String name, VariableProvider provider) {
        return registerVariable(name, provider, null);
    }

    /** 注册变量提供者到指定 scope。 */
    public Runnable registerVariable(String name, VariableProvider provider, String scope) {
        if (name == null || !VARIABLE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid prompt variable name \"" + name
                    + "\" (must match " + VARIABLE_NAME.pattern() + ")");
        }
        if (provider == null) throw new IllegalArgumentException("provider must not be null");
        synchronized (lock) {
            Map<String, VariableProvider> map = varLayer(scope, true);
            map.put(name, provider);
            return disposer(() -> {
                synchronized (lock) {
                    Map<String, VariableProvider> m = varLayer(scope, false);
                    if (m != null) m.remove(name);
                }
            });
        }
    }

    // ==================== 组装 ====================

    /**
     * 组装：scope 链收集（global 全部 + 匹配 scope 层）→ 同名覆盖 → order 排序 → 求值 → 校验 complete 唯一。
     * @param ctx 组装上下文（scope 决定哪些 scoped 段参与）
     * @return 组装结果（未插值；渲染时才插值）
     */
    public PromptAssembly assemble(AssembleContext ctx) {
        AssembleContext c = ctx == null ? AssembleContext.global() : ctx;
        synchronized (lock) {
            // 变量：global 先，scope 链后（后者覆盖前者）。
            // 值为 null 也保留在 map 中（区别于"未注册"），渲染时引用会报"has no value"
            Map<String, String> variables = new HashMap<>();
            for (Map.Entry<String, VariableProvider> e : globalVariables.entrySet()) {
                variables.put(e.getKey(), e.getValue().value(c));
            }
            for (Map.Entry<String, Map<String, VariableProvider>> layer : scopedVariables.entrySet()) {
                if (!c.matches(layer.getKey())) continue;
                for (Map.Entry<String, VariableProvider> e : layer.getValue().entrySet()) {
                    variables.put(e.getKey(), e.getValue().value(c));
                }
            }

            // 段：global + 匹配 scope，scope 同名覆盖 global
            Map<String, PromptSection> merged = new TreeMap<>(globalSections);
            for (Map.Entry<String, Map<String, PromptSection>> layer : scopedSections.entrySet()) {
                if (!c.matches(layer.getKey())) continue;
                merged.putAll(layer.getValue());
            }
            List<PromptSection> ordered = new ArrayList<>(merged.values());
            ordered.sort((a, b) -> a.order != b.order ? Integer.compare(a.order, b.order)
                    : a.name.compareTo(b.name));

            List<PromptSection> completeList = new ArrayList<>();
            for (PromptSection s : ordered) if (s.complete) completeList.add(s);
            if (completeList.size() > 1) {
                throw new IllegalStateException("multiple complete prompt sections are active: "
                        + completeList.stream().map(s -> "\"" + s.name + "\"").collect(java.util.stream.Collectors.joining(", ")));
            }
            PromptSection complete = completeList.isEmpty() ? null : completeList.get(0);

            List<PromptAssembly.Section> sections = new ArrayList<>();
            for (PromptSection s : ordered) {
                sections.add(new PromptAssembly.Section(s.name, s.resolve(c), s.interpolate));
            }
            if (complete != null) {
                sections.clear();
                sections.add(new PromptAssembly.Section(complete.name, complete.resolve(c), complete.interpolate));
            }

            // 上下文：global + 匹配 scope
            Map<String, PromptContext> ctxMerged = new TreeMap<>(globalContexts);
            for (Map.Entry<String, Map<String, PromptContext>> layer : scopedContexts.entrySet()) {
                if (!c.matches(layer.getKey())) continue;
                ctxMerged.putAll(layer.getValue());
            }
            List<PromptContext> ctxOrdered = new ArrayList<>(ctxMerged.values());
            ctxOrdered.sort((a, b) -> a.order != b.order ? Integer.compare(a.order, b.order)
                    : a.name.compareTo(b.name));
            List<PromptAssembly.Ctx> contexts = new ArrayList<>();
            for (PromptContext p : ctxOrdered) {
                contexts.add(new PromptAssembly.Ctx(p.name, p.resolve(c)));
            }
            return new PromptAssembly(sections, contexts, variables);
        }
    }

    // ==================== 渲染 ====================

    /**
     * 渲染系统提示：插值（interpolate=false 的段保留原文）→ 过滤空段 → 空行连接。
     * @return 渲染后的 prompt；全部为空时返回空串
     */
    public static String render(PromptAssembly assembly) {
        StringBuilder sb = new StringBuilder();
        for (PromptAssembly.Section s : assembly.sections) {
            String text = s.interpolate ? interpolate(s.name, s.text, assembly) : s.text;
            if (text == null || text.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(text.trim());
        }
        return sb.toString();
    }

    /**
     * 渲染动态上下文快照：与 dsh 的 {@code joinContextSections} 同款前缀语义
     * （"本快照取代更早的运行时上下文快照"），空内容返回空串。
     */
    public static String renderContextSnapshot(PromptAssembly assembly) {
        StringBuilder body = new StringBuilder();
        for (PromptAssembly.Ctx c : assembly.contexts) {
            String text = interpolate("ctx:" + c.name, c.text, assembly);
            if (text == null || text.trim().isEmpty()) continue;
            if (body.length() > 0) body.append("\n\n");
            body.append(text.trim());
        }
        if (body.length() == 0) return "";
        return "当前运行时上下文。此快照取代更早的运行时上下文快照。\n\n" + body;
    }

    /** 严格插值：畸形引用（含裸 {{）、未知变量、无值变量均抛错并带段名诊断；interpolate=false 由调用方跳过 */
    private static String interpolate(String owner, String text, PromptAssembly assembly) {
        if (text == null) return "";
        String result = "";
        int last = 0;
        for (int open = text.indexOf("{{", last); open >= 0; open = text.indexOf("{{", last)) {
            java.util.regex.Matcher m = GROUP_AT.matcher(text.substring(open));
            if (!m.find()) {
                // 后续有 }} 即畸形；否则当字面正文
                if (text.indexOf("}}", open + 2) >= 0) {
                    throw new IllegalStateException("malformed prompt variable reference at \""
                            + text.substring(open, Math.min(open + 16, text.length())) + "…\" in \"" + owner + "\"");
                }
                result += text.substring(last, open + 2);
                last = open + 2;
                continue;
            }
            String name = m.group(1);
            if (!VARIABLE_NAME.matcher(name).matches()) {
                throw new IllegalStateException("malformed prompt variable reference \"{{" + name
                        + "}}\" in \"" + owner + "\" (variable names match " + VARIABLE_NAME.pattern() + ")");
            }
            if (!assembly.variables.containsKey(name)) {
                throw new IllegalStateException("unknown prompt variable \"{{" + name + "}}\" in \"" + owner
                        + "\"; registered variables: " + assembly.variables.keySet());
            }
            String value = assembly.variables.get(name);
            if (value == null) {
                throw new IllegalStateException("prompt variable \"{{" + name + "}}\" has no value for this assembly (\""
                        + owner + "\")");
            }
            result += text.substring(last, open) + value;
            last = open + m.group(0).length();
        }
        return result + text.substring(last);
    }

    // ==================== 内部 ====================

    private Map<String, PromptSection> layer(String scope, boolean create) {
        if (scope == null) return globalSections;
        Map<String, PromptSection> m = scopedSections.get(scope);
        if (m == null && create) {
            m = new TreeMap<>();
            scopedSections.put(scope, m);
        }
        return m;
    }

    private Map<String, PromptContext> ctxLayer(String scope, boolean create) {
        if (scope == null) return globalContexts;
        Map<String, PromptContext> m = scopedContexts.get(scope);
        if (m == null && create) {
            m = new TreeMap<>();
            scopedContexts.put(scope, m);
        }
        return m;
    }

    private Map<String, VariableProvider> varLayer(String scope, boolean create) {
        if (scope == null) return globalVariables;
        Map<String, VariableProvider> m = scopedVariables.get(scope);
        if (m == null && create) {
            m = new HashMap<>();
            scopedVariables.put(scope, m);
        }
        return m;
    }

    private void unregister(String name, String scope, boolean fromDisposer) {
        synchronized (lock) {
            Map<String, PromptSection> map = layer(scope, false);
            if (map != null) map.remove(name);
        }
    }

    private void unregisterCtx(String name, String scope, boolean fromDisposer) {
        synchronized (lock) {
            Map<String, PromptContext> map = ctxLayer(scope, false);
            if (map != null) map.remove(name);
        }
    }

    private static Runnable disposer(Runnable r) {
        return () -> { try { r.run(); } catch (Throwable ignored) { } };
    }
}
