package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.intent.IntelligentIntentRecognizer;
import com.oilquiz.app.ai.intent.IntelligentIntentRecognizer.PrimaryIntent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI工具使用教程与最佳实践指南
 * 帮助用户正确使用工具，提高意图识别准确率
 */
public class AIToolUsageGuide {
    
    private static final String TAG = "AIToolUsageGuide";
    
    /**
     * 获取工具使用教程（完整版）
     */
    public static String getUsageGuide() {
        StringBuilder guide = new StringBuilder();
        
        guide.append("═══════════════════════════════════════════════════════\n");
        guide.append("           AI助手工具使用指南 v1.0\n");
        guide.append("═══════════════════════════════════════════════════════\n\n");
        
        // 前言
        guide.append("【前言】\n");
        guide.append("欢迎使用AI助手！我可以帮助你完成各种任务。\n");
        guide.append("本指南将帮助你正确使用工具，提高意图识别准确率。\n\n");
        
        // 快速入门
        guide.append("【快速入门】\n");
        guide.append("┌─────────────────────────────────────────────────────┐\n");
        guide.append("│ 1. 明确说明操作类型：识别、读取、解析、搜索、翻译   │\n");
        guide.append("│ 2. 提供必要参数：文件路径、语言、尺寸等             │\n");
        guide.append("│ 3. 使用完整路径：/storage/emulated/0/test.jpg       │\n");
        guide.append("│ 4. 指定操作对象：识别图片、读取文件、搜索信息       │\n");
        guide.append("└─────────────────────────────────────────────────────┘\n\n");
        
        // OCR工具
        guide.append("【工具1】文字识别 (OCR)\n");
        guide.append("┌─────────────────────────────────────────────────────┐\n");
        guide.append("│ 功能：识别图片或PDF中的文字内容                      │\n");
        guide.append("├─────────────────────────────────────────────────────┤\n");
        guide.append("│ 支持格式：JPEG、PNG、WebP、PDF                      │\n");
        guide.append("│ 支持语言：中文、英文、日语、韩语（自动检测）          │\n");
        guide.append("├─────────────────────────────────────────────────────┤\n");
        guide.append("│ 使用示例：                                          │\n");
        guide.append("│   • 帮我识别图片 /storage/emulated/0/test.jpg       │\n");
        guide.append("│   • 提取这张图片的文字                              │\n");
        guide.append("│   • 识别PDF文件中的中文                            │\n");
        guide.append("│   • OCR识别照片中的英文文字                        │\n");
        guide.append("├─────────────────────────────────────────────────────┤\n");
        guide.append("│ 必要参数：image_path（图片路径）                    │\n");
        guide.append("│ 可选参数：language（语言，默认auto）               │\n");
        guide.append("└─────────────────────────────────────────────────────┘\n\n");
        
        // 图片处理
        guide.append("【工具2】图片处理\n");
        guide.append("┌─────────────────────────────────────────────────────┐\n");
        guide.append("│ 功能：裁剪、缩放、旋转、生成图片                      │\n");
        guide.append("├─────────────────────────────────────────────────────┤\n");
        guide.append("│ 支持操作：裁剪、缩放、旋转、保存、生成                │\n");
        guide.append("│ 输出格式：JPEG、PNG、WEBP                          │\n");
        guide.append("├─────────────────────────────────────────────────────┤\n");
        guide.append("│ 使用示例：                                          │\n");
        guide.append("│   • 裁剪图片 /storage/emulated/0/photo.jpg         │\n");
        guide.append("│   • 将图片缩放到 800x600                           │\n");
        guide.append("│   • 旋转图片90度                                   │\n");
        guide.append("│   • 生成一张红色背景图片（1080x1920）              │\n");
        guide.append("│   • 生成带文字的图片                                │\n");
        guide.append("├─────────────────────────────────────────────────────┤\n");
        guide.append("│ 必要参数：image_path（输入图片路径，生成图片除外）  │\n");
        guide.append("│          output_path（输出路径）                    │\n");
        guide.append("│ 可选参数：width、height、degrees、color等          │\n");
        guide.append("└─────────────────────────────────────────────────────┘\n\n");
        
        // 文件处理
        guide.append("【工具3】文件解析\n");
        guide.append("┌─────────────────────────────────────────────────────┐\n");
        guide.append("│ 功能：读取和解析各种文件格式                        │\n");
        guide.append("├─────────────────────────────────────────────────────┤\n");
        guide.append("│ 支持格式：TXT、JSON、CSV、PDF                      │\n");
        guide.append("├─────────────────────────────────────────────────────┤\n");
        guide.append("│ 使用示例：                                          │\n");
        guide.append("│   • 读取文件 /storage/emulated/0/note.txt          │\n");
        guide.append("│   • 解析JSON文件 /storage/emulated/0/data.json     │\n");
        guide.append("│   • 解析CSV文件 /storage/emulated/0/data.csv       │\n");
        guide.append("│   • 查看文件类型                                   │\n");
        guide.append("│   • 读取文件第1-50行                               │\n");
        guide.append("├─────────────────────────────────────────────────────┤\n");
        guide.append("│ 必要参数：file_path（文件路径）                     │\n");
        guide.append("│ 可选参数：start_line、end_line、type                │\n");
        guide.append("└─────────────────────────────────────────────────────┘\n\n");
        
        // 网页解析
        guide.append("【工具4】网页解析\n");
        guide.append("┌─────────────────────────────────────────────────────┐\n");
        guide.append("│ 功能：解析网页内容，提取信息                        │\n");
        guide.append("├─────────────────────────────────────────────────────┤\n");
        guide.append("│ 支持操作：获取标题、链接、图片、正文                │\n");
        guide.append("├─────────────────────────────────────────────────────┤\n");
        guide.append("│ 使用示例：                                          │\n");
        guide.append("│   • 获取网页标题                                   │\n");
        guide.append("│   • 提取网页中的链接                                │\n");
        guide.append("│   • 获取网页正文内容                                │\n");
        guide.append("│   • 列出网页中的图片                                │\n");
        guide.append("│   • 解析HTML内容                                   │\n");
        guide.append("├─────────────────────────────────────────────────────┤\n");
        guide.append("│ 使用方式：直接粘贴网址或提供HTML内容                │\n");
        guide.append("│ 必要参数：source（网页URL或HTML内容）               │\n");
        guide.append("│ 可选参数：source_type（string/file）                │\n");
        guide.append("└─────────────────────────────────────────────────────┘\n\n");
        
        // 搜索功能
        guide.append("【工具5】网络搜索\n");
        guide.append("┌─────────────────────────────────────────────────────┐\n");
        guide.append("│ 功能：搜索网络信息                                  │\n");
        guide.append("├─────────────────────────────────────────────────────┤\n");
        guide.append("│ 使用示例：                                          │\n");
        guide.append("│   • 搜索最新新闻                                   │\n");
        guide.append("│   • 查找天气信息                                   │\n");
        guide.append("│   • 帮我搜索人工智能                                │\n");
        guide.append("│   • 搜索今天的头条新闻                             │\n");
        guide.append("├─────────────────────────────────────────────────────┤\n");
        guide.append("│ 提示：明确说明要搜索的内容                          │\n");
        guide.append("└─────────────────────────────────────────────────────┘\n\n");
        
        // 翻译功能
        guide.append("【工具6】翻译\n");
        guide.append("┌─────────────────────────────────────────────────────┐\n");
        guide.append("│ 功能：多语言互译                                    │\n");
        guide.append("├─────────────────────────────────────────────────────┤\n");
        guide.append("│ 支持语言：中文↔英文、日语、韩语                    │\n");
        guide.append("├─────────────────────────────────────────────────────┤\n");
        guide.append("│ 使用示例：                                          │\n");
        guide.append("│   • 翻译这段英文                                   │\n");
        guide.append("│   • 把中文翻译成英文                               │\n");
        guide.append("│   • 日语翻译                                       │\n");
        guide.append("│   • 将这句话翻译成韩文                             │\n");
        guide.append("└─────────────────────────────────────────────────────┘\n\n");
        
        // 计算器
        guide.append("【工具7】计算器\n");
        guide.append("┌─────────────────────────────────────────────────────┐\n");
        guide.append("│ 功能：数学计算                                      │\n");
        guide.append("├─────────────────────────────────────────────────────┤\n");
        guide.append("│ 支持运算：加减乘除、小数、括号                      │\n");
        guide.append("├─────────────────────────────────────────────────────┤\n");
        guide.append("│ 使用示例：                                          │\n");
        guide.append("│   • 计算 100 + 200                                 │\n");
        guide.append("│   • 3.14 * 5                                       │\n");
        guide.append("│   • 100 / 4                                        │\n");
        guide.append("│   • (10 + 5) * 2                                   │\n");
        guide.append("└─────────────────────────────────────────────────────┘\n\n");
        
        // 天气查询
        guide.append("【工具8】天气查询\n");
        guide.append("┌─────────────────────────────────────────────────────┐\n");
        guide.append("│ 功能：查询天气信息                                  │\n");
        guide.append("├─────────────────────────────────────────────────────┤\n");
        guide.append("│ 使用示例：                                          │\n");
        guide.append("│   • 今天天气怎么样                                 │\n");
        guide.append("│   • 查询北京天气                                   │\n");
        guide.append("│   • 天气预报                                       │\n");
        guide.append("│   • 今天气温多少度                                 │\n");
        guide.append("└─────────────────────────────────────────────────────┘\n\n");
        
        // 数据库操作
        guide.append("【工具9】数据库操作\n");
        guide.append("┌─────────────────────────────────────────────────────┐\n");
        guide.append("│ 功能：数据库查询和管理                              │\n");
        guide.append("├─────────────────────────────────────────────────────┤\n");
        guide.append("│ 使用示例：                                          │\n");
        guide.append("│   • 查询数据库记录                                 │\n");
        guide.append("│   • 插入数据到数据库                               │\n");
        guide.append("│   • 更新数据库记录                                 │\n");
        guide.append("│   • 删除数据库记录                                 │\n");
        guide.append("└─────────────────────────────────────────────────────┘\n\n");
        
        // 系统资源
        guide.append("【工具10】系统资源\n");
        guide.append("┌─────────────────────────────────────────────────────┐\n");
        guide.append("│ 功能：查看系统资源使用情况                          │\n");
        guide.append("├─────────────────────────────────────────────────────┤\n");
        guide.append("│ 使用示例：                                          │\n");
        guide.append("│   • 查看内存使用                                   │\n");
        guide.append("│   • 查看存储信息                                   │\n");
        guide.append("│   • 查看CPU信息                                    │\n");
        guide.append("│   • 查看电池状态                                   │\n");
        guide.append("└─────────────────────────────────────────────────────┘\n\n");
        
        // 智能研究
        guide.append("【工具11】智能研究\n");
        guide.append("┌─────────────────────────────────────────────────────┐\n");
        guide.append("│ 功能：智能信息检索和研究                            │\n");
        guide.append("├─────────────────────────────────────────────────────┤\n");
        guide.append("│ 使用示例：                                          │\n");
        guide.append("│   • 研究人工智能                                    │\n");
        guide.append("│   • 查找学术资料                                   │\n");
        guide.append("│   • 智能搜索                                       │\n");
        guide.append("│   • 资料汇总                                       │\n");
        guide.append("└─────────────────────────────────────────────────────┘\n\n");
        
        // 权限管理
        guide.append("【工具12】权限管理\n");
        guide.append("┌─────────────────────────────────────────────────────┐\n");
        guide.append("│ 功能：管理应用权限                                  │\n");
        guide.append("├─────────────────────────────────────────────────────┤\n");
        guide.append("│ 使用示例：                                          │\n");
        guide.append("│   • 检查权限                                       │\n");
        guide.append("│   • 请求权限                                       │\n");
        guide.append("│   • 查看权限状态                                   │\n");
        guide.append("│   • 权限设置                                       │\n");
        guide.append("└─────────────────────────────────────────────────────┘\n\n");
        
        // 应用操作
        guide.append("【工具13】应用操作\n");
        guide.append("┌─────────────────────────────────────────────────────┐\n");
        guide.append("│ 功能：管理应用程序                                  │\n");
        guide.append("├─────────────────────────────────────────────────────┤\n");
        guide.append("│ 使用示例：                                          │\n");
        guide.append("│   • 打开应用                                       │\n");
        guide.append("│   • 关闭应用                                       │\n");
        guide.append("│   • 安装应用                                       │\n");
        guide.append("│   • 卸载应用                                       │\n");
        guide.append("└─────────────────────────────────────────────────────┘\n\n");
        
        // 位置服务
        guide.append("【工具14】位置服务\n");
        guide.append("┌─────────────────────────────────────────────────────┐\n");
        guide.append("│ 功能：获取位置信息                                  │\n");
        guide.append("├─────────────────────────────────────────────────────┤\n");
        guide.append("│ 使用示例：                                          │\n");
        guide.append("│   • 获取当前位置                                   │\n");
        guide.append("│   • 定位                                          │\n");
        guide.append("│   • 获取地址信息                                   │\n");
        guide.append("│   • 导航                                          │\n");
        guide.append("└─────────────────────────────────────────────────────┘\n\n");
        
        // 最佳实践
        guide.append("═══════════════════════════════════════════════════════\n");
        guide.append("                    最佳实践\n");
        guide.append("═══════════════════════════════════════════════════════\n\n");
        
        guide.append("【如何提高意图识别准确率】\n");
        guide.append("┌─────────────────────────────────────────────────────┐\n");
        guide.append("│ 1. 使用明确动词：识别、读取、解析、搜索、翻译、计算 │\n");
        guide.append("│ 2. 提供完整路径：/storage/emulated/0/test.jpg      │\n");
        guide.append("│ 3. 指定语言：识别中文文字、翻译成英文               │\n");
        guide.append("│ 4. 说明操作对象：图片、文件、网页                  │\n");
        guide.append("│ 5. 使用完整句子：帮我识别这张图片中的文字          │\n");
        guide.append("└─────────────────────────────────────────────────────┘\n\n");
        
        guide.append("【常用关键词参考】\n");
        guide.append("┌─────────┬─────────────────────────────────────────┐\n");
        guide.append("│ 工具类型 │ 推荐关键词                             │\n");
        guide.append("├─────────┼─────────────────────────────────────────┤\n");
        guide.append("│ OCR     │ 识别、提取文字、OCR、图片文字           │\n");
        guide.append("│ 图片     │ 图片、照片、裁剪、缩放、旋转、生成     │\n");
        guide.append("│ 文件     │ 文件、读取、解析、JSON、CSV、TXT       │\n");
        guide.append("│ 网页     │ 网页、网站、HTML、链接、网址           │\n");
        guide.append("│ 搜索     │ 搜索、查找、查询                      │\n");
        guide.append("│ 翻译     │ 翻译、英文、中文、日语、韩语           │\n");
        guide.append("│ 计算     │ 计算、加、减、乘、除                   │\n");
        guide.append("└─────────┴─────────────────────────────────────────┘\n\n");
        
        guide.append("【完整操作流程示例】\n");
        guide.append("用户输入 → 意图识别 → 工具选择 → 参数构建 → 工具执行 → 返回结果\n\n");
        guide.append("示例：\n");
        guide.append("1. 用户：\"帮我识别图片 /storage/emulated/0/test.jpg\"\n");
        guide.append("2. 识别：OCR识别意图（置信度85%）\n");
        guide.append("3. 选择：app_toolkit工具\n");
        guide.append("4. 参数：{action: ocr_recognize, image_path: ...}\n");
        guide.append("5. 执行：调用OCR引擎识别图片\n");
        guide.append("6. 返回：识别到的文字内容\n\n");
        
        // 常见问题
        guide.append("═══════════════════════════════════════════════════════\n");
        guide.append("                    常见问题解答\n");
        guide.append("═══════════════════════════════════════════════════════\n\n");
        
        guide.append("【Q1】工具调用失败怎么办？\n");
        guide.append("├─ 检查文件路径是否正确\n");
        guide.append("├─ 确认文件是否存在\n");
        guide.append("├─ 检查文件格式是否支持\n");
        guide.append("└─ 确保应用有读取文件的权限\n\n");
        
        guide.append("【Q2】意图识别错误怎么办？\n");
        guide.append("├─ 使用更明确的动词：识别、读取、解析等\n");
        guide.append("├─ 提供完整的文件路径\n");
        guide.append("├─ 指定操作对象和目标\n");
        guide.append("└─ 尝试使用调试功能分析意图\n\n");
        
        guide.append("【Q3】如何确认我使用的是工具还是AI回答？\n");
        guide.append("├─ 工具执行时会显示\"正在调用工具\"提示\n");
        guide.append("├─ 工具返回结构化结果（包含状态、数据）\n");
        guide.append("└─ AI回答是自然语言文本\n\n");
        
        guide.append("【Q4】文件路径应该怎么写？\n");
        guide.append("├─ 完整路径示例：/storage/emulated/0/test.jpg\n");
        guide.append("├─ 外部存储根目录：/storage/emulated/0/\n");
        guide.append("└─ SD卡路径：/storage/sdcard1/\n\n");
        
        guide.append("【Q5】如何获取文件路径？\n");
        guide.append("├─ 使用文件选择器选择文件\n");
        guide.append("├─ 在文件管理器中查看属性\n");
        guide.append("└─ 通过系统API获取文件Uri\n\n");
        
        // 进阶用法
        guide.append("═══════════════════════════════════════════════════════\n");
        guide.append("                    进阶用法\n");
        guide.append("═══════════════════════════════════════════════════════\n\n");
        
        guide.append("【组合使用工具】\n");
        guide.append("示例：\n");
        guide.append("1. \"先识别图片中的文字，然后翻译为英文\"\n");
        guide.append("   → OCR识别 → 翻译工具\n");
        guide.append("\n2. \"读取JSON文件，然后分析数据\"\n");
        guide.append("   → 文件解析 → AI分析\n\n");
        
        guide.append("【调试意图识别】\n");
        guide.append("使用调试功能可以查看意图识别结果：\n");
        guide.append("• 输入：\"调试意图：帮我识别图片\"\n");
        guide.append("• 输出：显示识别的意图、置信度、推荐工具\n\n");
        
        guide.append("【生成调试报告】\n");
        guide.append("获取详细的意图分析报告：\n");
        guide.append("• 输入：\"分析我的请求\"\n");
        guide.append("• 输出：完整的调试报告，包含优化建议\n\n");
        
        // 权限说明
        guide.append("【权限说明】\n");
        guide.append("┌─────────┬─────────────────────────────────────────┐\n");
        guide.append("│ 功能     │ 需要的权限                             │\n");
        guide.append("├─────────┼─────────────────────────────────────────┤\n");
        guide.append("│ OCR     │ 读取图片文件权限                        │\n");
        guide.append("│ 图片处理 │ 读取和写入图片文件权限                  │\n");
        guide.append("│ 文件解析 │ 读取文件权限                          │\n");
        guide.append("│ 网络搜索 │ 网络访问权限                          │\n");
        guide.append("│ 翻译     │ 网络访问权限                          │\n");
        guide.append("└─────────┴─────────────────────────────────────────┘\n\n");
        
        // 结束语
        guide.append("═══════════════════════════════════════════════════════\n");
        guide.append("                    开始使用\n");
        guide.append("═══════════════════════════════════════════════════════\n\n");
        
        guide.append("请输入你的需求，我会自动判断使用哪个工具！\n\n");
        guide.append("示例：\n");
        guide.append("• \"帮我识别图片 /storage/emulated/0/test.jpg\"\n");
        guide.append("• \"读取文件 /storage/emulated/0/note.txt\"\n");
        guide.append("• \"搜索今天的新闻\"\n");
        guide.append("• \"翻译这段英文\"\n");
        guide.append("• \"计算 100 + 200\"\n\n");
        
        guide.append("如果需要帮助，请输入：\n");
        guide.append("• \"显示使用教程\" - 查看完整指南\n");
        guide.append("• \"调试意图：你的请求\" - 分析意图识别\n");
        guide.append("• \"分析我的请求\" - 生成调试报告\n\n");
        
        guide.append("═══════════════════════════════════════════════════════\n");
        
        return guide.toString();
    }
    
