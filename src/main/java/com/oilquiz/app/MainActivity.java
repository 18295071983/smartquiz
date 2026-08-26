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
import com.oilquiz.app.ai.service.AIServiceState;
import com.oilquiz.app.ai.inference.InferenceRouter;
import com.oilquiz.app.ai.model.OnlineModelManager;

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
        
        // 天气横幅：超过5分钟自动刷新
        WeatherBannerView weatherBanner = findViewById(R.id.weather_banner);
        if (weatherBanner != null) {
            weatherBanner.onResume();
        }

        // 注册AI状态观察者，监听状态变化
        registerAiStatusObserver();
        
        // 实时查询并更新AI服务状态
        updateAiStatus();
        
        // 实时查询并更新题库统计信息
        updateQuestionCount();
    }
    
    private AIService.DetailedStatusObserver aiStatusObserver;
    private InferenceRouter inferenceRouter;
    
    private void updateAiStatus() {
        new Thread(() -> {
            try {
                // 初始化 InferenceRouter
                if (inferenceRouter == null) {
                    inferenceRouter = InferenceRouter.getInstance(this);
                }
                
                AIService aiService = AIService.getInstance(this);
                boolean isInitialized = aiService.isInitialized();
                String modelName = aiService.getCurrentModelName();
                AIServiceState serviceState = aiService.getServiceState();
                AIServiceState.ServiceStage stage = serviceState.getCurrentStage();
                String stageMessage = serviceState.getStageMessage();
                int progress = serviceState.getProgressPercent();
                String errorMessage = serviceState.getErrorMessage();
                
                // 获取当前推理类型和模型名称
                boolean usingOnline = inferenceRouter.isUsingOnlineModel();
                String displayModelName = inferenceRouter.getCurrentModelName();
                
                runOnUiThread(() -> updateAiStatusUI(stage, displayModelName, modelName, 
                       usingOnline, stageMessage, progress, errorMessage));
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    android.widget.TextView tvAiStatus = findViewById(R.id.tvAiStatus);
                    if (tvAiStatus != null) {
                        tvAiStatus.setText(getString(R.string.ai_status_unknown));
                        tvAiStatus.setTextColor(getResources().getColor(R.color.error));
                    }
                });
            }
        }).start();
    }
    
    private void updateAiStatusUI(AIServiceState.ServiceStage stage, String displayModelName, 
                                   String localModelName, boolean usingOnline,
                                   String stageMessage, int progress, String errorMessage) {
        android.widget.TextView tvAiStatus = findViewById(R.id.tvAiStatus);
        if (tvAiStatus != null) {
            String statusText;
            int statusColor;
            
            if (usingOnline) {
                // 在线模式：在线服务不依赖本地 AIService 初始化（LlamaHelper/本地模型文件）。
                // 本地初始化失败（stage=ERROR）不影响在线能力，不应误报"在线服务异常"。
                switch (stage) {
                    case INITIALIZED:
                        statusText = getString(R.string.status_online_prefix,
                                displayModelName != null ? displayModelName : getString(R.string.status_online_api));
                        statusColor = getResources().getColor(R.color.success);
                        break;
                    case ERROR:
                        // 本地服务 ERROR（如 LlamaHelper 未加载）与在线服务无关，在线仍可用
                        statusText = getString(R.string.status_online_prefix,
                                displayModelName != null ? displayModelName : getString(R.string.status_local_ready));
                        statusColor = getResources().getColor(R.color.success);
                        break;
                    default:
                        statusText = getString(R.string.status_online_ready);
                        statusColor = getResources().getColor(R.color.success);
                        break;
                }
            } else {
                // 离线本地模式
                switch (stage) {
                    case INITIALIZED:
                        if (displayModelName != null) {
                            statusText = getString(R.string.status_local_prefix, displayModelName);
                        } else if (localModelName != null) {
                            statusText = getString(R.string.status_local_prefix, localModelName);
                        } else {
                            statusText = getString(R.string.status_local_ready);
                        }
                        statusColor = getResources().getColor(R.color.success);
                        break;
                    case MODEL_FILE_PREPARING:
                    case MODEL_LOADING:
                    case GPU_INITIALIZATION:
                    case CHAT_CONTEXT_CREATING:
                    case NATIVE_LIBRARY_LOADING:
                        statusText = getString(R.string.status_loading_progress, progress);
                        statusColor = getResources().getColor(R.color.warning);
                        break;
                    case CPU_FALLBACK:
                        statusText = getString(R.string.status_cpu_progress, progress);
                        statusColor = getResources().getColor(R.color.warning);
                        break;
                    case ERROR:
                        if (errorMessage != null && !errorMessage.isEmpty()) {
                            statusText = getString(R.string.status_ai_error);
                        } else {
                            statusText = getString(R.string.status_init_failed);
                        }
                        statusColor = getResources().getColor(R.color.error);
                        break;
                    case UNINITIALIZED:
                    default:
                        statusText = getString(R.string.status_not_loaded);
                        statusColor = getResources().getColor(R.color.error);
                        break;
                }
            }
            
            tvAiStatus.setText(statusText);
            tvAiStatus.setTextColor(statusColor);
        }
    }
    
    private void registerAiStatusObserver() {
        if (aiStatusObserver != null) {
            return;
        }
        
        // 确保 InferenceRouter 已初始化
        if (inferenceRouter == null) {
            inferenceRouter = InferenceRouter.getInstance(this);
        }
        
        aiStatusObserver = new AIService.DetailedStatusObserver() {
            @Override
            public void onStateChanged(AIServiceState.ServiceStage stage, String message, int progress, long elapsedMs) {
                boolean usingOnline = inferenceRouter.isUsingOnlineModel();
                String displayModelName = inferenceRouter.getCurrentModelName();
                String localModelName = AIService.getInstance(MainActivity.this).getCurrentModelName();
                updateAiStatusUI(stage, displayModelName, localModelName, 
                               usingOnline, message, progress, null);
            }
            
            @Override
            public void onError(String errorMessage) {
                boolean usingOnline = inferenceRouter.isUsingOnlineModel();
                String displayModelName = inferenceRouter.getCurrentModelName();
                String localModelName = AIService.getInstance(MainActivity.this).getCurrentModelName();
                updateAiStatusUI(AIServiceState.ServiceStage.ERROR, displayModelName, localModelName, 
                               usingOnline, null, 0, errorMessage);
            }
            
            @Override
            public void onInitialized(String modelName, long loadTimeMs) {
                boolean usingOnline = inferenceRouter.isUsingOnlineModel();
                String displayModelName = inferenceRouter.getCurrentModelName();
                String localModelName = AIService.getInstance(MainActivity.this).getCurrentModelName();
                updateAiStatusUI(AIServiceState.ServiceStage.INITIALIZED, displayModelName, localModelName,
                               usingOnline, "AI服务已就绪", 100, null);
            }
        };
        
        AIService.getInstance(this).registerDetailedStatusObserver(aiStatusObserver);
    }
    
    private void unregisterAiStatusObserver() {
        if (aiStatusObserver != null) {
            AIService.getInstance(this).unregisterDetailedStatusObserver(aiStatusObserver);
            aiStatusObserver = null;
        }
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
                    createTemplate(templateViewModel, getString(R.string.template_jiangyi), getString(R.string.template_jiangyi_desc), "lecture_notes.json");
                    createTemplate(templateViewModel, getString(R.string.template_xiaochao), getString(R.string.template_xiaochao_desc), "cheat_sheet.json");
                    createTemplate(templateViewModel, getString(R.string.template_dayin), getString(R.string.template_dayin_desc), "print_material.json");
                    createTemplate(templateViewModel, getString(R.string.template_beisong), getString(R.string.template_beisong_desc), "recitation.json");
                    createTemplate(templateViewModel, getString(R.string.template_yuedu), getString(R.string.template_yuedu_desc), "reading_material.json");
                    createTemplate(templateViewModel, getString(R.string.template_jiyi), getString(R.string.template_jiyi_desc), "memory_cards.json");
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
        View cardWeatherBanner = findViewById(R.id.card_weather_banner);
        WeatherBannerView weatherBanner = findViewById(R.id.weather_banner);
        
        if (cardWeatherBanner != null) {
            cardWeatherBanner.setClickable(true);
            cardWeatherBanner.setFocusable(true);
            cardWeatherBanner.setFocusableInTouchMode(true);
            cardWeatherBanner.setOnClickListener(v -> {
                android.util.Log.d("MainActivity", "card_weather_banner clicked");
                if (weatherBanner != null) {
                    weatherBanner.onBannerClicked();
                }
            });
        }
        
        if (weatherBanner != null) {
            weatherBanner.setClickable(true);
            weatherBanner.setFocusable(true);
            // 显式绑定点击跳转（不依赖 View 内部 init 的监听，双保险）
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
                            .setTitle(getString(R.string.select_operation))
                            .setItems(new String[]{getString(R.string.button_import),
                                    getString(R.string.button_export)}, (dialog, which) -> {
                                if (which == 0) {
                                    startActivity(new Intent(MainActivity.this, ImportGuideActivity.class));
                                } else {
                                    startActivity(new Intent(MainActivity.this, ExportActivity.class));
                                }
                            })
                            .setNegativeButton(getString(R.string.cancel), null)
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
        
        // 设置AI聊天按钮
        View btnQuestionGenerate = findViewById(R.id.btn_question_generate);
        if (btnQuestionGenerate != null) {
            btnQuestionGenerate.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(MainActivity.this, com.oilquiz.app.ui.activity.AIChatActivity.class));
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
        
        // 设置模型管理按钮
        View btnModelImport = findViewById(R.id.btn_model_import);
        if (btnModelImport != null) {
            btnModelImport.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(MainActivity.this, ModelSelectorActivity.class));
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
        intent.putExtra("title", getString(R.string.weather_detail_title));
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
        environmentInfo.append(getString(R.string.env_report_frame_top)).append("\n");
        environmentInfo.append(getString(R.string.env_report_frame_title)).append("\n");
        environmentInfo.append(getString(R.string.env_report_frame_bottom)).append("\n\n");

        // 设备信息
        environmentInfo.append(getString(R.string.section_device_info)).append("\n");
        environmentInfo.append(getString(R.string.manufacturer) + ": " + info.deviceInfo.manufacturer + "\n");
        environmentInfo.append(getString(R.string.brand) + ": " + info.deviceInfo.brand + "\n");
        environmentInfo.append(getString(R.string.model) + ": " + info.deviceInfo.model + "\n");
        environmentInfo.append(getString(R.string.device_type) + ": " + info.deviceInfo.deviceType + "\n");
        environmentInfo.append(getString(R.string.hardware) + ": " + info.deviceInfo.hardware + "\n");
        environmentInfo.append(getString(R.string.mainboard) + ": " + info.deviceInfo.board + "\n\n");

        // 系统信息
        environmentInfo.append(getString(R.string.section_system_info)).append("\n");
        environmentInfo.append(getString(R.string.android_version) + ": " + info.systemInfo.androidVersion + "\n");
        environmentInfo.append(getString(R.string.sdk_level) + ": " + info.systemInfo.sdkInt + "\n");
        environmentInfo.append(getString(R.string.security_patch) + ": " + info.systemInfo.securityPatch + "\n");
        environmentInfo.append(getString(R.string.language) + ": " + info.systemInfo.displayLanguage + "\n");
        environmentInfo.append(getString(R.string.time_zone) + ": " + info.systemInfo.timeZone + "\n");
        environmentInfo.append(getString(R.string.is_rooted) + ": " + (info.systemInfo.isRooted ? getString(R.string.yes) : getString(R.string.no)) + "\n");
        environmentInfo.append(getString(R.string.is_emulator) + ": " + (info.systemInfo.isEmulator ? getString(R.string.yes) : getString(R.string.no)) + "\n\n");

        // 屏幕信息
        environmentInfo.append(getString(R.string.section_screen_info)).append("\n");
        environmentInfo.append(getString(R.string.resolution) + ": " + info.screenInfo.widthPixels + " x " + info.screenInfo.heightPixels + "\n");
        environmentInfo.append(getString(R.string.screen_density) + ": " + info.screenInfo.densityDpi + " dpi\n");
        environmentInfo.append(getString(R.string.screen_size) + ": " + String.format(java.util.Locale.getDefault(), "%.2f", info.screenInfo.screenSizeInches) + " 英寸\n");
        environmentInfo.append(getString(R.string.orientation) + ": " + info.screenInfo.orientation + "\n\n");

        // 内存信息
        environmentInfo.append(getString(R.string.section_memory_info)).append("\n");
        environmentInfo.append(getString(R.string.total_memory) + ": " + com.oilquiz.app.util.NativeEnvironmentChecker.formatBytes(info.memoryInfo.totalMemory) + "\n");
        environmentInfo.append(getString(R.string.available_memory) + ": " + com.oilquiz.app.util.NativeEnvironmentChecker.formatBytes(info.memoryInfo.availableMemory) + "\n");
        environmentInfo.append(getString(R.string.usage_percent) + ": " + info.memoryInfo.usagePercent + "%\n");
        environmentInfo.append(getString(R.string.app_memory_limit) + ": " + info.memoryInfo.memoryClass + " MB\n");
        environmentInfo.append(getString(R.string.low_memory_state) + ": " + (info.memoryInfo.lowMemory ? getString(R.string.yes) : getString(R.string.no)) + "\n\n");

        // 存储信息
        environmentInfo.append(getString(R.string.section_storage_info)).append("\n");
        environmentInfo.append(getString(R.string.internal_storage_total) + ": " + com.oilquiz.app.util.NativeEnvironmentChecker.formatBytes(info.storageInfo.internalTotal) + "\n");
        environmentInfo.append(getString(R.string.internal_storage_available) + ": " + com.oilquiz.app.util.NativeEnvironmentChecker.formatBytes(info.storageInfo.internalAvailable) + "\n");
        if (info.storageInfo.externalMounted) {
            environmentInfo.append(getString(R.string.external_storage_total) + ": " + com.oilquiz.app.util.NativeEnvironmentChecker.formatBytes(info.storageInfo.externalTotal) + "\n");
            environmentInfo.append(getString(R.string.external_storage_available) + ": " + com.oilquiz.app.util.NativeEnvironmentChecker.formatBytes(info.storageInfo.externalAvailable) + "\n");
        }
        environmentInfo.append(getString(R.string.app_files_size) + ": " + com.oilquiz.app.util.NativeEnvironmentChecker.formatBytes(info.storageInfo.appFilesSize) + "\n");
        environmentInfo.append(getString(R.string.app_cache_size) + ": " + com.oilquiz.app.util.NativeEnvironmentChecker.formatBytes(info.storageInfo.appCacheSize) + "\n\n");

        // 应用信息
        environmentInfo.append(getString(R.string.section_app_info)).append("\n");
        environmentInfo.append(getString(R.string.app_name_label) + ": " + info.appInfo.appName + "\n");
        environmentInfo.append(getString(R.string.package_name) + ": " + info.appInfo.packageName + "\n");
        environmentInfo.append(getString(R.string.version_label) + ": " + info.appInfo.versionName + " (" + info.appInfo.versionCode + ")\n");
        environmentInfo.append(getString(R.string.target_sdk) + ": " + info.appInfo.targetSdkVersion + "\n");
        environmentInfo.append(getString(R.string.min_sdk) + ": " + info.appInfo.minSdkVersion + "\n");
        environmentInfo.append(getString(R.string.debug_mode) + ": " + (info.appInfo.isDebuggable ? getString(R.string.yes) : getString(R.string.no)) + "\n\n");

        // 硬件信息
        environmentInfo.append(getString(R.string.section_hardware_info)).append("\n");
        environmentInfo.append(getString(R.string.cpu_arch) + ": " + info.hardwareInfo.cpuAbi + "\n");
        if (info.hardwareInfo.supportedAbis != null && info.hardwareInfo.supportedAbis.length > 0) {
            environmentInfo.append(getString(R.string.supported_abis) + ": " + String.join(", ", info.hardwareInfo.supportedAbis) + "\n");
        }
        environmentInfo.append(getString(R.string.processor_count) + ": " + info.runtimeInfo.availableProcessors + "\n");
        environmentInfo.append(getString(R.string.camera) + ": " + (info.hardwareInfo.hasCamera ? getString(R.string.supported) : getString(R.string.not_supported)) + "\n");
        environmentInfo.append(getString(R.string.gps) + ": " + (info.hardwareInfo.hasGPS ? getString(R.string.supported) : getString(R.string.not_supported)) + "\n");
        environmentInfo.append(getString(R.string.nfc) + ": " + (info.hardwareInfo.hasNFC ? getString(R.string.supported) : getString(R.string.not_supported)) + "\n");
        environmentInfo.append(getString(R.string.bluetooth) + ": " + (info.hardwareInfo.hasBluetooth ? getString(R.string.supported) : getString(R.string.not_supported)) + "\n");
        environmentInfo.append(getString(R.string.wifi) + ": " + (info.hardwareInfo.hasWifi ? getString(R.string.supported) : getString(R.string.not_supported)) + "\n");

        new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.native_environment_detection))
                .setMessage(environmentInfo.toString())
                .setPositiveButton(getString(R.string.button_ok), null)
                .setNeutralButton(getString(R.string.button_copy), (dialog, which) -> {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    android.content.ClipData clip = android.content.ClipData.newPlainText(getString(R.string.environment_report), environmentInfo.toString());
                    clipboard.setPrimaryClip(clip);
                    android.widget.Toast.makeText(this, getString(R.string.environment_info_copied), android.widget.Toast.LENGTH_SHORT).show();
                })
                .show();
    }
    
    private void runTests() {
        StringBuilder testResults = new StringBuilder();
        testResults.append(getString(R.string.test_result_header)).append("\n\n");
        
        // 测试数据库连接
        try {
            com.oilquiz.app.database.AppDatabase db = com.oilquiz.app.database.AppDatabase.getDatabase(this);
            testResults.append(getString(R.string.db_connection_ok)).append("\n");
        } catch (Exception e) {
            testResults.append(getString(R.string.db_connection_failed, e.getMessage())).append("\n");
        }
        
        // 测试存储权限
        com.oilquiz.app.resource.AppResourceManager resources = com.oilquiz.app.resource.AppResourceManager.getInstance(this);
        if (resources.hasStoragePermission()) {
            testResults.append(getString(R.string.storage_permission_granted)).append("\n");
        } else {
            testResults.append(getString(R.string.storage_permission_denied)).append("\n");
        }
        
        // 测试网络连接
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
        android.net.NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        if (activeNetwork != null && activeNetwork.isConnectedOrConnecting()) {
            testResults.append(getString(R.string.network_connection_ok)).append("\n");
        } else {
            testResults.append(getString(R.string.network_connection_failed)).append("\n");
        }
        
        new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.test_result))
                .setMessage(testResults.toString())
                .setPositiveButton(getString(R.string.button_ok), null)
                .show();
    }
    
    private String getVersionName() {
        try {
            android.content.pm.PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            return packageInfo.versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return getString(R.string.unknown);
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
                    android.widget.Toast.makeText(this, getString(R.string.processing_file_failed, e.getMessage()), android.widget.Toast.LENGTH_SHORT).show();
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
    
    @Override
    protected void onPause() {
        super.onPause();
        // 天气横幅：离开主界面停止周期定时刷新（避免后台空跑网络请求）
        try {
            WeatherBannerView weatherBanner = findViewById(R.id.weather_banner);
            if (weatherBanner != null) {
                weatherBanner.onPause();
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterAiStatusObserver();
    }
}
