package com.oilquiz.app;

import com.oilquiz.app.theme.ThemeColors;

import com.oilquiz.app.ui.base.BaseActivity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.File;
import android.widget.Button;
import android.view.View;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.webkit.JavascriptInterface;
import androidx.annotation.NonNull;
import com.oilquiz.app.infra.AppLogger;
import com.oilquiz.app.resource.AppResourceManager;
import com.oilquiz.app.resource.SystemUIResourceAdapter;
import com.oilquiz.app.resource.PermissionResourceProvider;
import com.oilquiz.app.webview.RedirectWebViewClient;
import com.oilquiz.app.webview.WebViewLoadManager;
import com.oilquiz.app.webview.security.WebViewSecurityConfig;
import com.oilquiz.app.webview.security.WebViewLifecycleManager;
import com.oilquiz.app.webview.security.SecurityWebViewClient;
import com.oilquiz.app.webview.js.JSInterfaceManager;
import com.oilquiz.app.webview.js.JSToolInterface;
import com.oilquiz.app.webview.js.JSClipboardInterface;
import com.oilquiz.app.webview.js.JSFileInterface;
import com.oilquiz.app.webview.js.JSDatabaseInterface;
import com.oilquiz.app.model.Question;
import com.oilquiz.app.repository.QuestionRepository;
import com.oilquiz.app.viewmodel.QuestionViewModel;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.oilquiz.app.util.CharsetDetector;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class WebViewActivity extends BaseActivity {

    private static final String TAG = "WebViewActivity";
    private WebView x5WebView;
    private Button btnRefresh;
    private Button btnReset;
    private FrameLayout webViewContainer;
    private SystemUIResourceAdapter uiAdapter;
    
    // 标签页相关
    private LinearLayout tabContainer;
    private java.util.ArrayList<WebView> webViewList;
    private java.util.ArrayList<TextView> tabList;
    private int currentTabIndex = 0;
    private Button newTabButton;

    // 壳能力移植：Web 侧权限请求桥接状态（requestPermission 回调）
    private String pendingBridgePermission = null;
    private String pendingBridgeCallback = null;
    
    // 文件监控
    private android.os.FileObserver exportDirObserver;
    
    // WebView加载管理器
    private WebViewLoadManager webViewLoadManager;
    
    // 加载状态指示器
    private android.widget.ProgressBar pageProgressBar;
    private TextView loadingStatusText;

    private String customUrl;
    private String customTitle;
    private QuestionRepository questionRepository;
    private PermissionResourceProvider permissionProvider;
    private String cachedQuestionsJson = "[]";
    private String cachedQuestionTypesJson = "[]";
    private String cachedCategoriesJson = "[]";
    private boolean isQuestionsDataLoaded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 先初始化uiAdapter，确保在initView()之前可用
        uiAdapter = SystemUIResourceAdapter.getInstance(this);
        uiAdapter.applySystemTheme(this);
        
        // 初始化WebView加载管理器
        webViewLoadManager = WebViewLoadManager.getInstance(this);
        
        // 初始化QuestionRepository
        questionRepository = new QuestionRepository(getApplication());
        
        // 初始化权限提供者
        permissionProvider = PermissionResourceProvider.getInstance(this);
        
        // 预加载题目数据
        loadQuestionsData();
        
        // 处理Intent参数
        Intent intent = getIntent();
        if (intent != null) {
            customUrl = intent.getStringExtra("url");
            customTitle = intent.getStringExtra("title");
        }
        
        super.onCreate(savedInstanceState);

        // 准备 WebView 文件重定向规则        
        // com.oilquiz.app.webview.WebViewRedirectHelper.prepareWebView(this);
    }
    
    // 预加载题目数据
    private void loadQuestionsData() {
        // 加载题目数据
        questionRepository.getAllQuestions(new QuestionRepository.RepositoryCallback<List<Question>>() {
            @Override
            public void onSuccess(List<Question> questions) {
                Gson gson = new Gson();
                cachedQuestionsJson = gson.toJson(questions);
                AppLogger.d(TAG, "题目数据加载完成，共 " + questions.size() + " 条");
                checkAllDataLoaded();
            }
            
            @Override
            public void onFailure(String error) {
                AppLogger.e(TAG, "获取题目数据失败: " + error);
                cachedQuestionsJson = "[]";
                checkAllDataLoaded();
            }
        });
        
        // 加载题目类型
        questionRepository.getAllQuestionTypes(new QuestionRepository.RepositoryCallback<List<String>>() {
            @Override
            public void onSuccess(List<String> types) {
                Gson gson = new Gson();
                cachedQuestionTypesJson = gson.toJson(types);
                AppLogger.d(TAG, "题目类型加载完成，共 " + types.size() + " 种");
                checkAllDataLoaded();
            }
            
            @Override
            public void onFailure(String error) {
                AppLogger.e(TAG, "获取题目类型失败: " + error);
                cachedQuestionTypesJson = "[]";
                checkAllDataLoaded();
            }
        });
        
        // 加载分类
        questionRepository.getAllCategories(new QuestionRepository.RepositoryCallback<List<String>>() {
            @Override
            public void onSuccess(List<String> categories) {
                Gson gson = new Gson();
                cachedCategoriesJson = gson.toJson(categories);
                AppLogger.d(TAG, "分类数据加载完成，共 " + categories.size() + " 个");
                checkAllDataLoaded();
            }
            
            @Override
            public void onFailure(String error) {
                AppLogger.e(TAG, "获取分类数据失败: " + error);
                cachedCategoriesJson = "[]";
                checkAllDataLoaded();
            }
        });
    }
    
    // 检查所有数据是否加载完成
    private void checkAllDataLoaded() {
        // 题目数据、题目类型和分类数据都加载完成后，标记为已加载
        if (!cachedQuestionsJson.equals("[]") || !cachedQuestionTypesJson.equals("[]") || !cachedCategoriesJson.equals("[]")) {
            isQuestionsDataLoaded = true;
            
            // 如果WebView已经加载了题目渲染器页面，通知它数据已加载
            runOnUiThread(() -> {
                if (x5WebView != null && customUrl != null && customUrl.contains("question-renderer")) {
                    x5WebView.evaluateJavascript("if(typeof onQuestionsDataLoaded === 'function') onQuestionsDataLoaded();", null);
                }
            });
        }
    }

    @Override
    protected int getLayoutId() {
        // 由于使用动态布局，返回0
        return 0;
    }

    @Override
    protected void initView() {
        // 创建根布局
        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
        ));
        rootLayout.setBackgroundColor(uiAdapter.getBackgroundColor());
        setContentView(rootLayout);
        
        // 设置标题和Toolbar
        String title = customTitle != null ? customTitle : getString(R.string.h_d6603825);
        setupToolbar(title);

        // 创建标签容器 - 类似底部导航栏样式
        tabContainer = new LinearLayout(this);
        tabContainer.setOrientation(LinearLayout.HORIZONTAL);
        tabContainer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        tabContainer.setBackgroundColor(uiAdapter.getSurfaceColor());
        tabContainer.setId(View.generateViewId());
        tabContainer.setPadding(16, 12, 16, 12);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            tabContainer.setElevation(3);
        }
        rootLayout.addView(tabContainer);

        // 创建WebView容器
        webViewContainer = new FrameLayout(this);
        webViewContainer.setId(View.generateViewId());
        LinearLayout.LayoutParams webViewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
        );
        rootLayout.addView(webViewContainer, webViewParams);
        
        // 创建加载进度条
        pageProgressBar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pageProgressBar.setId(View.generateViewId());
        pageProgressBar.setMax(100);
        pageProgressBar.setProgress(0);
        pageProgressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                4
        );
        rootLayout.addView(pageProgressBar, 1, progressParams); // 插入到标签页和WebView之间
        
        // 创建加载状态文本
        loadingStatusText = new TextView(this);
        loadingStatusText.setId(View.generateViewId());
        loadingStatusText.setText(getString(R.string.h_215e98b8));
        loadingStatusText.setTextSize(12);
        loadingStatusText.setTextColor(uiAdapter.getTextSecondaryColor());
        loadingStatusText.setPadding(16, 4, 16, 4);
        loadingStatusText.setVisibility(View.GONE);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        rootLayout.addView(loadingStatusText, 2, statusParams);

        // 创建底部按钮容器
        LinearLayout buttonContainer = new LinearLayout(this);
        buttonContainer.setOrientation(LinearLayout.VERTICAL);
        buttonContainer.setPadding(8, 8, 8, 8);
        buttonContainer.setBackgroundColor(uiAdapter.getSurfaceColor());
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            buttonContainer.setElevation(8);
        }
        buttonContainer.setId(View.generateViewId());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        rootLayout.addView(buttonContainer, buttonParams);

        // 创建导航按钮
        LinearLayout navRow = new LinearLayout(this);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        navRow.setPadding(0, 0, 0, 8);
        LinearLayout.LayoutParams navRowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        buttonContainer.addView(navRow, navRowParams);

        // 添加返回按钮
        Button btnBack = createNavButton("◀", getString(R.string.h_5094c1a3));
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (x5WebView != null) {
                    try {
                        if (x5WebView.canGoBack()) {
                            x5WebView.goBack();
                        }
                    } catch (Exception e) {
                        AppLogger.e(TAG, "调用canGoBack或goBack失败: " + e.getMessage(), e);
                    }
                }
            }
        });
        navRow.addView(btnBack);

        // 添加前进按钮
        Button btnForward = createNavButton("▶", getString(R.string.h_5811e283));
        btnForward.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (x5WebView != null) {
                    try {
                        if (x5WebView.canGoForward()) {
                            x5WebView.goForward();
                        }
                    } catch (Exception e) {
                        AppLogger.e(TAG, "调用canGoForward或goForward失败: " + e.getMessage(), e);
                    }
                }
            }
        });
        navRow.addView(btnForward);

        // 添加刷新按钮
        btnRefresh = createNavButton("🔄", getString(R.string.h_694fc5ef));
        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 刷新当前页面（不再加载本地文件/最近文件，避免"链接错误"跳走）
                try {
                    if (x5WebView != null) {
                        x5WebView.reload();
                    }
                } catch (Exception e) {
                    AppLogger.e(TAG, "刷新当前页面失败: " + e.getMessage(), e);
                }
            }
        });
        navRow.addView(btnRefresh);

        // 添加主页按钮
        btnReset = createNavButton("🏠", getString(R.string.h_b04ec75c));
        btnReset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 返回 Bing 搜索引擎主页
                loadUrl("https://www.bing.com");
            }
        });
        navRow.addView(btnReset);
    }

    @Override
    protected void initData() {
        // 初始化文件预览管理器（包含TBS SDK）
        com.oilquiz.app.util.preview.FilePreviewManager.getInstance().initialize(this);
        
        // 设置TBS SDK隐私政策同意状态
        // 注意：根据腾讯文档要求，用户必须先同意隐私政策才能初始化TBS SDK
        com.oilquiz.app.util.preview.TBSPreviewManager.getInstance(this).setPrivacyPolicyAccepted(true);
        
        // 异步初始化TBS SDK
        com.oilquiz.app.util.preview.FilePreviewManager.getInstance().initializeX5Async((success, errorCode) -> {
            if (success) {
                AppLogger.d(TAG, "TBS SDK 初始化成功");
            } else {
                AppLogger.w(TAG, "TBS SDK 初始化失败: " + errorCode + "，将使用内置引擎");
            }
        });
        
        // 初始化标签页相关
        webViewList = new java.util.ArrayList<>();
        tabList = new java.util.ArrayList<>();
        
        // 配置WebView加载管理器
        setupWebViewLoadManager();
        
        // 创建第一个标签页（延迟加载，优化启动性能）
        createNewTabDelayed(100);

        // 检查是否首次启动（延迟执行，确保WebView初始化完成）
        new android.os.Handler().postDelayed(() -> {
            // 优先 file_path（本地文件完整预览链路：智能编码检测防 GBK 乱码）
            String localPath = getIntent() != null ? getIntent().getStringExtra("file_path") : null;
            if (localPath != null && !localPath.isEmpty()) {
                java.io.File f = new java.io.File(localPath);
                if (f.exists() && f.isFile()) {
                    AppLogger.d(TAG, "加载本地文件(file_path): " + localPath);
                    loadFile(f);
                    return;
                }
            }
            if (customUrl != null) {
                // 加载自定义URL
                loadUrl(customUrl);
            } else {
                checkFirstLaunch();
            }
        }, 200);
        
        // 初始化文件监控，监控导出目录变化
        initExportDirObserver();
    }
    
    /**
     * 配置WebView加载管理器
     */
    private void setupWebViewLoadManager() {
        // 设置页面加载回调
        webViewLoadManager.setPageLoadCallback(new WebViewLoadManager.PageLoadCallback() {
            @Override
            public void onPageStarted(String url) {
                runOnUiThread(() -> {
                    // 显示加载进度条
                    pageProgressBar.setVisibility(View.VISIBLE);
                    pageProgressBar.setProgress(0);
                    loadingStatusText.setVisibility(View.VISIBLE);
                    loadingStatusText.setText(getString(R.string.h_bd0271ed));
                });
            }
            
            @Override
            public void onPageFinished(String url) {
                runOnUiThread(() -> {
                    // 隐藏加载进度条
                    pageProgressBar.setVisibility(View.GONE);
                    loadingStatusText.setVisibility(View.GONE);
                    
                    // 更新标签页标题
                    if (currentTabIndex >= 0 && currentTabIndex < tabList.size()) {
                        updateTabTitleFromWebView(currentTabIndex);
                    }
                });
            }
            
            @Override
            public void onProgressChanged(int progress) {
                runOnUiThread(() -> {
                    // 更新进度条
                    pageProgressBar.setProgress(progress);
                    loadingStatusText.setText(getString(R.string.h_c96070db) + progress + "%");
                });
            }
            
            @Override
            public void onReceivedTitle(String title) {
                runOnUiThread(() -> {
                    // 更新标签页标题
                    if (currentTabIndex >= 0 && currentTabIndex < tabList.size()) {
                        tabList.get(currentTabIndex).setText(title);
                    }
                });
            }
            
            @Override
            public void onError(int errorCode, String description, String failingUrl) {
                runOnUiThread(() -> {
                    // 显示错误状态
                    pageProgressBar.setVisibility(View.GONE);
                    loadingStatusText.setText(getString(R.string.h_866b795e));
                    loadingStatusText.setTextColor(getResources().getColor(R.color.error_color));
                    
                    // 3秒后隐藏错误提示
                    new android.os.Handler().postDelayed(() -> {
                        loadingStatusText.setVisibility(View.GONE);
                        loadingStatusText.setTextColor(uiAdapter.getTextSecondaryColor());
                    }, 3000);
                });
            }
        });
    }
    
    /**
     * 延迟创建新标签页
     */
    private void createNewTabDelayed(long delayMillis) {
        new android.os.Handler().postDelayed(() -> {
            createNewTab();
        }, delayMillis);
    }

    @Override
    protected void initListener() {
        // 监听器已在initView中设置
    }
    
    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
    
    /**
     * 检查是否首次启动
     */
    private void checkFirstLaunch() {
        // 加载 Bing 搜索引擎作为主页
        loadUrl("https://www.bing.com");
    }
    
    /**
     * 打开文件选择对话框
     */
    private void openFileChooser() {
        // 创建文件选择Intent
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
        // 设置MIME类型为所有可渲染的文件格式
        intent.setType("*/*");
        intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        
        // 启动文件选择活动
        try {
            startActivityForResult(android.content.Intent.createChooser(intent, "选择文件"), 100);
        } catch (android.content.ActivityNotFoundException ex) {
            // 如果没有文件管理器，显示提示
            new android.app.AlertDialog.Builder(this)
                    .setTitle(getString(R.string.h_02d9819d))
                    .setMessage(getString(R.string.h_756d4a53))
                    .setPositiveButton(getString(R.string.h_38cf16f2), null)
                    .show();
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            // 获取选择的文件URI
            android.net.Uri uri = data.getData();
            if (uri != null) {
                // 处理选择的文件
                handleSelectedFile(uri);
            }
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // 壳桥权限请求结果转发（Web 侧 requestPermission 回调）
        if (pendingBridgePermission != null) {
            boolean granted = grantResults.length > 0;
            for (int r : grantResults) {
                if (r != android.content.pm.PackageManager.PERMISSION_GRANTED) granted = false;
            }
            dispatchPermissionResult(pendingBridgePermission, granted);
            pendingBridgePermission = null;
            pendingBridgeCallback = null;
            return;
        }
        // 处理权限请求结果
        if (permissionProvider != null) {
            permissionProvider.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    /** 壳桥：Web 权限名 → Android 权限（"camera"/"mic"/"storage"/"notification"/"location"） */
    private String[] bridgePermissionMapping(String name) {
        if (name == null) return null;
        switch (name.trim().toLowerCase(java.util.Locale.US)) {
            case "camera":
                return new String[]{android.Manifest.permission.CAMERA};
            case "mic":
            case "microphone":
                return new String[]{android.Manifest.permission.RECORD_AUDIO};
            case "storage":
            case "media":
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    return new String[]{
                            android.Manifest.permission.READ_MEDIA_IMAGES,
                            android.Manifest.permission.READ_MEDIA_VIDEO,
                            android.Manifest.permission.READ_MEDIA_AUDIO};
                }
                return new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE};
            case "notification":
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    return new String[]{android.Manifest.permission.POST_NOTIFICATIONS};
                }
                return new String[0];
            case "location":
                return new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION};
            default:
                return null;
        }
    }

    /** 壳桥：权限请求结果回调到 Web（window['cb'](json)） */
    private void dispatchPermissionResult(String permission, boolean granted) {
        org.json.JSONObject out = new org.json.JSONObject();
        try {
            out.put("permission", permission);
            out.put("granted", granted);
        } catch (Exception ignored) { }
        final String cb = pendingBridgeCallback == null ? "" : pendingBridgeCallback;
        final String js = "window['" + cb + "'] && window['" + cb + "'](" + org.json.JSONObject.quote(out.toString()) + ");";
        runOnUiThread(() -> {
            try {
                if (x5WebView != null) x5WebView.evaluateJavascript(js, null);
            } catch (Exception ignored) { }
        });
    }

    /** 根据文件扩展名返回 MIME 类型（分享文件用） */
    private static String mimeOf(String fileName) {
        String n = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.US);
        int dot = n.lastIndexOf('.');
        String ext = dot >= 0 ? n.substring(dot) : "";
        switch (ext) {
            case ".png": return "image/png";
            case ".jpg": case ".jpeg": return "image/jpeg";
            case ".gif": return "image/gif";
            case ".webp": return "image/webp";
            case ".pdf": return "application/pdf";
            case ".txt": return "text/plain";
            case ".html": case ".htm": return "text/html";
            case ".json": return "application/json";
            case ".csv": return "text/csv";
            case ".xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case ".docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case ".mp3": return "audio/mpeg";
            case ".mp4": return "video/mp4";
            case ".zip": return "application/zip";
            default: return "*/*";
        }
    }

    /**
     * 处理 WebView 权限请求
     * @param request 权限请求
     */
    private void handleWebViewPermissionRequest(final android.webkit.PermissionRequest request) {
        if (request == null || permissionProvider == null) {
            if (request != null) {
                request.deny();
            }
            return;
        }

        final String[] resources = request.getResources();
        if (resources == null || resources.length == 0) {
            request.deny();
            return;
        }

        final java.util.List<String> permissionsToRequest = new java.util.ArrayList<>();
        final java.util.List<String> resourcesToGrant = new java.util.ArrayList<>();

        for (String resource : resources) {
            if (android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                if (permissionProvider.hasCameraPermission()) {
                    resourcesToGrant.add(resource);
                } else {
                    permissionsToRequest.add(android.Manifest.permission.CAMERA);
                }
            } else if (android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                if (permissionProvider.hasMicrophonePermission()) {
                    resourcesToGrant.add(resource);
                } else {
                    permissionsToRequest.add(android.Manifest.permission.RECORD_AUDIO);
                }
            } else if (android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(resource) ||
                       android.webkit.PermissionRequest.RESOURCE_MIDI_SYSEX.equals(resource)) {
                resourcesToGrant.add(resource);
            }
        }

        if (resourcesToGrant.size() == resources.length) {
            request.grant(resourcesToGrant.toArray(new String[0]));
        } else if (!permissionsToRequest.isEmpty()) {
            permissionProvider.requestPermissions(WebViewActivity.this, 
                permissionsToRequest.toArray(new String[0]),
                new com.oilquiz.app.resource.PermissionResourceProvider.PermissionCallback() {
                    @Override
                    public void onGranted() {
                        java.util.List<String> grantedResources = new java.util.ArrayList<>();
                        for (String resource : resources) {
                            if (android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                                if (permissionProvider.hasCameraPermission()) {
                                    grantedResources.add(resource);
                                }
                            } else if (android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                                if (permissionProvider.hasMicrophonePermission()) {
                                    grantedResources.add(resource);
                                }
                            } else {
                                grantedResources.add(resource);
                            }
                        }
                        if (!grantedResources.isEmpty()) {
                            request.grant(grantedResources.toArray(new String[0]));
                        } else {
                            request.deny();
                        }
                    }

                    @Override
                    public void onDenied(java.util.List<String> deniedPermissions) {
                        request.deny();
                    }
                });
        } else {
            request.deny();
        }
    }
    
    /**
     * 处理选择的文件
     * @param uri 文件URI
     */
    private void handleSelectedFile(android.net.Uri uri) {
        try {
            AppLogger.d(TAG, "处理选择的文件URI: " + uri.toString());
            
            // SAF Uri 统一解析为可直接访问的真实文件（content:// 流复制到缓存；file:// 直接取路径）
            java.io.File resolved = com.oilquiz.app.util.UriPathResolver.resolveToFile(this, uri.toString());
            String filePath = resolved != null ? resolved.getAbsolutePath() : null;
            AppLogger.d(TAG, "获取到的文件路径: " + filePath);
            
            if (filePath != null) {
                File file = new File(filePath);
                
                // 检查文件是否存在
                if (!file.exists()) {
                    AppLogger.e(TAG, "文件不存在: " + filePath);
                    showFileErrorDialog("文件不存在或无法访问");
                    return;
                }
                
                // 检查文件是否可读
                if (!file.canRead()) {
                    AppLogger.e(TAG, "文件无法读取: " + filePath);
                    showFileErrorDialog("文件无法读取，请检查权限");
                    return;
                }
                
                AppLogger.d(TAG, "文件存在且可读，大小: " + file.length() + " bytes");
                
                // 验证文件格式是否支持
                if (isFileFormatSupported(filePath)) {
                    AppLogger.d(TAG, "文件格式支持，开始加载: " + filePath);
                    
                    // 保存文件路径到配置
                    AppResourceManager.getInstance(this).config().setConfig("webview_last_selected_file", filePath);
                    
                    // 创建新标签页
                    createNewTab();
                    
                    // 确保x5WebView已设置为新标签页的WebView
                    // 延迟加载文件，确保标签页切换完成
                    new android.os.Handler().postDelayed(() -> {
                        AppLogger.d(TAG, "延迟加载文件: " + filePath);
                        loadFile(file);
                    }, 200);
                } else {
                    AppLogger.w(TAG, "不支持的文件格式: " + filePath);
                    showFileErrorDialog("不支持的文件格式\n支持的格式：HTML、TXT、MD、图片、PDF、Office文档");
                }
            } else {
                AppLogger.e(TAG, "无法获取文件路径，URI: " + uri.toString());
                showFileErrorDialog("无法获取文件路径，请尝试其他文件");
            }
        } catch (Exception e) {
            AppLogger.e(TAG, "处理文件失败: " + e.getMessage(), e);
            showFileErrorDialog("文件处理失败: " + e.getMessage());
        }
    }
    
    /**
     * 显示文件错误对话框
     * @param message 错误信息
     */
    private void showFileErrorDialog(String message) {
        runOnUiThread(() -> {
            new android.app.AlertDialog.Builder(this)
                    .setTitle(getString(R.string.h_ce0c7e18))
                    .setMessage(message)
                    .setPositiveButton(getString(R.string.h_38cf16f2), null)
                    .show();
        });
    }
    
    /**
     * 检查文件格式是否支持
     * @param filePath 文件路径
     * @return 是否支持
     */
    private boolean isFileFormatSupported(String filePath) {
        String fileName = new File(filePath).getName().toLowerCase();
        // 检查是否为支持的文件格式
        return fileName.endsWith(".html") || fileName.endsWith(".htm") ||
               fileName.endsWith(".txt") || fileName.endsWith(".md") || fileName.endsWith(".markdown") ||
               fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") || fileName.endsWith(".png") || fileName.endsWith(".gif") || fileName.endsWith(".bmp") ||
               fileName.endsWith(".pdf") || fileName.endsWith(".doc") || fileName.endsWith(".docx") ||
               fileName.endsWith(".xls") || fileName.endsWith(".xlsx") ||
               fileName.endsWith(".ppt") || fileName.endsWith(".pptx") ||
               fileName.endsWith(".csv");
    }
    
    /**
     * 从URI获取文件名
     * @param uri 文件URI
     * @return 文件名
     */
    private String getFileNameFromUri(android.net.Uri uri) {
        String fileName = null;
        if ("content".equals(uri.getScheme())) {
            try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int displayNameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (displayNameIndex >= 0) {
                        fileName = cursor.getString(displayNameIndex);
                    }
                }
            } catch (Exception e) {
                AppLogger.w(TAG, "获取文件名失败: " + e.getMessage());
            }
        } else if ("file".equals(uri.getScheme())) {
            fileName = new java.io.File(uri.getPath()).getName();
        }
        return fileName;
    }
    
    /**
     * 加载URL
     * @param url URL地址
     */
    private void loadUrl(String url) {
        try {
            if (isWeatherWebsite(url)) {
                url = ensureWeatherHttps(url);
                if (x5WebView != null) {
                    x5WebView.clearCache(true);
                    x5WebView.loadUrl(url);
                }
            } else if (webViewLoadManager != null && x5WebView != null) {
                webViewLoadManager.loadUrl(x5WebView, url);
            } else if (x5WebView != null) {
                x5WebView.loadUrl(url);
            }
        } catch (Exception e) {
            AppLogger.e(TAG, "加载URL失败: " + e.getMessage(), e);
        }
    }

    private String ensureWeatherHttps(String url) {
        if (url != null && url.startsWith("http://") && isWeatherWebsite(url)) {
            return url.replaceFirst("http://", "https://");
        }
        return url;
    }
    
    /**
     * 延迟加载URL
     * @param url URL地址
     * @param delayMillis 延迟毫秒数
     */
    private void loadUrlDelayed(String url, long delayMillis) {
        try {
            if (webViewLoadManager != null && x5WebView != null) {
                webViewLoadManager.loadUrlDelayed(x5WebView, url, delayMillis);
            } else {
                new android.os.Handler().postDelayed(() -> loadUrl(url), delayMillis);
            }
        } catch (Exception e) {
            AppLogger.e(TAG, "延迟加载URL失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 加载数据
     * @param data 数据
     * @param mimeType MIME类型
     * @param encoding 编码
     */
    private void loadData(String data, String mimeType, String encoding) {
        try {
            // 使用WebViewLoadManager加载（带优化）
            if (webViewLoadManager != null && x5WebView != null) {
                webViewLoadManager.loadData(x5WebView, data, mimeType, encoding);
            } else if (x5WebView != null) {
                // 降级到直接加载
                x5WebView.loadDataWithBaseURL(null, data, mimeType, "UTF-8", null);
            }
        } catch (Exception e) {
            AppLogger.e(TAG, "加载数据失败: " + e.getMessage(), e);
        }
    }
    
    private void loadDataWithBaseURL(String baseUrl, String data, String mimeType, String encoding, String historyUrl) {
        try {
            // 使用WebViewLoadManager加载（带优化）
            if (webViewLoadManager != null && x5WebView != null) {
                webViewLoadManager.loadDataWithBaseURL(x5WebView, baseUrl, data, mimeType, encoding, historyUrl);
            } else if (x5WebView != null) {
                // 降级到直接加载
                x5WebView.loadDataWithBaseURL(baseUrl, data, mimeType, "UTF-8", historyUrl);
            }
        } catch (Exception e) {
            AppLogger.e(TAG, "加载数据失败: " + e.getMessage(), e);
        }
    }

    private void loadLocalHtml() {
        // 首先尝试加载最近选择的文件
        AppResourceManager resources = AppResourceManager.getInstance(this);
        String lastSelectedFile = resources.getConfigString("webview_last_selected_file", null);
        
        if (lastSelectedFile != null) {
            File selectedFile = new File(lastSelectedFile);
            if (selectedFile.exists()) {
                // 加载最近选择的文件
                loadFile(selectedFile);
                return;
            }
        }
        
        // 如果没有最近选择的文件，尝试从导出文件夹加载最新的文件
        File latestFile = getLatestFileFromExportDir();
        if (latestFile != null && latestFile.exists()) {
            // 根据文件类型选择不同的加载方式
            loadFile(latestFile);
        } else {
            // 如果导出文件夹没有文件，则加载assets目录中的功能介绍文件
            loadUrl("file:///android_asset/app2.html");
        }
    }

    private void loadFile(File file) {
        String fileName = file.getName().toLowerCase();
        String fileUrl = "file://" + file.getAbsolutePath();
        
        // 根据文件类型选择不同的加载方式
        if (fileName.endsWith(".html") || fileName.endsWith(".htm")) {
            // 读取HTML文件内容并使用loadDataWithBaseURL加载，确保正确渲染
            // 使用CharsetDetector智能检测编码，修复GBK/GB18030乱码问题
            try {
                String content = CharsetDetector.readFileAutoDetect(file);
                // 使用loadDataWithBaseURL加载HTML内容，设置base URL为文件所在目录，确保相对路径资源能正确加载
                String baseUrl = fileUrl.substring(0, fileUrl.lastIndexOf("/")) + "/";
                loadDataWithBaseURL(baseUrl, content, "text/html", "UTF-8", null);
            } catch (Exception e) {
                AppLogger.e(TAG, "加载HTML文件失败: " + e.getMessage(), e);
                loadData("<html><body><h1>加载失败</h1><p>无法加载HTML文件</p><p>错误: " + e.getMessage() + "</p></body></html>", "text/html", "UTF-8");
            }
        } else if (fileName.endsWith(".txt") || fileName.endsWith(".md") || fileName.endsWith(".markdown")) {
            // 加载文本和Markdown文件
            loadTextFile(file);
        } else if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") || fileName.endsWith(".png") || fileName.endsWith(".gif") || fileName.endsWith(".bmp")) {
            // 加载图片文件，确保宽度填充，高度可滑动
            loadImageFile(file);
        } else if (fileName.endsWith(".pdf") || fileName.endsWith(".doc") || fileName.endsWith(".docx") || fileName.endsWith(".xls") || fileName.endsWith(".xlsx") || fileName.endsWith(".ppt") || fileName.endsWith(".pptx")) {
            // 加载Office和PDF文件
            loadOfficeFile(file, fileUrl);
        } else if (fileName.endsWith(".csv")) {
            // CSV文件使用Excel渲染引擎处理
            loadOfficeFile(file, fileUrl);
        } else {
            // 对于其他文件类型，显示错误信息
            loadData("<html><body><h1>不支持的文件类型</h1><p>当前不支持查看此类文件</p><p>文件路径: " + file.getAbsolutePath() + "</p></body></html>", "text/html", "UTF-8");
        }
    }
    
    /**
     * 检查网络连接状态
     * @return 是否有网络连接
     */
    private boolean isNetworkConnected() {
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            android.net.NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
        }
        return false;
    }
    
    /**
     * 加载Office和PDF文件
     * @param file 文件
     * @param fileUrl 文件URL
     */
    private void loadOfficeFile(File file, String fileUrl) {
        // 方案1：统一使用文件渲染页预览，避免两套渲染管线
        try {
            Intent intent = new Intent(this, com.oilquiz.app.ui.activity.FileRenderActivity.class);
            intent.putExtra(com.oilquiz.app.ui.activity.FileRenderActivity.EXTRA_FILE_PATH, file.getAbsolutePath());
            startActivity(intent);
        } catch (Exception e) {
            AppLogger.e(TAG, "打开文件渲染页失败: " + e.getMessage(), e);
        }
    }
    
    
    /**
     * 加载图片文件，确保宽度填充，高度可滑动
     * @param file 图片文件
     */
    private void loadImageFile(File file) {
        String fileUrl = "file://" + file.getAbsolutePath();
        String fileName = file.getName();
        long fileSize = file.length();
        
        // 创建一个更美观的HTML页面来显示图片
        String htmlContent = "<!DOCTYPE html>" +
            "<html lang=\"zh-CN\">" +
            "<head>" +
            "<meta charset=\"UTF-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=3.0\">" +
            "<title>图片预览 - " + fileName + "</title>" +
            "<style>" +
            "* { box-sizing: border-box; margin: 0; padding: 0; }" +
            "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%); min-height: 100vh; padding: 20px; }" +
            ".container { max-width: 900px; margin: 0 auto; }" +
            ".header { text-align: center; margin-bottom: 20px; padding: 20px; background: rgba(255,255,255,0.1); border-radius: 12px; backdrop-filter: blur(10px); }" +
            ".header h1 { color: #fff; font-size: 18px; margin-bottom: 8px; }" +
            ".header .meta { color: rgba(255,255,255,0.7); font-size: 13px; }" +
            ".image-wrapper { background: #000; border-radius: 12px; overflow: hidden; box-shadow: 0 20px 60px rgba(0,0,0,0.5); }" +
            ".image-wrapper img { width: 100%; height: auto; display: block; transition: transform 0.3s ease; }" +
            ".image-wrapper img:hover { transform: scale(1.02); }" +
            ".controls { display: flex; justify-content: center; gap: 10px; margin-top: 20px; flex-wrap: wrap; }" +
            ".btn { background: rgba(255,255,255,0.2); color: #fff; border: none; padding: 10px 20px; border-radius: 8px; cursor: pointer; font-size: 14px; transition: all 0.3s; }" +
            ".btn:hover { background: rgba(255,255,255,0.3); }" +
            "</style>" +
            "</head>" +
            "<body>" +
            "<div class=\"container\">" +
            "<div class=\"header\">" +
            "<h1>🖼️ " + fileName + "</h1>" +
            "<div class=\"meta\">大小: " + formatFileSize(fileSize) + " | 点击可缩放</div>" +
            "</div>" +
            "<div class=\"image-wrapper\">" +
            "<img src=\"" + fileUrl + "\" alt=\"" + fileName + "\" id=\"previewImage\">" +
            "</div>" +
            "<div class=\"controls\">" +
            "<button class=\"btn\" onclick=\"zoomIn()\">🔍+ 放大</button>" +
            "<button class=\"btn\" onclick=\"zoomOut()\">🔍- 缩小</button>" +
            "<button class=\"btn\" onclick=\"resetZoom()\">↺ 重置</button>" +
            "</div>" +
            "</div>" +
            "<script>" +
            "let scale = 1;" +
            "const img = document.getElementById('previewImage');" +
            "function zoomIn() { scale = Math.min(scale + 0.2, 3); img.style.transform = 'scale(' + scale + ')'; }" +
            "function zoomOut() { scale = Math.max(scale - 0.2, 0.5); img.style.transform = 'scale(' + scale + ')'; }" +
            "function resetZoom() { scale = 1; img.style.transform = 'scale(1)'; }" +
            "img.onclick = function() { if (scale === 1) { zoomIn(); } else { resetZoom(); } };" +
            "</script>" +
            "</body>" +
            "</html>";
        
        loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null);
    }

    private void loadTextFile(File file) {
        String fileName = file.getName();
        long fileSize = file.length();
        
        try {
            // 检测文件编码
            String encoding = detectFileEncoding(file);
            
            // 读取文本文件内容
            StringBuilder content = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(file), encoding))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
            }
            
            // 转义HTML特殊字符
            String escapedContent = escapeHtml(content.toString());
            
            // 判断是否是Markdown文件
            boolean isMarkdown = fileName.toLowerCase().endsWith(".md") || fileName.toLowerCase().endsWith(".markdown");
            
            String htmlContent;
            if (isMarkdown) {
                // Markdown文件使用简单的Markdown渲染
                htmlContent = renderMarkdown(fileName, fileSize, escapedContent, content.toString());
            } else {
                // 普通文本文件
                htmlContent = renderTextFile(fileName, fileSize, escapedContent, encoding);
            }
            
            loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null);
        } catch (Exception e) {
            AppLogger.e(TAG, "加载文本文件失败: " + e.getMessage(), e);
            showFileError(file, "无法读取文本文件: " + e.getMessage());
        }
    }
    
    /**
     * 检测文件编码
     */
    private String detectFileEncoding(File file) {
        // 使用CharsetDetector智能检测编码，覆盖BOM、UTF-8、GB18030/GBK等
        try {
            CharsetDetector.DetectionResult result = CharsetDetector.detectCharset(file);
            AppLogger.d(TAG, "文件编码检测结果: " + result);
            return result.charset;
        } catch (Exception e) {
            AppLogger.w(TAG, "CharsetDetector编码检测失败，回退UTF-8: " + e.getMessage());
            return "UTF-8";
        }
    }
    
    /**
     * 转义HTML特殊字符
     */
    private String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#x27;");
    }
    
    /**
     * 格式化文件大小
     */
    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024.0));
        return String.format("%.1f GB", size / (1024.0 * 1024.0 * 1024.0));
    }
    
    /**
     * 渲染文本文件为HTML
     */
    private String renderTextFile(String fileName, long fileSize, String escapedContent, String encoding) {
        return "<!DOCTYPE html>" +
            "<html lang=\"zh-CN\">" +
            "<head>" +
            "<meta charset=\"UTF-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
            "<title>文本预览 - " + fileName + "</title>" +
            "<style>" +
            "* { box-sizing: border-box; margin: 0; padding: 0; }" +
            "body { font-family: 'SF Mono', Monaco, 'Courier New', monospace; background: #f8f9fa; min-height: 100vh; }" +
            ".header { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; padding: 20px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }" +
            ".header h1 { font-size: 18px; font-weight: 600; }" +
            ".header .meta { font-size: 12px; opacity: 0.9; margin-top: 5px; }" +
            ".content { padding: 20px; }" +
            ".text-container { background: white; border-radius: 8px; padding: 20px; box-shadow: 0 2px 8px rgba(0,0,0,0.1); overflow-x: auto; }" +
            ".text-content { white-space: pre-wrap; word-wrap: break-word; line-height: 1.6; color: #333; font-size: 14px; }" +
            ".line-numbers { position: absolute; left: 0; top: 0; bottom: 0; width: 50px; background: #f1f3f4; border-right: 1px solid #e0e0e0; text-align: right; padding: 20px 10px; color: #999; font-size: 12px; line-height: 1.6; }" +
            "</style>" +
            "</head>" +
            "<body>" +
            "<div class=\"header\">" +
            "<h1>📄 " + fileName + "</h1>" +
            "<div class=\"meta\">大小: " + formatFileSize(fileSize) + " | 编码: " + encoding + "</div>" +
            "</div>" +
            "<div class=\"content\">" +
            "<div class=\"text-container\">" +
            "<pre class=\"text-content\">" + escapedContent + "</pre>" +
            "</div>" +
            "</div>" +
            "</body>" +
            "</html>";
    }
    
    /**
     * 渲染Markdown文件为HTML
     */
    private String renderMarkdown(String fileName, long fileSize, String escapedContent, String rawContent) {
        // 简单的Markdown转换
        String html = rawContent
            // 代码块
            .replaceAll("(?m)^```(\\w*)\\s*\n(.*?)\n```", "<pre style='background:#f4f4f4;padding:15px;border-radius:6px;overflow-x:auto;'><code>$2</code></pre>")
            // 行内代码
            .replaceAll("`([^`]+)`", "<code style='background:#f4f4f4;padding:2px 6px;border-radius:3px;font-family:monospace;'>$1</code>")
            // 标题
            .replaceAll("(?m)^###### (.+)$", "<h6 style='color:#666;margin:10px 0;'>$1</h6>")
            .replaceAll("(?m)^##### (.+)$", "<h5 style='color:#555;margin:12px 0;'>$1</h5>")
            .replaceAll("(?m)^#### (.+)$", "<h4 style='color:#444;margin:14px 0;'>$1</h4>")
            .replaceAll("(?m)^### (.+)$", "<h3 style='color:#333;margin:16px 0;border-bottom:2px solid #667eea;padding-bottom:8px;'>$1</h3>")
            .replaceAll("(?m)^## (.+)$", "<h2 style='color:#222;margin:20px 0;border-bottom:3px solid #667eea;padding-bottom:10px;'>$1</h2>")
            .replaceAll("(?m)^# (.+)$", "<h1 style='color:#111;margin:24px 0;border-bottom:4px solid #667eea;padding-bottom:12px;'>$1</h1>")
            // 粗体和斜体
            .replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>")
            .replaceAll("\\*(.+?)\\*", "<em>$1</em>")
            // 链接
            .replaceAll("\\[([^\\]]+)\\]\\(([^\\)]+)\\)", "<a href='$2' style='color:#667eea;text-decoration:none;'>$1</a>")
            // 列表
            .replaceAll("(?m)^- (.+)$", "<li style='margin:5px 0;'>$1</li>")
            // 段落
            .replaceAll("\n\n", "</p><p style='margin:15px 0;line-height:1.8;color:#333;'>")
            // 换行
            .replaceAll("\n", "<br>");
        
        return "<!DOCTYPE html>" +
            "<html lang=\"zh-CN\">" +
            "<head>" +
            "<meta charset=\"UTF-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
            "<title>Markdown预览 - " + fileName + "</title>" +
            "<style>" +
            "* { box-sizing: border-box; margin: 0; padding: 0; }" +
            "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #f8f9fa; min-height: 100vh; }" +
            ".header { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; padding: 20px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }" +
            ".header h1 { font-size: 18px; font-weight: 600; }" +
            ".header .meta { font-size: 12px; opacity: 0.9; margin-top: 5px; }" +
            ".content { padding: 20px; }" +
            ".markdown-body { background: white; border-radius: 8px; padding: 30px; box-shadow: 0 2px 8px rgba(0,0,0,0.1); max-width: 800px; margin: 0 auto; }" +
            ".markdown-body p { margin: 15px 0; line-height: 1.8; color: #333; }" +
            ".markdown-body ul, .markdown-body ol { margin: 15px 0; padding-left: 30px; }" +
            ".markdown-body li { margin: 5px 0; }" +
            ".markdown-body blockquote { border-left: 4px solid #667eea; padding-left: 20px; margin: 20px 0; color: #666; background: #f8f9fa; padding: 15px 20px; border-radius: 0 6px 6px 0; }" +
            "</style>" +
            "</head>" +
            "<body>" +
            "<div class=\"header\">" +
            "<h1>📝 " + fileName + "</h1>" +
            "<div class=\"meta\">大小: " + formatFileSize(fileSize) + " | Markdown文档</div>" +
            "</div>" +
            "<div class=\"content\">" +
            "<div class=\"markdown-body\"><p>" + html + "</p></div>" +
            "</div>" +
            "</body>" +
            "</html>";
    }
    
    /**
     * 显示文件错误信息
     */
    private void showFileError(File file, String errorMessage) {
        String fileName = file.getName();
        long fileSize = file.length();
        
        String errorHtml = "<!DOCTYPE html>" +
            "<html><head><meta charset='UTF-8'>" +
            "<meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
            "<style>" +
            "body { font-family: Arial, sans-serif; margin: 20px; background: linear-gradient(135deg, #ff6b6b, #ee5a24); min-height: 100vh; display: flex; align-items: center; justify-content: center; }" +
            ".container { max-width: 500px; background: white; padding: 40px; border-radius: 16px; box-shadow: 0 20px 60px rgba(0,0,0,0.3); text-align: center; }" +
            ".icon { font-size: 80px; margin-bottom: 20px; }" +
            "h1 { color: #e74c3c; font-size: 24px; margin-bottom: 15px; }" +
            ".info { background: #f8f9fa; padding: 15px; border-radius: 8px; margin: 20px 0; text-align: left; }" +
            ".info p { margin: 8px 0; color: #555; font-size: 14px; }" +
            ".error { color: #e74c3c; margin-top: 15px; padding: 12px; background: #fdf2f2; border-radius: 6px; font-size: 13px; }" +
            "</style></head><body>" +
            "<div class='container'>" +
            "<div class='icon'>⚠️</div>" +
            "<h1>文件加载失败</h1>" +
            "<div class='info'>" +
            "<p><strong>文件名:</strong> " + fileName + "</p>" +
            "<p><strong>文件大小:</strong> " + formatFileSize(fileSize) + "</p>" +
            "</div>" +
            "<div class='error'>" +
            "<p><strong>错误信息:</strong> " + (errorMessage != null ? errorMessage : "未知错误") + "</p>" +
            "</div>" +
            "</div></body></html>";
        
        loadDataWithBaseURL(null, errorHtml, "text/html", "UTF-8", null);
    }

    private File getLatestFileFromExportDir() {
        // 获取导出文件夹
        File exportDir = getExportDirectory();
        if (exportDir == null || !exportDir.exists()) {
            return null;
        }

        // 查找所有文件
        File[] files = exportDir.listFiles(new java.io.FileFilter() {
            @Override
            public boolean accept(File file) {
                return file.isFile();
            }
        });

        if (files == null || files.length == 0) {
            return null;
        }

        // 找到最新的文件
        File latestFile = files[0];
        for (File file : files) {
            if (file.lastModified() > latestFile.lastModified()) {
                latestFile = file;
            }
        }

        return latestFile;
    }

    private File getExportDirectory() {
        // 导出到应用临时目录
        File exportDir = new File(getCacheDir(), "exports");
        if (!exportDir.exists()) {
            exportDir.mkdirs();
        }
        return exportDir;
    }

    private void createNewTab() {
        if (newTabButton != null) {
            tabContainer.removeView(newTabButton);
        }
        
        // 使用WebViewLoadManager从池中获取WebView（优化内存使用）
        WebView newWebView = initOptimizedWebViewForTab();
        
        if (newWebView != null) {
            webViewList.add(newWebView);
            
            final LinearLayout tab = new LinearLayout(this);
            tab.setOrientation(LinearLayout.HORIZONTAL);
            tab.setPadding(16, 16, 16, 16);
            tab.setClickable(true);
            tab.setFocusable(true);
            tab.setFocusableInTouchMode(true);
            final int tabIndex = webViewList.size() - 1;
            
            LinearLayout.LayoutParams tabLayoutParams = new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1.0f
            );
            tabLayoutParams.setMargins(0, 0, 8, 0);
            tab.setLayoutParams(tabLayoutParams);
            
            tab.setBackgroundResource(android.R.color.transparent);
            
            TextView tabTitle = new TextView(this);
            // 第一个标签为默认标签
            if (tabIndex == 0) {
                tabTitle.setText(getString(R.string.h_0113d26f));
            } else {
                tabTitle.setText(getString(R.string.h_20f0dc68));
            }
            tabTitle.setTextSize(15);
            tabTitle.setTextColor(ThemeColors.attr(WebViewActivity.this, R.attr.colorControlTextSecondary));
            tabTitle.setGravity(android.view.Gravity.CENTER);
            tabTitle.setSingleLine(true);
            tabTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1.0f
            );
            tabTitle.setLayoutParams(titleParams);
            tab.addView(tabTitle);
            tabList.add(tabTitle);
            
            Button closeButton = new Button(this);
            closeButton.setText("×");
            closeButton.setTextSize(20);
            closeButton.setPadding(14, 10, 14, 10);
            closeButton.setTextColor(ThemeColors.attr(WebViewActivity.this, R.attr.colorControlTextSecondary));
            closeButton.setAllCaps(false);
            closeButton.setBackgroundResource(android.R.color.transparent);
            closeButton.setClickable(true);
            closeButton.setMinWidth(0);
            closeButton.setMinimumWidth(0);
            closeButton.setMinHeight(0);
            closeButton.setMinimumHeight(0);
            closeButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeTab(tabIndex);
                }
            });
            LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            closeParams.setMargins(8, 0, 0, 0);
            closeButton.setLayoutParams(closeParams);
            tab.addView(closeButton);
            
            tab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchTab(tabIndex);
                }
            });
            
            tabContainer.addView(tab);
            
            addNewTabButton();
            
            // 直接使用成员变量webViewContainer添加新的WebView
            if (webViewContainer != null) {
                webViewContainer.addView(newWebView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                ));
            }
            
            switchTab(tabIndex);
            
            // 只有在没有传入自定义URL时才加载默认页面
            if (customUrl == null) {
                // 使用WebViewLoadManager加载 Bing 搜索引擎作为主页（带延迟优化）
                webViewLoadManager.loadUrlDelayed(x5WebView, "https://www.bing.com", 50);
            }
        }
    }

    /**
     * 初始化优化的WebView（使用WebViewLoadManager）
     */
    private WebView initOptimizedWebViewForTab() {
        AppLogger.d(TAG, "使用优化的WebView加载管理器");
        
        // 从WebViewLoadManager池中获取WebView
        WebView webView = webViewLoadManager.acquireWebView();
        
        // 设置WebView客户端 - 使用支持文件重定向的客户端
        RedirectWebViewClient redirectWebViewClient = new RedirectWebViewClient();
        redirectWebViewClient.setFileRedirectEnabled(true);
        redirectWebViewClient.setPageLoadCallback(new RedirectWebViewClient.PageLoadCallback() {
            @Override
            public void onPageStarted(String url) {
                AppLogger.d(TAG, "页面开始加载 " + url);
                // 触发全局加载回调
                if (webViewLoadManager != null) {
                    // 通过WebViewLoadManager的回调通知UI更新
                }
            }

            @Override
            public void onPageFinished(String url) {
                AppLogger.d(TAG, "页面加载完成: " + url);
                // 注入JavaScript来优化页面显示
                injectOptimizationScript(webView);
                // 更新标签页标题
                updateTabTitleFromWebView(currentTabIndex);
            }

            @Override
            public void onError(int errorCode, String description, String failingUrl) {
                AppLogger.e(TAG, "页面加载错误 [" + errorCode + "]: " + description);
                // 显示错误页面
                String errorHtml = "<html><body style='background-color: #f5f5f5; text-align: center; padding: 40px;'><h1>加载失败</h1><p>无法加载页面，请检查网络连接或文件路径</p><p>错误: " + description + "</p></body></html>";
                webView.loadData(errorHtml, "text/html", "UTF-8");
            }
        });
        webView.setWebViewClient(redirectWebViewClient);
        
        // 设置WebChromeClient来处理进度和标题
        webView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, android.webkit.GeolocationPermissions.Callback callback) {
                if (permissionProvider != null && permissionProvider.hasLocationPermission()) {
                    callback.invoke(origin, true, false);
                } else {
                    permissionProvider.requestLocationPermission(WebViewActivity.this, new com.oilquiz.app.resource.PermissionResourceProvider.PermissionCallback() {
                        @Override
                        public void onGranted() {
                            callback.invoke(origin, true, false);
                        }

                        @Override
                        public void onDenied(java.util.List<String> deniedPermissions) {
                            callback.invoke(origin, false, false);
                        }
                    });
                }
            }

            @Override
            public void onPermissionRequest(android.webkit.PermissionRequest request) {
                handleWebViewPermissionRequest(request);
            }

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);
                AppLogger.d(TAG, "加载进度: " + newProgress + "%");
                if (pageProgressBar != null) {
                    pageProgressBar.setProgress(newProgress);
                }
                if (loadingStatusText != null) {
                    loadingStatusText.setText(getString(R.string.h_c96070db) + newProgress + "%");
                }
            }
            
            @Override
            public void onReceivedTitle(WebView view, String title) {
                super.onReceivedTitle(view, title);
                if (currentTabIndex >= 0 && currentTabIndex < tabList.size()) {
                    tabList.get(currentTabIndex).setText(title);
                    updateTabTitleFromWebView(currentTabIndex);
                }
            }
        });
        
        // 添加JavaScript接口
        addJavascriptInterface(webView);
        
        // 设置下载监听器，处理文件下载
        webView.setDownloadListener(new android.webkit.DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                AppLogger.d(TAG, "开始下载: " + url);
                AppLogger.d(TAG, "文件类型: " + mimeType);
                AppLogger.d(TAG, "文件大小: " + contentLength + " bytes");

                // .gguf 模型文件：用 App 内部下载器下载到 ai_models 目录
                if (url != null) {
                    String cleanUrl = url;
                    int qIdx = cleanUrl.indexOf('?');
                    if (qIdx > 0) cleanUrl = cleanUrl.substring(0, qIdx);
                    if (cleanUrl.toLowerCase().endsWith(".gguf")) {
                        String fileName = cleanUrl.substring(cleanUrl.lastIndexOf('/') + 1);
                        try {
                            com.oilquiz.app.ai.model.ModelDownloadManager dm =
                                com.oilquiz.app.ai.model.ModelDownloadManager.getInstance(WebViewActivity.this);
                            dm.downloadFromCustomUrl(fileName, url, null);
                            android.widget.Toast.makeText(WebViewActivity.this,
                                getString(R.string.h_42611d28) + fileName, android.widget.Toast.LENGTH_LONG).show();
                            return;
                        } catch (Exception e) {
                            AppLogger.e(TAG, "模型下载失败: " + e.getMessage());
                        }
                    }
                }

                // 处理其他下载请求
                android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                intent.setData(android.net.Uri.parse(url));
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                
                try {
                    WebViewActivity.this.startActivity(intent);
                    AppLogger.d(TAG, "已启动下载: " + url);
                } catch (android.content.ActivityNotFoundException e) {
                    AppLogger.e(TAG, "无法处理下载: " + e.getMessage());
                    // 显示错误提示
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        android.widget.Toast.makeText(WebViewActivity.this,
                            "无法处理此下载，请检查是否有相应的应用",
                            android.widget.Toast.LENGTH_SHORT).show();
                    });
                }
            }
        });
        
        x5WebView = webView;
        
        AppLogger.d(TAG, "优化的WebView初始化成功，当前池大小: " + webViewLoadManager.getPoolSize());
        
        return webView;
    }
    
    /**
     * 从WebView更新标签页标题样式
     */
    private void updateTabTitleFromWebView(int tabIndex) {
        if (tabIndex < 0 || tabIndex >= tabList.size()) return;
        
        // 确保选中标签页的文字颜色保持为primary色
        tabList.get(tabIndex).setTextColor(ThemeColors.attr(WebViewActivity.this, R.attr.colorPrimary));
        
        // 确保关闭按钮的文字颜色也保持为primary色
        android.view.ViewParent tabParent = tabList.get(tabIndex).getParent();
        if (tabParent instanceof LinearLayout) {
            LinearLayout tabLayout = (LinearLayout) tabParent;
            for (int j = 0; j < tabLayout.getChildCount(); j++) {
                View child = tabLayout.getChildAt(j);
                if (child instanceof Button) {
                    ((Button) child).setTextColor(ThemeColors.attr(WebViewActivity.this, R.attr.colorPrimary));
                    ((Button) child).setBackgroundResource(android.R.color.transparent);
                }
            }
        }
    }
    
    /**
     * 传统的WebView初始化方法（保留用于兼容）
     */
    private WebView initSystemWebViewForTab() {
        AppLogger.d(TAG, "使用系统WebView（传统模式）");
        // 创建WebView，直接使用系统WebView
        WebView webView = new WebView(this);
        
        // 启用硬件加速
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null);
        }
        
        WebSettings webSettings = webView.getSettings();
        AppResourceManager.getInstance(this).webView().configureWebSettings(webSettings);
        
        webSettings.setGeolocationEnabled(true);
        webSettings.setGeolocationDatabasePath(getFilesDir().getPath());
        
        WebViewSecurityConfig.configure(webSettings);
        
        // 实现自适应显示功能
        webSettings.setDefaultFontSize(16);
        webSettings.setMinimumFontSize(12);
        webSettings.setDefaultFixedFontSize(16);
        webSettings.setTextZoom(100);
        
        // 优化硬件加速
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null);
        }
        
        // 优化资源加载
        webSettings.setLoadsImagesAutomatically(true);
        webSettings.setBlockNetworkImage(false);
        
        // 设置WebView客户端 - 使用支持文件重定向的客户端
        RedirectWebViewClient redirectWebViewClient = new RedirectWebViewClient();
        redirectWebViewClient.setFileRedirectEnabled(true);
        redirectWebViewClient.setPageLoadCallback(new RedirectWebViewClient.PageLoadCallback() {
            @Override
            public void onPageStarted(String url) {
                AppLogger.d(TAG, "页面开始加载 " + url);
            }

            @Override
            public void onPageFinished(String url) {
                AppLogger.d(TAG, "页面加载完成: " + url);
                // 注入JavaScript来优化页面显示
                injectOptimizationScript(webView);
            }

            @Override
            public void onError(int errorCode, String description, String failingUrl) {
                AppLogger.e(TAG, "页面加载错误 [" + errorCode + "]: " + description);
                // 显示错误页面
                String errorHtml = "<html><body style='background-color: #f5f5f5; text-align: center; padding: 40px;'><h1>加载失败</h1><p>无法加载页面，请检查网络连接或文件路径</p><p>错误: " + description + "</p></body></html>";
                webView.loadData(errorHtml, "text/html", "UTF-8");
            }
        });
        webView.setWebViewClient(redirectWebViewClient);
        
        // 设置WebChromeClient来处理进度和标题
        webView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, android.webkit.GeolocationPermissions.Callback callback) {
                if (permissionProvider != null && permissionProvider.hasLocationPermission()) {
                    callback.invoke(origin, true, false);
                } else {
                    permissionProvider.requestLocationPermission(WebViewActivity.this, new com.oilquiz.app.resource.PermissionResourceProvider.PermissionCallback() {
                        @Override
                        public void onGranted() {
                            callback.invoke(origin, true, false);
                        }

                        @Override
                        public void onDenied(java.util.List<String> deniedPermissions) {
                            callback.invoke(origin, false, false);
                        }
                    });
                }
            }

            @Override
            public void onPermissionRequest(android.webkit.PermissionRequest request) {
                handleWebViewPermissionRequest(request);
            }

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);
                AppLogger.d(TAG, "加载进度: " + newProgress + "%");
            }
            
            @Override
            public void onReceivedTitle(WebView view, String title) {
                super.onReceivedTitle(view, title);
                // 可以在这里更新标签页标题
                if (currentTabIndex >= 0 && currentTabIndex < tabList.size()) {
                    tabList.get(currentTabIndex).setText(title);
                    // 确保选中标签页的文字颜色保持为primary色
                    tabList.get(currentTabIndex).setTextColor(ThemeColors.attr(WebViewActivity.this, R.attr.colorPrimary));
                    // 确保关闭按钮的文字颜色也保持为primary色
                    android.view.ViewParent tabParent = tabList.get(currentTabIndex).getParent();
                    if (tabParent instanceof LinearLayout) {
                        LinearLayout tabLayout = (LinearLayout) tabParent;
                        for (int j = 0; j < tabLayout.getChildCount(); j++) {
                            View child = tabLayout.getChildAt(j);
                            if (child instanceof Button) {
                                ((Button) child).setTextColor(ThemeColors.attr(WebViewActivity.this, R.attr.colorPrimary));
                                ((Button) child).setBackgroundResource(android.R.color.transparent);
                            }
                        }
                    }
                }
            }
        });
        
        // 添加JavaScript接口
        addJavascriptInterface(webView);
        
        x5WebView = webView;
        
        // 获取WebView版本信息
        String webViewVersion = getWebViewVersionInfo();
        AppLogger.d(TAG, "系统WebView初始化成功，版本: " + webViewVersion);
        
        return webView;
    }
    
    /**
     * 获取WebView版本信息（简化版，避免线程问题）
     * @return WebView版本信息
     */
    private String getWebViewVersionInfo() {
        try {
            // 使用WebSettings获取UserAgent来推断WebView版本
            String userAgent = android.webkit.WebSettings.getDefaultUserAgent(this);
            // 从UserAgent中提取Chrome版本号
            if (userAgent != null && userAgent.contains("Chrome/")) {
                int start = userAgent.indexOf("Chrome/") + 7;
                int end = userAgent.indexOf(" ", start);
                if (end == -1) end = userAgent.length();
                return "Chrome " + userAgent.substring(start, end);
            }
            return "系统WebView";
        } catch (Exception e) {
            AppLogger.e(TAG, "获取WebView版本失败: " + e.getMessage());
            return "未知";
        }
    }

    private void addNewTabButton() {
        newTabButton = new Button(this);
        newTabButton.setText("+");
        newTabButton.setTextSize(26);
        newTabButton.setPadding(20, 16, 20, 16);
        newTabButton.setTextColor(ThemeColors.attr(WebViewActivity.this, R.attr.colorControlTextSecondary));
        newTabButton.setAllCaps(false);
        newTabButton.setBackgroundResource(android.R.color.transparent);
        newTabButton.setMinWidth(0);
        newTabButton.setMinimumWidth(0);
        newTabButton.setMinHeight(0);
        newTabButton.setMinimumHeight(0);
        
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        buttonParams.setMargins(0, 0, 0, 0);
        newTabButton.setLayoutParams(buttonParams);
        
        newTabButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                createNewTab();
            }
        });
        
        tabContainer.addView(newTabButton);
    }

    private void switchTab(int index) {
        if (index < 0 || index >= webViewList.size()) {
            return;
        }
        
        for (Object wv : webViewList) {
            if (wv instanceof View) {
                ((View) wv).setVisibility(View.GONE);
            }
        }
        
        x5WebView = webViewList.get(index);
        if (x5WebView instanceof View) {
            ((View) x5WebView).setVisibility(View.VISIBLE);
        }
        currentTabIndex = index;
        
        // 更新标签样式 - 类似底部导航栏
        for (int i = 0; i < tabList.size(); i++) {
            android.view.ViewParent tabParent = tabList.get(i).getParent();
            if (tabParent instanceof LinearLayout) {
                LinearLayout tabLayout = (LinearLayout) tabParent;
                if (i == index) {
                    // 选中状态 - 类似底部导航栏激活状态
                    tabLayout.setBackgroundResource(android.R.color.transparent);
                    tabList.get(i).setTextColor(ThemeColors.attr(WebViewActivity.this, R.attr.colorPrimary));
                    // 找到关闭按钮并修改其文字颜色
                    for (int j = 0; j < tabLayout.getChildCount(); j++) {
                        View child = tabLayout.getChildAt(j);
                        if (child instanceof Button) {
                            ((Button) child).setTextColor(ThemeColors.attr(WebViewActivity.this, R.attr.colorPrimary));
                            ((Button) child).setBackgroundResource(android.R.color.transparent);
                        }
                    }
                } else {
                    // 未选中状态
                    tabLayout.setBackgroundResource(android.R.color.transparent);
                    tabList.get(i).setTextColor(ThemeColors.attr(WebViewActivity.this, R.attr.colorControlTextSecondary));
                    // 找到关闭按钮并修改其文字颜色
                    for (int j = 0; j < tabLayout.getChildCount(); j++) {
                        View child = tabLayout.getChildAt(j);
                        if (child instanceof Button) {
                            ((Button) child).setTextColor(ThemeColors.attr(WebViewActivity.this, R.attr.colorControlTextSecondary));
                            ((Button) child).setBackgroundResource(android.R.color.transparent);
                        }
                    }
                }
            }
        }
    }

    private void closeTab(int index) {
        if (index < 0 || index >= webViewList.size()) {
            return;
        }
        
        // 防止关闭默认标签
        if (index == 0) {
            android.widget.Toast.makeText(this, getString(R.string.h_69e1adfa), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (webViewList.size() == 1) {
            android.widget.Toast.makeText(this, getString(R.string.h_ae735e4b), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (newTabButton != null) {
            tabContainer.removeView(newTabButton);
        }
        
        WebView wv = webViewList.get(index);
        if (wv != null) {
            // 使用WebViewLoadManager释放WebView（回收到池中）
            if (webViewLoadManager != null) {
                webViewLoadManager.releaseWebView(wv);
                AppLogger.d(TAG, "关闭标签页，WebView已回收到池，当前池大小: " + webViewLoadManager.getPoolSize());
            } else {
                // 降级处理：直接销毁
                wv.destroy();
            }
        }
        webViewList.remove(index);
        
        if (index < tabList.size()) {
            TextView tabTitle = tabList.get(index);
            if (tabTitle.getParent() instanceof LinearLayout) {
                tabContainer.removeView((View) tabTitle.getParent());
            }
            tabList.remove(index);
        }
        
        addNewTabButton();
        
        if (currentTabIndex >= index) {
            currentTabIndex--;
        }
        
        switchTab(currentTabIndex);
    }

    private Button createNavButton(String text, String tooltip) {
        Button button = new Button(this);
        button.setText(text);
        button.setLayoutParams(new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1
        ));
        button.setPadding(12, 8, 12, 8);
        button.setTextSize(16);
        button.setTextColor(getResources().getColor(R.color.black));
        button.setBackground(createRoundedBackground(uiAdapter.getSurfaceColor(), 8));
        button.setTooltipText(tooltip);
        return button;
    }

    private android.graphics.drawable.Drawable createRoundedBackground(int color, int radius) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(radius);
        drawable.setColor(color);
        return drawable;
    }

    private void injectOptimizationScript(WebView view) {
        if (view == null) return;
        
        String url = view.getUrl();
        if (url == null) return;
        
        // 对于天气网页，不注入任何优化脚本，保持原始布局
        if (isWeatherWebsite(url)) {
            AppLogger.d(TAG, "天气网页，跳过布局优化脚本");
            return;
        }
        
        // 对于其他页面，只注入最小化的优化脚本
        String script = "javascript:(function() { " +
            "var body = document.body;" +
            "if (body && body.scrollWidth > window.innerWidth) {" +
            "  var style = document.createElement('style');" +
            "  style.textContent = 'img { max-width: 100%; height: auto; }';" +
            "  document.head.appendChild(style);" +
            "}" +
            "})();";
        view.loadUrl(script);
    }
    
    /**
     * 判断是否为天气网页
     */
    private boolean isWeatherWebsite(String url) {
        if (url == null) return false;
        String lowerUrl = url.toLowerCase();
        return lowerUrl.contains("qweather") || 
               lowerUrl.contains("hefeng") || 
               lowerUrl.contains("和风") ||
               lowerUrl.contains("weather") ||
               lowerUrl.contains("tianqi");
    }

    private void addJavascriptInterface(WebView webView) {
        // 使用模块化的安全接口（替代原来的 WebAppInterface）
        com.oilquiz.app.webview.js.JSInterfaceManager jsManager = 
            com.oilquiz.app.webview.js.JSInterfaceManager.getInstance(this);
        
        // 注册各模块接口
        webView.addJavascriptInterface(jsManager.getToolInterface(), "AndroidUtil");
        webView.addJavascriptInterface(jsManager.getClipboardInterface(), "AndroidClipboard");
        webView.addJavascriptInterface(jsManager.getFileInterface(), "AndroidFile");
        webView.addJavascriptInterface(jsManager.getDatabaseInterface(), "AndroidDatabase");
        
        // 保留旧接口（兼容模式，仅转发到新接口）
        webView.addJavascriptInterface(new WebAppInterface(), "Android");
        
        AppLogger.d(TAG, "JavaScript接口已注册（安全模式）");
    }

    public class WebAppInterface {
        // ==================== UI交互接口 ====================
        // 保留旧接口以兼容现有网页，逐步迁移到新接口
        
        @JavascriptInterface
        public void showToast(String message) {
            runOnUiThread(() -> {
                android.widget.Toast.makeText(WebViewActivity.this, message, android.widget.Toast.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface
        public void showToastLong(String message) {
            runOnUiThread(() -> {
                android.widget.Toast.makeText(WebViewActivity.this, message, android.widget.Toast.LENGTH_LONG).show();
            });
        }
        
        @JavascriptInterface
        public void vibrate(long milliseconds) {
            android.os.Vibrator vibrator = (android.os.Vibrator) WebViewActivity.this.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(milliseconds, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(milliseconds);
                }
            }
        }
        
        // ==================== 文件与系统接口 ====================
        
        @JavascriptInterface
        public void openFile() {
            runOnUiThread(() -> openFileChooser());
        }
        
        @JavascriptInterface
        public void finishActivity() {
            runOnUiThread(() -> finish());
        }
        
        // ==================== 信息获取接口 ====================
        
        @JavascriptInterface
        public String getAppVersion() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "未知";
            }
        }
        
        @JavascriptInterface
        public String getWebViewVersion() {
            return getWebViewVersionInfo();
        }
        
        @JavascriptInterface
        public String getPackageName() {
            return WebViewActivity.this.getPackageName();
        }
        
        @JavascriptInterface
        public boolean isNetworkAvailable() {
            return isNetworkConnected();
        }
        
        @JavascriptInterface
        public long getCurrentTime() {
            return System.currentTimeMillis();
        }
        
        @JavascriptInterface
        public String getDeviceInfo() {
            try {
                org.json.JSONObject info = new org.json.JSONObject();
                info.put("manufacturer", android.os.Build.MANUFACTURER);
                info.put("model", android.os.Build.MODEL);
                info.put("androidVersion", android.os.Build.VERSION.RELEASE);
                info.put("sdkVersion", android.os.Build.VERSION.SDK_INT);
                info.put("webViewVersion", getWebViewVersionInfo());
                info.put("appVersion", getAppVersion());
                return info.toString();
            } catch (Exception e) {
                return "{}";
            }
        }
        
        // ==================== 剪贴板接口 ====================
        
        @JavascriptInterface
        public void setClipboard(String text) {
            runOnUiThread(() -> {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) WebViewActivity.this.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    android.content.ClipData clip = android.content.ClipData.newPlainText("Copied Text", text);
                    clipboard.setPrimaryClip(clip);
                }
            });
        }
        
        @JavascriptInterface
        public String getClipboard() {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) WebViewActivity.this.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null && clipboard.hasPrimaryClip()) {
                android.content.ClipData clip = clipboard.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    android.content.ClipData.Item item = clip.getItemAt(0);
                    CharSequence text = item.getText();
                    return text != null ? text.toString() : "";
                }
            }
            return "";
        }
        
        // ==================== 分享与浏览器接口 ====================
        
        @JavascriptInterface
        public void shareText(String title, String text) {
            runOnUiThread(() -> {
                android.content.Intent shareIntent = new android.content.Intent(android.content.Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(android.content.Intent.EXTRA_TITLE, title);
                shareIntent.putExtra(android.content.Intent.EXTRA_TEXT, text);
                startActivity(android.content.Intent.createChooser(shareIntent, "分享到"));
            });
        }
        
        @JavascriptInterface
        public void openUrlInBrowser(String url) {
            runOnUiThread(() -> {
                try {
                    android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
                    startActivity(intent);
                } catch (Exception e) {
                    showToast(getString(R.string.h_7ec3be03));
                }
            });
        }
        

        
        // ==================== WebView导航接口 ====================
        
        @JavascriptInterface
        public String getQuestions() {
            // 返回缓存的题目数据
            return cachedQuestionsJson;
        }
        
        @JavascriptInterface
        public boolean isQuestionsDataLoaded() {
            // 返回数据是否加载完成
            return isQuestionsDataLoaded;
        }
        
        @JavascriptInterface
        public String getQuestionTypes() {
            // 返回缓存的题目类型数据
            return cachedQuestionTypesJson;
        }
        
        @JavascriptInterface
        public String getCategories() {
            // 返回缓存的分类数据
            return cachedCategoriesJson;
        }
        
        @JavascriptInterface
        public boolean importQuestions(String questionsJson, boolean replaceExisting) {
            try {
                Gson gson = new Gson();
                Type questionListType = new TypeToken<List<Question>>(){}.getType();
                List<Question> questions = gson.fromJson(questionsJson, questionListType);
                
                if (replaceExisting) {
                    // 清空现有题目
                    questionRepository.clearAllQuestions(new QuestionViewModel.BatchOperationCallback() {
                        @Override
                        public void onSuccess(int count) {
                            AppLogger.d(TAG, "清空现有题目成功");
                        }
                        
                        @Override
                        public void onError(String error) {
                            AppLogger.e(TAG, "清空现有题目失败: " + error);
                        }
                    });
                }
                
                // 导入新题目
                questionRepository.addQuestions(questions, new QuestionViewModel.BatchOperationCallback() {
                    @Override
                    public void onSuccess(int count) {
                        AppLogger.d(TAG, "导入题目成功，共 " + count + " 条");
                        // 重新加载题目数据
                        loadQuestionsData();
                    }
                    
                    @Override
                    public void onError(String error) {
                        AppLogger.e(TAG, "导入题目失败: " + error);
                    }
                });
                
                return true;
            } catch (Exception e) {
                AppLogger.e(TAG, "导入题目失败: " + e.getMessage(), e);
                return false;
            }
        }

        @JavascriptInterface
        public void reloadCurrentPage() {
            runOnUiThread(() -> {
                if (x5WebView != null) {
                    x5WebView.reload();
                }
            });
        }
        
        @JavascriptInterface
        public void goBack() {
            runOnUiThread(() -> {
                if (x5WebView != null && x5WebView.canGoBack()) {
                    x5WebView.goBack();
                }
            });
        }
        
        @JavascriptInterface
        public void goForward() {
            runOnUiThread(() -> {
                if (x5WebView != null && x5WebView.canGoForward()) {
                    x5WebView.goForward();
                }
            });
        }
        
        @JavascriptInterface
        public boolean canGoBack() {
            return x5WebView != null && x5WebView.canGoBack();
        }
        
        @JavascriptInterface
        public boolean canGoForward() {
            return x5WebView != null && x5WebView.canGoForward();
        }
        
        // ==================== 存储接口 ====================
        
        @JavascriptInterface
        public void setLocalStorage(String key, String value) {
            AppResourceManager.getInstance(WebViewActivity.this).config().setConfig("webview_" + key, value);
        }
        
        @JavascriptInterface
        public String getLocalStorage(String key) {
            return AppResourceManager.getInstance(WebViewActivity.this).getConfigString("webview_" + key, "");
        }
        
        @JavascriptInterface
        public void removeLocalStorage(String key) {
            AppResourceManager.getInstance(WebViewActivity.this).config().setConfig("webview_" + key, null);
        }
        
        // ==================== 日志接口 ====================
        
        @JavascriptInterface
        public void logDebug(String message) {
            AppLogger.d("WebView_JS", message);
        }
        
        @JavascriptInterface
        public void logInfo(String message) {
            AppLogger.i("WebView_JS", message);
        }
        
        @JavascriptInterface
        public void logError(String message) {
            AppLogger.e("WebView_JS", message, null);
        }
        
        // ==================== 统计数据接口 ====================
        
        @JavascriptInterface
        public int getQuestionCount() {
            try {
                com.oilquiz.app.database.AppDatabase db = com.oilquiz.app.database.AppDatabase.getDatabase(WebViewActivity.this);
                return db.questionDao().getQuestionCount();
            } catch (Exception e) {
                AppLogger.e(TAG, "获取题目数量失败: " + e.getMessage(), e);
                return 0;
            }
        }
        
        @JavascriptInterface
        public int getQuizCount() {
            // 从偏好设置中获取测验次数
            try {
                AppResourceManager resources = AppResourceManager.getInstance(WebViewActivity.this);
                return resources.getConfigInt("quiz_count", 0);
            } catch (Exception e) {
                AppLogger.e(TAG, "获取测验次数失败: " + e.getMessage(), e);
                return 0;
            }
        }
        
        @JavascriptInterface
        public int getStudyDays() {
            // 从偏好设置中获取学习天数
            try {
                AppResourceManager resources = AppResourceManager.getInstance(WebViewActivity.this);
                return resources.getConfigInt("study_days", 0);
            } catch (Exception e) {
                AppLogger.e(TAG, "获取学习天数失败: " + e.getMessage(), e);
                return 0;
            }
        }
        
        // ==================== 应用功能跳转接口 ====================
        
        @JavascriptInterface
        public void openQuestionManager() {
            runOnUiThread(() -> {
                Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.activity.QuestionActivity.class);
                startActivity(intent);
            });
        }
        
        @JavascriptInterface
        public void openQuestionBank() {
            runOnUiThread(() -> {
                Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.activity.QuestionActivity.class);
                startActivity(intent);
            });
        }
        
        @JavascriptInterface
        public void openStartQuiz() {
            runOnUiThread(() -> {
                Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.activity.StartQuizActivity.class);
                startActivity(intent);
            });
        }
        
        @JavascriptInterface
        public void openStudyPlan() {
            runOnUiThread(() -> {
                Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.activity.StudyPlanActivity.class);
                startActivity(intent);
            });
        }
        
        @JavascriptInterface
        public void openWrongQuestions() {
            runOnUiThread(() -> {
                Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.activity.WrongQuestionActivity.class);
                startActivity(intent);
            });
        }
        
        @JavascriptInterface
        public void openNotes() {
            runOnUiThread(() -> {
                Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.activity.NoteActivity.class);
                startActivity(intent);
            });
        }
        
        @JavascriptInterface
        public void openImport() {
            runOnUiThread(() -> {
                Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.activity.ImportActivity.class);
                startActivity(intent);
            });
        }
        
        @JavascriptInterface
        public void openExport() {
            runOnUiThread(() -> {
                Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.activity.ExportActivity.class);
                startActivity(intent);
            });
        }
        
        @JavascriptInterface
        public void openBackup() {
            runOnUiThread(() -> {
                Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.activity.BackupActivity.class);
                startActivity(intent);
            });
        }
        
        @JavascriptInterface
        public void openFilePreview() {
            runOnUiThread(() -> {
                Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.activity.FileRenderActivity.class);
                startActivity(intent);
            });
        }
        
        @JavascriptInterface
        public void openOCR() {
            runOnUiThread(() -> {
                Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.activity.OCRActivity.class);
                startActivity(intent);
            });
        }
        
        // @JavascriptInterface
        // public void openAIChat() {
        //     runOnUiThread(() -> {
        //         Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.activity.AIChatActivity.class);
        //         startActivity(intent);
        //     });
        // }
        
        @JavascriptInterface
        public void openHistory() {
            runOnUiThread(() -> {
                Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.activity.HistoryActivity.class);
                startActivity(intent);
            });
        }
        
        @JavascriptInterface
        public void openStatistics() {
            // 学习统计页面已移除，此处留空避免 JS 调用抛错
        }
        
        @JavascriptInterface
        public void openThemeSettings() {
            runOnUiThread(() -> {
                Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.activity.ThemeActivity.class);
                startActivity(intent);
            });
        }
        
        @JavascriptInterface
        public void openAbout() {
            // 关于页面已移除（深色适配问题），此处留空避免 JS 调用抛错
        }
        
        @JavascriptInterface
        public void openLogs() {
            runOnUiThread(() -> {
                Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.activity.LogsActivity.class);
                startActivity(intent);
            });
        }
        
        @JavascriptInterface
        public void openUser() {
            // 用户页已移除，留空避免 JS 调用抛错
        }
        
        @JavascriptInterface
        public void openSettings() {
            runOnUiThread(() -> {
                Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.activity.ThemeActivity.class);
                startActivity(intent);
            });
        }
        
        // ==================== 日志功能接口 ====================
        
        @JavascriptInterface
        public void clearLogs() {
            runOnUiThread(() -> {
                try {
                    // 清除应用日志
                    AppLogger.clearLogs();
                    showToast(getString(R.string.h_d42d97bd));
                } catch (Exception e) {
                    AppLogger.e(TAG, "清除日志失败: " + e.getMessage(), e);
                    showToast(getString(R.string.h_3bee0e63));
                }
            });
        }
        
        @JavascriptInterface
        public void exportLogs() {
            runOnUiThread(() -> {
                try {
                    // 导出日志文件
                    File logFile = AppLogger.exportLogs(WebViewActivity.this);
                    if (logFile != null) {
                        showToast(getString(R.string.h_83dbb868) + logFile.getAbsolutePath());
                    } else {
                        showToast(getString(R.string.h_ae4679c7));
                    }
                } catch (Exception e) {
                    AppLogger.e(TAG, "导出日志失败: " + e.getMessage(), e);
                    showToast(getString(R.string.h_ae4679c7));
                }
            });
        }
        
        @JavascriptInterface
        public void setLogLevel(String level) {
            try {
                // 设置日志级别
                AppLogger.setLogLevel(level);
                AppLogger.i(TAG, "日志级别已设置为: " + level);
            } catch (Exception e) {
                AppLogger.e(TAG, "设置日志级别失败: " + e.getMessage(), e);
            }
        }
        
        @JavascriptInterface
        public void openTemplateManager() {
            runOnUiThread(() -> {
                Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.activity.TemplateManagerActivity.class);
                startActivity(intent);
            });
        }
        
        @JavascriptInterface
        public void openLanguage() {
            runOnUiThread(() -> {
                Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.activity.LanguageActivity.class);
                startActivity(intent);
            });
        }
        
        @JavascriptInterface
        public void openTest() {
            // 测试页已移除，留空避免 JS 调用抛错
        }
        
        @JavascriptInterface
        public void openDatabaseDetail() {
            runOnUiThread(() -> {
                Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.activity.DatabaseDetailActivity.class);
                startActivity(intent);
            });
        }
        
        @JavascriptInterface
        public void openDataIssueFix() {
            runOnUiThread(() -> {
                Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.activity.DataIssueFixActivity.class);
                startActivity(intent);
            });
        }

        // ==================== 壳能力移植：系统交互接口 ====================

        @JavascriptInterface
        public String getVersion() {
            org.json.JSONObject o = new org.json.JSONObject();
            try {
                o.put("versionName", getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
                o.put("versionCode", getPackageManager().getPackageInfo(getPackageName(), 0).versionCode);
            } catch (Exception ignored) { }
            return o.toString();
        }

        @JavascriptInterface
        public String isCharging() {
            try {
                android.content.IntentFilter filter = new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                android.content.Intent status = registerReceiver(null, filter);
                int plugged = status == null ? 0 : status.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1);
                return String.valueOf(plugged != 0);
            } catch (Exception e) {
                return "false";
            }
        }

        @JavascriptInterface
        public String getStorageInfo() {
            org.json.JSONObject o = new org.json.JSONObject();
            try {
                java.io.File data = android.os.Environment.getDataDirectory();
                android.os.StatFs sf = new android.os.StatFs(data.getPath());
                long total = sf.getTotalBytes(), free = sf.getAvailableBytes();
                o.put("internalTotalMB", total / 1048576L);
                o.put("internalFreeMB", free / 1048576L);
                java.io.File ext = android.os.Environment.getExternalStorageDirectory();
                if (ext != null && ext.exists()) {
                    android.os.StatFs ef = new android.os.StatFs(ext.getPath());
                    o.put("externalTotalMB", ef.getTotalBytes() / 1048576L);
                    o.put("externalFreeMB", ef.getAvailableBytes() / 1048576L);
                } else {
                    o.put("externalTotalMB", 0);
                    o.put("externalFreeMB", 0);
                }
            } catch (Exception ignored) { }
            return o.toString();
        }

        @JavascriptInterface
        public void haptic() {
            vibrate(20);
        }

        @JavascriptInterface
        public void exit() {
            runOnUiThread(WebViewActivity.this::finish);
        }

        @JavascriptInterface
        public void requestPermission(String name, String callbackName) {
            if (callbackName == null || !callbackName.matches("[A-Za-z0-9_]{1,64}")) return;
            final String cb = callbackName;
            final String[] perms = bridgePermissionMapping(name);
            if (perms == null) {
                pendingBridgePermission = name;
                pendingBridgeCallback = cb;
                dispatchPermissionResult(name, false);
                pendingBridgePermission = null;
                pendingBridgeCallback = null;
                return;
            }
            if (perms.length == 0) {
                pendingBridgePermission = name;
                pendingBridgeCallback = cb;
                dispatchPermissionResult(name, true);
                pendingBridgePermission = null;
                pendingBridgeCallback = null;
                return;
            }
            boolean allGranted = true;
            for (String p : perms) {
                if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) allGranted = false;
            }
            if (allGranted) {
                pendingBridgePermission = name;
                pendingBridgeCallback = cb;
                dispatchPermissionResult(name, true);
                pendingBridgePermission = null;
                pendingBridgeCallback = null;
                return;
            }
            pendingBridgePermission = name;
            pendingBridgeCallback = cb;
            runOnUiThread(() -> requestPermissions(perms, 0x5E11));
        }

        @JavascriptInterface
        public void openAppSettings() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception e) {
                    android.widget.Toast.makeText(WebViewActivity.this, "无法打开设置", android.widget.Toast.LENGTH_SHORT).show();
                }
            });
        }

        /** 分享文件：支持绝对路径或相对 filesDir 的路径（如 "export/report.xlsx"） */
        @JavascriptInterface
        public void shareFile(String path) {
            if (path == null || path.isEmpty()) return;
            runOnUiThread(() -> {
                try {
                    java.io.File f = new java.io.File(path);
                    if (!f.isAbsolute()) {
                        f = new java.io.File(getFilesDir(), path);
                    }
                    if (!f.isFile()) {
                        android.widget.Toast.makeText(WebViewActivity.this, "文件不存在: " + path, android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                            WebViewActivity.this, "com.oilquiz.app.fileprovider", f);
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType(mimeOf(f.getName()));
                    i.putExtra(Intent.EXTRA_STREAM, uri);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(i, "分享文件"));
                } catch (Exception e) {
                    android.widget.Toast.makeText(WebViewActivity.this, "分享失败: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
                }
            });
        }

        // ==================== 题目相关功能接口 ====================
        
        @JavascriptInterface
        public void openQuestionBrowse() {
            runOnUiThread(() -> {
                Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.activity.QuestionActivity.class);
                startActivity(intent);
            });
        }
        
        @JavascriptInterface
        public void openQuestionDetail() {
            runOnUiThread(() -> {
                Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.activity.QuestionDetailActivity.class);
                startActivity(intent);
            });
        }
        
        // ==================== 文件预览相关接口 ====================
        
        @JavascriptInterface
        public void openTBSFilePreview() {
            runOnUiThread(() -> {
                Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.activity.TBSFilePreviewActivity.class);
                startActivity(intent);
            });
        }
        
        @JavascriptInterface
        public void openPdfiumPreview() {
            runOnUiThread(() -> {
                Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.activity.PdfiumPreviewActivity.class);
                startActivity(intent);
            });
        }
        
        @JavascriptInterface
        public void openFileRender() {
            runOnUiThread(() -> {
                Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.activity.FileRenderActivity.class);
                startActivity(intent);
            });
        }
        
        @JavascriptInterface
        public void openWebViewFilePreview() {
            runOnUiThread(() -> {
                Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.activity.WebViewFilePreviewActivity.class);
                startActivity(intent);
            });
        }
        
        // ==================== 导入相关功能接口 ====================
        
        @JavascriptInterface
        public void openImportGuide() {
            runOnUiThread(() -> {
                Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.activity.ImportGuideActivity.class);
                startActivity(intent);
            });
        }
        
        @JavascriptInterface
        public void openImportResult() {
            runOnUiThread(() -> {
                Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.activity.ImportResultActivity.class);
                startActivity(intent);
            });
        }
        
        @JavascriptInterface
        public void openSmartMapping() {
            runOnUiThread(() -> {
                Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.activity.SmartMappingActivity.class);
                startActivity(intent);
            });
        }
        
        @JavascriptInterface
        public void openQuestionTypeMapper() {
            runOnUiThread(() -> {
                Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.activity.QuestionTypeMapperActivity.class);
                startActivity(intent);
            });
        }
        
        @JavascriptInterface
        public void openMappingEditor() {
            runOnUiThread(() -> {
                Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.activity.MappingEditorActivity.class);
                startActivity(intent);
            });
        }
        
        // ==================== 模板相关功能接口 ====================
        
        @JavascriptInterface
        public void openEditTemplate() {
            runOnUiThread(() -> {
                Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.activity.EditTemplateActivity.class);
                startActivity(intent);
            });
        }
        
        @JavascriptInterface
        public void openTemplateSelection() {
            runOnUiThread(() -> {
                Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.export.TemplateSelectionActivity.class);
                startActivity(intent);
            });
        }
        
        @JavascriptInterface
        public void openFieldConfig() {
            runOnUiThread(() -> {
                Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.export.FieldConfigActivity.class);
                startActivity(intent);
            });
        }
        
        @JavascriptInterface
        public void openExportProgress() {
            runOnUiThread(() -> {
                Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.export.ExportProgressActivity.class);
                startActivity(intent);
            });
        }
        
        // ==================== 系统原生接口 ====================
        
        // 相机相关接口
        @JavascriptInterface
        public void takePicture() {
            runOnUiThread(() -> {
                Intent intent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
                if (intent.resolveActivity(getPackageManager()) != null) {
                    startActivity(intent);
                } else {
                    showToast(getString(R.string.h_6aba49b6));
                }
            });
        }
        
        @JavascriptInterface
        public void pickImage() {
            runOnUiThread(() -> {
                // SAF：ACTION_OPEN_DOCUMENT 无需存储权限，兼容 Android 13+（原 ACTION_PICK+MediaStore 依赖 READ_MEDIA_IMAGES）
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.setType("image/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(intent, 101);
            });
        }
        
        // 系统通知接口
        @JavascriptInterface
        public void showNotification(String title, String message) {
            runOnUiThread(() -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    android.app.NotificationChannel channel = new android.app.NotificationChannel("webview_notifications", "WebView Notifications", android.app.NotificationManager.IMPORTANCE_DEFAULT);
                    channel.setDescription("Notifications from WebView");
                    android.app.NotificationManager notificationManager = WebViewActivity.this.getSystemService(android.app.NotificationManager.class);
                    if (notificationManager != null) {
                        notificationManager.createNotificationChannel(channel);
                    }
                }
                
                android.app.Notification.Builder builder = new android.app.Notification.Builder(WebViewActivity.this, "webview_notifications")
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(title)
                        .setContentText(message)
                        .setPriority(android.app.Notification.PRIORITY_DEFAULT);
                
                android.app.NotificationManager notificationManager = (android.app.NotificationManager) WebViewActivity.this.getSystemService(Context.NOTIFICATION_SERVICE);
                if (notificationManager != null) {
                    notificationManager.notify((int) System.currentTimeMillis(), builder.build());
                }
            });
        }
        
        // 网络相关接口
        @JavascriptInterface
        public String getNetworkType() {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) WebViewActivity.this.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                android.net.NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
                if (activeNetwork != null) {
                    if (activeNetwork.getType() == android.net.ConnectivityManager.TYPE_WIFI) {
                        return "WIFI";
                    } else if (activeNetwork.getType() == android.net.ConnectivityManager.TYPE_MOBILE) {
                        return "MOBILE";
                    }
                }
            }
            return "NONE";
        }
        
        // 电池状态接口
        @JavascriptInterface
        public int getBatteryLevel() {
            android.content.Intent batteryIntent = registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            if (batteryIntent != null) {
                int level = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
                if (level > 0 && scale > 0) {
                    return (level * 100) / scale;
                }
            }
            return -1;
        }
        
        @JavascriptInterface
        public boolean isBatteryCharging() {
            android.content.Intent batteryIntent = registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            if (batteryIntent != null) {
                int status = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
                return status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                       status == android.os.BatteryManager.BATTERY_STATUS_FULL;
            }
            return false;
        }
        
        // 设备信息接口
        @JavascriptInterface
        public String getDeviceId() {
            try {
                return android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
            } catch (Exception e) {
                AppLogger.e(TAG, "获取设备ID失败: " + e.getMessage(), e);
                return "";
            }
        }
        
        @JavascriptInterface
        public int getScreenWidth() {
            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            return metrics.widthPixels;
        }
        
        @JavascriptInterface
        public int getScreenHeight() {
            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            return metrics.heightPixels;
        }
        
        // 系统设置接口
        @JavascriptInterface
        public void openSystemSettings() {
            runOnUiThread(() -> {
                Intent intent = new Intent(android.provider.Settings.ACTION_SETTINGS);
                startActivity(intent);
            });
        }
        
        @JavascriptInterface
        public void openWifiSettings() {
            runOnUiThread(() -> {
                Intent intent = new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS);
                startActivity(intent);
            });
        }
        
        @JavascriptInterface
        public void openBluetoothSettings() {
            runOnUiThread(() -> {
                Intent intent = new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS);
                startActivity(intent);
            });
        }
        
        // 应用管理接口
        @JavascriptInterface
        public boolean isAppInstalled(String packageName) {
            try {
                getPackageManager().getPackageInfo(packageName, 0);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
        
        @JavascriptInterface
        public void openApp(String packageName) {
            runOnUiThread(() -> {
                try {
                    Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
                    if (intent != null) {
                        startActivity(intent);
                    } else {
                        showToast(getString(R.string.h_645b9651));
                    }
                } catch (Exception e) {
                    showToast(getString(R.string.h_1e1da12f));
                }
            });
        }
        
        // 存储相关接口
        @JavascriptInterface
        public long getFreeStorageSpace() {
            android.os.StatFs stat = new android.os.StatFs(android.os.Environment.getExternalStorageDirectory().getPath());
            long blockSize = stat.getBlockSizeLong();
            long availableBlocks = stat.getAvailableBlocksLong();
            return availableBlocks * blockSize;
        }
        
        @JavascriptInterface
        public long getTotalStorageSpace() {
            android.os.StatFs stat = new android.os.StatFs(android.os.Environment.getExternalStorageDirectory().getPath());
            long blockSize = stat.getBlockSizeLong();
            long totalBlocks = stat.getBlockCountLong();
            return totalBlocks * blockSize;
        }
        
        // ==================== 文件系统接口 ====================
        
        // 读取文件内容
        @JavascriptInterface
        public String readFile(String filePath) {
            try {
                java.io.File file = new java.io.File(filePath);
                if (!file.exists()) {
                    return "ERROR: File not found";
                }
                // 使用CharsetDetector智能检测编码，修复GBK/GB18030文件乱码
                return CharsetDetector.readFileAutoDetect(file);
            } catch (Exception e) {
                AppLogger.e(TAG, "读取文件失败: " + e.getMessage(), e);
                return "ERROR: " + e.getMessage();
            }
        }
        
        // 写入文件内容
        @JavascriptInterface
        public boolean writeFile(String filePath, String content, boolean append) {
            try {
                java.io.File file = new java.io.File(filePath);
                // 确保父目录存在
                java.io.File parentDir = file.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs();
                }
                java.io.BufferedWriter writer = new java.io.BufferedWriter(new java.io.FileWriter(file, append));
                writer.write(content);
                writer.close();
                return true;
            } catch (Exception e) {
                AppLogger.e(TAG, "写入文件失败: " + e.getMessage(), e);
                return false;
            }
        }
        
        // 列出目录内容
        @JavascriptInterface
        public String listDirectory(String directoryPath) {
            try {
                java.io.File directory = new java.io.File(directoryPath);
                if (!directory.exists() || !directory.isDirectory()) {
                    return "ERROR: Directory not found or not a directory";
                }
                java.io.File[] files = directory.listFiles();
                org.json.JSONArray jsonArray = new org.json.JSONArray();
                if (files != null) {
                    for (java.io.File file : files) {
                        org.json.JSONObject fileInfo = new org.json.JSONObject();
                        fileInfo.put("name", file.getName());
                        fileInfo.put("path", file.getAbsolutePath());
                        fileInfo.put("isDirectory", file.isDirectory());
                        fileInfo.put("size", file.length());
                        fileInfo.put("lastModified", file.lastModified());
                        jsonArray.put(fileInfo);
                    }
                }
                return jsonArray.toString();
            } catch (Exception e) {
                AppLogger.e(TAG, "列出目录内容失败: " + e.getMessage(), e);
                return "ERROR: " + e.getMessage();
            }
        }
        
        // 创建目录
        @JavascriptInterface
        public boolean createDirectory(String directoryPath) {
            try {
                java.io.File directory = new java.io.File(directoryPath);
                return directory.mkdirs();
            } catch (Exception e) {
                AppLogger.e(TAG, "创建目录失败: " + e.getMessage(), e);
                return false;
            }
        }
        
        // 删除文件或目录
        @JavascriptInterface
        public boolean deleteFile(String filePath) {
            try {
                java.io.File file = new java.io.File(filePath);
                if (!file.exists()) {
                    return false;
                }
                if (file.isDirectory()) {
                    // 递归删除目录
                    java.io.File[] files = file.listFiles();
                    if (files != null) {
                        for (java.io.File child : files) {
                            deleteFile(child.getAbsolutePath());
                        }
                    }
                }
                return file.delete();
            } catch (Exception e) {
                AppLogger.e(TAG, "删除文件失败: " + e.getMessage(), e);
                return false;
            }
        }
        
        // 复制文件
        @JavascriptInterface
        public boolean copyFile(String sourcePath, String destinationPath) {
            try {
                java.io.File source = new java.io.File(sourcePath);
                java.io.File destination = new java.io.File(destinationPath);
                if (!source.exists()) {
                    return false;
                }
                // 确保目标目录存在
                java.io.File parentDir = destination.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs();
                }
                java.io.FileInputStream in = new java.io.FileInputStream(source);
                java.io.FileOutputStream out = new java.io.FileOutputStream(destination);
                byte[] buffer = new byte[1024];
                int length;
                while ((length = in.read(buffer)) > 0) {
                    out.write(buffer, 0, length);
                }
                in.close();
                out.close();
                return true;
            } catch (Exception e) {
                AppLogger.e(TAG, "复制文件失败: " + e.getMessage(), e);
                return false;
            }
        }
        
        // 移动文件
        @JavascriptInterface
        public boolean moveFile(String sourcePath, String destinationPath) {
            try {
                java.io.File source = new java.io.File(sourcePath);
                java.io.File destination = new java.io.File(destinationPath);
                if (!source.exists()) {
                    return false;
                }
                // 确保目标目录存在
                java.io.File parentDir = destination.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs();
                }
                return source.renameTo(destination);
            } catch (Exception e) {
                AppLogger.e(TAG, "移动文件失败: " + e.getMessage(), e);
                return false;
            }
        }
        
        // 获取文件信息
        @JavascriptInterface
        public String getFileInfo(String filePath) {
            try {
                java.io.File file = new java.io.File(filePath);
                if (!file.exists()) {
                    return "ERROR: File not found";
                }
                org.json.JSONObject fileInfo = new org.json.JSONObject();
                fileInfo.put("name", file.getName());
                fileInfo.put("path", file.getAbsolutePath());
                fileInfo.put("isDirectory", file.isDirectory());
                fileInfo.put("size", file.length());
                fileInfo.put("lastModified", file.lastModified());
                fileInfo.put("exists", file.exists());
                fileInfo.put("canRead", file.canRead());
                fileInfo.put("canWrite", file.canWrite());
                return fileInfo.toString();
            } catch (Exception e) {
                AppLogger.e(TAG, "获取文件信息失败: " + e.getMessage(), e);
                return "ERROR: " + e.getMessage();
            }
        }
        
        // 检查文件是否存在
        @JavascriptInterface
        public boolean fileExists(String filePath) {
            try {
                java.io.File file = new java.io.File(filePath);
                return file.exists();
            } catch (Exception e) {
                AppLogger.e(TAG, "检查文件是否存在失败: " + e.getMessage(), e);
                return false;
            }
        }
        
        // 获取文件大小
        @JavascriptInterface
        public long getFileSize(String filePath) {
            try {
                java.io.File file = new java.io.File(filePath);
                if (!file.exists()) {
                    return -1;
                }
                return file.length();
            } catch (Exception e) {
                AppLogger.e(TAG, "获取文件大小失败: " + e.getMessage(), e);
                return -1;
            }
        }
        
        // ==================== 权限相关接口 ====================
        
        // 检查单个权限是否已授予
        @JavascriptInterface
        public boolean checkPermission(String permission) {
            return permissionProvider.isPermissionGranted(permission);
        }
        
        // 检查权限组是否已授予
        @JavascriptInterface
        public boolean checkPermissionGroup(String groupName) {
            return permissionProvider.isPermissionGroupGranted(groupName);
        }
        
        // 请求单个权限
        @JavascriptInterface
        public void requestPermission(String permission) {
            runOnUiThread(() -> {
                permissionProvider.requestPermission(WebViewActivity.this, permission);
            });
        }
        
        // 请求权限组
        @JavascriptInterface
        public void requestPermissionGroup(String groupName) {
            runOnUiThread(() -> {
                permissionProvider.requestPermissionGroup(WebViewActivity.this, groupName);
            });
        }
        
        // 请求存储权限
        @JavascriptInterface
        public void requestStoragePermission() {
            runOnUiThread(() -> {
                permissionProvider.requestStoragePermission(WebViewActivity.this);
            });
        }
        
        // 请求相机权限
        @JavascriptInterface
        public void requestCameraPermission() {
            runOnUiThread(() -> {
                permissionProvider.requestCameraPermission(WebViewActivity.this);
            });
        }
        
        // 检查存储权限
        @JavascriptInterface
        public boolean hasStoragePermission() {
            return permissionProvider.hasStoragePermission();
        }
        
        // 检查相机权限
        @JavascriptInterface
        public boolean hasCameraPermission() {
            return permissionProvider.hasCameraPermission();
        }
        
        // 获取权限组列表
        @JavascriptInterface
        public String getPermissionGroups() {
            try {
                String[] groups = permissionProvider.getPermissionGroupNames();
                org.json.JSONArray jsonArray = new org.json.JSONArray();
                for (String group : groups) {
                    jsonArray.put(group);
                }
                return jsonArray.toString();
            } catch (Exception e) {
                AppLogger.e(TAG, "获取权限组列表失败: " + e.getMessage(), e);
                return "[]";
            }
        }
        
        // 获取权限组中的权限
        @JavascriptInterface
        public String getPermissionsInGroup(String groupName) {
            try {
                String[] permissions = permissionProvider.getPermissionsInGroup(groupName);
                org.json.JSONArray jsonArray = new org.json.JSONArray();
                if (permissions != null) {
                    for (String permission : permissions) {
                        jsonArray.put(permission);
                    }
                }
                return jsonArray.toString();
            } catch (Exception e) {
                AppLogger.e(TAG, "获取权限组中的权限失败: " + e.getMessage(), e);
                return "[]";
            }
        }
        
        // 获取权限的友好名称
        @JavascriptInterface
        public String getPermissionFriendlyName(String permission) {
            return permissionProvider.getPermissionFriendlyName(permission);
        }
        
        // 获取权限组的友好名称
        @JavascriptInterface
        public String getPermissionGroupFriendlyName(String groupName) {
            return permissionProvider.getPermissionGroupFriendlyName(groupName);
        }
        
        // ==================== 主题相关功能接口 ====================
        
        @JavascriptInterface
        public void openThemeColor() {
            // 主题色/模式设置统一收敛到 ThemeActivity
            runOnUiThread(() -> {
                Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.activity.ThemeActivity.class);
                startActivity(intent);
            });
        }
        
        // ==================== 用户相关功能接口 ====================
        
        @JavascriptInterface
        public void openLogin() {
            runOnUiThread(() -> {
                Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.activity.LoginActivity.class);
                startActivity(intent);
            });
        }
        
        @JavascriptInterface
        public void openRegister() {
            runOnUiThread(() -> {
                Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.activity.RegisterActivity.class);
                startActivity(intent);
            });
        }
        
        @JavascriptInterface
        public void openForgotPassword() {
            runOnUiThread(() -> {
                Intent intent = new Intent(WebViewActivity.this, com.oilquiz.app.ui.activity.ForgotPasswordActivity.class);
                startActivity(intent);
            });
        }
        
        // ==================== 通用跳转接口 ====================
        
        @JavascriptInterface
        public void openActivity(String activityName) {
            runOnUiThread(() -> {
                try {
                    Class<?> activityClass = Class.forName("com.oilquiz.app.ui.activity." + activityName);
                    Intent intent = new Intent(WebViewActivity.this, activityClass);
                    startActivity(intent);
                } catch (Exception e) {
                    AppLogger.e(TAG, "打开Activity失败: " + e.getMessage(), e);
                    showToast(getString(R.string.h_e985bb87));
                }
            });
        }
        
        // ==================== 页面导航接口 ====================
        
        @JavascriptInterface
        public void navigateTo(String page) {
            runOnUiThread(() -> {
                try {
                    if (page == null || page.isEmpty()) {
                        return;
                    }
                    
                    String targetUrl = "file:///android_asset/" + page;
                    AppLogger.d(TAG, "导航到页面: " + targetUrl);
                    loadUrl(targetUrl);
                } catch (Exception e) {
                    AppLogger.e(TAG, "导航失败: " + e.getMessage(), e);
                    showToast(getString(R.string.h_393bb104));
                }
            });
        }
        
        // ==================== 关闭当前WebView ====================
        
        @JavascriptInterface
        public void finishWebView() {
            runOnUiThread(() -> {
                finish();
            });
        }
    }

    private void initExportDirObserver() {
        // 初始化文件监控
        File exportDir = getExportDirectory();
        if (exportDir != null && exportDir.exists()) {
            exportDirObserver = new android.os.FileObserver(exportDir.getAbsolutePath()) {
                @Override
                public void onEvent(int event, String path) {
                    if (event == android.os.FileObserver.CREATE || event == android.os.FileObserver.MODIFY) {
                        // 导出目录有新文件或文件被修改，重新加载
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                loadLocalHtml();
                            }
                        });
                    }
                }
            };
            exportDirObserver.startWatching();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        // 1. 停止文件监控
        if (exportDirObserver != null) {
            exportDirObserver.stopWatching();
            exportDirObserver = null;
        }
        
        // 2. 使用生命周期管理器销毁所有 WebView
        com.oilquiz.app.webview.security.WebViewLifecycleManager lifecycleManager = 
            com.oilquiz.app.webview.security.WebViewLifecycleManager.getInstance();
        
        // 销毁所有活跃的 WebView
        lifecycleManager.destroyAllWebViews();
        
        // 3. 清理 JS 接口管理器
        com.oilquiz.app.webview.js.JSInterfaceManager jsManager = 
            com.oilquiz.app.webview.js.JSInterfaceManager.getInstance(this);
        jsManager.destroy();
        
        // 4. 清理列表
        webViewList.clear();
        tabList.clear();
        
        AppLogger.d(TAG, "WebViewActivity 销毁完成，所有资源已释放");
    }
}
