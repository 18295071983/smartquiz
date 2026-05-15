package com.oilquiz.app.ui.activity;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ui.adapter.ModelAdapter;

import java.util.ArrayList;
import java.util.List;

public class ModelSelectorActivity extends AppCompatActivity implements ModelAdapter.OnModelClickListener {

    private static final String TAG = "ModelSelectorActivity";
    private static final int REQUEST_IMPORT_MODEL = 1002;

    private AIService aiService;
    private OnlineModelManager onlineModelManager;
    private RecyclerView modelsRecycler;
    private RecyclerView onlineModelsRecycler;
    private ModelAdapter modelAdapter;
    private TextView currentModelNameTextView;
    private MaterialButton refreshButton;
    private MaterialButton importButton;
    private MaterialButton addOnlineModelButton;
    private TextView onlineModelsSectionTitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_model_selector);

        try {
            aiService = AIService.getInstance(this);
            onlineModelManager = OnlineModelManager.getInstance(this);

            currentModelNameTextView = findViewById(R.id.current_model_name);
            modelsRecycler = findViewById(R.id.models_recycler);
            refreshButton = findViewById(R.id.refresh_button);
            importButton = findViewById(R.id.import_button);

            View toolbar = findViewById(R.id.toolbar);
            if (toolbar != null) {
                toolbar.setOnClickListener(v -> finish());
            }

            if (refreshButton != null) {
                refreshButton.setOnClickListener(v -> refreshModels());
            }
            if (importButton != null) {
                importButton.setOnClickListener(v -> importModel());
            }

            refreshModels();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "初始化失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void refreshModels() {
        try {
            String[] availableModels = aiService.getAvailableModels();
            List<String> modelList = new ArrayList<>();
            if (availableModels != null) {
                for (String model : availableModels) {
                    modelList.add(model);
                }
            }

            String currentModel = aiService.getCurrentModelName();
            if (currentModelNameTextView != null) {
                if (currentModel != null) {
                    currentModelNameTextView.setText(currentModel);
                } else {
                    currentModelNameTextView.setText("未初始化");
                }
            }

            if (modelsRecycler != null) {
                if (modelAdapter == null) {
                    modelAdapter = new ModelAdapter(this, modelList, currentModel, this);
                    modelsRecycler.setAdapter(modelAdapter);
                } else {
                    modelAdapter.updateData(modelList, currentModel);
                }
            }

            if (modelList.isEmpty()) {
                Toast.makeText(this, "暂无可用模型，请先导入模型", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "刷新模型列表失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void importModel() {
        Intent intent = new Intent(this, ModelImportActivity.class);
        startActivityForResult(intent, REQUEST_IMPORT_MODEL);
    }

    private void showAddOnlineModelDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("添加在线模型");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 10);

        final EditText nameInput = new EditText(this);
        nameInput.setHint("模型名称 (如: GPT-4)");
        layout.addView(nameInput);

        final EditText urlInput = new EditText(this);
        urlInput.setHint("API地址 (如: https://api.openai.com/v1)");
        layout.addView(urlInput);

        final EditText modelInput = new EditText(this);
        modelInput.setHint("模型标识 (如: gpt-4)");
        layout.addView(modelInput);

        final EditText keyInput = new EditText(this);
        keyInput.setHint("API Key");
        layout.addView(keyInput);

        builder.setView(layout);

        builder.setPositiveButton("添加", (dialog, which) -> {
            String name = nameInput.getText().toString().trim();
            String url = urlInput.getText().toString().trim();
            String model = modelInput.getText().toString().trim();
            String key = keyInput.getText().toString().trim();

            if (name.isEmpty() || url.isEmpty() || model.isEmpty()) {
                Toast.makeText(this, "请填写必要信息", Toast.LENGTH_SHORT).show();
                return;
            }

            onlineModelManager.addModel(name, url, model, key);
            Toast.makeText(this, "在线模型已添加: " + name, Toast.LENGTH_SHORT).show();
        });

        builder.setNegativeButton("取消", null);
        builder.show();
    }

    @Override
    public void onModelClick(String modelName) {
        Toast.makeText(this, "正在切换到模型: " + modelName, Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            boolean success = aiService.switchModel(modelName);

            runOnUiThread(() -> {
                if (success) {
                    Toast.makeText(this, "模型切换成功: " + modelName, Toast.LENGTH_SHORT).show();
                    refreshModels();
                } else {
                    Toast.makeText(this, "模型切换失败", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMPORT_MODEL) {
            refreshModels();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshModels();
    }
}
