package com.oilquiz.app.ai.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 工具引导配置（{@link ToolGuideFlowConfig}）的校验器。
 *
 * <p><b>为什么需要它</b>：引导流程现在写在 {@link ToolGuideFlow} 的 builder 里，参数名写错
 * 编译就红。搬进 JSON 后没有这层保护——{@code "cit y"} 这种错会静默通过，运行时变成一步
 * 填不进参数的死步骤，而用户只会看到"点了没反应"。本校验器就是补上的那道编译期等价物。</p>
 *
 * <p>三档规则，严重度分 ERROR（装载方应据此拒绝该配置并回退）与 WARN（不挡运行，但要说清）：</p>
 * <ol>
 *   <li><b>结构完整性</b>：类型合法、必填字段在、OPTION 有选项、CONFIRM 不带参数。</li>
 *   <li><b>内部引用一致性</b>：{@code condition.key} 必须指向更早的 OPTION 步骤；
 *       同一 paramKey 的两个步骤条件值集必须不相交（重叠即"同一次运行里两个输入框抢一个参数"）。</li>
 *   <li><b>跨 schema 一致性</b>（仅在提供 {@link SchemaProbe} 时执行）：paramKey 必须存在于
 *       模型真正看到的那份 {@code AIToolManager.getToolDefinition()} 里；OPTION 的 value 必须落在
 *       该参数声明的枚举内。</li>
 * </ol>
 *
 * <p>第 3 档的枚举校验目前只能降级为 WARN：{@code ai_weather}/{@code network_search}/
 * {@code database}/{@code location} 在 {@code getToolDefinition} 里走硬编码 switch 分支，
 * 而那些分支没传 {@code enumValues}——枚举只写在 description 文案里。给这四个工具补上
 * {@code ParamDefinition.enumValues} 后，本校验器自动升级为硬校验，无需改这里。</p>
 *
 * <p>本类不依赖 Android，纯 JVM 可跑，既给运行时装载方用，也给单元测试用。</p>
 */
public final class ToolGuideFlowValidator {

    /** 问题严重度。 */
    public enum Severity {
        /** 必须修：装载方遇到 ERROR 应拒绝整份配置并回退到 builder/schema 兜底路径。 */
        ERROR,
        /** 不挡运行，但属于可疑配置或未验项。 */
        WARN
    }

    /** 单条问题。{@code location} 用 JSON 路径定位，便于直接改配置文件。 */
    public static final class Issue {
        public final Severity severity;
        /** 定位串，如 {@code tools[1].steps[2]} 或 {@code composites[go_out].steps[1].paramRefs[lat]} */
        public final String location;
        public final String message;

        Issue(Severity severity, String location, String message) {
            this.severity = severity;
            this.location = location;
            this.message = message;
        }

        @Override
        public String toString() {
            return severity + " " + location + ": " + message;
        }
    }

    /** 模型可见 schema 的某个参数：必填性 + 枚举（枚举为 null 表示未声明）。 */
    public static final class ParamSpec {
        public final boolean required;
        /** null = schema 没给这个参数声明枚举值 */
        public final Set<String> enumValues;

        public ParamSpec(boolean required, Set<String> enumValues) {
            this.required = required;
            this.enumValues = enumValues;
        }
    }

    /**
     * schema 探针：由装载方/测试注入，校验器借此读取"模型真正收到的那份工具定义"。
     * 做成接口是为了让本类不依赖 AIToolManager 与 Context，纯 JVM 也能跑单测。
     */
    public interface SchemaProbe {
        /**
         * 取指定工具在模型可见 schema 中的参数表。
         *
         * @return 参数名 → 参数约束；工具在 schema 里查不到时返回 null（校验器据此报 WARN）
         */
        Map<String, ParamSpec> paramsOf(String toolName);
    }

    /** 合法的步骤类型，取 {@link ToolGuideFlow.GuideStep.StepType} 枚举名原文。 */
    private static final Set<String> VALID_TYPES = new LinkedHashSet<>(Arrays.asList(
            "OPTION", "INPUT", "CONFIRM", "FILE_PICKER", "IMAGE_PICKER", "DIRECTORY_PICKER"));

