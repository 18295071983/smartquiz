package com.oilquiz.app.ui.activity;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import android.widget.RadioButton;
import android.widget.RadioGroup;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.ai.model.APIConfig;
import com.oilquiz.app.ai.model.ApiModel;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.model.UsageInfo;
import com.oilquiz.app.ai.service.ModelListFetcher;
import com.oilquiz.app.ai.service.UsageTracker;
import com.oilquiz.app.ai.util.APIConfigParser;
import com.oilquiz.app.ai.util.APIKeyManager;
import com.oilquiz.app.ui.base.BaseActivity;
import com.oilquiz.app.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public class ApiConfigActivity extends BaseActivity {

    private static final String TAG = "ApiConfigActivity";
    private static final int REQUEST_CODE_IMPORT_FILE = 1001;
    private static final int REQUEST_CODE_EXPORT_FILE = 1002;
    private static final int REQUEST_CODE_PERMISSION = 1003;
    private static final int REQUEST_CODE_SELECT_FOLDER = 1004;
    private static final int REQUEST_CODE_IMPORT_CONFIG_FILE = 1005;

    private EditText etSearch;
    private MaterialButton btnAdd;
    private LinearLayout llApiList;
    private LinearLayout llEmpty;
    private MaterialButton btnImportJson;
    private MaterialButton btnExportJson;
    private MaterialButton btnImportConfigFile;
    private MaterialButton btnScanArchive;
    private MaterialButton btnSetArchivePath;
    private MaterialButton btnTestAll;

    private EditText pendingPathEditText;

    private APIKeyManager apiKeyManager;
    private OnlineModelManager onlineModelManager;
    private ModelListFetcher modelListFetcher;
    private UsageTracker usageTracker;
    private Handler mainHandler;
    private List<APIConfig> allConfigs;
    private List<APIConfig> filteredConfigs;

    private final String[] serviceTypes = {
        "OpenAI", "Anthropic", "Google",
        "和风天气", "Bing Search", "Google Maps", "自定义"
    };
    
    private final String[] serviceTypeValues = {
        APIConfig.ServiceType.OPENAI, APIConfig.ServiceType.ANTHROPIC,
        APIConfig.ServiceType.GOOGLE,
        APIConfig.ServiceType.HEFENG_WEATHER, APIConfig.ServiceType.BING_SEARCH,
        APIConfig.ServiceType.GOOGLE_MAPS, APIConfig.ServiceType.CUSTOM
    };

    private final String[] categories = {
        "AI服务", "天气服务", "搜索服务", "地图服务", "其他"
    };
    
    private final String[] categoryValues = {
        APIConfig.Category.AI, APIConfig.Category.WEATHER,
        APIConfig.Category.SEARCH, APIConfig.Category.MAPS,
        APIConfig.Category.OTHER
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        apiKeyManager = APIKeyManager.getInstance(this);
        modelListFetcher = ModelListFetcher.getInstance(this);
        usageTracker = UsageTracker.getInstance(this);
        mainHandler = new Handler(Looper.getMainLooper());
        allConfigs = new ArrayList<>();
        filteredConfigs = new ArrayList<>();
        super.onCreate(savedInstanceState);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_api_config;
    }

    @Override
    protected void initView() {
        setupToolbar("API配置管理");

        etSearch = findViewById(R.id.et_search);
        btnAdd = findViewById(R.id.btn_add);
        llApiList = findViewById(R.id.ll_api_list);
        llEmpty = findViewById(R.id.ll_empty);
        btnImportJson = findViewById(R.id.btn_import_json);
        btnExportJson = findViewById(R.id.btn_export_json);
        btnImportConfigFile = findViewById(R.id.btn_import_config_file);
        btnScanArchive = findViewById(R.id.btn_scan_archive);
        btnSetArchivePath = findViewById(R.id.btn_set_archive_path);
        btnTestAll = findViewById(R.id.btn_test_all);
    }

    @Override
    protected void initData() {
        loadApiConfigs();
    }

    @Override
    protected void initListener() {
        btnAdd.setOnClickListener(v -> showEditDialog(null));

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterConfigs(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        btnImportJson.setOnClickListener(v -> importFromJson());
        btnExportJson.setOnClickListener(v -> exportToJson());
        btnImportConfigFile.setOnClickListener(v -> importFromConfigFile());
        btnScanArchive.setOnClickListener(v -> scanArchiveDirectory());
        btnSetArchivePath.setOnClickListener(v -> showArchivePathDialog());
        btnTestAll.setOnClickListener(v -> testAllConfigs());
    }

    private void loadApiConfigs() {
        if (apiKeyManager == null) {
            apiKeyManager = APIKeyManager.getInstance(this);
        }
        if (onlineModelManager == null) {
            onlineModelManager = OnlineModelManager.getInstance(this);
        }
        allConfigs = apiKeyManager.getAllAPIConfigs();
        if (allConfigs == null) {
            allConfigs = new ArrayList<>();
        }
        filteredConfigs.clear();
        filteredConfigs.addAll(allConfigs);
        refreshApiList();
    }

    private void filterConfigs(String query) {
        ensureListsInitialized();
        filteredConfigs.clear();
        if (query == null || query.isEmpty()) {
            filteredConfigs.addAll(allConfigs);
        } else {
            String lowerQuery = query.toLowerCase();
            for (APIConfig config : allConfigs) {
                if (config.getName() != null && config.getName().toLowerCase().contains(lowerQuery) ||
                    config.getServiceType() != null && config.getServiceType().toLowerCase().contains(lowerQuery) ||
                    config.getDescription() != null && config.getDescription().toLowerCase().contains(lowerQuery)) {
                    filteredConfigs.add(config);
                }
            }
        }
        refreshApiList();
    }

    private void refreshApiList() {
        ensureListsInitialized();
        llApiList.removeAllViews();

        if (filteredConfigs.isEmpty()) {
            llEmpty.setVisibility(View.VISIBLE);
            return;
        }

        llEmpty.setVisibility(View.GONE);

        for (APIConfig config : filteredConfigs) {
            View itemView = LayoutInflater.from(this).inflate(R.layout.item_api_config, llApiList, false);
            bindApiConfigItem(itemView, config);
            llApiList.addView(itemView);
        }
    }

    private void ensureListsInitialized() {
        if (allConfigs == null) {
            allConfigs = new ArrayList<>();
        }
        if (filteredConfigs == null) {
            filteredConfigs = new ArrayList<>();
        }
    }

    private void bindApiConfigItem(View itemView, APIConfig config) {
        TextView tvName = itemView.findViewById(R.id.tv_api_name);
        TextView tvServiceType = itemView.findViewById(R.id.tv_service_type);
        TextView tvApiKey = itemView.findViewById(R.id.tv_api_key);
        TextView tvApiHost = itemView.findViewById(R.id.tv_api_host);
        TextView tvModelName = itemView.findViewById(R.id.tv_model_name);
        TextView tvStatus = itemView.findViewById(R.id.tv_status);
        TextView tvUsage = itemView.findViewById(R.id.tv_usage);
        TextView tvLatency = itemView.findViewById(R.id.tv_latency);
        TextView tvLastUsed = itemView.findViewById(R.id.tv_last_used);
        TextView tvUsagePercent = itemView.findViewById(R.id.tv_usage_percent);
        TextView tvUsageDetail = itemView.findViewById(R.id.tv_usage_detail);
        View statusIndicator = itemView.findViewById(R.id.status_indicator);
        LinearLayout llHost = itemView.findViewById(R.id.ll_api_host);
        LinearLayout llModel = itemView.findViewById(R.id.ll_model_name);
        LinearLayout llUsage = itemView.findViewById(R.id.ll_usage);
        ProgressBar pbUsage = itemView.findViewById(R.id.pb_usage);

        MaterialButton btnTest = itemView.findViewById(R.id.btn_test);
        MaterialButton btnEdit = itemView.findViewById(R.id.btn_edit);
        MaterialButton btnDelete = itemView.findViewById(R.id.btn_delete);

        // 显示格式：服务商 + 模型名称
        String serviceType = getServiceTypeDisplay(config.getServiceType());
        String modelName = config.getModelName();
        String displayName;
        
        if (modelName != null && !modelName.isEmpty()) {
            displayName = serviceType + " · " + modelName;
        } else {
            displayName = config.getName() != null ? config.getName() : serviceType;
        }
        
        tvName.setText(displayName);
        tvServiceType.setText(serviceType);
        tvApiKey.setText(config.getMaskedApiKey());

        if (config.getApiHost() != null && !config.getApiHost().isEmpty()) {
            tvApiHost.setText(config.getApiHost());
            llHost.setVisibility(View.VISIBLE);
        } else {
            llHost.setVisibility(View.GONE);
        }

        if (config.getModelName() != null && !config.getModelName().isEmpty()) {
            tvModelName.setText(config.getModelName());
            llModel.setVisibility(View.VISIBLE);
        } else {
            llModel.setVisibility(View.GONE);
        }

        tvStatus.setText(getStatusDisplay(config.getStatus()));
        tvUsage.setText("使用: " + config.getUseCount() + "次");

        updateStatusIndicator(statusIndicator, config.getStatus());

        // 显示延迟（如果有）
        if (config.getLastUsedAt() > 0) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
            tvLastUsed.setText("最后使用: " + sdf.format(new Date(config.getLastUsedAt())));
            tvLastUsed.setVisibility(View.VISIBLE);
        } else {
            tvLastUsed.setVisibility(View.GONE);
        }

        // 显示使用量进度
        if (usageTracker != null) {
            String configId = config.getId();
            UsageTracker.UsageInfo usageInfo = usageTracker.getUsageInfo(configId);
            if (usageInfo != null && usageInfo.totalQuota > 0) {
                int progress = (int) ((usageInfo.usedQuota * 100) / usageInfo.totalQuota);
                pbUsage.setProgress(progress);
                tvUsagePercent.setText(progress + "%");
                tvUsageDetail.setText(String.format(Locale.getDefault(), "%.2f / %.2f",
                    usageInfo.usedQuota / 1000000.0, usageInfo.totalQuota / 1000000.0));
                llUsage.setVisibility(View.VISIBLE);
            } else {
                llUsage.setVisibility(View.GONE);
            }
        }

        btnTest.setOnClickListener(v -> testSingleConfig(config));
        btnEdit.setOnClickListener(v -> {
            Log.d("ApiConfigActivity", "编辑按钮点击，config=" + (config != null ? config.getName() : "null"));
            if (config != null && config.getId() != null) {
                showEditDialog(config);
            } else {
                Log.e("ApiConfigActivity", "无法编辑：配置对象为空或ID为空");
                Toast.makeText(this, "无法编辑：配置信息不完整", Toast.LENGTH_SHORT).show();
            }
        });
        btnDelete.setOnClickListener(v -> confirmDelete(config));
    }

    private void updateStatusIndicator(View indicator, String status) {
        if (indicator == null) return;
        
        int bgRes;
        switch (status != null ? status : APIConfig.Status.UNKNOWN) {
            case APIConfig.Status.VALID:
                bgRes = android.R.color.holo_green_dark;
                break;
            case APIConfig.Status.INVALID:
            case APIConfig.Status.EXPIRED:
                bgRes = android.R.color.holo_red_dark;
                break;
            case APIConfig.Status.RATE_LIMITED:
                bgRes = android.R.color.holo_orange_dark;
                break;
            default:
                bgRes = android.R.color.darker_gray;
                break;
        }
        indicator.setBackgroundColor(ContextCompat.getColor(this, bgRes));
    }

    private String getServiceTypeDisplay(String type) {
        for (int i = 0; i < serviceTypeValues.length; i++) {
            if (serviceTypeValues[i].equals(type)) {
                return serviceTypes[i];
            }
        }
        return "自定义";
    }

    private String getStatusDisplay(String status) {
        switch (status != null ? status : APIConfig.Status.UNKNOWN) {
            case APIConfig.Status.VALID:
                return "有效";
            case APIConfig.Status.INVALID:
                return "无效";
            case APIConfig.Status.EXPIRED:
                return "已过期";
            case APIConfig.Status.RATE_LIMITED:
                return "频率受限";
            default:
                return "未知";
        }
    }

    private void showEditDialog(@Nullable APIConfig existingConfig) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(existingConfig == null ? "添加API配置" : "编辑API配置");

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_api_config_edit, null);
        EditText etName = dialogView.findViewById(R.id.et_name);
        Spinner spinnerService = dialogView.findViewById(R.id.spinner_service_type);
        EditText etApiKey = dialogView.findViewById(R.id.et_api_key);
        EditText etApiHost = dialogView.findViewById(R.id.et_api_host);
        EditText etModelName = dialogView.findViewById(R.id.et_model_name);
        Spinner spinnerCategory = dialogView.findViewById(R.id.spinner_category);
        EditText etTimeout = dialogView.findViewById(R.id.et_timeout);
        EditText etDescription = dialogView.findViewById(R.id.et_description);

        // 新增的UI元素
        MaterialButton btnTestConnection = dialogView.findViewById(R.id.btn_test_connection);
        MaterialButton btnFetchModels = dialogView.findViewById(R.id.btn_fetch_models);
        LinearLayout llConnectionStatus = dialogView.findViewById(R.id.ll_connection_status);
        View statusIndicator = dialogView.findViewById(R.id.status_indicator);
        TextView tvConnectionStatus = dialogView.findViewById(R.id.tv_connection_status);
        ProgressBar pbTesting = dialogView.findViewById(R.id.pb_testing);
        LinearLayout llModelSection = dialogView.findViewById(R.id.ll_model_section);
        Spinner spinnerModel = dialogView.findViewById(R.id.spinner_model);
        TextView tvModelList = dialogView.findViewById(R.id.tv_model_list);
        LinearLayout llUsageSection = dialogView.findViewById(R.id.ll_usage_section);
        ProgressBar pbUsage = dialogView.findViewById(R.id.pb_usage);
        TextView tvUsagePercent = dialogView.findViewById(R.id.tv_usage_percent);
        TextView tvUsageDetail = dialogView.findViewById(R.id.tv_usage_detail);

        // 当前正在编辑的配置（用于测试和获取模型）
        APIConfig[] currentConfig = {existingConfig};

        ArrayAdapter<String> serviceAdapter = new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_dropdown_item, serviceTypes);
        spinnerService.setAdapter(serviceAdapter);

        ArrayAdapter<String> categoryAdapter = new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_dropdown_item, categories);
        spinnerCategory.setAdapter(categoryAdapter);

        if (existingConfig != null) {
            if (etName != null) {
                etName.setText(existingConfig.getName());
            }
            if (etApiKey != null) {
                etApiKey.setText(existingConfig.getApiKey());
            }
            if (etApiHost != null) {
                etApiHost.setText(existingConfig.getApiHost());
            }
            if (etModelName != null) {
                etModelName.setText(existingConfig.getModelName());
            }
            if (etTimeout != null) {
                etTimeout.setText(String.valueOf(existingConfig.getTimeout()));
            }
            if (etDescription != null) {
                etDescription.setText(existingConfig.getDescription());
            }

            if (spinnerService != null) {
                for (int i = 0; i < serviceTypeValues.length; i++) {
                    if (serviceTypeValues[i].equals(existingConfig.getServiceType())) {
                        spinnerService.setSelection(i);
                        break;
                    }
                }
            }

            if (spinnerCategory != null) {
                for (int i = 0; i < categoryValues.length; i++) {
                    if (categoryValues[i].equals(existingConfig.getCategory())) {
                        spinnerCategory.setSelection(i);
                        break;
                    }
                }
            }

            // 显示使用量信息
            if (llUsageSection != null) {
                llUsageSection.setVisibility(View.VISIBLE);
                updateUsageDisplay(llUsageSection, pbUsage, tvUsagePercent, tvUsageDetail, existingConfig.getId());
            }
        }

        // 测试连通性按钮
        if (btnTestConnection != null) {
            btnTestConnection.setOnClickListener(v -> {
                if (etApiKey == null || etApiHost == null || spinnerService == null) {
                    return;
                }
                
                String apiKey = etApiKey.getText().toString().trim();
                String apiHost = etApiHost.getText().toString().trim();
                String serviceType = serviceTypeValues[spinnerService.getSelectedItemPosition()];

                if (apiKey.isEmpty()) {
                    Toast.makeText(this, "请先输入API Key", Toast.LENGTH_SHORT).show();
                    return;
                }

                if (llConnectionStatus != null) {
                    llConnectionStatus.setVisibility(View.VISIBLE);
                }
                if (tvConnectionStatus != null) {
                    tvConnectionStatus.setText("正在测试连接...");
                }
                if (pbTesting != null) {
                    pbTesting.setVisibility(View.VISIBLE);
                }
                btnTestConnection.setEnabled(false);

                // 创建临时配置用于测试
                APIConfig testConfig = existingConfig != null ? existingConfig : new APIConfig();
                testConfig.setApiKey(apiKey);
                testConfig.setApiHost(apiHost);
                testConfig.setServiceType(serviceType);

                apiKeyManager.testAPIConnection(testConfig).thenAccept(result -> runOnUiThread(() -> {
                    if (pbTesting != null) {
                        pbTesting.setVisibility(View.GONE);
                    }
                    btnTestConnection.setEnabled(true);

                    if (result.success) {
                        if (tvConnectionStatus != null) {
                            tvConnectionStatus.setText("连接成功! 延迟: " + result.latency + "ms");
                        }
                        if (statusIndicator != null) {
                            statusIndicator.setBackgroundResource(R.drawable.status_indicator_valid);
                        }
                        // 更新配置状态
                        if (currentConfig[0] != null) {
                            currentConfig[0].setStatus(APIConfig.Status.VALID);
                        }
                    } else {
                        if (tvConnectionStatus != null) {
                            tvConnectionStatus.setText("连接失败: " + result.message);
                        }
                        if (statusIndicator != null) {
                            statusIndicator.setBackgroundResource(R.drawable.status_indicator_invalid);
                        }
                        if (currentConfig[0] != null) {
                            currentConfig[0].setStatus(APIConfig.Status.INVALID);
                        }
                    }
                }));
            });
        }

        // 获取模型列表按钮
        if (btnFetchModels != null) {
            btnFetchModels.setOnClickListener(v -> {
                if (etApiKey == null || etApiHost == null || spinnerService == null) {
                    return;
                }
                
                String apiKey = etApiKey.getText().toString().trim();
                String apiHost = etApiHost.getText().toString().trim();
                String serviceType = serviceTypeValues[spinnerService.getSelectedItemPosition()];

                if (apiKey.isEmpty()) {
                    Toast.makeText(this, "请先输入API Key", Toast.LENGTH_SHORT).show();
                    return;
                }

                if (llModelSection != null) {
                    llModelSection.setVisibility(View.VISIBLE);
                }
                if (tvModelList != null) {
                    tvModelList.setText("正在获取模型列表...");
                }
                btnFetchModels.setEnabled(false);

                modelListFetcher.fetchModels(apiHost, apiKey)
                    .thenAccept(models -> runOnUiThread(() -> {
                        btnFetchModels.setEnabled(true);

                        if (models != null && !models.isEmpty()) {
                            List<String> modelNames = new ArrayList<>();
                            for (ApiModel model : models) {
                                modelNames.add(model.getName());
                            }

                            ArrayAdapter<String> modelAdapter = new ArrayAdapter<>(
                                ApiConfigActivity.this,
                                android.R.layout.simple_spinner_dropdown_item,
                                modelNames);
                            if (spinnerModel != null) {
                                spinnerModel.setAdapter(modelAdapter);
                            }

                            if (tvModelList != null) {
                                tvModelList.setText("可用模型：" + models.size() + " 个");
                            }

                            // 自动选择当前配置的模型
                            if (existingConfig != null && existingConfig.getModelName() != null && spinnerModel != null) {
                                for (int i = 0; i < modelNames.size(); i++) {
                                    if (modelNames.get(i).equals(existingConfig.getModelName())) {
                                        spinnerModel.setSelection(i);
                                        break;
                                    }
                                }
                            }
                        } else {
                            if (tvModelList != null) {
                                tvModelList.setText("未获取到模型列表");
                            }
                        }
                    }))
                    .exceptionally(e -> {
                        runOnUiThread(() -> {
                            btnFetchModels.setEnabled(true);
                            if (tvModelList != null) {
                                tvModelList.setText("获取失败: " + e.getMessage());
                            }
                        });
                        return null;
                    });
            });
        }

        // 模型选择监听
        if (spinnerModel != null) {
            spinnerModel.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    if (spinnerModel == null) return;
                    String selectedModel = (String) spinnerModel.getSelectedItem();
                    if (selectedModel != null && !selectedModel.startsWith("获取") && etModelName != null) {
                        etModelName.setText(selectedModel);
                    }
                }

                @Override
                public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            });
        }

        // 服务类型变化时隐藏模型区域
        if (spinnerService != null) {
            spinnerService.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    String serviceType = serviceTypeValues[position];
                    // 只有AI服务类型显示模型选择
                    if (llModelSection == null) return;
                    if (APIConfig.ServiceType.OPENAI.equals(serviceType) ||
                        APIConfig.ServiceType.ANTHROPIC.equals(serviceType) ||
                        APIConfig.ServiceType.GOOGLE.equals(serviceType)) {
                        if (llModelSection.getVisibility() != View.VISIBLE) {
                            llModelSection.setVisibility(View.VISIBLE);
                        }
                    } else {
                        llModelSection.setVisibility(View.GONE);
                    }
                }

                @Override
                public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            });
        }

        builder.setView(dialogView);
        builder.setPositiveButton("保存", (dialog, which) -> {
            if (etName == null || etApiKey == null || etApiHost == null || 
                etModelName == null || etTimeout == null || etDescription == null ||
                spinnerService == null || spinnerCategory == null) {
                Toast.makeText(this, "初始化失败，请重试", Toast.LENGTH_SHORT).show();
                return;
            }
            
            String name = etName.getText().toString().trim();
            String apiKey = etApiKey.getText().toString().trim();

            if (name.isEmpty() || apiKey.isEmpty()) {
                Toast.makeText(this, "名称和API Key不能为空", Toast.LENGTH_SHORT).show();
                return;
            }

            APIConfig config = existingConfig != null ? existingConfig : new APIConfig();
            config.setName(name);
            config.setServiceType(serviceTypeValues[spinnerService.getSelectedItemPosition()]);
            config.setApiKey(apiKey);
            config.setApiHost(etApiHost.getText().toString().trim());
            config.setModelName(etModelName.getText().toString().trim());
            config.setCategory(categoryValues[spinnerCategory.getSelectedItemPosition()]);
            config.setDescription(etDescription.getText().toString().trim());

            String timeoutStr = etTimeout.getText().toString().trim();
            if (!timeoutStr.isEmpty()) {
                try {
                    config.setTimeout(Integer.parseInt(timeoutStr));
                } catch (NumberFormatException e) {
                    config.setTimeout(30);
                }
            }

            apiKeyManager.saveAPIConfig(config);

            // 同步单个配置到在线模型管理器（增量同步：新增/更新/转非AI时移除）
            try {
                boolean changed = onlineModelManager.syncSingleAPIConfig(config.getId());
                if (changed) {
                    Log.d(TAG, "已同步配置到在线模型管理器: " + config.getName());
                }
            } catch (Exception e) {
                Log.e(TAG, "同步到在线模型管理器失败: " + e.getMessage());
            }

            loadApiConfigs();
            Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show();
        });

        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void updateUsageDisplay(LinearLayout llUsage, ProgressBar pbUsage, 
            TextView tvPercent, TextView tvDetail, String configId) {
        if (usageTracker != null && configId != null && !configId.isEmpty()) {
            UsageTracker.UsageInfo info = usageTracker.getUsageInfo(configId);
            if (info != null && info.totalQuota > 0) {
                int progress = (int) ((info.usedQuota * 100) / info.totalQuota);
                pbUsage.setProgress(progress);
                tvPercent.setText(progress + "%");
                tvDetail.setText(String.format(Locale.getDefault(), 
                    "%.2fM / %.2fM tokens", 
                    info.usedQuota / 1000000.0, 
                    info.totalQuota / 1000000.0));
                llUsage.setVisibility(View.VISIBLE);
            } else {
                llUsage.setVisibility(View.GONE);
            }
        }
    }

    private void confirmDelete(APIConfig config) {
        new AlertDialog.Builder(this)
            .setTitle("确认删除")
            .setMessage("确定要删除 \"" + config.getName() + "\" 吗？此操作不可撤销。")
            .setPositiveButton("删除", (dialog, which) -> {
                String configId = config.getId();
                apiKeyManager.deleteAPIConfig(configId);
                // 同步删除 OnlineModelManager 中对应的配置
                try {
                    onlineModelManager.removeByAPIConfigId(configId);
                } catch (Exception e) {
                    Log.e(TAG, "同步删除在线模型配置失败: " + e.getMessage());
                }
                loadApiConfigs();
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void testSingleConfig(APIConfig config) {
        Toast.makeText(this, "正在测试 " + config.getName() + "...", Toast.LENGTH_SHORT).show();
        
        apiKeyManager.testAPIConnection(config)
            .thenAccept(result -> runOnUiThread(() -> {
                String message = result.success ? 
                    "测试成功! 延迟: " + result.latency + "ms" : 
                    "测试失败: " + result.message;
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                loadApiConfigs();
            }));
    }

    private void testAllConfigs() {
        if (filteredConfigs.isEmpty()) {
            Toast.makeText(this, "没有可测试的配置", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "开始批量测试 " + filteredConfigs.size() + " 个配置...", Toast.LENGTH_SHORT).show();

        List<CompletableFuture<APIKeyManager.TestResult>> futures = new ArrayList<>();
        for (APIConfig config : filteredConfigs) {
            futures.add(apiKeyManager.testAPIConnection(config));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenRun(() -> runOnUiThread(() -> {
                int successCount = 0;
                for (CompletableFuture<APIKeyManager.TestResult> future : futures) {
                    try {
                        APIKeyManager.TestResult result = future.get();
                        if (result.success) successCount++;
                    } catch (Exception ignored) {}
                }
                loadApiConfigs();
                Toast.makeText(this, 
                    "批量测试完成: " + successCount + "/" + futures.size() + " 成功", 
                    Toast.LENGTH_LONG).show();
            }));
    }

    private void importFromJson() {
        if (!checkPermission()) {
            requestPermission();
            return;
        }

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("application/json");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, "选择JSON文件"), REQUEST_CODE_IMPORT_FILE);
    }

    private void exportToJson() {
        if (!checkPermission()) {
            requestPermission();
            return;
        }

        if (allConfigs.isEmpty()) {
            Toast.makeText(this, "没有可导出的配置", Toast.LENGTH_SHORT).show();
            return;
        }

        String json = apiKeyManager.exportToJson();
        String fileName = "api_configs_" + 
            new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".json";

        try {
            File exportDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File exportFile = new File(exportDir, fileName);
            
            try (OutputStreamWriter writer = new OutputStreamWriter(
                openFileOutput(fileName, MODE_PRIVATE))) {
                writer.write(json);
            }

            Toast.makeText(this, "已导出到: " + exportFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "导出失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void importFromConfigFile() {
        if (!checkPermission()) {
            requestPermission();
            return;
        }

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, "选择配置文件"), REQUEST_CODE_IMPORT_CONFIG_FILE);
    }

    private void scanArchiveDirectory() {
        if (!checkPermission()) {
            requestPermission();
            return;
        }

        String archivePath = apiKeyManager.getArchivePath();
        apiKeyManager.ensureArchiveDirectoryExists();

        Toast.makeText(this, "正在扫描存档目录...\n" + archivePath, Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            List<APIConfig> foundConfigs = apiKeyManager.scanArchiveDirectory(archivePath);
            
            runOnUiThread(() -> {
                if (foundConfigs.isEmpty()) {
                    new AlertDialog.Builder(this)
                        .setTitle("扫描结果")
                        .setMessage("未在存档目录中找到API配置文件\n\n路径：" + archivePath)
                        .setPositiveButton("确定", null)
                        .setNeutralButton("修改路径", (dialog, which) -> {
                            showArchivePathDialog();
                        })
                        .show();
                    return;
                }

                new AlertDialog.Builder(this)
                    .setTitle("发现API配置")
                    .setMessage("在存档目录中发现 " + foundConfigs.size() + " 个API配置，是否导入？\n\n路径：" + archivePath)
                    .setPositiveButton("导入", (dialog, which) -> {
                        for (APIConfig config : foundConfigs) {
                            apiKeyManager.saveAPIConfig(config);
                            // 同步单个配置到在线模型管理器
                            try {
                                onlineModelManager.syncSingleAPIConfig(config.getId());
                            } catch (Exception e) {
                                Log.e(TAG, "同步到在线模型管理器失败: " + e.getMessage());
                            }
                        }
                        loadApiConfigs();
                        Toast.makeText(this, "已导入 " + foundConfigs.size() + " 个配置", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
            });
        }).start();
    }

    private void showArchivePathDialog() {
        String currentPath = apiKeyManager.getArchivePath();
        String defaultPath = apiKeyManager.getDefaultArchivePath();
        boolean isUsingDefault = apiKeyManager.isUsingDefaultArchivePath();
        
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("存档路径设置");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 24, 32, 24);

        TextView tvInfo = new TextView(this);
        tvInfo.setText("设置API配置文件的存档目录路径");
        tvInfo.setTextSize(14);
        tvInfo.setPadding(0, 0, 0, 16);
        layout.addView(tvInfo);

        TextView tvCurrent = new TextView(this);
        String currentLabel = isUsingDefault ? "当前路径（默认）：" : "当前路径（自定义）：";
        tvCurrent.setText(currentLabel);
        tvCurrent.setTextSize(12);
        tvCurrent.setPadding(0, 8, 0, 4);
        layout.addView(tvCurrent);

        TextView tvCurrentPath = new TextView(this);
        tvCurrentPath.setText(currentPath);
        tvCurrentPath.setTextSize(12);
        tvCurrentPath.setPadding(16, 0, 0, 16);
        tvCurrentPath.setTextColor(0xFF666666);
        layout.addView(tvCurrentPath);

        TextView tvInputLabel = new TextView(this);
        tvInputLabel.setText("输入新路径（自定义）：");
        tvInputLabel.setTextSize(12);
        tvInputLabel.setPadding(0, 8, 0, 4);
        layout.addView(tvInputLabel);

        LinearLayout inputLayout = new LinearLayout(this);
        inputLayout.setOrientation(LinearLayout.HORIZONTAL);
        inputLayout.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        EditText etPath = new EditText(this);
        etPath.setHint(defaultPath);
        etPath.setSingleLine(true);
        etPath.setPadding(16, 12, 16, 12);
        LinearLayout.LayoutParams etParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        etPath.setLayoutParams(etParams);
        inputLayout.addView(etPath);

        MaterialButton btnBrowse = new MaterialButton(this);
        btnBrowse.setText("选择");
        btnBrowse.setCornerRadius(8);
        btnBrowse.setPadding(20, 0, 20, 0);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        btnParams.setMargins(16, 0, 0, 0);
        btnBrowse.setLayoutParams(btnParams);
        btnBrowse.setOnClickListener(v -> {
            openFolderPicker(etPath);
        });
        inputLayout.addView(btnBrowse);

        layout.addView(inputLayout);

        if (!isUsingDefault) {
            TextView tvResetInfo = new TextView(this);
            tvResetInfo.setText("\n点击\"恢复默认\"可使用应用自动创建的目录");
            tvResetInfo.setTextSize(11);
            tvResetInfo.setPadding(0, 12, 0, 0);
            tvResetInfo.setTextColor(0xFF999999);
            layout.addView(tvResetInfo);
        }

        builder.setView(layout);
        
        builder.setPositiveButton("保存", (dialog, which) -> {
            String path = etPath.getText().toString().trim();
            if (path.isEmpty()) {
                apiKeyManager.resetToDefaultArchivePath();
                apiKeyManager.ensureArchiveDirectoryExists();
                Toast.makeText(this, "已恢复默认存档路径", Toast.LENGTH_SHORT).show();
            } else {
                apiKeyManager.setArchivePath(path);
                apiKeyManager.ensureArchiveDirectoryExists();
                Toast.makeText(this, "存档路径已保存", Toast.LENGTH_SHORT).show();
            }
        });

        if (!isUsingDefault) {
            builder.setNeutralButton("恢复默认", (dialog, which) -> {
                apiKeyManager.resetToDefaultArchivePath();
                apiKeyManager.ensureArchiveDirectoryExists();
                Toast.makeText(this, "已恢复默认存档路径", Toast.LENGTH_SHORT).show();
            });
        }

        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void openFolderPicker(EditText etPath) {
        if (!checkPermission()) {
            requestPermission();
            return;
        }
        
        try {
            pendingPathEditText = etPath;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, REQUEST_CODE_SELECT_FOLDER);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开文件夹选择器: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private String getRealPathFromTreeUri(Uri treeUri) {
        if (treeUri == null) {
            return null;
        }

        String path = treeUri.getPath();
        if (path != null && path.startsWith("/tree/")) {
            String treeId = path.substring(6);
            
            if (treeId.contains(":")) {
                String[] parts = treeId.split(":", 2);
                String storageType = parts[0];
                String relativePath = parts[1];
                
                if ("primary".equalsIgnoreCase(storageType)) {
                    return Environment.getExternalStorageDirectory() + "/" + relativePath;
                }
                
                try {
                    java.io.File[] externalDirs = getExternalFilesDirs(null);
                    if (externalDirs != null && externalDirs.length > 0) {
                        for (java.io.File dir : externalDirs) {
                            if (dir != null) {
                                String dirPath = dir.getAbsolutePath();
                                int androidIndex = dirPath.indexOf("/Android/");
                                if (androidIndex > 0) {
                                    String storageRoot = dirPath.substring(0, androidIndex);
                                    if (storageRoot.contains(storageType)) {
                                        return storageRoot + "/" + relativePath;
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                }
            }
        }
        
        return null;
    }

    private boolean checkPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return ContextCompat.checkSelfPermission(this, 
                Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, 
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                Toast.makeText(this, "请授予\"所有文件访问\"权限", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                try {
                    Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivity(intent);
                } catch (Exception ex) {
                    Toast.makeText(this, "无法打开权限设置页面", Toast.LENGTH_SHORT).show();
                }
            }
        } else {
            ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, REQUEST_CODE_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "权限已授予", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "需要存储权限才能导入导出文件", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_CODE_IMPORT_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(getContentResolver().openInputStream(uri)));
                    StringBuilder jsonBuilder = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        jsonBuilder.append(line);
                    }
                    reader.close();

                    int imported = apiKeyManager.importFromJson(jsonBuilder.toString());
                    // 同步到在线模型管理器
                    try {
                        onlineModelManager.syncFromAPIKeyManager();
                    } catch (Exception e) {
                        Log.e(TAG, "同步到在线模型管理器失败: " + e.getMessage());
                    }
                    loadApiConfigs();
                    Toast.makeText(this, "已导入 " + imported + " 个新配置", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "导入失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        } else if (requestCode == REQUEST_CODE_SELECT_FOLDER && resultCode == RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            if (treeUri != null) {
                final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | 
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                getContentResolver().takePersistableUriPermission(treeUri, takeFlags);
                
                String path = getRealPathFromTreeUri(treeUri);
                if (path != null && pendingPathEditText != null) {
                    pendingPathEditText.setText(path);
                    Toast.makeText(this, "已选择路径: " + path, Toast.LENGTH_SHORT).show();
                } else if (path != null) {
                    apiKeyManager.setArchivePath(path);
                    apiKeyManager.ensureArchiveDirectoryExists();
                    Toast.makeText(this, "存档路径已设置: " + path, Toast.LENGTH_SHORT).show();
                }
                pendingPathEditText = null;
            }
        } else if (requestCode == REQUEST_CODE_IMPORT_CONFIG_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(getContentResolver().openInputStream(uri)));
                    StringBuilder content = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        content.append(line).append("\n");
                    }
                    reader.close();

                    APIConfigParser.ParseResult parseResult = APIConfigParser.parse(content.toString());
                    
                    if (parseResult.configs.isEmpty()) {
                        String errorMsg = parseResult.errorMessage != null ? 
                            parseResult.errorMessage : "未检测到有效的API配置";
                        Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show();
                    } else {
                        showImportPreviewDialog(parseResult);
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "读取文件失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private void showImportPreviewDialog(APIConfigParser.ParseResult parseResult) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("导入预览");

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_import_preview, null);
        
        TextView tvDetectedFormat = dialogView.findViewById(R.id.tv_detected_format);
        TextView tvConfigCount = dialogView.findViewById(R.id.tv_config_count);
        RadioGroup rgConfigs = dialogView.findViewById(R.id.rg_configs);
        LinearLayout llNoConfig = dialogView.findViewById(R.id.ll_no_config);
        LinearLayout llPreview = dialogView.findViewById(R.id.ll_preview);
        TextView tvErrorMessage = dialogView.findViewById(R.id.tv_error_message);
        TextView tvPreviewServiceType = dialogView.findViewById(R.id.tv_preview_service_type);
        TextView tvPreviewApiKey = dialogView.findViewById(R.id.tv_preview_api_key);
        TextView tvPreviewApiHost = dialogView.findViewById(R.id.tv_preview_api_host);
        TextView tvPreviewModel = dialogView.findViewById(R.id.tv_preview_model);

        tvDetectedFormat.setText(parseResult.detectedFormat);
        tvConfigCount.setText("检测到 " + parseResult.configs.size() + " 个配置");

        if (parseResult.configs.isEmpty()) {
            llNoConfig.setVisibility(View.VISIBLE);
            llPreview.setVisibility(View.GONE);
            tvErrorMessage.setText(parseResult.errorMessage);
        } else {
            llNoConfig.setVisibility(View.GONE);
            llPreview.setVisibility(View.VISIBLE);

            for (int i = 0; i < parseResult.configs.size(); i++) {
                APIConfig config = parseResult.configs.get(i);
                RadioButton radioButton = new RadioButton(this);
                radioButton.setText(config.getName() + " (" + getServiceTypeDisplay(config.getServiceType()) + ")");
                radioButton.setTag(config);
                radioButton.setPadding(8, 8, 8, 8);
                rgConfigs.addView(radioButton);
                
                if (i == 0) {
                    radioButton.setChecked(true);
                    tvPreviewServiceType.setText(getServiceTypeDisplay(config.getServiceType()));
                    tvPreviewApiKey.setText(config.getMaskedApiKey());
                    String apiHost = config.getApiHost();
                    tvPreviewApiHost.setText(apiHost != null && !apiHost.isEmpty() ? apiHost : "未设置");
                    String modelName = config.getModelName();
                    tvPreviewModel.setText(modelName != null && !modelName.isEmpty() ? modelName : "未设置");
                }
            }

            rgConfigs.setOnCheckedChangeListener((group, checkedId) -> {
                RadioButton selected = group.findViewById(checkedId);
                if (selected != null) {
                    APIConfig config = (APIConfig) selected.getTag();
                    tvPreviewServiceType.setText(getServiceTypeDisplay(config.getServiceType()));
                    tvPreviewApiKey.setText(config.getMaskedApiKey());
                    String apiHost = config.getApiHost();
                    tvPreviewApiHost.setText(apiHost != null && !apiHost.isEmpty() ? apiHost : "未设置");
                    String modelName = config.getModelName();
                    tvPreviewModel.setText(modelName != null && !modelName.isEmpty() ? modelName : "未设置");
                }
            });
        }

        builder.setView(dialogView);
        builder.setPositiveButton("导入", (dialog, which) -> {
            if (!parseResult.configs.isEmpty()) {
                int selectedId = rgConfigs.getCheckedRadioButtonId();
                RadioButton selected = rgConfigs.findViewById(selectedId);
                if (selected != null) {
                    APIConfig config = (APIConfig) selected.getTag();
                    apiKeyManager.saveAPIConfig(config);
                    // 同步单个配置到在线模型管理器
                    try {
                        onlineModelManager.syncSingleAPIConfig(config.getId());
                    } catch (Exception e) {
                        Log.e(TAG, "同步到在线模型管理器失败: " + e.getMessage());
                    }
                    loadApiConfigs();
                    Toast.makeText(this, "已导入: " + config.getName(), Toast.LENGTH_SHORT).show();
                }
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }
}
