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

    // ========== v2 场景化模板字段 ==========
    /** 场景标签：standard(标准)/practice(刷题)/answer(答案解析)/teaching(讲义)/memory(记忆卡片)/data(数据分析) */
    private String scene;
    /** 适用格式列表（EXCEL/CSV/HTML/MARKDOWN/JSON/WORD/PDF/LONG_IMAGE），为空时用 format 字段匹配 */
    private List<String> appliesTo;
    /** 模板结构版本（用于默认模板迁移） */
    private int version = 1;

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

    public String getScene() {
        return scene;
    }

    public void setScene(String scene) {
        this.scene = scene;
    }

    public List<String> getAppliesTo() {
        return appliesTo;
    }

    public void setAppliesTo(List<String> appliesTo) {
        this.appliesTo = appliesTo;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    /**
     * 判断模板是否支持指定格式
     */
    public boolean supportsFormat(String format) {
        if (format == null) return false;
        if (appliesTo != null && !appliesTo.isEmpty()) {
            return appliesTo.contains(format);
        }
        return format.equals(this.format);
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