    /**
     * 获取工具列表
     */
    public static List<Map<String, Object>> getToolList(Context context) {
        List<Map<String, Object>> tools = new ArrayList<>();
        
        AIToolManager manager = AIToolManager.getInstance(context);
        
        for (AITool tool : manager.getTools()) {
            Map<String, Object> toolInfo = new HashMap<>();
            toolInfo.put("name", tool.getName());
            toolInfo.put("description", tool.getDescription());
            toolInfo.put("parameters", tool.getParameterDescriptions());
            tools.add(toolInfo);
        }
        
        return tools;
    }
    
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
        
        // 意图识别
        IntelligentIntentRecognizer.IntentResult result = 
            IntelligentIntentRecognizer.recognize(userInput, null);
        
        report.append("【意图识别结果】\n");
        report.append("┌─────────────────────────────────────────────────────┐\n");
        report.append("│ 识别意图：").append(result.primaryIntent.name()).append("\n");
        report.append("│ 意图描述：").append(result.primaryIntent.getDisplayName()).append("\n");
        report.append("│ 置信度：").append(String.format("%.2f", result.confidence * 100)).append("%\n");
        
        // 推荐工具
        String toolName = IntelligentIntentRecognizer.getRecommendedTool(result.primaryIntent);
        report.append("│ 推荐工具：").append(toolName != null ? toolName : "无（直接回答）").append("\n");
        report.append("└─────────────────────────────────────────────────────┘\n\n");
        
        // 参数预测
        Map<String, Object> params = IntelligentIntentRecognizer.buildToolParameters(result.primaryIntent, userInput);
        report.append("【预测参数】\n");
        report.append("┌─────────────────────────────────────────────────────┐\n");
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            report.append("│ ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        report.append("└─────────────────────────────────────────────────────┘\n");
        
        // 建议
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
        
        // 示例提示
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