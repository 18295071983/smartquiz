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

        // 2. 工具组合示例已移除：场景→工具/组合引导交由模型自行判断，不注入示例

        // 3. 错误处理指引（精简：模型内置重试逻辑，仅保留关键规则）
        sb.append("【错误处理】\n");
        sb.append("  • 参数错误：系统会注入该工具的详细参数定义和缺失参数分析，请据此修正后重试\n");
        sb.append("  • 工具不适用：系统会推荐替代工具（回退链），请判断是否适合后调用\n");
        sb.append("  • 同一工具连续失败 2 次：更换策略或向用户澄清\n");
        sb.append("  • 缺少前置信息时（如查天气无城市），可先调用依赖工具（如 location）补充\n\n");

        // 4. 应用定制规则（模型内置知识没有这些，必须明确告知）
        sb.append("【调用规则】\n");
        sb.append("  1. 文件路径：工作区文件用相对路径（如 report.md 或 files/报告.pdf），系统自动解析；外部文件用绝对路径\n");
        sb.append("  2. 涉及权限的操作（定位/相机/录音/存储）先主动调 permission_manager(action=request_and_wait, permission=对应权限名) 请求授权\n");
        sb.append("  3. 善用推理能力先思考再行动，可多轮推理和调用工具\n");
        sb.append("  4. 调用工具是你正常的工作方式：需要实时信息、计算、行动或外部数据时直接调用；不需要工具时直接回答\n");
        sb.append("  5. 按需选参数：多数工具支持多种操作类型（action）与多种参数方式，按用户需求选 action，再填对应参数；完整参数用 tool_registry(action=get, tool=工具名) 查看后调用\n");
        sb.append("  6. 工具调用后会返回结果。先分析结果内容：结果是否回答了用户问题？缺什么信息？据此决定下一步——已满足直接回答，不满足继续调工具补齐；结果不完整、不清晰或与用户问题不符时，换参数/换工具主动探索，直到拿到可用信息。\n");
        sb.append("  7. 信息不足时（实时数据/最新事件/超出已知范围的事实）用搜索类工具补全再答；时效性事实用工具查证后再答\n");
        sb.append("  8. 用户已给出的参数（城市/编码/时间/位置等）直接照用先调用，工具会解析并返回结果，以工具返回为准；参数有误时工具返回错误，再按工具结果调整。\n");
        sb.append("  9. 实时信息（时间/位置/天气/新闻/行情/开奖/政策等）一律用工具获取，训练数据不采纳；缺失或不确定时同样直接调对应工具拿真实结果，工具返回的数据是准确实时的，直接采纳，不要编造结果里没有的数据。\n");
        sb.append("  10. 不确定时用工具测试：不确定参数、数据或结果时，直接调工具拿返回确认，以工具返回为准。\n\n");

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
            case "speech": return "语音";
            case "tool": return "工具管理";
            case "meta": return "元工具";
            case "general": return "通用";
            default: return category;
        }
    }
}
