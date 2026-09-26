package com.oilquiz.app.ai.tool;

import java.util.HashMap;
import java.util.Map;

/**
 * AI工具抽象基类，实现了AITool接口的默认方法
 * 
 * 提供了工具的基本属性和默认实现，子类只需实现execute方法
 */
public abstract class BaseAITool implements AITool {
    private final String toolName;
    private final String description;

    public BaseAITool(String toolName, String description) {
        this.toolName = toolName;
        this.description = description;
    }

    @Override
    public String getName() {
        return toolName;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        return new HashMap<>();
    }
    
    public boolean canHandle(String input) {
        return input != null && input.toLowerCase().contains(toolName.toLowerCase());
    }

    /**
     * 把异常转成可读原因，供工具失败时回填给 Agent/用户。
     *
     * <p>为什么需要：{@code e.getMessage()} 允许为 null（NullPointerException、部分框架/反射异常、
     * 某些 Chaquopy 包装异常都是 null），直接拼接会得到「xx失败: 」这种**原因为空**的失败信息，
     * Agent 拿到后无从判断（真机实测：python_file_ops 报 "工具执行失败: Python文件工具失败: "）。
     * 这里在 message 为空时退化为「异常类名」，并尽量带上 cause。
     */
    protected static String errText(Throwable e) {
        if (e == null) {
            return "未知错误";
        }
        String m = e.getMessage();
        if (m != null && !m.trim().isEmpty()) {
            return m;
        }
        StringBuilder sb = new StringBuilder(e.getClass().getSimpleName());
        Throwable c = e.getCause();
        if (c != null && c != e) {
            sb.append(" <- ").append(c.getClass().getSimpleName());
            if (c.getMessage() != null && !c.getMessage().trim().isEmpty()) {
                sb.append(": ").append(c.getMessage());
            }
        }
        return sb.toString();
    }
}