package com.oilquiz.app.ai.agent.online;

import java.util.List;

/**
 * 在线模型专用提示词构建器。
 * 独立于 {@link com.oilquiz.app.ai.service.AIService} 和 {@link com.oilquiz.app.ai.agent.UnifiedAgentEngine}，
 * 专为在线模型原生 function calling 设计。
 *
 * 集成 {@link OnlineToolGuide} 生成系统提示词，并提供回退/组合建议提示词。
 */
public class OnlinePromptBuilder {

    private final OnlineToolGuide guide;

    public OnlinePromptBuilder(OnlineToolGuide guide) {
        this.guide = guide;
    }

    /**
     * 构建系统提示词（本地辅助模式）。
     * 包含：角色定义、工具指南（来自 OnlineToolGuide）、输出要求、推理能力。
     * 不包含本地 agent 的 ReAct/CoT/Plan 模式描述。
     */
    public String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个智能AI助手，拥有多种工具来帮助用户完成任务。\n\n");

        // 集成工具指南（原生 function calling 格式）
        if (guide != null) {
            sb.append(guide.buildGuide()).append("\n");
        } else {
            // 降级：无指南时使用基础规范
            sb.append("【工具使用规范】\n");
            sb.append("1. 通过原生 function calling 调用工具，系统会自动执行并将结果返回。\n");
            sb.append("2. 优先使用专用工具（如查询天气用 ai_weather，搜索用 network_search）。\n");
            sb.append("3. 工具可组合使用，可同时调用多个工具（并行）。\n");
            sb.append("4. 工具失败时分析原因：参数错误则修正重试，工具不适用则更换工具。\n");
            sb.append("5. 同一工具连续失败2次应更换策略或向用户澄清。\n\n");
        }

        sb.append(buildKnowledgeStrategySection());

        sb.append("【输出要求】\n");
        sb.append("- 用中文回答用户问题\n");
        sb.append("- 回答要简洁、准确、有条理\n");
        sb.append("- 如果使用了工具，在回答中自然地融入工具结果\n");
        sb.append("- 如果工具失败，向用户说明原因并提供替代建议\n\n");

        sb.append("【推理能力】\n");
        sb.append("- 你可以多轮推理和调用工具，每次工具结果返回后你可以继续思考\n");
        sb.append("- 善用你的推理能力（reasoning），先思考再行动\n");
        sb.append("- 如果已有足够信息，直接回答用户，不要调用不必要的工具\n");

