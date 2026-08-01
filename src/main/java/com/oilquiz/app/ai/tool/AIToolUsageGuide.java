package com.oilquiz.app.ai.tool;

import android.content.Context;

import com.oilquiz.app.ai.intent.IntelligentIntentRecognizer;
import com.oilquiz.app.ai.intent.IntelligentIntentRecognizer.PrimaryIntent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI工具使用指南与AI Agent工具调用方法
 *
 * 提供两套内容：
 * 1. {@link #getUsageGuide()} —— 面向用户的工具使用教程（静态概览）
 * 2. {@link #getAgentToolUsageMethod(Context)} —— 面向AI Agent的工具调用规范，
 *    动态从 {@link AIToolManager} 读取真实工具定义，可作为system prompt片段注入。
 *
 * 工具清单与 {@link AIToolManager#getToolDefinition(String)} 保持单一真相源，
 * 避免描述与实际实现脱节。
 */
public class AIToolUsageGuide {

    private static final String TAG = "AIToolUsageGuide";

    // ==================== 面向AI Agent的工具调用方法 ====================

    /**
     * 获取AI Agent工具使用方法（面向LLM的调用规范）。
     * 动态从 {@link AIToolManager} 读取已注册工具的真实定义，保证工具名/参数与实现一致。
     * 可作为system prompt片段注入Agent上下文。
     *
     * @param context 上下文
     * @return 工具调用规范文本
     */
    public static String getAgentToolUsageMethod(Context context) {
        StringBuilder sb = new StringBuilder();

        sb.append("═══════════════════════════════════════════════════════\n");
        sb.append("              AI Agent 工具调用规范\n");
        sb.append("═══════════════════════════════════════════════════════\n\n");

        // 1. 调用协议
        sb.append("【一、调用协议】\n");
        sb.append("采用 OpenAI function calling 标准格式。当需要使用工具时，输出 tool_call：\n");
        sb.append("  {\n");
        sb.append("    \"name\": \"<工具名>\",\n");
        sb.append("    \"arguments\": \"<JSON参数对象>\"\n");
        sb.append("  }\n\n");
        sb.append("说明：\n");
        sb.append("  • arguments 必须是合法 JSON 字符串\n");
        sb.append("  • 参数名严格匹配下方工具定义，区分大小写\n");
        sb.append("  • 必填参数缺失会导致工具执行失败\n");
        sb.append("  • 工具调用支持别名（见各工具 aliases），但建议使用主名称\n\n");

        // 2. 工具清单（动态）
        sb.append("【二、可用工具清单】\n");
        AIToolManager manager = AIToolManager.getInstance(context);
        List<Map<String, Object>> tools = manager.getToolDescriptions();
        int idx = 0;
        for (Map<String, Object> tool : tools) {
            idx++;
            String name = String.valueOf(tool.get("name"));
            String desc = String.valueOf(tool.get("description"));
            sb.append("  ").append(idx).append(". ").append(name).append("\n");
            sb.append("     功能: ").append(desc).append("\n");
            Object paramsObj = tool.get("parameters");
            if (paramsObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, String> params = (Map<String, String>) paramsObj;
                if (!params.isEmpty()) {
                    sb.append("     参数:\n");
                    for (Map.Entry<String, String> e : params.entrySet()) {
                        sb.append("       - ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
                    }
                } else {
                    sb.append("     参数: 无\n");
                }
            }
            sb.append("\n");
        }

        // 3. 调用规则
        sb.append("【三、调用规则】\n");
        sb.append("  1. 优先使用专用工具，而非聚合工具 app_toolkit。例如天气用 ai_weather，搜索用 network_search。\n");
        sb.append("  2. app_toolkit 仅在需要 OCR/图像处理/文件解析/网页解析等聚合能力时使用，通过 action 指定子操作。\n");
        sb.append("  3. 数学计算使用 python_calculate，复杂数据分析使用 python_analyze_data，Python代码执行使用 python_execute。\n");
        sb.append("  4. 文件路径必须为绝对路径（如 /storage/emulated/0/...），否则工具会返回“文件不存在”。\n");
        sb.append("  5. 涉及权限的操作（定位/权限管理）会自动触发权限请求，无需预先调用 permission_manager。\n");
        sb.append("  6. 工具结果可能被自动摘要/截断，如需完整内容请细化查询条件。\n");
        sb.append("  7. 同一工具连续失败 2 次应更换策略或向用户澄清，不要无限重试。\n\n");

        // 4. 典型调用示例
        sb.append("【四、典型调用示例】\n");
        sb.append("  示例1 查询天气：\n");
        sb.append("    name: ai_weather\n");
        sb.append("    arguments: {\"action\":\"current\",\"city\":\"北京\"}\n\n");
        sb.append("  示例2 网络搜索并阅读：\n");
        sb.append("    name: network_search\n");
        sb.append("    arguments: {\"action\":\"search_and_read\",\"query\":\"量子计算最新进展\",\"limit\":5}\n\n");
        sb.append("  示例3 数学计算：\n");
        sb.append("    name: python_calculate\n");
        sb.append("    arguments: {\"expression\":\"3.14*5*5\"}\n\n");
        sb.append("  示例4 翻译：\n");
        sb.append("    name: translation\n");
        sb.append("    arguments: {\"text\":\"Hello world\",\"target_lang\":\"zh\"}\n\n");
        sb.append("  示例5 读取文件：\n");
        sb.append("    name: file_reader\n");
        sb.append("    arguments: {\"action\":\"read\",\"file_path\":\"/storage/emulated/0/note.txt\"}\n\n");
        sb.append("  示例6 OCR识别（聚合工具）：\n");
        sb.append("    name: app_toolkit\n");
        sb.append("    arguments: {\"action\":\"ocr_recognize\",\"image_path\":\"/storage/emulated/0/test.jpg\"}\n\n");
        sb.append("  示例7 智能研究（搜索→阅读→摘要全流程）：\n");
        sb.append("    name: smart_research\n");
        sb.append("    arguments: {\"topic\":\"可再生能源发展现状\",\"depth\":2,\"maxResults\":5}\n\n");

        // 5. 错误处理
        sb.append("【五、错误处理】\n");
        sb.append("  • 工具返回 failure/错误信息时，检查参数名、路径、权限后最多重试1次。\n");
        sb.append("  • “Tool not found”表示工具名错误，核对可用工具清单。\n");
        sb.append("  • “参数验证失败”按提示补全必填参数。\n");
        sb.append("  • “工具执行超时”多为网络/权限问题，提示用户检查后重试。\n\n");

        sb.append("═══════════════════════════════════════════════════════\n");
        return sb.toString();
    }

    // ==================== 面向用户的工具使用教程 ====================

    /**
     * 获取工具使用教程（面向用户，静态概览）。
     * 工具名与 {@link AIToolManager} 注册名保持一致。
     */
    public static String getUsageGuide() {
        StringBuilder guide = new StringBuilder();

        guide.append("═══════════════════════════════════════════════════════\n");
        guide.append("           AI助手工具使用指南 v2.0\n");
        guide.append("═══════════════════════════════════════════════════════\n\n");

        guide.append("【前言】\n");
        guide.append("AI助手通过工具完成需要外部能力的任务。直接描述需求即可，助手会自动选择工具。\n\n");

        guide.append("【工具一览】\n");
        guide.append("┌──────────────────┬──────────────────────────────────────┐\n");
        guide.append("│ 工具名            │ 用途                                 │\n");
        guide.append("├──────────────────┼──────────────────────────────────────┤\n");
        guide.append("│ ai_weather       │ 查询当前/预报/逐小时/空气质量/预警/生活指数 │\n");
        guide.append("│ network_search   │ 网络搜索、获取网页、提取信息、智能摘要     │\n");
        guide.append("│ smart_research   │ 智能研究：搜索→阅读→摘要全自动流程        │\n");
        guide.append("│ webpage_reader   │ 网页阅读、信息提取、摘要、多页抓取         │\n");
        guide.append("│ translation      │ 多语言翻译（中英日韩法德西俄等）          │\n");
        guide.append("│ python_calculate │ 数学表达式计算                        │\n");
        guide.append("│ python_execute   │ 执行Python代码                        │\n");
        guide.append("│ python_analyze_data│ 使用Python分析数据                    │\n");
        guide.append("│ location         │ 获取当前位置/城市/经纬度                │\n");
        guide.append("│ file             │ 文件信息/读取/目录列表                 │\n");
        guide.append("│ file_reader      │ 文本文件读取/按行读/搜索/实体提取/预览   │\n");
        guide.append("│ file_analyzer    │ 文件内容分析                          │\n");
        guide.append("│ file_generator   │ 生成文本/JSON/配置/Markdown文件        │\n");
        guide.append("│ database         │ 题库查询/用户/分数等数据库操作          │\n");
        guide.append("│ system_resource  │ 打开应用/URL、发短信、拨打电话等系统操作 │\n");
        guide.append("│ app_operation    │ 应用内页面跳转（用户/题库/答题/计划等）  │\n");
        guide.append("│ permission_manager│ 权限检查/请求/状态管理                 │\n");
        guide.append("│ app_toolkit      │ 聚合工具：OCR/图像/文件解析/网页解析/天气 │\n");
        guide.append("│ create_dynamic_tool│ 动态创建/管理/删除AI工具              │\n");
        guide.append("│ ai_create_tool   │ 用AI自动生成新工具                    │\n");
        guide.append("└──────────────────┴──────────────────────────────────────┘\n\n");

        guide.append("【使用要点】\n");
        guide.append("  1. 明确动词：识别、读取、搜索、翻译、计算、查询。\n");
        guide.append("  2. 文件用绝对路径：/storage/emulated/0/xxx.jpg。\n");
        guide.append("  3. 翻译指定目标语言：翻译成英文/日文。\n");
        guide.append("  4. 搜索提供关键词：搜索 量子计算 最新进展。\n\n");

        guide.append("【示例】\n");
        guide.append("  • 查询北京今天的天气\n");
        guide.append("  • 搜索 人工智能 最新新闻\n");
        guide.append("  • 把这段话翻译成英文：...\n");
        guide.append("  • 计算 (10+5)*3\n");
        guide.append("  • 读取文件 /storage/emulated/0/note.txt\n");
        guide.append("  • 识别图片 /storage/emulated/0/test.jpg 中的文字\n");
        guide.append("  • 研究一下 可再生能源 发展现状\n\n");

        guide.append("【调试】\n");
        guide.append("  • “调试意图：你的请求” —— 查看意图识别结果\n");
        guide.append("  • “分析我的请求”   —— 生成调试报告\n\n");

        guide.append("═══════════════════════════════════════════════════════\n");
        return guide.toString();
    }

    /**
     * 获取工具列表（不初始化工具实例，仅从工厂描述获取）。
     */
    public static List<Map<String, Object>> getToolList(Context context) {
        return AIToolManager.getInstance(context).getToolDescriptions();
    }

    // ==================== 意图调试 ====================

    /**
     * 预测意图（用于调试）
     */
    public static String predictIntent(String message) {
        IntelligentIntentRecognizer.IntentResult result =
            IntelligentIntentRecognizer.recognize(message, null);

        StringBuilder sb = new StringBuilder();
        sb.append("┌─────────────────────────────────────────────────────┐\n");
        sb.append("│            意图预测结果                            │\n");
        sb.append("├─────────────────────────────────────────────────────┤\n");
        sb.append("│ 输入：").append(message).append("\n");
        sb.append("├─────────────────────────────────────────────────────┤\n");
        sb.append("│ 意图：").append(result.primaryIntent.getDisplayName()).append("\n");
        sb.append("│ 置信度：").append(String.format("%.2f", result.confidence * 100)).append("%\n");
        sb.append("│ 推荐工具：").append(
            IntelligentIntentRecognizer.getRecommendedTool(result.primaryIntent) != null ?
            IntelligentIntentRecognizer.getRecommendedTool(result.primaryIntent) : "无"
        ).append("\n");
        sb.append("└─────────────────────────────────────────────────────┘\n");

        if (result.confidence < 0.5) {
            sb.append("\n⚠️  警告：置信度较低（<50%）\n");
            sb.append("建议：\n");
            sb.append("  • 添加明确动词：识别、读取、解析、搜索、翻译、计算\n");
            sb.append("  • 提供完整的文件路径\n");
            sb.append("  • 指定操作对象\n");
        } else if (result.confidence < 0.7) {
            sb.append("\nℹ️  提示：置信度一般（50%-70%）\n");
            sb.append("建议：可以考虑增加关键词明确意图\n");
        } else {
            sb.append("\n✅  提示：置信度较高（>70%）\n");
            sb.append("工具调用应该准确\n");
        }

        return sb.toString();
    }

    /**
     * 获取意图关键词列表
     */
    public static Map<String, List<String>> getIntentKeywords() {
        Map<String, List<String>> keywords = new HashMap<>();

        keywords.put("OCR识别", List.of("识别", "OCR", "文字识别", "提取文字", "读取图片"));
        keywords.put("图片处理", List.of("图片", "照片", "截图", "裁剪", "缩放", "旋转", "生成"));
        keywords.put("文件处理", List.of("文件", "读取", "解析", "CSV", "JSON", "TXT"));
        keywords.put("网页解析", List.of("网页", "网站", "HTML", "链接", "网址"));
        keywords.put("搜索", List.of("搜索", "查找", "查询"));
        keywords.put("翻译", List.of("翻译", "英文", "中文", "日语", "韩语"));
        keywords.put("计算器", List.of("计算", "加", "减", "乘", "除"));
        keywords.put("天气", List.of("天气", "气温", "温度", "预报"));
        keywords.put("聊天", List.of("你好", "嗨", "hello", "hi"));

        return keywords;
    }

    /**
     * 生成调试报告
     */
    public static String generateDebugReport(String userInput) {
        StringBuilder report = new StringBuilder();

        report.append("═══════════════════════════════════════════════════════\n");
        report.append("              意图识别调试报告\n");
        report.append("═══════════════════════════════════════════════════════\n\n");

        report.append("【用户输入】\n");
        report.append(userInput).append("\n\n");

        IntelligentIntentRecognizer.IntentResult result =
            IntelligentIntentRecognizer.recognize(userInput, null);

        report.append("【意图识别结果】\n");
        report.append("┌─────────────────────────────────────────────────────┐\n");
        report.append("│ 识别意图：").append(result.primaryIntent.name()).append("\n");
        report.append("│ 意图描述：").append(result.primaryIntent.getDisplayName()).append("\n");
        report.append("│ 置信度：").append(String.format("%.2f", result.confidence * 100)).append("%\n");

        String toolName = IntelligentIntentRecognizer.getRecommendedTool(result.primaryIntent);
        report.append("│ 推荐工具：").append(toolName != null ? toolName : "无（直接回答）").append("\n");
        report.append("└─────────────────────────────────────────────────────┘\n\n");

        Map<String, Object> params = IntelligentIntentRecognizer.buildToolParameters(result.primaryIntent, userInput);
        report.append("【预测参数】\n");
        report.append("┌─────────────────────────────────────────────────────┐\n");
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            report.append("│ ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        report.append("└─────────────────────────────────────────────────────┘\n");

        report.append("\n【优化建议】\n");
        if (result.confidence < 0.5) {
            report.append("⚠️  置信度较低（<50%），建议：\n");
            report.append("  1. 添加明确的动词：识别、读取、解析、搜索、翻译\n");
            report.append("  2. 提供完整的文件路径\n");
            report.append("  3. 指定操作对象\n");
            report.append("  4. 使用更完整的句子表达\n");
        } else if (result.confidence < 0.7) {
            report.append("ℹ️  置信度一般（50%-70%），可以考虑：\n");
            report.append("  • 增加关键词明确意图\n");
            report.append("  • 提供更多上下文信息\n");
        } else {
            report.append("✅  置信度较高（>70%），工具调用应该准确\n");
        }

        report.append("\n【参考示例】\n");
        report.append("┌─────────────────────────────────────────────────────┐\n");
        report.append(getExampleForIntent(result.primaryIntent));
        report.append("└─────────────────────────────────────────────────────┘\n");

        report.append("\n═══════════════════════════════════════════════════════\n");

        return report.toString();
    }

    /**
     * 获取特定意图的示例
     */
    private static String getExampleForIntent(PrimaryIntent intent) {
        switch (intent) {
            case OCR:
                return "│ • 识别图片 /storage/emulated/0/test.jpg\n" +
                       "│ • 提取图片中的中文文字\n" +
                       "│ • OCR识别这张照片\n";
            case IMAGE:
                return "│ • 裁剪图片 /storage/emulated/0/photo.png\n" +
                       "│ • 缩放图片到800x600\n" +
                       "│ • 生成红色背景图片\n";
            case FILE:
                return "│ • 读取文件 /storage/emulated/0/note.txt\n" +
                       "│ • 解析JSON文件\n" +
                       "│ • 查看文件类型\n";
            case WEB:
                return "│ • 获取网页标题\n" +
                       "│ • 提取网页链接\n" +
                       "│ • 解析HTML内容\n";
            case SEARCH:
                return "│ • 搜索最新新闻\n" +
                       "│ • 查找天气信息\n" +
                       "│ • 帮我搜索人工智能\n";
            case TRANSLATE:
                return "│ • 翻译这段英文\n" +
                       "│ • 中文翻译成英文\n" +
                       "│ • 日语翻译\n";
            case CALCULATOR:
                return "│ • 计算 100 + 200\n" +
                       "│ • 3.14 * 5\n" +
                       "│ • 100 / 4\n";
            case WEATHER:
                return "│ • 今天天气怎么样\n" +
                       "│ • 查询北京天气\n" +
                       "│ • 天气预报\n";
            default:
                return "│ • 直接提问或聊天\n" +
                       "│ • 请明确说明需要的操作\n";
        }
    }

    /**
     * 验证工具调用参数
     */
    public static String validateParameters(PrimaryIntent intent, Map<String, Object> params) {
        StringBuilder validation = new StringBuilder();

        validation.append("┌─────────────────────────────────────────────────────┐\n");
        validation.append("│            参数验证结果                            │\n");
        validation.append("├─────────────────────────────────────────────────────┤\n");

        switch (intent) {
            case OCR:
                if (!params.containsKey("image_path") || params.get("image_path") == null) {
                    validation.append("│ ❌ 缺少图片路径参数\n");
                    validation.append("│    请添加：image_path: \"/path/to/image.jpg\"\n");
                } else {
                    validation.append("│ ✅ 图片路径：").append(params.get("image_path")).append("\n");
                }
                break;
            case IMAGE:
                String action = (String) params.get("action");
                if (action == null) {
                    validation.append("│ ❌ 缺少操作类型\n");
                } else if (!action.startsWith("image_generate") &&
                          (!params.containsKey("image_path") || params.get("image_path") == null)) {
                    validation.append("│ ❌ 缺少图片路径参数\n");
                    validation.append("│    请添加：image_path: \"/path/to/image.jpg\"\n");
                } else {
                    validation.append("│ ✅ 操作：").append(action).append("\n");
                }
                break;
            case FILE:
                if (!params.containsKey("file_path") || params.get("file_path") == null) {
                    validation.append("│ ❌ 缺少文件路径参数\n");
                    validation.append("│    请添加：file_path: \"/path/to/file.txt\"\n");
                } else {
                    validation.append("│ ✅ 文件路径：").append(params.get("file_path")).append("\n");
                }
                break;
            case WEB:
                if (!params.containsKey("source") || params.get("source") == null) {
                    validation.append("│ ❌ 缺少网页源参数\n");
                    validation.append("│    请添加URL或HTML内容\n");
                } else {
                    validation.append("│ ✅ 网页源：").append(params.get("source")).append("\n");
                }
                break;
            case CALCULATOR:
                if (!params.containsKey("expression") || params.get("expression") == null) {
                    validation.append("│ ❌ 缺少表达式参数\n");
                } else {
                    validation.append("│ ✅ 表达式：").append(params.get("expression")).append("\n");
                }
                break;
            default:
                validation.append("│ ✅ 参数验证通过\n");
        }

        validation.append("└─────────────────────────────────────────────────────┘\n");

        return validation.toString();
    }
}
