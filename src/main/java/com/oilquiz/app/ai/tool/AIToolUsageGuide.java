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

    // ========== 静态缓存 ==========
    /** 缓存的 Agent 工具使用方法文本 */
    private static String cachedAgentToolUsageMethod;
    /** 缓存脏标记 */
    private static boolean isCacheDirty = true;
    /** 上次缓存的工具数量（用于检测变化） */
    private static int lastCachedToolCount = -1;

    /**
     * 标记缓存为脏，下次 getAgentToolUsageMethod 调用时重建。
     * 在工具注册/卸载后调用。
     */
    public static void markCacheDirty() {
        isCacheDirty = true;
    }

    // ==================== 面向AI Agent的工具调用方法 ====================

    /**
     * 获取AI Agent工具使用方法（面向LLM的调用规范）。
     * 使用静态缓存避免重复构建字符串，工具数量变化时自动刷新。
     *
     * @param context 上下文
     * @return 工具调用规范文本
     */
    public static String getAgentToolUsageMethod(Context context) {
        AIToolManager manager = AIToolManager.getInstance(context);
        List<Map<String, Object>> tools = manager.getToolDescriptions();
        int currentToolCount = tools.size();

        // 检查缓存有效性：非脏且工具数量未变化
        if (!isCacheDirty && cachedAgentToolUsageMethod != null 
            && currentToolCount == lastCachedToolCount) {
            return cachedAgentToolUsageMethod;
        }

        // 重建缓存
        StringBuilder sb = new StringBuilder();

        sb.append("═══════════════════════════════════════════════════════\n");
        sb.append("              AI Agent 工具调用规范\n");
        sb.append("═══════════════════════════════════════════════════════\n\n");

        // 1. 调用协议
        sb.append("【一、调用协议】\n");
        sb.append("使用原生 function calling 调用工具，无需任何 JSON 封装或文本格式标记：\n");
        sb.append("  • 直接以原生 function calling 格式输出工具调用，系统会自动解析并执行\n");
        sb.append("  • 不要在回复中构造 {\"tool_calls\": [...]} 之类的 JSON 封装，也不要使用 TOOLS_CALL/TOOLS_END 等文本标记\n");
        sb.append("  • 可在一次回复中输出多个工具调用\n");
        sb.append("  • 参数为 JSON 对象，严格匹配下方工具定义的参数名与类型\n");
        sb.append("  • 必填参数缺失会导致工具执行失败\n\n");
        sb.append("特殊命令：\n");
        sb.append("  • 输出 [TOOL_INFO: 工具名] 可获取该工具的详细参数说明\n");
        sb.append("  • 输出 [TOOL_GUIDE] 可再次查看本指南\n");
        sb.append("  • 工具可组合使用，如查天气可先用 location 定位再用 ai_weather 查询\n");
        sb.append("  • 工具失败时系统会自动分析原因：参数错误会提示修正，工具不适用会推荐替代工具\n\n");

        // 2. 工具清单（动态）
        sb.append("【二、可用工具清单】\n");
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
        sb.append("  1. 天气优先 ai_weather（结构化完整；查当前位置实时天气用经纬度最准——直接用 location 定位或环境上下文的 lat/lon；查具体城市用 city，可填城市名或和风城市编码），也可用 network_search 搜索；由你按情况判断。\n");
        sb.append("  3. 数学计算可用 calculator 或 python_calculate，复杂数据分析用 python_analyze_data，任意 Python 代码用 python_execute，由你按场景选择。\n");
        sb.append("  4. 文件路径必须为绝对路径（如 /storage/emulated/0/...），否则工具会返回文件不存在。\n");
        sb.append("  5. 涉及权限的操作（定位/权限管理）会自动触发权限请求，无需预先调用 permission_manager。\n");
        sb.append("  6. 工具结果可能被自动摘要/截断，如需完整内容请细化查询条件。\n");
        sb.append("  7. 同一工具连续失败 2 次应更换策略或向用户澄清，不要无限重试。\n");
        sb.append("  8. 【重要】生成图片优先调用 image_gen 工具（会自动下载并内联显示在对话中），或 dashscope_media（通义万相）；也可直接输出 image_grid 组件标记展示图片。\n");
        sb.append("     尽量避免用 python_execute 拼 URL、用 system_resource open_url 打开浏览器等方式绕路（这些方式图片无法在对话内展示）。\n");
        sb.append("     图片生成后直接内联展示给用户，不要让用户离开对话去浏览器查看。\n");
        sb.append("  8.5 音视频处理（看信息/截帧封面/抽音频/转WAV/剪切/压缩转码/图片缩放裁剪转格式）一律用 media_toolkit（系统硬解硬编，不依赖 ffmpeg/外部二进制，处理过程不需要权限、不联网）；内置 shell 工具箱里没有 ffmpeg（也装不上），不要去 shell 里找。Python 里用 import android_media。\n");
        sb.append("      能力边界：能处理什么由设备的解码/编码器决定（avi/flv/rmvb 等冷门容器、时间轴水印/多路混流/字幕烧录等复杂滤镜链不支持）；trim 仅关键帧对齐；transcode 实际分辨率会被编码器对齐取整（以返回 output_width/height 为准）。做不到就如实说明，不要承诺。\n");
        sb.append("  9. 用户明确表达偏好/身份/常用信息（如：我叫小明、我住在北京、我喜欢简洁回答）时，用 memory 工具 save 保存（key 用英文短词如 user_name/preference_city）；\n");
        sb.append("     需要回忆用户历史信息时用 memory recall；不确定时先 list。记忆会跨对话保留。\n");
        sb.append("  9.5 用户提到\"之前/上次/历史里\"（之前创建的组件/工具/文件、说过的话）时，用 chat_history 工具读对话历史找回上下文（source=ai 默认，可 search 关键词）；历史只读。\n");
        sb.append("  10. 现有信息不足以回答时（实时数据/最新事件/超出已知范围的事实），主动用 network_search 或 smart_research 搜索补全再答，不硬答不编造。\n");
        sb.append("  11. 工具可配合/串联使用（如 location 定位→ai_weather 经纬度查天气；network_search 搜索→webpage_reader 读详情；file_reader 读文件→python_analyze_data 分析），按需组合。\n");
        sb.append("  12. 用户已给出的参数（城市/编码/时间/位置等）直接照用先调用，工具会解析并返回结果，以工具返回为准；参数有误时工具返回错误，再按工具结果调整。\n");
        sb.append("  13. 实时信息（时间/位置/天气/新闻/行情/开奖/政策等）一律用工具获取，训练数据不采纳；缺失或不确定时同样直接调对应工具拿真实结果，工具返回的数据是准确实时的，直接采纳。\n\n");

        // 4. 典型调用示例（示例已删除：让模型根据工具定义自行组织调用，不照抄示例）
        sb.append("【四、调用方式】\n");
        sb.append("  【选参原则】多数工具支持多种操作类型（action）与多种参数方式：先按用户当前需求选最匹配的 action，再填对应参数；\n");
        sb.append("  完整参数可调用 tool_registry(action=get) 查看定义后再调用。\n\n");

        // 5. 错误处理
        sb.append("【五、错误处理】\n");
        sb.append("  • 工具失败时系统会自动分析原因并给出建议，请根据建议修正参数或更换工具。\n");
        sb.append("  • 参数错误时系统会注入该工具的详细参数定义和缺失参数分析，请据此修正后重试。\n");
        sb.append("  • 工具不适用时系统会推荐替代工具，请判断是否适合后调用。\n");
        sb.append("  • 如不确定工具参数，可输出 [TOOL_INFO: 工具名] 获取详细说明。\n");
        sb.append("  • 同一工具连续失败 2 次应更换策略或向用户澄清，不要无限重试。\n\n");

        sb.append("═══════════════════════════════════════════════════════\n");

        // 更新缓存
        cachedAgentToolUsageMethod = sb.toString();
        lastCachedToolCount = currentToolCount;
        isCacheDirty = false;

        return cachedAgentToolUsageMethod;
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
        guide.append("│ python_calculate │ 数学表达式计算                        │\n");
        guide.append("│ python_execute   │ 执行Python代码                        │\n");
        guide.append("│ python_analyze_data│ 使用Python分析数据                    │\n");
        guide.append("│ python_web_reader │ Python抓网页/API(requests+bs4)        │\n");
        guide.append("│ python_file_ops   │ Python文件读/改/解析(含docx/pptx/pdf)  │\n");
        guide.append("│ python_chart      │ Python绘图(Pillow/matplotlib):柱/线/饼/散点→PNG │\n");
        guide.append("│ location         │ 获取当前位置/城市/经纬度                │\n");
        guide.append("│ file_reader      │ 读取/按行/搜索/解析Excel-CSV-JSON-XML/列目录 │\n");
        guide.append("│ file_analyzer    │ 文件内容分析                          │\n");
        guide.append("│ file_generator   │ 生成文本/JSON/配置/Markdown文件        │\n");
        guide.append("│ database         │ 题库查询/用户/分数等数据库操作          │\n");
        guide.append("│ excel_tool       │ Excel查询(sheets/query/cell)/修改(write/add) │\n");
        guide.append("│ system_resource  │ 打开应用/URL、发短信、拨打电话等系统操作 │\n");
        guide.append("│ app_operation    │ 应用内页面跳转（用户/题库/答题/计划等）  │\n");
        guide.append("│ permission_manager│ 权限检查/请求/状态管理                 │\n");
        guide.append("│ create_dynamic_tool│ 动态创建/管理/删除AI工具              │\n");
        guide.append("│ chat_history     │ 对话历史(跨会话读最近消息/关键词搜索)     │\n");
        guide.append("│ ai_create_tool   │ 用AI自动生成新工具                    │\n");
        guide.append("└──────────────────┴──────────────────────────────────────┘\n\n");

        guide.append("【使用要点】\n");
        guide.append("  1. 明确动词：识别、读取、搜索、计算、查询。\n");
        guide.append("  2. 文件用绝对路径：/storage/emulated/0/xxx.jpg。\n");
        guide.append("  3. 搜索提供关键词：搜索 量子计算 最新进展。\n\n");

        guide.append("【示例】\n");
        guide.append("  • 查询北京今天的天气\n");
        guide.append("  • 搜索 人工智能 最新新闻\n");
        guide.append("  • 计算 (10+5)*3\n");
        guide.append("  • 读取文件 /storage/emulated/0/note.txt\n");
        guide.append("  • 识别图片 /storage/emulated/0/test.jpg 中的文字\n");
        guide.append("  • 研究一下 可再生能源 发展现状\n\n");

        guide.append("【调试】\n");
        guide.append("  • 调试意图：你的请求 —— 查看意图识别结果\n");
        guide.append("  • 分析我的请求   —— 生成调试报告\n\n");

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
        keywords.put("计算器", List.of("计算", "加", "减", "乘", "除"));
        keywords.put("语音", List.of("语音", "朗读", "说话", "播报", "听写", "识别录音"));
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
