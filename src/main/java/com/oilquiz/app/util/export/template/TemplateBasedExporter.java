package com.oilquiz.app.util.export.template;

import com.oilquiz.app.model.Question;
import com.oilquiz.app.util.export.ExportManager;
import com.oilquiz.app.util.export.Exporter;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * 基于模板的导出器基类
 * 提供模板支持的通用功能
 */
public abstract class TemplateBasedExporter implements Exporter {
    protected Template template;

    public TemplateBasedExporter(Template template) {
        this.template = template;
    }

    @Override
    public File export(ExportManager.ExportTask task) throws Exception {
        validateParameters(task);
        
        // 获取模板配置
        Map<String, Object> templateConfig = template != null ? template.getConfig() : null;
        
        // 应用模板配置到导出任务
        applyTemplateConfig(task, templateConfig);
        
        // 执行具体的导出逻辑
        return doExport(task);
    }

    @Override
    public void validateParameters(ExportManager.ExportTask task) throws IllegalArgumentException {
        if (task == null) {
            throw new IllegalArgumentException("Export task cannot be null");
        }
        if (task.getQuestions() == null) {
            throw new IllegalArgumentException("Questions cannot be null");
        }
        if (task.getContext() == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
    }

    /**
     * 应用模板配置到导出任务
     */
    protected void applyTemplateConfig(ExportManager.ExportTask task, Map<String, Object> templateConfig) {
        if (templateConfig != null) {
            // 应用模板配置到任务配置
            ExportManager.ExportConfig config = task.getConfig();
            
            // 应用基本配置
            if (templateConfig.containsKey("includeAnswers")) {
                config.setIncludeAnswers((Boolean) templateConfig.get("includeAnswers"));
            }
            if (templateConfig.containsKey("includeExplanations")) {
                config.setIncludeExplanations((Boolean) templateConfig.get("includeExplanations"));
            }
            if (templateConfig.containsKey("groupByCategory")) {
                config.setGroupByCategory((Boolean) templateConfig.get("groupByCategory"));
            }
            if (templateConfig.containsKey("includeCategories")) {
                config.setIncludeCategories((Boolean) templateConfig.get("includeCategories"));
            }
            if (templateConfig.containsKey("includeDifficulty")) {
                config.setIncludeDifficulty((Boolean) templateConfig.get("includeDifficulty"));
            }
            if (templateConfig.containsKey("autoSizeColumns")) {
                config.setAutoSizeColumns((Boolean) templateConfig.get("autoSizeColumns"));
            }
            if (templateConfig.containsKey("splitByCategory")) {
                config.setSplitByCategory((Boolean) templateConfig.get("splitByCategory"));
            }
            if (templateConfig.containsKey("documentTitle")) {
                config.setDocumentTitle((String) templateConfig.get("documentTitle"));
            }
            if (templateConfig.containsKey("documentAuthor")) {
                config.setDocumentAuthor((String) templateConfig.get("documentAuthor"));
            }
            if (templateConfig.containsKey("documentSubject")) {
                config.setDocumentSubject((String) templateConfig.get("documentSubject"));
            }
            
            // 应用字段配置
            if (templateConfig.containsKey("selectedFields")) {
                config.setSelectedFields((List<String>) templateConfig.get("selectedFields"));
            } else if (template != null && template.getFields() != null) {
                config.setSelectedFields(template.getFields());
            }
        }
    }

    /**
     * 执行具体的导出逻辑
     */
    protected abstract File doExport(ExportManager.ExportTask task) throws Exception;

    @Override
    public String getFormatName() {
        return template != null ? template.getName() : getFileExtension().toUpperCase();
    }

    /**
     * 获取模板
     */
    public Template getTemplate() {
        return template;
    }

    /**
     * 设置模板
     */
    public void setTemplate(Template template) {
        this.template = template;
    }
}