    private static final Set<String> PICKER_TYPES = new HashSet<>(Arrays.asList(
            "FILE_PICKER", "IMAGE_PICKER", "DIRECTORY_PICKER"));

    /** {@code $prev.<字段>} 引用表达式，运行时由 ToolResultStore.resolveRef 解析。 */
    private static final Pattern REF_PATTERN =
            Pattern.compile("^\\$prev\\.[A-Za-z_][A-Za-z0-9_.]*$");

    private ToolGuideFlowValidator() {
    }

    /** 只做第 1、2 档（结构 + 内部引用），不查 schema。 */
    public static List<Issue> validate(ToolGuideFlowConfig config) {
        return validate(config, null);
    }

    /**
     * 全量校验。
     *
     * @param config 待验配置
     * @param probe  schema 探针；传 null 表示跳过第 3 档跨 schema 校验
     * @return 问题列表（无问题时为空列表，永不为 null）
     */
    public static List<Issue> validate(ToolGuideFlowConfig config, SchemaProbe probe) {
        List<Issue> issues = new ArrayList<>();
        if (config == null) {
            issues.add(new Issue(Severity.ERROR, "root", "配置为空（JSON 解析失败或内容为 null）"));
            return issues;
        }
        if (isBlank(config.version)) {
            issues.add(new Issue(Severity.ERROR, "root",
                    "缺少 version 字段；filesDir 覆盖档与随包档比较新旧要用它"));
        }
        boolean noTools = config.tools == null || config.tools.isEmpty();
        boolean noComposites = config.composites == null || config.composites.isEmpty();
        if (noTools && noComposites) {
            issues.add(new Issue(Severity.ERROR, "root",
                    "tools 与 composites 都是空的；这份配置不会接管任何引导"));
        }
        validateTerms(config.terms, issues);
        if (config.tools != null) {
            for (int i = 0; i < config.tools.size(); i++) {
                validateTool(config.tools.get(i), "tools[" + i + "]", probe, issues);
            }
            Set<String> names = new HashSet<>();
            for (int i = 0; i < config.tools.size(); i++) {
                ToolGuideFlowConfig.ToolFlow tool = config.tools.get(i);
                if (tool != null && !isBlank(tool.toolName) && !names.add(tool.toolName)) {
                    issues.add(new Issue(Severity.ERROR, "tools[" + i + "]",
                            "toolName=\"" + tool.toolName + "\" 重复；同一工具两份流程，装载时后一份会盖掉前一份"));
                }
            }
        }
        if (config.composites != null) {
            validateComposites(config.composites, probe, issues);
        }
        return issues;
    }

    /** 只取 ERROR，装载方据此决定是否拒绝配置。 */
    public static List<Issue> errors(List<Issue> issues) {
        List<Issue> out = new ArrayList<>();
        if (issues != null) {
            for (Issue issue : issues) {
                if (issue.severity == Severity.ERROR) {
                    out.add(issue);
                }
            }
        }
        return out;
    }

    /** 存在 ERROR 即应拒绝该配置。 */
    public static boolean hasErrors(List<Issue> issues) {
        return !errors(issues).isEmpty();
    }

    /** 只取 WARN。 */
    public static List<Issue> warnings(List<Issue> issues) {
        List<Issue> out = new ArrayList<>();
        if (issues != null) {
            for (Issue issue : issues) {
                if (issue.severity == Severity.WARN) {
                    out.add(issue);
                }
            }
        }
        return out;
    }

