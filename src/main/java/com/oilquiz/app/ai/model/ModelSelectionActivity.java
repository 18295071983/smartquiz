package com.oilquiz.app.ai.model;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.inference.InferenceRouter;
import com.oilquiz.app.ai.service.AIService;

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

    private AIService aiService;
    private OnlineModelManager onlineModelManager;
    private InferenceRouter inferenceRouter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_model_selection);

        initViews();
        initModels();
        initAdapter();
        setupListeners();

        aiService = AIService.getInstance(this);
        onlineModelManager = OnlineModelManager.getInstance(this);
        inferenceRouter = InferenceRouter.getInstance(this);
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
        // 确保在主线程且RecyclerView不在布局计算时更新adapter
        safeNotifyAdapterChanged();

        // 停止当前活跃的在线模型
        OnlineModelManager.OnlineModelConfig activeOnline = onlineModelManager.getActiveModel();
        if (activeOnline != null) {
            onlineModelManager.stopActiveModel();
        }

        // 实际切换到选中的本地模型
        String modelName = model.getName();
        inferenceRouter.switchModel(modelName);
        Toast.makeText(this, "已切换到模型: " + modelName, Toast.LENGTH_SHORT).show();
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
        // 确保在主线程且RecyclerView不在布局计算时更新adapter
        safeNotifyAdapterChanged();
    }

    /**
     * 安全地更新RecyclerView adapter，避免在布局计算或滚动时更新导致崩溃
     */
    private void safeNotifyAdapterChanged() {
        if (modelRecyclerView == null || modelAdapter == null) {
            return;
        }
        final boolean isMainThread = Looper.myLooper() == Looper.getMainLooper();
        Runnable notifyRunnable = () -> {
            if (modelRecyclerView.isComputingLayout() || modelRecyclerView.isAnimating()) {
                // RecyclerView正在布局，延迟下一帧再更新
                modelRecyclerView.post(() -> {
                    if (modelAdapter != null) {
                        modelAdapter.notifyDataSetChanged();
                    }
                });
            } else {
                modelAdapter.notifyDataSetChanged();
            }
        };
        if (isMainThread) {
            notifyRunnable.run();
        } else {
            runOnUiThread(notifyRunnable);
        }
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