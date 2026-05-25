package com.oilquiz.app.ui.activity;

import android.app.AlertDialog;
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

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import androidx.appcompat.widget.SearchView;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.model.ModelDownloadManager;
import com.oilquiz.app.ui.base.BaseActivity;

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
    private Spinner spinnerModelType;
    private Spinner spinnerCategory;
    private RecyclerView recyclerView;
    private TextView tvDownloadStats;
    private TextView tvSearchHint;
    private TextView tvMirrorSource;
    private MaterialButton btnSwitchMirror;
    
    private ModelDownloadManager modelDownloadManager;
    
    private List<Object> currentModelList;
    private List<Object> allModelList;
    private ModelAdapter modelAdapter;
    
    private String selectedModelType = "llm";
    private String selectedCategory = "all";
    private String searchQuery = "";
    
    private final String[] MODEL_TYPES = {"LLM 模型", "在线搜索"};
    private final String[] LLM_CATEGORIES = {"全部模型", "中文模型", "代码模型", "轻量级", "高性能"};
    private final String[] ONLINE_CATEGORIES = {"GGUF", "PyTorch", "ONNX", "TensorFlow"};
    
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
        btnAddUrl = findViewById(R.id.btn_add_url);
        spinnerModelType = findViewById(R.id.spinner_model_type);
        spinnerCategory = findViewById(R.id.spinner_category);
        recyclerView = findViewById(R.id.recycler_view);
        tvDownloadStats = findViewById(R.id.tv_download_stats);
        tvSearchHint = findViewById(R.id.tv_search_hint);
        tvMirrorSource = findViewById(R.id.tv_mirror_source);
        btnSwitchMirror = findViewById(R.id.btn_switch_mirror);
        
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
    }

    @Override
    protected void initData() {
        modelDownloadManager = ModelDownloadManager.getInstance(this);
        
        currentModelList = new ArrayList<>();
        allModelList = new ArrayList<>();
        modelAdapter = new ModelAdapter();
        recyclerView.setAdapter(modelAdapter);
        
        setupSpinners();
        loadModels();
        updateDownloadStats();
        
        modelDownloadManager.setGlobalCallback(new ModelDownloadManager.DownloadCallback() {
            @Override
            public void onProgress(String modelId, int progress, long downloadedMB, long totalMB) {
                runOnUiThread(() -> {
                    modelAdapter.updateProgress(modelId, progress);
                });
            }

            @Override
            public void onComplete(String modelId, String filePath) {
                runOnUiThread(() -> {
                    modelAdapter.updateComplete(modelId, filePath);
                    updateDownloadStats();
                    loadModels();
                });
            }

            @Override
            public void onError(String modelId, String error) {
                runOnUiThread(() -> {
                    modelAdapter.updateError(modelId, error);
                });
            }

            @Override
            public void onPaused(String modelId) {}

            @Override
            public void onCancelled(String modelId) {}
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
                } else {
                    filterModels();
                }
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                searchQuery = newText.trim();
                filterModels();
                return true;
            }
        });
        
        btnAddUrl.setOnClickListener(v -> showCustomUrlDialog(null));
        
        spinnerModelType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedModelType = position == 0 ? "llm" : "online";
                updateCategorySpinner();
                loadModels();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        
        spinnerCategory.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (selectedModelType.equals("llm")) {
                    switch (position) {
                        case 0: selectedCategory = "all"; break;
                        case 1: selectedCategory = "chinese"; break;
                        case 2: selectedCategory = "code"; break;
                        case 3: selectedCategory = "lightweight"; break;
                        case 4: selectedCategory = "performance"; break;
                    }
                } else {
                    selectedCategory = ONLINE_CATEGORIES[position].toLowerCase();
                }
                loadModels();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        
        // 镜像源切换
        btnSwitchMirror.setOnClickListener(v -> showMirrorSwitchDialog());
        
        // 初始化镜像显示
        updateMirrorSourceDisplay();
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

    private void setupSpinners() {
        ArrayAdapter<String> typeAdapter = new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_dropdown_item, MODEL_TYPES);
        spinnerModelType.setAdapter(typeAdapter);
        updateCategorySpinner();
    }

    private void updateCategorySpinner() {
        String[] categories;
        if (selectedModelType.equals("llm")) {
            categories = LLM_CATEGORIES;
        } else {
            categories = ONLINE_CATEGORIES;
        }
        ArrayAdapter<String> categoryAdapter = new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_dropdown_item, categories);
        spinnerCategory.setAdapter(categoryAdapter);
    }

    private void loadModels() {
        allModelList.clear();
        
        if (selectedModelType.equals("online")) {
            loadOnlineModels();
        } else {
            loadLLMModels();
        }
        
        filterModels();
    }

    private void loadOnlineModels() {
        tvSearchHint.setVisibility(View.VISIBLE);
        tvSearchHint.setText("提示：在搜索框输入 Hugging Face 模型名称（如：TheBloke/Llama-2-7B-Chat-GGUF）或直接输入模型下载链接");
        
        List<OnlineModelInfo> onlineModels = getPopularOnlineModels();
        allModelList.addAll(onlineModels);
    }

    private List<OnlineModelInfo> getPopularOnlineModels() {
        List<OnlineModelInfo> models = new ArrayList<>();
        
        models.add(new OnlineModelInfo("Llama-2-7B-Chat", "Meta Llama 2 7B 聊天模型", 
            "https://huggingface.co/TheBloke/Llama-2-7B-Chat-GGUF/resolve/main/llama-2-7b-chat.Q4_K_M.gguf", 
            "Q4_K_M", "4.2 GB", "GGUF", "7B"));
        
        models.add(new OnlineModelInfo("Zephyr-7B-beta", "Hugging Face Zephyr 聊天模型", 
            "https://huggingface.co/TheBloke/zephyr-7B-beta-GGUF/resolve/main/zephyr-7b-beta.Q4_K_M.gguf", 
            "Q4_K_M", "4.5 GB", "GGUF", "7B"));
        
        models.add(new OnlineModelInfo("Mistral-7B-Instruct", "Mistral 7B 指令模型", 
            "https://huggingface.co/TheBloke/Mistral-7B-Instruct-v0.2-GGUF/resolve/main/mistral-7b-instruct-v0.2.Q4_K_M.gguf", 
            "Q4_K_M", "4.1 GB", "GGUF", "7B"));
        
        models.add(new OnlineModelInfo("Yi-6B-Chat", "零一万物 Yi-6B 聊天模型", 
            "https://huggingface.co/TheBloke/Yi-6B-Chat-GGUF/resolve/main/yi-6b-chat.Q4_K_M.gguf", 
            "Q4_K_M", "3.7 GB", "GGUF", "6B"));
        
        models.add(new OnlineModelInfo("Qwen-7B-Chat", "阿里通义千问 Qwen-7B", 
            "https://huggingface.co/Qwen/Qwen-7B-Chat-GGUF/resolve/main/qwen-7b-chat-q4_0.gguf", 
            "Q4_0", "4.3 GB", "GGUF", "7B"));
        
        models.add(new OnlineModelInfo("ChatGLM3-6B", "智谱 ChatGLM3-6B", 
            "https://huggingface.co/THUDM/chatglm3-6b-gguf/resolve/main/chatglm3-6b-q4_0.gguf", 
            "Q4_0", "3.8 GB", "GGUF", "6B"));
        
        models.add(new OnlineModelInfo("Qwen-14B-Chat", "阿里通义千问 Qwen-14B", 
            "https://huggingface.co/Qwen/Qwen-14B-Chat-GGUF/resolve/main/qwen-14b-chat-q4_0.gguf", 
            "Q4_0", "8.4 GB", "GGUF", "14B"));
        
        models.add(new OnlineModelInfo("Llama-2-13B-Chat", "Meta Llama 2 13B", 
            "https://huggingface.co/TheBloke/Llama-2-13B-Chat-GGUF/resolve/main/llama-2-13b-chat.Q4_K_M.gguf", 
            "Q4_K_M", "8.0 GB", "GGUF", "13B"));
        
        return models;
    }

    private void loadLLMModels() {
        tvSearchHint.setVisibility(View.GONE);
        List<ModelDownloadManager.ModelPresetInfo> allModels = modelDownloadManager.getPresetDomesticModels();
        ModelDownloadManager.ModelCategory category;
        
        switch (selectedCategory) {
            case "chinese":
                category = ModelDownloadManager.ModelCategory.CHINESE;
                break;
            case "code":
                category = ModelDownloadManager.ModelCategory.CODE;
                break;
            case "lightweight":
                category = ModelDownloadManager.ModelCategory.LIGHTWEIGHT;
                break;
            case "performance":
                category = ModelDownloadManager.ModelCategory.PERFORMANCE;
                break;
            default:
                category = ModelDownloadManager.ModelCategory.ALL;
                break;
        }
        
        if (category == ModelDownloadManager.ModelCategory.ALL) {
            allModelList.addAll(allModels);
        } else {
            allModelList.addAll(modelDownloadManager.getPresetModelsByCategory(category));
        }
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
                name.endsWith(".tflite"));
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

        void updateProgress(String modelId, int progress) {
            for (int i = 0; i < currentModelList.size(); i++) {
                Object model = currentModelList.get(i);
                String id = getIdFromModel(model);
                if (id != null && id.equals(modelId)) {
                    notifyItemChanged(i);
                    break;
                }
            }
        }

        void updateComplete(String modelId, String filePath) {
            updateProgress(modelId, 100);
        }

        void updateError(String modelId, String error) {
            updateProgress(modelId, -1);
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
            ProgressBar progressBar;
            MaterialButton btnAction;
            ImageView ivIcon;
            LinearLayout llProgress;

            ModelViewHolder(View itemView) {
                super(itemView);
                tvName = itemView.findViewById(R.id.tv_name);
                tvDescription = itemView.findViewById(R.id.tv_description);
                tvSize = itemView.findViewById(R.id.tv_size);
                tvInfo = itemView.findViewById(R.id.tv_info);
                tvStatus = itemView.findViewById(R.id.tv_status);
                progressBar = itemView.findViewById(R.id.progress_bar);
                btnAction = itemView.findViewById(R.id.btn_action);
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
                tvInfo.setText("精度: " + preset.quantization + " | 上下文: " + 
                    (preset.contextLength / 1024) + "K | 推荐显存: " + preset.minRamMB + " MB");
                
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
                String modelDir = new File(getFilesDir(), "ai_models").getAbsolutePath();
                String fileName = downloadUrl.substring(downloadUrl.lastIndexOf('/') + 1);
                String modelPath = modelDir + File.separator + fileName;
                
                boolean isDownloaded = new File(modelPath).exists();
                boolean isDownloading = modelDownloadManager.isDownloading(modelId);
                ModelDownloadManager.DownloadProgress progress = modelDownloadManager.getProgress(modelId);
                
                if (isDownloaded) {
                    tvStatus.setText("已下载");
                    tvStatus.setTextColor(0xFF4CAF50);
                    llProgress.setVisibility(View.GONE);
                    btnAction.setText("删除");
                    btnAction.setOnClickListener(v -> {
                        File file = new File(modelPath);
                        if (file.exists()) {
                            file.delete();
                        }
                        updateModelState(modelId, downloadUrl);
                        updateDownloadStats();
                    });
                } else if (isDownloading && progress != null) {
                    tvStatus.setText("下载中... " + progress.getProgressPercent() + "%");
                    tvStatus.setTextColor(0xFFFF9800);
                    llProgress.setVisibility(View.VISIBLE);
                    progressBar.setProgress(progress.getProgressPercent());
                    btnAction.setText("取消");
                    btnAction.setOnClickListener(v -> {
                        modelDownloadManager.cancel(modelId);
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

            private void updateOnlineModelState(String downloadUrl) {
                String fileName = downloadUrl.substring(downloadUrl.lastIndexOf('/') + 1);
                String modelPath = new File(getFilesDir(), "ai_models").getAbsolutePath() + File.separator + fileName;
                
                boolean isDownloaded = new File(modelPath).exists();
                boolean isDownloading = modelDownloadManager.isDownloading(downloadUrl);
                ModelDownloadManager.DownloadProgress progress = modelDownloadManager.getProgress(downloadUrl);
                
                if (isDownloaded) {
                    tvStatus.setText("已下载");
                    tvStatus.setTextColor(0xFF4CAF50);
                    llProgress.setVisibility(View.GONE);
                    btnAction.setText("删除");
                    btnAction.setOnClickListener(v -> {
                        File file = new File(modelPath);
                        if (file.exists()) {
                            file.delete();
                        }
                        updateOnlineModelState(downloadUrl);
                        updateDownloadStats();
                    });
                } else if (isDownloading && progress != null) {
                    tvStatus.setText("下载中... " + progress.getProgressPercent() + "%");
                    tvStatus.setTextColor(0xFFFF9800);
                    llProgress.setVisibility(View.VISIBLE);
                    progressBar.setProgress(progress.getProgressPercent());
                    btnAction.setText("取消");
                    btnAction.setOnClickListener(v -> {
                        modelDownloadManager.cancel(downloadUrl);
                        updateOnlineModelState(downloadUrl);
                    });
                } else {
                    tvStatus.setText("在线");
                    tvStatus.setTextColor(0xFF2196F3);
                    llProgress.setVisibility(View.GONE);
                    btnAction.setText("下载");
                    btnAction.setOnClickListener(v -> {
                        startDownload(downloadUrl, downloadUrl);
                    });
                }
            }

            private void startDownload(String modelId, String downloadUrl) {
                tvStatus.setText("开始下载...");
                tvStatus.setTextColor(0xFFFF9800);
                llProgress.setVisibility(View.VISIBLE);
                progressBar.setProgress(0);
                btnAction.setText("取消");
                btnAction.setOnClickListener(v -> modelDownloadManager.cancel(modelId));
                
                modelDownloadManager.downloadFromCustomUrl(modelId, downloadUrl, 
                    new ModelDownloadManager.DownloadCallback() {
                        @Override
                        public void onProgress(String id, int progress, long downloadedMB, long totalMB) {
                            runOnUiThread(() -> {
                                progressBar.setProgress(progress);
                                tvStatus.setText("下载中... " + progress + "%");
                            });
                        }

                        @Override
                        public void onComplete(String id, String filePath) {
                            runOnUiThread(() -> {
                                tvStatus.setText("已下载");
                                tvStatus.setTextColor(0xFF4CAF50);
                                llProgress.setVisibility(View.GONE);
                                btnAction.setText("删除");
                                updateDownloadStats();
                            });
                        }

                        @Override
                        public void onError(String id, String error) {
                            runOnUiThread(() -> {
                                tvStatus.setText("下载失败");
                                tvStatus.setTextColor(0xFFF44336);
                                llProgress.setVisibility(View.GONE);
                                btnAction.setText("重试");
                            });
                        }

                        @Override
                        public void onPaused(String id) {}

                        @Override
                        public void onCancelled(String id) {
                            runOnUiThread(() -> {
                                tvStatus.setText("已取消");
                                tvStatus.setTextColor(0xFF999999);
                                llProgress.setVisibility(View.GONE);
                                btnAction.setText("下载");
                            });
                        }
                    });
            }
        }
    }
}
