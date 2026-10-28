package com.oilquiz.app.ai.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * 把配置态的 {@link ToolGuideFlowConfig.ToolFlow} 转成运行时
 * {@link ToolGuideFlow}（PoC 档，由 {@link ToolGuideFlowFlag} 门控）。
 *
 * <p><b>转换严格按 {@code GuideStep} 各类型工厂方法的字段子集来</b>，不额外多设字段。
 * 这不是洁癖：工厂方法本身字段覆盖不一致（例如 {@code imagePickerStep} 会把
 * {@code mimeTypes} 强制成 {@code {"image/*"}}、{@code directoryPickerStep} 根本不设
 * {@code mimeTypes}/{@code allowMultiple}、{@code optionStep} 根本不设 {@code hint}/
 * {@code required}），转换器若"顺手都填上"，同一份配置就会产出现行 builder 不会产出的字段组合，
 * 而渲染层是按这些字段分支的。等价性由 {@code ToolGuideFlowConverterTest}
 * 用逐字段 diff 钉住。</p>
 *
 * <p><b>每次调用都新建对象</b>。配置可以缓存，流程不行——
 * {@link ToolGuideFlow.GuideStep#paramValue} 是运行时槽位，用户每选一步就由
 * AIChatActivity 往当前步骤对象上写值（{@code :2300-2304}、{@code :2363}）。
 * 缓存流程实例等于让两次引导共享同一批步骤对象，后开的那个会看见前一个的已选值。</p>
 *
 * <p>配置里出现的类型以外的字段（如 {@code OPTION} 上的 {@code dynamicOptions}）
 * 一律忽略，与工厂方法保持同形；这类写法在校验器里是 WARN，不会被静默接受。</p>
 */
public final class ToolGuideFlowConverter {

    private ToolGuideFlowConverter() {
    }

    /**
     * 在配置里找指定工具的引导流程。
     *
     * @return 新建的流程实例；配置为 null 或没有这个工具时返回 null
     *         （调用方据此回退到硬编码 builder）
     */
    public static ToolGuideFlow findToolFlow(ToolGuideFlowConfig config, String toolName) {
        if (config == null || config.tools == null || toolName == null) {
            return null;
        }
        for (ToolGuideFlowConfig.ToolFlow flow : config.tools) {
            if (flow != null && toolName.equals(flow.toolName)) {
                return toFlow(flow);
            }
        }
        return null;
    }

    /** 单个工具流程 → 运行时流程（字段与 {@link ToolGuideFlow} 的 builder 同形）。 */
    public static ToolGuideFlow toFlow(ToolGuideFlowConfig.ToolFlow src) {
        if (src == null) {
            return null;
        }
        ToolGuideFlow flow = new ToolGuideFlow(src.toolName, src.toolDisplayName,
                src.toolDescription, toSteps(src.steps));
        if (src.contextDependencies != null) {
            flow.contextDependencies = new ArrayList<>(src.contextDependencies);
        }
        return flow;
    }

    /** 步骤列表转换；无法识别类型的步骤被跳过（校验器已在上游按 ERROR 拦掉）。 */
    public static List<ToolGuideFlow.GuideStep> toSteps(List<ToolGuideFlowConfig.Step> steps) {
        List<ToolGuideFlow.GuideStep> out = new ArrayList<>();
        if (steps == null) {
            return out;
        }
        for (ToolGuideFlowConfig.Step step : steps) {
            ToolGuideFlow.GuideStep converted = toStep(step);
            if (converted != null) {
                out.add(converted);
            }
        }
        return out;
    }

    /**
     * 单步转换。分支结构与 {@code GuideStep} 的工厂方法一一对应，
     * 每个分支只传该工厂接受的参数。
     */
    public static ToolGuideFlow.GuideStep toStep(ToolGuideFlowConfig.Step src) {
        if (src == null || src.type == null) {
            return null;
        }
        String conditionKey = src.condition == null ? null : src.condition.key;
        String conditionValue = src.condition == null
                ? null
                : joinValues(src.condition.values);
        ToolGuideFlow.GuideStep.StepType type;
        try {
            type = ToolGuideFlow.GuideStep.StepType.valueOf(src.type.trim());
        } catch (IllegalArgumentException e) {
            android.util.Log.w("ToolGuideFlow",
                    "忽略无法识别的步骤类型: " + src.type + " (paramKey=" + src.paramKey + ")");
            return null;
        }
        switch (type) {
            case OPTION:
                return ToolGuideFlow.GuideStep.optionStep(src.title, src.description, src.paramKey,
                        toOptions(src.options), conditionKey, conditionValue);
            case INPUT:
                return ToolGuideFlow.GuideStep.inputStep(src.title, src.description, src.paramKey,
                        src.hint, bool(src.required), bool(src.multiline),
                        conditionKey, conditionValue, toDynamicOptions(src.dynamicOptions));
            case FILE_PICKER:
                return ToolGuideFlow.GuideStep.filePickerStep(src.title, src.description, src.paramKey,
                        bool(src.required), toMimeTypes(src.mimeTypes), bool(src.allowMultiple),
                        conditionKey, conditionValue);
            case IMAGE_PICKER:
                // mimeTypes 由工厂强制成 image/*，配置里写了也不传进去——与现行行为一致
                return ToolGuideFlow.GuideStep.imagePickerStep(src.title, src.description, src.paramKey,
                        bool(src.required), bool(src.allowMultiple), conditionKey, conditionValue);
            case DIRECTORY_PICKER:
                return ToolGuideFlow.GuideStep.directoryPickerStep(src.title, src.description,
                        src.paramKey, bool(src.required), conditionKey, conditionValue);
            case CONFIRM:
                return ToolGuideFlow.GuideStep.confirmStep(src.title, src.description);
            default:
                return null;
        }
    }

    private static List<ToolGuideFlow.GuideStep.Option> toOptions(
            List<ToolGuideFlowConfig.Option> src) {
        if (src == null) {
            return null;
        }
        List<ToolGuideFlow.GuideStep.Option> out = new ArrayList<>();
        for (ToolGuideFlowConfig.Option option : src) {
            if (option == null) {
                continue;
            }
            out.add(new ToolGuideFlow.GuideStep.Option(option.label, option.value));
        }
        return out;
    }

    private static String[] toMimeTypes(List<String> src) {
        if (src == null) {
            return null;
        }
        return src.toArray(new String[0]);
    }

    private static ToolGuideFlow.GuideStep.DynamicOptionsSpec toDynamicOptions(
            ToolGuideFlowConfig.DynamicOptions src) {
        if (src == null) {
            return null;
        }
        return new ToolGuideFlow.GuideStep.DynamicOptionsSpec(src.toolName, src.action, src.listKey,
                src.itemField, src.allOptionLabel);
    }

    /**
     * 结构化 {@code values[]} 回填成 POJO 的 "|" 分隔串。
     *
     * <p>分隔符不会与真实值冲突：校验器规定 {@code condition.values} 里不允许空串，
     * 而本仓库的枚举值（{@code search}/{@code read_url}/{@code create}…）不含竖线。
     * {@link ToolGuideFlow#getActiveSteps(java.util.Map)} 用 {@code split("\|")} 解，
     * 顺序按配置里的顺序保留。</p>
     */
    private static String joinValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0) {
                sb.append("|");
            }
            sb.append(value);
        }
        return sb.toString();
    }

    /** Boolean 三态归一：没写＝false，与 builder 的默认值一致。 */
    private static boolean bool(Boolean value) {
        return Boolean.TRUE.equals(value);
    }
}
