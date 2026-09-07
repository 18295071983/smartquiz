package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.oilquiz.app.resource.AppResourceManager;
import com.oilquiz.app.resource.PermissionResourceProvider;
import java.util.List;

import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.model.ModelManager;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.util.ModelFileSelector;

public class ModelImportActivity extends AppCompatActivity {
    
    private static final String TAG = "ModelImportActivity";
    private static final int FILE_PICKER_REQUEST = 1001;
    private static final int PERMISSION_REQUEST_CODE = 1002;
    
    private ModelManager modelManager;
    private TextView modelPathTextView;
    private MaterialButton selectModelButton;
    private MaterialButton importModelButton;
    private TextView importStatusTextView;
    private TextView importMessageTextView;
    private MaterialButton viewModelsButton;
    private MaterialButton importAnotherButton;
    private MaterialButton goDownloadButton;
    private LinearLayout progressContainer;
    private TextView importProgressTextView;
    private ProgressBar progressBar;
    private TextView progressPercentageTextView;
    
    private Uri selectedModelUri;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_model_import);
        
        try {
            // 初始化ModelManager
            modelManager = new ModelManager(this);
            
            // 初始化UI组件
            modelPathTextView = findViewById(R.id.tv_model_path);
            selectModelButton = findViewById(R.id.btn_select_model);
            importModelButton = findViewById(R.id.btn_import_model);
            importStatusTextView = findViewById(R.id.tv_import_status);
            importMessageTextView = findViewById(R.id.tv_import_message);
            viewModelsButton = findViewById(R.id.btn_view_models);
            importAnotherButton = findViewById(R.id.btn_import_another);
            goDownloadButton = findViewById(R.id.btn_go_download);
            progressContainer = findViewById(R.id.progress_container);
            importProgressTextView = findViewById(R.id.tv_import_progress);
            progressBar = findViewById(R.id.progress_bar);
            progressPercentageTextView = findViewById(R.id.tv_progress_percentage);
            
            // 检查权限
            checkPermissions();
            
            // 设置按钮点击事件
            if (selectModelButton != null) {
                selectModelButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // 再次检查权限
                        if (checkPermissions()) {
                            selectModelFile();
                        }
                    }
                });
            }
            
            if (importModelButton != null) {
                importModelButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        loadSelectedModel();
                    }
                });
            }
            
            if (viewModelsButton != null) {
                viewModelsButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // 查看模型列表
                        Intent intent = new Intent(ModelImportActivity.this, ModelSelectorActivity.class);
                        startActivity(intent);
                    }
                });
            }
            
            if (importAnotherButton != null) {
                importAnotherButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // 导入另一个模型
                        selectedModelUri = null;
                        if (modelPathTextView != null) {
                            modelPathTextView.setText(getString(R.string.h_46edf8b7));
                        }
                        View resultContainer = findViewById(R.id.result_container);
                        View actionsContainer = findViewById(R.id.actions_container);
                        if (resultContainer != null) {
                            resultContainer.setVisibility(View.GONE);
                        }
                        if (actionsContainer != null) {
                            actionsContainer.setVisibility(View.GONE);
                        }
                    }
                });
            }
            
            if (goDownloadButton != null) {
                goDownloadButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Intent intent = new Intent(ModelImportActivity.this, ModelDownloadActivity.class);
                        startActivity(intent);
                    }
                });
            }
            
            // 显示当前可用的模型
            updateModelStatus();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, getString(R.string.h_58c10e4c) + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    
    /**
     * 检查并请求存储权限
     * @return 是否已经获得权限
     */
    private boolean checkPermissions() {
        AppResourceManager resources = AppResourceManager.getInstance(this);
        if (!resources.hasStoragePermission()) {
            resources.permissions().requestStoragePermission(this, new PermissionResourceProvider.PermissionCallback() {
                @Override
                public void onGranted() {
                    Toast.makeText(ModelImportActivity.this, getString(R.string.h_3587abc9), Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onDenied(List<String> deniedPermissions) {
                    Toast.makeText(ModelImportActivity.this, getString(R.string.h_5e9761c4), Toast.LENGTH_SHORT).show();
                }
            });
            return false;
        }
        return true;
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        AppResourceManager.getInstance(this).permissions().onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
    
    /**
     * 选择模型文件
     */
    private void selectModelFile() {
        Intent intent = ModelFileSelector.createModelFilePickerIntent();
        startActivityForResult(intent, FILE_PICKER_REQUEST);
    }
    
    /**
     * 加载选中的模型
     */
    private void loadSelectedModel() {
        if (selectedModelUri == null) {
            Toast.makeText(this, getString(R.string.h_1a309dc1), Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (!ModelFileSelector.isValidModelUri(this, selectedModelUri)) {
            Toast.makeText(this, getString(R.string.h_3e077148), Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 禁用按钮，防止重复点击
        importModelButton.setEnabled(false);
        
        // 显示进度容器，隐藏其他容器
        runOnUiThread(() -> {
            progressContainer.setVisibility(View.VISIBLE);
            findViewById(R.id.result_container).setVisibility(View.GONE);
            findViewById(R.id.actions_container).setVisibility(View.GONE);
            // 重置进度
            progressBar.setProgress(0);
            progressPercentageTextView.setText("0%");
            importProgressTextView.setText(getString(R.string.h_853df94f));
        });
        
        // 在后台线程中加载模型
        new Thread(() -> {
            try {
                String modelName = modelManager.loadModelFromUri(selectedModelUri, new ModelFileSelector.ProgressCallback() {
                    @Override
                    public void onProgress(int progress) {
                        // 在UI线程更新进度
                        runOnUiThread(() -> {
                            progressBar.setProgress(progress);
                            progressPercentageTextView.setText(progress + "%");
                        });
                    }
                    
                    @Override
                    public void onComplete() {
                        // 导入完成，由主线程统一处理
                    }
                    
                    @Override
                    public void onError(String error) {
                        // 错误信息由主线程统一处理
                    }
                });
                
                // 在UI线程更新结果
                runOnUiThread(() -> {
                    // 隐藏进度容器
                    progressContainer.setVisibility(View.GONE);
                    
                    if (modelName != null) {
                        findViewById(R.id.result_container).setVisibility(View.VISIBLE);
                        findViewById(R.id.actions_container).setVisibility(View.VISIBLE);
                        importStatusTextView.setText(getString(R.string.h_1eea18ce));
                        importMessageTextView.setText(getString(R.string.h_5cf01c1b));

                        // 自动切换到新导入的模型（使用热切换）
                        AIService aiService = AIService.getInstance(ModelImportActivity.this);
                        aiService.hotSwitchModel(modelName, new AIService.HotSwitchCallback() {
                            @Override
                            public void onSwitchStarted(String fromModel, String toModel) {
                                runOnUiThread(() -> {
                                    Toast.makeText(ModelImportActivity.this,
                                        getString(R.string.h_0239f57c) + toModel, Toast.LENGTH_SHORT).show();
                                });
                            }

                            @Override
                            public void onSwitchProgress(int progress, String message) {
                                // 进度更新
                            }

                            @Override
                            public void onSwitchCompleted(boolean success, String model) {
                                runOnUiThread(() -> {
                                    if (success) {
                                        Toast.makeText(ModelImportActivity.this,
                                            getString(R.string.h_0056ac68) + model, Toast.LENGTH_SHORT).show();
                                    } else {
                                        Toast.makeText(ModelImportActivity.this,
                                            getString(R.string.h_b9c8e7b7), Toast.LENGTH_SHORT).show();
                                    }
                                });
                            }

                            @Override
                            public void onSwitchFailed(String reason) {
                                runOnUiThread(() -> {
                                    Toast.makeText(ModelImportActivity.this,
                                        getString(R.string.h_70a7d4d9) + reason, Toast.LENGTH_SHORT).show();
                                });
                            }
                        });
                        
                        updateModelStatus();
                    } else {
                        findViewById(R.id.result_container).setVisibility(View.VISIBLE);
                        findViewById(R.id.actions_container).setVisibility(View.VISIBLE);
                        importStatusTextView.setText(getString(R.string.h_8f35e073));
                        importMessageTextView.setText(getString(R.string.h_5eb96784));
                        Toast.makeText(this, getString(R.string.h_5eb96784), Toast.LENGTH_SHORT).show();
                    }
                    importModelButton.setEnabled(true);
                });
            } catch (Exception e) {
                // 在UI线程更新错误信息
                runOnUiThread(() -> {
                    // 隐藏进度容器
                    progressContainer.setVisibility(View.GONE);
                    
                    findViewById(R.id.result_container).setVisibility(View.VISIBLE);
                    findViewById(R.id.actions_container).setVisibility(View.VISIBLE);
                    importStatusTextView.setText(getString(R.string.h_506ba504));
                    importMessageTextView.setText(getString(R.string.h_2f0f752e) + e.getMessage());
                    Toast.makeText(this, getString(R.string.h_2f0f752e) + e.getMessage(), Toast.LENGTH_SHORT).show();
                    importModelButton.setEnabled(true);
                });
            }
        }).start();
    }
    
    /**
     * 更新模型状态
     */
    private void updateModelStatus() {
        try {
            String[] availableModels = modelManager.listAvailableModels();
            if (modelPathTextView != null) {
                if (availableModels != null && availableModels.length > 0) {
                    StringBuilder status = new StringBuilder("可用模型:\n");
                    for (String model : availableModels) {
                        long size = modelManager.getModelSize(model);
                        status.append(model).append(" (").append(formatFileSize(size)).append(")\n");
                    }
                    modelPathTextView.setText(status.toString());
                } else {
                    modelPathTextView.setText(getString(R.string.h_2d6b332e));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            if (modelPathTextView != null) {
                modelPathTextView.setText(getString(R.string.h_11c16161));
            }
        }
    }
    
    /**
     * 格式化文件大小
     */
    private String formatFileSize(long size) {
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return (size / 1024) + " KB";
        } else if (size < 1024 * 1024 * 1024) {
            return (size / (1024 * 1024)) + " MB";
        } else {
            return (size / (1024 * 1024 * 1024)) + " GB";
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == FILE_PICKER_REQUEST && resultCode == RESULT_OK) {
            if (data != null) {
                Uri uri = data.getData();
                if (uri != null) {
                    selectedModelUri = uri;
                    String fileName = ModelFileSelector.getFileNameFromUri(this, uri);
                    if (fileName != null) {
                        modelPathTextView.setText(getString(R.string.h_ad674f46) + fileName);
                        Toast.makeText(this, getString(R.string.h_1359960a) + fileName, Toast.LENGTH_SHORT).show();
                        Log.i(TAG, "Selected model file: " + fileName + " (Uri: " + uri + ")");
                    } else {
                        modelPathTextView.setText(getString(R.string.h_ea2e8bc2));
                        Toast.makeText(this, getString(R.string.h_ea2e8bc2), Toast.LENGTH_SHORT).show();
                        Log.e(TAG, "Failed to get file name from Uri: " + uri);
                    }
                }
            }
        }
    }
}
