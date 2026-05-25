package com.oilquiz.app.ai.tool;

import android.Manifest;
import android.util.Log;

import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Dependency;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;
import com.oilquiz.app.ai.tool.annotation.ToolDependencies;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具Schema自动提取器
 * 
 * 功能：
 * 1. 从AITool实例自动提取工具schema（基于注解或接口方法）
 * 2. 支持注解驱动和反射驱动两种方式
 * 3. 提取工具依赖关系
 * 4. 生成AgentService可用的ToolSchema
 */
public class ToolSchemaExtractor {
    private static final String TAG = "ToolSchemaExtractor";
    
    /**
     * 工具Schema信息
     */
    public static class ExtractedSchema {
        public final String name;
        public final String description;
        public final String paramDesc;
        public final String category;
        public final String[] aliases;
        public final List<ToolDependency> dependencies;
        
        public ExtractedSchema(String name, String description, String paramDesc,
                              String category, String[] aliases, List<ToolDependency> deps) {
            this.name = name;
            this.description = description;
            this.paramDesc = paramDesc;
            this.category = category;
            this.aliases = aliases;
            this.dependencies = deps;
        }
    }
    
    /**
     * 工具依赖信息
     */
    public static class ToolDependency {
        public final String tool;
        public final String action;
        public final String condition;
        public final String conditionParam;
        public final String argsTemplate;
        public final boolean blockOnFailure;
        public final String description;
        
        public ToolDependency(String tool, String action, String condition,
                             String conditionParam, String argsTemplate,
                             boolean blockOnFailure, String description) {
            this.tool = tool;
            this.action = action;
            this.condition = condition;
            this.conditionParam = conditionParam;
            this.argsTemplate = argsTemplate;
            this.blockOnFailure = blockOnFailure;
            this.description = description;
        }
        
        /**
         * 检查依赖条件是否满足
         */
        public boolean isConditionMet(Map<String, Object> context) {
            switch (condition) {
                case "permission_not_granted":
                    return isPermissionNotGranted(context);
                case "always":
                    return true;
                default:
                    return true;
            }
        }
        
        private boolean isPermissionNotGranted(Map<String, Object> context) {
            String permission = conditionParam;
            if (permission == null || permission.isEmpty()) {
                return false;
            }
            
            Boolean granted = (Boolean) context.get("permission_" + permission);
            return granted == null || !granted;
        }
        
        /**
         * 构建前置工具调用参数
         */
        public Map<String, Object> buildArgs() {
            Map<String, Object> args = new HashMap<>();
            if (argsTemplate != null && !argsTemplate.isEmpty()) {
                try {
                    String[] pairs = argsTemplate.replace("{", "").replace("}", "").split(",");
                    for (String pair : pairs) {
                        String[] kv = pair.split(":", 2);
                        if (kv.length == 2) {
                            args.put(kv[0].trim(), kv[1].trim().replace("\"", ""));
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to parse args template: " + argsTemplate);
                }
            }
            if (action != null && !action.isEmpty() && !args.containsKey("action")) {
                args.put("action", action);
            }
            return args;
        }
    }
    
    /**
     * 从AITool实例提取Schema
     */
    public static ExtractedSchema extract(AITool tool) {
        // 优先使用注解
        Tool toolAnnotation = tool.getClass().getAnnotation(Tool.class);
        if (toolAnnotation != null) {
            return extractFromAnnotation(tool, toolAnnotation);
        }
        
        // 回退到反射提取
        return extractFromReflection(tool);
    }
    
    /**
     * 从注解提取Schema
     */
    private static ExtractedSchema extractFromAnnotation(AITool tool, Tool annotation) {
        String name = annotation.value().isEmpty() ? tool.getName() : annotation.value();
        String description = annotation.description().isEmpty() ? tool.getDescription() : annotation.description();
        String category = annotation.category();
        String[] aliases = annotation.aliases();
        
        // 提取参数描述
        StringBuilder paramDesc = new StringBuilder();
        Map<String, String> paramDescriptions = tool.getParameterDescriptions();
        if (!paramDescriptions.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, String> entry : paramDescriptions.entrySet()) {
                parts.add(entry.getKey() + "(" + entry.getValue() + ")");
            }
            paramDesc.append(String.join(", ", parts));
        }
        
        // 处理actions
        Action[] actions = annotation.actions();
        if (actions.length > 0) {
            if (paramDesc.length() > 0) paramDesc.append(", ");
            List<String> actionNames = new ArrayList<>();
            for (Action action : actions) {
                actionNames.add(action.name());
            }
            paramDesc.append("action(操作类型,必填: ").append(String.join("/", actionNames)).append(")");
            
            // 合并每个action的参数
            for (Action action : actions) {
                Param[] actionParams = action.params();
                for (Param param : actionParams) {
                    paramDesc.append(", ").append(param.name())
                        .append("(").append(param.description())
                        .append(param.required() ? ",必填" : ",可选").append(")");
                }
            }
        }
        
        // 处理通用params
        Param[] params = annotation.params();
        if (params.length > 0) {
            if (paramDesc.length() > 0) paramDesc.append(", ");
            List<String> paramParts = new ArrayList<>();
            for (Param param : params) {
                StringBuilder desc = new StringBuilder(param.description());
                if (param.required()) desc.append(",必填");
                else desc.append(",可选");
                if (param.options().length > 0) {
                    desc.append(": ").append(String.join("/", param.options()));
                }
                paramParts.add(param.name() + "(" + desc + ")");
            }
            paramDesc.append(String.join(", ", paramParts));
        }
        
        // 提取依赖
        List<ToolDependency> dependencies = extractDependencies(tool);
        
        return new ExtractedSchema(name, description, paramDesc.toString(),
                                  category, aliases, dependencies);
    }
    
    /**
     * 从反射提取Schema（无注解时）
     */
    private static ExtractedSchema extractFromReflection(AITool tool) {
        String name = tool.getName();
        String description = tool.getDescription();
        Map<String, String> paramDescriptions = tool.getParameterDescriptions();
        
        StringBuilder paramDesc = new StringBuilder();
        if (!paramDescriptions.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, String> entry : paramDescriptions.entrySet()) {
                parts.add(entry.getKey() + "(" + entry.getValue() + ")");
            }
            paramDesc.append(String.join(", ", parts));
        }
        
        return new ExtractedSchema(name, description, paramDesc.toString(),
                                  "general", new String[0], new ArrayList<>());
    }
    
    /**
     * 提取工具依赖
     */
    public static List<ToolDependency> extractDependencies(AITool tool) {
        List<ToolDependency> dependencies = new ArrayList<>();
        
        // 检查类级别的依赖注解
        ToolDependencies depsAnnotation = tool.getClass().getAnnotation(ToolDependencies.class);
        if (depsAnnotation != null) {
            for (Dependency dep : depsAnnotation.value()) {
                dependencies.add(new ToolDependency(
                    dep.tool(),
                    dep.action(),
                    dep.condition(),
                    dep.conditionParam(),
                    dep.argsTemplate(),
                    dep.blockOnFailure(),
                    dep.description()
                ));
            }
        }
        
        return dependencies;
    }
    
    /**
     * 从参数描述Map生成参数字符串
     */
    public static String formatParamDescriptions(Map<String, String> descriptions) {
        if (descriptions == null || descriptions.isEmpty()) {
            return "无参数";
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> entry : descriptions.entrySet()) {
            parts.add(entry.getKey() + "(" + entry.getValue() + ")");
        }
        return String.join(", ", parts);
    }
}
