package com.oilquiz.app.ai.agent.online;

import com.oilquiz.app.ai.tool.openai.ParamDefinition;
import com.oilquiz.app.ai.tool.openai.ToolDefinition;
import com.oilquiz.app.util.AILogger;

import java.util.List;
import java.util.Map;

/**
 * 在线模型专用工具指南 —— 独立于 {@link com.oilquiz.app.ai.tool.AIToolUsageGuide}。
 *
 * 生成原生 function calling 格式的工具使用指南（非 TOOLS_CALL 文本格式）：
 * - 原生 function calling 使用说明（无需文本格式标记）
 * - 按类别分组的工具清单
 * - 工具组合示例（从 {@link OnlineToolChain} 获取）
 * - 错误处理指引
 * - 支持按需生成单工具详细说明
 * - 带缓存机制，工具数量变化时自动刷新
 */
public class OnlineToolGuide {

    private static final String TAG = "OnlineToolGuide";

    private final OnlineToolRegistry registry;
    private final OnlineToolChain chain;
    private final OnlineToolUsageTracker usageTracker;

    /** 完整指南缓存 */
    private volatile String cachedGuide;
    private volatile int cachedToolCount = -1;

    public OnlineToolGuide(OnlineToolRegistry registry, OnlineToolChain chain) {
        this(registry, chain, null);
    }

    public OnlineToolGuide(OnlineToolRegistry registry, OnlineToolChain chain,
                           OnlineToolUsageTracker usageTracker) {
        this.registry = registry;
        this.chain = chain;
        this.usageTracker = usageTracker;
    }
    private volatile boolean cacheDirty = true;

    /** 速查缓存 */
    private volatile String cachedQuickReference;

    /** 标记缓存脏（工具或链变化时调用） */
    public void markCacheDirty() {
        cacheDirty = true;
        cachedQuickReference = null;
    }

    // ==================== 完整指南 ====================

