package com.oilquiz.app.ai.agent;

import java.util.List;

/**
 * 把 {@link ToolGuideTerms} 应用到已构造好的引导流程上（只改显示名，不碰下发值）。
 *
 * <p><b>为什么只在动态兜底那条路上起作用</b>：{@link ToolGuideFlow#getFlow(String)} 里
 * 13 个手写 builder 的选项本来就有中文 label；而动态兜底
 * （{@code ToolGuideFlow:155}）是 {@code new GuideStep.Option(enumVal, enumVal)}——
 * label 直接等于枚举原文，所以用户在选项卡上看到的就是 {@code list}／{@code get}／{@code air_quality}。
 * 本类补的就是这一层。</p>
 *
 * <p><b>两条安全约束，测试逐条钉着</b>：</p>
 * <ol>
 *   <li>只在 {@code label} 与 {@code value} 完全相同（= 原始值直接露出）时才改写。
 *       这条守卫让已手写中文 label 的步骤绝不会被词典覆盖——即使某个值恰好在全局词条里
 *       （例如 {@code values["search"]="搜索"}），中文选项"搜一下"也不会被改成"搜索"。
 *       副作用是城市名这种 {@code ("银川","银川")} 的同值选项也不会被改（词典里没有"银川"，
 *       即便有也只会把它从"银川"改成另一个词，那种改写本来就该由手写引导负责）。</li>
 *   <li><b>{@code value} 永不修改。</b>下发给工具的必须是 schema 里的原文，
 *       翻译只作用于 {@code label}。改错 value 会让工具直接报"未知操作"。</li>
 * </ol>
 *
 * <p>查不到词条时保持原文并计数（宁可露出原始值，也不编一个可能错的名字）。
 * 计数只在动态生成的步骤上有意义——动态步骤按构造 label==value，
 * 所以 {@code untranslatedOptions} 就是"仍在露原始值"的条数。</p>
 */
public final class ToolGuideLabels {

    /** 一次应用的结果统计，供装载日志观测覆盖度。 */
    public static final class Result {
        /** 被改成中文的选项数 */
        public int translatedOptions;
        /** 仍是原始值（词典没覆盖）的选项数 */
        public int untranslatedOptions;
        /** 被改成中文的参数标题数（"填写 file_path" → "文件路径"） */
        public int translatedTitles;

        @Override
        public String toString() {
            return "translatedOptions=" + translatedOptions
                    + ", untranslatedOptions=" + untranslatedOptions
                    + ", translatedTitles=" + translatedTitles;
        }
    }

    private ToolGuideLabels() {
    }

    /**
     * 就地改写流程里各步骤的显示名。
     *
     * @param flow  待改流程（可为 null，null 时返回全零统计）
     * @param terms 词典（可为 null 或 {@link ToolGuideTerms#EMPTY}，此时什么都不改）
     * @return 改写统计
     */
    public static Result applyTo(ToolGuideFlow flow, ToolGuideTerms terms) {
        Result result = new Result();
        if (flow == null || terms == null || terms.isEmpty()) {
            return result;
        }
        applyToSteps(flow, flow.steps, terms, result);
        return result;
    }

    private static void applyToSteps(ToolGuideFlow flow, List<ToolGuideFlow.GuideStep> steps,
                                     ToolGuideTerms terms, Result result) {
        if (steps == null) {
            return;
        }
        for (ToolGuideFlow.GuideStep step : steps) {
            if (step == null) {
                continue;
            }
            if (step.type == ToolGuideFlow.GuideStep.StepType.OPTION) {
                applyToOptions(flow, step, terms, result);
            } else {
                applyToTitle(flow, step, terms, result);
            }
        }
    }

    private static void applyToOptions(ToolGuideFlow flow, ToolGuideFlow.GuideStep step,
                                       ToolGuideTerms terms, Result result) {
        if (step.options == null) {
            return;
        }
        for (ToolGuideFlow.GuideStep.Option option : step.options) {
            if (option == null || option.value == null) {
                continue;
            }
            boolean raw = option.value.equals(option.label);
            if (!raw) {
                continue; // 已有自己的显示名（手写 label），词典不覆盖
            }
            String hit = terms.valueLabel(flow.toolName, step.paramKey, option.value);
            if (hit == null) {
                result.untranslatedOptions++;
                continue;
            }
            option.label = hit;
            result.translatedOptions++;
        }
    }

    /**
     * 动态兜底给"描述为空的参数"起的标题是 {@code "填写 " + 参数名}
     * （{@code ToolGuideFlow:150}），露的就是原始字段名。有中文词条时换成中文。
     */
    private static void applyToTitle(ToolGuideFlow flow, ToolGuideFlow.GuideStep step,
                                     ToolGuideTerms terms, Result result) {
        if (step.paramKey == null || step.title == null) {
            return;
        }
        if (!step.title.equals("填写 " + step.paramKey)) {
            return;
        }
        String hit = terms.paramLabel(step.paramKey);
        if (hit == null) {
            return;
        }
        step.title = hit;
        result.translatedTitles++;
    }
}
