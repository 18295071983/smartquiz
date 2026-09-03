package com.oilquiz.app.ui.activity;

import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;

import com.oilquiz.app.R;
import com.oilquiz.app.manager.ConfigManager;
import com.oilquiz.app.model.Question;
import com.oilquiz.app.resource.AppResourceManager;
import com.oilquiz.app.resource.SystemUIResourceAdapter;
import com.oilquiz.app.util.export.ExportFileSaver;
import com.oilquiz.app.util.export.ExportManager;
import com.oilquiz.app.util.export.ExportUtils;
import com.oilquiz.app.util.export.template.Template;
import com.oilquiz.app.util.export.template.TemplateManager;
import com.oilquiz.app.viewmodel.QuestionViewModel;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 导出功能主页面
 * 支持：场景模板选择（标准/刷题/答案解析/讲义/记忆卡片/数据分析）+ 格式选择 + 范围过滤 + 字段自定义，
 * 导出文件保存到公共「下载/OilQuiz」目录（文件管理器可见可编辑）。
 */
public class ExportActivity extends AppCompatActivity {
    private static final String TAG = "ExportActivity";
    private static final String LOG_PREFIX = "ExportActivity";

    private QuestionViewModel questionViewModel;

    private MaterialButton btnSelectFields;
    private MaterialButton btnStartExport;
    private MaterialButton btnSelectTemplate;
    private CheckBox cbIncludeAnswers;
    private CheckBox cbIncludeExplanations;
    private CheckBox cbIncludeDifficulty;
    private RadioGroup rgExportScope;
    private TextView tvSelectedFields;
    private TextView tvQuestionCount;
    private TextView tvTemplateDesc;
    private LinearLayout llTemplateScenes;
    private LinearLayout llFormatOptions;

    private List<String> selectedFields;
    private Template selectedTemplate;
    private ExportManager.ExportFormat selectedFormat;
    private List<Template> sceneTemplates = new ArrayList<>();
    private List<Map<String, String>> exportFormats = new ArrayList<>();
    private MaterialButton currentFormatChip;
    private MaterialButton currentSceneChip;
    /** 模板要求仅导出答错过的题目（错题本版） */
    private boolean onlyIncorrect;
    /** 模板要求按难度/分类分组（试卷版/讲义版） */
    private boolean sortByDifficulty;

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        Log.i(LOG_PREFIX, "onCreate called");
        super.onCreate(savedInstanceState);

        // 应用系统UI主题
        SystemUIResourceAdapter uiAdapter = SystemUIResourceAdapter.getInstance(this);
        uiAdapter.applySystemTheme(this);

        setContentView(R.layout.activity_export);
        Log.i(LOG_PREFIX, "Layout inflated");

        questionViewModel = new ViewModelProvider(this).get(QuestionViewModel.class);
        Log.i(LOG_PREFIX, "QuestionViewModel initialized");

        // 初始化UI组件
        btnSelectFields = findViewById(R.id.btn_select_fields);
        btnStartExport = findViewById(R.id.btn_start_export);
        btnSelectTemplate = findViewById(R.id.btn_select_template);
        cbIncludeAnswers = findViewById(R.id.cb_include_answers);
        cbIncludeExplanations = findViewById(R.id.cb_include_explanations);
        cbIncludeDifficulty = findViewById(R.id.cb_include_difficulty);
        rgExportScope = findViewById(R.id.rg_export_scope);
        tvSelectedFields = findViewById(R.id.tv_selected_fields);
        tvQuestionCount = findViewById(R.id.textQuestionCount);
        tvTemplateDesc = findViewById(R.id.tv_template_desc);
        llTemplateScenes = findViewById(R.id.ll_template_scenes);
        llFormatOptions = findViewById(R.id.ll_format_options);
        Log.i(LOG_PREFIX, "UI components initialized");

        // 初始化模板管理器与场景模板
        TemplateManager.getInstance().init(this);
        sceneTemplates = TemplateManager.getInstance().getDefaultTemplates();
        Log.i(LOG_PREFIX, "Scene templates loaded: " + sceneTemplates.size() + " templates");
        initSceneTemplateChips();

        // 初始化导出格式 chips
        initFormatChips();
        Log.i(LOG_PREFIX, "Format chips initialized: " + exportFormats.size() + " formats");

