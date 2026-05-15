package com.oilquiz.app.ai.model;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.webkit.WebView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.R;

import java.util.ArrayList;
import java.util.List;

public class ModelComparisonActivity extends AppCompatActivity {

    private Spinner modelASelector;
    private Spinner modelBSelector;
    private WebView performanceChart;

    private List<Model> models;
    private Model selectedModelA;
    private Model selectedModelB;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_model_comparison);

        initViews();
        initModels();
        setupModelSelection();
        setupComparisonButton();
        setupPerformanceChart();
    }

    private void initViews() {
        modelASelector = findViewById(R.id.model_a_selector);
        modelBSelector = findViewById(R.id.model_b_selector);
        performanceChart = findViewById(R.id.performance_web_view);
    }

    private void initModels() {
        models = new ArrayList<>();
        // 添加示例模型
        models.add(new Model(
                "1",
                "Llama-3-8B",
                "通用对话模型，平衡性能与质量",
                "Llama 3",
                8192,
                128000,
                "8B",
                "4-bit",
                "4GB",
                "20 t/s",
                85.5f,
                90.2f,
                "通用对话、知识问答、创意写作",
                false
        ));

        models.add(new Model(
                "2",
                "Qwen-2-7B",
                "中文优化模型，适合中文对话",
                "Qwen",
                8192,
                128000,
                "7B",
                "4-bit",
                "3.5GB",
                "25 t/s",
                88.0f,
                89.5f,
                "中文对话、翻译、知识问答",
                false
        ));

        models.add(new Model(
                "3",
                "GPT-4",
                "OpenAI最强模型",
                "GPT-4",
                128000,
                1000000,
                "1.76T",
                "N/A",
                "N/A",
                "30 t/s",
                95.0f,
                98.0f,
                "复杂推理、创意内容、多模态",
                false
        ));

        models.add(new Model(
                "4",
                "Claude-3",
                "Anthropic大语言模型",
                "Claude",
                200000,
                1000000,
                "100B+",
                "N/A",
                "N/A",
                "25 t/s",
                94.0f,
                97.0f,
                "长文本处理、创意写作、多语言",
                false
        ));
    }

    private void setupModelSelection() {
        // 准备模型名称列表
        List<String> modelNames = new ArrayList<>();
        for (Model model : models) {
            modelNames.add(model.getName());
        }

        // 设置Spinner适配器
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, modelNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        modelASelector.setAdapter(adapter);
        modelBSelector.setAdapter(adapter);

        // 设置默认选择
        modelASelector.setSelection(0);
        modelBSelector.setSelection(1);

        // 模型A选择器监听器
        modelASelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedModelA = models.get(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        // 模型B选择器监听器
        modelBSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedModelB = models.get(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        // 初始化选中的模型
        selectedModelA = models.get(0);
        selectedModelB = models.get(1);
    }

    private void setupComparisonButton() {
        // 比较按钮在布局中是run_test_button，不是compare_button
        MaterialButton runTestButton = findViewById(R.id.run_test_button);
        if (runTestButton != null) {
            runTestButton.setOnClickListener(v -> {
                if (selectedModelA != null && selectedModelB != null) {
                    performComparison();
                }
            });
        }
    }

    private void performComparison() {
        // 执行模型比较
        // 这里可以实现详细的比较逻辑
        updatePerformanceChart();
        // 可以更新RecyclerView显示比较结果
    }

    private void setupPerformanceChart() {
        if (performanceChart != null) {
            // 启用JavaScript
            android.webkit.WebSettings webSettings = performanceChart.getSettings();
            webSettings.setJavaScriptEnabled(true);
            
            // 初始加载一个简单的图表
            loadDefaultChart();
        }
    }

    private void updatePerformanceChart() {
        if (performanceChart != null && selectedModelA != null && selectedModelB != null) {
            // 生成比较图表的HTML
            String chartHtml = generateComparisonChart();
            performanceChart.loadDataWithBaseURL("file:///android_asset/", chartHtml, "text/html", "UTF-8", null);
        }
    }

    private void loadDefaultChart() {
        String defaultChart = getDefaultChartHtml();
        performanceChart.loadDataWithBaseURL("file:///android_asset/", defaultChart, "text/html", "UTF-8", null);
    }

    private String getDefaultChartHtml() {
        return "" +
                "<!DOCTYPE html>"
                + "<html>"
                + "<head>"
                + "<title>模型性能对比</title>"
                + "<script src=\"https://cdn.jsdelivr.net/npm/chart.js\"></script>"
                + "<style>"
                + "body { font-family: Arial, sans-serif; padding: 20px; }"
                + "h2 { text-align: center; }"
                + "canvas { max-width: 100%; height: 300px; }"
                + "</style>"
                + "</head>"
                + "<body>"
                + "<h2>模型性能对比</h2>"
                + "<canvas id=\"performanceChart\"></canvas>"
                + "<script>"
                + "const ctx = document.getElementById('performanceChart').getContext('2d');"
                + "const chart = new Chart(ctx, {"
                + "  type: 'bar',"
                + "  data: {"
                + "    labels: ['性能评分', '质量评分'],"
                + "    datasets: ["
                + "      {"
                + "        label: '模型A',"
                + "        data: [85, 90],"
                + "        backgroundColor: 'rgba(75, 192, 192, 0.2)',"
                + "        borderColor: 'rgba(75, 192, 192, 1)',"
                + "        borderWidth: 1"
                + "      },"
                + "      {"
                + "        label: '模型B',"
                + "        data: [88, 89],"
                + "        backgroundColor: 'rgba(153, 102, 255, 0.2)',"
                + "        borderColor: 'rgba(153, 102, 255, 1)',"
                + "        borderWidth: 1"
                + "      }"
                + "    ]"
                + "  },"
                + "  options: {"
                + "    scales: {"
                + "      y: {"
                + "        beginAtZero: true,"
                + "        max: 100"
                + "      }"
                + "    }"
                + "  }"
                + "});"
                + "</script>"
                + "</body>"
                + "</html>";
    }

    private String generateComparisonChart() {
        if (selectedModelA == null || selectedModelB == null) {
            return getDefaultChartHtml();
        }
        
        String chartHtml = "" +
                "<!DOCTYPE html>"
                + "<html>"
                + "<head>"
                + "<title>模型性能对比</title>"
                + "<script src=\"https://cdn.jsdelivr.net/npm/chart.js\"></script>"
                + "<style>"
                + "body { font-family: Arial, sans-serif; padding: 20px; }"
                + "h2 { text-align: center; }"
                + "canvas { max-width: 100%; height: 300px; }"
                + "</style>"
                + "</head>"
                + "<body>"
                + "<h2>" + selectedModelA.getName() + " vs " + selectedModelB.getName() + "</h2>"
                + "<canvas id=\"performanceChart\"></canvas>"
                + "<script>"
                + "const ctx = document.getElementById('performanceChart').getContext('2d');"
                + "const chart = new Chart(ctx, {"
                + "  type: 'bar',"
                + "  data: {"
                + "    labels: ['性能评分', '质量评分'],"
                + "    datasets: ["
                + "      {"
                + "        label: '" + selectedModelA.getName() + "',"
                + "        data: [" + selectedModelA.getPerformanceScore() + ", " + selectedModelA.getQualityScore() + "],"
                + "        backgroundColor: 'rgba(75, 192, 192, 0.2)',"
                + "        borderColor: 'rgba(75, 192, 192, 1)',"
                + "        borderWidth: 1"
                + "      },"
                + "      {"
                + "        label: '" + selectedModelB.getName() + "',"
                + "        data: [" + selectedModelB.getPerformanceScore() + ", " + selectedModelB.getQualityScore() + "],"
                + "        backgroundColor: 'rgba(153, 102, 255, 0.2)',"
                + "        borderColor: 'rgba(153, 102, 255, 1)',"
                + "        borderWidth: 1"
                + "      }"
                + "    ]"
                + "  },"
                + "  options: {"
                + "    scales: {"
                + "      y: {"
                + "        beginAtZero: true,"
                + "        max: 100"
                + "      }"
                + "    }"
                + "  }"
                + "});"
                + "</script>"
                + "</body>"
                + "</html>";
        
        return chartHtml;
    }



    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (performanceChart != null) {
            performanceChart.destroy();
        }
    }
}