    /** 拼成多行可读文本，供 AILogger 落日志；无问题时返回 "no issues"。 */
    public static String render(List<Issue> issues) {
        if (issues == null || issues.isEmpty()) {
            return "no issues";
        }
        StringBuilder sb = new StringBuilder();
        for (Issue issue : issues) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(issue);
        }
        return sb.toString();
    }

    /** 按严重度过滤，供测试与日志用。 */
    public static List<Issue> withSeverity(List<Issue> issues, Severity severity) {
        List<Issue> out = new ArrayList<>();
        if (issues != null) {
            for (Issue issue : issues) {
                if (issue.severity == severity) {
                    out.add(issue);
                }
            }
        }
        return out;
    }

    // ==================== 术语表 ====================

    /**
     * 校验 {@code terms} 段。
     *
     * <p>已知局限：Gson 把 JSON 里重复的 key 静默按"最后一个赢"合并，落到 Map 后就看不出来了，
     * 所以 {@code {"list":"列索引","list":"列出"}} 这种手滑本校验器查不到。
     * 要查得在装载前用 JsonReader 逐 token 扫一遍，本阶段没做。</p>
     */
    private static void validateTerms(ToolGuideFlowConfig.Terms terms, List<Issue> issues) {
        if (terms == null) {
            return; // 没有术语表是合法档：动态兜底维持原始字段名
        }
        if (terms.params != null) {
            for (Map.Entry<String, String> entry : terms.params.entrySet()) {
                String loc = "terms.params[" + entry.getKey() + "]";
                if (isBlank(entry.getKey())) {
                    issues.add(new Issue(Severity.ERROR, "terms.params", "参数名词典有条目的 key 为空，永远不会被查到"));
                }
                if (isBlank(entry.getValue())) {
                    issues.add(new Issue(Severity.ERROR, loc, "中文名为空；查到了却显示空白比露出原始字段名更糟"));
                } else if (entry.getKey() != null && entry.getKey().equals(entry.getValue())) {
                    issues.add(new Issue(Severity.WARN, loc, "中文名与字段名相同，等于没翻"));
                }
            }
        }
        if (terms.values != null) {
            for (Map.Entry<String, String> entry : terms.values.entrySet()) {
                String loc = "terms.values[" + entry.getKey() + "]";
                if (isBlank(entry.getKey())) {
                    issues.add(new Issue(Severity.ERROR, "terms.values",
                            "值词典有条目的 key 是空串；空串表示\"不传该参数\"，本就不该被翻译"));
                }
                if (isBlank(entry.getValue())) {
                    issues.add(new Issue(Severity.ERROR, loc, "中文名为空；查到了却显示空白比露出原始字段名更糟"));
                } else if (entry.getKey() != null && entry.getKey().equals(entry.getValue())) {
                    issues.add(new Issue(Severity.WARN, loc, "中文名与原始值相同，等于没翻"));
                }
            }
        }
        if (terms.byTool == null) {
            return;
        }
        for (Map.Entry<String, Map<String, Map<String, String>>> toolEntry : terms.byTool.entrySet()) {
            String toolLoc = "terms.byTool[" + toolEntry.getKey() + "]";
            if (isBlank(toolEntry.getKey())) {
                issues.add(new Issue(Severity.ERROR, "terms.byTool", "工具名为空，这层词条永远不会被查到"));
                continue;
            }
            Map<String, Map<String, String>> toolParams = toolEntry.getValue();
            if (toolParams == null || toolParams.isEmpty()) {
                issues.add(new Issue(Severity.WARN, toolLoc, "该工具下没有任何词条"));
                continue;
            }
            for (Map.Entry<String, Map<String, String>> paramEntry : toolParams.entrySet()) {
                String paramLoc = toolLoc + "[" + paramEntry.getKey() + "]";
                if (isBlank(paramEntry.getKey())) {
                    issues.add(new Issue(Severity.ERROR, toolLoc, "参数名为空，这层词条永远不会被查到"));
                    continue;
                }
                Map<String, String> table = paramEntry.getValue();
                if (table == null || table.isEmpty()) {
                    issues.add(new Issue(Severity.WARN, paramLoc, "该参数下没有任何词条"));
                    continue;
                }
                for (Map.Entry<String, String> valueEntry : table.entrySet()) {
                    String valueLoc = paramLoc + "[" + valueEntry.getKey() + "]";
                    if (isBlank(valueEntry.getKey())) {
                        issues.add(new Issue(Severity.ERROR, paramLoc,
                                "值词典有条目的 key 是空串；空串表示\"不传该参数\"，本就不该被翻译"));
                    }
                    if (isBlank(valueEntry.getValue())) {
                        issues.add(new Issue(Severity.ERROR, valueLoc,
                                "中文名为空；查到了却显示空白比露出原始字段名更糟"));
                    } else if (valueEntry.getKey() != null && valueEntry.getKey().equals(valueEntry.getValue())) {
                        issues.add(new Issue(Severity.WARN, valueLoc, "中文名与原始值相同，等于没翻"));
                    }
                }
            }
        }
    }

    // ==================== 第 1、2 档：单工具流程 ====================

    private static void validateTool(ToolGuideFlowConfig.ToolFlow tool, String path,
                                     SchemaProbe probe, List<Issue> issues) {
        if (tool == null) {
            issues.add(new Issue(Severity.ERROR, path, "工具流程为 null"));
            return;
        }
        String loc = isBlank(tool.toolName) ? path : path + "[" + tool.toolName + "]";
        if (isBlank(tool.toolName)) {
            issues.add(new Issue(Severity.ERROR, loc, "toolName 为空，无法与 AIToolManager 注册名对上"));
        }
        if (isBlank(tool.toolDisplayName)) {
            issues.add(new Issue(Severity.WARN, loc, "toolDisplayName 为空，引导面板顶部会显示空白"));
        }
        List<ToolGuideFlowConfig.Step> steps = tool.steps;
        if (steps == null || steps.isEmpty()) {
            issues.add(new Issue(Severity.ERROR, loc, "steps 为空，该工具没有可展示的引导步骤"));
            return;
        }

        Map<String, ParamSpec> schemaParams = null;
        if (probe != null && !isBlank(tool.toolName)) {
            schemaParams = probe.paramsOf(tool.toolName);
            if (schemaParams == null) {
                issues.add(new Issue(Severity.WARN, loc,
                        "schema 里查不到工具 \"" + tool.toolName + "\"（未注册或 getToolDefinition 返回 null）；"
                                + "paramKey 与枚举本轮未经校验"));
            }
        }

        // paramKey → 该步骤的条件值集（null 表示无条件=通配），用于重叠检测
        Map<String, Set<String>> paramKeyConditions = new LinkedHashMap<>();
        // 已出现过的 OPTION 参数名，供 condition.key 引用检查（顺序敏感）
        Set<String> seenOptionParams = new LinkedHashSet<>();

        for (int i = 0; i < steps.size(); i++) {
            String stepLoc = loc + ".steps[" + i + "]";
            ToolGuideFlowConfig.Step step = steps.get(i);
            if (step == null) {
                issues.add(new Issue(Severity.ERROR, stepLoc, "步骤为 null"));
                continue;
            }
            String type = step.type == null ? "" : step.type.trim();
            if (type.isEmpty()) {
                issues.add(new Issue(Severity.ERROR, stepLoc, "缺少 type；可用值 " + VALID_TYPES));
                continue;
            }
            if (!VALID_TYPES.contains(type)) {
                issues.add(new Issue(Severity.ERROR, stepLoc,
                        "type=\"" + step.type + "\" 不是合法步骤类型；可用值 " + VALID_TYPES
                                + "（请照 GuideStep.StepType 枚举名写，别另起小写别名）"));
                continue;
            }
            if (isBlank(step.title)) {
                issues.add(new Issue(Severity.WARN, stepLoc, "title 为空，步骤标题会空着"));
            }

            boolean isConfirm = "CONFIRM".equals(type);
            if (isConfirm) {
                if (!isBlank(step.paramKey)) {
                    issues.add(new Issue(Severity.WARN, stepLoc,
                            "CONFIRM 步骤不该带 paramKey=\"" + step.paramKey + "\"（确认步不产出参数）"));
                }
            } else if (isBlank(step.paramKey)) {
                issues.add(new Issue(Severity.ERROR, stepLoc,
                        type + " 步骤缺少 paramKey，用户填的值无处可放"));
            }

            if ("OPTION".equals(type)) {
                validateOptions(step, stepLoc, issues);
            } else if (step.options != null) {
                issues.add(new Issue(Severity.WARN, stepLoc,
                        type + " 步骤带了 options，只有 OPTION 会渲染选项"));
            }

            if ("INPUT".equals(type)) {
                if (isBlank(step.hint)) {
                    issues.add(new Issue(Severity.WARN, stepLoc,
                            "INPUT 步骤 hint 为空，输入框没有提示语；必填项尤其要给例子"));
                }
            } else if (step.dynamicOptions != null) {
                issues.add(new Issue(Severity.WARN, stepLoc,
                        type + " 步骤带 dynamicOptions，动态候选只在 INPUT 上生效"));
            }
            if (step.dynamicOptions != null) {
                validateDynamicOptions(step.dynamicOptions, stepLoc, issues);
            }
            if (PICKER_TYPES.contains(type) && step.mimeTypes != null) {
                for (int m = 0; m < step.mimeTypes.size(); m++) {
                    if (isBlank(step.mimeTypes.get(m))) {
                        issues.add(new Issue(Severity.WARN, stepLoc + ".mimeTypes[" + m + "]",
                                "MIME 为空串；不过滤的话建议整段省略 mimeTypes"));
                    }
                }
            }

            if (step.condition != null) {
                validateCondition(step, stepLoc, seenOptionParams, issues);
            }
            if ("OPTION".equals(type) && !isBlank(step.paramKey)) {
                seenOptionParams.add(step.paramKey);
            }
            if (!isConfirm && !isBlank(step.paramKey)) {
                checkParamKeyConflict(step, stepLoc, paramKeyConditions, issues);
            }
            if (schemaParams != null && !isConfirm && !isBlank(step.paramKey)) {
                validateAgainstSchema(step, type, stepLoc, schemaParams, issues);
            }
        }

        ToolGuideFlowConfig.Step last = steps.get(steps.size() - 1);
        if (last == null || !"CONFIRM".equals(last.type)) {
            issues.add(new Issue(Severity.WARN, loc,
                    "最后一个步骤不是 CONFIRM；现行 13 个 builder 全部以确认步收尾，缺了就没有执行入口"));
        }
    }

    private static void validateOptions(ToolGuideFlowConfig.Step step, String stepLoc, List<Issue> issues) {
        if (step.options == null || step.options.isEmpty()) {
            issues.add(new Issue(Severity.ERROR, stepLoc, "OPTION 步骤没有 options"));
            return;
        }
        Set<String> values = new HashSet<>();
        for (int k = 0; k < step.options.size(); k++) {
            ToolGuideFlowConfig.Option option = step.options.get(k);
            String optionLoc = stepLoc + ".options[" + k + "]";
            if (option == null) {
                issues.add(new Issue(Severity.ERROR, optionLoc, "选项为 null"));
                continue;
            }
            if (isBlank(option.label)) {
                issues.add(new Issue(Severity.ERROR, optionLoc, "label 为空，chip 上会看不见字"));
            }
            if (option.value == null) {
                issues.add(new Issue(Severity.ERROR, optionLoc,
                        "value 缺失；\"不传该参数\"要显式写空串 \"\"（如自动定位），null 与空串语义不同"));
                continue;
            }
            if (!values.add(option.value)) {
                issues.add(new Issue(Severity.ERROR, optionLoc,
                        "value=\"" + option.value + "\" 与本步骤其它选项重复（两个空串也算重复，会让\"不传\"出现两次）"));
            }
        }
    }

    private static void validateCondition(ToolGuideFlowConfig.Step step, String stepLoc,
                                          Set<String> seenOptionParams, List<Issue> issues) {
        ToolGuideFlowConfig.Condition condition = step.condition;
        String condLoc = stepLoc + ".condition";
        if (isBlank(condition.key)) {
            issues.add(new Issue(Severity.ERROR, condLoc, "condition.key 为空"));
            return;
        }
        if (condition.values == null || condition.values.isEmpty()) {
            issues.add(new Issue(Severity.ERROR, condLoc,
                    "condition.values 为空；没有值能命中，这一步永远不会展示"));
            return;
        }
        for (int v = 0; v < condition.values.size(); v++) {
            if (isBlank(condition.values.get(v))) {
                issues.add(new Issue(Severity.ERROR, condLoc + ".values[" + v + "]",
                        "条件值为空串；前序选项要表达\"不传\"时，应改用无 condition 的步骤"));
            }
        }
        if (condition.key.equals(step.paramKey)) {
            issues.add(new Issue(Severity.ERROR, condLoc + ".key",
                    "condition.key 不能是本步骤自己的 paramKey=\"" + step.paramKey + "\"（自己依赖自己）"));
            return;
        }
        if (!seenOptionParams.contains(condition.key)) {
            issues.add(new Issue(Severity.ERROR, condLoc + ".key",
                    "condition.key=\"" + condition.key + "\" 在此之前没有对应的 OPTION 步骤；"
                            + "getActiveSteps 只按已选参数过滤，引用不存在的前序选项会让本步永不展示"));
        }
    }

    /**
     * 同一 paramKey 被多个步骤产出时，两者的条件值集必须不相交。
     * 无条件视为通配，与任何条件都相交。
     */
    private static void checkParamKeyConflict(ToolGuideFlowConfig.Step step, String stepLoc,
                                              Map<String, Set<String>> paramKeyConditions, List<Issue> issues) {
        Set<String> current = conditionValues(step);
        Set<String> previous = paramKeyConditions.get(step.paramKey);
        if (previous == null && !paramKeyConditions.containsKey(step.paramKey)) {
            paramKeyConditions.put(step.paramKey, current);
            return;
        }
        if (intersects(previous, current)) {
            issues.add(new Issue(Severity.ERROR, stepLoc,
                    "paramKey=\"" + step.paramKey + "\" 被两个条件重叠的步骤产出（前一步 "
                            + describeSet(previous) + "，本步 " + describeSet(current)
                            + "）；同一次运行会出现两个输入框抢一个参数"));
            return;
        }
        Set<String> merged = new LinkedHashSet<>(previous);
        if (current != null) {
            merged.addAll(current);
        }
        paramKeyConditions.put(step.paramKey, merged);
    }

    /** 步骤的条件值集；null 表示无条件（通配）。 */
    private static Set<String> conditionValues(ToolGuideFlowConfig.Step step) {
        if (step.condition == null || isBlank(step.condition.key)) {
            return null;
        }
        return step.condition.values == null
                ? Collections.<String>emptySet()
                : new LinkedHashSet<>(step.condition.values);
    }

    private static boolean intersects(Set<String> a, Set<String> b) {
        if (a == null || b == null) {
            return true; // 任一无条件 → 通配相交
        }
        for (String value : a) {
            if (b.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private static String describeSet(Set<String> set) {
        return set == null ? "无条件(通配)" : set.toString();
    }

    private static void validateDynamicOptions(ToolGuideFlowConfig.DynamicOptions spec, String stepLoc,
                                               List<Issue> issues) {
        String loc = stepLoc + ".dynamicOptions";
        if (isBlank(spec.toolName)) {
            issues.add(new Issue(Severity.ERROR, loc, "dynamicOptions.toolName 为空，不知道该调哪个工具取列表"));
        }
        if (isBlank(spec.action)) {
            issues.add(new Issue(Severity.ERROR, loc, "dynamicOptions.action 为空"));
        }
        if (isBlank(spec.listKey)) {
            issues.add(new Issue(Severity.ERROR, loc, "dynamicOptions.listKey 为空；要从结果里取列表却没说 key"));
        }
    }

    // ==================== 第 3 档：跨 schema ====================

    private static void validateAgainstSchema(ToolGuideFlowConfig.Step step, String type, String stepLoc,
                                              Map<String, ParamSpec> schemaParams, List<Issue> issues) {
        ParamSpec spec = schemaParams.get(step.paramKey);
        if (spec == null) {
            issues.add(new Issue(Severity.ERROR, stepLoc,
                    "paramKey=\"" + step.paramKey + "\" 不在该工具的模型可见 schema 参数里；"
                            + "这一步填的值会被工具当未知参数丢掉。schema 里的参数是 " + schemaParams.keySet()));
            return;
        }
        if ("INPUT".equals(type) && Boolean.FALSE.equals(step.required) && spec.required) {
            issues.add(new Issue(Severity.WARN, stepLoc,
                    "步骤标 required=false，但 schema 说 \"" + step.paramKey + "\" 是必填；留空执行会报缺参"));
        }
        if ("OPTION".equals(type)) {
            if (spec.enumValues == null) {
                issues.add(new Issue(Severity.WARN, stepLoc,
                        "参数 \"" + step.paramKey + "\" 在 schema 里没声明枚举值（只写在 description 文案里），"
                                + "本步骤 " + (step.options == null ? 0 : step.options.size())
                                + " 个选项的 value 未经校验；给这四个 switch 分支补 enumValues 后这里会变成硬校验"));
                return;
            }
            if (step.options == null) {
                return;
            }
            for (int k = 0; k < step.options.size(); k++) {
                ToolGuideFlowConfig.Option option = step.options.get(k);
                if (option == null || option.value == null || option.value.isEmpty()) {
                    continue; // 空串=不传该参数，不属于枚举取值
                }
                if (!spec.enumValues.contains(option.value)) {
                    issues.add(new Issue(Severity.ERROR, stepLoc + ".options[" + k + "]",
                            "value=\"" + option.value + "\" 不在参数 \"" + step.paramKey
                                    + "\" 声明的枚举里；模型看到的可选值是 " + spec.enumValues));
                }
            }
        } else if ("INPUT".equals(type) && spec.enumValues != null && !spec.enumValues.isEmpty()) {
            issues.add(new Issue(Severity.WARN, stepLoc,
                    "参数 \"" + step.paramKey + "\" 在 schema 里是枚举（" + spec.enumValues
                            + "），却配成 INPUT 手动输入；建议改 OPTION 步骤让人点选"));
        }
    }

    // ==================== 聚合方案 ====================

    private static void validateComposites(List<ToolGuideFlowConfig.CompositeFlow> composites,
                                           SchemaProbe probe, List<Issue> issues) {
        Set<String> flowIds = new HashSet<>();
        for (int i = 0; i < composites.size(); i++) {
            ToolGuideFlowConfig.CompositeFlow flow = composites.get(i);
            String path = "composites[" + i + "]";
            if (flow == null) {
                issues.add(new Issue(Severity.ERROR, path, "聚合方案为 null"));
                continue;
            }
            String loc = isBlank(flow.flowId) ? path : path + "[" + flow.flowId + "]";
            if (isBlank(flow.flowId)) {
                issues.add(new Issue(Severity.ERROR, loc,
                        "flowId 为空；快捷 chip 按 flowId 跳转（CompositeGuideFlow.getFlow），空了点不进"));
            } else if (!flowIds.add(flow.flowId)) {
                issues.add(new Issue(Severity.ERROR, loc, "flowId 重复"));
            }
            if (isBlank(flow.displayName)) {
                issues.add(new Issue(Severity.ERROR, loc,
                        "displayName 为空；chip 文案要由本配置供给（收编 QuickToolChipModule 后没有别的来源）"));
            }
            if (isBlank(flow.icon)) {
                issues.add(new Issue(Severity.WARN, loc, "icon 为空，chip 上会只剩文字"));
            }
            if (flow.steps == null || flow.steps.isEmpty()) {
                issues.add(new Issue(Severity.ERROR, loc, "steps 为空，聚合方案没有可执行的步骤"));
                continue;
            }
            for (int s = 0; s < flow.steps.size(); s++) {
                validateCompositeStep(flow.steps.get(s), loc + ".steps[" + s + "]", probe, issues);
            }
        }
    }

    private static void validateCompositeStep(ToolGuideFlowConfig.CompositeStep step, String stepLoc,
                                              SchemaProbe probe, List<Issue> issues) {
        if (step == null) {
            issues.add(new Issue(Severity.ERROR, stepLoc, "步骤为 null"));
            return;
        }
        if (isBlank(step.toolName)) {
            issues.add(new Issue(Severity.ERROR, stepLoc, "toolName 为空，不知道该执行哪个工具"));
        } else if (probe != null && probe.paramsOf(step.toolName) == null) {
            issues.add(new Issue(Severity.WARN, stepLoc,
                    "schema 里查不到工具 \"" + step.toolName + "\"，这一步的参数没有对照物"));
        }
        if (isBlank(step.actionDescription)) {
            issues.add(new Issue(Severity.WARN, stepLoc, "actionDescription 为空，活动卡上会没有说明文字"));
        }
        boolean hasGuideSteps = step.guideSteps != null && !step.guideSteps.isEmpty();
        if (Boolean.TRUE.equals(step.autoExecute) && hasGuideSteps) {
            issues.add(new Issue(Severity.WARN, stepLoc,
                    "autoExecute=true 却带了 guideSteps；自动步不会停下来问用户，这些步骤是死配置"));
        }
        if (step.paramRefs != null) {
            for (Map.Entry<String, String> entry : step.paramRefs.entrySet()) {
                String refLoc = stepLoc + ".paramRefs[" + entry.getKey() + "]";
                if (isBlank(entry.getKey())) {
                    issues.add(new Issue(Severity.ERROR, refLoc, "参数引用的参数名为空"));
                }
                String ref = entry.getValue();
                if (ref == null || !REF_PATTERN.matcher(ref).matches()) {
                    issues.add(new Issue(Severity.ERROR, refLoc,
                            "引用表达式 \"" + ref + "\" 不合法；只支持 $prev.<字段> 形式"));
                }
            }
        }
        if (step.fixedParams != null && hasGuideSteps) {
            for (int g = 0; g < step.guideSteps.size(); g++) {
                ToolGuideFlowConfig.Step guideStep = step.guideSteps.get(g);
                String guideLoc = stepLoc + ".guideSteps[" + g + "]";
                validateGuideStepOwnBody(guideStep, guideLoc, issues);
                if (guideStep != null && !isBlank(guideStep.paramKey)
                        && step.fixedParams.containsKey(guideStep.paramKey)) {
                    issues.add(new Issue(Severity.WARN, guideLoc,
                            "paramKey=\"" + guideStep.paramKey + "\" 与本步 fixedParams 撞名；"
                                    + "谁覆盖谁我没验过（buildCompositeParams 的实际优先级待实测），先按可疑报"));
                }
            }
        } else if (hasGuideSteps) {
            for (int g = 0; g < step.guideSteps.size(); g++) {
                validateGuideStepOwnBody(step.guideSteps.get(g), stepLoc + ".guideSteps[" + g + "]", issues);
            }
        }
    }

    /**
     * 聚合方案里的引导步骤只验本体：不要求以 CONFIRM 收尾（聚合步的执行入口在方案层），
     * 也不重复验工具级字段。
     */
    private static void validateGuideStepOwnBody(ToolGuideFlowConfig.Step step, String stepLoc,
                                                 List<Issue> issues) {
        if (step == null) {
            issues.add(new Issue(Severity.ERROR, stepLoc, "引导步骤为 null"));
            return;
        }
        String type = step.type == null ? "" : step.type.trim();
        if (!VALID_TYPES.contains(type)) {
            issues.add(new Issue(Severity.ERROR, stepLoc,
                    "type=\"" + step.type + "\" 不是合法步骤类型；可用值 " + VALID_TYPES));
            return;
        }
        if ("CONFIRM".equals(type)) {
            return;
        }
        if (isBlank(step.paramKey)) {
            issues.add(new Issue(Severity.ERROR, stepLoc, type + " 步骤缺少 paramKey"));
        }
        if ("OPTION".equals(type)) {
            validateOptions(step, stepLoc, issues);
        }
        if ("INPUT".equals(type) && isBlank(step.hint)) {
            issues.add(new Issue(Severity.WARN, stepLoc, "INPUT 步骤 hint 为空"));
        }
    }

    /**
     * 只给参数名集合、枚举一律视为未声明的探针，供测试与"schema 拿不到枚举"的场景使用。
     * 运行时装载方应按类注释实现 {@link SchemaProbe} 走 AIToolManager。
     */
    public static SchemaProbe probeFromParamNames(final Map<String, Set<String>> toolToParamNames) {
        return new SchemaProbe() {
            @Override
            public Map<String, ParamSpec> paramsOf(String toolName) {
                Set<String> names = toolToParamNames == null ? null : toolToParamNames.get(toolName);
                if (names == null) {
                    return null;
                }
                Map<String, ParamSpec> out = new LinkedHashMap<>();
                for (String name : names) {
                    out.put(name, new ParamSpec(false, null));
                }
                return out;
            }
        };
    }

    /** 测试与装载方共用的构造器：参数名 → (必填性, 枚举)。 */
    public static ParamSpec paramSpec(boolean required, String... enumValues) {
        return new ParamSpec(required,
                enumValues == null || enumValues.length == 0
                        ? null
                        : new LinkedHashSet<>(Arrays.asList(enumValues)));
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
