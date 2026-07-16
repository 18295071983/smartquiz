package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.R;
import com.oilquiz.app.ui.adapter.SmartMappingAdapter;
import com.oilquiz.app.util.render.ExcelUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SmartMappingActivity extends AppCompatActivity {
    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_SHEET_INDEX = "sheet_index";
    public static final String EXTRA_FIELD_MAPPING = "field_mapping";
    public static final String EXTRA_IMPORT_SETTINGS = "import_settings";
    public static final String EXTRA_RESULT_QUESTION_TYPE_MAPPING = "question_type_mapping";
    public static final String EXTRA_RESULT_DIFFICULTY_MAPPING = "difficulty_mapping";
    public static final String EXTRA_RESULT_CATEGORY_MAPPING = "category_mapping";

    private File file;
    private int sheetIndex;
    private Map<String, Integer> fieldMapping;
    private ExcelUtil.ImportSettings importSettings;
    private Map<String, String> questionTypeMapping;
    private Map<String, String> difficultyMapping;
    private Map<String, String> categoryMapping;

    private ProgressBar progressBar;
    private TextView statusText;
    private TextView progressDetailText;
    private CardView statsCard;
    private CardView mappingCard;
    private CardView loadingCard;
    private TextView questionTypeCount;
    private TextView difficultyCount;
    private TextView categoryCount;
    private Toolbar toolbar;
    private RecyclerView mappingRecyclerView;
    private MaterialButton btnQuestionType;
    private MaterialButton btnDifficulty;
    private MaterialButton btnCategory;
    private MaterialButton btnNext;
    private MaterialButton btnCancel;
    
    private List<String> detectedQuestionTypes;
    private List<String> detectedDifficulties;
    private List<String> detectedCategories;
    private List<String> standardQuestionTypes;
    private List<String> standardDifficulties;
    private List<String> standardCategories;
    private SmartMappingAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_smart_mapping);

        initViews();
        loadData();
        
        if (file == null || fieldMapping == null) {
            showErrorState("加载数据失败：文件或字段映射为空");
            Toast.makeText(this, "无法加载文件数据，请重新选择文件", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        
        setupListeners();
        generateMappingSuggestions();
    }

    private void initViews() {
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        
        progressBar = findViewById(R.id.progressBar);
        statusText = findViewById(R.id.statusText);
        progressDetailText = findViewById(R.id.progressDetailText);
        mappingRecyclerView = findViewById(R.id.mappingRecyclerView);
        btnNext = findViewById(R.id.btnNext);
        btnCancel = findViewById(R.id.btnCancel);
        btnQuestionType = findViewById(R.id.btnQuestionType);
        btnDifficulty = findViewById(R.id.btnDifficulty);
        btnCategory = findViewById(R.id.btnCategory);
        
        statsCard = findViewById(R.id.statsCard);
        loadingCard = findViewById(R.id.loadingCard);
        mappingCard = findViewById(R.id.mappingCard);
        questionTypeCount = findViewById(R.id.questionTypeCount);
        difficultyCount = findViewById(R.id.difficultyCount);
        categoryCount = findViewById(R.id.categoryCount);

        mappingRecyclerView.setLayoutManager(new LinearLayoutManager(this));
    }

    private void loadData() {
        Intent intent = getIntent();
        if (intent != null) {
            String filePath = intent.getStringExtra(EXTRA_FILE_PATH);
            if (filePath != null) {
                file = new File(filePath);
            }
            sheetIndex = intent.getIntExtra(EXTRA_SHEET_INDEX, 0);
            fieldMapping = (Map<String, Integer>) intent.getSerializableExtra(EXTRA_FIELD_MAPPING);
            importSettings = (ExcelUtil.ImportSettings) intent.getSerializableExtra(EXTRA_IMPORT_SETTINGS);
            if (importSettings == null) {
                importSettings = new ExcelUtil.ImportSettings();
            }
        }
    }

    private void setupListeners() {
        btnNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (adapter != null) {
                    questionTypeMapping = adapter.getQuestionTypeMapping();
                    difficultyMapping = adapter.getDifficultyMapping();
                    categoryMapping = adapter.getCategoryMapping();

                    Intent resultIntent = new Intent();
                    resultIntent.putExtra(EXTRA_RESULT_QUESTION_TYPE_MAPPING, (java.io.Serializable) questionTypeMapping);
                    resultIntent.putExtra(EXTRA_RESULT_DIFFICULTY_MAPPING, (java.io.Serializable) difficultyMapping);
                    resultIntent.putExtra(EXTRA_RESULT_CATEGORY_MAPPING, (java.io.Serializable) categoryMapping);
                    resultIntent.putExtra(EXTRA_FIELD_MAPPING, (java.io.Serializable) fieldMapping);
                    setResult(RESULT_OK, resultIntent);
                    finish();
                } else {
                    Toast.makeText(SmartMappingActivity.this, "映射数据未就绪", Toast.LENGTH_SHORT).show();
                }
            }
        });

        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        
        btnQuestionType.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (adapter != null) {
                    adapter.setShowType(SmartMappingAdapter.SHOW_QUESTION_TYPE);
                    updateButtonStyles(SmartMappingAdapter.SHOW_QUESTION_TYPE);
                }
            }
        });
        
        btnDifficulty.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (adapter != null) {
                    adapter.setShowType(SmartMappingAdapter.SHOW_DIFFICULTY);
                    updateButtonStyles(SmartMappingAdapter.SHOW_DIFFICULTY);
                }
            }
        });
        
        btnCategory.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (adapter != null) {
                    adapter.setShowType(SmartMappingAdapter.SHOW_CATEGORY);
                    updateButtonStyles(SmartMappingAdapter.SHOW_CATEGORY);
                }
            }
        });
    }
    
    private void updateButtonStyles(int selectedType) {
        int primaryColor = getResources().getColor(R.color.teal_200);
        
        btnQuestionType.setStrokeWidth(2);
        btnDifficulty.setStrokeWidth(2);
        btnCategory.setStrokeWidth(2);
        
        if (selectedType == SmartMappingAdapter.SHOW_QUESTION_TYPE) {
            btnQuestionType.setBackgroundColor(primaryColor);
            btnQuestionType.setTextColor(getResources().getColor(android.R.color.white));
            btnDifficulty.setBackgroundColor(getResources().getColor(android.R.color.transparent));
            btnDifficulty.setTextColor(getResources().getColor(android.R.color.black));
            btnCategory.setBackgroundColor(getResources().getColor(android.R.color.transparent));
            btnCategory.setTextColor(getResources().getColor(android.R.color.black));
        } else if (selectedType == SmartMappingAdapter.SHOW_DIFFICULTY) {
            btnQuestionType.setBackgroundColor(getResources().getColor(android.R.color.transparent));
            btnQuestionType.setTextColor(getResources().getColor(android.R.color.black));
            btnDifficulty.setBackgroundColor(primaryColor);
            btnDifficulty.setTextColor(getResources().getColor(android.R.color.white));
            btnCategory.setBackgroundColor(getResources().getColor(android.R.color.transparent));
            btnCategory.setTextColor(getResources().getColor(android.R.color.black));
        } else {
            btnQuestionType.setBackgroundColor(getResources().getColor(android.R.color.transparent));
            btnQuestionType.setTextColor(getResources().getColor(android.R.color.black));
            btnDifficulty.setBackgroundColor(getResources().getColor(android.R.color.transparent));
            btnDifficulty.setTextColor(getResources().getColor(android.R.color.black));
            btnCategory.setBackgroundColor(primaryColor);
            btnCategory.setTextColor(getResources().getColor(android.R.color.white));
        }
    }

    private void generateMappingSuggestions() {
        if (file == null || !file.exists()) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    showErrorState("文件不存在或已删除");
                    Toast.makeText(SmartMappingActivity.this, "文件不存在，请重新选择", Toast.LENGTH_SHORT).show();
                }
            });
            return;
        }
        
        if (fieldMapping == null || fieldMapping.isEmpty()) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    showErrorState("字段映射为空");
                    Toast.makeText(SmartMappingActivity.this, "字段映射配置无效", Toast.LENGTH_SHORT).show();
                }
            });
            return;
        }
        
        showLoadingState("正在分析数据...", "检测题型、难度和分类信息");

        ExcelUtil.executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    updateProgress("正在检测题型信息...");
                    detectedQuestionTypes = ExcelUtil.detectQuestionTypes(file, sheetIndex, fieldMapping);
                    
                    updateProgress("正在检测难度信息...");
                    detectedDifficulties = ExcelUtil.detectDifficultyLevels(file, sheetIndex, fieldMapping);
                    
                    updateProgress("正在检测分类信息...");
                    detectedCategories = ExcelUtil.detectCategories(file, sheetIndex, fieldMapping);

                    standardQuestionTypes = getStandardQuestionTypes();
                    standardDifficulties = getStandardDifficulties();
                    standardCategories = getStandardCategories();

                    updateProgress("正在生成映射建议...");
                    questionTypeMapping = ExcelUtil.generateMappingSuggestions(detectedQuestionTypes, standardQuestionTypes);
                    difficultyMapping = ExcelUtil.generateMappingSuggestions(detectedDifficulties, standardDifficulties);
                    categoryMapping = ExcelUtil.generateMappingSuggestions(detectedCategories, standardCategories);

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            loadingCard.setVisibility(View.GONE);
                            
                            showStats(detectedQuestionTypes.size(), detectedDifficulties.size(), detectedCategories.size());
                            
                            adapter = new SmartMappingAdapter(SmartMappingActivity.this, detectedQuestionTypes, detectedDifficulties, detectedCategories,
                                standardQuestionTypes, standardDifficulties, standardCategories,
                                questionTypeMapping, difficultyMapping, categoryMapping);
                            mappingRecyclerView.setAdapter(adapter);
                            updateButtonStyles(SmartMappingAdapter.SHOW_QUESTION_TYPE);
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showErrorState("生成映射建议失败: " + e.getMessage());
                            Toast.makeText(SmartMappingActivity.this, "生成映射建议失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        });
    }

    private void showLoadingState(String status, String detail) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusText.setText(status);
                progressDetailText.setText(detail);
                progressBar.setVisibility(View.VISIBLE);
                loadingCard.setVisibility(View.VISIBLE);
                statsCard.setVisibility(View.GONE);
                mappingCard.setVisibility(View.GONE);
                btnNext.setEnabled(false);
            }
        });
    }

    private void updateProgress(String detail) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                progressDetailText.setText(detail);
            }
        });
    }

    private void showStats(int typeCount, int difficultyCount, int categoryCount) {
        this.questionTypeCount.setText(String.valueOf(typeCount));
        this.difficultyCount.setText(String.valueOf(difficultyCount));
        this.categoryCount.setText(String.valueOf(categoryCount));
        
        statsCard.setVisibility(View.VISIBLE);
        mappingCard.setVisibility(View.VISIBLE);
        btnNext.setEnabled(true);
    }

    private void showErrorState(String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusText.setText("分析失败");
                progressDetailText.setText(message);
                progressBar.setVisibility(View.GONE);
            }
        });
    }

    private List<String> getStandardQuestionTypes() {
        List<String> types = new ArrayList<>();
        types.add("单选题");
        types.add("多选题");
        types.add("判断题");
        types.add("填空题");
        types.add("简答题");
        types.add("论述题");
        types.add("匹配题");
        types.add("排序题");
        return types;
    }

    private List<String> getStandardDifficulties() {
        List<String> difficulties = new ArrayList<>();
        difficulties.add("简单");
        difficulties.add("中等");
        difficulties.add("困难");
        return difficulties;
    }

    private List<String> getStandardCategories() {
        List<String> categories = new ArrayList<>();
        categories.add("基础知识");
        categories.add("专业知识");
        categories.add("安全知识");
        categories.add("操作技能");
        categories.add("法律法规");
        categories.add("应急处理");
        return categories;
    }
}
