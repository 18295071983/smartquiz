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
            case "translation":
                return buildTranslation();
            case "database":
                return buildDatabase();
            case "file":
                return buildFile();
            case "location":
                return buildLocation();
            case "app_operation":
                return buildAppOperation();
            case "app_toolkit":
                return buildAppToolkit();
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
        // 步骤2：输入城市
        steps.add(GuideStep.inputStep(
                "哪个城市?",
                "留空自动定位",
                "city",
                "留空自动定位",
                false,
                false
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

    /** translation 翻译 */
    private static ToolGuideFlow buildTranslation() {
        List<GuideStep> steps = new ArrayList<>();
        // 步骤1：输入待翻译文本
        steps.add(GuideStep.inputStep(
                "翻译什么内容?",
                "输入要翻译的文本",
                "text",
                "输入待翻译文本",
                true,
                true
        ));
        // 步骤2：选择目标语言
        steps.add(GuideStep.optionStep(
                "翻译成什么?",
                "选目标语言",
                "target_lang",
                Arrays.asList(
                        new GuideStep.Option("中文", "zh"),
                        new GuideStep.Option("英文", "en"),
                        new GuideStep.Option("日文", "ja"),
                        new GuideStep.Option("韩文", "ko")
                )
        ));
        // 步骤3：确认执行
        steps.add(GuideStep.confirmStep("确认执行", "信息无误就点执行"));
        return new ToolGuideFlow("translation", "翻译",
                "文本翻译，支持中英日韩等多种语言", steps);
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
                        new GuideStep.Option("按关键词搜索", "search_questions"),
                        new GuideStep.Option("获取所有分类", "get_all_categories"),
                        new GuideStep.Option("获取所有类型", "get_all_question_types"),
                        new GuideStep.Option("获取题目数量", "get_question_count"),
                        new GuideStep.Option("获取统计信息", "get_question_statistics"),
                        new GuideStep.Option("获取数据库版本", "get_database_version")
                )
        ));
        // 步骤2：输入搜索关键词（仅 search_questions）
        steps.add(GuideStep.inputStep(
                "搜什么关键词?",
                "输入要搜的题目关键词",
                "keyword",
                "输入搜索关键词",
                true,
                false,
                "action", "search_questions"
        ));
        // 步骤3：确认执行
        steps.add(GuideStep.confirmStep("确认执行", "信息无误就点执行"));
        return new ToolGuideFlow("database", "题库数据库",
                "题目查询、分类统计、数据库信息等", steps);
    }

    /** file 文件工具 */
    private static ToolGuideFlow buildFile() {
        List<GuideStep> steps = new ArrayList<>();
        // 步骤1：选择操作类型
        steps.add(GuideStep.optionStep(
                "想做什么?",
                "选要做的文件操作",
                "action",
                Arrays.asList(
                        new GuideStep.Option("获取文件信息", "get_file_info"),
                        new GuideStep.Option("读取文件内容", "read_file"),
                        new GuideStep.Option("列出目录文件", "list_files")
                )
        ));
        // 步骤2：选择文件（仅 get_file_info | read_file）—— 使用文件选择器
        steps.add(GuideStep.filePickerStep(
                "选择文件",
                "点击按钮打开文件管理器选择文件",
                "file_path",
                true,
                null,
                false,
                "action", "get_file_info|read_file"
        ));
        // 步骤2b：选择目录（仅 list_files）—— 使用目录选择器，也允许手动输入
        steps.add(GuideStep.directoryPickerStep(
                "选择目录",
                "点击按钮打开文件管理器选择目录，留空默认应用目录",
                "directory_path",
                false,
                "action", "list_files"
        ));
        // 步骤3：确认执行
        steps.add(GuideStep.confirmStep("确认执行", "信息无误就点执行"));
        return new ToolGuideFlow("file", "文件操作",
                "文件信息、读取、目录列举等", steps);
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

    /** app_toolkit 聚合工具包 */
    private static ToolGuideFlow buildAppToolkit() {
        List<GuideStep> steps = new ArrayList<>();
        // 步骤1：选择分类
        steps.add(GuideStep.optionStep(
                "用哪类工具?",
                "选要用的工具分类",
                "category",
                Arrays.asList(
                        new GuideStep.Option("天气", "weather"),
                        new GuideStep.Option("计算", "calculate"),
                        new GuideStep.Option("OCR识别", "ocr"),
                        new GuideStep.Option("图像处理", "image"),
                        new GuideStep.Option("文件解析", "file_parse"),
                        new GuideStep.Option("网页解析", "web_parse"),
                        new GuideStep.Option("获取信息", "get_info")
                )
        ));
        // 步骤2a：天气具体操作（仅 weather）
        steps.add(GuideStep.optionStep(
                "选择天气操作",
                "选具体的天气查询",
                "action",
                Arrays.asList(
                        new GuideStep.Option("现在天气", "weather_current"),
                        new GuideStep.Option("未来天气", "weather_forecast"),
                        new GuideStep.Option("24小时", "weather_hourly"),
                        new GuideStep.Option("空气", "weather_air")
                ),
                "category", "weather"
        ));
        // 步骤2b：计算表达式（仅 calculate）
        steps.add(GuideStep.inputStep(
                "算什么?",
                "输入要算的表达式",
                "expression",
                "输入数学表达式，如 2+3*4",
                true,
                false,
                "category", "calculate"
        ));
        // 步骤2c：OCR具体操作（仅 ocr）
        steps.add(GuideStep.optionStep(
                "选择OCR操作",
                "选具体的OCR识别操作",
                "action",
                Arrays.asList(
                        new GuideStep.Option("OCR识别", "ocr_recognize"),
                        new GuideStep.Option("设置语言", "ocr_set_language"),
                        new GuideStep.Option("获取语言", "ocr_get_language")
                ),
                "category", "ocr"
        ));
        // 步骤2d：图像具体操作（仅 image）
        steps.add(GuideStep.optionStep(
                "选择图像操作",
                "选具体的图像处理操作",
                "action",
                Arrays.asList(
                        new GuideStep.Option("保存图片", "image_save"),
                        new GuideStep.Option("缩放", "image_scale"),
                        new GuideStep.Option("裁剪", "image_crop"),
                        new GuideStep.Option("旋转", "image_rotate"),
                        new GuideStep.Option("生成颜色", "image_generate_color"),
                        new GuideStep.Option("生成文字", "image_generate_text")
                ),
                "category", "image"
        ));
        // 步骤2e：文件解析具体操作（仅 file_parse）
        steps.add(GuideStep.optionStep(
                "选择文件解析操作",
                "选具体的文件解析操作",
                "action",
                Arrays.asList(
                        new GuideStep.Option("解析文本", "file_parse_text"),
                        new GuideStep.Option("解析CSV", "file_parse_csv"),
                        new GuideStep.Option("解析JSON", "file_parse_json"),
                        new GuideStep.Option("读取行", "file_read_lines"),
                        new GuideStep.Option("获取类型", "file_get_type")
                ),
                "category", "file_parse"
        ));
        // 步骤2f：网页解析具体操作（仅 web_parse）
        steps.add(GuideStep.optionStep(
                "选择网页解析操作",
                "选具体的网页解析操作",
                "action",
                Arrays.asList(
                        new GuideStep.Option("解析HTML", "web_parse_html"),
                        new GuideStep.Option("获取标题", "web_get_title"),
                        new GuideStep.Option("获取链接", "web_get_links"),
                        new GuideStep.Option("获取图片", "web_get_images"),
                        new GuideStep.Option("获取文本", "web_get_text")
                ),
                "category", "web_parse"
        ));
        // 步骤3a：输入城市（仅天气相关 action）
        steps.add(GuideStep.inputStep(
                "哪个城市?",
                "留空自动定位",
                "city",
                "城市名",
                false,
                false,
                "action", "weather_current|weather_forecast|weather_hourly|weather_air"
        ));
        // 步骤3b：选择图片（仅 OCR/图像相关 action）—— 使用图片选择器
        steps.add(GuideStep.imagePickerStep(
                "选择图片",
                "点击按钮打开相册或文件管理器选择图片",
                "image_path",
                true,
                false,
                "action", "ocr_recognize|image_save|image_scale|image_crop|image_rotate|image_label_recognize|object_detect"
        ));
        // 步骤3c：选择文件（仅文件解析相关 action）—— 使用文件选择器
        steps.add(GuideStep.filePickerStep(
                "选择文件",
                "点击按钮打开文件管理器选择文件",
                "file_path",
                true,
                null,
                false,
                "action", "file_parse_text|file_parse_csv|file_parse_json|file_read_lines|file_get_type"
        ));
        // 步骤3d：输入网页URL（仅网页解析相关 action）
        steps.add(GuideStep.inputStep(
                "网页地址是?",
                "输入网页地址",
                "url",
                "网页URL",
                true,
                false,
                "action", "web_parse_html|web_get_title|web_get_links|web_get_images|web_get_text"
        ));
        // 步骤4：确认执行
        steps.add(GuideStep.confirmStep("确认执行", "信息无误就点执行"));
        return new ToolGuideFlow("app_toolkit", "聚合工具包",
                "天气、计算、OCR、图像、文件解析、网页解析等聚合工具", steps);
    }

    /** file_reader 文件阅读工具 */
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
                "读到第几行，留空读到末尾",
                "endLine",
                "例如：100",
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
        // 文件路径（除 delete/copy 外都需要）
        steps.add(GuideStep.inputStep(
                "文件名/路径?",
                "输入要生成的文件名或完整路径",
                "file_name",
                "例如：output.txt 或 /sdcard/Download/test.json",
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
                "目标路径?",
                "输入复制后的目标文件路径",
                "file_name",
                "目标路径，例如：/sdcard/Download/copy.txt",
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
        // 研究主题
        steps.add(GuideStep.inputStep(
                "研究什么主题?",
                "输入研究主题或关键词",
                "topic",
                "例如：人工智能最新进展",
                true, false,
                "action", "research|quick_search|summarize_topic"
        ));
        // query（等价于topic，也支持）
        steps.add(GuideStep.inputStep(
                "搜索关键词?",
                "输入搜索词",
                "query",
                "输入关键词",
                true, false,
                "action", "research|quick_search|summarize_topic"
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
                        new GuideStep.Option("获取应用信息", "get_app_info")
                )
        ));
        // 打开应用：应用名
        steps.add(GuideStep.inputStep(
                "打开哪个应用?",
                "输入应用名称，如：微信、QQ、支付宝",
                "app_name",
                "应用名称",
                true, false,
                "action", "open_app|get_app_info"
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
        steps.add(GuideStep.confirmStep("确认执行", "信息无误就点执行"));
        return new ToolGuideFlow("system_resource", "系统资源",
                "打开应用/网址、发送短信、拨打电话等系统级操作", steps);
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
    }
}
