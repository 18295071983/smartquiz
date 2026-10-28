package com.oilquiz.app.ai.agent;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 引导界面用中文词典（不可变查找器）。
 *
 * <p>数据只有一个来源：{@code tool_guide_flow.json} 的 {@code terms} 段
 * （{@link ToolGuideFlowConfig.Terms}）。这里刻意<b>不内置</b>任何兜底词条——
 * 在 Java 里再塞一份硬编码词典，就变成两个权威，跟这次迁移要解决的问题正好相反。</p>
 *
 * <p>三层查找，先精确后宽松：</p>
 * <ol>
 *   <li>{@code byTool[工具][参数][值]} —— 同名值在不同工具里含义不同时用它压住全局档；</li>
 *   <li>{@code values[值]} —— 全局值级兜底；</li>
 *   <li>都没有 → 返回 {@code null}，调用方保持原文。</li>
 * </ol>
 *
 * <p>返回 null 而不是原文，是为了让"没译"这件事可统计
 * （见 {@link ToolGuideLabels#applyTo(ToolGuideFlow, ToolGuideTerms)} 的 untranslated 计数），
 * 而不是在词典层就把它混成"翻好了"。</p>
 */
public final class ToolGuideTerms {

    /** 空词典：所有查找返回 null，界面维持现状（原始值）。装载失败时就是这个档。 */
    public static final ToolGuideTerms EMPTY = new ToolGuideTerms(null, null, null);

    private final Map<String, String> params;
    private final Map<String, String> values;
    private final Map<String, Map<String, Map<String, String>>> byTool;

    private ToolGuideTerms(Map<String, String> params, Map<String, String> values,
                           Map<String, Map<String, Map<String, String>>> byTool) {
        this.params = params == null ? Collections.<String, String>emptyMap()
                : new HashMap<>(params);
        this.values = values == null ? Collections.<String, String>emptyMap()
                : new HashMap<>(values);
        this.byTool = byTool == null
                ? Collections.<String, Map<String, Map<String, String>>>emptyMap()
                : new HashMap<>(byTool);
    }

    /** 从配置构造；入参为 null 时等价于 {@link #EMPTY}。 */
    public static ToolGuideTerms fromConfig(ToolGuideFlowConfig.Terms source) {
        if (source == null) {
            return EMPTY;
        }
        return new ToolGuideTerms(source.params, source.values, source.byTool);
    }

    /**
     * 取某个参数值的中文显示名。
     *
     * @param toolName 工具名（可为 null，则跳过工具级）
     * @param paramKey 参数名
     * @param value    参数值原文
     * @return 中文名；查不到返回 null（调用方保持原文）
     */
    public String valueLabel(String toolName, String paramKey, String value) {
        if (value == null || value.isEmpty()) {
            // 空串是"不传该参数"的有意语义（如天气的自动定位），不该被当成一个可译的词
            return null;
        }
        if (toolName != null && paramKey != null) {
            Map<String, Map<String, String>> toolParams = byTool.get(toolName);
            if (toolParams != null) {
                Map<String, String> table = toolParams.get(paramKey);
                if (table != null) {
                    String hit = table.get(value);
                    if (hit != null && !hit.trim().isEmpty()) {
                        return hit;
                    }
                }
            }
        }
        String global = values.get(value);
        return global == null || global.trim().isEmpty() ? null : global;
    }

    /**
     * 取参数名的中文显示名。
     *
     * @return 中文名；查不到返回 null
     */
    public String paramLabel(String paramKey) {
        if (paramKey == null) {
            return null;
        }
        String hit = params.get(paramKey);
        return hit == null || hit.trim().isEmpty() ? null : hit;
    }

    public boolean isEmpty() {
        return params.isEmpty() && values.isEmpty() && byTool.isEmpty();
    }

    /** 值级词条总数（工具级 + 全局），供装载日志观测覆盖度。 */
    public int valueTermCount() {
        int count = values.size();
        for (Map<String, Map<String, String>> toolParams : byTool.values()) {
            for (Map<String, String> table : toolParams.values()) {
                count += table.size();
            }
        }
        return count;
    }

    public int paramTermCount() {
        return params.size();
    }
}
