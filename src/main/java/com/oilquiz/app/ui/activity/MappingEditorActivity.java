package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.app.AlertDialog;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import androidx.appcompat.widget.AppCompatButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.oilquiz.app.R;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class MappingEditorActivity extends AppCompatActivity {

    public static final String EXTRA_FIELD_MAPPING = "field_mapping";
    public static final String EXTRA_QUESTION_TYPE_MAPPING = "question_type_mapping";
    public static final String EXTRA_COLUMN_HEADERS = "column_headers";
    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_SHEET_INDEX = "sheet_index";

    private static final String PREFS_NAME = "mapping_prefs";
    private static final String KEY_FIELD_MAPPING = "field_mapping";
    private static final String KEY_QUESTION_TYPE_MAPPING = "question_type_mapping";

    private LinearLayout mappingContainer;
    private AppCompatButton btnAddField;
    private AppCompatButton btnLoadMapping;
    private AppCompatButton btnCancel;
    private AppCompatButton btnNext;
    private Toolbar toolbar;
    private ProgressBar progressBar;
    private TextView progressText;
    private LinearLayout progressContainer;

    private Map<String, Integer> fieldMapping;
    private Map<String, String> questionTypeMapping;
    private List<String> columnHeaders;

    private List<View> mappingViews;
    private Map<View, Integer> selectedColumnsMap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mapping_editor);

        initViews();
        loadData();
        setupListeners();
        populateMappingViews();
    }

    private void initViews() {
        mappingContainer = findViewById(R.id.mappingContainer);
        btnAddField = findViewById(R.id.btn_add_field);
        btnLoadMapping = findViewById(R.id.btn_load_mapping);
        btnCancel = findViewById(R.id.btn_cancel);
        btnNext = findViewById(R.id.btn_next);
        toolbar = findViewById(R.id.toolbar);
        progressContainer = findViewById(R.id.progressContainer);
        progressBar = findViewById(R.id.progressBar);
        progressText = findViewById(R.id.progressText);
        mappingViews = new ArrayList<>();
        selectedColumnsMap = new HashMap<>();
    }

    private void loadData() {
        Intent intent = getIntent();
        if (intent != null) {
            // 优先从Intent获取映射数据（从WebViewActivity或ImportActivity传入）
            Map<String, Integer> intentFieldMapping = (Map<String, Integer>) intent.getSerializableExtra(EXTRA_FIELD_MAPPING);
            Map<String, String> intentQuestionTypeMapping = (Map<String, String>) intent.getSerializableExtra(EXTRA_QUESTION_TYPE_MAPPING);
            
            if (intentFieldMapping != null && !intentFieldMapping.isEmpty()) {
                // 有从Intent传入的映射数据，使用它
                fieldMapping = intentFieldMapping;
            } else {
                // 如果没有Intent传入的数据，才从SharedPreferences加载
                loadSavedMappings();
            }
            
            if (intentQuestionTypeMapping != null) {
                questionTypeMapping = intentQuestionTypeMapping;
            }
            
            // 加载传入的列标题
            columnHeaders = intent.getStringArrayListExtra(EXTRA_COLUMN_HEADERS);
            if (columnHeaders == null) {
                columnHeaders = new ArrayList<>();
            }
        } else {
            // 没有Intent的话，从SharedPreferences加载
            loadSavedMappings();
            columnHeaders = new ArrayList<>();
        }
    }

    private void setupListeners() {
        // 返回按钮
        toolbar.setNavigationOnClickListener(v -> finish());
        
        // 添加字段
        btnAddField.setOnClickListener(v -> addNewFieldMapping());
        
        // 加载配置
        btnLoadMapping.setOnClickListener(v -> loadSavedMappingsWithToast());
        
        // 取消按钮
        btnCancel.setOnClickListener(v -> finish());
        
        // 下一步按钮
        btnNext.setOnClickListener(v -> saveMapping());
    }

    // 从SharedPreferences加载保存的映射配置
    private void loadSavedMappings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String fieldMappingJson = prefs.getString(KEY_FIELD_MAPPING, null);
        String questionTypeMappingJson = prefs.getString(KEY_QUESTION_TYPE_MAPPING, null);

        // 初始化新的映射
        fieldMapping = new HashMap<>();
        questionTypeMapping = new HashMap<>();

        // 尝试解析字段映射
        if (fieldMappingJson != null) {
            try {
                JSONObject jsonObject = new JSONObject(fieldMappingJson);
                Iterator<String> keys = jsonObject.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    int value = jsonObject.getInt(key);
                    fieldMapping.put(key, value);
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        // 尝试解析题型映射
        if (questionTypeMappingJson != null) {
            try {
                JSONObject jsonObject = new JSONObject(questionTypeMappingJson);
                Iterator<String> keys = jsonObject.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    String value = jsonObject.getString(key);
                    questionTypeMapping.put(key, value);
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }

    private void loadSavedMappingsWithToast() {
        loadSavedMappings();
        populateMappingViews();
        Toast.makeText(this, "已加载保存的映射配置", Toast.LENGTH_SHORT).show();
    }

    // 保存映射配置到SharedPreferences
    private void saveMappingToPreferences(Map<String, Integer> fieldMap, Map<String, String> questionTypeMap) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        // 保存字段映射
        if (fieldMap != null && !fieldMap.isEmpty()) {
            try {
                JSONObject jsonObject = new JSONObject();
                for (Map.Entry<String, Integer> entry : fieldMap.entrySet()) {
                    jsonObject.put(entry.getKey(), entry.getValue());
                }
                editor.putString(KEY_FIELD_MAPPING, jsonObject.toString());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // 保存题型映射
        if (questionTypeMap != null && !questionTypeMap.isEmpty()) {
            try {
                JSONObject jsonObject = new JSONObject();
                for (Map.Entry<String, String> entry : questionTypeMap.entrySet()) {
                    jsonObject.put(entry.getKey(), entry.getValue());
                }
                editor.putString(KEY_QUESTION_TYPE_MAPPING, jsonObject.toString());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        editor.apply();
        Toast.makeText(this, "映射配置已保存", Toast.LENGTH_SHORT).show();
    }

    private void populateMappingViews() {
        mappingContainer.removeAllViews();
        mappingViews.clear();
        selectedColumnsMap.clear();

        // 默认字段列表
        String[] defaultFields = {"题目", "选项A", "选项B", "选项C", "选项D", "正确答案", "解析", "难度", "分类", "题型"};

        // 添加默认字段映射
        for (String fieldName : defaultFields) {
            Integer columnIndex = fieldMapping.get(fieldName);
            if (columnIndex != null) {
                addMappingView(fieldName, columnIndex);
            } else {
                addMappingView(fieldName, -1);
            }
        }

        // 添加非默认的保存字段
        for (Map.Entry<String, Integer> entry : fieldMapping.entrySet()) {
            String fieldName = entry.getKey();
            boolean isDefault = false;
            for (String defaultField : defaultFields) {
                if (defaultField.equals(fieldName)) {
                    isDefault = true;
                    break;
                }
            }
            if (!isDefault) {
                addMappingView(fieldName, entry.getValue());
            }
        }
    }

    private void addMappingView(String fieldName, int columnIndex) {
        View mappingView = LayoutInflater.from(this).inflate(R.layout.item_mapping_field, mappingContainer, false);
        
        // 获取控件
        TextInputEditText etFieldName = mappingView.findViewById(R.id.et_field_name);
        AppCompatButton btnSelectColumn = mappingView.findViewById(R.id.btn_select_column);
        AppCompatButton btnDelete = mappingView.findViewById(R.id.btn_delete);

        // 设置字段名
        etFieldName.setText(fieldName);

        // 保存选中的列索引
        if (columnIndex >= 0 && columnIndex < columnHeaders.size()) {
            selectedColumnsMap.put(mappingView, columnIndex);
            btnSelectColumn.setText("列 " + (columnIndex + 1) + ": " + columnHeaders.get(columnIndex));
        } else {
            selectedColumnsMap.put(mappingView, -1);
            btnSelectColumn.setText("选择列");
        }

        // 列选择按钮点击事件
        btnSelectColumn.setOnClickListener(v -> showColumnSelectionDialog(mappingView, btnSelectColumn));

        // 删除按钮
        btnDelete.setOnClickListener(v -> {
            mappingContainer.removeView(mappingView);
            mappingViews.remove(mappingView);
            selectedColumnsMap.remove(mappingView);
        });

        mappingContainer.addView(mappingView);
        mappingViews.add(mappingView);
    }

    private void showColumnSelectionDialog(View mappingView, AppCompatButton btnSelectColumn) {
        // 准备列选择选项
        final List<String> columnOptions = new ArrayList<>();
        columnOptions.add("不映射");
        for (int i = 0; i < columnHeaders.size(); i++) {
            columnOptions.add("列 " + (i + 1) + ": " + columnHeaders.get(i));
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("选择映射列");
        builder.setItems(columnOptions.toArray(new String[0]), (dialog, which) -> {
            if (which == 0) {
                selectedColumnsMap.put(mappingView, -1);
                btnSelectColumn.setText("选择列");
            } else {
                int columnIndex = which - 1;
                selectedColumnsMap.put(mappingView, columnIndex);
                btnSelectColumn.setText(columnOptions.get(which));
            }
        });
        builder.show();
    }

    private void addNewFieldMapping() {
        addMappingView("新字段", -1);
    }

    private void saveMapping() {
        Map<String, Integer> newFieldMapping = new HashMap<>();
        Map<Integer, String> columnToFieldMap = new HashMap<>();

        for (View view : mappingViews) {
            TextInputEditText etFieldName = view.findViewById(R.id.et_field_name);

            String fieldName = etFieldName.getText().toString().trim();
            Integer selectedIndex = selectedColumnsMap.get(view);

            if (!fieldName.isEmpty() && selectedIndex != null && selectedIndex >= 0) {
                // 检查列是否已经被其他字段映射
                if (columnToFieldMap.containsKey(selectedIndex)) {
                    Toast.makeText(this, "错误：列 " + (selectedIndex + 1) + " 已被字段 '" + columnToFieldMap.get(selectedIndex) + "' 映射", Toast.LENGTH_SHORT).show();
                    return;
                }

                newFieldMapping.put(fieldName, selectedIndex);
                columnToFieldMap.put(selectedIndex, fieldName);
            }
        }

        // 保存到SharedPreferences
        saveMappingToPreferences(newFieldMapping, questionTypeMapping);

        // 返回结果
        Intent resultIntent = new Intent();
        resultIntent.putExtra(EXTRA_FIELD_MAPPING, (java.io.Serializable) newFieldMapping);
        resultIntent.putExtra(EXTRA_QUESTION_TYPE_MAPPING, (java.io.Serializable) questionTypeMapping);
        setResult(RESULT_OK, resultIntent);
        finish();
    }
}