        return sb.toString();
    }

    /**
     * 构建系统提示词（模型接管模式）。
     *
     * 当在线模型具备完整 agent 能力（原生 function calling + 多轮自主推理）时，
     * 本地退化为纯执行器：信任模型的自主决策，不强加调用规则与错误处理指引。
     * 仅提供工具清单（模型需要知道有哪些工具可用）和最小输出要求。
     */
    public String buildSystemPromptTakeover() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个具备完整 Agent 能力的智能助手，通过原生 function calling 自主完成任务。\n\n");

        // 仅提供工具清单（按类别），不附加调用规则和错误处理指引
        if (guide != null) {
            sb.append("【可用工具】\n");
            sb.append(guide.buildQuickReference()).append("\n\n");
            sb.append("工具的完整参数定义已通过 API 的 tools 参数提供，可直接发起 tool_calls 调用。\n\n");
        }

        sb.append(buildKnowledgeStrategySection());

        sb.append("【自主决策权限】\n");
        sb.append("- 你拥有完整的自主决策权：自主决定调用哪些工具、何时调用、如何组合、是否并行。\n");
        sb.append("- 工具失败时自主判断：修正参数重试、更换工具、向用户澄清，或基于已有信息作答。\n");
        sb.append("- 自主控制推理轮数，直到完成任务或确认无法完成。\n");
        sb.append("- 无需遵循固定流程，发挥你的推理与规划能力以最优方式解决问题。\n\n");

        sb.append("【输出要求】\n");
        sb.append("- 用中文回答用户问题\n");
        sb.append("- 回答要简洁、准确、有条理\n");
        sb.append("- 如果使用了工具，在回答中自然地融入工具结果\n");

        return sb.toString();
    }

    /**
     * 构建知识库驱动的工具策略提示词。
     *
     * 引导模型利用自身训练数据中的知识（API用法、数据源、查询方法等）
     * 来优化工具调用策略，而非盲目调用工具。
     */
    private String buildKnowledgeStrategySection() {
        StringBuilder sb = new StringBuilder();
        sb.append("【知识库驱动的工具策略】\n");
        sb.append("你拥有丰富的知识库，包括各类API、网站、数据源的使用方法。请充分利用这些知识优化工具调用：\n\n");

        sb.append("1. 搜索策略优化：根据你的知识构造精准搜索词\n");
        sb.append("   - 查油价 → 搜索\"今日国际原油价格\"或\"国内成品油价格调整最新\"\n");
        sb.append("   - 查汇率 → 搜索\"人民币兑美元实时汇率\"\n");
        sb.append("   - 查新闻 → 搜索关键词+\"最新\"获取时效性结果\n");
        sb.append("   - 查技术问题 → 搜索错误信息+技术关键词，定位Stack Overflow/官方文档\n\n");

        sb.append("2. 数据源感知：你知道哪些网站/数据源更可靠，优先搜索和读取权威来源\n");
        sb.append("   - 官方数据 → 政府网站、官方API文档\n");
        sb.append("   - 新闻资讯 → 权威新闻网站\n");
        sb.append("   - 技术文档 → 官方文档、GitHub、Stack Overflow\n");
        sb.append("   - 实时数据 → 用 network_search 定位数据页，再用 webpage_reader 提取关键信息\n\n");

        sb.append("3. 工具组合策略：基于你的知识选择最优工具组合\n");
        sb.append("   - 需要实时信息 → network_search 搜索 + webpage_reader 深度阅读提取\n");
        sb.append("   - 需要本地数据 → database 查询题库/记录 + file 读取文件\n");
        sb.append("   - 需要位置服务 → location 定位 + ai_weather 天气查询\n");
        sb.append("   - 需要翻译 → translation 直接翻译，无需搜索\n");
        sb.append("   - 需要计算 → app_toolkit 计算，无需搜索\n\n");

        sb.append("4. 查询构造技巧：\n");
        sb.append("   - 包含时间词（\"今日\"、\"最新\"、年份）获取时效性信息\n");
        sb.append("   - 包含地域词（\"中国\"、\"北京\"）获取本地化结果\n");
        sb.append("   - 使用专业术语提高搜索精准度，避免过于宽泛\n");
        sb.append("   - 搜索后先看摘要，判断哪些结果值得用 webpage_reader 深度阅读\n\n");

        sb.append("5. 结果分析与整合：\n");
        sb.append("   - 用你的知识判断工具返回数据是否合理、是否过时\n");
        sb.append("   - 多个来源的信息交叉验证，取可靠数据\n");
        sb.append("   - 将工具结果与你已有的知识整合，给出完整准确的回答\n\n");

        return sb.toString();
    }

    /**
     * 构建工具失败时的回退建议提示词。
     * 注入下一轮推理的 system 消息，模型可选择调用替代工具。
     *
     * @param failedTool    失败的工具名
     * @param fallbackTools 推荐的替代工具列表（来自 OnlineToolChain）
     * @return 回退建议提示词
     */
    public String buildFallbackHint(String failedTool, List<String> fallbackTools) {
        if (failedTool == null || fallbackTools == null || fallbackTools.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【工具回退建议】\n");
        sb.append("工具 ").append(failedTool).append(" 执行失败。可选择以下替代工具：\n");
        for (String tool : fallbackTools) {
            sb.append("  - ").append(tool).append("\n");
        }
        sb.append("请判断是否适合后调用，也可向用户澄清需求。\n");
        return sb.toString();
    }

    /**
     * 构建工具组合建议提示词。
     * 提示模型可搭配使用其他工具。
     *
     * @param toolName      当前工具名
     * @param combinations  建议的组合工具列表
     * @return 组合建议提示词
     */
    public String buildCombinationHint(String toolName, List<String> combinations) {
        if (toolName == null || combinations == null || combinations.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【工具组合建议】\n");
        sb.append("调用 ").append(toolName).append(" 后，可考虑搭配使用以下工具：\n");
        for (String tool : combinations) {
            sb.append("  - ").append(tool).append("\n");
        }
        sb.append("根据任务需要决定是否调用。\n");
        return sb.toString();
    }

    /**
     * 构建单工具的按需查看提示词。
     * 用于模型主动获取某工具的详细参数说明。
     *
     * @param toolName 工具名
     * @return 工具详细说明，若工具不存在返回 null
     */
    public String buildToolInfoHint(String toolName) {
        if (guide == null) return null;
        return guide.buildToolDetail(toolName);
    }
}