    /**
     * 生成完整工具指南（带缓存）。
     * 工具数量变化或缓存脏时重建。
     */
    public String buildGuide() {
        int currentCount = registry.getEnabledToolCount();
        if (!cacheDirty && cachedGuide != null && currentCount == cachedToolCount) {
            return cachedGuide;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════════\n");
        sb.append("         工具使用指南（原生 Function Calling）\n");
        sb.append("═══════════════════════════════════════════════════════\n\n");

        // 1. 工具清单（仅列名称一行速查——模型内置 function calling 知识，
        //    完整参数定义由 API tools 参数提供，不在此重复，避免占 token）
        sb.append("【可用工具】\n");
        Map<String, List<String>> categoryIndex = registry.getCategoryIndex();
        for (Map.Entry<String, List<String>> e : categoryIndex.entrySet()) {
            sb.append("  ▸ ").append(categoryDisplayName(e.getKey())).append(": ");
            List<String> names = e.getValue();
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(names.get(i));
            }
            sb.append("\n");
        }
        sb.append("（每个工具的完整参数定义见 API 的 tools 参数，按定义填参即可；"
                + "不确定时用 tool_registry(action=get, tool=工具名) 查看）\n\n");

        // 2. 工具组合示例（静态链 + 学习到的历史模式）
        sb.append("【工具组合示例】\n");
        List<String[]> combos = chain.getCombinationPairs();
        if (!combos.isEmpty()) {
            for (String[] pair : combos) {
                sb.append("  • ").append(pair[0]).append(" → ").append(pair[1]).append("\n");
            }
        }
        // 自进化：注入历史高频组合（持久化统计，跨会话累积）
        List<OnlineToolUsageTracker.ToolPattern> learned = usageTracker != null
                ? usageTracker.discoverPatterns() : new java.util.ArrayList<>();
        if (!learned.isEmpty()) {
            sb.append("  （经验提示：历史对话中以下组合效果良好，可优先参考）\n");
            int shown = 0;
            for (OnlineToolUsageTracker.ToolPattern p : learned) {
                if (shown >= 5) break;
                sb.append("  • ").append(p.toolA).append(" + ").append(p.toolB)
                        .append("（用过 ").append(p.count).append(" 次）\n");
                shown++;
            }
        }
        if (combos.isEmpty() && learned.isEmpty()) {
            sb.append("  （暂无组合建议）\n");
        }
        sb.append("\n");

        // 3. 错误处理指引（精简：模型内置重试逻辑，仅保留关键规则）
        sb.append("【错误处理】\n");
        sb.append("  • 参数错误：系统会注入该工具的详细参数定义和缺失参数分析，请据此修正后重试\n");
        sb.append("  • 工具不适用：系统会推荐替代工具（回退链），请判断是否适合后调用\n");
        sb.append("  • 同一工具连续失败 2 次：更换策略或向用户澄清，不要无限重试\n");
        sb.append("  • 缺少前置信息时（如查天气无城市），可先调用依赖工具（如 location）补充\n\n");

        // 4. 应用定制规则（模型内置知识没有这些，必须明确告知）
        sb.append("【调用规则】\n");
        sb.append("  1. 优先使用专用工具，而非聚合工具 app_toolkit\n");
        sb.append("  2. 文件路径：工作区文件用相对路径（如 report.md 或 files/报告.pdf），系统自动解析；外部文件用绝对路径\n");
        sb.append("  3. 涉及权限的操作（定位/相机/录音/存储）先主动调 permission_manager(action=request_and_wait, permission=对应权限名) 请求，不要假设已授权\n");
        sb.append("  4. 天气优先用 ai_weather（当前天气/多日预报完整返回；城市用 city，无城市可先 location 定位拿 lat/lon 配合查询），也可用 network_search 搜索天气；不要依赖注入的环境信息\n");
        sb.append("  5. 善用推理能力先思考再行动，可多轮推理和调用工具\n");
        sb.append("  6. 调用工具是你正常的工作方式：需要实时信息、计算、行动或外部数据时放心调用，是否调用由你自主判断；信息已足够时自然回答即可\n");
        sb.append("  7. 按需选参数：多数工具支持多种操作类型（action）与多种参数方式——先按用户需求选最匹配的 action，再填对应参数。\n");
        sb.append("     例如 ai_weather 可 current(实时)/forecast(预报)/hourly(逐小时)/air_quality(空气质量)/alerts(预警)/indices(生活指数)/all(全部)，位置可用 city(城市名或和风城市编码) 或 lat+lon 经纬度；\n");
        sb.append("     network_search 可 search(搜索)/ask(智能问答)/read_url(网页读取)/get_webpage(本地抓取)/extract_info(提取信息)/summarize(摘要)/search_and_read(搜索并阅读)/get_dynamic_content(动态网页)/smart_search(智能搜索)/smart_read(智能阅读)，ask还可选model=concise/detail/research。\n");
        sb.append("     不确定某工具完整参数时用 tool_registry(action=get, tool=工具名) 查看定义后再调用，不要只重复默认参数。\n");
        sb.append("  8. 现有信息不足以回答时（实时数据/最新事件/超出已知范围的事实），主动用 network_search 或 smart_research 搜索补全再答，不硬答不编造；不确定的时效性事实先查证再下结论。\n");
        sb.append("  9. 用户已给出的参数（城市/编码/时间/位置等）直接照用先调用，工具会解析并返回结果，以工具返回为准；参数有误时工具返回错误，再按工具结果调整。\n");
        sb.append("  10. 实时信息（时间/位置/天气/新闻/行情/开奖/政策等）一律用工具获取，训练数据不采纳；缺失或不确定时同样直接调对应工具拿真实结果，工具返回的数据是准确实时的，直接采纳。\n\n");

        sb.append("═══════════════════════════════════════════════════════\n");

        cachedGuide = sb.toString();
        cachedToolCount = currentCount;
        cacheDirty = false;
        AILogger.i(TAG, "Built guide for " + currentCount + " tools");
        return cachedGuide;
    }

