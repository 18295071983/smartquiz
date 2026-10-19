package com.oilquiz.app.ai.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 多工具聚合引导方案。
 *
 * 定义由多个工具依次组合完成的复杂任务（如"出行准备"=定位→天气→空气→预警）。
 * 每个聚合方案包含若干 {@link CompositeStep}，步骤间可通过 paramRefs 引用前序步骤的
 * 执行结果（由 {@link ToolResultStore} 解析），实现参数自动传递。
 *
 * 设计要点：
 * - {@link CompositeStep#fixedParams} 提供固定参数
 * - {@link CompositeStep#paramRefs} 提供参数引用（如 "$prev.city"），运行时解析
 * - {@link CompositeStep#guideSteps} 可选，需要用户补充参数时展示引导步骤
 * - {@link CompositeStep#autoExecute} 为 true 时无需用户确认直接执行
 */
public class CompositeGuideFlow {

    /** 聚合流程ID（如 "go_out"） */
    public String flowId;
    /** 显示名（如 "出行准备"） */
    public String displayName;
    /** 流程描述（如 "依次:定位→天气→空气→预警"） */
    public String description;
    /** emoji 图标 */
    public String icon;
    /** 组合步骤列表 */
    public List<CompositeStep> steps;

    public CompositeGuideFlow() {}

    public CompositeGuideFlow(String flowId, String displayName, String description,
                              String icon, List<CompositeStep> steps) {
        this.flowId = flowId;
        this.displayName = displayName;
        this.description = description;
        this.icon = icon;
        this.steps = steps;
    }

    /**
     * 工厂方法：获取指定聚合流程。
     *
     * @param flowId 流程ID
     * @return 对应聚合流程；未定义时返回 null
     */
    public static CompositeGuideFlow getFlow(String flowId) {
        if (flowId == null) {
            return null;
        }
        switch (flowId) {
            case "go_out":
                return buildGoOut();
            case "study":
                return buildStudy();
            case "research":
                return buildResearch();
            default:
                return null;
        }
    }

    /**
     * 获取全部聚合流程。
     *
     * @return 聚合流程列表
     */
    public static List<CompositeGuideFlow> getAllFlows() {
        List<CompositeGuideFlow> all = new ArrayList<>();
        all.add(buildGoOut());
        all.add(buildStudy());
        all.add(buildResearch());
        return all;
    }

    // ==================== 各聚合方案定义 ====================

    /** 出行准备：定位→当前天气→空气质量→天气预警，全部自动执行 */
    private static CompositeGuideFlow buildGoOut() {
        List<CompositeStep> steps = new ArrayList<>();

        // 步骤1：获取当前位置（自动执行）
        CompositeStep step1 = new CompositeStep();
        step1.toolName = "location";
        step1.actionDescription = "获取当前位置";
        step1.icon = "📍";
        step1.autoExecute = true;
        step1.fixedParams = new HashMap<>();
        step1.fixedParams.put("action", "get_current");
        steps.add(step1);

        // 步骤2：查当前天气（自动执行，引用上一步坐标）
        CompositeStep step2 = new CompositeStep();
        step2.toolName = "ai_weather";
        step2.actionDescription = "查当前天气";
        step2.icon = "🌤️";
        step2.autoExecute = true;
        step2.fixedParams = new HashMap<>();
        step2.fixedParams.put("action", "current");
        step2.paramRefs = new HashMap<>();
        step2.paramRefs.put("city", "$prev.city");
        step2.paramRefs.put("lat", "$prev.lat");
        step2.paramRefs.put("lon", "$prev.lon");
        steps.add(step2);

        // 步骤3：查空气质量（自动执行，引用上一步坐标）
        CompositeStep step3 = new CompositeStep();
        step3.toolName = "ai_weather";
        step3.actionDescription = "查空气质量";
        step3.icon = "💨";
        step3.autoExecute = true;
        step3.fixedParams = new HashMap<>();
        step3.fixedParams.put("action", "air_quality");
        step3.paramRefs = new HashMap<>();
        step3.paramRefs.put("lat", "$prev.lat");
        step3.paramRefs.put("lon", "$prev.lon");
        steps.add(step3);

        // 步骤4：查天气预警（自动执行，引用上一步坐标）
        CompositeStep step4 = new CompositeStep();
        step4.toolName = "ai_weather";
        step4.actionDescription = "查天气预警";
        step4.icon = "⚠️";
        step4.autoExecute = true;
        step4.fixedParams = new HashMap<>();
        step4.fixedParams.put("action", "alerts");
        step4.paramRefs = new HashMap<>();
        step4.paramRefs.put("lat", "$prev.lat");
        step4.paramRefs.put("lon", "$prev.lon");
        steps.add(step4);

        return new CompositeGuideFlow("go_out", "出行准备",
                "依次:定位→天气→空气→预警", "🚗", steps);
    }

    /** 学习查询：搜索题目→（可选）翻译结果 */
    private static CompositeGuideFlow buildStudy() {
        List<CompositeStep> steps = new ArrayList<>();

        // 步骤1：搜索题目（需用户输入关键词）
        CompositeStep step1 = new CompositeStep();
        step1.toolName = "database";
        step1.actionDescription = "搜索题目";
        step1.icon = "🔍";
        step1.autoExecute = false;
        step1.fixedParams = new HashMap<>();
        step1.fixedParams.put("action", "search_questions");
        step1.guideSteps = new ArrayList<>();
        step1.guideSteps.add(ToolGuideFlow.GuideStep.inputStep(
                "搜什么关键词?", "输入关键词", "keyword", "搜关键词", true, false));
        steps.add(step1);

        return new CompositeGuideFlow("study", "学习查询",
                "搜索题目", "📚", steps);
    }

    /** 网页研究：搜索→读网页 */
    private static CompositeGuideFlow buildResearch() {
        List<CompositeStep> steps = new ArrayList<>();

        // 步骤1：搜索（需用户输入关键词）
        CompositeStep step1 = new CompositeStep();
        step1.toolName = "network_search";
        step1.actionDescription = "搜索";
        step1.icon = "🔎";
        step1.autoExecute = false;
        step1.fixedParams = new HashMap<>();
        step1.fixedParams.put("action", "search");
        step1.fixedParams.put("limit", "5");
        step1.guideSteps = new ArrayList<>();
        step1.guideSteps.add(ToolGuideFlow.GuideStep.inputStep(
                "搜什么?", "输入关键词", "query", "搜关键词", true, false));
        steps.add(step1);

        // 步骤2：读网页（引用上一步首个URL）
        CompositeStep step2 = new CompositeStep();
        step2.toolName = "network_search";
        step2.actionDescription = "读网页";
        step2.icon = "📄";
        step2.autoExecute = false;
        step2.fixedParams = new HashMap<>();
        step2.fixedParams.put("action", "read_url");
        step2.paramRefs = new HashMap<>();
        step2.paramRefs.put("url", "$prev.firstUrl");
        steps.add(step2);

        return new CompositeGuideFlow("research", "网页研究",
                "依次:搜索→读网页", "🔍", steps);
    }

    // ==================== 组合步骤数据模型 ====================

    /**
     * 聚合流程中的单个步骤。
     */
    public static class CompositeStep {
        /** 工具名（如 "ai_weather"） */
        public String toolName;
        /** 步骤动作描述（如 "查询当前天气"） */
        public String actionDescription;
        /** emoji 图标 */
        public String icon;
        /**
         * 参数引用，key 为目标参数名，value 为引用表达式（如 "$prev.city"）。
         * 运行时由 {@link ToolResultStore#resolveRef(String)} 解析。
         */
        public Map<String, String> paramRefs;
        /** 固定参数（如 {"action":"current"}） */
        public Map<String, String> fixedParams;
        /** 可选：需要用户补充参数时的引导步骤 */
        public List<ToolGuideFlow.GuideStep> guideSteps;
        /** 是否自动执行（无需用户确认） */
        public boolean autoExecute;

        public CompositeStep() {}
    }
}
