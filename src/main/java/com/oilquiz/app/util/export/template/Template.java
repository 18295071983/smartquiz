package com.oilquiz.app.util.export.template;

import java.util.List;
import java.util.Map;

/**
 * 模板基类
 * 定义模板的基本信息和配置
 */
public class Template {
    private String id;
    private String name;
    private String description;
    private String format;
    private Map<String, Object> config;
    private List<String> fields;
    private Map<String, String> fieldMappings;
    private boolean isDefault;

    public Template() {
    }

    public Template(String id, String name, String description, String format) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.format = format;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public Map<String, Object> getConfig() {
        return config;
    }

    public void setConfig(Map<String, Object> config) {
        this.config = config;
    }

    public List<String> getFields() {
        return fields;
    }

    public void setFields(List<String> fields) {
        this.fields = fields;
    }

    public Map<String, String> getFieldMappings() {
        return fieldMappings;
    }

    public void setFieldMappings(Map<String, String> fieldMappings) {
        this.fieldMappings = fieldMappings;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean aDefault) {
        isDefault = aDefault;
    }

    @Override
    public String toString() {
        return "Template{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", format='" + format + '\'' +
                '}';
    }
}
