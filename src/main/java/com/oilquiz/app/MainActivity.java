package com.oilquiz.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import com.oilquiz.app.infra.AppLogger;
import com.oilquiz.app.resource.AppResourceManager;
import com.oilquiz.app.resource.SystemUIResourceAdapter;
import com.oilquiz.app.ui.base.BaseActivity;
import com.oilquiz.app.ui.widget.WeatherBannerView;

import com.oilquiz.app.ui.activity.UserActivity;
import com.oilquiz.app.ui.activity.QuestionActivity;
import com.oilquiz.app.ui.activity.QuizActivity;
import com.oilquiz.app.ui.activity.StartQuizActivity;
import com.oilquiz.app.ui.activity.StudyPlanActivity;
import com.oilquiz.app.ui.activity.WrongQuestionActivity;
import com.oilquiz.app.ui.activity.NoteActivity;
import com.oilquiz.app.ui.activity.OCRActivity;
import com.oilquiz.app.ui.activity.ImportActivity;
import com.oilquiz.app.ui.activity.ImportGuideActivity;
import com.oilquiz.app.ui.activity.QuestionGenerateActivity;
import com.oilquiz.app.ui.activity.EnvironmentCheckActivity;
import com.oilquiz.app.ui.activity.ExportActivity;
import com.oilquiz.app.ui.activity.BackupActivity;
import com.oilquiz.app.ui.activity.ThemeActivity;
import com.oilquiz.app.ui.activity.LanguageActivity;
import com.oilquiz.app.ui.activity.SimpleFilePreviewActivity;
import com.oilquiz.app.ui.activity.TestActivity;
import com.oilquiz.app.ui.activity.LogsActivity;
import com.oilquiz.app.ui.activity.AboutActivity;
import com.oilquiz.app.ui.activity.HistoryActivity;
import com.oilquiz.app.ui.activity.StatisticsActivity;
import com.oilquiz.app.ui.activity.DatabaseManagementActivity;
import com.oilquiz.app.ui.activity.AICenterActivity;
import com.oilquiz.app.ui.activity.ModelImportActivity;
import com.oilquiz.app.ui.activity.ModelSelectorActivity;
import com.oilquiz.app.ui.activity.AIServiceStatusActivity;
import com.oilquiz.app.ui.activity.ToolboxActivity;
import com.oilquiz.app.ai.service.AIService;

import java.io.File;

