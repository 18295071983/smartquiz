package com.oilquiz.app.util.export.template;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模板管理器
 * 负责模板的加载、保存、管理
 */
public class TemplateManager {
    private static final String TAG = "TemplateManager";
    private static final String TEMPLATES_DIR = "templates";
    private static final String TEMPLATES_FILE = "templates.json";
    private static TemplateManager instance;
    private Context context;
    private List<Template> templates;
    private Map<String, Template> templateMap;

    private TemplateManager() {
        templates = new ArrayList<>();
        templateMap = new HashMap<>();
    }

    public static synchronized TemplateManager getInstance() {
        if (instance == null) {
            instance = new TemplateManager();
        }
        return instance;
    }

    public void init(Context context) {
        this.context = context;
        Log.d(TAG, "Initializing TemplateManager");
        loadTemplates();
        Log.d(TAG, "Templates loaded, count: " + templates.size());
        if (templates.isEmpty()) {
            Log.d(TAG, "Creating default templates");
            createDefaultTemplates();
            Log.d(TAG, "Default templates created, count: " + templates.size());
        }
    }

    /**
     * 加载模板
     */
    private void loadTemplates() {
        try {
            File templatesFile = getTemplatesFile();
            Log.d(TAG, "Loading templates from: " + templatesFile.getAbsolutePath());
            Log.d(TAG, "Templates file exists: " + templatesFile.exists());
            if (templatesFile.exists()) {
                FileReader reader = new FileReader(templatesFile);
                Type type = new TypeToken<List<Template>>() {}.getType();
                templates = new Gson().fromJson(reader, type);
                reader.close();
                updateTemplateMap();
                Log.d(TAG, "Loaded " + templates.size() + " templates");
            } else {
                Log.d(TAG, "Templates file does not exist, will create default templates");
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to load templates: " + e.getMessage());
            templates = new ArrayList<>();
        }
    }

    /**
     * 保存模板
     */
    public void saveTemplates() {
        try {
            File templatesFile = getTemplatesFile();
            FileWriter writer = new FileWriter(templatesFile);
            new Gson().toJson(templates, writer);
            writer.close();
            Log.d(TAG, "Saved " + templates.size() + " templates");
        } catch (IOException e) {
            Log.e(TAG, "Failed to save templates: " + e.getMessage());
        }
    }

    /**
     * 创建默认模板
     */
    private void createDefaultTemplates() {
        // 创建默认的HTML模板
        createDefaultHTMLTemplate();
        // 创建默认的增强HTML模板
        createDefaultEnhancedHTMLTemplate();
        // 创建默认的Word模板
        createDefaultWordTemplate();
        // 创建默认的Excel模板
        createDefaultExcelTemplate();
        // 创建默认的PDF模板
        createDefaultPDFTemplate();
        // 创建默认的CSV模板
        createDefaultCSVTemplate();
        // 创建默认的Markdown模板
        createDefaultMarkdownTemplate();
        // 创建默认的JSON模板
        createDefaultJSONTemplate();
        // 创建默认的长图片模板
        createDefaultLongImageTemplate();
        saveTemplates();
    }

    private void createDefaultHTMLTemplate() {
        Template template = new Template();
        template.setId("html_default");
        template.setName("默认HTML模板");
        template.setDescription("标准的HTML导出模板");
        template.setFormat("HTML");
        template.setDefault(true);
        
        List<String> fields = new ArrayList<>();
        fields.add("questionText");
        fields.add("optionA");
        fields.add("optionB");
        fields.add("optionC");
        fields.add("optionD");
        fields.add("correctAnswer");
        fields.add("explanation");
        fields.add("questionType");
        fields.add("difficulty");
        fields.add("category");
        template.setFields(fields);
        
        Map<String, String> mappings = new HashMap<>();
        mappings.put("questionText", "题目");
        mappings.put("optionA", "选项A");
        mappings.put("optionB", "选项B");
        mappings.put("optionC", "选项C");
        mappings.put("optionD", "选项D");
        mappings.put("correctAnswer", "正确答案");
        mappings.put("explanation", "解析");
        mappings.put("questionType", "题型");
        mappings.put("difficulty", "难度");
        mappings.put("category", "分类");
        template.setFieldMappings(mappings);
        
        Map<String, Object> config = new HashMap<>();
        config.put("includeAnswers", true);
        config.put("includeExplanations", true);
        config.put("groupByCategory", false);
        config.put("includeCategories", true);
        template.setConfig(config);
        
        templates.add(template);
    }

    private void createDefaultWordTemplate() {
        Template template = new Template();
        template.setId("word_default");
        template.setName("默认Word模板");
        template.setDescription("标准的Word导出模板");
        template.setFormat("WORD");
        template.setDefault(true);
        
        List<String> fields = new ArrayList<>();
        fields.add("questionText");
        fields.add("optionA");
        fields.add("optionB");
        fields.add("optionC");
        fields.add("optionD");
        fields.add("correctAnswer");
        fields.add("explanation");
        template.setFields(fields);
        
        Map<String, String> mappings = new HashMap<>();
        mappings.put("questionText", "题目");
        mappings.put("optionA", "选项A");
        mappings.put("optionB", "选项B");
        mappings.put("optionC", "选项C");
        mappings.put("optionD", "选项D");
        mappings.put("correctAnswer", "正确答案");
        mappings.put("explanation", "解析");
        template.setFieldMappings(mappings);
        
        Map<String, Object> config = new HashMap<>();
        config.put("includeAnswers", true);
        config.put("includeExplanations", true);
        config.put("documentTitle", "题库导出");
        config.put("documentAuthor", "OilQuiz");
        template.setConfig(config);
        
        templates.add(template);
    }

    private void createDefaultExcelTemplate() {
        Template template = new Template();
        template.setId("excel_default");
        template.setName("默认Excel模板");
        template.setDescription("标准的Excel导出模板");
        template.setFormat("EXCEL");
        template.setDefault(true);
        
        List<String> fields = new ArrayList<>();
        fields.add("questionText");
        fields.add("optionA");
        fields.add("optionB");
        fields.add("optionC");
        fields.add("optionD");
        fields.add("correctAnswer");
        fields.add("explanation");
        fields.add("questionType");
        fields.add("difficulty");
        fields.add("category");
        template.setFields(fields);
        
        Map<String, String> mappings = new HashMap<>();
        mappings.put("questionText", "题目");
        mappings.put("optionA", "选项A");
        mappings.put("optionB", "选项B");
        mappings.put("optionC", "选项C");
        mappings.put("optionD", "选项D");
        mappings.put("correctAnswer", "正确答案");
        mappings.put("explanation", "解析");
        mappings.put("questionType", "题型");
        mappings.put("difficulty", "难度");
        mappings.put("category", "分类");
        template.setFieldMappings(mappings);
        
        Map<String, Object> config = new HashMap<>();
        config.put("includeAnswers", true);
        config.put("includeExplanations", true);
        config.put("autoSizeColumns", true);
        template.setConfig(config);
        
        templates.add(template);
    }

    private void createDefaultPDFTemplate() {
        Template template = new Template();
        template.setId("pdf_default");
        template.setName("默认PDF模板");
        template.setDescription("标准的PDF导出模板");
        template.setFormat("PDF");
        template.setDefault(true);
        
        List<String> fields = new ArrayList<>();
        fields.add("questionText");
        fields.add("optionA");
        fields.add("optionB");
        fields.add("optionC");
        fields.add("optionD");
        fields.add("correctAnswer");
        fields.add("explanation");
        template.setFields(fields);
        
        Map<String, String> mappings = new HashMap<>();
        mappings.put("questionText", "题目");
        mappings.put("optionA", "选项A");
        mappings.put("optionB", "选项B");
        mappings.put("optionC", "选项C");
        mappings.put("optionD", "选项D");
        mappings.put("correctAnswer", "正确答案");
        mappings.put("explanation", "解析");
        template.setFieldMappings(mappings);
        
        Map<String, Object> config = new HashMap<>();
        config.put("includeAnswers", true);
        config.put("includeExplanations", true);
        config.put("pageSize", "A4");
        template.setConfig(config);
        
        templates.add(template);
    }

    private void createDefaultEnhancedHTMLTemplate() {
        Template template = new Template();
        template.setId("enhanced_html_default");
        template.setName("默认增强HTML模板");
        template.setDescription("增强版HTML导出模板，包含更多样式和交互功能");
        template.setFormat("ENHANCED_HTML");
        template.setDefault(true);
        
        List<String> fields = new ArrayList<>();
        fields.add("questionText");
        fields.add("optionA");
        fields.add("optionB");
        fields.add("optionC");
        fields.add("optionD");
        fields.add("correctAnswer");
        fields.add("explanation");
        fields.add("questionType");
        fields.add("difficulty");
        fields.add("category");
        template.setFields(fields);
        
        Map<String, String> mappings = new HashMap<>();
        mappings.put("questionText", "题目");
        mappings.put("optionA", "选项A");
        mappings.put("optionB", "选项B");
        mappings.put("optionC", "选项C");
        mappings.put("optionD", "选项D");
        mappings.put("correctAnswer", "正确答案");
        mappings.put("explanation", "解析");
        mappings.put("questionType", "题型");
        mappings.put("difficulty", "难度");
        mappings.put("category", "分类");
        template.setFieldMappings(mappings);
        
        Map<String, Object> config = new HashMap<>();
        config.put("includeAnswers", true);
        config.put("includeExplanations", true);
        config.put("groupByCategory", true);
        config.put("includeCategories", true);
        config.put("enableSearch", true);
        config.put("enableFilter", true);
        template.setConfig(config);
        
        templates.add(template);
    }

    private void createDefaultCSVTemplate() {
        Template template = new Template();
        template.setId("csv_default");
        template.setName("默认CSV模板");
        template.setDescription("标准的CSV导出模板，适合数据分析");
        template.setFormat("CSV");
        template.setDefault(true);
        
        List<String> fields = new ArrayList<>();
        fields.add("questionText");
        fields.add("optionA");
        fields.add("optionB");
        fields.add("optionC");
        fields.add("optionD");
        fields.add("correctAnswer");
        fields.add("explanation");
        fields.add("questionType");
        fields.add("difficulty");
        fields.add("category");
        template.setFields(fields);
        
        Map<String, String> mappings = new HashMap<>();
        mappings.put("questionText", "题目");
        mappings.put("optionA", "选项A");
        mappings.put("optionB", "选项B");
        mappings.put("optionC", "选项C");
        mappings.put("optionD", "选项D");
        mappings.put("correctAnswer", "正确答案");
        mappings.put("explanation", "解析");
        mappings.put("questionType", "题型");
        mappings.put("difficulty", "难度");
        mappings.put("category", "分类");
        template.setFieldMappings(mappings);
        
        Map<String, Object> config = new HashMap<>();
        config.put("includeAnswers", true);
        config.put("includeExplanations", true);
        config.put("delimiter", ",");
        config.put("quoteCharacter", "\"");
        template.setConfig(config);
        
        templates.add(template);
    }

    private void createDefaultMarkdownTemplate() {
        Template template = new Template();
        template.setId("markdown_default");
        template.setName("默认Markdown模板");
        template.setDescription("标准的Markdown导出模板，适合文档编写");
        template.setFormat("MARKDOWN");
        template.setDefault(true);
        
        List<String> fields = new ArrayList<>();
        fields.add("questionText");
        fields.add("optionA");
        fields.add("optionB");
        fields.add("optionC");
        fields.add("optionD");
        fields.add("correctAnswer");
        fields.add("explanation");
        fields.add("questionType");
        fields.add("difficulty");
        fields.add("category");
        template.setFields(fields);
        
        Map<String, String> mappings = new HashMap<>();
        mappings.put("questionText", "题目");
        mappings.put("optionA", "选项A");
        mappings.put("optionB", "选项B");
        mappings.put("optionC", "选项C");
        mappings.put("optionD", "选项D");
        mappings.put("correctAnswer", "正确答案");
        mappings.put("explanation", "解析");
        mappings.put("questionType", "题型");
        mappings.put("difficulty", "难度");
        mappings.put("category", "分类");
        template.setFieldMappings(mappings);
        
        Map<String, Object> config = new HashMap<>();
        config.put("includeAnswers", true);
        config.put("includeExplanations", true);
        config.put("useHeadingLevels", true);
        config.put("includeTableOfContents", true);
        template.setConfig(config);
        
        templates.add(template);
    }

    private void createDefaultJSONTemplate() {
        Template template = new Template();
        template.setId("json_default");
        template.setName("默认JSON模板");
        template.setDescription("标准的JSON导出模板，适合程序处理");
        template.setFormat("JSON");
        template.setDefault(true);
        
        List<String> fields = new ArrayList<>();
        fields.add("questionText");
        fields.add("optionA");
        fields.add("optionB");
        fields.add("optionC");
        fields.add("optionD");
        fields.add("correctAnswer");
        fields.add("explanation");
        fields.add("questionType");
        fields.add("difficulty");
        fields.add("category");
        template.setFields(fields);
        
        Map<String, String> mappings = new HashMap<>();
        mappings.put("questionText", "题目");
        mappings.put("optionA", "选项A");
        mappings.put("optionB", "选项B");
        mappings.put("optionC", "选项C");
        mappings.put("optionD", "选项D");
        mappings.put("correctAnswer", "正确答案");
        mappings.put("explanation", "解析");
        mappings.put("questionType", "题型");
        mappings.put("difficulty", "难度");
        mappings.put("category", "分类");
        template.setFieldMappings(mappings);
        
        Map<String, Object> config = new HashMap<>();
        config.put("includeAnswers", true);
        config.put("includeExplanations", true);
        config.put("prettyPrint", true);
        config.put("indentSize", 2);
        template.setConfig(config);
        
        templates.add(template);
    }

    private void createDefaultLongImageTemplate() {
        Template template = new Template();
        template.setId("long_image_default");
        template.setName("默认长图片模板");
        template.setDescription("标准的长图片导出模板，适合分享");
        template.setFormat("LONG_IMAGE");
        template.setDefault(true);
        
        List<String> fields = new ArrayList<>();
        fields.add("questionText");
        fields.add("optionA");
        fields.add("optionB");
        fields.add("optionC");
        fields.add("optionD");
        fields.add("correctAnswer");
        fields.add("explanation");
        template.setFields(fields);
        
        Map<String, String> mappings = new HashMap<>();
        mappings.put("questionText", "题目");
        mappings.put("optionA", "选项A");
        mappings.put("optionB", "选项B");
        mappings.put("optionC", "选项C");
        mappings.put("optionD", "选项D");
        mappings.put("correctAnswer", "正确答案");
        mappings.put("explanation", "解析");
        template.setFieldMappings(mappings);
        
        Map<String, Object> config = new HashMap<>();
        config.put("includeAnswers", true);
        config.put("includeExplanations", true);
        config.put("imageWidth", 3840);
        config.put("textSize", 32);
        config.put("backgroundColor", "#FFFFFF");
        config.put("padding", 40);
        template.setConfig(config);
        
        templates.add(template);
    }

    /**
     * 重置模板数据（用于测试）
     */
    public void resetTemplates() {
        templates.clear();
        templateMap.clear();
        createDefaultTemplates();
    }

    /**
     * 获取所有模板
     */
    public List<Template> getAllTemplates() {
        return new ArrayList<>(templates);
    }

    /**
     * 根据格式获取模板
     */
    public List<Template> getTemplatesByFormat(String format) {
        List<Template> result = new ArrayList<>();
        for (Template template : templates) {
            if (template.getFormat().equals(format)) {
                result.add(template);
            }
        }
        return result;
    }

    /**
     * 根据ID获取模板
     */
    public Template getTemplateById(String id) {
        return templateMap.get(id);
    }

    /**
     * 获取默认模板
     */
    public Template getDefaultTemplate(String format) {
        for (Template template : templates) {
            if (template.getFormat().equals(format) && template.isDefault()) {
                return template;
            }
        }
        // 如果没有默认模板，返回第一个该格式的模板
        for (Template template : templates) {
            if (template.getFormat().equals(format)) {
                return template;
            }
        }
        return null;
    }

    /**
     * 添加模板
     */
    public void addTemplate(Template template) {
        templates.add(template);
        templateMap.put(template.getId(), template);
        saveTemplates();
    }

    /**
     * 更新模板
     */
    public void updateTemplate(Template template) {
        for (int i = 0; i < templates.size(); i++) {
            if (templates.get(i).getId().equals(template.getId())) {
                templates.set(i, template);
                templateMap.put(template.getId(), template);
                saveTemplates();
                return;
            }
        }
    }

    /**
     * 删除模板
     */
    public void deleteTemplate(String id) {
        templates.removeIf(template -> template.getId().equals(id));
        templateMap.remove(id);
        saveTemplates();
    }

    /**
     * 复制模板
     */
    public Template copyTemplate(String id, String newName) {
        Template original = getTemplateById(id);
        if (original == null) {
            return null;
        }
        
        Template copy = new Template();
        copy.setId(original.getId() + "_copy_" + System.currentTimeMillis());
        copy.setName(newName);
        copy.setDescription(original.getDescription());
        copy.setFormat(original.getFormat());
        copy.setConfig(new HashMap<>(original.getConfig()));
        copy.setFields(new ArrayList<>(original.getFields()));
        copy.setFieldMappings(new HashMap<>(original.getFieldMappings()));
        copy.setDefault(false);
        
        addTemplate(copy);
        return copy;
    }

    /**
     * 更新模板映射
     */
    private void updateTemplateMap() {
        templateMap.clear();
        for (Template template : templates) {
            templateMap.put(template.getId(), template);
        }
    }

    /**
     * 获取模板文件
     */
    private File getTemplatesFile() {
        File appDir = new File(context.getFilesDir(), TEMPLATES_DIR);
        if (!appDir.exists()) {
            appDir.mkdirs();
        }
        return new File(appDir, TEMPLATES_FILE);
    }

    /**
     * 刷新模板列表
     */
    public void refresh() {
        loadTemplates();
    }
}
