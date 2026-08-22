package com.oilquiz.app.util;

import com.oilquiz.app.R;

import java.util.HashMap;
import java.util.Map;

public class AIIconMapper {

    public static final int UNKNOWN_ICON = R.drawable.ic_ai_robot;

    private static final Map<String, Integer> ICON_MAP = new HashMap<>();
    private static final Map<String, AIIconInfo> ICON_INFO_MAP = new HashMap<>();

    static {
        ICON_MAP.put("ai_brain", R.drawable.ic_ai_brain);
        ICON_MAP.put("ai_robot", R.drawable.ic_ai_robot);
        ICON_MAP.put("ai_thinking", R.drawable.ic_ai_thinking);
        ICON_MAP.put("ai_response", R.drawable.ic_ai_response);
        ICON_MAP.put("ai_analyze", R.drawable.ic_ai_analyze);
        ICON_MAP.put("ai_translate", R.drawable.ic_ai_translate);
        ICON_MAP.put("ai_generate", R.drawable.ic_ai_generate);
        ICON_MAP.put("ai_summarize", R.drawable.ic_ai_summarize);
        ICON_MAP.put("ai_question", R.drawable.ic_ai_question);
        ICON_MAP.put("ai_tool", R.drawable.ic_ai_tool);
        ICON_MAP.put("ai_tool_call", R.drawable.ic_ai_tool_call);
        ICON_MAP.put("ai_ocr", R.drawable.ic_ai_ocr);
        ICON_MAP.put("ai_model", R.drawable.ic_ai_model);
        ICON_MAP.put("ai_download", R.drawable.ic_ai_download);
        ICON_MAP.put("ai_upload", R.drawable.ic_ai_upload);
        ICON_MAP.put("ai_loading", R.drawable.ic_ai_loading);
        ICON_MAP.put("ai_success", R.drawable.ic_ai_success);
        ICON_MAP.put("ai_error", R.drawable.ic_ai_error);
        ICON_MAP.put("ai_warning", R.drawable.ic_ai_warning);
        ICON_MAP.put("ai_learning", R.drawable.ic_ai_learning);
        ICON_MAP.put("ai_agent", R.drawable.ic_ai_agent);
        ICON_MAP.put("ai_chat_bubble", R.drawable.ic_ai_chat_bubble);
        ICON_MAP.put("ai_mic", R.drawable.ic_ai_mic);
        ICON_MAP.put("ai_code", R.drawable.ic_ai_code);
        ICON_MAP.put("ai_image", R.drawable.ic_ai_image);
        ICON_MAP.put("ai_memory", R.drawable.ic_ai_memory);
        ICON_MAP.put("ai_connect", R.drawable.ic_ai_connect);
        ICON_MAP.put("ai_chat", R.drawable.ic_ai_chat);

        ICON_INFO_MAP.put("ai_brain", new AIIconInfo(
            "ai_brain",
            R.drawable.ic_ai_brain,
            "AI大脑",
            "用于表示AI核心功能、智能中心等",
            AIIconCategory.CORE,
            "primary"
        ));
        ICON_INFO_MAP.put("ai_robot", new AIIconInfo(
            "ai_robot",
            R.drawable.ic_ai_robot,
            "AI机器人",
            "用于表示AI助手、聊天机器人等",
            AIIconCategory.CORE,
            "primary"
        ));
        ICON_INFO_MAP.put("ai_thinking", new AIIconInfo(
            "ai_thinking",
            R.drawable.ic_ai_thinking,
            "AI思考中",
            "用于表示AI正在处理、思考、生成响应等状态",
            AIIconCategory.STATUS,
            "thinking"
        ));
        ICON_INFO_MAP.put("ai_response", new AIIconInfo(
            "ai_response",
            R.drawable.ic_ai_response,
            "AI响应",
            "用于表示AI已完成响应、消息已发送",
            AIIconCategory.CHAT,
            "success"
        ));

        ICON_INFO_MAP.put("ai_analyze", new AIIconInfo(
            "ai_analyze",
            R.drawable.ic_ai_analyze,
            "AI分析",
            "用于表示数据分析、趋势分析、问题分析等功能",
            AIIconCategory.FUNCTION,
            "primary"
        ));
        ICON_INFO_MAP.put("ai_translate", new AIIconInfo(
            "ai_translate",
            R.drawable.ic_ai_translate,
            "AI翻译",
            "用于表示文本翻译、语言转换功能",
            AIIconCategory.FUNCTION,
            "secondary"
        ));
        ICON_INFO_MAP.put("ai_generate", new AIIconInfo(
            "ai_generate",
            R.drawable.ic_ai_generate,
            "AI生成",
            "用于表示内容生成、创作、出题等功能",
            AIIconCategory.FUNCTION,
            "primary"
        ));
        ICON_INFO_MAP.put("ai_summarize", new AIIconInfo(
            "ai_summarize",
            R.drawable.ic_ai_summarize,
            "AI摘要",
            "用于表示内容摘要、总结、提炼等功能",
            AIIconCategory.FUNCTION,
            "tertiary"
        ));
        ICON_INFO_MAP.put("ai_question", new AIIconInfo(
            "ai_question",
            R.drawable.ic_ai_question,
            "AI问答",
            "用于表示问答功能、帮助、常见问题等",
            AIIconCategory.FUNCTION,
            "secondary"
        ));

        ICON_INFO_MAP.put("ai_tool", new AIIconInfo(
            "ai_tool",
            R.drawable.ic_ai_tool,
            "AI工具",
            "用于表示工具箱、工具集合、插件等",
            AIIconCategory.TOOL,
            "primary"
        ));
        ICON_INFO_MAP.put("ai_tool_call", new AIIconInfo(
            "ai_tool_call",
            R.drawable.ic_ai_tool_call,
            "工具调用",
            "用于表示Agent工具调用、函数执行等",
            AIIconCategory.TOOL,
            "success"
        ));
        ICON_INFO_MAP.put("ai_ocr", new AIIconInfo(
            "ai_ocr",
            R.drawable.ic_ai_ocr,
            "AI OCR",
            "用于表示文字识别、图像转文字功能",
            AIIconCategory.TOOL,
            "primary"
        ));
        ICON_INFO_MAP.put("ai_model", new AIIconInfo(
            "ai_model",
            R.drawable.ic_ai_model,
            "AI模型",
            "用于表示模型管理、模型选择、模型状态",
            AIIconCategory.TOOL,
            "primary"
        ));
        ICON_INFO_MAP.put("ai_download", new AIIconInfo(
            "ai_download",
            R.drawable.ic_ai_download,
            "模型下载",
            "用于表示模型下载、资源获取",
            AIIconCategory.TOOL,
            "success"
        ));
        ICON_INFO_MAP.put("ai_upload", new AIIconInfo(
            "ai_upload",
            R.drawable.ic_ai_upload,
            "数据上传",
            "用于表示文件上传、数据导入",
            AIIconCategory.TOOL,
            "warning"
        ));

        ICON_INFO_MAP.put("ai_loading", new AIIconInfo(
            "ai_loading",
            R.drawable.ic_ai_loading,
            "加载中",
            "用于表示AI正在加载、处理、初始化等状态",
            AIIconCategory.STATUS,
            "primary"
        ));
        ICON_INFO_MAP.put("ai_success", new AIIconInfo(
            "ai_success",
            R.drawable.ic_ai_success,
            "成功",
            "用于表示操作成功、任务完成等状态",
            AIIconCategory.STATUS,
            "success"
        ));
        ICON_INFO_MAP.put("ai_error", new AIIconInfo(
            "ai_error",
            R.drawable.ic_ai_error,
            "错误",
            "用于表示操作失败、错误发生等状态",
            AIIconCategory.STATUS,
            "error"
        ));
        ICON_INFO_MAP.put("ai_warning", new AIIconInfo(
            "ai_warning",
            R.drawable.ic_ai_warning,
            "警告",
            "用于表示警告、注意事项等状态",
            AIIconCategory.STATUS,
            "warning"
        ));
        ICON_INFO_MAP.put("ai_learning", new AIIconInfo(
            "ai_learning",
            R.drawable.ic_ai_learning,
            "学习助手",
            "用于表示学习助手、智能学习、学习进度等",
            AIIconCategory.STATUS,
            "success"
        ));
        ICON_INFO_MAP.put("ai_agent", new AIIconInfo(
            "ai_agent",
            R.drawable.ic_ai_agent,
            "AI Agent",
            "用于表示智能代理、自主执行、工作流等",
            AIIconCategory.CORE,
            "primary"
        ));

        ICON_INFO_MAP.put("ai_chat_bubble", new AIIconInfo(
            "ai_chat_bubble",
            R.drawable.ic_ai_chat_bubble,
            "聊天气泡",
            "用于表示消息、对话、聊天记录等",
            AIIconCategory.CHAT,
            "primary"
        ));
        ICON_INFO_MAP.put("ai_mic", new AIIconInfo(
            "ai_mic",
            R.drawable.ic_ai_mic,
            "语音输入",
            "用于表示语音输入、语音转文字、语音助手",
            AIIconCategory.CHAT,
            "secondary"
        ));
        ICON_INFO_MAP.put("ai_code", new AIIconInfo(
            "ai_code",
            R.drawable.ic_ai_code,
            "AI代码",
            "用于表示代码生成、代码分析、代码审查",
            AIIconCategory.FUNCTION,
            "primary"
        ));
        ICON_INFO_MAP.put("ai_image", new AIIconInfo(
            "ai_image",
            R.drawable.ic_ai_image,
            "AI图像",
            "用于表示图像生成、图像处理、图像分析",
            AIIconCategory.FUNCTION,
            "tertiary"
        ));
        ICON_INFO_MAP.put("ai_memory", new AIIconInfo(
            "ai_memory",
            R.drawable.ic_ai_memory,
            "AI记忆",
            "用于表示上下文记忆、历史记录、知识库",
            AIIconCategory.CORE,
            "primary"
        ));
        ICON_INFO_MAP.put("ai_connect", new AIIconInfo(
            "ai_connect",
            R.drawable.ic_ai_connect,
            "AI连接",
            "用于表示API连接、服务连接、网络连接",
            AIIconCategory.TOOL,
            "success"
        ));
        ICON_INFO_MAP.put("ai_chat", new AIIconInfo(
            "ai_chat",
            R.drawable.ic_ai_chat,
            "AI聊天",
            "用于表示AI聊天、对话界面入口",
            AIIconCategory.CHAT,
            "primary"
        ));
    }

