package com.oilquiz.app.ai.chat;

public class ModeInfo {
    public String id;
    public String name;
    public String icon;
    public String description;
    public boolean isDefault;
    
    public ModeInfo(String id, String name, String icon, String description, boolean isDefault) {
        this.id = id;
        this.name = name;
        this.icon = icon;
        this.description = description;
        this.isDefault = isDefault;
    }
    
    public static ModeInfo createNormalMode() {
        return new ModeInfo(
            "normal",
            "普通模式",
            "💬",
            "标准对话模式，平衡速度和质量，适合日常问答和聊天",
            true
        );
    }
    
    public static ModeInfo createDeepThinkingMode() {
        return new ModeInfo(
            "deep",
            "深度思考",
            "🧠",
            "更深入的分析和推理，回答更全面但速度较慢，适合复杂问题",
            false
        );
    }
    
    public static ModeInfo createAgentMode() {
        return new ModeInfo(
            "agent",
            "Agent模式",
            "🤖",
            "智能代理模式，可调用工具和执行多步骤任务，适合需要规划和执行的场景",
            false
        );
    }
    
    public static ModeInfo createCreativeMode() {
        return new ModeInfo(
            "creative",
            "创作模式",
            "✍️",
            "专注于创意内容生成，如故事、诗歌、代码等创造性任务",
            false
        );
    }
    
    public static java.util.List<ModeInfo> getDefaultModes() {
        java.util.List<ModeInfo> modes = new java.util.ArrayList<>();
        modes.add(createNormalMode());
        modes.add(createDeepThinkingMode());
        modes.add(createAgentMode());
        modes.add(createCreativeMode());
        return modes;
    }
}
