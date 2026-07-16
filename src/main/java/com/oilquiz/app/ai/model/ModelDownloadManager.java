package com.oilquiz.app.ai.model;

import android.content.Context;
import android.util.Log;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ModelDownloadManager {
    private static final String TAG = "ModelDownloadManager";
    private static final int BUFFER_SIZE = 8192;
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final int CONNECT_TIMEOUT_MS = 30000;
    private static final int READ_TIMEOUT_MS = 300000;

    private static volatile ModelDownloadManager INSTANCE;
    private final Context context;
    private final Map<String, DownloadTask> downloadTasks = new ConcurrentHashMap<>();
    private final Map<String, DownloadProgress> downloadProgress = new ConcurrentHashMap<>();
    private final AtomicInteger activeDownloads = new AtomicInteger(0);
    private final int maxConcurrentDownloads = 2;
    private ExecutorService executor;
    private DownloadCallback globalCallback;

    private MirrorSource currentMirrorSource = MirrorSource.HF_MIRROR;
    private boolean useDomesticMirror = true;

    public enum MirrorSource {
        HUGGINGFACE("huggingface.co", "https://huggingface.co"),
        HF_MIRROR("hf-mirror.com", "https://hf-mirror.com"),
        HF_CN("hf-mirror.com", "https://hf-mirror.com"),
        MODELSCOPE("modelscope.cn", "https://www.modelscope.cn"),
        GITEE("gitee.com", "https://gitee.com"),
        CUSTOM("custom", null);

        public final String domain;
        public final String baseUrl;

        MirrorSource(String domain, String baseUrl) {
            this.domain = domain;
            this.baseUrl = baseUrl;
        }
    }

    private static final java.util.Map<String, String> MIRROR_URL_MAPPINGS = new java.util.HashMap<>();
    static {
        MIRROR_URL_MAPPINGS.put("huggingface.co", "hf-mirror.com");
        MIRROR_URL_MAPPINGS.put("cdn-lfs.huggingface.co", "hf-mirror.com");
        MIRROR_URL_MAPPINGS.put("huggingface.co:443", "hf-mirror.com");
    }

    private ModelDownloadManager(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newFixedThreadPool(maxConcurrentDownloads);
        loadMirrorPreference();
    }

    public static ModelDownloadManager getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (ModelDownloadManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ModelDownloadManager(context);
                }
            }
        }
        return INSTANCE;
    }

    private void loadMirrorPreference() {
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences("model_download_prefs", Context.MODE_PRIVATE);
            String savedMirror = prefs.getString("mirror_source", MirrorSource.HF_MIRROR.name());
            currentMirrorSource = MirrorSource.valueOf(savedMirror);
            useDomesticMirror = prefs.getBoolean("use_domestic_mirror", true);
        } catch (Exception e) {
            android.util.Log.w(TAG, "Failed to load mirror preference, using default", e);
        }
    }

    private void saveMirrorPreference() {
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences("model_download_prefs", Context.MODE_PRIVATE);
            prefs.edit()
                .putString("mirror_source", currentMirrorSource.name())
                .putBoolean("use_domestic_mirror", useDomesticMirror)
                .apply();
        } catch (Exception e) {
            android.util.Log.w(TAG, "Failed to save mirror preference", e);
        }
    }

    public void setUseDomesticMirror(boolean enabled) {
        this.useDomesticMirror = enabled;
        saveMirrorPreference();
    }

    public boolean isUseDomesticMirror() {
        return useDomesticMirror;
    }

    public void setCurrentMirrorSource(MirrorSource source) {
        this.currentMirrorSource = source;
        saveMirrorPreference();
    }

    public MirrorSource getCurrentMirrorSource() {
        return currentMirrorSource;
    }

    public String convertToDomesticMirror(String originalUrl) {
        if (!useDomesticMirror || originalUrl == null || originalUrl.isEmpty()) {
            return originalUrl;
        }

        try {
            java.net.URI uri = java.net.URI.create(originalUrl);
            String host = uri.getHost();
            if (host == null) {
                return originalUrl;
            }

            String mirrorDomain = MIRROR_URL_MAPPINGS.get(host);
            if (mirrorDomain != null) {
                String convertedUrl = originalUrl.replace(host, mirrorDomain);
                android.util.Log.i(TAG, "URL converted to domestic mirror: " + originalUrl + " -> " + convertedUrl);
                return convertedUrl;
            }

            if (host.contains("modelscope.cn") || host.contains("hf-mirror.com")) {
                return originalUrl;
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "Failed to convert URL to mirror: " + originalUrl, e);
        }

        return originalUrl;
    }

    public String getDomesticMirrorUrl(String modelRepo, String fileName, String revision) {
        String baseUrl = currentMirrorSource.baseUrl;
        if (baseUrl == null) {
            return null;
        }

        switch (currentMirrorSource) {
            case HF_MIRROR:
            case HF_CN:
                return String.format("%s/%s/resolve/%s/%s", 
                    baseUrl, modelRepo, revision != null ? revision : "main", fileName);
            case MODELSCOPE:
                return String.format("%s/models/%s/resolve/%s/%s",
                    baseUrl, modelRepo, revision != null ? revision : "master", fileName);
            case HUGGINGFACE:
                return String.format("%s/%s/resolve/%s/%s",
                    baseUrl, modelRepo, revision != null ? revision : "main", fileName);
            default:
                return null;
        }
    }

    public static final String[] PRESET_DOMESTIC_MODEL_URLS = {
        "https://hf-mirror.com/Qwen/Qwen2-0.5B-Instruct-GGUF/resolve/main/qwen2-0_5b-instruct-q4_k_m.gguf",
        "https://hf-mirror.com/Qwen/Qwen2-1.5B-Instruct-GGUF/resolve/main/qwen2-1_5b-instruct-q4_k_m.gguf",
        "https://hf-mirror.com/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
        "https://hf-mirror.com/THUDM/chatglm3-6b-gguf/resolve/main/chatglm3-ggml-q4_0.bin",
        "https://hf-mirror.com/chuanli11/Chinese-Vicuna-7B-GGML/resolve/main/chinese-vicuna-7b-ggml-q4_0.bin"
    };

    public List<ModelPresetInfo> getPresetDomesticModels() {
        List<ModelPresetInfo> list = new java.util.ArrayList<>();

        list.add(new ModelPresetInfo(
            "qwen2-0.5b-instruct-q4_k_m",
            "Qwen2-0.5B-Instruct",
            "通义千问2 0.5B 指令微调版，轻量级中文模型，响应速度快",
            "https://hf-mirror.com/Qwen/Qwen2-0.5B-Instruct-GGUF/resolve/main/qwen2-0_5b-instruct-q4_k_m.gguf",
            330,
            "Q4_K_M",
            32768,
            1024,
            2
        ));
        list.add(new ModelPresetInfo(
            "qwen2-1.5b-instruct-q4_k_m",
            "Qwen2-1.5B-Instruct",
            "通义千问2 1.5B 指令微调版，中文能力强，平衡性能与速度",
            "https://hf-mirror.com/Qwen/Qwen2-1.5B-Instruct-GGUF/resolve/main/qwen2-1_5b-instruct-q4_k_m.gguf",
            920,
            "Q4_K_M",
            32768,
            2048,
            4
        ));
        list.add(new ModelPresetInfo(
            "qwen2-3b-instruct-q4_k_m",
            "Qwen2-3B-Instruct",
            "通义千问2 3B 指令微调版，更强的推理和创作能力",
            "https://hf-mirror.com/Qwen/Qwen2-3B-Instruct-GGUF/resolve/main/qwen2-3b-instruct-q4_k_m.gguf",
            1800,
            "Q4_K_M",
            32768,
            4096,
            8
        ));
        list.add(new ModelPresetInfo(
            "qwen2.5-0.5b-instruct-q4_k_m",
            "Qwen2.5-0.5B-Instruct",
            "通义千问2.5 0.5B 最新版，更强性能，更好的中文理解",
            "https://hf-mirror.com/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            350,
            "Q4_K_M",
            32768,
            1024,
            2
        ));
        list.add(new ModelPresetInfo(
            "qwen2.5-1.5b-instruct-q4_k_m",
            "Qwen2.5-1.5B-Instruct",
            "通义千问2.5 1.5B 最新版，中文能力出色，多语言支持",
            "https://hf-mirror.com/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            950,
            "Q4_K_M",
            32768,
            2048,
            4
        ));
        list.add(new ModelPresetInfo(
            "qwen2.5-3b-instruct-q4_k_m",
            "Qwen2.5-3B-Instruct",
            "通义千问2.5 3B 最新版，更强的推理和代码能力",
            "https://hf-mirror.com/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            1900,
            "Q4_K_M",
            32768,
            4096,
            8
        ));
        list.add(new ModelPresetInfo(
            "qwen2.5-coder-1.5b-instruct-q4_k_m",
            "Qwen2.5-Coder-1.5B-Instruct",
            "通义千问2.5 Coder 1.5B 代码专用模型，擅长编程任务",
            "https://hf-mirror.com/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            950,
            "Q4_K_M",
            32768,
            2048,
            4
        ));
        list.add(new ModelPresetInfo(
            "qwen2.5-coder-0.5b-instruct-q4_k_m",
            "Qwen2.5-Coder-0.5B-Instruct",
            "通义千问2.5 Coder 0.5B 轻量级代码模型",
            "https://hf-mirror.com/Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-0.5b-instruct-q4_k_m.gguf",
            350,
            "Q4_K_M",
            32768,
            1024,
            2
        ));
        list.add(new ModelPresetInfo(
            "llama3.2-1b-instruct-q4_k_m",
            "Llama-3.2-1B-Instruct",
            "Meta最新轻量级模型，指令优化，长上下文支持",
            "https://hf-mirror.com/hugging-quants/Llama-3.2-1B-Instruct-Q4_K_M-GGUF/resolve/main/llama-3.2-1b-instruct-q4_k_m.gguf",
            750,
            "Q4_K_M",
            131072,
            2048,
            4
        ));
        list.add(new ModelPresetInfo(
            "llama3.2-3b-instruct-q4_k_m",
            "Llama-3.2-3B-Instruct",
            "Meta Llama 3.2 3B 模型，Vision能力，多模态支持",
            "https://hf-mirror.com/hugging-quants/Llama-3.2-3B-Instruct-Q4_K_M-GGUF/resolve/main/llama-3.2-3b-instruct-q4_k_m.gguf",
            2100,
            "Q4_K_M",
            131072,
            4096,
            8
        ));
        list.add(new ModelPresetInfo(
            "llama3.1-8b-instruct-q4_k_m",
            "Llama-3.1-8B-Instruct (Q4)",
            "Meta Llama 3.1 8B 指令微调版，强大的通用能力",
            "https://hf-mirror.com/hugging-quants/Llama-3.1-8B-Instruct-Q4_K_M-GGUF/resolve/main/llama-3.1-8b-instruct-q4_k_m.gguf",
            5500,
            "Q4_K_M",
            131072,
            8192,
            16
        ));
        list.add(new ModelPresetInfo(
            "gemma-2-2b-it-q4_k_m",
            "Gemma-2-2B-IT",
            "Google Gemma 2 2B 指令版本，开源友好许可",
            "https://hf-mirror.com/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
            1500,
            "Q4_K_M",
            8192,
            4096,
            6
        ));
        list.add(new ModelPresetInfo(
            "gemma-2-9b-it-q4_k_m",
            "Gemma-2-9B-IT (Q4)",
            "Google Gemma 2 9B 指令版本，强大的推理能力",
            "https://hf-mirror.com/bartowski/gemma-2-9b-it-GGUF/resolve/main/gemma-2-9b-it-Q4_K_M.gguf",
            5800,
            "Q4_K_M",
            8192,
            8192,
            18
        ));
        list.add(new ModelPresetInfo(
            "mistral-nemo-instruct-2407-12b-q4_k_m",
            "Mistral-Nemo-Instruct-2407-12B (Q4)",
            "Mistral Nemo 12B 2024版，出色的性能和速度",
            "https://hf-mirror.com/hugging-quants/Mistral-Nemo-Instruct-2407-12B-Q4_K_M-GGUF/resolve/main/mistral-nemo-instruct-2407-12b-q4_k_m.gguf",
            8000,
            "Q4_K_M",
            131072,
            12288,
            24
        ));
        list.add(new ModelPresetInfo(
            "phi-2-q4_k_m",
            "Phi-2",
            "微软2.7B小模型，代码和推理能力强，适合移动端",
            "https://hf-mirror.com/Microsoft/phi-2-GGUF/resolve/main/phi-2.Q4_K_M.gguf",
            1700,
            "Q4_K_M",
            2048,
            4096,
            8
        ));
        list.add(new ModelPresetInfo(
            "phi-3-mini-4k-instruct-q4_k_m",
            "Phi-3-Mini-4K-Instruct",
            "微软Phi-3 3.8B 最新版，性能超越Phi-2",
            "https://hf-mirror.com/Microsoft/Phi-3-mini-4k-instruct-GGUF/resolve/main/phi-3-mini-4k-instruct-q4_k_m.gguf",
            2400,
            "Q4_K_M",
            4096,
            4096,
            10
        ));
        list.add(new ModelPresetInfo(
            "phi-3.5-mini-instruct-q4_k_m",
            "Phi-3.5-Mini-Instruct",
            "微软Phi-3.5 Mini 最新版，更好的代码和推理能力",
            "https://hf-mirror.com/microsoft/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf",
            2400,
            "Q4_K_M",
            131072,
            4096,
            10
        ));
        list.add(new ModelPresetInfo(
            "deepseek-v2-lite-chat-q4_k_m",
            "DeepSeek-V2-Lite-Chat",
            "深度求索V2 Lite中文模型，出色的中文对话能力",
            "https://hf-mirror.com/deepseek-ai/DeepSeek-V2-Lite-Chat-GGUF/resolve/main/deepseek-v2-lite-chat-q4_k_m.gguf",
            8500,
            "Q4_K_M",
            128000,
            12288,
            28
        ));
        list.add(new ModelPresetInfo(
            "yi-1.5-6b-chat-q4_k_m",
            "Yi-1.5-6B-Chat (Q4)",
            "零一万物 Yi 1.5 6B 中文模型，出色的中文理解",
            "https://hf-mirror.com/01-ai/Yi-1.5-6B-Chat-GGUF/resolve/main/yi-1.5-6b-chat-q4_k_m.gguf",
            4200,
            "Q4_K_M",
            8192,
            8192,
            16
        ));
        list.add(new ModelPresetInfo(
            "baichuan2-7b-chat-q4_k_m",
            "Baichuan2-7B-Chat (Q4)",
            "百川2 7B 中文对话模型，出色的中文能力",
            "https://hf-mirror.com/baichuan-inc/Baichuan2-7B-Chat-GGUF/resolve/main/baichuan2-7b-chat-q4_k_m.gguf",
            4500,
            "Q4_K_M",
            4096,
            8192,
            16
        ));
        list.add(new ModelPresetInfo(
            "internlm2-chat-7b-q4_k_m",
            "InternLM2-Chat-7B (Q4)",
            "书生·浦语 InternLM2 7B 中文模型",
            "https://hf-mirror.com/internlm/internlm2-chat-7b-gguf/resolve/main/internlm2-chat-7b-q4_k_m.gguf",
            4800,
            "Q4_K_M",
            32768,
            8192,
            16
        ));
        list.add(new ModelPresetInfo(
            "chatglm3-6b-q4_k_m",
            "ChatGLM3-6B (Q4)",
            "智谱AI ChatGLM3 6B 中文对话模型",
            "https://hf-mirror.com/THUDM/chatglm3-6b-gguf/resolve/main/chatglm3-ggml-q4_0.bin",
            3800,
            "Q4_0",
            8192,
            6144,
            14
        ));
        list.add(new ModelPresetInfo(
            "openchat-3.5-0106-q4_k_m",
            "OpenChat-3.5-0106",
            "基于Mistral的开源聊天模型，性能出色",
            "https://hf-mirror.com/TheBloke/openchat-3.5-0106-GGUF/resolve/main/openchat-3.5-0106.Q4_K_M.gguf",
            7200,
            "Q4_K_M",
            8192,
            8192,
            16
        ));
        list.add(new ModelPresetInfo(
            "mixtral-8x7b-instruct-v0.1-q4_k_m",
            "Mixtral-8x7B-Instruct-v0.1 (Q4)",
            "Mistral MoE 8x7B 专家混合模型，高性能",
            "https://hf-mirror.com/hugging-quants/Mixtral-8x7B-Instruct-v0.1-Q4_K_M-GGUF/resolve/main/mixtral-8x7b-instruct-v0.1-q4_k_m.gguf",
            26000,
            "Q4_K_M",
            32768,
            16384,
            32
        ));
        list.add(new ModelPresetInfo(
            "tinyllama-1.1b-chat-v1.0-q4_k_m",
            "TinyLlama-1.1B-Chat-v1.0",
            "TinyLlama 1.1B 超轻量级模型，速度极快",
            "https://hf-mirror.com/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf",
            700,
            "Q4_K_M",
            2048,
            1024,
            3
        ));
        list.add(new ModelPresetInfo(
            "stablelm-zephyr-3b-q4_k_m",
            "StableLM-Zephyr-3B",
            "StabilityAI 3B Zephyr模型，对话能力强",
            "https://hf-mirror.com/TheBloke/stablelm-zephyr-3b-GGUF/resolve/main/stablelm-zephyr-3b.Q4_K_M.gguf",
            1900,
            "Q4_K_M",
            4096,
            4096,
            8
        ));
        list.add(new ModelPresetInfo(
            "zephyr-7b-beta-q4_k_m",
            "Zephyr-7B-Beta (Q4)",
            "基于Mistral的Zephyr 7B，出色的对话能力",
            "https://hf-mirror.com/TheBloke/zephyr-7B-beta-GGUF/resolve/main/zephyr-7b-beta.Q4_K_M.gguf",
            5100,
            "Q4_K_M",
            4096,
            8192,
            16
        ));
        
        return list;
    }
    
    public java.util.List<ModelPresetInfo> getPresetModelsByCategory(ModelCategory category) {
        java.util.List<ModelPresetInfo> all = getPresetDomesticModels();
        java.util.List<ModelPresetInfo> filtered = new java.util.ArrayList<>();
        
        for (ModelPresetInfo preset : all) {
            boolean matches = false;
            switch (category) {
                case CHINESE:
                    matches = preset.description.contains("中文") || 
                              preset.name.contains("Qwen") ||
                              preset.name.contains("Baichuan") ||
                              preset.name.contains("Yi") ||
                              preset.name.contains("InternLM") ||
                              preset.name.contains("ChatGLM") ||
                              preset.name.contains("DeepSeek");
                    break;
                case CODE:
                    matches = preset.name.contains("Coder") ||
                              preset.description.contains("代码");
                    break;
                case LIGHTWEIGHT:
                    matches = preset.sizeMB <= 1000;
                    break;
                case PERFORMANCE:
                    matches = preset.sizeMB > 2000;
                    break;
                case ALL:
                default:
                    matches = true;
                    break;
            }
            if (matches) {
                filtered.add(preset);
            }
        }
        return filtered;
    }
    
    public enum ModelCategory {
        ALL,
        CHINESE,
        CODE,
        LIGHTWEIGHT,
        PERFORMANCE
    }

    public static class ModelPresetInfo {
        public final String id;
        public final String name;
        public final String description;
        public final String downloadUrl;
        public final long sizeMB;
        public final String quantization;
        public final int contextLength;
        public final long minRamMB;
        public final int recommendedGpuLayers;

        public ModelPresetInfo(String id, String name, String description, String downloadUrl,
                               long sizeMB, String quantization, int contextLength,
                               long minRamMB, int recommendedGpuLayers) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.downloadUrl = downloadUrl;
            this.sizeMB = sizeMB;
            this.quantization = quantization;
            this.contextLength = contextLength;
            this.minRamMB = minRamMB;
            this.recommendedGpuLayers = recommendedGpuLayers;
        }
    }

    public void setGlobalCallback(DownloadCallback callback) {
        this.globalCallback = callback;
    }

    public String download(DownloadRequest request, DownloadCallback callback) {
        String taskId = request.modelId;

        String originalUrl = request.modelUrl;
        String convertedUrl = convertToDomesticMirror(originalUrl);

        if (!originalUrl.equals(convertedUrl)) {
            android.util.Log.i(TAG, "Using domestic mirror URL for download");
        }

        DownloadRequest mirrorRequest = new DownloadRequest(
            request.modelId,
            convertedUrl,
            request.modelPath,
            request.expectedSize,
            request.checksum,
            request.priority
        );

        if (activeDownloads.get() >= maxConcurrentDownloads) {
            Log.w(TAG, "Max concurrent downloads reached, queuing: " + taskId);
        }

        DownloadTask task = new DownloadTask(taskId, mirrorRequest, callback != null ? callback : globalCallback);
        downloadTasks.put(taskId, task);

        executor.execute(task);
        activeDownloads.incrementAndGet();

        return taskId;
    }

    public String downloadPresetModel(String modelId, ModelPresetInfo presetInfo, DownloadCallback callback) {
        String modelDir = new File(context.getFilesDir(), "ai_models").getAbsolutePath();
        String modelPath = modelDir + File.separator + getFileNameFromUrl(presetInfo.downloadUrl);

        DownloadRequest request = new DownloadRequest(
            modelId,
            presetInfo.downloadUrl,
            modelPath,
            presetInfo.sizeMB * 1024 * 1024,
            null
        );

        return download(request, callback);
    }

    public String downloadFromCustomUrl(String modelId, String url, DownloadCallback callback) {
        String modelDir = new File(context.getFilesDir(), "ai_models").getAbsolutePath();
        String modelPath = modelDir + File.separator + getFileNameFromUrl(url);

        DownloadRequest request = new DownloadRequest(
            modelId,
            url,
            modelPath,
            0,
            null
        );

        return download(request, callback);
    }

    private String getFileNameFromUrl(String url) {
        if (url == null || url.isEmpty()) {
            return "model.gguf";
        }
        String decodedUrl = java.net.URLDecoder.decode(url, java.nio.charset.StandardCharsets.UTF_8);
        int lastSlash = decodedUrl.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < decodedUrl.length() - 1) {
            String fileName = decodedUrl.substring(lastSlash + 1);
            int queryIndex = fileName.indexOf('?');
            if (queryIndex > 0) {
                fileName = fileName.substring(0, queryIndex);
            }
            return fileName;
        }
        return "model.gguf";
    }

    public void pause(String modelId) {
        DownloadTask task = downloadTasks.get(modelId);
        if (task != null) {
            task.pause();
            DownloadProgress progress = downloadProgress.get(modelId);
            if (progress != null) {
                progress.state = DownloadState.PAUSED;
            }
            if (globalCallback != null) {
                globalCallback.onPaused(modelId);
            }
        }
    }

    public void cancel(String modelId) {
        DownloadTask task = downloadTasks.get(modelId);
        if (task != null) {
            task.cancel();
            downloadTasks.remove(modelId);
            downloadProgress.remove(modelId);
            if (globalCallback != null) {
                globalCallback.onCancelled(modelId);
            }
        }
    }

    public void cancelAll() {
        for (String taskId : downloadTasks.keySet()) {
            cancel(taskId);
        }
    }

    public DownloadProgress getProgress(String modelId) {
        return downloadProgress.get(modelId);
    }

    public boolean isDownloading(String modelId) {
        DownloadProgress progress = downloadProgress.get(modelId);
        return progress != null && progress.state == DownloadState.DOWNLOADING;
    }

    public boolean isModelDownloaded(String modelPath) {
        File file = new File(modelPath);
        return file.exists() && file.length() > 0;
    }

    public int getActiveDownloadCount() {
        return activeDownloads.get();
    }

    public int getQueuedDownloadCount() {
        return downloadTasks.size() - activeDownloads.get();
    }

    public void cleanup() {
        executor.shutdown();
    }

    private class DownloadTask implements Runnable {
        private final String taskId;
        private final DownloadRequest request;
        private final DownloadCallback callback;
        private volatile boolean isPaused = false;
        private volatile boolean isCancelled = false;

        DownloadTask(String taskId, DownloadRequest request, DownloadCallback callback) {
            this.taskId = taskId;
            this.request = request;
            this.callback = callback;
        }

        void pause() {
            isPaused = true;
        }

        void cancel() {
            isCancelled = true;
        }

        @Override
        public void run() {
            DownloadProgress progress = new DownloadProgress(taskId);
            progress.totalBytes = request.expectedSize;
            progress.state = DownloadState.CONNECTING;
            downloadProgress.put(taskId, progress);

            Log.i(TAG, "Starting download: " + taskId + " from " + request.modelUrl);

            int attempt = 0;
            Exception lastError = null;

            while (attempt < MAX_RETRY_ATTEMPTS) {
                try {
                    String result = downloadFile(request, progress);

                    if (result != null) {
                        if (request.checksum != null) {
                            if (!verifyChecksum(result, request.checksum)) {
                                new File(result).delete();
                                throw new IOException("Checksum verification failed");
                            }
                        }

                        progress.state = DownloadState.COMPLETED;
                        Log.i(TAG, "Download completed: " + taskId);

                        if (callback != null) {
                            callback.onComplete(taskId, result);
                        }
                        if (globalCallback != null) {
                            globalCallback.onComplete(taskId, result);
                        }
                        return;
                    } else {
                        throw new IOException("Download returned null");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    lastError = e;
                    attempt++;
                    Log.w(TAG, "Download attempt " + attempt + " failed: " + taskId, e);

                    if (attempt < MAX_RETRY_ATTEMPTS) {
                        try {
                            Thread.sleep(1000L * attempt);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }

            progress.state = DownloadState.FAILED;
            progress.errorMessage = lastError != null ? lastError.getMessage() : "Download failed";

            if (callback != null) {
                callback.onError(taskId, progress.errorMessage);
            }
            if (globalCallback != null) {
                globalCallback.onError(taskId, progress.errorMessage);
            }
        }

        private String downloadFile(DownloadRequest request, DownloadProgress progress) throws Exception {
            HttpURLConnection connection = null;
            InputStream inputStream = null;
            OutputStream outputStream = null;
            File outputFile = null;

            try {
                URL url = new URL(request.modelUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept-Encoding", "identity");

                int responseCode = connection.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw new IOException("HTTP error: " + responseCode);
                }

                long totalBytes = request.expectedSize > 0 ? request.expectedSize : connection.getContentLengthLong();
                progress.totalBytes = totalBytes;
                progress.state = DownloadState.DOWNLOADING;

                outputFile = new File(request.modelPath);
                outputFile.getParentFile().mkdirs();

                inputStream = new BufferedInputStream(connection.getInputStream(), BUFFER_SIZE);
                outputStream = new FileOutputStream(outputFile);

                byte[] buffer = new byte[BUFFER_SIZE];
                long totalBytesRead = 0;
                long lastUpdateTime = System.currentTimeMillis();
                long bytesSinceLastUpdate = 0;

                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    if (isCancelled) {
                        outputFile.delete();
                        throw new InterruptedException("Download cancelled");
                    }

                    while (isPaused) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            throw e;
                        }
                    }

                    outputStream.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;
                    bytesSinceLastUpdate += bytesRead;

                    progress.downloadedBytes = totalBytesRead;

                    long currentTime = System.currentTimeMillis();
                    long elapsedSinceLastUpdate = currentTime - lastUpdateTime;

                    if (elapsedSinceLastUpdate >= 500) {
                        long speed = (bytesSinceLastUpdate * 1000L) / elapsedSinceLastUpdate;
                        progress.speedBps = speed;
                        progress.lastUpdateTime = currentTime;

                        bytesSinceLastUpdate = 0;
                        lastUpdateTime = currentTime;

                        long downloadedMB = totalBytesRead / (1024 * 1024);
                        long totalMB = totalBytes > 0 ? totalBytes / (1024 * 1024) : 0;

                        if (callback != null) {
                            callback.onProgress(taskId, progress.getProgressPercent(), downloadedMB, totalMB);
                        }
                    }
                }

                outputStream.flush();
                return outputFile.getAbsolutePath();

            } catch (Exception e) {
                if (outputFile != null) {
                    outputFile.delete();
                }
                throw e;
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
                if (inputStream != null) {
                    try {
                        inputStream.close();
                    } catch (IOException e) {
                    }
                }
                if (outputStream != null) {
                    try {
                        outputStream.close();
                    } catch (IOException e) {
                    }
                }
                activeDownloads.decrementAndGet();
                downloadTasks.remove(taskId);
            }
        }

        private boolean verifyChecksum(String filePath, String expectedChecksum) {
            try {
                File file = new File(filePath);
                String checksum = calculateSHA256(file);
                boolean isValid = checksum.equalsIgnoreCase(expectedChecksum);
                if (!isValid) {
                    Log.w(TAG, "Checksum mismatch: expected=" + expectedChecksum + ", actual=" + checksum);
                }
                return isValid;
            } catch (Exception e) {
                Log.e(TAG, "Failed to verify checksum", e);
                return false;
            }
        }

        private String calculateSHA256(File file) throws Exception {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            FileInputStream fis = new FileInputStream(file);
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;

            try {
                while ((bytesRead = fis.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            } finally {
                fis.close();
            }

            byte[] hashBytes = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        }
    }

    public interface DownloadCallback {
        void onProgress(String modelId, int progress, long downloadedMB, long totalMB);
        void onComplete(String modelId, String filePath);
        void onError(String modelId, String error);
        void onPaused(String modelId);
        void onCancelled(String modelId);
    }

    public static class DownloadProgress {
        public final String modelId;
        public long totalBytes;
        public long downloadedBytes;
        public DownloadState state;
        public String errorMessage;
        public long speedBps;
        public long lastUpdateTime;

        DownloadProgress(String modelId) {
            this.modelId = modelId;
            this.state = DownloadState.IDLE;
            this.lastUpdateTime = System.currentTimeMillis();
        }

        public int getProgressPercent() {
            return totalBytes > 0 ? (int) ((downloadedBytes * 100) / totalBytes) : 0;
        }

        public long getEstimatedTimeRemainingSeconds() {
            return speedBps > 0 ? (totalBytes - downloadedBytes) / speedBps : -1;
        }
    }

    public enum DownloadState {
        IDLE, CONNECTING, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED
    }

    public static class DownloadRequest {
        public final String modelId;
        public final String modelUrl;
        public final String modelPath;
        public final long expectedSize;
        public final String checksum;
        public final DownloadPriority priority;

        public DownloadRequest(String modelId, String modelUrl, String modelPath, long expectedSize, String checksum) {
            this(modelId, modelUrl, modelPath, expectedSize, checksum, DownloadPriority.NORMAL);
        }

        public DownloadRequest(String modelId, String modelUrl, String modelPath, long expectedSize, String checksum, DownloadPriority priority) {
            this.modelId = modelId;
            this.modelUrl = modelUrl;
            this.modelPath = modelPath;
            this.expectedSize = expectedSize;
            this.checksum = checksum;
            this.priority = priority;
        }
    }

    public enum DownloadPriority {
        LOW, NORMAL, HIGH
    }

    public static class ModelDownloadConfig {
        public boolean allowBackgroundDownload = true;
        public long downloadTimeoutSeconds = 3600;
        public int retryAttempts = 3;
        public boolean verifyChecksum = true;
        public int maxConcurrentDownloads = 2;

        public static ModelDownloadConfig DEFAULT = new ModelDownloadConfig();
    }
}