    // ==================== 单工具详细说明 ====================

    /**
     * 生成单工具的详细参数说明（不缓存，按需生成）。
     */
    public String buildToolDetail(String toolName) {
        OnlineToolRegistry.ToolMeta meta = registry.getToolMeta(toolName);
        if (meta == null) {
            return "工具不存在: " + toolName;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════════\n");
        sb.append("工具详细说明: ").append(toolName).append("\n");
        sb.append("═══════════════════════════════════════════════════════\n\n");
        sb.append("功能: ").append(meta.description).append("\n");
        sb.append("类别: ").append(categoryDisplayName(meta.category)).append("\n\n");

        sb.append("参数:\n");
        ToolDefinition def = meta.definition;
        if (def != null && def.getParameters() != null && !def.getParameters().isEmpty()) {
            for (ParamDefinition param : def.getParameters()) {
                sb.append("  - ").append(param.getName());
                sb.append(" (").append(param.getType() != null ? param.getType() : "string").append(")");
                sb.append(param.isRequired() ? " [必填]" : " [可选]");
                if (param.getDefaultValue() != null) {
                    sb.append(" [默认: ").append(param.getDefaultValue()).append("]");
                }
                sb.append("\n");
                sb.append("    说明: ").append(param.getDescription()).append("\n");
                if (param.getEnumValues() != null && !param.getEnumValues().isEmpty()) {
                    sb.append("    枚举: ").append(String.join(" / ", param.getEnumValues())).append("\n");
                }
            }
        } else {
            sb.append("  （无参数）\n");
        }

        // 相关工具链
        List<String> fallbacks = chain.getFallbackTools(toolName);
        List<String> combinations = chain.getCombinations(toolName);
        List<String> dependencies = chain.getDependencies(toolName);
        sb.append("\n关联工具:\n");
        if (!dependencies.isEmpty()) {
            sb.append("  依赖（缺少参数时可先调用）: ").append(String.join(", ", dependencies)).append("\n");
        }
        if (!fallbacks.isEmpty()) {
            sb.append("  回退（失败时替代）: ").append(String.join(", ", fallbacks)).append("\n");
        }
        if (!combinations.isEmpty()) {
            sb.append("  组合（建议搭配使用）: ").append(String.join(", ", combinations)).append("\n");
        }
        if (dependencies.isEmpty() && fallbacks.isEmpty() && combinations.isEmpty()) {
            sb.append("  （无关联工具）\n");
        }
        sb.append("═══════════════════════════════════════════════════════\n");
        return sb.toString();
    }

    // ==================== 工具名速查 ====================

    /**
     * 生成工具名速查（一行列表，带缓存）。
     */
    public String buildQuickReference() {
        int currentCount = registry.getEnabledToolCount();
        if (cachedQuickReference != null && currentCount == cachedToolCount) {
            return cachedQuickReference;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("可用工具: ");
        boolean first = true;
        for (OnlineToolRegistry.ToolMeta meta : registry.getAllToolMetas()) {
            if (!first) sb.append(", ");
            sb.append(meta.name);
            first = false;
        }
        cachedQuickReference = sb.toString();
        return cachedQuickReference;
    }

    // ==================== 内部工具 ====================

    /** 类别英文键 → 中文显示名（与 AIToolManager 实际注册类别保持一致） */
    private String categoryDisplayName(String category) {
        if (category == null) return "其他";
        switch (category) {
            case "weather": return "天气";
            case "search": return "搜索";
            case "research": return "研究";
            case "web": return "网页";
            case "file": return "文件";
            case "data": return "数据";
            case "location": return "定位";
            case "system": return "系统";
            case "app": return "应用";
            case "python": return "Python";
            case "code": return "代码";
            case "calculator": return "计算";
            case "knowledge": return "知识库";
            case "speech": return "语音";
            case "tool": return "工具管理";
            case "meta": return "元工具";
            case "general": return "通用";
            default: return category;
        }
    }
}
