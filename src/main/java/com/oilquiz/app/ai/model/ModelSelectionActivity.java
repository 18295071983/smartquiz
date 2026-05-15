package com.oilquiz.app.ai.model;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ModelSelectionActivity extends AppCompatActivity {

    private static final String TAG = "ModelSelectionActivity";
    private final ExecutorService simulationExecutor = Executors.newSingleThreadExecutor();

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
        List<ModelPresetConfig.ModelPreset> presets = ModelPresetConfig.loadPresets(this);
        for (ModelPresetConfig.ModelPreset preset : presets) {
            if (preset.id.matches("\\d+")) {
                models.add(ModelPresetConfig.toDisplayModel(preset));
            }
        }
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
        simulationExecutor.execute(() -> {
            try {
                Thread.sleep(2000);
                runOnUiThread(() -> {
                    hideLoading();
                });
            } catch (InterruptedException e) {
                Log.e(TAG, "Download simulation interrupted", e);
            }
        });
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