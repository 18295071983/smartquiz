package com.oilquiz.app.ai.tool.openai;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ToolDefinition {
    
    private final String name;
    private final String description;
    private final List<ParamDefinition> parameters;
    private final String category;
    
    private ToolDefinition(Builder builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.parameters = builder.parameters;
        this.category = builder.category;
    }
    
    public String getName() {
        return name;
    }
    
    public String getDescription() {
        return description;
    }
    
    public List<ParamDefinition> getParameters() {
        return parameters;
    }
    
    public String getCategory() {
        return category;
    }
    
    public JSONObject toOpenAIFormat() throws JSONException {
        JSONObject tool = new JSONObject();
        tool.put("type", "function");
        
        JSONObject function = new JSONObject();
        function.put("name", name);
        function.put("description", description);
        
        JSONObject params = new JSONObject();
        params.put("type", "object");
        
        JSONObject properties = new JSONObject();
        JSONArray required = new JSONArray();
        
        for (ParamDefinition param : parameters) {
            JSONObject paramSchema = new JSONObject();
            paramSchema.put("type", param.getType());
            paramSchema.put("description", param.getDescription());
            // 不输出 default：避免引导模型只用默认参数（如 action 固定为 current）
            if (param.getEnumValues() != null && !param.getEnumValues().isEmpty()) {
                JSONArray enumArray = new JSONArray();
                for (String enumVal : param.getEnumValues()) {
                    enumArray.put(enumVal);
                }
                paramSchema.put("enum", enumArray);
            }
            properties.put(param.getName(), paramSchema);
            
            if (param.isRequired()) {
                required.put(param.getName());
            }
        }
        
        params.put("properties", properties);
        if (required.length() > 0) {
            params.put("required", required);
        }
        function.put("parameters", params);
        
        tool.put("function", function);
        return tool;
    }
    
    public String toPromptFormat() {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append(": ").append(description).append("\n");
        sb.append("参数:\n");
        for (ParamDefinition param : parameters) {
            sb.append("  - ").append(param.getName());
            sb.append(" (").append(param.getType()).append(")");
            if (param.isRequired()) sb.append(" [必填]");
            sb.append(": ").append(param.getDescription()).append("\n");
        }
        return sb.toString();
    }
    
    public static Builder builder(String name, String description) {
        return new Builder(name, description);
    }
    
    public static class Builder {
        private final String name;
        private final String description;
        private final List<ParamDefinition> parameters = new ArrayList<>();
        private String category = "general";
        
        public Builder(String name, String description) {
            this.name = name;
            this.description = description;
        }
        
        public Builder addParameter(String name, String type, String description, boolean required) {
            parameters.add(new ParamDefinition(name, type, description, required, null, null));
            return this;
        }
        
        public Builder addParameter(String name, String type, String description, boolean required, Object defaultValue) {
            parameters.add(new ParamDefinition(name, type, description, required, defaultValue, null));
            return this;
        }
        
        public Builder addParameter(String name, String type, String description, boolean required, Object defaultValue, List<String> enumValues) {
            parameters.add(new ParamDefinition(name, type, description, required, defaultValue, enumValues));
            return this;
        }
        
        public Builder category(String category) {
            this.category = category;
            return this;
        }
        
        public ToolDefinition build() {
            return new ToolDefinition(this);
        }
    }
}