package com.oilquiz.app.ai.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 工具引导流程系统。
 *
 * 为每个工具定义硬编码的分步引导流程。用户通过点击选项和填写关键字段完成工具执行，
 * 全程无需 LLM 参与。
 *
 * 设计要点：
 * - {@link GuideStep} 描述单个步骤（选项 / 输入 / 确认）
 * - 步骤可通过 conditionKey + conditionValue 实现条件分支，仅在前序参数匹配时展示
 * - conditionValue 支持 "|" 分隔多值匹配
 * - {@link #getActiveSteps(Map)} 根据已选参数过滤出当前应当展示的步骤
 * - {@link #getFlow(String)} 工厂方法返回指定工具的完整引导流程
 * - {@link #contextDependencies} 声明工具执行前依赖的环境上下文（如 "location"）
 */
public class ToolGuideFlow {

    /** 工具名（如 "ai_weather"） */
    public String toolName;
    /** 工具显示名（如 "天气查询"） */
    public String toolDisplayName;
    /** 工具描述 */
    public String toolDescription;
    /** 步骤列表 */
    public List<GuideStep> steps;
    /** 上下文依赖（如天气工具依赖 "location"），供执行前注入环境上下文 */
    public List<String> contextDependencies;

    public ToolGuideFlow() {}

    public ToolGuideFlow(String toolName, String toolDisplayName, String toolDescription, List<GuideStep> steps) {
        this.toolName = toolName;
        this.toolDisplayName = toolDisplayName;
        this.toolDescription = toolDescription;
        this.steps = steps;
    }

    /**
     * 获取过滤后的步骤（根据已选参数过滤条件分支步骤）。
     *
     * 规则：
     * - 无 conditionKey 的步骤始终展示
     * - 有 conditionKey 的步骤，仅当 selectedParams 中对应参数值匹配 conditionValue
     *   （支持 "|" 分隔多值）时才展示
     *
     * @param selectedParams 已选参数（key 为参数名，value 为参数值）
     * @return 当前应当展示的步骤列表
     */
    public List<GuideStep> getActiveSteps(Map<String, String> selectedParams) {
        List<GuideStep> active = new ArrayList<>();
        if (steps == null) {
            return active;
        }
        for (GuideStep step : steps) {
            if (step == null) {
                continue;
            }
            // 无条件分支：始终展示
            if (step.conditionKey == null || step.conditionKey.isEmpty()) {
                active.add(step);
                continue;
            }
            // 条件分支：检查前序参数是否匹配
            String selectedValue = selectedParams == null ? null : selectedParams.get(step.conditionKey);
            if (selectedValue == null || step.conditionValue == null || step.conditionValue.isEmpty()) {
                continue;
            }
            // 支持 "|" 分隔多值匹配
            String[] allowedValues = step.conditionValue.split("\\|");
            for (String allowed : allowedValues) {
                if (allowed.equals(selectedValue)) {
                    active.add(step);
                    break;
                }
            }
        }
        return active;
    }

    /**
     * 工厂方法：获取工具的引导流程。
     *
     * @param toolName 工具名
     * @return 对应工具的引导流程；未定义时返回 null
     */
    public static ToolGuideFlow getFlow(String toolName) {
        if (toolName == null) {
            return null;
        }
        switch (toolName) {
            case "ai_weather":
                return buildAiWeather();
            case "network_search":
                return buildNetworkSearch();
            case "database":
                return buildDatabase();
            case "location":
                return buildLocation();
            case "app_operation":
                return buildAppOperation();
            case "file_reader":
                return buildFileReader();
            case "file_analyzer":
                return buildFileAnalyzer();
            case "file_generator":
                return buildFileGenerator();
            case "webpage_reader":
                return buildWebpageReader();
            case "smart_research":
                return buildSmartResearch();
            case "system_resource":
                return buildSystemResource();
            case "permission_manager":
                return buildPermissionManager();
            case "python_calculate":
                return buildPythonCalculate();
            default:
                // 动态兜底：未定义精细引导的工具，从 AIToolManager 工具定义自动生成通用引导
                // （枚举参数→选项步骤、必填参数→输入步骤、其余→可选输入步骤、最后确认步骤）。
                // 保证工具描述/参数始终与模型 tools 参数同一真相源，新增工具无需手写引导。
                return buildDynamicFlow(toolName);
        }
    }

    /**
     * 从 AIToolManager 工具定义动态生成通用引导流程（兜底）。
     * 数据源与模型 tools 参数一致（AIToolManager.getToolDefinition），
     * 保证引导界面展示的描述/参数与模型实际看到的完全同步。
     */
    private static ToolGuideFlow buildDynamicFlow(String toolName) {
        try {
            com.oilquiz.app.ai.tool.AIToolManager manager =
                    com.oilquiz.app.ai.tool.AIToolManager.getInstance(
                            com.oilquiz.app.SmartQuizApplication.getAppContext());
            com.oilquiz.app.ai.tool.openai.ToolDefinition def = manager.getToolDefinition(toolName);
            if (def == null) {
                return null;
            }
            List<GuideStep> steps = new ArrayList<>();
            List<com.oilquiz.app.ai.tool.openai.ParamDefinition> params = def.getParameters();
            if (params != null) {
                for (com.oilquiz.app.ai.tool.openai.ParamDefinition p : params) {
                    String title = (p.getDescription() != null && !p.getDescription().isEmpty())
                            ? p.getDescription() : ("填写 " + p.getName());
                    // 枚举参数 → 选项步骤（选择列表）
                    if (p.getEnumValues() != null && !p.getEnumValues().isEmpty()) {
                        List<GuideStep.Option> options = new ArrayList<>();
                        for (String enumVal : p.getEnumValues()) {
                            options.add(new GuideStep.Option(enumVal, enumVal));
                        }
                        steps.add(GuideStep.optionStep(title, title, p.getName(), options));
                        continue;
                    }
                    // 必填参数 → 必填输入步骤
                    if (p.isRequired()) {
                        steps.add(GuideStep.inputStep(title, title, p.getName(),
                                p.getDescription() != null ? p.getDescription() : "请输入",
                                true, false));
                        continue;
                    }
                    // 可选参数 → 可选输入步骤（带默认值提示）
                    String hint = p.getDescription() != null ? p.getDescription() : "可留空";
                    if (p.getDefaultValue() != null) {
                        hint = "默认: " + p.getDefaultValue() + "（" + hint + "）";
                    }
                    steps.add(GuideStep.inputStep(title, title, p.getName(), hint, false, false));
                }
            }
            // 确认步骤
            steps.add(GuideStep.confirmStep("确认执行", "参数填写完成，点击执行"));
            String displayName = toolName;
            String desc = def.getDescription() != null ? def.getDescription() : "";
            return new ToolGuideFlow(toolName, displayName, desc, steps);
        } catch (Throwable t) {
            android.util.Log.w("ToolGuideFlow", "动态生成引导失败: " + t.getMessage());
            return null;
        }
    }

    // ==================== 各工具流程定义 ====================

    /** ai_weather 天气查询 */
    private static ToolGuideFlow buildAiWeather() {
        List<GuideStep> steps = new ArrayList<>();
        // 步骤1：选择操作类型
        steps.add(GuideStep.optionStep(
                "想查点什么?",
                "选要查的天气信息",
                "action",
                Arrays.asList(
                        new GuideStep.Option("现在天气", "current"),
                        new GuideStep.Option("未来天气", "forecast"),
                        new GuideStep.Option("24小时", "hourly"),
                        new GuideStep.Option("空气", "air_quality"),
                        new GuideStep.Option("预警", "alerts"),
                        new GuideStep.Option("生活指数", "indices"),
                        new GuideStep.Option("全部", "all")
                )
        ));
        // 步骤2：选择城市（点选常用城市；"自动定位"使用当前位置）
        steps.add(GuideStep.optionStep(
                "哪个城市?",
                "选择城市（自动定位使用当前位置）",
                "city",
                Arrays.asList(
                        new GuideStep.Option("📍 自动定位", ""),
                        new GuideStep.Option("银川", "银川"),
                        new GuideStep.Option("北京", "北京"),
                        new GuideStep.Option("上海", "上海"),
                        new GuideStep.Option("广州", "广州"),
                        new GuideStep.Option("深圳", "深圳"),
                        new GuideStep.Option("西安", "西安"),
                        new GuideStep.Option("成都", "成都")
                )
        ));
        // 步骤3：确认执行
        steps.add(GuideStep.confirmStep("确认执行", "信息无误就点执行"));
        ToolGuideFlow flow = new ToolGuideFlow("ai_weather", "天气查询",
                "查询天气、预报、空气质量、预警等信息", steps);
        // 天气工具依赖位置上下文（留空城市时自动定位）
        flow.contextDependencies = Arrays.asList("location");
        return flow;
    }

    /** network_search 联网搜索 */
    private static ToolGuideFlow buildNetworkSearch() {
        List<GuideStep> steps = new ArrayList<>();
        // 步骤1：选择操作类型
        steps.add(GuideStep.optionStep(
                "想搜什么?",
                "选要做的网络操作",
                "action",
                Arrays.asList(
                        new GuideStep.Option("搜一下", "search"),
                        new GuideStep.Option("问一问", "ask"),
                        new GuideStep.Option("读网页", "read_url")
                )
        ));
        // 步骤2a：搜索关键词（仅 search）
        steps.add(GuideStep.inputStep(
                "搜什么关键词?",
                "输入搜索词",
                "query",
                "输入搜索关键词",
                true,
                false,
                "action", "search"
        ));
        // 步骤2b：结果数量（仅 search）
        steps.add(GuideStep.optionStep(
                "选择结果数量",
                "选返回几条结果，默认5条",
                "limit",
                Arrays.asList(
                        new GuideStep.Option("3条", "3"),
                        new GuideStep.Option("5条", "5"),
                        new GuideStep.Option("10条", "10")
                ),
                "action", "search"
        ));
        // 步骤2c：智能问答问题（仅 ask）
        steps.add(GuideStep.inputStep(
                "想问什么?",
                "输入要问的问题",
                "question",
                "输入问题",
                true,
                true,
                "action", "ask"
        ));
        // 步骤2d：问答模式（仅 ask）
        steps.add(GuideStep.optionStep(
                "要多详细?",
                "选问答的深度",
                "model",
                Arrays.asList(
                        new GuideStep.Option("简洁", "concise"),
                        new GuideStep.Option("深入", "detail"),
                        new GuideStep.Option("研究", "research")
                ),
                "action", "ask"
        ));
        // 步骤2e：网页URL（仅 read_url）
        steps.add(GuideStep.inputStep(
                "网页地址是?",
                "输入要读的网页地址",
                "url",
                "输入网页URL",
                true,
                false,
                "action", "read_url"
        ));
        // 步骤3：确认执行
        steps.add(GuideStep.confirmStep("确认执行", "信息无误就点执行"));
        return new ToolGuideFlow("network_search", "联网搜索",
                "网络搜索、智能问答、网页读取", steps);
    }

    /** database 数据库工具 */
    private static ToolGuideFlow buildDatabase() {
        List<GuideStep> steps = new ArrayList<>();
        // 步骤1：选择操作类型
        steps.add(GuideStep.optionStep(
                "想做什么?",
                "选要做的数据库操作",
                "action",
                Arrays.asList(
                        new GuideStep.Option("浏览题目列表", "get_questions"),
                        new GuideStep.Option("按关键词搜索", "search_questions"),
                        new GuideStep.Option("按ID查题目", "get_question_by_id"),
                        new GuideStep.Option("列出所有表", "list_tables"),
                        new GuideStep.Option("查看表结构", "get_table_schema"),
                        new GuideStep.Option("获取所有分类", "get_all_categories"),
                        new GuideStep.Option("获取所有类型", "get_all_question_types"),
                        new GuideStep.Option("获取题目数量", "get_question_count"),
                        new GuideStep.Option("获取统计信息", "get_question_statistics"),
                        new GuideStep.Option("获取数据库版本", "get_database_version")
                )
        ));
        // 步骤2a：选择题分类（仅 get_questions，动态拉取分类列表供点选，可选）
        steps.add(GuideStep.inputStep(
                "按什么分类浏览?",
                "从列表选择分类，或手动输入；留空查看全部",
                "category",
                "如：安全、消防（可留空）",
                false,
                false,
                "action", "get_questions",
                new GuideStep.DynamicOptionsSpec("database", "get_all_categories", "categories", null, "📖 全部分类（不限）")
        ));
        // 步骤2b：输入搜索关键词（仅 search_questions）
        steps.add(GuideStep.inputStep(
                "搜什么关键词?",
                "输入要搜的题目关键词",
                "keyword",
                "输入搜索关键词",
                true,
                false,
                "action", "search_questions"
        ));
        // 步骤2c：输入题目ID（仅 get_question_by_id）
        steps.add(GuideStep.inputStep(
                "题目ID是多少?",
                "输入要查的题目ID",
                "id",
                "输入题目ID",
                true,
                false,
                "action", "get_question_by_id"
        ));
        // 步骤2d：选择表名（仅 get_table_schema，动态拉取表列表供点选）
        steps.add(GuideStep.inputStep(
                "查哪张表?",
                "从列表选择表，或手动输入表名",
                "table_name",
                "如：questions",
                true,
                false,
                "action", "get_table_schema",
                new GuideStep.DynamicOptionsSpec("database", "list_tables", "tables", "name", null)
        ));
        // 步骤3：确认执行
        steps.add(GuideStep.confirmStep("确认执行", "信息无误就点执行"));
        return new ToolGuideFlow("database", "题库数据库",
                "题目浏览与搜索、表结构查看、分类统计、数据库信息等", steps);
    }

    /** location 定位工具 */
    private static ToolGuideFlow buildLocation() {
        List<GuideStep> steps = new ArrayList<>();
        // 步骤1：选择操作类型
        steps.add(GuideStep.optionStep(
                "想做什么?",
                "选要做的定位操作",
                "action",
                Arrays.asList(
                        new GuideStep.Option("获取当前位置", "get_current"),
                        new GuideStep.Option("获取城市名", "get_city"),
                        new GuideStep.Option("获取坐标", "get_coordinates")
                )
        ));
        // 步骤2：确认执行
        steps.add(GuideStep.confirmStep("确认执行", "信息无误就点执行"));
        return new ToolGuideFlow("location", "定位服务",
                "获取当前位置、城市名、坐标等信息", steps);
    }

    /** app_operation 应用操作 */
    private static ToolGuideFlow buildAppOperation() {
        List<GuideStep> steps = new ArrayList<>();
        // 步骤1：选择操作类型（补全 get_info / open_settings / share）
        steps.add(GuideStep.optionStep(
                "想做什么?",
                "选要做的应用操作",
                "action",
                Arrays.asList(
                        new GuideStep.Option("跳转页面", "navigate"),
                        new GuideStep.Option("列出所有页面", "list_pages"),
                        new GuideStep.Option("回到主页", "go_home"),
                        new GuideStep.Option("返回上一页", "go_back"),
                        new GuideStep.Option("获取应用信息", "get_info"),
                        new GuideStep.Option("打开系统设置", "open_settings"),
                        new GuideStep.Option("分享内容", "share")
                )
        ));
        // 步骤2：选择要跳转的页面（仅 navigate）— 改为常用页面下拉 + 兜底手动输入
        steps.add(GuideStep.optionStep(
                "跳转到哪个页面?",
                "选择常用页面，或在下一步手动输入",
                "page",
                Arrays.asList(
                        new GuideStep.Option("🏠 主页 / 首页", "home"),
                        new GuideStep.Option("🤖 AI 中心", "ai"),
                        new GuideStep.Option("📚 题库", "question"),
                        new GuideStep.Option("📝 开始答题", "start_quiz"),
                        new GuideStep.Option("🎯 答题中", "quiz"),
                        new GuideStep.Option("👤 我的 / 用户中心", "user"),
                        new GuideStep.Option("📋 学习计划", "study_plan"),
                        new GuideStep.Option("❌ 错题本", "wrong_question"),
                        new GuideStep.Option("📒 笔记", "note"),
                        new GuideStep.Option("📷 OCR 扫描", "ocr"),
                        new GuideStep.Option("📥 导入题目", "import"),
                        new GuideStep.Option("📖 导入指南", "import_guide"),
                        new GuideStep.Option("🧠 题目生成", "question_generate"),
                        new GuideStep.Option("🩺 环境检查", "environment_check"),
                        new GuideStep.Option("📤 导出", "export"),
                        new GuideStep.Option("💾 备份", "backup"),
                        new GuideStep.Option("🎨 主题", "theme"),
                        new GuideStep.Option("🌍 语言", "language"),
                        new GuideStep.Option("📄 文件预览", "file_preview"),
                        new GuideStep.Option("📊 统计", "statistics"),
                        new GuideStep.Option("🗄️ 数据库管理", "database"),
                        new GuideStep.Option("📜 日志", "logs"),
                        new GuideStep.Option("ℹ️ 关于", "about"),
                        new GuideStep.Option("🧰 工具箱", "toolbox")
                ),
                "action", "navigate"
        ));
        // 步骤2b：选择系统设置项（仅 open_settings）
        steps.add(GuideStep.optionStep(
                "打开哪个系统设置?",
                "选择要打开的系统设置页面",
                "setting",
                Arrays.asList(
                        new GuideStep.Option("📶 WiFi", "wifi"),
                        new GuideStep.Option("🔵 蓝牙", "bluetooth"),
                        new GuideStep.Option("📍 定位", "location"),
                        new GuideStep.Option("🖥️ 显示 / 亮度", "display"),
                        new GuideStep.Option("🔊 声音", "sound"),
                        new GuideStep.Option("💾 存储", "storage"),
                        new GuideStep.Option("📱 应用管理", "app"),
                        new GuideStep.Option("⚙️ 全部设置", "all")
                ),
                "action", "open_settings"
        ));
        // 步骤2c：分享文本（仅 share）—— 标题（可选，单行）
        steps.add(GuideStep.inputStep(
                "分享标题(可选)?",
                "留空使用默认标题",
                "title",
                "例如：分享自答题宝",
                false, false,
                "action", "share"
        ));
        // 步骤2d：分享文本（仅 share）—— 正文（必填，多行）
        steps.add(GuideStep.inputStep(
                "分享什么内容?",
                "输入要分享的文本内容",
                "text",
                "输入分享内容",
                true, true,
                "action", "share"
        ));
        // 步骤3：确认执行
        steps.add(GuideStep.confirmStep("确认执行", "信息无误就点执行"));
        return new ToolGuideFlow("app_operation", "应用操作",
                "页面导航、列出页面、回主页、返回、应用信息、打开系统设置、分享内容", steps);
    }

    private static ToolGuideFlow buildFileReader() {
        List<GuideStep> steps = new ArrayList<>();
        steps.add(GuideStep.optionStep(
                "想怎么读?",
                "选择阅读方式",
                "action",
                Arrays.asList(
                        new GuideStep.Option("完整读取", "read"),
                        new GuideStep.Option("按行读取", "read_lines"),
                        new GuideStep.Option("搜索文本", "search_text"),
                        new GuideStep.Option("提取实体", "extract_entities"),
                        new GuideStep.Option("提取文本", "extract_text"),
                        new GuideStep.Option("文件预览", "preview")
                )
        ));
        // 文件选择
        steps.add(GuideStep.filePickerStep(
                "选择文件",
                "点击按钮打开文件管理器选择要读取的文件",
                "file_path",
                true,
                null,
                false,
                null, null
        ));
        // 编码选择（可选）
        steps.add(GuideStep.optionStep(
                "文件编码?",
                "选择文件编码，默认UTF-8",
                "encoding",
                Arrays.asList(
                        new GuideStep.Option("UTF-8(推荐)", "UTF-8"),
                        new GuideStep.Option("GBK", "GBK"),
                        new GuideStep.Option("GB18030", "GB18030"),
                        new GuideStep.Option("自动检测", "auto")
                ),
                null, null
        ));
        // 按行读取参数（仅 read_lines）
        steps.add(GuideStep.inputStep(
                "起始行号?",
                "从第几行开始读，留空默认从开头",
                "startLine",
                "例如：1",
                false, false,
                "action", "read_lines"
        ));
        steps.add(GuideStep.inputStep(
                "结束行号?",
                "读到第几行，留空默认读 50 行",
                "endLine",
                "例如：100，留空默认 50 行",
                false, false,
                "action", "read_lines"
        ));
        // 搜索关键词（仅 search_text）
        steps.add(GuideStep.inputStep(
                "搜什么?",
                "输入要搜索的关键词",
                "keyword",
                "输入关键词",
                true, false,
                "action", "search_text"
        ));
        steps.add(GuideStep.confirmStep("确认执行", "信息无误就点执行"));
        return new ToolGuideFlow("file_reader", "文件阅读",
                "支持读取文本、按行读取、搜索文本、提取实体、预览等", steps);
    }

    /** file_analyzer 文件分析工具 */
    private static ToolGuideFlow buildFileAnalyzer() {
        List<GuideStep> steps = new ArrayList<>();
        steps.add(GuideStep.filePickerStep(
                "选择要分析的文件",
                "点击按钮打开文件管理器选择文件",
                "file_path",
                true,
                null, false, null, null
        ));
        steps.add(GuideStep.optionStep(
                "分析类型?",
                "选择分析类型（可选）",
                "analysis_type",
                Arrays.asList(
                        new GuideStep.Option("自动分析", "auto"),
                        new GuideStep.Option("结构分析", "structure"),
                        new GuideStep.Option("内容摘要", "summary"),
                        new GuideStep.Option("统计信息", "stats")
                ),
                null, null
        ));
        steps.add(GuideStep.confirmStep("确认执行", "信息无误就点执行"));
        return new ToolGuideFlow("file_analyzer", "文件分析",
                "分析文件内容、结构、统计信息等", steps);
    }

    /** file_generator 文件生成工具 */
    private static ToolGuideFlow buildFileGenerator() {
        List<GuideStep> steps = new ArrayList<>();
        steps.add(GuideStep.optionStep(
                "生成什么文件?",
                "选择文件生成方式",
                "action",
                Arrays.asList(
                        new GuideStep.Option("创建普通文件", "create"),
                        new GuideStep.Option("追加写入", "append"),
                        new GuideStep.Option("JSON文件", "json"),
                        new GuideStep.Option("配置文件", "config"),
                        new GuideStep.Option("Markdown文件", "markdown"),
                        new GuideStep.Option("报告文件", "report"),
                        new GuideStep.Option("从模板生成", "template"),
                        new GuideStep.Option("复制文件", "copy"),
                        new GuideStep.Option("删除文件", "delete")
                )
        ));
        // 文件名（未指定绝对路径时自动保存到 Agent 工作区，无需选路径）
        steps.add(GuideStep.inputStep(
                "文件名?",
                "只输文件名即可，默认保存到工作区；如需指定位置可输完整路径",
                "file_name",
                "例如 output.txt（自动存到工作区）",
                true, false,
                "action", "create|append|json|config|markdown|report|template"
        ));
        // delete 操作的文件路径
        steps.add(GuideStep.filePickerStep(
                "选择要删除的文件",
                "点击按钮选择要删除的文件",
                "file_name",
                true,
                null, false,
                "action", "delete"
        ));
        // copy 操作：源文件
        steps.add(GuideStep.filePickerStep(
                "选择源文件",
                "点击按钮选择要复制的源文件",
                "source_path",
                true,
                null, false,
                "action", "copy"
        ));
        steps.add(GuideStep.inputStep(
                "目标文件名?",
                "输入复制后的目标文件名，默认保存到工作区",
                "file_name",
                "例如 copy.txt（自动存到工作区）",
                true, false,
                "action", "copy"
        ));
        // 内容输入（create/append/markdown）
        steps.add(GuideStep.inputStep(
                "文件内容?",
                "输入要写入的内容",
                "content",
                "输入文件内容",
                false, true,
                "action", "create|append|markdown"
        ));
        // 标题（markdown/report）
        steps.add(GuideStep.inputStep(
                "标题?",
                "输入标题（可选）",
                "title",
                "文档标题",
                false, false,
                "action", "markdown|report"
        ));
        steps.add(GuideStep.confirmStep("确认执行", "信息无误就点执行"));
        return new ToolGuideFlow("file_generator", "文件生成",
                "创建、追加、复制、删除文件，支持JSON/Markdown/配置等格式", steps);
    }

    /** webpage_reader 网页阅读工具 */
    private static ToolGuideFlow buildWebpageReader() {
        List<GuideStep> steps = new ArrayList<>();
        steps.add(GuideStep.optionStep(
                "想怎么读网页?",
                "选择阅读方式",
                "action",
                Arrays.asList(
                        new GuideStep.Option("完整读取", "read"),
                        new GuideStep.Option("提取关键信息", "extract"),
                        new GuideStep.Option("智能摘要", "summarize"),
                        new GuideStep.Option("批量读取多页", "read_multiple"),
                        new GuideStep.Option("跟踪链接深入", "follow_links")
                )
        ));
        // 单URL输入
        steps.add(GuideStep.inputStep(
                "网页地址是?",
                "输入要读取的网页URL",
                "url",
                "例如：https://example.com/article",
                true, false,
                "action", "read|extract|summarize|follow_links"
        ));
        // 多URL（read_multiple 特殊处理：提示一行一个）
        steps.add(GuideStep.inputStep(
                "多个网页URL?",
                "每行输入一个URL，或用逗号分隔",
                "url",
                "URL列表，每行一个",
                true, true,
                "action", "read_multiple"
        ));
        // 搜索查询（extract 关联用）
        steps.add(GuideStep.inputStep(
                "相关搜索词?",
                "用于相关性计算（可选）",
                "query",
                "输入关键词",
                false, false,
                "action", "extract"
        ));
        // 最大链接深度（follow_links）
        steps.add(GuideStep.optionStep(
                "跟踪深度?",
                "选择链接跟踪深度，默认2层",
                "maxDepth",
                Arrays.asList(
                        new GuideStep.Option("1层(当前页)", "1"),
                        new GuideStep.Option("2层(推荐)", "2"),
                        new GuideStep.Option("3层", "3")
                ),
                "action", "follow_links"
        ));
        steps.add(GuideStep.confirmStep("确认执行", "信息无误就点执行"));
        return new ToolGuideFlow("webpage_reader", "网页阅读",
                "获取网页内容、提取信息、生成摘要、批量读取、跟踪链接", steps);
    }

    /** smart_research 智能研究工具 */
    private static ToolGuideFlow buildSmartResearch() {
        List<GuideStep> steps = new ArrayList<>();
        steps.add(GuideStep.optionStep(
                "用哪种研究方式?",
                "选择研究模式",
                "action",
                Arrays.asList(
                        new GuideStep.Option("完整研究流程", "research"),
                        new GuideStep.Option("快速搜索", "quick_search"),
                        new GuideStep.Option("深度阅读", "deep_read"),
                        new GuideStep.Option("主题摘要", "summarize_topic")
                )
        ));
        // 研究主题（topic 与 query 互为别名，只需填一个，避免重复必填）
        steps.add(GuideStep.inputStep(
                "研究什么主题?",
                "输入研究主题或关键词",
                "topic",
                "例如：人工智能最新进展",
                true, false,
                "action", "research|quick_search|summarize_topic"
        ));
        // 深度阅读需要 URL（deep_read 必填 urls，工具侧已兼容单个 url 字符串）
        steps.add(GuideStep.inputStep(
                "要深度阅读哪个网页?",
                "输入要深度阅读的网址",
                "url",
                "例如：https://example.com/article",
                true, false,
                "action", "deep_read"
        ));
        // 最大结果数
        steps.add(GuideStep.optionStep(
                "结果数量?",
                "选择返回结果数量，默认5",
                "maxResults",
                Arrays.asList(
                        new GuideStep.Option("3条", "3"),
                        new GuideStep.Option("5条(推荐)", "5"),
                        new GuideStep.Option("10条", "10")
                ),
                null, null
        ));
        steps.add(GuideStep.confirmStep("确认执行", "信息无误就点执行"));
        return new ToolGuideFlow("smart_research", "智能研究",
                "自动完成搜索→选择→阅读→摘要的完整研究流程", steps);
    }

    /** system_resource 系统资源调用工具 */
    private static ToolGuideFlow buildSystemResource() {
        List<GuideStep> steps = new ArrayList<>();
        steps.add(GuideStep.optionStep(
                "想做什么系统操作?",
                "选择系统级操作",
                "action",
                Arrays.asList(
                        new GuideStep.Option("打开应用", "open_app"),
                        new GuideStep.Option("打开网址", "open_url"),
                        new GuideStep.Option("发送短信", "send_sms"),
                        new GuideStep.Option("拨打电话", "make_call"),
                        new GuideStep.Option("列出已装应用", "list_apps"),
                        new GuideStep.Option("获取应用信息", "get_app_info"),
                        new GuideStep.Option("执行Shell命令(含内置busybox)", "shell_command"),
                        new GuideStep.Option("在Termux中执行命令", "termux_exec")
                )
        ));
        // 打开应用：应用名（动态拉取已装应用列表供点选，减少手动输入）
        steps.add(GuideStep.inputStep(
                "打开哪个应用?",
                "从下方选择已安装应用，或直接输入应用名",
                "app_name",
                "应用名称，可从下方点选",
                true, false,
                "action", "open_app|get_app_info",
                new GuideStep.DynamicOptionsSpec("system_resource", "list_apps", "user_apps", "name", null)
        ));
        // 打开网址：URL
        steps.add(GuideStep.inputStep(
                "打开哪个网址?",
                "输入要打开的URL",
                "url",
                "例如：https://www.baidu.com",
                true, false,
                "action", "open_url"
        ));
        // 发送短信/拨打电话：号码
        steps.add(GuideStep.inputStep(
                "对方号码?",
                "输入电话号码",
                "phone_number",
                "例如：13800138000",
                true, false,
                "action", "send_sms|make_call"
        ));
        // 短信内容
        steps.add(GuideStep.inputStep(
                "短信内容?",
                "输入要发送的短信内容",
                "message",
                "输入短信内容",
                true, true,
                "action", "send_sms"
        ));
        // 执行命令：command
        steps.add(GuideStep.inputStep(
                "执行什么命令?",
                "shell_command 走系统 shell（内置 busybox：ash/wget/awk/vi/telnet 等，无需安装）",
                "command",
                "例如：pm list packages | head -20 或 wget -O /sdcard/a.zip URL",
                true, false,
                "action", "shell_command|termux_exec"
        ));
        steps.add(GuideStep.confirmStep("确认执行", "信息无误就点执行"));
        return new ToolGuideFlow("system_resource", "系统资源",
                "打开应用/网址、发送短信、拨打电话，或执行 Shell 命令（内置 busybox 工具链）/ Termux 命令", steps);
    }

    /** permission_manager 权限管理工具 */
    private static ToolGuideFlow buildPermissionManager() {
        List<GuideStep> steps = new ArrayList<>();
        steps.add(GuideStep.optionStep(
                "想做什么权限操作?",
                "选择权限管理操作",
                "action",
                Arrays.asList(
                        new GuideStep.Option("检查权限", "check"),
                        new GuideStep.Option("批量检查", "check_all"),
                        new GuideStep.Option("请求权限", "request"),
                        new GuideStep.Option("请求并等待", "request_and_wait"),
                        new GuideStep.Option("获取权限状态", "get_status"),
                        new GuideStep.Option("列出所有权限", "list_permissions"),
                        new GuideStep.Option("权限说明", "explain_permission"),
                        new GuideStep.Option("是否可请求", "can_request")
                )
        ));
        // 单选权限
        steps.add(GuideStep.optionStep(
                "哪个权限?",
                "选择要操作的权限",
                "permission",
                Arrays.asList(
                        new GuideStep.Option("相机", "camera"),
                        new GuideStep.Option("位置/定位", "location"),
                        new GuideStep.Option("录音/麦克风", "record_audio"),
                        new GuideStep.Option("存储/读写", "storage"),
                        new GuideStep.Option("拨打电话", "call_phone"),
                        new GuideStep.Option("发送短信", "send_sms"),
                        new GuideStep.Option("通讯录", "contacts"),
                        new GuideStep.Option("通知", "notification")
                ),
                "action", "check|request|request_and_wait|get_status|explain_permission|can_request"
        ));
        steps.add(GuideStep.confirmStep("确认执行", "信息无误就点执行"));
        return new ToolGuideFlow("permission_manager", "权限管理",
                "检查、请求、管理应用权限，支持说明与状态查询", steps);
    }

    /** python_calculate Python数学计算工具 */
    private static ToolGuideFlow buildPythonCalculate() {
        List<GuideStep> steps = new ArrayList<>();
        steps.add(GuideStep.inputStep(
                "计算什么?",
                "输入数学表达式，支持 + - * / () % 幂等",
                "expression",
                "例如：(2+3)*4 / sqrt(16) 或 sin(pi/2)",
                true, false,
                null, null
        ));
        steps.add(GuideStep.inputStep(
                "任务描述(可选)",
                "简要说明计算目的（可选）",
                "task",
                "例如：求三角形斜边",
                false, false,
                null, null
        ));
        steps.add(GuideStep.confirmStep("确认执行", "信息无误就点执行"));
        return new ToolGuideFlow("python_calculate", "数学计算",
                "使用Python进行精确数学计算，支持复杂表达式和函数", steps);
    }

    // ==================== 引导步骤数据模型 ====================

    /**
     * 引导步骤。描述用户完成工具执行所需的单步操作。
     * 支持类型：选项选择（OPTION）、文本输入（INPUT）、确认执行（CONFIRM）、
     * 文件选择（FILE_PICKER）、图片选择（IMAGE_PICKER）、目录选择（DIRECTORY_PICKER）。
     */
    public static class GuideStep {
        /** 步骤类型 */
        public enum StepType { OPTION, INPUT, CONFIRM, FILE_PICKER, IMAGE_PICKER, DIRECTORY_PICKER }

        /** 步骤类型 */
        public StepType type;
        /** 步骤标题（如 "选择操作类型"） */
        public String title;
        /** 步骤描述/提示 */
        public String description;
        /** 对应工具参数名（如 "action"、"city"） */
        public String paramKey;
        /** INPUT 类型：用户输入的值；OPTION 类型：用户选择的值 */
        public String paramValue;

        /** OPTION 类型专用：可选项列表 */
        public List<Option> options;

        /** INPUT 类型专用：输入提示 */
        public String hint;
        /** INPUT 类型专用：是否必填 */
        public boolean required;
        /** INPUT 类型专用：是否多行输入 */
        public boolean multiline;

        /** PICKER 类型专用：MIME类型过滤（如 "application/pdf"、"image/*"），null表示不限制 */
        public String[] mimeTypes;
        /** PICKER 类型专用：是否允许多选（默认false） */
        public boolean allowMultiple;

        /** INPUT 类型可选：动态选项来源（渲染时异步拉取列表供用户点选，减少手动输入），null 表示纯手动输入 */
        public DynamicOptionsSpec dynamicOptions;

        /** 条件分支：依赖的前序参数名 */
        public String conditionKey;
        /** 条件分支：依赖的前序参数值（支持 "|" 分隔多值，如 "search|ask"） */
        public String conditionValue;

        public GuideStep() {}

        // ----- OPTION 类型工厂方法 -----

        /** 创建无条件的 OPTION 步骤 */
        public static GuideStep optionStep(String title, String description, String paramKey, List<Option> options) {
            return optionStep(title, description, paramKey, options, null, null);
        }

        /** 创建带条件分支的 OPTION 步骤 */
        public static GuideStep optionStep(String title, String description, String paramKey,
                                           List<Option> options, String conditionKey, String conditionValue) {
            GuideStep step = new GuideStep();
            step.type = StepType.OPTION;
            step.title = title;
            step.description = description;
            step.paramKey = paramKey;
            step.options = options;
            step.conditionKey = conditionKey;
            step.conditionValue = conditionValue;
            return step;
        }

        // ----- INPUT 类型工厂方法 -----

        /** 创建无条件的 INPUT 步骤 */
        public static GuideStep inputStep(String title, String description, String paramKey,
                                          String hint, boolean required, boolean multiline) {
            return inputStep(title, description, paramKey, hint, required, multiline, null, null);
        }

        /** 创建带条件分支的 INPUT 步骤 */
        public static GuideStep inputStep(String title, String description, String paramKey,
                                          String hint, boolean required, boolean multiline,
                                          String conditionKey, String conditionValue) {
            GuideStep step = new GuideStep();
            step.type = StepType.INPUT;
            step.title = title;
            step.description = description;
            step.paramKey = paramKey;
            step.hint = hint;
            step.required = required;
            step.multiline = multiline;
            step.conditionKey = conditionKey;
            step.conditionValue = conditionValue;
            return step;
        }

        /** 创建带条件分支 + 动态选项的 INPUT 步骤（列表可点选，也可手动输入兜底） */
        public static GuideStep inputStep(String title, String description, String paramKey,
                                          String hint, boolean required, boolean multiline,
                                          String conditionKey, String conditionValue,
                                          DynamicOptionsSpec dynamicOptions) {
            GuideStep step = inputStep(title, description, paramKey, hint, required, multiline,
                    conditionKey, conditionValue);
            step.dynamicOptions = dynamicOptions;
            return step;
        }

        // ----- PICKER 类型工厂方法 -----

        /** 创建无条件的 FILE_PICKER 步骤 */
        public static GuideStep filePickerStep(String title, String description, String paramKey,
                                               boolean required, String[] mimeTypes) {
            return filePickerStep(title, description, paramKey, required, mimeTypes, false, null, null);
        }

        /** 创建带条件分支的 FILE_PICKER 步骤 */
        public static GuideStep filePickerStep(String title, String description, String paramKey,
                                               boolean required, String[] mimeTypes, boolean allowMultiple,
                                               String conditionKey, String conditionValue) {
            GuideStep step = new GuideStep();
            step.type = StepType.FILE_PICKER;
            step.title = title;
            step.description = description;
            step.paramKey = paramKey;
            step.required = required;
            step.mimeTypes = mimeTypes;
            step.allowMultiple = allowMultiple;
            step.conditionKey = conditionKey;
            step.conditionValue = conditionValue;
            return step;
        }

        /** 创建无条件的 IMAGE_PICKER 步骤 */
        public static GuideStep imagePickerStep(String title, String description, String paramKey,
                                                boolean required) {
            return imagePickerStep(title, description, paramKey, required, false, null, null);
        }

        /** 创建带条件分支的 IMAGE_PICKER 步骤 */
        public static GuideStep imagePickerStep(String title, String description, String paramKey,
                                                boolean required, boolean allowMultiple,
                                                String conditionKey, String conditionValue) {
            GuideStep step = new GuideStep();
            step.type = StepType.IMAGE_PICKER;
            step.title = title;
            step.description = description;
            step.paramKey = paramKey;
            step.required = required;
            step.mimeTypes = new String[]{"image/*"};
            step.allowMultiple = allowMultiple;
            step.conditionKey = conditionKey;
            step.conditionValue = conditionValue;
            return step;
        }

        /** 创建无条件的 DIRECTORY_PICKER 步骤 */
        public static GuideStep directoryPickerStep(String title, String description, String paramKey,
                                                    boolean required) {
            return directoryPickerStep(title, description, paramKey, required, null, null);
        }

        /** 创建带条件分支的 DIRECTORY_PICKER 步骤 */
        public static GuideStep directoryPickerStep(String title, String description, String paramKey,
                                                    boolean required,
                                                    String conditionKey, String conditionValue) {
            GuideStep step = new GuideStep();
            step.type = StepType.DIRECTORY_PICKER;
            step.title = title;
            step.description = description;
            step.paramKey = paramKey;
            step.required = required;
            step.conditionKey = conditionKey;
            step.conditionValue = conditionValue;
            return step;
        }

        // ----- CONFIRM 类型工厂方法 -----

        /** 创建 CONFIRM 步骤 */
        public static GuideStep confirmStep(String title, String description) {
            GuideStep step = new GuideStep();
            step.type = StepType.CONFIRM;
            step.title = title;
            step.description = description;
            return step;
        }

        /**
         * 判断步骤类型是否为选择器类型（需要弹出文件管理器/相册）。
         */
        public boolean isPickerType() {
            return type == StepType.FILE_PICKER
                || type == StepType.IMAGE_PICKER
                || type == StepType.DIRECTORY_PICKER;
        }

        /**
         * 选项项。
         */
        public static class Option {
            /** 显示文本（如 "当前天气"） */
            public String label;
            /** 实际值（如 "current"） */
            public String value;

            public Option() {}

            public Option(String label, String value) {
                this.label = label;
                this.value = value;
            }
        }

        /**
         * 动态选项来源声明：INPUT 步骤渲染时异步调用指定工具的 action 拉取列表，
         * 让用户从列表点选而非手动输入。拉取失败时退化为纯手动输入。
         */
        public static class DynamicOptionsSpec {
            /** 提供列表的工具名（如 "database"） */
            public String toolName;
            /** 工具 action（如 "get_categories"） */
            public String action;
            /** 结果 Map 中列表所在的 key（如 "categories"/"tables"） */
            public String listKey;
            /** 列表为 Map 元素时取值的字段名（如 tables 的 "name"）；列表为字符串时留空 */
            public String itemField;
            /** 可选项：列表顶部附加的"不限/全部"选项，label 为显示文本，value 为空串表示不传该参数 */
            public String allOptionLabel;

            public DynamicOptionsSpec(String toolName, String action, String listKey, String itemField, String allOptionLabel) {
                this.toolName = toolName;
                this.action = action;
                this.listKey = listKey;
                this.itemField = itemField;
                this.allOptionLabel = allOptionLabel;
            }
        }
    }
}
