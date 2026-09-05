package com.oilquiz.app.ui.activity;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.URLUtil;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import androidx.appcompat.widget.SearchView;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.model.ModelDownloadManager;
import com.oilquiz.app.ai.model.ModelInfo;
import com.oilquiz.app.ai.service.AIServiceInitializer;
import com.oilquiz.app.ui.base.BaseActivity;
import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class ModelDownloadActivity extends BaseActivity {

    private static final String TAG = "ModelDownloadActivity";
    
    private static final String HUGGINGFACE_API = "https://huggingface.co/api/models";
    private static final Pattern URL_PATTERN = Pattern.compile("https?://.*\\.(gguf|bin|pt|onnx|tflite)(\\?.*)?", Pattern.CASE_INSENSITIVE);

    private SearchView searchView;
    private MaterialButton btnAddUrl;
    private MaterialButton btnSearch;
    private RecyclerView recyclerView;
    private TextView tvDownloadStats;
    private TextView tvSearchHint;
    private TextView tvMirrorSource;
    private MaterialButton btnSwitchMirror;
    private TextView tvDownloadMethod;
    private MaterialButton btnSwitchDownloadMethod;
    private MaterialButton btnAICenter;
    private MaterialButton btnAIService;
    private MaterialButton btnAiInit;
    
    private ModelDownloadManager modelDownloadManager;
    
    private List<Object> currentModelList;
    private List<Object> allModelList;
    private ModelAdapter modelAdapter;
    
    private String searchQuery = "";
    
    // 镜像源选项
    private final ModelDownloadManager.MirrorSource[] MIRROR_SOURCES = {
        ModelDownloadManager.MirrorSource.HF_MIRROR,
        ModelDownloadManager.MirrorSource.MODELSCOPE,
        ModelDownloadManager.MirrorSource.HUGGINGFACE,
        ModelDownloadManager.MirrorSource.GITEE
    };
    private final String[] MIRROR_NAMES = {
        "HF Mirror (国内推荐)",
        "ModelScope (国内)",
        "HuggingFace (官方)",
        "Gitee"
    };

    // 下载方式选项
    private final ModelDownloadManager.DownloadMethod[] DOWNLOAD_METHODS = {
        ModelDownloadManager.DownloadMethod.CUSTOM,
        ModelDownloadManager.DownloadMethod.SYSTEM
    };
    private final String[] DOWNLOAD_METHOD_NAMES = {
        "自定义下载器 (推荐)",
        "系统下载管理器"
    };
    private final String[] DOWNLOAD_METHOD_DESCRIPTIONS = {
        "支持暂停/恢复、速度显示、断点续传",
        "后台下载、通知栏进度、无需额外依赖"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_model_download;
    }

    @Override
    protected void initView() {
        setupToolbar("模型下载");
        
        searchView = findViewById(R.id.search_view);
        searchView.setIconifiedByDefault(false); // 默认展开，不用点图标
        searchView.setIconified(false);
        btnAddUrl = findViewById(R.id.btn_add_url);
        btnSearch = findViewById(R.id.btn_search);
        recyclerView = findViewById(R.id.recycler_view);
        tvDownloadStats = findViewById(R.id.tv_download_stats);
        tvSearchHint = findViewById(R.id.tv_search_hint);
        tvMirrorSource = findViewById(R.id.tv_mirror_source);
        btnSwitchMirror = findViewById(R.id.btn_switch_mirror);
        tvDownloadMethod = findViewById(R.id.tv_download_method);
        btnSwitchDownloadMethod = findViewById(R.id.btn_switch_download_method);
        btnAICenter = findViewById(R.id.btn_ai_center);
        btnAIService = findViewById(R.id.btn_ai_service);
        btnAiInit = findViewById(R.id.btn_ai_init);
        
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        // 快捷入口：AI 中心 / AI 服务
        if (btnAICenter != null) {
            btnAICenter.setOnClickListener(v ->
                    startActivity(new Intent(ModelDownloadActivity.this, AICenterActivity.class)));
        }
        if (btnAIService != null) {
            btnAIService.setOnClickListener(v ->
                    startActivity(new Intent(ModelDownloadActivity.this, AIServiceStatusActivity.class)));
        }
        // AI 服务一键初始化：本地与在线均未配置时提供，点击进入精美引导界面
        if (btnAiInit != null) {
            updateAiInitButtonVisibility();
            btnAiInit.setOnClickListener(v -> {
                if (!AIServiceInitializer.needsInitialization(this)) {
                    updateAiInitButtonVisibility();
                    return;
                }
                startActivity(new Intent(ModelDownloadActivity.this, AIServiceInitActivity.class));
            });
        }
    }

    /** 更新一键初始化按钮显隐（本地与在线均未配置时显示可用） */
    private void updateAiInitButtonVisibility() {
        if (btnAiInit == null) return;
        boolean show = AIServiceInitializer.needsInitialization(this);
        btnAiInit.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            btnAiInit.setEnabled(true);
            btnAiInit.setText("⚡ 一键初始化");
        }
    }

    @Override
    protected void initData() {
        modelDownloadManager = ModelDownloadManager.getInstance(this);
        currentModelList = new ArrayList<>();
        allModelList = new ArrayList<>();
        modelAdapter = new ModelAdapter();
        recyclerView.setAdapter(modelAdapter);
        
        loadModels();
        updateDownloadStats();
        modelDownloadManager.setGlobalCallback(new ModelDownloadManager.DownloadCallback() {
            @Override
            public void onProgress(String modelId, int progress, long downloadedMB, long totalMB) {
                runOnUiThread(() -> {
                    modelAdapter.updateProgress(modelId, progress, downloadedMB * 1024 * 1024, totalMB * 1024 * 1024, 0);
                });
            }

            @Override
            public void onSpeedUpdate(String modelId, long speedBps, long etaSeconds) {
                runOnUiThread(() -> {
                    modelAdapter.updateSpeed(modelId, speedBps, etaSeconds);
                });
            }

            @Override
            public void onComplete(String modelId, String filePath) {
                runOnUiThread(() -> {
                    // 如果是 mmproj 文件下载完成，不更新 UI 状态
                    if (modelId.endsWith("_mmproj")) {
                        AILogger.i("ModelDownloadActivity", "mmproj downloaded for model: " + modelId);
                        updateDownloadStats();
                        return;
                    }
                    // updateComplete 内部已更新对应 item 状态并调用 updateDownloadStats()
                    modelAdapter.updateComplete(modelId, filePath);
                });
            }

            @Override
            public void onError(String modelId, String error) {
                runOnUiThread(() -> {
                    modelAdapter.updateError(modelId, error);
                });
            }

            @Override
            public void onPaused(String modelId) {
                runOnUiThread(() -> {
                    modelAdapter.updatePaused(modelId);
                });
            }

            @Override
            public void onCancelled(String modelId) {
                runOnUiThread(() -> {
                    loadModels();
                });
            }

            @Override
            public void onResumed(String modelId) {
                runOnUiThread(() -> {
                    // 恢复下载后刷新该 item 状态（不能传 progress=-1，否则 updateProgress 里 if(progress>=0) 不执行）
                    loadModels();
                });
            }
        });
    }

    @Override
    protected void initListener() {
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                searchQuery = query.trim();
                if (isValidUrl(searchQuery)) {
                    showCustomUrlDialog(searchQuery);
                } else if (!TextUtils.isEmpty(searchQuery)) {
                    // 搜索框有内容 → 直接在线搜索
                    searchHuggingFaceModels(searchQuery);
                } else {
                    // 搜索框为空 → 回到 LLM 模型列表
                    loadLLMModels();
                    filterModels();
                }
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                searchQuery = newText.trim();
                if (TextUtils.isEmpty(searchQuery)) {
                    // 搜索框清空 → 回到 LLM 模型列表
                    loadLLMModels();
                    filterModels();
                }
                return true;
            }
        });
        
        btnAddUrl.setOnClickListener(v -> showCustomUrlDialog(null));

        btnSearch.setOnClickListener(v -> {
            String query = searchView.getQuery().toString().trim();
            if (TextUtils.isEmpty(query)) {
                showToast("请输入搜索关键词");
                return;
            }
            if (isValidUrl(query)) {
                showCustomUrlDialog(query);
            } else {
                searchHuggingFaceModels(query);
            }
        });
        
        // 镜像源切换
        btnSwitchMirror.setOnClickListener(v -> showMirrorSwitchDialog());
        
        // 下载方式切换
        btnSwitchDownloadMethod.setOnClickListener(v -> showDownloadMethodSwitchDialog());
        
        // 初始化镜像显示
        updateMirrorSourceDisplay();
        
        // 初始化下载方式显示
        updateDownloadMethodDisplay();
    }
    
    /**
     * 更新镜像源显示
     */
    private void updateMirrorSourceDisplay() {
        ModelDownloadManager.MirrorSource currentSource = modelDownloadManager.getCurrentMirrorSource();
        String displayName = getMirrorDisplayName(currentSource);
        tvMirrorSource.setText(displayName);
    }
    
    /**
     * 获取镜像显示名称
     */
    private String getMirrorDisplayName(ModelDownloadManager.MirrorSource source) {
        for (int i = 0; i < MIRROR_SOURCES.length; i++) {
            if (MIRROR_SOURCES[i] == source) {
                return MIRROR_NAMES[i];
            }
        }
        return "HF Mirror (国内推荐)";
    }
    
    /**
     * 显示镜像源切换对话框
     */
    private void showMirrorSwitchDialog() {
        ModelDownloadManager.MirrorSource currentSource = modelDownloadManager.getCurrentMirrorSource();
        int currentIndex = 0;
        for (int i = 0; i < MIRROR_SOURCES.length; i++) {
            if (MIRROR_SOURCES[i] == currentSource) {
                currentIndex = i;
                break;
            }
        }
        
        new AlertDialog.Builder(this)
            .setTitle("选择下载源")
            .setSingleChoiceItems(MIRROR_NAMES, currentIndex, (dialog, which) -> {
                ModelDownloadManager.MirrorSource selected = MIRROR_SOURCES[which];
                modelDownloadManager.setCurrentMirrorSource(selected);
                updateMirrorSourceDisplay();
                showToast("已切换到: " + MIRROR_NAMES[which]);
                dialog.dismiss();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /**
     * 更新下载方式显示
     */
    private void updateDownloadMethodDisplay() {
        ModelDownloadManager.DownloadMethod currentMethod = modelDownloadManager.getDownloadMethod();
        String displayName = getDownloadMethodDisplayName(currentMethod);
        tvDownloadMethod.setText(displayName);
    }
    
    /**
     * 获取下载方式显示名称
     */
    private String getDownloadMethodDisplayName(ModelDownloadManager.DownloadMethod method) {
        for (int i = 0; i < DOWNLOAD_METHODS.length; i++) {
            if (DOWNLOAD_METHODS[i] == method) {
                return DOWNLOAD_METHOD_NAMES[i];
            }
        }
        return "自定义下载器 (推荐)";
    }
    
    /**
     * 显示下载方式切换对话框
     */
    private void showDownloadMethodSwitchDialog() {
        int currentIndex = 0;
        ModelDownloadManager.DownloadMethod currentMethod = modelDownloadManager.getDownloadMethod();
        for (int i = 0; i < DOWNLOAD_METHODS.length; i++) {
            if (DOWNLOAD_METHODS[i] == currentMethod) {
                currentIndex = i;
                break;
            }
        }
        
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("选择下载方式")
            .setSingleChoiceItems(DOWNLOAD_METHOD_NAMES, currentIndex, (dialog, which) -> {
                ModelDownloadManager.DownloadMethod selected = DOWNLOAD_METHODS[which];
                modelDownloadManager.setDownloadMethod(selected);
                updateDownloadMethodDisplay();
                showToast("已切换到: " + DOWNLOAD_METHOD_NAMES[which] + "\n" + DOWNLOAD_METHOD_DESCRIPTIONS[which]);
                dialog.dismiss();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void loadModels() {
        allModelList.clear();
        loadLLMModels();
        
        filterModels();
    }

    private void loadOnlineModels() {
        tvSearchHint.setVisibility(View.VISIBLE);
        tvSearchHint.setText("提示：在搜索框输入 Hugging Face 模型名称（如：Qwen/Qwen3-8B-GGUF）或直接输入模型下载链接\nLLM 模型 tab 已包含常用国产模型，此处提供特殊量化版本与自定义下载");

        List<OnlineModelInfo> onlineModels = getPopularOnlineModels();
        allModelList.addAll(onlineModels);
    }

    /**
     * 在线搜索推荐模型：只保留 LLM 预设列表（models_presets.json）中没有的特殊量化/版本，
     * 避免与"LLM 模型" tab 重复。常用国产模型统一在 LLM 模型 tab 下载。
     */
    private List<OnlineModelInfo> getPopularOnlineModels() {
        List<OnlineModelInfo> models = new ArrayList<>();

        // 工具调用专用版
        models.add(new OnlineModelInfo("Qwen3-4B-ToolCalling", "Qwen3 4B 工具调用专用模型（Function Calling 优化版）",
            "https://hf-mirror.com/Manojb/Qwen3-4B-toolcalling-gguf-codex/resolve/main/Qwen3-4B-Function-Calling-Pro.gguf",
            "Q4_K_M", "4 GB", "GGUF", "4B"));

        // 高精度量化版本（Q8_0 / Q5_K_M，预设列表只有 Q4_K_M）
        models.add(new OnlineModelInfo("Qwen3-0.6B-Q8", "Qwen3 0.6B 轻量中文模型（高精度 Q8_0 量化）",
            "https://hf-mirror.com/Qwen/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q8_0.gguf",
            "Q8_0", "610 MB", "GGUF", "0.6B"));

        models.add(new OnlineModelInfo("Qwen2.5-3B-Instruct-Q5KM", "通义千问2.5 3B 更高精度（Q5_K_M）",
            "https://hf-mirror.com/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q5_k_m.gguf",
            "Q5_K_M", "2.3 GB", "GGUF", "3B"));

        models.add(new OnlineModelInfo("Qwen2.5-1.5B-Instruct-Q5KM", "通义千问2.5 1.5B 高精度（Q5_K_M）",
            "https://hf-mirror.com/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q5_k_m.gguf",
            "Q5_K_M", "1.2 GB", "GGUF", "1.5B"));

        // 预设列表没有的型号
        models.add(new OnlineModelInfo("GLM-Edge-4B-Chat", "智谱AI端侧模型，面向PC/平板",
            "https://hf-mirror.com/zai-org/glm-edge-4b-chat-gguf/resolve/main/ggml-model-Q4_K_M.gguf",
            "Q4_K_M", "2.5 GB", "GGUF", "4B"));

        models.add(new OnlineModelInfo("Yi-Coder-1.5B-Chat", "零一万物代码模型，支持52种编程语言",
            "https://hf-mirror.com/MaziyarPanahi/Yi-Coder-1.5B-Chat-GGUF/resolve/main/Yi-Coder-1.5B-Chat.Q4_K_M.gguf",
            "Q4_K_M", "950 MB", "GGUF", "1.5B"));

        return models;
    }

    // ==================== HuggingFace 在线搜索 ====================

    // 搜索 API 和 resolve 路径根据当前镜像源动态生成，不硬编码
    private String getSearchApi() {
        ModelDownloadManager.MirrorSource mirror = modelDownloadManager.getCurrentMirrorSource();
        if (mirror.baseUrl == null) return null;
        String domain = mirror.domain;
        if (!domain.contains("hf-mirror") && !domain.contains("huggingface")) return null;
        return mirror.baseUrl + "/api/models?search=%s&limit=30";
    }

    private String getResolveBase() {
        ModelDownloadManager.MirrorSource mirror = modelDownloadManager.getCurrentMirrorSource();
        if (mirror.baseUrl == null) return "https://hf-mirror.com/%s/resolve/main/";
        return mirror.baseUrl + "/%s/resolve/main/";
    }

    /**
     * 调用当前镜像源的 HF 搜索 API，返回 GGUF 候选模型列表。
     * 搜索结果的 downloadUrl 为 resolve/main/ 基础路径（无文件名），
     * 点击「浏览文件」用 WebView 打开 repo 文件列表。
     */
    private void searchHuggingFaceModels(String keyword) {
        android.util.Log.d("ModelDownload", "searchHuggingFaceModels called: keyword=" + keyword);
        if (TextUtils.isEmpty(keyword)) {
            filterModels();
            return;
        }
        showToast("正在搜索 " + modelDownloadManager.getCurrentMirrorSource().domain + "...");
        final String query = keyword + " GGUF";
        final String searchApi = getSearchApi();
        if (searchApi == null) {
            showToast("当前镜像源不支持在线搜索，请切换到 HF 镜像");
            return;
        }
        final String resolveBase = getResolveBase();
        new Thread(() -> {
            String body = null;
            Exception lastErr = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    String apiUrl = String.format(searchApi, java.net.URLEncoder.encode(query, "UTF-8"));
                    android.util.Log.d("ModelDownload", "search attempt " + (attempt+1) + "/3 start: " + apiUrl);
                    long t0 = System.currentTimeMillis();
                    okhttp3.OkHttpClient searchClient = new okhttp3.OkHttpClient.Builder()
                            .dns(com.oilquiz.app.ai.model.ModelDownloadManager.getHttpClient().dns())
                            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                            .build();
                    okhttp3.Request okRequest = new okhttp3.Request.Builder()
                            .url(apiUrl)
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                            .header("Accept", "application/json")
                            .build();
                    okhttp3.Response response = searchClient.newCall(okRequest).execute();
                    long t1 = System.currentTimeMillis();
                    android.util.Log.d("ModelDownload", "search attempt " + (attempt+1) + " response in " + (t1-t0) + "ms code=" + response.code());
                    int code = response.code();
                    if (code != 200) {
                        final int finalCode = code;
                        runOnUiThread(() -> showToast("搜索失败 HTTP " + finalCode));
                        response.close();
                        return;
                    }
                    body = response.body() != null ? response.body().string() : "";
                    response.close();
                    break; // 成功，跳出重试循环
                } catch (java.io.IOException e) {
                    // connect reset / timeout / 网络异常 → 重试
                    lastErr = e;
                    android.util.Log.w("ModelDownload", "search attempt " + (attempt+1) + " failed: " + e.getMessage());
                    if (attempt < 2) {
                        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                    }
                } catch (Exception e) {
                    lastErr = e;
                    android.util.Log.e("ModelDownload", "search attempt " + (attempt+1) + " error", e);
                    break; // 非 IO 异常不重试
                }
            }
            if (body == null) {
                final String err = lastErr != null ? lastErr.getMessage() : "unknown";
                runOnUiThread(() -> showToast("搜索异常（已重试3次）: " + err));
                return;
            }

            try {
                android.util.Log.d("ModelDownload", "search raw body len=" + body.length() + " preview=" + body.substring(0, Math.min(500, body.length())));
                org.json.JSONArray arr = new org.json.JSONArray(body);
                final java.util.List<OnlineModelInfo> results = new java.util.ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject m = arr.getJSONObject(i);
                    String repoId = m.optString("id", "");
                    if (TextUtils.isEmpty(repoId)) continue;

                    // 筛选 GGUF 模型：library_name=gguf 或 tags 含 gguf
                    boolean isGguf = "gguf".equalsIgnoreCase(m.optString("library_name", ""));
                    if (!isGguf) {
                        org.json.JSONArray tags = m.optJSONArray("tags");
                        if (tags != null) {
                            for (int j = 0; j < tags.length(); j++) {
                                if ("gguf".equalsIgnoreCase(tags.optString(j))) {
                                    isGguf = true;
                                    break;
                                }
                            }
                        }
                    }
                    if (!isGguf) continue;

                    int downloads = m.optInt("downloads", 0);
                    int likes = m.optInt("likes", 0);
                    String desc = "HF搜索结果 · 下载" + downloads + " · 赞" + likes;
                    String resolveUrl = String.format(resolveBase, repoId);
                    results.add(new OnlineModelInfo(repoId, desc, resolveUrl, "", "", "GGUF", ""));
                }

                runOnUiThread(() -> {
                    if (results.isEmpty()) {
                        showToast("未找到 GGUF 模型，试试其他关键词");
                        return;
                    }
                    allModelList.clear();
                    allModelList.addAll(results);
                    currentModelList.clear();
                    currentModelList.addAll(results);
                    modelAdapter.notifyDataSetChanged();
                    tvSearchHint.setText("搜索到 " + results.size() + " 个 GGUF 模型，点击「选择文件」查看该模型下的 .gguf 文件并下载");
                    tvSearchHint.setVisibility(View.VISIBLE);
                    showToast("找到 " + results.size() + " 个候选");
                });
            } catch (Exception e) {
                android.util.Log.e("ModelDownload", "search error", e);
                final String err = e.getMessage();
                runOnUiThread(() -> showToast("搜索异常: " + err));
            }
        }).start();
    }

    /**
     * 搜索结果点击下载时弹出文件名输入框，补全 resolve/main/{filename}。
     */
    private void showFileNameDialog(String repoId, String baseResolveUrl) {
        android.widget.EditText etFileName = new android.widget.EditText(this);
        etFileName.setHint("输入 .gguf 文件名，如 qwen2.5-0.5b-instruct-q4_k_m.gguf");
        etFileName.setPadding(48, 24, 48, 24);
        // 预填常见 q4_k_m 文件名（从 repo id 推断）
        String guess = repoId.substring(repoId.lastIndexOf('/') + 1).toLowerCase();
        if (!guess.endsWith(".gguf")) {
            guess = guess.replace("-gguf", "").replace("_gguf", "") + "-q4_k_m.gguf";
        }
        etFileName.setText(guess);
        etFileName.setSelection(etFileName.getText().length());

        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("选择模型文件")
            .setMessage("Repo: " + repoId + "\n\n镜像无法获取文件列表，请输入该 repo 下的具体 .gguf 文件名")
            .setView(etFileName)
            .setPositiveButton("下载", (d, w) -> {
                String fileName = etFileName.getText().toString().trim();
                if (TextUtils.isEmpty(fileName)) {
                    showToast("请输入文件名");
                    return;
                }
                String fullUrl = baseResolveUrl + fileName;
                // 添加到列表并开始下载
                OnlineModelInfo model = new OnlineModelInfo(fileName, "HF搜索下载", fullUrl, "", "", "GGUF", "");
                currentModelList.add(0, model);
                modelAdapter.notifyItemInserted(0);
                recyclerView.scrollToPosition(0);
                // 延迟一帧后开始下载（确保 ViewHolder 已绑定）
                recyclerView.post(() -> {
                    RecyclerView.ViewHolder vh = recyclerView.findViewHolderForAdapterPosition(0);
                    if (vh instanceof ModelAdapter.ModelViewHolder) {
                        ((ModelAdapter.ModelViewHolder) vh).startDownload(fullUrl, fullUrl);
                    }
                });
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /**
     * 调用 HF 文件列表 API（/api/models/{repo_id}/tree/main），
     * 自动筛选 .gguf 文件，弹出列表供用户选择，选中后直接下载。
     * 复用 SafeDns + 重试3次 + 浏览器 UA。
     */
    private void fetchAndShowRepoFiles(String repoId, String baseResolveUrl) {
        showToast("正在获取文件列表…");
        ModelDownloadManager.MirrorSource mirror = modelDownloadManager.getCurrentMirrorSource();
        String mirrorBase = mirror.baseUrl != null ? mirror.baseUrl : "https://hf-mirror.com";
        final String treeApi = mirrorBase + "/api/models/" + repoId + "/tree/main";

        new Thread(() -> {
            String body = null;
            Exception lastErr = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                            .dns(ModelDownloadManager.getHttpClient().dns())
                            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                            .build();
                    okhttp3.Request req = new okhttp3.Request.Builder()
                            .url(treeApi)
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
                            .header("Accept", "application/json")
                            .build();
                    okhttp3.Response resp = client.newCall(req).execute();
                    if (resp.code() != 200) {
                        lastErr = new Exception("HTTP " + resp.code());
                        resp.close();
                        if (attempt < 2) { Thread.sleep(1000); }
                        continue;
                    }
                    body = resp.body() != null ? resp.body().string() : "";
                    resp.close();
                    break;
                } catch (java.io.IOException e) {
                    lastErr = e;
                    android.util.Log.w("ModelDownload", "tree API attempt " + (attempt+1) + " failed: " + e.getMessage());
                    if (attempt < 2) { try { Thread.sleep(1000); } catch (InterruptedException ignored) {} }
                } catch (Exception e) {
                    lastErr = e;
                    break;
                }
            }
            if (body == null) {
                final String err = lastErr != null ? lastErr.getMessage() : "unknown";
                runOnUiThread(() -> {
                    showToast("获取文件列表失败: " + err);
                    // 失败回退到手动输入文件名
                    showFileNameDialog(repoId, baseResolveUrl);
                });
                return;
            }

            try {
                org.json.JSONArray arr = new org.json.JSONArray(body);
                final java.util.List<String[]> ggufFiles = new java.util.ArrayList<>(); // [filename, sizeStr]
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject f = arr.getJSONObject(i);
                    String type = f.optString("type", "");
                    String path = f.optString("path", "");
                    if (!"file".equals(type)) continue;
                    if (!path.toLowerCase().endsWith(".gguf")) continue;
                    long size = f.optLong("size", 0);
                    String sizeStr = size > 0 ? formatFileSize(size) : "";
                    ggufFiles.add(new String[]{path, sizeStr});
                }

                runOnUiThread(() -> {
                    if (ggufFiles.isEmpty()) {
                        showToast("该 repo 下未找到 .gguf 文件");
                        showFileNameDialog(repoId, baseResolveUrl);
                        return;
                    }
                    // 弹出文件列表对话框
                    String[] items = new String[ggufFiles.size()];
                    for (int i = 0; i < ggufFiles.size(); i++) {
                        String[] f = ggufFiles.get(i);
                        items[i] = f[0] + (f[1].isEmpty() ? "" : "  (" + f[1] + ")");
                    }
                    new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("选择 .gguf 文件（" + ggufFiles.size() + "个）")
                        .setItems(items, (d, w) -> {
                            String fileName = ggufFiles.get(w)[0];
                            String fullUrl = baseResolveUrl + fileName;
                            // 添加到列表并开始下载
                            OnlineModelInfo model = new OnlineModelInfo(fileName, "HF搜索下载", fullUrl, "", ggufFiles.get(w)[1], "GGUF", "");
                            currentModelList.add(0, model);
                            modelAdapter.notifyItemInserted(0);
                            recyclerView.scrollToPosition(0);
                            recyclerView.post(() -> {
                                RecyclerView.ViewHolder vh = recyclerView.findViewHolderForAdapterPosition(0);
                                if (vh instanceof ModelAdapter.ModelViewHolder) {
                                    ((ModelAdapter.ModelViewHolder) vh).startDownload(fullUrl, fullUrl);
                                }
                            });
                            showToast("开始下载: " + fileName);
                        })
                        .setNegativeButton("手动输入", (d, w) -> showFileNameDialog(repoId, baseResolveUrl))
                        .show();
                });
            } catch (Exception e) {
                android.util.Log.e("ModelDownload", "parse tree error", e);
                final String err = e.getMessage();
                runOnUiThread(() -> {
                    showToast("解析文件列表失败: " + err);
                    showFileNameDialog(repoId, baseResolveUrl);
                });
            }
        }).start();
    }

    private String formatFileSize(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private void loadLLMModels() {
        tvSearchHint.setVisibility(View.GONE);
        // 预设国产模型
        allModelList.addAll(modelDownloadManager.getPresetDomesticModels());
        // 追加内置的特殊版本在线模型（工具调用版、高精度Q8/Q5量化等）
        allModelList.addAll(getPopularOnlineModels());
    }

    private void filterModels() {
        currentModelList.clear();
        
        if (TextUtils.isEmpty(searchQuery)) {
            currentModelList.addAll(allModelList);
        } else {
            String query = searchQuery.toLowerCase();
            for (Object model : allModelList) {
                String name = "";
                String desc = "";
                
                if (model instanceof ModelDownloadManager.ModelPresetInfo) {
                    ModelDownloadManager.ModelPresetInfo m = (ModelDownloadManager.ModelPresetInfo) model;
                    name = m.name.toLowerCase();
                    desc = m.description.toLowerCase();
                } else if (model instanceof OnlineModelInfo) {
                    OnlineModelInfo m = (OnlineModelInfo) model;
                    name = m.name.toLowerCase();
                    desc = m.description.toLowerCase();
                }
                
                if (name.contains(query) || desc.contains(query)) {
                    currentModelList.add(model);
                }
            }
        }
        
        modelAdapter.notifyDataSetChanged();
    }

    private boolean isValidUrl(String url) {
        return URLUtil.isValidUrl(url) && URL_PATTERN.matcher(url).matches();
    }

    private void showCustomUrlDialog(String prefilledUrl) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_custom_url, null);
        EditText etUrl = dialogView.findViewById(R.id.et_url);
        EditText etName = dialogView.findViewById(R.id.et_name);
        
        if (!TextUtils.isEmpty(prefilledUrl)) {
            etUrl.setText(prefilledUrl);
            String fileName = prefilledUrl.substring(prefilledUrl.lastIndexOf('/') + 1);
            etName.setText(fileName);
        }
        
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("添加自定义模型")
               .setView(dialogView)
               .setPositiveButton("下载", (dialog, which) -> {
                   String url = etUrl.getText().toString().trim();
                   String name = etName.getText().toString().trim();
                   
                   if (TextUtils.isEmpty(url)) {
                       showToast("请输入下载链接");
                       return;
                   }
                   if (!URLUtil.isValidUrl(url)) {
                       showToast("请输入有效的URL");
                       return;
                   }
                   if (TextUtils.isEmpty(name)) {
                       name = url.substring(url.lastIndexOf('/') + 1);
                   }
                   
                   OnlineModelInfo customModel = new OnlineModelInfo(name, "自定义模型", url, "", "", "Custom", "");
                   currentModelList.add(0, customModel);
                   modelAdapter.notifyItemInserted(0);
                   recyclerView.scrollToPosition(0);
                   showToast("已添加自定义模型");
               })
               .setNegativeButton("取消", null)
               .show();
    }

    private void updateDownloadStats() {
        String modelDir = new File(getFilesDir(), "ai_models").getAbsolutePath();
        File dir = new File(modelDir);
        int downloadedCount = 0;
        long totalSize = 0;
        
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles((f, name) -> 
                name.endsWith(".gguf") || name.endsWith(".bin") || 
                name.endsWith(".pt") || name.endsWith(".onnx") ||
                name.endsWith(".tflite") || name.endsWith(".mmproj.gguf"));
            if (files != null) {
                downloadedCount = files.length;
                for (File f : files) {
                    totalSize += f.length();
                }
            }
        }
        
        String sizeText = formatSize(totalSize);
        tvDownloadStats.setText(String.format("已下载 %d 个模型，总计 %s", downloadedCount, sizeText));
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    public static class OnlineModelInfo {
        public String name;
        public String description;
        public String downloadUrl;
        public String quantization;
        public String size;
        public String format;
        public String params;
        
        public OnlineModelInfo(String name, String description, String downloadUrl, 
                              String quantization, String size, String format, String params) {
            this.name = name;
            this.description = description;
            this.downloadUrl = downloadUrl;
            this.quantization = quantization;
            this.size = size;
            this.format = format;
            this.params = params;
        }
    }

    private class ModelAdapter extends RecyclerView.Adapter<ModelAdapter.ModelViewHolder> {

        @NonNull
        @Override
        public ModelViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_model_download, parent, false);
            return new ModelViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ModelViewHolder holder, int position) {
            Object model = currentModelList.get(position);
            holder.bind(model);
        }

        @Override
        public int getItemCount() {
            return currentModelList.size();
        }

        void updateProgress(String modelId, int progress, long downloadedBytes, long totalBytes, long speedBps) {
            for (int i = 0; i < currentModelList.size(); i++) {
                Object model = currentModelList.get(i);
                String id = getIdFromModel(model);
                if (id != null && id.equals(modelId)) {
                    RecyclerView.ViewHolder holder = recyclerView.findViewHolderForAdapterPosition(i);
                    if (holder instanceof ModelViewHolder) {
                        ModelViewHolder mvh = (ModelViewHolder) holder;
                        if (progress >= 0) {
                            mvh.llProgress.setVisibility(View.VISIBLE);
                            mvh.progressBar.setProgress(progress);
                            mvh.tvStatus.setText("下载中... " + progress + "%");
                            mvh.tvStatus.setTextColor(0xFFFF9800);
                            mvh.btnAction.setText("暂停");
                            
                            // 显示已下载大小和总大小
                            if (mvh.tvProgressInfo != null && totalBytes > 0) {
                                long downloadedMB = downloadedBytes / (1024 * 1024);
                                long totalMB = totalBytes / (1024 * 1024);
                                mvh.tvProgressInfo.setText(downloadedMB + " MB / " + totalMB + " MB");
                            }
                            
                            // 显示速度
                            if (mvh.tvSpeed != null) {
                                mvh.tvSpeed.setText(formatSpeed(speedBps));
                            }
                        }
                    } else {
                        notifyItemChanged(i);
                    }
                    break;
                }
            }
        }

        void updateSpeed(String modelId, long speedBps, long etaSeconds) {
            for (int i = 0; i < currentModelList.size(); i++) {
                Object model = currentModelList.get(i);
                String id = getIdFromModel(model);
                if (id != null && id.equals(modelId)) {
                    RecyclerView.ViewHolder holder = recyclerView.findViewHolderForAdapterPosition(i);
                    if (holder instanceof ModelViewHolder) {
                        ModelViewHolder mvh = (ModelViewHolder) holder;
                        if (mvh.tvSpeed != null) {
                            mvh.tvSpeed.setText(formatSpeed(speedBps));
                        }
                    }
                    break;
                }
            }
        }

        void updatePaused(String modelId) {
            for (int i = 0; i < currentModelList.size(); i++) {
                Object model = currentModelList.get(i);
                String id = getIdFromModel(model);
                if (id != null && id.equals(modelId)) {
                    RecyclerView.ViewHolder holder = recyclerView.findViewHolderForAdapterPosition(i);
                    if (holder instanceof ModelViewHolder) {
                        ModelViewHolder mvh = (ModelViewHolder) holder;
                        mvh.tvStatus.setText("已暂停");
                        mvh.tvStatus.setTextColor(0xFFFF9800);
                        mvh.btnAction.setText("继续");
                        if (mvh.tvSpeed != null) mvh.tvSpeed.setText("");
                    }
                    break;
                }
            }
        }

        private String formatSpeed(long bytesPerSecond) {
            if (bytesPerSecond <= 0) return "";
            if (bytesPerSecond < 1024) return bytesPerSecond + " B/s";
            if (bytesPerSecond < 1024 * 1024) return String.format("%.1f KB/s", bytesPerSecond / 1024.0);
            return String.format("%.2f MB/s", bytesPerSecond / (1024.0 * 1024));
        }

        private String formatEta(long seconds) {
            if (seconds < 0 || seconds > 86400) return "";
            if (seconds < 60) return "剩余 " + seconds + "秒";
            if (seconds < 3600) return "剩余 " + (seconds / 60) + "分" + (seconds % 60) + "秒";
            return "剩余 " + (seconds / 3600) + "时" + ((seconds % 3600) / 60) + "分";
        }

        void updateComplete(String modelId, String filePath) {
            // 只更新对应 item，避免全量 loadModels() 导致列表闪烁和滚动位置丢失
            for (int i = 0; i < currentModelList.size(); i++) {
                Object model = currentModelList.get(i);
                String id = getIdFromModel(model);
                if (id != null && id.equals(modelId)) {
                    RecyclerView.ViewHolder holder = recyclerView.findViewHolderForAdapterPosition(i);
                    if (holder instanceof ModelViewHolder) {
                        ((ModelViewHolder) holder).bind(model);
                    } else {
                        notifyItemChanged(i);
                    }
                    break;
                }
            }
            updateDownloadStats();
        }

        void updateError(String modelId, String error) {
            for (int i = 0; i < currentModelList.size(); i++) {
                Object model = currentModelList.get(i);
                String id = getIdFromModel(model);
                if (id != null && id.equals(modelId)) {
                    RecyclerView.ViewHolder holder = recyclerView.findViewHolderForAdapterPosition(i);
                    if (holder instanceof ModelViewHolder) {
                        ModelViewHolder mvh = (ModelViewHolder) holder;
                        mvh.tvStatus.setText("下载失败");
                        mvh.tvStatus.setTextColor(0xFFF44336);
                        mvh.llProgress.setVisibility(View.GONE);
                        mvh.btnAction.setText("重试");
                    } else {
                        notifyItemChanged(i);
                    }
                    break;
                }
            }
        }

        private String getIdFromModel(Object model) {
            if (model instanceof ModelDownloadManager.ModelPresetInfo) {
                return ((ModelDownloadManager.ModelPresetInfo) model).id;
            } else if (model instanceof OnlineModelInfo) {
                return ((OnlineModelInfo) model).downloadUrl;
            }
            return null;
        }

        class ModelViewHolder extends RecyclerView.ViewHolder {
            TextView tvName;
            TextView tvDescription;
            TextView tvSize;
            TextView tvInfo;
            TextView tvStatus;
            TextView tvProgressInfo;
            TextView tvSpeed;
            ProgressBar progressBar;
            MaterialButton btnAction;
            MaterialButton btnDelete;
            ImageView ivIcon;
            LinearLayout llProgress;

            ModelViewHolder(View itemView) {
                super(itemView);
                tvName = itemView.findViewById(R.id.tv_name);
                tvDescription = itemView.findViewById(R.id.tv_description);
                tvSize = itemView.findViewById(R.id.tv_size);
                tvInfo = itemView.findViewById(R.id.tv_info);
                tvStatus = itemView.findViewById(R.id.tv_status);
                tvProgressInfo = itemView.findViewById(R.id.tv_progress_info);
                tvSpeed = itemView.findViewById(R.id.tv_speed);
                progressBar = itemView.findViewById(R.id.progress_bar);
                btnAction = itemView.findViewById(R.id.btn_action);
                btnDelete = itemView.findViewById(R.id.btn_delete);
                ivIcon = itemView.findViewById(R.id.iv_icon);
                llProgress = itemView.findViewById(R.id.ll_progress);
            }

            void bind(Object model) {
                if (model instanceof ModelDownloadManager.ModelPresetInfo) {
                    bindLLMModel((ModelDownloadManager.ModelPresetInfo) model);
                } else if (model instanceof OnlineModelInfo) {
                    bindOnlineModel((OnlineModelInfo) model);
                }
            }

            private void bindLLMModel(ModelDownloadManager.ModelPresetInfo preset) {
                tvName.setText(preset.name);
                tvDescription.setText(preset.description);
                tvSize.setText("大小: " + preset.sizeMB + " MB");
                
                StringBuilder info = new StringBuilder();
                info.append("精度: ").append(preset.quantization)
                    .append(" | 上下文: ").append(preset.contextLength / 1024).append("K")
                    .append(" | 推荐显存: ").append(preset.minRamMB).append(" MB");
                
                // 显示多模态标识
                if (preset.multimodal) {
                    info.append(" | 多模态");
                }
                tvInfo.setText(info.toString());
                
                ivIcon.setImageResource(R.drawable.ic_ai_model);
                
                updateModelState(preset.id, preset.downloadUrl);
            }

            private void bindOnlineModel(OnlineModelInfo model) {
                tvName.setText(model.name);
                tvDescription.setText(model.description);
                tvSize.setText("大小: " + model.size);
                tvInfo.setText("格式: " + model.format + " | 精度: " + model.quantization + 
                    (model.params.isEmpty() ? "" : " | 参数: " + model.params));
                
                ivIcon.setImageResource(R.drawable.ic_ai_download);
                
                updateOnlineModelState(model.downloadUrl);
            }

            private void updateModelState(String modelId, String downloadUrl) {
                btnDelete.setVisibility(View.GONE);
                String modelDir = new File(getFilesDir(), "ai_models").getAbsolutePath();
                String fileName = getFileNameFromDownloadUrl(downloadUrl);
                String modelPath = modelDir + File.separator + fileName;
                
                // 检测多模态投影文件是否存在：
                // 优先按预设 mmprojUrl 的实际文件名（如 mmproj-F16.gguf / mmproj-model-f16.gguf）检测，
                // 兼容旧命名 "<模型名>.mmproj.gguf"，避免"多模态"徽标检测不到。
                boolean mmprojAvailable = false;
                ModelDownloadManager.ModelPresetInfo presetInfo = findPresetInfoByUrl(downloadUrl);
                if (presetInfo != null && presetInfo.mmprojUrl != null && !presetInfo.mmprojUrl.isEmpty()) {
                    String mmprojName = getFileNameFromDownloadUrl(presetInfo.mmprojUrl);
                    if (mmprojName != null && !mmprojName.isEmpty()) {
                        mmprojAvailable = new File(modelDir + File.separator + mmprojName).exists();
                    }
                }
                if (!mmprojAvailable) {
                    String baseName = fileName;
                    if (baseName.toLowerCase().endsWith(".gguf")) {
                        baseName = baseName.substring(0, baseName.length() - 5);
                    }
                    mmprojAvailable = new File(modelDir + File.separator + baseName + ".mmproj.gguf").exists();
                }
                
                boolean isDownloaded = new File(modelPath).exists();
                boolean isDownloading = modelDownloadManager.isDownloading(modelId);
                boolean isPaused = modelDownloadManager.isPaused(modelId);
                ModelDownloadManager.DownloadProgress progress = modelDownloadManager.getProgress(modelId);
                
                if (isDownloaded) {
                    StringBuilder status = new StringBuilder("已下载");
                    if (mmprojAvailable) {
                        status.append(" | 多模态");
                        tvStatus.setTextColor(0xFF00BCD4);  // 青色标识多模态可用
                    } else {
                        tvStatus.setTextColor(0xFF4CAF50);
                    }
                    tvStatus.setText(status.toString());
                    llProgress.setVisibility(View.GONE);
                    btnAction.setText("使用");
                    btnDelete.setVisibility(View.VISIBLE);
                    final String finalFileName = fileName;
                    btnAction.setOnClickListener(v -> switchToModel(finalFileName));
                    btnDelete.setOnClickListener(v -> {
                        File file = new File(modelPath);
                        if (file.exists()) {
                            file.delete();
                        }
                        // 同时清理未完成的 .part 临时文件（.part 机制）
                        File part = new File(modelPath + ".part");
                        if (part.exists()) {
                            part.delete();
                        }
                        // 删除对应的 mmproj 文件（按预设实际文件名 + 兼容旧命名）
                        deleteMmprojForModel(modelDir, fileName, downloadUrl);
                        // P3: 若删除的是当前使用中的模型，卸载并重置 AI 服务状态
                        resetAIServiceIfCurrentModel(fileName);
                        updateModelState(modelId, downloadUrl);
                        updateDownloadStats();
                    });
                } else if (isPaused && progress != null) {
                    // 暂停状态：显示已下载进度，提供“继续”按钮
                    tvStatus.setText("已暂停 " + progress.getProgressPercent() + "%");
                    tvStatus.setTextColor(0xFFFF9800);
                    llProgress.setVisibility(View.VISIBLE);
                    progressBar.setProgress(progress.getProgressPercent());
                    if (tvSpeed != null) tvSpeed.setText("");
                    btnAction.setText("继续");
                    btnAction.setOnClickListener(v -> {
                        modelDownloadManager.resume(modelId);
                        tvStatus.setText("恢复下载...");
                        btnAction.setText("暂停");
                        btnAction.setOnClickListener(v2 -> {
                            modelDownloadManager.pause(modelId);
                            updateModelState(modelId, downloadUrl);
                        });
                    });
                } else if (isDownloading && progress != null) {
                    int percent = progress.getProgressPercent();
                    long downloadedMB = progress.getDownloadedBytes() / (1024 * 1024);
                    long totalMB = progress.getTotalBytes() / (1024 * 1024);
                    long speedBps = progress.getSpeedBps();
                    
                    tvStatus.setText("下载中... " + percent + "%");
                    tvStatus.setTextColor(0xFFFF9800);
                    llProgress.setVisibility(View.VISIBLE);
                    progressBar.setProgress(percent);
                    
                    if (tvProgressInfo != null) {
                        tvProgressInfo.setText(downloadedMB + " MB / " + totalMB + " MB");
                    }
                    if (tvSpeed != null) {
                        tvSpeed.setText(formatSpeed(speedBps));
                    }
                    
                    btnAction.setText("暂停");
                    btnAction.setOnClickListener(v -> {
                        modelDownloadManager.pause(modelId);
                        updateModelState(modelId, downloadUrl);
                    });
                } else {
                    tvStatus.setText("在线");
                    tvStatus.setTextColor(0xFF2196F3);
                    llProgress.setVisibility(View.GONE);
                    btnAction.setText("下载");
                    btnAction.setOnClickListener(v -> {
                        startDownload(modelId, downloadUrl);
                    });
                }
            }

            /** P3: 若删除的是当前使用中的模型，卸载并重置 AI 服务，避免对话指向已删文件 */
            private void resetAIServiceIfCurrentModel(String deletedFileName) {
                try {
                    com.oilquiz.app.ai.service.AIService aiService =
                            com.oilquiz.app.ai.service.AIService.getInstance(ModelDownloadActivity.this);
                    if (aiService == null) return;
                    String cur = aiService.getCurrentModelName();
                    if (cur != null && cur.equals(deletedFileName)) {
                        com.oilquiz.app.util.AILogger.i("ModelDownloadActivity", "删除的是当前模型，卸载 AI 服务: " + deletedFileName);
                        aiService.unloadCurrentModel();
                    }
                } catch (Exception e) {
                    com.oilquiz.app.util.AILogger.w("ModelDownloadActivity", "resetAIServiceIfCurrentModel failed: " + e.getMessage());
                }
            }

            /** 切换到指定模型（热切换，带进度回调） */
            private void switchToModel(String fileName) {
                try {
                    com.oilquiz.app.ai.service.AIService aiService =
                            com.oilquiz.app.ai.service.AIService.getInstance(ModelDownloadActivity.this);
                    if (aiService == null) {
                        showToast("AI 服务未初始化，请先完成初始化");
                        return;
                    }
                    String cur = aiService.getCurrentModelName();
                    if (cur != null && cur.equals(fileName)) {
                        showToast("该模型已是当前使用模型");
                        return;
                    }
                    showToast("正在切换到: " + fileName);
                    aiService.hotSwitchModel(fileName, new com.oilquiz.app.ai.service.AIService.HotSwitchCallback() {
                        @Override
                        public void onSwitchStarted(String fromModel, String toModel) {
                            runOnUiThread(() -> showToast("开始切换模型..."));
                        }
                        @Override
                        public void onSwitchProgress(int progress, String message) {}
                        @Override
                        public void onSwitchCompleted(boolean success, String model) {
                            runOnUiThread(() -> {
                                if (success) {
                                    showToast("模型切换成功: " + model);
                                } else {
                                    showToast("模型切换失败");
                                }
                            });
                        }
                        @Override
                        public void onSwitchFailed(String reason) {
                            runOnUiThread(() -> showToast("模型切换失败: " + reason));
                        }
                    });
                } catch (Exception e) {
                    com.oilquiz.app.util.AILogger.w("ModelDownloadActivity", "switchToModel failed: " + e.getMessage());
                    showToast("切换失败: " + e.getMessage());
                }
            }

            /** 删除与模型关联的 mmproj 文件（优先预设实际文件名，兼容旧命名） */
            private void deleteMmprojForModel(String modelDir, String fileName, String downloadUrl) {
                ModelDownloadManager.ModelPresetInfo presetInfo = findPresetInfoByUrl(downloadUrl);
                if (presetInfo != null && presetInfo.mmprojUrl != null && !presetInfo.mmprojUrl.isEmpty()) {
                    String mmprojName = getFileNameFromDownloadUrl(presetInfo.mmprojUrl);
                    if (mmprojName != null && !mmprojName.isEmpty()) {
                        File f = new File(modelDir + File.separator + mmprojName);
                        if (f.exists()) f.delete();
                        // 同时清理 mmproj 的 .part 临时文件
                        File fpart = new File(modelDir + File.separator + mmprojName + ".part");
                        if (fpart.exists()) fpart.delete();
                    }
                }
                String baseName = fileName;
                if (baseName.toLowerCase().endsWith(".gguf")) {
                    baseName = baseName.substring(0, baseName.length() - 5);
                }
                File legacy = new File(modelDir + File.separator + baseName + ".mmproj.gguf");
                if (legacy.exists()) legacy.delete();
                File legacyPart = new File(modelDir + File.separator + baseName + ".mmproj.gguf.part");
                if (legacyPart.exists()) legacyPart.delete();
            }

            private void updateOnlineModelState(String downloadUrl) {
                btnDelete.setVisibility(View.GONE);
                String fileName = getFileNameFromDownloadUrl(downloadUrl);
                // 搜索结果：downloadUrl 是 resolve/main/ 基础路径，文件名为空，判定为未下载
                boolean isSearchResult = TextUtils.isEmpty(fileName) || (downloadUrl != null && downloadUrl.endsWith("/resolve/main/"));
                String modelPath = new File(getFilesDir(), "ai_models").getAbsolutePath() + File.separator + fileName;
                
                boolean isDownloaded = !isSearchResult && new File(modelPath).exists();
                boolean isDownloading = modelDownloadManager.isDownloading(downloadUrl);
                boolean isPaused = modelDownloadManager.isPaused(downloadUrl);
                ModelDownloadManager.DownloadProgress progress = modelDownloadManager.getProgress(downloadUrl);
                
                if (isDownloaded) {
                    tvStatus.setText("已下载");
                    tvStatus.setTextColor(0xFF4CAF50);
                    llProgress.setVisibility(View.GONE);
                    btnAction.setText("使用");
                    btnDelete.setVisibility(View.VISIBLE);
                    final String finalFileName = fileName;
                    btnAction.setOnClickListener(v -> switchToModel(finalFileName));
                    btnDelete.setOnClickListener(v -> {
                        File file = new File(modelPath);
                        if (file.exists()) {
                            file.delete();
                        }
                        updateOnlineModelState(downloadUrl);
                        updateDownloadStats();
                    });
                } else if (isPaused && progress != null) {
                    tvStatus.setText("已暂停 " + progress.getProgressPercent() + "%");
                    tvStatus.setTextColor(0xFFFF9800);
                    llProgress.setVisibility(View.VISIBLE);
                    progressBar.setProgress(progress.getProgressPercent());
                    if (tvSpeed != null) tvSpeed.setText("");
                    btnAction.setText("继续");
                    btnAction.setOnClickListener(v -> {
                        modelDownloadManager.resume(downloadUrl);
                        tvStatus.setText("恢复下载...");
                        btnAction.setText("暂停");
                        btnAction.setOnClickListener(v2 -> {
                            modelDownloadManager.pause(downloadUrl);
                            updateOnlineModelState(downloadUrl);
                        });
                    });
                } else if (isDownloading && progress != null) {
                    tvStatus.setText("下载中... " + progress.getProgressPercent() + "%");
                    tvStatus.setTextColor(0xFFFF9800);
                    llProgress.setVisibility(View.VISIBLE);
                    progressBar.setProgress(progress.getProgressPercent());
                    btnAction.setText("暂停");
                    btnAction.setOnClickListener(v -> {
                        modelDownloadManager.pause(downloadUrl);
                        updateOnlineModelState(downloadUrl);
                    });
                } else {
                    tvStatus.setText("在线");
                    tvStatus.setTextColor(0xFF2196F3);
                    llProgress.setVisibility(View.GONE);
                    // 搜索结果：downloadUrl 是 resolve/main/ 基础路径 → 调文件列表 API 显示 .gguf 文件供选择
                    if (downloadUrl != null && downloadUrl.endsWith("/resolve/main/")) {
                        btnAction.setText("选择文件");
                        btnAction.setOnClickListener(v -> {
                            // 从 resolve/main/ 基础路径中提取 repoId（兼容任意镜像源）
                            String repoId = downloadUrl;
                            int schemeIdx = repoId.indexOf("://");
                            if (schemeIdx >= 0) repoId = repoId.substring(schemeIdx + 3);
                            int slashIdx = repoId.indexOf('/');
                            if (slashIdx >= 0) repoId = repoId.substring(slashIdx + 1);
                            repoId = repoId.replace("/resolve/main/", "");
                            fetchAndShowRepoFiles(repoId, downloadUrl);
                        });
                    } else {
                        btnAction.setText("下载");
                        btnAction.setOnClickListener(v -> {
                            startDownload(downloadUrl, downloadUrl);
                        });
                    }
                }
            }

            private void startDownload(String modelId, String downloadUrl) {
                tvStatus.setText("开始下载...");
                tvStatus.setTextColor(0xFFFF9800);
                llProgress.setVisibility(View.VISIBLE);
                progressBar.setProgress(0);
                if (tvSpeed != null) tvSpeed.setText("");
                btnAction.setText("暂停");
                btnAction.setOnClickListener(v -> {
                    modelDownloadManager.pause(modelId);
                    tvStatus.setText("已暂停");
                    btnAction.setText("继续");
                    btnAction.setOnClickListener(v2 -> {
                        modelDownloadManager.resume(modelId);
                        tvStatus.setText("恢复下载...");
                        btnAction.setText("暂停");
                    });
                });
                
                // 查找匹配的预设模型信息，以支持多模态下载
                ModelDownloadManager.ModelPresetInfo presetInfo = findPresetInfoByUrl(downloadUrl);
                if (presetInfo != null) {
                    // 使用 downloadPresetModel，支持多模态投影文件下载
                    // 不传局部回调：全局 setGlobalCallback 已统一处理所有 UI 更新，避免双重触发
                    modelDownloadManager.downloadPresetModel(modelId, presetInfo, null);
                } else {
                    // 未找到预设模型，使用自定义 URL 下载
                    modelDownloadManager.downloadFromCustomUrl(modelId, downloadUrl, null);
                }
            }
            
            /**
             * 根据下载 URL 查找匹配的预设模型信息
             */
            private ModelDownloadManager.ModelPresetInfo findPresetInfoByUrl(String downloadUrl) {
                List<ModelDownloadManager.ModelPresetInfo> allModels = modelDownloadManager.getPresetDomesticModels();
                for (ModelDownloadManager.ModelPresetInfo preset : allModels) {
                    if (preset.downloadUrl != null && preset.downloadUrl.equals(downloadUrl)) {
                        return preset;
                    }
                }
                return null;
            }

            /**
             * 从下载 URL 提取文件名，去除查询参数，与 ModelDownloadManager.getFileNameFromUrl 保持一致
             */
            private String getFileNameFromDownloadUrl(String url) {
                if (url == null || url.isEmpty()) return "model.gguf";
                try {
                    String decoded = java.net.URLDecoder.decode(url, java.nio.charset.StandardCharsets.UTF_8);
                    int lastSlash = decoded.lastIndexOf('/');
                    if (lastSlash >= 0 && lastSlash < decoded.length() - 1) {
                        String fileName = decoded.substring(lastSlash + 1);
                        int queryIdx = fileName.indexOf('?');
                        if (queryIdx > 0) fileName = fileName.substring(0, queryIdx);
                        return fileName;
                    }
                } catch (Exception ignored) {}
                return url.substring(url.lastIndexOf('/') + 1);
            }
        }
    }
}