import com.oilquiz.app.WebViewActivity;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class MainActivity extends BaseActivity {

    private AppResourceManager resources;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_main;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        resources = AppResourceManager.getInstance(this);
        
        // 检查是否使用 WebView 视图
        boolean useWebView = resources.getConfigBoolean("home_view_web", false);
        if (useWebView) {
            // 如果使用 WebView 视图，先调用父类 onCreate
            super.onCreate(savedInstanceState);
            // 然后跳转到 WebViewActivity
            Intent intent = new Intent(this, WebViewActivity.class);
            startActivity(intent);
            finish();
            return;
        }
        
        // 否则正常调用父类 onCreate，加载原生界面
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void initView() {
        // 应用系统UI主题
        SystemUIResourceAdapter uiAdapter = SystemUIResourceAdapter.getInstance(this);
        uiAdapter.applySystemTheme(this);
    }

    @Override
    protected void initData() {
        // 检查是否使用WebView视图，如果是则不执行初始化操作
        boolean useWebView = resources.getConfigBoolean("home_view_web", false);
        if (useWebView) {
            return;
        }
        
        // 记录页面启动日志
        AppLogger.i("MainActivity", "主页面已启动");

        // 初始化模板数据
        initTemplates();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // 检查是否使用WebView视图，如果是则不执行更新操作
        boolean useWebView = resources.getConfigBoolean("home_view_web", false);
        if (useWebView) {
            return;
        }
        
        // 实时查询并更新AI服务状态
        updateAiStatus();
        // 实时查询并更新题库统计信息
        updateQuestionCount();
    }
    
    private void updateAiStatus() {
        new Thread(() -> {
            try {
                AIService aiService = AIService.getInstance(this);
                boolean isInitialized = aiService.isInitialized();
                String modelName = aiService.getCurrentModelName();
                
                runOnUiThread(() -> {
                    android.widget.TextView tvAiStatus = findViewById(R.id.tvAiStatus);
                    if (tvAiStatus != null) {
                        if (isInitialized && modelName != null) {
                            tvAiStatus.setText("AI运行中");
                            tvAiStatus.setTextColor(getResources().getColor(R.color.success));
                        } else {
                            tvAiStatus.setText("AI未初始化");
                            tvAiStatus.setTextColor(getResources().getColor(R.color.error));
                        }
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    android.widget.TextView tvAiStatus = findViewById(R.id.tvAiStatus);
                    if (tvAiStatus != null) {
                        tvAiStatus.setText("错误");
                        tvAiStatus.setTextColor(getResources().getColor(R.color.error));
                    }
                });
            }
        }).start();
    }
    
    private void updateQuestionCount() {
        new Thread(() -> {
            try {
                com.oilquiz.app.database.AppDatabase db = 
                    com.oilquiz.app.database.AppDatabase.getDatabase(this);
                int questionCount = db.questionDao().getQuestionCount();
                
                runOnUiThread(() -> {
                    android.widget.TextView tvQuestionCount = findViewById(R.id.tvQuestionCount);
                    if (tvQuestionCount != null) {
                        tvQuestionCount.setText(String.valueOf(questionCount));
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

@Override
    protected void initListener() {
        setupButtons();
    }

    private void initTemplates() {
        com.oilquiz.app.viewmodel.TemplateViewModel templateViewModel = 
            new com.oilquiz.app.viewmodel.TemplateViewModel(getApplication());
        
        // 检查是否已有模板
        templateViewModel.getTemplates(new com.oilquiz.app.viewmodel.TemplateViewModel.GetTemplatesCallback() {
            @Override
            public void onSuccess(java.util.List<com.oilquiz.app.model.Template> templates) {
                if (templates.isEmpty()) {
                    // 创建6个模板
                    createTemplate(templateViewModel, "讲义", "课程讲义模板", "lecture_notes.json");
                    createTemplate(templateViewModel, "小抄", "考试小抄模板", "cheat_sheet.json");
                    createTemplate(templateViewModel, "打印", "打印材料模板", "print_material.json");
                    createTemplate(templateViewModel, "背诵", "背诵内容模板", "recitation.json");
                    createTemplate(templateViewModel, "阅读", "阅读材料模板", "reading_material.json");
                    createTemplate(templateViewModel, "记忆", "记忆卡片模板", "memory_cards.json");
                }
            }

            @Override
            public void onFailure(String error) {
                // 处理错误
            }
        });
    }

    private void createTemplate(com.oilquiz.app.viewmodel.TemplateViewModel viewModel, String name, String description, String filePath) {
        com.oilquiz.app.model.Template template = new com.oilquiz.app.model.Template();
        template.setName(name);
        template.setDescription(description);
        // 设置正确的模板文件路径，指向 assets/templates/ 目录
        template.setFilePath("assets/templates/" + filePath);
        template.setCreatedAt(System.currentTimeMillis());
        template.setUpdatedAt(System.currentTimeMillis());
        template.setEnabled(true);
        
        viewModel.addTemplate(template, new com.oilquiz.app.viewmodel.TemplateViewModel.AddTemplateCallback() {
            @Override
            public void onSuccess() {
                // 模板创建成功
            }

            @Override
            public void onFailure(String error) {
                // 处理错误
            }
        });
    }

    private void setupButtons() {
        WeatherBannerView weatherBanner = findViewById(R.id.weather_banner);
        if (weatherBanner != null) {
            weatherBanner.setOnClickListener(v -> weatherBanner.onBannerClicked());
        }

        setupButton(R.id.btn_question, QuestionActivity.class);
        setupButton(R.id.btn_quiz, StartQuizActivity.class);
        setupButton(R.id.btn_study_plan, StudyPlanActivity.class);
        setupButton(R.id.btn_wrong_question, WrongQuestionActivity.class);
        setupButton(R.id.btn_note, NoteActivity.class);
        setupButton(R.id.btn_backup, BackupActivity.class);
        setupButton(R.id.btn_theme, ThemeActivity.class);
        setupButton(R.id.btn_history, HistoryActivity.class);
        setupButton(R.id.btn_about, AboutActivity.class);
        

        
        // 设置前端题目渲染界面按钮
        View btnFrontendView = findViewById(R.id.btn_frontend_view);
        if (btnFrontendView != null) {
            btnFrontendView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openQuestionRenderer();
                }
            });
        }
        
        // 设置AI功能中心按钮
        View btnAiCenter = findViewById(R.id.btn_ai_center);
        if (btnAiCenter != null) {
            btnAiCenter.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(MainActivity.this, AICenterActivity.class));
                }
            });
        }
        
        // 设置导入导出按钮
        View btnImportExport = findViewById(R.id.btn_import_export);
        if (btnImportExport != null) {
            btnImportExport.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // 弹出选择对话框
                    new android.app.AlertDialog.Builder(MainActivity.this)
                            .setTitle("选择操作")
                            .setItems(new String[]{"导入题目", "导出题目"}, (dialog, which) -> {
                                if (which == 0) {
                                    startActivity(new Intent(MainActivity.this, ImportGuideActivity.class));
                                } else {
                                    startActivity(new Intent(MainActivity.this, ExportActivity.class));
                                }
                            })
                            .setNegativeButton("取消", null)
                            .show();
                }
            });
        }
        
        // 设置语言设置按钮
        View btnLanguage = findViewById(R.id.btn_language);
        if (btnLanguage != null) {
            btnLanguage.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(MainActivity.this, LanguageActivity.class));
                }
            });
        }
        
        // 设置题目生成按钮
        View btnQuestionGenerate = findViewById(R.id.btn_question_generate);
        if (btnQuestionGenerate != null) {
            btnQuestionGenerate.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(MainActivity.this, QuestionGenerateActivity.class));
                }
            });
        }
        
        // 设置OCR按钮
        View btnOcr = findViewById(R.id.btn_ocr);
        if (btnOcr != null) {
            btnOcr.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(MainActivity.this, OCRActivity.class));
                }
            });
        }
        
        // 设置模型导入按钮
        View btnModelImport = findViewById(R.id.btn_model_import);
        if (btnModelImport != null) {
            btnModelImport.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(MainActivity.this, ModelImportActivity.class));
                }
            });
        }
        
        // 设置AI服务状态按钮
        View btnAiService = findViewById(R.id.btn_ai_service);
        if (btnAiService != null) {
            btnAiService.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(MainActivity.this, AIServiceStatusActivity.class));
                }
            });
        }
        
        // 设置系统日志按钮
        View btnLogs = findViewById(R.id.btn_logs);
        if (btnLogs != null) {
            btnLogs.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(MainActivity.this, LogsActivity.class));
                }
            });
        }
        
        // 设置工具集按钮
        View btnToolbox = findViewById(R.id.btn_toolbox);
        if (btnToolbox != null) {
            btnToolbox.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(MainActivity.this, ToolboxActivity.class));
                }
            });
        }
        
        // 设置数据库管理按钮
        View btnDatabaseManagement = findViewById(R.id.btn_database_management);
        if (btnDatabaseManagement != null) {
            btnDatabaseManagement.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(MainActivity.this, DatabaseManagementActivity.class));
                }
            });
        }
        
        // 设置原生检测按钮
        View btnSystemCheck = findViewById(R.id.btn_system_check);
        if (btnSystemCheck != null) {
            btnSystemCheck.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(MainActivity.this, EnvironmentCheckActivity.class));
                }
            });
        }
        

    }
    
    // 打开前端题目渲染界面
    private void openQuestionRenderer() {
        Intent intent = new Intent(this, WebViewActivity.class);
        intent.putExtra("url", "https://www.qweather.com");
        intent.putExtra("title", "天气详情");
        startActivity(intent);
    }

    private void setupButton(int buttonId, final Class<?> activityClass) {
        View cardView = findViewById(buttonId);
        if (cardView != null) {
            cardView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(MainActivity.this, activityClass));
                }
            });
        }
    }
    
    private void showEnvironmentInfo() {
        // 使用原生环境检测
        com.oilquiz.app.util.NativeEnvironmentChecker checker = new com.oilquiz.app.util.NativeEnvironmentChecker(this);
        com.oilquiz.app.util.NativeEnvironmentChecker.EnvironmentInfo info = checker.getFullEnvironmentInfo();

        StringBuilder environmentInfo = new StringBuilder();
        environmentInfo.append("╔══════════════════════════════════╗\n");
        environmentInfo.append("║       原生环境检测报告           ║\n");
        environmentInfo.append("╚══════════════════════════════════╝\n\n");

        // 设备信息
        environmentInfo.append("【设备信息】\n");
        environmentInfo.append("制造商: " + info.deviceInfo.manufacturer + "\n");
        environmentInfo.append("品牌: " + info.deviceInfo.brand + "\n");
        environmentInfo.append("型号: " + info.deviceInfo.model + "\n");
        environmentInfo.append("设备类型: " + info.deviceInfo.deviceType + "\n");
        environmentInfo.append("硬件: " + info.deviceInfo.hardware + "\n");
        environmentInfo.append("主板: " + info.deviceInfo.board + "\n\n");

        // 系统信息
        environmentInfo.append("【系统信息】\n");
        environmentInfo.append("Android版本: " + info.systemInfo.androidVersion + "\n");
        environmentInfo.append("SDK级别: " + info.systemInfo.sdkInt + "\n");
        environmentInfo.append("安全补丁: " + info.systemInfo.securityPatch + "\n");
        environmentInfo.append("语言: " + info.systemInfo.displayLanguage + "\n");
        environmentInfo.append("时区: " + info.systemInfo.timeZone + "\n");
        environmentInfo.append("是否Root: " + (info.systemInfo.isRooted ? "是" : "否") + "\n");
        environmentInfo.append("是否模拟器: " + (info.systemInfo.isEmulator ? "是" : "否") + "\n\n");

        // 屏幕信息
        environmentInfo.append("【屏幕信息】\n");
        environmentInfo.append("分辨率: " + info.screenInfo.widthPixels + " x " + info.screenInfo.heightPixels + "\n");
        environmentInfo.append("屏幕密度: " + info.screenInfo.densityDpi + " dpi\n");
        environmentInfo.append("屏幕尺寸: " + String.format(java.util.Locale.getDefault(), "%.2f", info.screenInfo.screenSizeInches) + " 英寸\n");
        environmentInfo.append("方向: " + info.screenInfo.orientation + "\n\n");

        // 内存信息
        environmentInfo.append("【内存信息】\n");
        environmentInfo.append("总内存: " + com.oilquiz.app.util.NativeEnvironmentChecker.formatBytes(info.memoryInfo.totalMemory) + "\n");
        environmentInfo.append("可用内存: " + com.oilquiz.app.util.NativeEnvironmentChecker.formatBytes(info.memoryInfo.availableMemory) + "\n");
        environmentInfo.append("使用率: " + info.memoryInfo.usagePercent + "%\n");
        environmentInfo.append("应用内存限制: " + info.memoryInfo.memoryClass + " MB\n");
        environmentInfo.append("低内存状态: " + (info.memoryInfo.lowMemory ? "是" : "否") + "\n\n");

        // 存储信息
        environmentInfo.append("【存储信息】\n");
        environmentInfo.append("内部存储总空间: " + com.oilquiz.app.util.NativeEnvironmentChecker.formatBytes(info.storageInfo.internalTotal) + "\n");
        environmentInfo.append("内部存储可用: " + com.oilquiz.app.util.NativeEnvironmentChecker.formatBytes(info.storageInfo.internalAvailable) + "\n");
        if (info.storageInfo.externalMounted) {
            environmentInfo.append("外部存储总空间: " + com.oilquiz.app.util.NativeEnvironmentChecker.formatBytes(info.storageInfo.externalTotal) + "\n");
            environmentInfo.append("外部存储可用: " + com.oilquiz.app.util.NativeEnvironmentChecker.formatBytes(info.storageInfo.externalAvailable) + "\n");
        }
        environmentInfo.append("应用文件大小: " + com.oilquiz.app.util.NativeEnvironmentChecker.formatBytes(info.storageInfo.appFilesSize) + "\n");
        environmentInfo.append("应用缓存大小: " + com.oilquiz.app.util.NativeEnvironmentChecker.formatBytes(info.storageInfo.appCacheSize) + "\n\n");

        // 应用信息
        environmentInfo.append("【应用信息】\n");
        environmentInfo.append("应用名称: " + info.appInfo.appName + "\n");
        environmentInfo.append("包名: " + info.appInfo.packageName + "\n");
        environmentInfo.append("版本: " + info.appInfo.versionName + " (" + info.appInfo.versionCode + ")\n");
        environmentInfo.append("目标SDK: " + info.appInfo.targetSdkVersion + "\n");
        environmentInfo.append("最小SDK: " + info.appInfo.minSdkVersion + "\n");
        environmentInfo.append("调试模式: " + (info.appInfo.isDebuggable ? "是" : "否") + "\n\n");

        // 硬件信息
        environmentInfo.append("【硬件信息】\n");
        environmentInfo.append("CPU架构: " + info.hardwareInfo.cpuAbi + "\n");
        if (info.hardwareInfo.supportedAbis != null && info.hardwareInfo.supportedAbis.length > 0) {
            environmentInfo.append("支持的ABI: " + String.join(", ", info.hardwareInfo.supportedAbis) + "\n");
        }
        environmentInfo.append("处理器数量: " + info.runtimeInfo.availableProcessors + "\n");
        environmentInfo.append("相机: " + (info.hardwareInfo.hasCamera ? "支持" : "不支持") + "\n");
        environmentInfo.append("GPS: " + (info.hardwareInfo.hasGPS ? "支持" : "不支持") + "\n");
        environmentInfo.append("NFC: " + (info.hardwareInfo.hasNFC ? "支持" : "不支持") + "\n");
        environmentInfo.append("蓝牙: " + (info.hardwareInfo.hasBluetooth ? "支持" : "不支持") + "\n");
        environmentInfo.append("WiFi: " + (info.hardwareInfo.hasWifi ? "支持" : "不支持") + "\n");

        new android.app.AlertDialog.Builder(this)
                .setTitle("原生环境检测")
                .setMessage(environmentInfo.toString())
                .setPositiveButton("确定", null)
                .setNeutralButton("复制", (dialog, which) -> {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    android.content.ClipData clip = android.content.ClipData.newPlainText("环境检测报告", environmentInfo.toString());
                    clipboard.setPrimaryClip(clip);
                    android.widget.Toast.makeText(this, "环境信息已复制", android.widget.Toast.LENGTH_SHORT).show();
                })
                .show();
    }
    
    private void runTests() {
        StringBuilder testResults = new StringBuilder();
        testResults.append("测试结果:\n\n");
        
        // 测试数据库连接
        try {
            com.oilquiz.app.database.AppDatabase db = com.oilquiz.app.database.AppDatabase.getDatabase(this);
            testResults.append("✓ 数据库连接正常\n");
        } catch (Exception e) {
            testResults.append("✗ 数据库连接失败: " + e.getMessage() + "\n");
        }
        
        // 测试存储权限
        com.oilquiz.app.resource.AppResourceManager resources = com.oilquiz.app.resource.AppResourceManager.getInstance(this);
        if (resources.hasStoragePermission()) {
            testResults.append("✓ 存储权限已授予\n");
        } else {
            testResults.append("✗ 存储权限未授予\n");
        }
        
        // 测试网络连接
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
        android.net.NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        if (activeNetwork != null && activeNetwork.isConnectedOrConnecting()) {
            testResults.append("✓ 网络连接正常\n");
        } else {
            testResults.append("✗ 网络连接失败\n");
        }
        
        new android.app.AlertDialog.Builder(this)
                .setTitle("测试结果")
                .setMessage(testResults.toString())
                .setPositiveButton("确定", null)
                .show();
    }
    
    private String getVersionName() {
        try {
            android.content.pm.PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            return packageInfo.versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "未知";
        }
    }
    
    private String getScreenResolution() {
        android.util.DisplayMetrics displayMetrics = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        return displayMetrics.widthPixels + "x" + displayMetrics.heightPixels;
    }
    
    private long getAvailableMemory() {
        android.app.ActivityManager.MemoryInfo memoryInfo = new android.app.ActivityManager.MemoryInfo();
        android.app.ActivityManager activityManager = (android.app.ActivityManager) getSystemService(android.content.Context.ACTIVITY_SERVICE);
        activityManager.getMemoryInfo(memoryInfo);
        return memoryInfo.availMem / 1024 / 1024;
    }
    
    private long getAvailableStorage() {
        android.os.StatFs stat = new android.os.StatFs(android.os.Environment.getExternalStorageDirectory().getPath());
        long blockSize = stat.getBlockSizeLong();
        long availableBlocks = stat.getAvailableBlocksLong();
        return availableBlocks * blockSize / 1024 / 1024;
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        AppResourceManager.getInstance(this).permissions().onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (resultCode == RESULT_OK && data != null) {
            android.net.Uri uri = data.getData();
            if (uri != null) {
                try {
                    // 将 Uri 转换为文件路径
                    String path = getPathFromUri(uri);
                    if (path != null) {
                        switch (requestCode) {
                            case 1004:
                                // LibreOffice 预览
                                com.oilquiz.app.ui.activity.LibreOfficeKitPreviewActivity.start(this, path);
                                break;
                            case 1005:
                                // OnlyOffice 预览
                                com.oilquiz.app.ui.activity.OnlyOfficePreviewActivity.start(this, path);
                                break;
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    android.widget.Toast.makeText(this, "处理文件失败: " + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
                }
            }
        }
    }
    
    /**
     * 从 Uri 获取文件路径
     */
    private String getPathFromUri(android.net.Uri uri) {
        try {
            if (uri.getScheme().equals("content")) {
                // 对于 content:// 类型的 Uri
                // 尝试多种方式获取文件路径
                String[] projections = {
                    android.provider.MediaStore.Images.Media.DATA,
                    android.provider.MediaStore.MediaColumns.DATA,
                    android.provider.MediaStore.Files.FileColumns.DATA
                };
                
                for (String projection : projections) {
                    try {
                        android.database.Cursor cursor = getContentResolver().query(uri, new String[]{projection}, null, null, null);
                        if (cursor != null) {
                            if (cursor.moveToFirst()) {
                                int columnIndex = cursor.getColumnIndexOrThrow(projection);
                                String path = cursor.getString(columnIndex);
                                cursor.close();
                                if (path != null && !path.isEmpty()) {
                                    return path;
                                }
                            }
                            cursor.close();
                        }
                    } catch (Exception e) {
                        // 尝试下一种方式
                    }
                }
                
                // 如果以上方法都失败，尝试使用临时文件方式
                return getPathFromContentUri(uri);
            } else if (uri.getScheme().equals("file")) {
                // 对于 file:// 类型的 Uri
                return uri.getPath();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
    
    /**
     * 从 content:// Uri 获取文件路径（通过创建临时文件）
     */
    private String getPathFromContentUri(android.net.Uri uri) {
        try {
            // 创建临时文件
            java.io.File tempFile = createTempFileFromUri(uri);
            if (tempFile != null) {
                return tempFile.getAbsolutePath();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
    
    /**
     * 从 Uri 创建临时文件
     */
    private java.io.File createTempFileFromUri(android.net.Uri uri) throws java.io.IOException {
        // 获取文件类型
        String mimeType = getContentResolver().getType(uri);
        String extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
        if (extension == null) {
            extension = "tmp";
        }
        
        // 创建临时文件
        java.io.File tempFile = java.io.File.createTempFile("preview_", "." + extension, getExternalFilesDir(null));
        tempFile.deleteOnExit();
        
        // 复制文件内容
        try (java.io.InputStream inputStream = getContentResolver().openInputStream(uri);
             java.io.FileOutputStream outputStream = new java.io.FileOutputStream(tempFile)) {
            byte[] buffer = new byte[1024];
            int length;
            while ((length = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, length);
            }
        }
        
        return tempFile;
    }
}
