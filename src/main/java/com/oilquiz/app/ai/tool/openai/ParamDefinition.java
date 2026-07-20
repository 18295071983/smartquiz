package com.oilquiz.app.ai.tool.openai;

import java.util.List;

public class ParamDefinition {
    
    private final String name;
    private final String type;
    private final String description;
    private final boolean required;
    private final Object defaultValue;
    private final List<String> enumValues;
    
    public ParamDefinition(String name, String type, String description, boolean required) {
        this(name, type, description, required, null, null);
    }
    
    public ParamDefinition(String name, String type, String description, boolean required, Object defaultValue) {
        this(name, type, description, required, defaultValue, null);
    }
    
    public ParamDefinition(String name, String type, String description, boolean required, Object defaultValue, List<String> enumValues) {
        this.name = name;
        this.type = type;
        this.description = description;
        this.required = required;
        this.defaultValue = defaultValue;
        this.enumValues = enumValues;
    }
    
    public String getName() {
        return name;
    }
    
    public String getType() {
        return type;
    }
    
    public String getDescription() {
        return description;
    }
    
    public boolean isRequired() {
        return required;
    }
    
    public Object getDefaultValue() {
        return defaultValue;
    }
    
    public List<String> getEnumValues() {
        return enumValues;
    }
}