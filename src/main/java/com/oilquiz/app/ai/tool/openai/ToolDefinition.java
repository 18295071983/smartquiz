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
    /** 工具调用体系 P0-1：一句话适用场景（when_to_use），辅助选型 */
    private final String whenToUse;
    
    private ToolDefinition(Builder builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.parameters = builder.parameters;
        this.category = builder.category;
        this.whenToUse = builder.whenToUse;
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
    
    public String getWhenToUse() {
        return whenToUse;
    }
    
    public JSONObject toOpenAIFormat() throws JSONException {
        return schemaFor(name, description, parameters);
    }

    /**
     * 工具 schema 的<b>唯一装配实现</b>。
     *
     * <p>为什么收成一个静态方法：{@code OnlineToolRegistry} 曾另写一份同样的装配，两处随后漂开
     * ——最典型的是 {@code default}：这里刻意不输出（避免引导模型只传默认值），那边却会输出，
     * 于是同一条 chat 链路有两个口径。定义存在时两处都走这里，"两套口径"在结构上不再可能。</p>
     *
     * <p>三条约定：<b>不输出 {@code default}</b>（工具的缺省由 execute 自己兜底，写进 schema 只会
     * 诱导模型偷懒）；{@code type} 缺省按 string（不能把 null 写进 schema 让它消失）；
     * {@code required} 只收"任何调用都必须给"的字段——按 action 成立的条件必填写在 description 里，
     * 扁平 schema 表达不了，硬标等于骗模型。</p>
     */
    public static JSONObject schemaFor(String functionName, String description,
                                       List<ParamDefinition> params) throws JSONException {
        JSONObject tool = new JSONObject();
        tool.put("type", "function");

        JSONObject function = new JSONObject();
        function.put("name", functionName != null ? functionName : "");
        function.put("description", description != null ? description : "");

        JSONObject parameters = new JSONObject();
        parameters.put("type", "object");
        JSONObject properties = new JSONObject();
        JSONArray required = new JSONArray();

        if (params != null) {
            for (ParamDefinition param : params) {
                if (param == null || param.getName() == null) continue;
                JSONObject prop = new JSONObject();
                String type = param.getType();
                prop.put("type", type == null || type.isEmpty() ? "string" : type);
                prop.put("description", param.getDescription() != null ? param.getDescription() : "");
                if (param.getEnumValues() != null && !param.getEnumValues().isEmpty()) {
                    JSONArray enumArray = new JSONArray();
                    for (String enumVal : param.getEnumValues()) {
                        enumArray.put(enumVal);
                    }
                    prop.put("enum", enumArray);
                }
                properties.put(param.getName(), prop);
                if (param.isRequired()) {
                    required.put(param.getName());
                }
            }
        }
        parameters.put("properties", properties);
        if (required.length() > 0) {
            parameters.put("required", required);
        }
        function.put("parameters", parameters);
        // 与 Responses 侧同口径（ResponsesPayloadBuilder.toFlatTools 也显式写 false）：
        // 本项目的工具 schema 刻意宽松（可选参数、自由 object、无 items 数组），开严格模式
        // 一条都过不了；显式 false 是为了不赌各网关对 strict 的默认值，而不是在此处加严。
        function.put("strict", false);

        tool.put("function", function);
        return tool;
    }
    
    public String toPromptFormat() {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append(": ").append(description).append("\n");
        if (whenToUse != null && !whenToUse.isEmpty()) {
            sb.append("适用场景: ").append(whenToUse).append("\n");
        }
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
        private String whenToUse = "";
        
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
        
        /** 工具调用体系 P0-1：一句话适用场景（when_to_use） */
        public Builder whenToUse(String whenToUse) {
            this.whenToUse = whenToUse == null ? "" : whenToUse;
            return this;
        }
        
        public ToolDefinition build() {
            return new ToolDefinition(this);
        }
    }
}