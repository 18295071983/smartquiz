package com.oilquiz.app.ai.agent;

import java.util.List;
import java.util.Map;

/**
 * 工具引导流程的配置文件模型（{@code tool_guide_flow.json} 的反序列化目标）。
 *
 * <p>本类只是"配置态"的数据容器，与运行时对象 {@link ToolGuideFlow} 分离：
 * 前者描述"配了什么"，后者承担"怎么跑"（条件过滤、步骤渲染由
 * {@link ToolGuideFlow#getActiveSteps(Map)} 与 AIChatActivity 负责，不在这里重复）。
 * 装载入口应为 {@code ToolGuideFlow} 侧的一个 fromConfig 转换，本类不含行为。</p>
 *
 * <p><b>字段命名刻意与 {@link ToolGuideFlow.GuideStep} 逐字一致</b>（{@code title}
 * /{@code description}/{@code paramKey}/{@code hint}/{@code required}/{@code multiline}
 * /{@code mimeTypes}/{@code allowMultiple}/{@code options}/{@code dynamicOptions}），
 * 目的是让转换层不需要字段映射表，映射表正是这类迁移里最容易悄悄写错的地方。
 * 两处例外：</p>
 * <ul>
 *   <li>条件分支从 POJO 的 {@code conditionKey} + {@code conditionValue}（"|" 分隔串）
 *       改为结构化的 {@link Condition}（{@code key} + {@code values[]}）。语义等价，
 *       转换时 join("|") 回填即可；改用数组是因为 "|" 这种"值里不能出现的分隔符"
 *       写进配置文件属于埋雷。</li>
 *   <li>{@code paramValue} 不在此声明。它是运行时槽位——用户选完后由
 *       AIChatActivity 写入（见 :2300-2304、:2363），配置里没有它的生产者，
 *       放进 schema 只会让人以为可以预填默认值。</li>
 * </ul>
 *
 * <p>接线状态（2026-10-28 第二步）：{@code terms} 段已被动态兜底引导消费
 * （{@link ToolGuideLabels#applyTo(ToolGuideFlow, ToolGuideTerms)}）；
 * {@code tools} 与 {@code composites} 两段还没有消费者——要等 flag + 装载切换那步落地后
 * 才接管引导流程，现在引导仍走 {@link ToolGuideFlow#getFlow(String)} 里的硬编码 builder。</p>
 */
public class ToolGuideFlowConfig {

    /** 配置版本（如 "2026-10-28-v2"），用于与随包/外部覆盖档做新旧判断 */
    public String version;

    /**
     * 术语表（{@code paramKey}/{@code value} → 中文显示名）。
     *
     * <p>它服务的不是下面 {@code tools} 段的手写引导（那些本来就有中文 label），
     * 而是 {@link ToolGuideFlow#getFlow(String)} 的动态兜底：没写精细引导的工具
     * 从 schema 自动生成步骤时，选项 label 直接等于枚举原文
     * （{@code new Option(enumVal, enumVal)}），用户看到的是 {@code list}/{@code get}/{@code air_quality}
     * 这种原始字段值。术语表补的就是这一层的中文显示名。</p>
     */
    public Terms terms;

    /** 单工具引导流程 */
    public List<ToolFlow> tools;

    /** 多工具聚合引导方案 */
    public List<CompositeFlow> composites;

    // ==================== 单工具流程 ====================

    /** 单个工具的引导流程定义，对应 {@link ToolGuideFlow} 的一个实例。 */
    public static class ToolFlow {
        /** 工具名，必须与 AIToolManager 注册名一致（如 "ai_weather"） */
        public String toolName;
        /** 工具显示名（如 "天气查询"） */
        public String toolDisplayName;
        /** 工具描述 */
        public String toolDescription;
        /** 执行前依赖的环境上下文（如 ["location"]），对应 {@link ToolGuideFlow#contextDependencies} */
        public List<String> contextDependencies;
        /** 步骤列表，顺序即展示顺序 */
        public List<Step> steps;
    }

