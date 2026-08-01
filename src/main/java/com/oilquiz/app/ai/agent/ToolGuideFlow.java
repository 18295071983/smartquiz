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
        // 步骤2：输入文件路径（仅 get_file_info | read_file）
        steps.add(GuideStep.inputStep(
                "文件在哪?",
                "输入文件路径",
                "file_path",
                "输入文件路径",
                true,
                false,
                "action", "get_file_info|read_file"
        ));
        // 步骤2b：输入目录路径（仅 list_files）
        steps.add(GuideStep.inputStep(
                "哪个目录?",
                "留空默认应用目录",
                "directory_path",
                "输入目录路径，留空默认应用目录",
                false,
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
        // 步骤1：选择操作类型
        steps.add(GuideStep.optionStep(
                "想做什么?",
                "选要做的应用操作",
                "action",
                Arrays.asList(
                        new GuideStep.Option("页面导航", "navigate"),
                        new GuideStep.Option("列出所有页面", "list_pages"),
                        new GuideStep.Option("回到主页", "go_home"),
                        new GuideStep.Option("返回上一页", "go_back")
                )
        ));
        // 步骤2：输入页面名称（仅 navigate）
        steps.add(GuideStep.inputStep(
                "去哪个页面?",
                "输入要去的页面名称",
                "page",
                "页面名称：user/question/quiz/study_plan/wrong_question/note/ocr/ai等",
                true,
                false,
                "action", "navigate"
        ));
        // 步骤3：确认执行
        steps.add(GuideStep.confirmStep("确认执行", "信息无误就点执行"));
        return new ToolGuideFlow("app_operation", "应用操作",
                "页面导航、列出页面、回主页、返回等", steps);
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
        // 步骤3b：输入图片路径（仅 OCR/图像相关 action）
        steps.add(GuideStep.inputStep(
                "图片在哪?",
                "输入图片路径",
                "image_path",
                "图片路径",
                true,
                false,
                "action", "ocr_recognize|image_save|image_scale|image_crop|image_rotate|image_label_recognize|object_detect"
        ));
        // 步骤3c：输入文件路径（仅文件解析相关 action）
        steps.add(GuideStep.inputStep(
                "文件在哪?",
                "输入文件路径",
                "file_path",
                "文件路径",
                true,
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

    // ==================== 引导步骤数据模型 ====================

    /**
     * 引导步骤。描述用户完成工具执行所需的单步操作。
     * 支持三种类型：选项选择（OPTION）、文本输入（INPUT）、确认执行（CONFIRM）。
     */
    public static class GuideStep {
        /** 步骤类型 */
        public enum StepType { OPTION, INPUT, CONFIRM }

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
