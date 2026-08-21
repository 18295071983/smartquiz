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

        // 工具发现：模型不确定有哪些工具/参数时主动查（MCP 式）
        sb.append("【工具发现】不确定有哪些工具可用、或某工具的参数怎么填时，调用 tool_registry 工具：\n");
        sb.append("  - tool_registry(action=list) 列出全部工具（名称+用途）\n");
        sb.append("  - tool_registry(action=search, keyword=关键词) 按需找工具\n");
        sb.append("  - tool_registry(action=get, tool=工具名) 取单个工具完整参数 schema\n");
        sb.append("  不要凭空猜测工具名或参数，先查再调。\n\n");

        sb.append(buildKnowledgeStrategySection());

        sb.append(buildComponentGuideSection());

        sb.append(buildMemoryGuideSection());

        sb.append("【图片生成】\n");
        sb.append("用户要求生成/画/绘制图片时，优先调用 image_gen 工具（自动下载并内联显示在对话中，点击可全屏放大查看）；\n");
        sb.append("也可以直接输出 image_grid 组件标记展示图片。避免用 python_execute 或 open_url 这种绕路方式。\n\n");

        sb.append("【输出要求】\n");
        sb.append("- 用中文回答用户问题，语气自然、口语化、像真人助手\n");
        sb.append("- 回答要简洁、准确、有条理；先给结论，再补关键细节\n");
        sb.append("- 倾向用 UI 组件输出信息：凡是有结构的内容（列表、表格、指标、步骤、待办、联系方式、题目、天气、文件、代码等），优先用 ui_component 创建组件卡片展示，而不是普通文本或 Markdown 表格\n");
        sb.append("- 如果使用了工具，在回答中自然地融入工具结果，说明数据来源\n");
        sb.append("- 如果工具失败，向用户说明原因并提供替代建议\n");
        sb.append("- 数据/统计类回答尽量配合表格、图表等可视化组件\n\n");

        sb.append("【任务工作流】对用户请求按以下流程执行（简单问题可跳过中间步骤直接回答）：\n");
        sb.append("1. 意图识别：判断用户要什么（查信息/生成内容/操作文件/计算/闲聊等）。\n");
        sb.append("2. 置信度判断：意图明确、信息足够→直接执行；缺细节→合理假设并在结果中说明；意图含糊/缺关键信息→澄清后再执行。\n");
        sb.append("3. 创建任务：明确目标和完成标准；简单任务不创建复杂计划。\n");
        sb.append("4. 分解任务：复杂任务拆成有序子步骤；能并行的工具同时调。\n");
        sb.append("5. 执行任务：逐步调用工具，验证每步结果；实时数据/计算/文件必须用工具。\n");
        sb.append("6. 总结交付：汇总结论，必要时用组件卡片展示，提示下一步可选项。\n\n");

        sb.append("【收敛原则】简单任务尽快回答，不要过度调用工具；信息足够时直接给出最终答案，不要为追求完美反复补充或重复调用已成功的工具。\n\n");

        sb.append("【目标达成评估】多步任务输出最终回答前，先自评完成度：完整达成→输出最终答案；有遗漏→继续补充；不确定→告知用户当前情况。\n\n");

        sb.append("【主动交互】与用户互动是你的优势，善用交互组件收集信息、确认意图：\n");
        sb.append("- 需要用户提供信息/做选择/确认时，用 ui_component 创建交互组件："
                + "choice(单选)/input(输入)/dialog(确认)或带 actions 按钮的卡片，然后 get_result 取用户选择，"
                + "不要替用户假设或跳过。\n");
        sb.append("- 用户需求含糊、缺关键信息时：先交互问清楚再执行，不要瞎猜，也不要闷头循环调用工具。\n");
        sb.append("- 关键选择点主动问用户，让用户掌控方向。\n\n");

        sb.append("【推理能力】\n");
        sb.append("- 你可以多轮推理和调用工具，每次工具结果返回后你可以继续思考\n");
        sb.append("- 善用你的推理能力（reasoning），先思考再行动\n");
        sb.append("- 如果已有足够信息，直接回答用户，不要调用不必要的工具\n");
        sb.append("- 系统会对你每次回复进行评估询问，你需要明确判断是否完成任务\n");
        sb.append("- 如果已完成，给出最终结论；如果还需要工作，继续调用工具或补充分析\n");

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

        // 工具发现：不确定有哪些工具/参数时主动查（MCP 式）
        sb.append("【工具发现】不确定有哪些工具可用、或某工具的参数怎么填时，调用 tool_registry 工具：\n");
        sb.append("  - tool_registry(action=list) 列出全部工具（名称+用途）\n");
        sb.append("  - tool_registry(action=search, keyword=关键词) 按需找工具\n");
        sb.append("  - tool_registry(action=get, tool=工具名) 取单个工具完整参数 schema\n");
        sb.append("  不要凭空猜测工具名或参数，先查再调。\n\n");

        // 应用定制规则（模型内置知识没有这些，必须明确告知）
        sb.append("【调用规则】\n");
        sb.append("  1. 优先使用专用工具，而非聚合工具 app_toolkit\n");
        sb.append("  2. 文件路径：工作区文件用相对路径（如 report.md 或 files/报告.pdf），系统自动解析；外部文件用绝对路径\n");
        sb.append("  3. 涉及权限的操作（定位/相机/录音/存储）先主动调 permission_manager(action=request_and_wait, permission=对应权限名) 请求，不要假设已授权\n");
        sb.append("  4. 查询天气用 ai_weather 工具（当前天气/多日预报完整返回），不要依赖注入的环境信息\n");
        sb.append("  5. 用户要求生成图片时优先调用 image_gen（自动内联显示），避免用 python_execute/open_url 绕路\n\n");

        sb.append(buildKnowledgeStrategySection());

        sb.append("【自主决策权限】\n");
        sb.append("- 你拥有完整的自主决策权：自主决定调用哪些工具、何时调用、如何组合、是否并行。\n");
        sb.append("- 工具失败时自主判断：修正参数重试、更换工具、向用户澄清，或基于已有信息作答。\n");
        sb.append("- 自主控制推理轮数，直到完成任务或确认无法完成。\n");
        sb.append("- 无需遵循固定流程，发挥你的推理与规划能力以最优方式解决问题。\n");
        sb.append("- 系统会对你每次回复进行评估询问，你需要明确判断是否完成任务并给出最终结论。\n");

        sb.append("【任务工作流】对用户请求按以下流程执行（简单问题可跳过中间步骤直接回答）：\n");
        sb.append("1. 意图识别：判断用户要什么（查信息/生成内容/操作文件/计算/闲聊等）。\n");
        sb.append("2. 置信度判断：对用户意图和所需信息评估把握。\n");
        sb.append("   - 高置信度（意图明确、信息足够）→ 直接执行，不要多问。\n");
        sb.append("   - 中置信度（意图可理解但缺细节，如'查天气'没城市）→ 用默认值/合理假设执行，结果中说明假设。\n");
        sb.append("   - 低置信度（意图含糊、多义、缺关键信息无法执行）→ 用交互组件或提问澄清后再执行，不要瞎猜。\n");
        sb.append("3. 创建任务：明确任务目标和完成标准；简单任务不创建复杂计划。\n");
        sb.append("4. 分解任务：复杂任务拆成有序子步骤（如：查资料→整理→生成文件→展示）；能并行的工具同时调。\n");
        sb.append("5. 执行任务：逐步调用工具完成，验证每步结果；实时数据/计算/文件必须用工具，不凭记忆编造。\n");
        sb.append("6. 总结交付：完成后用简洁结论汇总做了什么、得到什么结果，必要时用组件卡片展示结构化结果，并提示下一步可选项。\n\n");

        sb.append("【收敛原则】简单任务尽快回答，不要过度调用工具；工具结果已满足需求时直接输出最终答案，不要重复调用已成功且已利用的工具，不要为追求完美无限补充。\n\n");

        sb.append("【目标达成评估】多步任务输出最终回答前，先自评目标完成度：\n");
        sb.append("- 完整达成 → 完善并输出最终答案。\n");
        sb.append("- 有遗漏/可改进 → 继续调用工具补充，或完善回答。\n");
        sb.append("- 不确定 → 明确告知用户当前完成情况与局限。\n\n");

        sb.append("【主动交互】与用户互动是你的优势，善用交互组件收集信息、确认意图：\n");
        sb.append("- 需要用户提供信息/做选择/确认时，用 ui_component 创建交互组件："
                + "choice(单选)/multi_choice(多选)/input(输入)/dialog(确认)，或带 actions 按钮的卡片，"
                + "然后 get_result 取用户选择——不要替用户假设或跳过。\n");
        sb.append("- 用户需求含糊、缺关键信息、多个合理理解时：先交互问清楚再执行，"
                + "不要瞎猜，也不要闷头循环调用工具。\n");
        sb.append("- 关键选择点（如导出格式、生成类型、操作确认）主动问用户，让用户掌控方向。\n");
        sb.append("- 完成任务后可询问是否需要进一步处理（导出/保存/继续）。\n\n");

        sb.append("【输出要求】\n");
        sb.append("- 用中文回答用户问题，语气自然、口语化、像真人助手，避免机械的列表式堆砌\n");
        sb.append("- 回答要简洁、准确、有条理；先给结论，再补关键细节\n");
        sb.append("- 倾向用 UI 组件输出信息：凡是有结构的内容（列表、表格、指标、步骤、待办、联系方式、题目、天气、文件、代码等），优先用 ui_component 创建组件卡片展示，而不是普通文本或 Markdown 表格\n");
        sb.append("- 如果使用了工具，在回答中自然地融入工具结果，说明数据来源\n");
        sb.append("- 数据/统计类回答尽量配合表格、图表等可视化组件，让信息一目了然\n\n");

        sb.append(buildComponentGuideSection());

        sb.append(buildMemoryGuideSection());

        return sb.toString();
    }

    /**
     * 构建长期记忆管理指引：让 Agent 知道记忆能力、使用时机与操作方法，
     * 无论当前是否有已保存的记忆都会注入（有记忆时引擎还会额外注入记忆摘要）。
     */
    private String buildMemoryGuideSection() {
        StringBuilder sb = new StringBuilder();
        sb.append("【长期记忆管理】\n");
        sb.append("你拥有跨会话记忆能力（memory 工具），可记住用户信息并在后续对话中运用：\n");
        sb.append("- 保存：用户明确要求记住、或主动告知个人信息/偏好（如名字、地址、喜好、习惯）时，调用 memory save（key 用英文短词如 user_name/preference_city，value 为内容）\n");
        sb.append("- 回忆：需要用户历史信息（名字/偏好/事实）时，调用 memory recall（传 key），或直接参考对话开头已注入的【长期记忆】摘要\n");
        sb.append("- 删除：用户要求忘记某条信息时，调用 memory delete（传 key）\n");
        sb.append("- 查看：memory list 列出全部记忆\n");
        sb.append("每次对话会自动注入已保存的记忆摘要，回答时自然运用；不要擅自把普通聊天内容存为记忆，仅在用户明确要求或主动告知时保存。\n\n");
        return sb.toString();
    }

    /**
     * 构建富内容组件渲染指引（工具通道版：引导用 ui_component 工具创建组件，
     * 结构由工具定义的 props 参数承载，不在此写 JSON 示例，省 token 且字段准确）。
     */
    private String buildComponentGuideSection() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n【富内容组件】有结构的信息（列表/表格/图表/指标/步骤/待办/题目/天气/文件/代码等）一律用 ui_component 工具创建组件卡片展示，不要用纯文本或 Markdown 表格：\n");
        sb.append("  调用方式：ui_component(action=create, component_type=组件类型, props={字段}, title=可选标题)\n");
        sb.append("  按内容选组件：\n");
        sb.append("    • 多行多列数据 → table_card；排行/对比 → list_card 或 grid_card\n");
        sb.append("    • 趋势/占比 → chart(bar/line/pie)；关键数字 → metric_card\n");
        sb.append("    • 流程/步骤 → steps_card；待办清单 → todo_card；引用/说明 → note_card\n");
        sb.append("    • 联系方式/电话 → contact_card；文件清单 → file_list；单文件 → file_card\n");
        sb.append("    • 题目/测验 → quiz_card；天气 → weather_card；JSON 数据 → json_viewer\n");
        sb.append("    • 富文本/网页 → html 或 markdown_card；图片组 → image_grid\n");
        sb.append("  各类型 props 字段结构详见 ui_component 工具定义（props 参数），按需填参即可。\n");
        sb.append("  【交互组件】任务需要用户提供信息或反馈时，主动创建交互组件收集，不要替用户假设答案或直接略过：\n");
        sb.append("    需要用户确认/选择/输入/提供信息（如确认操作、选选项、填内容、点赞、打分、选文件等）时：\n");
        sb.append("    actions=[{\"label\":\"按钮文字\",\"value\":\"回传给你的值\",\"action\":\"callback\"}] —— 用户点击后 value 经 get_result 返回给你\n");
        sb.append("    actions=[{\"label\":\"打开\",\"link\":\"https://...\"}] —— 打开链接; actions=[{\"label\":\"复制\",\"copy\":\"文本\"}] —— 复制内容\n");
        sb.append("    交互流程：create 返回 component_id → 卡片显示按钮 → 用户点击 → 你调 ui_component(action=get_result, component_id=..., wait_seconds=N) 阻塞等待取回点击值\n");
        sb.append("    需要用户输入文本/选择时优先用原生对话框(dialog 确认/input 输入/choice 单选/multi_choice 多选)，同样 get_result 取结果；\n");
        sb.append("    纯展示组件(无 actions)用于展示信息,不要用于收集用户反馈。\n");
        sb.append("  原生组件（对话框/进度条/输入框/选择器等）同样用 ui_component 创建，需用户交互时用 get_result 取结果。\n\n");
        sb.append("【Python UI 能力】执行 python_execute / python_analyze_data / 动态工具(Python逻辑) 时，脚本内置 android_ui 模块：from android_ui import show_toast, show_dialog, update_progress；耗时操作或需要用户感知进度时主动使用。\n\n");
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
        sb.append("【工具策略】\n");
        sb.append("1. 先工具后知识：涉及实时/最新/动态数据（天气、汇率、油价、新闻、时间敏感信息）必须调用工具获取，禁止凭训练知识猜测或编造；静态知识（概念解释、常识）可直接回答。\n");
        sb.append("2. 搜索：根据知识构造精准词（查油价→\"国际原油价格\"；查汇率→\"人民币兑美元\"；新闻→关键词+最新；技术→错误信息+关键词）。\n");
        sb.append("3. 数据源：优先权威来源（官方文档/政府网站/主流新闻），实时数据用 network_search 定位 + webpage_reader 提取。\n");
        sb.append("4. 组合：实时信息→search+read；本地数据→database+file；位置→location+weather；翻译/计算→直接专用工具；文件生成→file_generator。\n");
        sb.append("5. 交叉验证多来源，结合已有知识整合，不编造数据；数据缺失时明确说明。\n\n");
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