    /** 单个引导步骤，对应 {@link ToolGuideFlow.GuideStep}。 */
    public static class Step {
        /**
         * 步骤类型，取 {@link ToolGuideFlow.GuideStep.StepType} 的枚举名原文：
         * OPTION / INPUT / CONFIRM / FILE_PICKER / IMAGE_PICKER / DIRECTORY_PICKER。
         * 用大写枚举名而非另设一套小写别名，是为了让校验报错能直接钉回代码里的枚举。
         */
        public String type;
        /** 步骤标题 */
        public String title;
        /** 步骤描述/提示 */
        public String description;
        /** 对应工具参数名（CONFIRM 步骤无此字段） */
        public String paramKey;
        /** OPTION 专用：可选项 */
        public List<Option> options;
        /** INPUT 专用：输入框提示 */
        public String hint;
        /** INPUT/PICKER 专用：是否必填。用包装类型区分"没写"与"写了 false" */
        public Boolean required;
        /** INPUT 专用：是否多行 */
        public Boolean multiline;
        /** PICKER 专用：MIME 过滤。POJO 侧是 String[]，转换时 toArray */
        public List<String> mimeTypes;
        /** PICKER 专用：是否多选 */
        public Boolean allowMultiple;
        /** INPUT 专用：动态选项来源 */
        public DynamicOptions dynamicOptions;
        /** 条件分支：前序参数匹配时才展示 */
        public Condition condition;
    }

    /** 选项项，对应 {@link ToolGuideFlow.GuideStep.Option}。 */
    public static class Option {
        /** 显示文本 */
        public String label;
        /**
         * 实际下发的参数值。允许为空串且含义明确："不传该参数"
         * （如天气的"📍 自动定位"选项 value=""），所以校验不能把空串当缺失。
         */
        public String value;
    }

    /** 条件分支，替代 POJO 侧的 conditionKey + "|" 分隔 conditionValue。 */
    public static class Condition {
        /** 依赖的前序参数名 */
        public String key;
        /** 命中任一值即展示 */
        public List<String> values;
    }

    /** 动态选项来源，对应 {@link ToolGuideFlow.GuideStep.DynamicOptionsSpec}。 */
    public static class DynamicOptions {
        /** 提供列表的工具名 */
        public String toolName;
        /** 该工具的 action */
        public String action;
        /** 结果 Map 中列表所在的 key */
        public String listKey;
        /** 列表元素为 Map 时取值的字段名；元素为字符串时留空 */
        public String itemField;
        /** 列表顶部附加的"全部/不限"项文案；null 表示不附加 */
        public String allOptionLabel;
    }

    // ==================== 聚合方案 ====================

    /** 多工具聚合方案，对应 {@link CompositeGuideFlow}。 */
    public static class CompositeFlow {
        /** 方案 ID（如 "go_out"），chip 入口按它跳转 */
        public String flowId;
        /** 显示名（如 "出行准备"） */
        public String displayName;
        /** 流程描述 */
        public String description;
        /** emoji 图标 */
        public String icon;
        /** 组合步骤 */
        public List<CompositeStep> steps;
    }

    /** 聚合方案中的单个步骤，对应 {@link CompositeGuideFlow.CompositeStep}。 */
    public static class CompositeStep {
        /** 本步调用的工具名 */
        public String toolName;
        /** 动作描述（如 "查当前天气"） */
        public String actionDescription;
        /** emoji 图标 */
        public String icon;
        /** 是否自动执行（无需用户确认） */
        public Boolean autoExecute;
        /** 固定参数 */
        public Map<String, String> fixedParams;
        /** 参数引用，value 形如 "$prev.lat"，运行时由 ToolResultStore.resolveRef 解析 */
        public Map<String, String> paramRefs;
        /** 需要用户补参数时的引导步骤 */
        public List<Step> guideSteps;
    }

    // ==================== 术语表 ====================

    /**
     * 中文显示名词典。三层查找：工具级 → 全局值级 → 没有就保持原文。
     *
     * <p>翻译器不猜、不编：查不到就露出原始值，也好过给一个可能错的名字。
     * 未翻译的条数由 {@link ToolGuideLabels#applyTo(ToolGuideFlow, ToolGuideTerms)} 统计并落日志。</p>
     */
    public static class Terms {
        /** 参数名 → 中文（如 {@code "file_path" → "文件路径"}） */
        public Map<String, String> params;
        /** 参数值 → 中文，全局兜底（如 {@code "read_url" → "读网页"}） */
        public Map<String, String> values;
        /**
         * 工具 → 参数 → 值 → 中文。同名值在不同工具里含义不同时用它压掉全局档
         * （如 {@code "list"} 在知识库里是"列索引"、在 memory 里是"列出"）。
         */
        public Map<String, Map<String, Map<String, String>>> byTool;
    }
}