    public static int getIconResource(String iconKey) {
        Integer resId = ICON_MAP.get(iconKey);
        return resId != null ? resId : UNKNOWN_ICON;
    }

    public static AIIconInfo getIconInfo(String iconKey) {
        return ICON_INFO_MAP.get(iconKey);
    }

    public static Map<String, Integer> getAllIcons() {
        return new HashMap<>(ICON_MAP);
    }

    public static Map<String, AIIconInfo> getAllIconInfo() {
        return new HashMap<>(ICON_INFO_MAP);
    }

    public static int getIconByCategoryAndAction(AIIconCategory category, String action) {
        for (Map.Entry<String, AIIconInfo> entry : ICON_INFO_MAP.entrySet()) {
            AIIconInfo info = entry.getValue();
            if (info.getCategory() == category) {
                String desc = info.getDescription().toLowerCase();
                String displayName = info.getDisplayName().toLowerCase();
                if (desc.contains(action.toLowerCase()) || displayName.contains(action.toLowerCase())) {
                    return info.getResourceId();
                }
            }
        }
        return UNKNOWN_ICON;
    }

    public static int getIconForIntent(String intentType) {
        switch (intentType.toLowerCase()) {
            case "chat":
            case "conversation":
            case "message":
                return R.drawable.ic_ai_chat_bubble;
            case "analyze":
            case "analysis":
            case "report":
                return R.drawable.ic_ai_analyze;
            case "generate":
            case "create":
            case "compose":
                return R.drawable.ic_ai_generate;
            case "summarize":
            case "summary":
            case "abstract":
                return R.drawable.ic_ai_summarize;
            case "ocr":
            case "scan":
            case "recognize":
                return R.drawable.ic_ai_ocr;
            case "code":
            case "coding":
            case "programming":
                return R.drawable.ic_ai_code;
            case "image":
            case "picture":
            case "photo":
                return R.drawable.ic_ai_image;
            case "tool":
            case "plugin":
            case "extension":
                return R.drawable.ic_ai_tool;
            case "tool_call":
            case "function":
            case "execute":
                return R.drawable.ic_ai_tool_call;
            case "model":
            case "llm":
            case "model_select":
                return R.drawable.ic_ai_model;
            case "download":
            case "get":
            case "fetch":
                return R.drawable.ic_ai_download;
            case "upload":
            case "import":
            case "send":
                return R.drawable.ic_ai_upload;
            case "voice":
            case "speech":
            case "audio":
                return R.drawable.ic_ai_mic;
            case "memory":
            case "context":
            case "history":
                return R.drawable.ic_ai_memory;
            case "agent":
            case "workflow":
            case "automation":
                return R.drawable.ic_ai_agent;
            case "success":
            case "done":
            case "complete":
                return R.drawable.ic_ai_success;
            case "error":
            case "fail":
            case "exception":
                return R.drawable.ic_ai_error;
            case "warning":
            case "caution":
            case "alert":
                return R.drawable.ic_ai_warning;
            case "loading":
            case "processing":
            case "thinking":
                return R.drawable.ic_ai_loading;
            case "brain":
            case "intelligence":
            case "core":
                return R.drawable.ic_ai_brain;
            case "robot":
            case "assistant":
            case "bot":
                return R.drawable.ic_ai_robot;
            case "connect":
            case "network":
            case "api":
                return R.drawable.ic_ai_connect;
            case "question":
            case "help":
            case "faq":
                return R.drawable.ic_ai_question;
            case "learning":
            case "study":
            case "education":
                return R.drawable.ic_ai_learning;
            default:
                return UNKNOWN_ICON;
        }
    }

    public enum AIIconCategory {
        CORE,
        FUNCTION,
        TOOL,
        STATUS,
        CHAT
    }

    public static class AIIconInfo {
        private final String key;
        private final int resourceId;
        private final String displayName;
        private final String description;
        private final AIIconCategory category;
        private final String colorTheme;

        public AIIconInfo(String key, int resourceId, String displayName, String description,
                          AIIconCategory category, String colorTheme) {
            this.key = key;
            this.resourceId = resourceId;
            this.displayName = displayName;
            this.description = description;
            this.category = category;
            this.colorTheme = colorTheme;
        }

        public String getKey() {
            return key;
        }

        public int getResourceId() {
            return resourceId;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getDescription() {
            return description;
        }

        public AIIconCategory getCategory() {
            return category;
        }

        public String getColorTheme() {
            return colorTheme;
        }
    }
}
