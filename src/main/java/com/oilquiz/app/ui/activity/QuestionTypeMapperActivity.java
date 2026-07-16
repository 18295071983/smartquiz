package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import com.oilquiz.app.R;
import com.oilquiz.app.resource.SystemUIResourceAdapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class QuestionTypeMapperActivity extends AppCompatActivity {

    public static final String EXTRA_DETECTED_QUESTION_TYPES = "detected_question_types";
    public static final String EXTRA_QUESTION_TYPE_MAPPING = "question_type_mapping";
    public static final String EXTRA_RESULT_QUESTION_TYPE_MAPPING = "result_question_type_mapping";

    private LinearLayout mappingsContainer;
    private List<String> detectedQuestionTypes;
    private Map<String, String> questionTypeMapping;
    private List<String> defaultQuestionTypes;
    
    // UI组件
    private TextView detectedTypeCount;
    private TextView mappedTypeCount;
    private int mappedCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_question_type_mapper);

        initViews();
        loadData();
        setupListeners();
        showMappings();
        updateStats();
    }
    
    private void initViews() {
        mappingsContainer = findViewById(R.id.mappings_container);
        detectedTypeCount = findViewById(R.id.detectedTypeCount);
        mappedTypeCount = findViewById(R.id.mappedTypeCount);
    }

    private void loadData() {
        // 获取传入的检测到的题型列表
        detectedQuestionTypes = getIntent().getStringArrayListExtra(EXTRA_DETECTED_QUESTION_TYPES);
        // 获取传入的现有映射
        if (getIntent().hasExtra(EXTRA_QUESTION_TYPE_MAPPING)) {
            questionTypeMapping = (Map<String, String>) getIntent().getSerializableExtra(EXTRA_QUESTION_TYPE_MAPPING);
        } else {
            questionTypeMapping = new HashMap<>();
        }

        // 初始化默认题型列表
        initDefaultQuestionTypes();
    }
    
    private void setupListeners() {
        // 设置确认按钮点击事件
        findViewById(R.id.btn_confirm).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmMapping();
            }
        });

        // 设置取消按钮点击事件
        findViewById(R.id.btn_cancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    private void initDefaultQuestionTypes() {
        defaultQuestionTypes = new ArrayList<>();
        defaultQuestionTypes.add("未分类");
        defaultQuestionTypes.add("单选题");
        defaultQuestionTypes.add("多选题");
        defaultQuestionTypes.add("判断题");
        defaultQuestionTypes.add("填空题");
        defaultQuestionTypes.add("简答题");
        defaultQuestionTypes.add("论述题");
        defaultQuestionTypes.add("匹配题");
        defaultQuestionTypes.add("排序题");
    }

    private void showMappings() {
        if (detectedQuestionTypes == null || detectedQuestionTypes.isEmpty()) {
            // 没有检测到题型
            showNoTypesMessage();
            return;
        }

        // 为每个检测到的题型创建映射视图
        for (String detectedType : detectedQuestionTypes) {
            addMappingView(detectedType, defaultQuestionTypes);
        }
    }
    
    private void showNoTypesMessage() {
        // 创建提示布局
        LinearLayout noTypesLayout = new LinearLayout(this);
        noTypesLayout.setOrientation(LinearLayout.VERTICAL);
        noTypesLayout.setPadding(32, 32, 32, 32);
        noTypesLayout.setGravity(android.view.Gravity.CENTER);
        
        // 添加图标
        ImageView iconView = new ImageView(this);
        iconView.setImageResource(R.drawable.ic_info);
        iconView.setColorFilter(SystemUIResourceAdapter.getInstance(this).getTextSecondaryColor());
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(64, 64);
        iconParams.setMargins(0, 0, 0, 16);
        iconView.setLayoutParams(iconParams);
        noTypesLayout.addView(iconView);
        
        // 添加提示文本
        TextView noTypesText = new TextView(this);
        noTypesText.setText("未检测到题型数据\n将使用默认题型映射");
        noTypesText.setTextSize(16);
        noTypesText.setTextColor(SystemUIResourceAdapter.getInstance(this).getTextSecondaryColor());
        noTypesText.setGravity(android.view.Gravity.CENTER);
        noTypesText.setLineSpacing(0, 1.5f);
        noTypesLayout.addView(noTypesText);
        
        mappingsContainer.addView(noTypesLayout);
        
        // 添加默认题型映射选项
        addDefaultMappingOptions();
    }
    
    private void addDefaultMappingOptions() {
        // 添加默认题型映射选项，让用户可以为未检测到的题型设置默认映射
        TextView defaultMappingText = new TextView(this);
        defaultMappingText.setText("默认题型映射:");
        defaultMappingText.setTextSize(16);
        defaultMappingText.setTextColor(SystemUIResourceAdapter.getInstance(this).getTextPrimaryColor());
        defaultMappingText.setPadding(16, 24, 16, 8);
        mappingsContainer.addView(defaultMappingText);
        
        // 添加未分类的默认映射
        addMappingView("未分类", defaultQuestionTypes);
    }

    private void addMappingView(String detectedType, List<String> defaultQuestionTypes) {
        // 创建映射项容器
        LinearLayout mappingItem = new LinearLayout(this);
        mappingItem.setOrientation(LinearLayout.HORIZONTAL);
        mappingItem.setPadding(16, 12, 16, 12);
        mappingItem.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        mappingItem.setBackgroundResource(R.drawable.rounded_background);
        mappingItem.setElevation(2);
        
        // 设置边距
        LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        itemParams.setMargins(0, 0, 0, 12);
        mappingItem.setLayoutParams(itemParams);

        // 添加检测到的题型文本
        TextView detectedTypeText = new TextView(this);
        if (detectedType == null || detectedType.trim().isEmpty()) {
            detectedTypeText.setText("未命名题型");
        } else {
            detectedTypeText.setText(detectedType);
        }
        detectedTypeText.setTextSize(16);
        detectedTypeText.setTextColor(android.graphics.Color.BLACK);
        detectedTypeText.setMinWidth(200);
        detectedTypeText.setPadding(8, 0, 8, 0);
        detectedTypeText.setLayoutParams(new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1.2f
        ));
        detectedTypeText.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        mappingItem.addView(detectedTypeText);

        // 添加映射箭头
        TextView arrowText = new TextView(this);
        arrowText.setText(" → ");
        arrowText.setTextSize(18);
        arrowText.setTextColor(android.graphics.Color.parseColor("#6200EE"));
        arrowText.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        mappingItem.addView(arrowText);

        // 添加默认题型选择器
        Spinner defaultTypeSpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, defaultQuestionTypes);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        defaultTypeSpinner.setAdapter(adapter);

        // 设置默认选中值
        String mappedType = questionTypeMapping.get(detectedType);
        if (mappedType != null && defaultQuestionTypes.contains(mappedType)) {
            defaultTypeSpinner.setSelection(defaultQuestionTypes.indexOf(mappedType));
        } else {
            // 默认选中未分类
            defaultTypeSpinner.setSelection(0);
        }

        // 设置选择监听器
        final String currentDetectedType = detectedType;
        defaultTypeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedType = (String) parent.getItemAtPosition(position);
                questionTypeMapping.put(currentDetectedType, selectedType);
                updateMappedCount();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // 不做处理
            }
        });

        defaultTypeSpinner.setLayoutParams(new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1
        ));
        mappingItem.addView(defaultTypeSpinner);

        // 添加到容器
        mappingsContainer.addView(mappingItem);
    }
    
    private void updateStats() {
        if (detectedQuestionTypes != null) {
            detectedTypeCount.setText(String.valueOf(detectedQuestionTypes.size()));
        } else {
            detectedTypeCount.setText("0");
        }
        updateMappedCount();
    }
    
    private void updateMappedCount() {
        mappedCount = 0;
        if (detectedQuestionTypes != null) {
            for (String type : detectedQuestionTypes) {
                if (questionTypeMapping.containsKey(type) && 
                    !"未分类".equals(questionTypeMapping.get(type))) {
                    mappedCount++;
                }
            }
        }
        mappedTypeCount.setText(String.valueOf(mappedCount));
    }

    private void confirmMapping() {
        // 返回映射结果
        Intent resultIntent = new Intent();
        resultIntent.putExtra(EXTRA_RESULT_QUESTION_TYPE_MAPPING, (java.io.Serializable) questionTypeMapping);
        setResult(RESULT_OK, resultIntent);
        finish();
    }
}
