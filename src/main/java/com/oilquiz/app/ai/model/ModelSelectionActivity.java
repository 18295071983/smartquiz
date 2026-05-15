package com.oilquiz.app.ai.model;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.R;

import java.util.ArrayList;
import java.util.List;

public class ModelSelectionActivity extends AppCompatActivity {

    private RecyclerView modelRecyclerView;
    private ModelAdapter modelAdapter;
    private List<Model> models;
    private ProgressBar loadingIndicator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_model_selection);

        initViews();
        initModels();
        initAdapter();
        setupListeners();
    }

    private void initViews() {
        modelRecyclerView = findViewById(R.id.model_recycler_view);
        loadingIndicator = findViewById(R.id.loading_indicator);

        MaterialButton compareButton = findViewById(R.id.compare_button);
        if (compareButton != null) {
            compareButton.setOnClickListener(v -> {
                startActivity(new Intent(this, ModelComparisonActivity.class));
            });
        }
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

    private void initAdapter() {
        if (modelRecyclerView != null) {
            modelAdapter = new ModelAdapter(models, action -> {
                switch (action.getType()) {
                    case SELECT:
                        handleModelSelect(action.getModel());
                        break;
                    case DOWNLOAD:
                        handleModelDownload(action.getModel());
                        break;
                    case DELETE:
                        handleModelDelete(action.getModel());
                        break;
                    case CONFIGURE:
                        handleModelConfigure(action.getModel());
                        break;
                    case TEST:
                        handleModelTest(action.getModel());
                        break;
                }
            });

            modelRecyclerView.setLayoutManager(new LinearLayoutManager(this));
            modelRecyclerView.setAdapter(modelAdapter);
        }
    }

    private void setupListeners() {
    }

    private void handleModelSelect(Model model) {
        // 取消其他模型的选择状态
        for (Model m : models) {
            m.setSelected(false);
        }
        // 设置当前模型为选中状态
        model.setSelected(true);
        modelAdapter.notifyDataSetChanged();
        // 可以在这里保存选中的模型
    }

    private void handleModelDownload(Model model) {
        // 模拟下载操作
        showLoading();
        new Thread(() -> {
            try {
                Thread.sleep(2000); // 模拟下载时间
                runOnUiThread(() -> {
                    hideLoading();
                    // 显示下载完成提示
                });
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void handleModelDelete(Model model) {
        models.remove(model);
        modelAdapter.notifyDataSetChanged();
    }

    private void handleModelConfigure(Model model) {
        // 打开配置界面
    }

    private void handleModelTest(Model model) {
        // 打开测试界面
    }

    private void showLoading() {
        if (loadingIndicator != null) {
            loadingIndicator.setVisibility(View.VISIBLE);
        }
    }

    private void hideLoading() {
        if (loadingIndicator != null) {
            loadingIndicator.setVisibility(View.GONE);
        }
    }
}