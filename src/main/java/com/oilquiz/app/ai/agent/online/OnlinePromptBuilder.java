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

        sb.append(buildComponentGuideSection());

        sb.append(buildMemoryGuideSection());

        sb.append("【图片生成】\n");
        sb.append("用户要求生成/画/绘制图片时，优先调用 image_gen 工具（自动下载并内联显示在对话中，无需拼接 URL）；\n");
        sb.append("也可以直接输出 image_grid 组件标记展示图片。避免用 python_execute 或 open_url 这种绕路方式。\n\n");

        sb.append("【输出要求】\n");
        sb.append("- 用中文回答用户问题\n");
        sb.append("- 回答要简洁、准确、有条理\n");
        sb.append("- 倾向用 UI 组件输出信息：凡是有结构的内容（列表、表格、指标、步骤、待办、联系方式、题目、天气、文件、代码等），优先输出组件标记渲染成卡片（见下方组件清单），而不是普通文本或 Markdown 表格\n");
        sb.append("- 如果使用了工具，在回答中自然地融入工具结果\n");
        sb.append("- 如果工具失败，向用户说明原因并提供替代建议\n\n");

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

        sb.append(buildKnowledgeStrategySection());

        sb.append("【自主决策权限】\n");
        sb.append("- 你拥有完整的自主决策权：自主决定调用哪些工具、何时调用、如何组合、是否并行。\n");
        sb.append("- 工具失败时自主判断：修正参数重试、更换工具、向用户澄清，或基于已有信息作答。\n");
        sb.append("- 自主控制推理轮数，直到完成任务或确认无法完成。\n");
        sb.append("- 无需遵循固定流程，发挥你的推理与规划能力以最优方式解决问题。\n");
        sb.append("- 系统会对你每次回复进行评估询问，你需要明确判断是否完成任务并给出最终结论。\n");

        sb.append("【输出要求】\n");
        sb.append("- 用中文回答用户问题\n");
        sb.append("- 回答要简洁、准确、有条理\n");
        sb.append("- 倾向用 UI 组件输出信息：凡是有结构的内容（列表、表格、指标、步骤、待办、联系方式、题目、天气、文件、代码等），优先输出组件标记渲染成卡片（见下方组件清单），而不是普通文本或 Markdown 表格\n");
        sb.append("- 如果使用了工具，在回答中自然地融入工具结果\n");
        sb.append("- 用户要求生成图片时优先调用 image_gen（自动内联显示），或输出 image_grid 组件标记展示；避免用 python_execute/open_url 绕路\n");

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
     * 构建富内容组件渲染指引。
     *
     * 告知模型可用的全部 UI 组件及内容流标记格式（```component:xxx {json}```），
     * 界面会将标记渲染为对应组件并插入到标记所在位置（插入式、流式生效）。
     */
    private String buildComponentGuideSection() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n【富内容组件】有结构的信息用组件标记展示（```component:类型 {json}```），界面自动渲染为卡片。可用类型：\n");
        sb.append("  chart(图表: chartType=bar|line|pie,title,categories,series[{'name','data'}])、info_card(信息卡: title,items[{'label','value'}])、table_card(表格: title,headers,rows)、\n");
        sb.append("  alert_card(提示: type=success|warning|error|info,title,content)、metric_card(指标: title,metrics[{'label','value','color'}])、steps_card(步骤: title,steps[{'status','title','description'}])、\n");
        sb.append("  list_card(列表: title,items[{'icon','title','description','value'}])、note_card(引用: type=note|quote|tip|summary,content)、todo_card(待办: title,items[{'text','done'}])、\n");
        sb.append("  progress_card(进度: title,progress,description)、json_viewer(JSON: title,data)、code_card(代码: language,code,title)、link_card(链接: title,url,description)、\n");
        sb.append("  grid_card(宫格: title,columns,items[{'icon','label'}])、contact_card(联系: type=phone|sms|email,title,value)、file_card(文件: name,size,path)、file_list(文件列表: title,files[{'name','path','size'}])\n");
        sb.append("  image_grid(图片网格: images,columns)、quiz_card(题目: type,question,options,answer,analysis)、weather_card(天气: city,temp,text,icon)、html(富内容: html,title,maxHeight)。\n");
        sb.append("  示例：```component:info_card\\n{\"title\":\"标题\",\"items\":[{\"label\":\"字段\",\"value\":\"值\"}]}```；自定义类型(如 custom_panel)自动通用卡片兜底。\n");
        sb.append("  交互组件（确认/输入/选择/进度/通知等）用 ui_component 工具直接创建系统 UI，不写组件标记。\n");
        sb.append("【组件输出硬性要求】\n");
        sb.append("1. 组件标记必须用三反引号包裹且闭合：```component:类型\\n{JSON}\\n```，类型名只用小写字母/数字/下划线（如 info_card、custom_panel），不要带空格或特殊符号。\n");
        sb.append("2. JSON 必须完整合法：双引号、括号闭合、无注释、无尾随逗号；props 键名与上方示例一致。\n");
        sb.append("3. 无法保证 JSON 合法时，不要输出组件标记——用普通 markdown 表格或列表展示即可，禁止把 JSON 或 component: 源码直接裸露给用户。\n");
        sb.append("4. 一个组件标记块只包含一个组件的 JSON，不要在一个块里放多个对象或数组外层包裹。\n\n");
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
        sb.append("1. 搜索：根据知识构造精准词（查油价→\"国际原油价格\"；查汇率→\"人民币兑美元\"；新闻→关键词+最新；技术→错误信息+关键词）。\n");
        sb.append("2. 数据源：优先权威来源（官方文档/政府网站/主流新闻），实时数据用 network_search 定位 + webpage_reader 提取。\n");
        sb.append("3. 组合：实时信息→search+read；本地数据→database+file；位置→location+weather；翻译/计算→直接专用工具。\n");
        sb.append("4. 交叉验证多来源，结合已有知识整合，不编造数据。\n\n");
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
}
