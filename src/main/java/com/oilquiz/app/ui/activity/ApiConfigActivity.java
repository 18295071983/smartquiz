package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.ui.base.BaseActivity;
import com.oilquiz.app.R;
import com.oilquiz.app.manager.ConfigManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ApiConfigActivity extends BaseActivity {

    private static final int REQUEST_CODE_PICK_CSV = 1001;

    // UI组件
    private EditText etApiKey;
    private MaterialButton btnSaveApiKey;
    private MaterialButton btnImportFromCsv;
    private MaterialButton btnOnlineConfig;

    // 配置管理器
    private ConfigManager configManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configManager = ConfigManager.getInstance(this);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_api_config;
    }

    @Override
    protected void initView() {
        // 设置Toolbar
        setupToolbar("API配置");

        // 初始化UI组件
        etApiKey = findViewById(R.id.et_api_key);
        btnSaveApiKey = findViewById(R.id.btn_save_api_key);
        btnImportFromCsv = findViewById(R.id.btn_import_from_csv);
        btnOnlineConfig = findViewById(R.id.btn_online_config);

        // 加载现有的API key
        loadExistingApiKey();
    }

    @Override
    protected void initData() {
        // 初始化数据
    }

    @Override
    protected void initListener() {
        // 保存API key按钮
        if (btnSaveApiKey != null) {
            btnSaveApiKey.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    saveApiKey();
                }
            });
        }

        // 从CSV导入按钮
        if (btnImportFromCsv != null) {
            btnImportFromCsv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pickCsvFile();
                }
            });
        }

        // 在线配置按钮
        if (btnOnlineConfig != null) {
            btnOnlineConfig.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startOnlineConfig();
                }
            });
        }
    }

    // 加载现有的API key
    private void loadExistingApiKey() {
        try {
            // 从配置中获取API key
            String apiKey = "";
            // 由于ConfigManager没有直接获取Map的方法，我们可以使用其他方式获取
            // 这里暂时留空，因为API功能已经移除
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 保存API key
    private void saveApiKey() {
        try {
            String apiKey = etApiKey.getText().toString().trim();
            if (apiKey.isEmpty()) {
                Toast.makeText(this, "请输入API Key", Toast.LENGTH_SHORT).show();
                return;
            }

            // 保存到配置管理器
            Map<String, String> apiConfig = new HashMap<>();
            apiConfig.put("api_key", apiKey);
            configManager.updateConfig("api_config", apiConfig);

            Toast.makeText(this, "API Key 保存成功", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // 选择CSV文件
    private void pickCsvFile() {
        // 浏览应用内部存储中的CSV文件
        showFileBrowser();
    }

    // 显示文件浏览器
    private void showFileBrowser() {
        try {
            // 获取应用文件目录
            java.io.File filesDir = getFilesDir();
            if (filesDir == null) {
                Toast.makeText(this, "无法访问文件目录", Toast.LENGTH_SHORT).show();
                return;
            }

            // 查找所有CSV文件
            java.util.List<java.io.File> csvFiles = findCsvFiles(filesDir);
            if (csvFiles.isEmpty()) {
                Toast.makeText(this, "未找到CSV文件", Toast.LENGTH_SHORT).show();
                return;
            }

            // 显示文件选择对话框
            showFileSelectionDialog(csvFiles);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "浏览文件失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // 查找CSV文件
    private java.util.List<java.io.File> findCsvFiles(java.io.File directory) {
        java.util.List<java.io.File> csvFiles = new java.util.ArrayList<>();
        if (directory == null || !directory.exists() || !directory.isDirectory()) {
            return csvFiles;
        }

        java.io.File[] files = directory.listFiles();
        if (files != null) {
            for (java.io.File file : files) {
                if (file.isDirectory()) {
                    // 递归搜索子目录
                    csvFiles.addAll(findCsvFiles(file));
                } else if (file.getName().toLowerCase().endsWith(".csv")) {
                    csvFiles.add(file);
                }
            }
        }
        return csvFiles;
    }

    // 显示文件选择对话框
    private void showFileSelectionDialog(java.util.List<java.io.File> csvFiles) {
        // 提取文件名
        java.util.List<String> fileNames = new java.util.ArrayList<>();
        for (java.io.File file : csvFiles) {
            fileNames.add(file.getName());
        }

        // 创建对话框
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("选择CSV文件");
        builder.setItems(fileNames.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface dialog, int which) {
                java.io.File selectedFile = csvFiles.get(which);
                // 解析选中的CSV文件
                parseCsvFileFromInternalStorage(selectedFile);
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    // 解析应用内部存储中的CSV文件
    private void parseCsvFileFromInternalStorage(java.io.File file) {
        try {
            // 读取CSV文件内容
            StringBuilder csvContent = new StringBuilder();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) {
                csvContent.append(line).append("\n");
            }
            reader.close();

            // 解析CSV数据
            java.util.List<java.util.Map<String, String>> apiConfigs = parseCsvContent(csvContent.toString());
            if (apiConfigs.isEmpty()) {
                Toast.makeText(this, "CSV文件中没有有效的API配置", Toast.LENGTH_SHORT).show();
                return;
            }

            // 保存解析到的配置
            saveApiConfigs(apiConfigs);
            Toast.makeText(this, "CSV文件解析成功，共导入 " + apiConfigs.size() + " 个API配置", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "解析CSV文件失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // 开始在线配置
    private void startOnlineConfig() {
        // 显示加载对话框
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setMessage("正在获取在线配置...");
        builder.setCancelable(false);
        final android.app.AlertDialog dialog = builder.create();
        dialog.show();

        // 异步获取在线配置
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 模拟网络请求获取配置
                    final Map<String, String> onlineConfig = fetchOnlineConfig();
                    
                    // 在UI线程更新
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            dialog.dismiss();
                            if (onlineConfig != null && !onlineConfig.isEmpty()) {
                                // 应用在线配置
                                applyOnlineConfig(onlineConfig);
                                Toast.makeText(ApiConfigActivity.this, "在线配置更新成功", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(ApiConfigActivity.this, "获取在线配置失败", Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            dialog.dismiss();
                            Toast.makeText(ApiConfigActivity.this, "网络请求失败", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        }).start();
    }

    // 获取在线配置
    private Map<String, String> fetchOnlineConfig() throws Exception {
        // 这里模拟从服务器获取配置
        // 实际项目中应该使用OkHttp或Retrofit进行网络请求
        Thread.sleep(1000); // 模拟网络延迟
        
        Map<String, String> config = new HashMap<>();
        config.put("api_key", "online_api_key_example");
        config.put("api_url", "https://api.example.com/v1");
        config.put("model_name", "gpt-4");
        config.put("timeout", "30");
        
        return config;
    }

    // 应用在线配置
    private void applyOnlineConfig(Map<String, String> config) {
        try {
            // 更新API Key
            String apiKey = config.get("api_key");
            if (apiKey != null && !apiKey.isEmpty()) {
                etApiKey.setText(apiKey);
                saveApiKey();
            }

            // 保存完整配置
            configManager.updateConfig("online_api_config", config);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_PICK_CSV && resultCode == RESULT_OK && data != null) {
            // 处理CSV文件选择
            handleCsvFile(data);
        }
    }

    // 处理CSV文件
    private void handleCsvFile(Intent data) {
        try {
            if (data != null && data.getData() != null) {
                android.net.Uri uri = data.getData();
                // 解析CSV文件
                parseCsvFile(uri);
            } else {
                Toast.makeText(this, "文件选择失败", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "处理文件失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // 解析CSV文件
    private void parseCsvFile(android.net.Uri uri) {
        try {
            // 读取CSV文件内容
            StringBuilder csvContent = new StringBuilder();
            android.content.ContentResolver contentResolver = getContentResolver();
            java.io.InputStream inputStream = contentResolver.openInputStream(uri);
            if (inputStream != null) {
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(inputStream));
                String line;
                while ((line = reader.readLine()) != null) {
                    csvContent.append(line).append("\n");
                }
                reader.close();
                inputStream.close();
            }

            // 解析CSV数据
            List<Map<String, String>> apiConfigs = parseCsvContent(csvContent.toString());
            if (apiConfigs.isEmpty()) {
                Toast.makeText(this, "CSV文件中没有有效的API配置", Toast.LENGTH_SHORT).show();
                return;
            }

            // 保存解析到的配置
            saveApiConfigs(apiConfigs);
            Toast.makeText(this, "CSV文件解析成功，共导入 " + apiConfigs.size() + " 个API配置", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "解析CSV文件失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // 解析CSV内容
    private List<Map<String, String>> parseCsvContent(String csvContent) {
        List<Map<String, String>> configs = new ArrayList<>();
        try {
            String[] lines = csvContent.split("\n");
            if (lines.length < 2) {
                return configs; // 至少需要表头和一行数据
            }

            // 解析表头
            String[] headers = lines[0].split(",");
            if (headers.length < 2) {
                return configs; // 至少需要两列：名称和API Key
            }

            // 解析数据行
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;

                String[] values = line.split(",");
                if (values.length < 2) continue;

                Map<String, String> config = new HashMap<>();
                for (int j = 0; j < Math.min(headers.length, values.length); j++) {
                    config.put(headers[j].trim(), values[j].trim());
                }
                configs.add(config);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return configs;
    }

    // 保存API配置
    private void saveApiConfigs(List<Map<String, String>> apiConfigs) {
        try {
            // 保存到配置管理器
            configManager.updateConfig("api_configs", apiConfigs);

            // 如果只有一个配置，自动设置为当前API Key
            if (apiConfigs.size() == 1) {
                Map<String, String> config = apiConfigs.get(0);
                String apiKey = config.get("api_key") != null ? config.get("api_key") : 
                               config.get("API Key") != null ? config.get("API Key") : 
                               config.get("key") != null ? config.get("key") : "";
                
                if (!apiKey.isEmpty()) {
                    etApiKey.setText(apiKey);
                    saveApiKey();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
