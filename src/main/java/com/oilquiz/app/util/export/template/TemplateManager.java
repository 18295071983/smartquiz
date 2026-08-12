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
    /** 模板结构版本：v2 起为场景化模板（scene_*），旧版按格式区分的默认模板不再创建 */
    private static final int SCHEMA_VERSION = 2;
    /** 场景化默认模板的 ID 前缀 */
    private static final String SCENE_PREFIX = "scene_";
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
                // 版本迁移：检测到旧版默认模板时，重建场景化默认模板（保留用户自定义模板）
                migrateTemplatesIfNeeded();
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
     * 版本迁移：确保场景化默认模板齐全。
     * - 若完全没有场景模板（旧版 schema），删除旧默认模板并重建全部场景模板；
     * - 若有场景模板但缺失部分（如升级后新增模板），只补齐缺失项，保留用户自定义模板。
     */
    private void migrateTemplatesIfNeeded() {
        boolean hasSceneTemplate = false;
        for (Template template : templates) {
            if (template.getId() != null && template.getId().startsWith(SCENE_PREFIX)) {
                hasSceneTemplate = true;
                break;
            }
        }
        if (!hasSceneTemplate) {
            Log.i(TAG, "Schema v" + SCHEMA_VERSION + " templates missing, rebuilding default scene templates");
            templates.removeIf(Template::isDefault);
            createDefaultTemplates();
            return;
        }
        // 按 id 补齐缺失的场景模板（老用户升级后自动获得新增模板，不删除任何用户数据）
        List<String> existingIds = new ArrayList<>();
        for (Template template : templates) {
            if (template.getId() != null) {
                existingIds.add(template.getId());
            }
        }
        boolean changed = false;
        if (!existingIds.contains("scene_standard")) { createSceneStandardTemplate(); changed = true; }
        if (!existingIds.contains("scene_practice")) { createScenePracticeTemplate(); changed = true; }
        if (!existingIds.contains("scene_answer")) { createSceneAnswerTemplate(); changed = true; }
        if (!existingIds.contains("scene_teaching")) { createSceneTeachingTemplate(); changed = true; }
        if (!existingIds.contains("scene_memory")) { createSceneMemoryTemplate(); changed = true; }
        if (!existingIds.contains("scene_data")) { createSceneDataTemplate(); changed = true; }
        if (!existingIds.contains("scene_mistake")) { createSceneMistakeTemplate(); changed = true; }
        if (!existingIds.contains("scene_exam")) { createSceneExamTemplate(); changed = true; }
        if (changed) {
            Log.i(TAG, "Missing scene templates created, saving");
            saveTemplates();
        }
    }

    /**
     * 创建默认模板（场景化）
     */
    private void createDefaultTemplates() {
        createSceneStandardTemplate();
        createScenePracticeTemplate();
        createSceneAnswerTemplate();
        createSceneTeachingTemplate();
        createSceneMemoryTemplate();
        createSceneDataTemplate();
        createSceneMistakeTemplate();
        createSceneExamTemplate();
        saveTemplates();
    }

    /**
     * 场景模板：标准完整版（全部核心字段，适合备份与跨端迁移）
     */
    private void createSceneStandardTemplate() {
        Template template = new Template();
        template.setId("scene_standard");
        template.setName("标准完整版");
        template.setDescription("包含题目、选项、答案、解析、知识点、分类、难度等全部核心字段，适合题库备份与跨端迁移");
        template.setFormat("EXCEL");
        template.setScene("standard");
        template.setVersion(2);
        template.setDefault(true);
        template.setAppliesTo(java.util.Arrays.asList("EXCEL", "CSV", "JSON", "HTML", "MARKDOWN", "WORD"));

        List<String> fields = new ArrayList<>();
        fields.add("id");
        fields.add("questionType");
        fields.add("questionText");
        fields.add("optionA");
        fields.add("optionB");
        fields.add("optionC");
        fields.add("optionD");
        fields.add("optionE");
        fields.add("optionF");
        fields.add("optionG");
        fields.add("optionH");
        fields.add("optionI");
        fields.add("optionJ");
        fields.add("optionK");
        fields.add("optionL");
        fields.add("correctAnswer");
        fields.add("answerText");
        fields.add("explanation");
        fields.add("analysis");
        fields.add("knowledgePoint");
        fields.add("category");
        fields.add("subCategory");
        fields.add("difficulty");
        fields.add("tags");
        fields.add("hint");
        fields.add("relatedQuestion");
        fields.add("source");
        template.setFields(fields);

        template.setFieldMappings(buildMappings());

        Map<String, Object> config = new HashMap<>();
        config.put("includeAnswers", true);
        config.put("includeExplanations", true);
        config.put("includeDifficulty", true);
        config.put("groupByCategory", false);
        template.setConfig(config);

        templates.add(template);
    }

    /**
     * 场景模板：刷题训练版（只含题干与选项，不含答案，适合打印练习/课堂测验）
     */
    private void createScenePracticeTemplate() {
        Template template = new Template();
        template.setId("scene_practice");
        template.setName("刷题训练版");
        template.setDescription("仅导出题干与选项，不含答案与解析，适合打印练习、课堂测验与自我检测");
        template.setFormat("EXCEL");
        template.setScene("practice");
        template.setVersion(2);
        template.setDefault(true);
        template.setAppliesTo(java.util.Arrays.asList("EXCEL", "CSV", "HTML", "MARKDOWN", "WORD", "PDF", "LONG_IMAGE"));

        List<String> fields = new ArrayList<>();
        fields.add("id");
        fields.add("questionType");
        fields.add("questionText");
        fields.add("optionA");
        fields.add("optionB");
        fields.add("optionC");
        fields.add("optionD");
        fields.add("optionE");
        fields.add("optionF");
        fields.add("optionG");
        fields.add("optionH");
        fields.add("optionI");
        fields.add("optionJ");
        fields.add("optionK");
        fields.add("optionL");
        template.setFields(fields);

        template.setFieldMappings(buildMappings());

        Map<String, Object> config = new HashMap<>();
        config.put("includeAnswers", false);
        config.put("includeExplanations", false);
        config.put("includeDifficulty", false);
        config.put("groupByCategory", false);
        template.setConfig(config);

        templates.add(template);
    }

    /**
     * 场景模板：答案解析版（题干+答案+解析，适合复习备考与错题整理）
     */
    private void createSceneAnswerTemplate() {
        Template template = new Template();
        template.setId("scene_answer");
        template.setName("答案解析版");
        template.setDescription("导出题干、答案、解析与知识点，适合复习备考、错题整理与学习笔记");
        template.setFormat("EXCEL");
        template.setScene("answer");
        template.setVersion(2);
        template.setDefault(true);
        template.setAppliesTo(java.util.Arrays.asList("EXCEL", "CSV", "HTML", "MARKDOWN", "WORD"));

        List<String> fields = new ArrayList<>();
        fields.add("id");
        fields.add("questionType");
        fields.add("questionText");
        fields.add("correctAnswer");
        fields.add("answerText");
        fields.add("explanation");
        fields.add("analysis");
        fields.add("knowledgePoint");
        template.setFields(fields);

        template.setFieldMappings(buildMappings());

        Map<String, Object> config = new HashMap<>();
        config.put("includeAnswers", true);
        config.put("includeExplanations", true);
        config.put("includeDifficulty", false);
        config.put("groupByCategory", false);
        template.setConfig(config);

        templates.add(template);
    }

    /**
     * 场景模板：讲义备课版（按分类分组，含难度与知识点，适合老师备课讲义）
     */
    private void createSceneTeachingTemplate() {
        Template template = new Template();
        template.setId("scene_teaching");
        template.setName("讲义备课版");
        template.setDescription("按分类分组导出，含难度、知识点、详细解析，适合老师备课、讲义与课堂材料");
        template.setFormat("EXCEL");
        template.setScene("teaching");
        template.setVersion(2);
        template.setDefault(true);
        template.setAppliesTo(java.util.Arrays.asList("EXCEL", "HTML", "WORD", "PDF"));

        List<String> fields = new ArrayList<>();
        fields.add("id");
        fields.add("questionType");
        fields.add("category");
        fields.add("subCategory");
        fields.add("difficulty");
        fields.add("knowledgePoint");
        fields.add("questionText");
        fields.add("optionA");
        fields.add("optionB");
        fields.add("optionC");
        fields.add("optionD");
        fields.add("optionE");
        fields.add("optionF");
        fields.add("optionG");
        fields.add("optionH");
        fields.add("optionI");
        fields.add("optionJ");
        fields.add("optionK");
        fields.add("optionL");
        fields.add("correctAnswer");
        fields.add("answerText");
        fields.add("explanation");
        fields.add("analysis");
        template.setFields(fields);

        template.setFieldMappings(buildMappings());

        Map<String, Object> config = new HashMap<>();
        config.put("includeAnswers", true);
        config.put("includeExplanations", true);
        config.put("includeDifficulty", true);
        config.put("groupByCategory", true);
        template.setConfig(config);

        templates.add(template);
    }

    /**
     * 场景模板：记忆卡片版（题干+答案精简，适合背诵与制卡）
     */
    private void createSceneMemoryTemplate() {
        Template template = new Template();
        template.setId("scene_memory");
        template.setName("记忆卡片版");
        template.setDescription("仅导出题干与答案两列核心信息，适合快速背诵、制作记忆卡片或导入 Anki");
        template.setFormat("CSV");
        template.setScene("memory");
        template.setVersion(2);
        template.setDefault(true);
        template.setAppliesTo(java.util.Arrays.asList("CSV", "EXCEL", "HTML", "MARKDOWN"));

        List<String> fields = new ArrayList<>();
        fields.add("questionText");
        fields.add("correctAnswer");
        fields.add("answerText");
        template.setFields(fields);

        template.setFieldMappings(buildMappings());

        Map<String, Object> config = new HashMap<>();
        config.put("includeAnswers", true);
        config.put("includeExplanations", false);
        config.put("includeDifficulty", false);
        config.put("groupByCategory", false);
        template.setConfig(config);

        templates.add(template);
    }

    /**
     * 场景模板：数据分析版（题型/分类/难度与答题统计，适合数据分析）
     */
    private void createSceneDataTemplate() {
        Template template = new Template();
        template.setId("scene_data");
        template.setName("数据分析版");
        template.setDescription("导出题型、分类、难度、知识点与答题统计（正确/错误次数），适合学习情况数据分析");
        template.setFormat("CSV");
        template.setScene("data");
        template.setVersion(2);
        template.setDefault(true);
        template.setAppliesTo(java.util.Arrays.asList("CSV", "EXCEL"));

        List<String> fields = new ArrayList<>();
        fields.add("id");
        fields.add("questionType");
        fields.add("category");
        fields.add("difficulty");
        fields.add("knowledgePoint");
        fields.add("favorite");
        fields.add("usageCount");
        fields.add("correctCount");
        fields.add("incorrectCount");
        template.setFields(fields);

        template.setFieldMappings(buildMappings());

        Map<String, Object> config = new HashMap<>();
        config.put("includeAnswers", false);
        config.put("includeExplanations", false);
        config.put("includeDifficulty", true);
        config.put("groupByCategory", false);
        template.setConfig(config);

        templates.add(template);
    }

    private void createSceneMistakeTemplate() {
        Template template = new Template();
        template.setId("scene_mistake");
        template.setName("错题回顾版");
        template.setDescription("导出错题及解析，包含错误次数与正确答案，适合错题复习");
        template.setFormat("EXCEL");
        template.setScene("mistake");
        template.setVersion(1);
        template.setDefault(true);
        template.setAppliesTo(java.util.Arrays.asList("EXCEL", "CSV"));

        List<String> fields = new ArrayList<>();
        fields.add("id");
        fields.add("questionText");
        fields.add("questionType");
        fields.add("category");
        fields.add("difficulty");
        fields.add("knowledgePoint");
        fields.add("answerText");
        fields.add("correctAnswer");
        fields.add("explanation");
        fields.add("incorrectCount");
        template.setFields(fields);

        template.setFieldMappings(buildMappings());

        Map<String, Object> config = new HashMap<>();
        config.put("includeAnswers", true);
        config.put("includeExplanations", true);
        config.put("includeDifficulty", true);
        config.put("onlyIncorrect", true);
        template.setConfig(config);

        templates.add(template);
    }

    private void createSceneExamTemplate() {
        Template template = new Template();
        template.setId("scene_exam");
        template.setName("模拟考试版");
        template.setDescription("按题型、难度、分类组卷导出，适合模拟考试练习");
        template.setFormat("EXCEL");
        template.setScene("exam");
        template.setVersion(1);
        template.setDefault(true);
        template.setAppliesTo(java.util.Arrays.asList("EXCEL", "PDF"));

        List<String> fields = new ArrayList<>();
        fields.add("id");
        fields.add("questionText");
        fields.add("questionType");
        fields.add("optionA");
        fields.add("optionB");
        fields.add("optionC");
        fields.add("optionD");
        fields.add("correctAnswer");
        fields.add("category");
        fields.add("difficulty");
        fields.add("knowledgePoint");
        template.setFields(fields);

        template.setFieldMappings(buildMappings());

        Map<String, Object> config = new HashMap<>();
        config.put("includeAnswers", true);
        config.put("includeExplanations", false);
        config.put("includeDifficulty", true);
        config.put("groupByCategory", true);
        config.put("sortByDifficulty", true);
        template.setConfig(config);

        templates.add(template);
    }

    /**
     * 构建字段中文名映射（与 ExportUtils.getFieldDisplayName 保持一致）
     */
    private Map<String, String> buildMappings() {
        Map<String, String> mappings = new HashMap<>();
        mappings.put("id", "序号");
        mappings.put("questionText", "题目内容");
        mappings.put("optionA", "选项A");
        mappings.put("optionB", "选项B");
        mappings.put("optionC", "选项C");
        mappings.put("optionD", "选项D");
        mappings.put("optionE", "选项E");
        mappings.put("optionF", "选项F");
        mappings.put("optionG", "选项G");
        mappings.put("optionH", "选项H");
        mappings.put("optionI", "选项I");
        mappings.put("optionJ", "选项J");
        mappings.put("optionK", "选项K");
        mappings.put("optionL", "选项L");
        mappings.put("correctAnswer", "正确答案");
        mappings.put("answerText", "答案文本");
        mappings.put("explanation", "解析");
        mappings.put("analysis", "详细解析");
        mappings.put("knowledgePoint", "知识点");
        mappings.put("category", "分类");
        mappings.put("subCategory", "子分类");
        mappings.put("difficulty", "难度");
        mappings.put("tags", "标签");
        mappings.put("hint", "提示");
        mappings.put("relatedQuestion", "相关题目");
        mappings.put("source", "来源");
        mappings.put("favorite", "收藏");
        mappings.put("usageCount", "使用次数");
        mappings.put("correctCount", "答对次数");
        mappings.put("incorrectCount", "答错次数");
        mappings.put("questionType", "题型");
        mappings.put("points", "分值");
        mappings.put("timeLimit", "时限(秒)");
        mappings.put("status", "状态");
        mappings.put("isPublic", "是否公开");
        mappings.put("author", "作者");
        mappings.put("comment", "备注");
        mappings.put("createdAt", "创建时间");
        mappings.put("updatedAt", "更新时间");
        mappings.put("imageUri", "配图路径");
        mappings.put("audioUri", "音频路径");
        mappings.put("parentId", "母题ID");
        mappings.put("sortOrder", "排序");
        return mappings;
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
     * 根据格式获取模板（支持场景化模板的 appliesTo 匹配）
     */
    public List<Template> getTemplatesByFormat(String format) {
        List<Template> result = new ArrayList<>();
        for (Template template : templates) {
            if (template.supportsFormat(format)) {
                result.add(template);
            }
        }
        return result;
    }

    /**
     * 获取全部默认模板（场景模板，按创建顺序）
     */
    public List<Template> getDefaultTemplates() {
        List<Template> result = new ArrayList<>();
        for (Template template : templates) {
            if (template.isDefault()) {
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
            if (template.supportsFormat(format) && template.isDefault()) {
                return template;
            }
        }
        // 如果没有默认模板，返回第一个支持该格式的模板
        for (Template template : templates) {
            if (template.supportsFormat(format)) {
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
