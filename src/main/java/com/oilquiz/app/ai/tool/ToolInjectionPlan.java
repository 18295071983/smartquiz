package com.oilquiz.app.ai.tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 一轮请求要注入哪些工具（<b>唯一口径</b>）。
 *
 * <p>背景：在线侧和原生侧各自决定过注入集，结果是同一次对话在两条路径上工具能力不同。
 * 2026-11-04 用户明确取向：<b>常驻核心 + 动态工具全量 + MCP 全量</b>，
 * <b>不要内置全量</b>（内置那 135 个不需要每轮都摊在模型面前，要用的时候
 * {@code tool_registry} 发现或代执行即可）。这条取向写在代码里而不是散在注释里，
 * 两个调用方共用这里，才不会一边改了另一边漂开。
 *
 * <p>顺序即注入顺序：核心段在最前（每轮都该先看见入口），动态工具与 MCP 各按名字排序跟在后面。
 * 分段排序换来的是"名单怎么变都确定"；代价也说清楚：<b>只有核心段的前缀稳定</b>——
 * 新增一个动态工具会把 MCP 段的下标往后推。取向是能力优先（用户明确"不要考虑成本"），
 * 所以不做二次重排去掩盖这件事，也不为了字节好看而少给能力。
 */
public final class ToolInjectionPlan {

    private ToolInjectionPlan() {
    }

    /**
     * @param core            常驻核心（按调用方给的顺序原样保留，可为 null）
     * @param dynamicNames    动态工具名（全量注入）
     * @param mcpNames        MCP 工具本地名（全量注入）
     * @param builtInNames    内置工具名（默认<b>不</b>注入）
     * @param injectBuiltIn   取向开关：{@code false}＝只要常驻+动态+MCP；{@code true}＝内置全量也进来
     * @return 去重后的注入顺序；null 输入按空集处理
     */
    public static List<String> select(Collection<String> core,
                                      Collection<String> dynamicNames,
                                      Collection<String> mcpNames,
                                      Collection<String> builtInNames,
                                      boolean injectBuiltIn) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        addAll(out, core);                       // 核心顺序不动：它是前缀的锚
        addSorted(out, dynamicNames);            // 动态按名排序：新建/删除一个不会打乱其余
        addSorted(out, mcpNames);
        if (injectBuiltIn) addSorted(out, builtInNames);
        return new ArrayList<>(out);
    }

    private static void addAll(LinkedHashSet<String> out, Collection<String> in) {
        if (in == null) return;
        for (String s : in) {
            if (s != null && !s.trim().isEmpty()) out.add(s.trim());
        }
    }

    private static void addSorted(LinkedHashSet<String> out, Collection<String> in) {
        if (in == null) return;
        List<String> names = new ArrayList<>();
        for (String s : in) {
            if (s != null && !s.trim().isEmpty()) names.add(s.trim());
        }
        Collections.sort(names);
        out.addAll(names);
    }

    /** 给日志用的一行分组摘要（模型可见数量与来源分栏，方便对账"这轮到底带了谁"）。 */
    public static String describe(Collection<String> core, Collection<String> dynamic,
                                  Collection<String> mcp, Collection<String> builtIn,
                                  boolean injectBuiltIn) {
        return "常驻=" + size(core) + " 动态=" + size(dynamic) + " MCP=" + size(mcp)
                + " 内置=" + (injectBuiltIn ? "全量" + size(builtIn) : "不注入(" + size(builtIn) + ")");
    }

    private static int size(Collection<String> c) {
        return c == null ? 0 : c.size();
    }
}