        // 默认选中第一个场景模板
        if (!sceneTemplates.isEmpty()) {
            selectSceneTemplate(sceneTemplates.get(0), null);
        } else {
            // 无模板时默认全字段
            selectedFields = ExportUtils.getQuestionFields();
            updateSelectedFieldsText();
        }

        // 加载题目数量
        loadQuestionCount();

        // 设置选择字段按钮点击事件
        btnSelectFields.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showFieldSelectionDialog();
            }
        });

        // 设置模板管理按钮点击事件：弹模板选择对话框（与页面模板 chips 同一来源），
        // 选中后直接应用并与自定义字段/开关联动，不再进入独立的模板三级流水线
        btnSelectTemplate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTemplateSelectDialog();
            }
        });

        // 设置开始导出按钮点击事件
        btnStartExport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startExport();
            }
        });
    }

    /**
     * 模板管理：弹出全部场景模板选择对话框（与页面模板 chips 同一来源）。
     * 选中后应用模板字段/开关并高亮对应 chip，与自定义字段在同一页面联动。
     */
    private void showTemplateSelectDialog() {
        if (sceneTemplates == null || sceneTemplates.isEmpty()) {
            Toast.makeText(this, "暂无可用模板", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[sceneTemplates.size()];
        for (int i = 0; i < sceneTemplates.size(); i++) {
            Template t = sceneTemplates.get(i);
            String desc = t.getDescription();
            names[i] = t.getName() + (desc != null && !desc.isEmpty() ? "：" + desc : "");
        }
        new AlertDialog.Builder(this)
                .setTitle("选择导出模板")
                .setItems(names, (dialog, which) -> {
                    Template t = sceneTemplates.get(which);
                    MaterialButton chip = findSceneChip(t);
                    selectSceneTemplate(t, chip);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 动态生成场景模板 chips
     */
    private void initSceneTemplateChips() {
        Log.i(LOG_PREFIX, "initSceneTemplateChips called");
        llTemplateScenes.removeAllViews();
        for (int i = 0; i < sceneTemplates.size(); i++) {
            Template template = sceneTemplates.get(i);
            Log.i(LOG_PREFIX, "Adding scene chip: " + template.getName() + " (id: " + template.getId() + ")");
            final int index = i;
            MaterialButton chip = createChip(template.getName());
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Log.i(LOG_PREFIX, "Scene chip clicked: " + sceneTemplates.get(index).getName());
                    selectSceneTemplate(sceneTemplates.get(index), chip);
                }
            });
            llTemplateScenes.addView(chip);
        }
        Log.i(LOG_PREFIX, "Scene chips added: " + llTemplateScenes.getChildCount());
    }

    /**
     * 选中场景模板：应用其字段组合与选项状态
     */
    private void selectSceneTemplate(Template template, MaterialButton chip) {
        Log.i(LOG_PREFIX, "selectSceneTemplate: " + template.getName() + " (id: " + template.getId() + ")");
        selectedTemplate = template;

        // 高亮当前 chip
        if (chip != null) {
            if (currentSceneChip != null) {
                setChipSelected(currentSceneChip, false);
            }
            currentSceneChip = chip;
            setChipSelected(chip, true);
        }

        // 应用模板字段
        if (template.getFields() != null && !template.getFields().isEmpty()) {
            selectedFields = new ArrayList<>(template.getFields());
            Log.i(LOG_PREFIX, "Applied template fields: " + selectedFields.size() + " fields");
        } else {
            selectedFields = ExportUtils.getQuestionFields();
            Log.i(LOG_PREFIX, "Applied default fields: " + selectedFields.size() + " fields");
        }
        updateSelectedFieldsText();

        // 应用模板描述
        String desc = template.getDescription();
        if (desc != null && !desc.isEmpty()) {
            tvTemplateDesc.setText(desc);
            Log.i(LOG_PREFIX, "Template description set");
        }

        // 应用模板选项状态（config 中存在时才覆盖）
        Map<String, Object> config = template.getConfig();
        if (config != null) {
            Log.i(LOG_PREFIX, "Applying template config: " + config.keySet());
            if (config.containsKey("includeAnswers")) {
                cbIncludeAnswers.setChecked(Boolean.TRUE.equals(config.get("includeAnswers")));
                Log.i(LOG_PREFIX, "  includeAnswers: " + cbIncludeAnswers.isChecked());
            }
            if (config.containsKey("includeExplanations")) {
                cbIncludeExplanations.setChecked(Boolean.TRUE.equals(config.get("includeExplanations")));
                Log.i(LOG_PREFIX, "  includeExplanations: " + cbIncludeExplanations.isChecked());
            }
            if (config.containsKey("includeDifficulty")) {
                cbIncludeDifficulty.setChecked(Boolean.TRUE.equals(config.get("includeDifficulty")));
                Log.i(LOG_PREFIX, "  includeDifficulty: " + cbIncludeDifficulty.isChecked());
            }
            // 错题本版：仅导出答错过的题目
            onlyIncorrect = Boolean.TRUE.equals(config.get("onlyIncorrect"));
            Log.i(LOG_PREFIX, "  onlyIncorrect: " + onlyIncorrect);
            // 试卷版/讲义版：导出时按难度排序
            sortByDifficulty = Boolean.TRUE.equals(config.get("sortByDifficulty"));
            Log.i(LOG_PREFIX, "  sortByDifficulty: " + sortByDifficulty);
        }

        // 模板不支持当前格式时，自动切到第一个支持的格式
        if (selectedFormat != null && !template.supportsFormat(selectedFormat.name())) {
            Log.i(LOG_PREFIX, "Current format " + selectedFormat.name() + " not supported by template, switching...");
            switchToFirstSupportedFormat(template);
        }
    }

    /**
     * 动态生成导出格式 chips（来自 ConfigManager 配置）
     */
    private void initFormatChips() {
        ConfigManager configManager = ConfigManager.getInstance(this);
        exportFormats = configManager.getExportFormats();
        llFormatOptions.removeAllViews();

        // 默认选中 Excel（第一个）
        boolean first = true;
        for (Map<String, String> format : exportFormats) {
            final String value = format.get("value");
            final String label = format.get("label");
            MaterialButton chip = createChip(label);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectFormat(value, label, chip);
                }
            });
            llFormatOptions.addView(chip);
            if (first) {
                selectFormat(value, label, chip);
                first = false;
            }
        }
    }

    private void selectFormat(String value, String label, MaterialButton chip) {
        Log.i(LOG_PREFIX, "selectFormat: value=" + value + ", label=" + label);
        try {
            selectedFormat = ExportManager.ExportFormat.valueOf(value);
        } catch (Exception e) {
            Log.e(LOG_PREFIX, "Failed to parse format: " + value + ", error: " + e.getMessage());
            Toast.makeText(this, "不支持的导出格式：" + label, Toast.LENGTH_SHORT).show();
            return;
        }
        Log.i(LOG_PREFIX, "Selected format: " + selectedFormat);
        if (currentFormatChip != null) {
            setChipSelected(currentFormatChip, false);
        }
        currentFormatChip = chip;
        setChipSelected(chip, true);

        // 当前模板不支持该格式时，提示并切换模板
        if (selectedTemplate != null && !selectedTemplate.supportsFormat(value)) {
            Log.w(LOG_PREFIX, "Template does not support format " + value + ", finding alternative...");
            Template supported = findTemplateForFormat(value);
            if (supported != null) {
                Toast.makeText(this, "当前模板不支持" + label + "，已切换到「" + supported.getName() + "」", Toast.LENGTH_SHORT).show();
                selectSceneTemplate(supported, findSceneChip(supported));
            } else {
                Log.w(LOG_PREFIX, "No template supports format " + value);
            }
        }
    }

    private void switchToFirstSupportedFormat(Template template) {
        for (Map<String, String> format : exportFormats) {
            if (template.supportsFormat(format.get("value"))) {
                selectFormat(format.get("value"), format.get("label"), findFormatChip(format.get("value")));
                return;
            }
        }
    }

    private Template findTemplateForFormat(String formatValue) {
        for (Template template : sceneTemplates) {
            if (template.supportsFormat(formatValue)) {
                return template;
            }
        }
        return null;
    }

    private MaterialButton findSceneChip(Template template) {
        if (template == null) return null;
        int idx = -1;
        for (int i = 0; i < sceneTemplates.size(); i++) {
            if (sceneTemplates.get(i).getId() != null && sceneTemplates.get(i).getId().equals(template.getId())) {
                idx = i;
                break;
            }
        }
        if (idx >= 0 && idx < llTemplateScenes.getChildCount()) {
            return (MaterialButton) llTemplateScenes.getChildAt(idx);
        }
        return null;
    }

    private MaterialButton findFormatChip(String value) {
        for (Map<String, String> format : exportFormats) {
            if (format.get("value").equals(value)) {
                int idx = exportFormats.indexOf(format);
                if (idx >= 0 && idx < llFormatOptions.getChildCount()) {
                    return (MaterialButton) llFormatOptions.getChildAt(idx);
                }
            }
        }
        return null;
    }

    private MaterialButton createChip(String text) {
        MaterialButton chip = new MaterialButton(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(12);
        chip.setLayoutParams(params);
        chip.setText(text);
        chip.setTextSize(13);
        chip.setAllCaps(false);
        chip.setCornerRadius(20);
        chip.setMinimumHeight(48);
        chip.setPadding(40, 0, 40, 0);
        setChipSelected(chip, false);
        return chip;
    }

    private void setChipSelected(MaterialButton chip, boolean selected) {
        if (chip == null) return;
        if (selected) {
            chip.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    getColor(com.oilquiz.app.R.color.primary)));
            chip.setTextColor(android.graphics.Color.WHITE);
            chip.setStrokeWidth(0);
        } else {
            chip.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    getColor(com.oilquiz.app.R.color.surface)));
            chip.setTextColor(getColor(com.oilquiz.app.R.color.primary));
            chip.setStrokeColor(android.content.res.ColorStateList.valueOf(
                    getColor(com.oilquiz.app.R.color.primary)));
            chip.setStrokeWidth(2);
        }
    }

    /**
     * 显示字段选择对话框
     * 字段列表与主流程统一（ExportUtils.EXPORTABLE_FIELDS，去无功能项）；
     * 外层 ScrollView 保证字段多时全部可见可滚动。
     */
    private void showFieldSelectionDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("选择导出字段");

        // 获取所有字段（与模板/导出器共用同一来源）
        List<String> fields = ExportUtils.getQuestionFields();
        boolean[] checkedItems = new boolean[fields.size()];

        // 设置默认选中状态
        for (int i = 0; i < fields.size(); i++) {
            checkedItems[i] = selectedFields.contains(fields.get(i));
        }

        // 创建复选框列表（外层 ScrollView 支持滚动，避免字段多时底部不可见）
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(20, 20, 20, 20);

        final List<String> tempSelectedFields = new ArrayList<>(selectedFields);

        for (int i = 0; i < fields.size(); i++) {
            CheckBox checkBox = new CheckBox(this);
            checkBox.setText(ExportUtils.getFieldDisplayName(fields.get(i)));
            checkBox.setChecked(checkedItems[i]);
            checkBox.setTag(fields.get(i));
            layout.addView(checkBox);
        }

        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        scrollView.addView(layout);
        builder.setView(scrollView);

        builder.setPositiveButton("确定", (dialog, which) -> {
            // 收集选中的字段
            tempSelectedFields.clear();
            for (int i = 0; i < layout.getChildCount(); i++) {
                CheckBox checkBox = (CheckBox) layout.getChildAt(i);
                if (checkBox.isChecked()) {
                    tempSelectedFields.add((String) checkBox.getTag());
                }
            }

            if (tempSelectedFields.isEmpty()) {
                Toast.makeText(ExportActivity.this, "请至少选择一个字段", Toast.LENGTH_SHORT).show();
                return;
            }

            selectedFields = tempSelectedFields;
            updateSelectedFieldsText();
            // 联动：勾选的答案/解析/难度字段自动启用对应导出开关，保证开关与字段一致
            syncTogglesWithFields(selectedFields);
            // 联动：使用自定义字段后取消模板高亮（不再跟随模板字段）
            if (currentSceneChip != null) {
                setChipSelected(currentSceneChip, false);
                currentSceneChip = null;
            }
            if (tvTemplateDesc != null) {
                tvTemplateDesc.setText("已使用自定义字段；重新选择模板可恢复模板字段组合");
            }
        });

        builder.setNegativeButton("取消", (dialog, which) -> dialog.dismiss());

        builder.show();
    }

    /** 字段与导出开关联动：勾选答案/解析/难度类字段时自动开启对应开关 */
    private void syncTogglesWithFields(List<String> fields) {
        if (fields == null) return;
        boolean hasAnswer = false, hasExplanation = false, hasDifficulty = false;
        for (String f : fields) {
            if (f == null) continue;
            if ("correctAnswer".equals(f) || "answerText".equals(f) || f.startsWith("blankAnswer")) {
                hasAnswer = true;
            } else if ("explanation".equals(f) || "analysis".equals(f)) {
                hasExplanation = true;
            } else if ("difficulty".equals(f) || "difficultyText".equals(f)) {
                hasDifficulty = true;
            }
        }
        if (hasAnswer) cbIncludeAnswers.setChecked(true);
        if (hasExplanation) cbIncludeExplanations.setChecked(true);
        if (hasDifficulty) cbIncludeDifficulty.setChecked(true);
    }

    /**
     * 更新选中字段文本
     */
    private void updateSelectedFieldsText() {
        if (selectedFields == null || selectedFields.isEmpty()) {
            tvSelectedFields.setText("已选择 0 个字段");
        } else if (selectedFields.size() >= ExportUtils.getQuestionFields().size()) {
            tvSelectedFields.setText("已选择所有字段（" + selectedFields.size() + " 个）");
        } else {
            tvSelectedFields.setText("已选择 " + selectedFields.size() + " 个字段");
        }
    }

    /**
     * 开始导出
     */
    private void startExport() {
        Log.i(LOG_PREFIX, "startExport called");
        // 检查存储权限
        checkStoragePermissionAndStartExport();
    }

    /**
     * 检查存储权限并开始导出
     */
    private void checkStoragePermissionAndStartExport() {
        AppResourceManager resources = AppResourceManager.getInstance(this);
        if (resources.hasStoragePermission()) {
            proceedWithExport();
        } else {
            resources.permissions().requestStoragePermission(this);
        }
    }

    /**
     * 继续执行导出操作
     */
    private void proceedWithExport() {
        Log.i(LOG_PREFIX, "proceedWithExport called");
        if (selectedFormat == null) {
            Log.e(LOG_PREFIX, "selectedFormat is null");
            Toast.makeText(this, "请选择导出格式", Toast.LENGTH_SHORT).show();
            return;
        }

        final ExportManager.ExportFormat exportFormat = selectedFormat;
        Log.i(LOG_PREFIX, "Format to export: " + exportFormat);

        doExport(exportFormat);
    }

    /**
     * 执行导出（获取题目数据后导出）
     */
    private void doExport(final ExportManager.ExportFormat exportFormat) {
        Log.i(LOG_PREFIX, "doExport called: " + exportFormat);

        // 同步获取题目数据（先获取数据，确保成功后再导出）
        final int[] totalQuestions = {0};
        final List<Question>[] allQuestions = new ArrayList[1];
        final String[] errorHolder = new String[1];
        
        questionViewModel.getQuestions(new QuestionViewModel.GetQuestionsCallback() {
            @Override
            public void onSuccess(List<Question> questions) {
                Log.i(LOG_PREFIX, "getQuestions onSuccess: " + (questions != null ? questions.size() : "null"));
                
                // 处理空数据库或空题目列表
                if (questions == null) {
                    questions = new ArrayList<>();
                    Log.w(LOG_PREFIX, "Questions list is null, using empty list");
                }
                
                totalQuestions[0] = questions.size();
                allQuestions[0] = questions;
                errorHolder[0] = null;
                
                // 数据处理完成后直接执行导出
                processAndExport(questions, exportFormat, true);
            }

            @Override
            public void onError(String error) {
                Log.e(LOG_PREFIX, "getQuestions onError: " + error);
                errorHolder[0] = error;
                Toast.makeText(ExportActivity.this, "获取题目失败：" + error, Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * 处理数据并执行导出（在主线程中执行）
     */
    private void processAndExport(List<Question> questions, ExportManager.ExportFormat exportFormat, boolean showProgress) {
        Log.i(LOG_PREFIX, "processAndExport: questions=" + questions.size() + ", format=" + exportFormat);
        
        if (questions.isEmpty()) {
            Toast.makeText(this, "当前数据库中没有题目可导出", Toast.LENGTH_LONG).show();
            return;
        }
        
        // 范围过滤：仅收藏 / 仅错题（错题本版模板）
        List<Question> exportList = questions;
        int exportScope = (rgExportScope != null && rgExportScope.getCheckedRadioButtonId() == R.id.rb_scope_favorite) ? 1 : 0;
        Log.i(LOG_PREFIX, "Export scope: " + (exportScope == 1 ? "favorites" : "all"));
        if (rgExportScope != null && rgExportScope.getCheckedRadioButtonId() == R.id.rb_scope_favorite) {
            exportList = new ArrayList<>();
            for (Question q : questions) {
                if (q != null && q.isFavorite()) {
                    exportList.add(q);
                }
            }
            Log.i(LOG_PREFIX, "Filtered favorites: " + exportList.size() + " / " + questions.size());
            if (exportList.isEmpty()) {
                Toast.makeText(this, "没有收藏的题目可导出", Toast.LENGTH_SHORT).show();
                return;
            }
        } else if (onlyIncorrect) {
            // 错题本版：仅导出答错过（答错次数>0）的题目
            exportList = new ArrayList<>();
            for (Question q : questions) {
                if (q != null && q.getIncorrectCount() > 0) {
                    exportList.add(q);
                }
            }
            Log.i(LOG_PREFIX, "Filtered incorrect questions: " + exportList.size() + " / " + questions.size());
            if (exportList.isEmpty()) {
                Toast.makeText(this, "没有答错过的题目可导出（错题本为空）", Toast.LENGTH_SHORT).show();
                return;
            }
            Toast.makeText(this, "已过滤出 " + exportList.size() + " 道错题", Toast.LENGTH_SHORT).show();
        }
        // 试卷版/模拟考试版：按难度从易到难排序，组卷更合理
        if (sortByDifficulty && exportList.size() > 1) {
            exportList = new ArrayList<>(exportList);
            exportList.sort((a, b) -> Integer.compare(a.getDifficulty(), b.getDifficulty()));
            Log.i(LOG_PREFIX, "Sorted by difficulty: " + exportList.size() + " questions");
        }
        Log.i(LOG_PREFIX, "Final export list: " + exportList.size() + " questions, format: " + exportFormat);
        exportQuestions(exportList, exportFormat);
    }

    private void exportQuestions(List<Question> questions, ExportManager.ExportFormat format) {
        Log.i(LOG_PREFIX, "exportQuestions called: questions=" + questions.size() + ", format=" + format);
        
        // 创建导出配置
        ExportManager.ExportConfig config = new ExportManager.ExportConfig();
        config.setFormat(format);
        config.setIncludeAnswers(cbIncludeAnswers.isChecked());
        config.setIncludeExplanations(cbIncludeExplanations.isChecked());
        config.setIncludeDifficulty(cbIncludeDifficulty.isChecked());
        config.setSelectedFields(selectedFields);
        if (selectedTemplate != null) {
            config.setTemplateId(selectedTemplate.getId());
        }
        Log.i(LOG_PREFIX, "Export config: templateId=" + config.getTemplateId() +
                ", fields=" + (selectedFields != null ? selectedFields.size() : 0) +
                ", answers=" + config.isIncludeAnswers() +
                ", explanations=" + config.isIncludeExplanations());

        // 根据导出格式选择对话框布局
        AlertDialog.Builder progressBuilder = new AlertDialog.Builder(ExportActivity.this);
        final ProgressBar[] progressBarRef = new ProgressBar[1];

        progressBuilder.setTitle("导出中");
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 24, 24, 24);

        ProgressBar progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        layout.addView(progressBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        progressBarRef[0] = progressBar;

        TextView progressText = new TextView(this);
        progressText.setText("正在生成文件...");
        progressText.setTextSize(16);
        progressText.setTextColor(SystemUIResourceAdapter.getInstance(this).getTextPrimaryColor());
        progressText.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        textParams.setMargins(0, 16, 0, 0);
        layout.addView(progressText, textParams);

        progressBuilder.setView(layout);
        progressBuilder.setCancelable(false);
        progressBuilder.setNegativeButton("关闭", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
            }
        });
        AlertDialog progressDialog = progressBuilder.create();

        // 创建导出任务
        ExportManager.ExportTask task = new ExportManager.ExportTask();
        task.setConfig(config);
        task.setQuestions(questions);
        task.setContext(ExportActivity.this);
        task.setCallback(new ExportManager.ExportCallback() {
            @Override
            public void onExportStart() {
                Log.i(LOG_PREFIX, "onExportStart");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        progressDialog.show();
                    }
                });
            }

            @Override
            public void onExportProgress(int progress) {
                Log.d(LOG_PREFIX, "onExportProgress: " + progress + "%");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (progressBarRef[0] != null) {
                            progressBarRef[0].setProgress(progress);
                        }
                    }
                });
            }

            @Override
            public void onExportLog(String message) {
                Log.d(LOG_PREFIX, "Export log: " + message);
            }

            @Override
            public void onExportComplete(File file) {
                Log.i(LOG_PREFIX, "onExportComplete: " + file.getAbsolutePath() + 
                        ", size=" + file.length() + " bytes");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // 导出线程回调时页面可能已销毁：不再弹窗/复制，避免 BadTokenException
                        if (isFinishing() || isDestroyed()) {
                            Log.w(LOG_PREFIX, "导出完成时页面已销毁，跳过结果展示");
                            return;
                        }
                        progressDialog.dismiss();
                        // 判断是否为 WebView 导出
                        boolean isWebView = selectedFormat == ExportManager.ExportFormat.WEBVIEW_APK;
                        // 复制到公共「下载/OilQuiz」目录，用户可在文件管理器中查看与编辑
                        String savedPath = ExportFileSaver.copyToDownloads(
                                ExportActivity.this, file, getMimeType(file.getAbsolutePath()));
                        Log.i(LOG_PREFIX, "File copied to downloads: " + (savedPath != null ? savedPath : "failed"));
                        
                        if (isWebView) {
                            showWebViewExportCompleteDialog(file, savedPath, questions);
                        } else {
                            showExportCompleteDialog(file, savedPath, false);
                        }
                    }
                });
            }

            @Override
            public void onExportError(String error) {
                Log.e(LOG_PREFIX, "onExportError: " + error);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        progressDialog.dismiss();
                        Toast.makeText(ExportActivity.this, "导出失败：" + error, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });

        // 开始导出
        ExportManager.getInstance().startExport(task);
    }

    /**
     * 显示导出完成对话框
     */
    private void showExportCompleteDialog(File file, String savedPath, boolean isWebViewAPK) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("导出完成");

        String message;
        if (isWebViewAPK) {
            // WebView 应用导出（实际产物为单个 HTML 学习页，文案如实说明）
            message = "WebView 学习页生成成功！\n\n";
            if (savedPath != null) {
                message += "文件已保存到：\n" + savedPath + "\n\n";
            }
            message += "文件大小: " + (file.length() / 1024) + " KB\n\n";
            message += "使用方法：\n";
            message += "1. 用系统浏览器或文件管理器直接打开该 HTML 文件\n";
            message += "2. 或在支持本地 HTML 的阅读器/WebView 应用中打开\n\n";
            message += "可在系统「文件管理 → 下载 → OilQuiz」中查看";
        } else {
            message = "导出成功！\n\n文件已保存到：\n" + savedPath
                    + "\n\n文件大小: " + (file.length() / 1024) + " KB"
                    + "\n\n可在系统「文件管理 → 下载 → OilQuiz」中查看和编辑";
        }
        builder.setMessage(message);

        builder.setPositiveButton("查看文件", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                openFile(file);
            }
        });

        builder.setNeutralButton("分享文件", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                shareFile(file);
            }
        });

        builder.setNegativeButton("确定", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
            }
        });

        builder.show();
    }

    /**
     * 显示 WebView APK 导出完成对话框（产物为可安装 APK）
     */
    private void showWebViewExportCompleteDialog(File file, String savedPath, List<Question> questions) {
        // 异步显示完成对话框（不阻塞）
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                AlertDialog.Builder builder = new AlertDialog.Builder(ExportActivity.this);
                builder.setTitle("导出完成");
                String message = "已生成独立 APK 应用（可直接安装到手机）！\n\n";
                if (savedPath != null) {
                    message += "文件已保存到：\n" + savedPath + "\n\n";
                }
                message += "文件大小: " + (file.length() / 1024) + " KB\n\n";
                message += "该 APK 内置当前题库，安装后即可离线刷题。";
                builder.setMessage(message);

                builder.setPositiveButton("安装", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        openFile(file);
                    }
                });
                builder.setNeutralButton("分享", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        shareFile(file);
                    }
                });
                builder.setNegativeButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                });
                builder.show();
            }
        });
    }

    /**
     * 预览 WebView 文件
     */
    private void previewWebViewFile(File file) {
        Log.i(LOG_PREFIX, "Previewing WebView file: " + file.getAbsolutePath());
        
        try {
            // 启动 SimpleWebViewActivity 打开 HTML 文件（类似背题应用）
            Intent intent = new Intent(ExportActivity.this, com.oilquiz.app.SimpleWebViewActivity.class);
            intent.putExtra("html_path", file.getAbsolutePath());
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(LOG_PREFIX, "Failed to preview WebView file", e);
            Toast.makeText(ExportActivity.this, "预览失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 打开文件
     */
    private void openFile(File file) {
        // APK 直接调起系统包安装器（不经过选择器）
        if (file.getName().toLowerCase().endsWith(".apk")) {
            installApk(file);
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri uri;

            // 使用FileProvider创建Uri，避免FileUriExposedException
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                uri = FileProvider.getUriForFile(
                        this,
                        "com.oilquiz.app.fileprovider",
                        file
                );
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                // 旧版本Android使用传统方式
                uri = Uri.fromFile(file);
            }

            String mimeType = getMimeType(file.getAbsolutePath());
            intent.setDataAndType(uri, mimeType);

            // 确保有应用可以处理此Intent
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(Intent.createChooser(intent, "选择打开方式"));
            } else {
                Toast.makeText(this, "没有找到可以打开此文件的应用", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "无法打开文件: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    /**
     * 安装 APK：直接调起系统包安装器
     */
    private void installApk(File file) {
        try {
            Uri uri;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                uri = FileProvider.getUriForFile(this, "com.oilquiz.app.fileprovider", file);
            } else {
                uri = Uri.fromFile(file);
            }
            Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            intent.setData(uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(LOG_PREFIX, "无法安装 APK", e);
            Toast.makeText(this, "无法安装 APK: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 获取文件MIME类型
     */
    private String getMimeType(String filePath) {
        String extension = filePath.substring(filePath.lastIndexOf(".") + 1).toLowerCase();
        switch (extension) {
            case "csv":
                return "text/csv";
            case "xlsx":
                return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "xls":
                return "application/vnd.ms-excel";
            case "pdf":
                return "application/pdf";
            case "docx":
                return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "doc":
                return "application/msword";
            case "pptx":
                return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "ppt":
                return "application/vnd.ms-powerpoint";
            case "html":
                return "text/html";
            case "zip":
                return "application/zip";
            case "md":
                return "text/markdown";
            case "json":
                return "application/json";
            default:
                return "application/octet-stream";
        }
    }

    /**
     * 分享文件
     */
    private void shareFile(File file) {
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            Uri uri;

            // 使用FileProvider创建Uri，避免FileUriExposedException
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                uri = FileProvider.getUriForFile(
                        this,
                        "com.oilquiz.app.fileprovider",
                        file
                );
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                // 旧版本Android使用传统方式
                uri = Uri.fromFile(file);
            }

            String mimeType = getMimeType(file.getAbsolutePath());
            intent.setType(mimeType);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.putExtra(Intent.EXTRA_SUBJECT, "导出文件");
            intent.putExtra(Intent.EXTRA_TEXT, "这是从OilQuiz应用导出的文件：" + file.getName());

            // 确保有应用可以处理此Intent
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(Intent.createChooser(intent, "分享文件"));
            } else {
                Toast.makeText(this, "没有找到可以分享此文件的应用", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "无法分享文件: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        AppResourceManager.getInstance(this).permissions().onRequestPermissionsResult(requestCode, permissions, grantResults);

        // 检查权限是否授予成功
        if (AppResourceManager.getInstance(this).hasStoragePermission()) {
            // 权限授予成功，继续执行导出操作
            Toast.makeText(this, "存储权限已授予", Toast.LENGTH_SHORT).show();
            proceedWithExport();
        } else {
            // 权限授予失败
            Toast.makeText(this, "存储权限被拒绝，无法执行导出操作", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 加载题目数量
     */
    private void loadQuestionCount() {
        questionViewModel.getQuestions(new QuestionViewModel.GetQuestionsCallback() {
            @Override
            public void onSuccess(List<Question> questions) {
                final int count = (questions != null) ? questions.size() : 0;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tvQuestionCount.setText("当前题库共有 " + count + " 道题目");
                    }
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tvQuestionCount.setText("当前题库共有 0 道题目");
                    }
                });
            }
        });
    }
}
