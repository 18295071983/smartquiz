package com.oilquiz.app.ai.model;

import android.content.Context;
import android.util.Log;
import com.oilquiz.app.ai.gpu.GpuDatabase;
import com.oilquiz.app.ai.gpu.GpuProfile;
import com.oilquiz.app.ai.gpu.GpuCapabilityDetector;
import com.oilquiz.app.ai.service.AIService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

public class MultiModelManager {
    private static final String TAG = "MultiModelManager";
    private static final int MAX_LOADED_MODELS = 2;
    private static final long DEFAULT_TIMEOUT = 30000L;

    @SuppressWarnings("VolatileArrayField")
    private static volatile MultiModelManager instance;

    private final Context context;
    private final ConcurrentHashMap<String, ModelInfo> modelMap;
    private final ConcurrentHashMap<String, ModelInstance> loadedModels;
    private final AtomicReference<String> activeModelId;
    private final ConcurrentHashMap<String, ReentrantLock> modelLocks;
    private ModelConfig defaultConfig;
    private boolean isInitialized = false;

    private final GpuDatabase gpuDatabase;
    private final GpuCapabilityDetector gpuDetector;
    private GpuProfile currentGpuProfile;

    private MultiModelManager(Context context) {
        this.context = context.getApplicationContext();
        this.modelMap = new ConcurrentHashMap<>();
        this.loadedModels = new ConcurrentHashMap<>();
        this.activeModelId = new AtomicReference<>(null);
        this.modelLocks = new ConcurrentHashMap<>();
        this.gpuDatabase = GpuDatabase.getInstance();
        this.gpuDetector = new GpuCapabilityDetector(context);
        detectGpuProfile();
        initDefaultModels();
    }

    private void detectGpuProfile() {
        currentGpuProfile = gpuDetector.getGpuProfile();
        if (currentGpuProfile != null) {
            Log.i(TAG, "Detected GPU: " + currentGpuProfile.getGpuName() +
                      " (" + currentGpuProfile.getSeries() + ", " +
                      currentGpuProfile.getArchitecture() + ")");
        } else {
            Log.w(TAG, "Unknown GPU, using conservative settings");
        }
    }

    public static void initialize(Context context) {
        if (instance == null) {
            synchronized (MultiModelManager.class) {
                if (instance == null) {
                    instance = new MultiModelManager(context);
                    instance.isInitialized = true;
                    Log.i(TAG, "MultiModelManager initialized");
                }
            }
        }
    }

    public static MultiModelManager getInstance(Context context) {
        if (instance == null) {
            initialize(context);
        }
        return instance;
    }

    private void initDefaultModels() {
        ModelInfo model1 = new ModelInfo();
        model1.id = "qwen2.5-7b-q4_k_m";
        model1.name = "Qwen 2.5 7B";
        model1.description = "Q4_K_M";
        model1.sizeMB = 4800;
        model1.contextLength = 32000;
        model1.quantization = "Q4_K_M";
        model1.recommendedGpuLayers = getRecommendedGpuLayers(4800);
        model1.minRamMB = getMinRamMB(4800);
        addModel(model1);

        ModelInfo model2 = new ModelInfo();
        model2.id = "qwen2.5-3b-q4_k_m";
        model2.name = "Qwen 2.5 3B";
        model2.description = "Q4_K_M";
        model2.sizeMB = 2500;
        model2.contextLength = 32000;
        model2.quantization = "Q4_K_M";
        model2.recommendedGpuLayers = getRecommendedGpuLayers(2500);
        model2.minRamMB = getMinRamMB(2500);
        addModel(model2);

        ModelInfo model3 = new ModelInfo();
        model3.id = "llama3-8b-instruct-q4_k_m";
        model3.name = "Llama 3 8B";
        model3.description = "Q4_K_M";
        model3.sizeMB = 5000;
        model3.contextLength = 128000;
        model3.quantization = "Q4_K_M";
        model3.recommendedGpuLayers = getRecommendedGpuLayers(5000);
        model3.minRamMB = getMinRamMB(5000);
        addModel(model3);

        ModelInfo model4 = new ModelInfo();
        model4.id = "gemma-2-9b-it-q4_k_m";
        model4.name = "Gemma 2 9B";
        model4.description = "Q4_K_M";
        model4.sizeMB = 5600;
        model4.contextLength = 256000;
        model4.quantization = "Q4_K_M";
        model4.recommendedGpuLayers = getRecommendedGpuLayers(5600);
        model4.minRamMB = getMinRamMB(5600);
        addModel(model4);

        Log.i(TAG, "Default models initialized: " + modelMap.size() + " models");
    }

    private int getRecommendedGpuLayers(long modelSizeMB) {
        if (currentGpuProfile != null) {
            return gpuDatabase.getRecommendedGpuLayers(currentGpuProfile, modelSizeMB);
        }
        return Math.min(10, (int)(modelSizeMB / 500));
    }

    private int getMinRamMB(long modelSizeMB) {
        if (currentGpuProfile != null) {
            var config = gpuDatabase.getConfigForModel(currentGpuProfile, modelSizeMB);
            return (int)(modelSizeMB * 1.5 + config.getBatchSize() * 10);
        }
        return (int)(modelSizeMB * 1.8);
    }

    public void addModel(ModelInfo modelInfo) {
        if (currentGpuProfile != null) {
            modelInfo.recommendedGpuLayers = gpuDatabase.getRecommendedGpuLayers(currentGpuProfile, modelInfo.sizeMB);
            modelInfo.minRamMB = getMinRamMB(modelInfo.sizeMB);
        }
        modelMap.put(modelInfo.id, modelInfo);
        Log.i(TAG, "Model added: " + modelInfo.id +
                  " (GPU layers: " + modelInfo.recommendedGpuLayers +
                  ", Min RAM: " + modelInfo.minRamMB + "MB)");
    }

    public void removeModel(String modelId) {
        modelMap.remove(modelId);
        unloadModel(modelId);
        Log.i(TAG, "Model removed: " + modelId);
    }

    public ModelInfo getModel(String modelId) {
        return modelMap.get(modelId);
    }

    public ConcurrentHashMap<String, ModelInfo> getAllModels() {
        return modelMap;
    }

    public void setDefaultConfig(ModelConfig config) {
        this.defaultConfig = config;
        Log.i(TAG, "Default config set: " + config.modelId);
    }

    public ModelConfig getDefaultConfig() {
        if (defaultConfig == null) {
            defaultConfig = new ModelConfig();
            defaultConfig.modelId = "qwen2.5-3b-q4_k_m";
            defaultConfig.inferenceParams = new ModelConfig.InferenceParams();
            defaultConfig.generationParams = new ModelConfig.GenerationParams();
        }
        return defaultConfig;
    }

    public boolean isInitialized() {
        return isInitialized;
    }

    public void shutdown() {
        try {
            for (String modelId : new ArrayList<>(loadedModels.keySet())) {
                unloadModel(modelId);
            }
        } catch (ConcurrentModificationException e) {
            Log.e(TAG, "Error during shutdown: " + e.getMessage());
        }
        modelMap.clear();
        modelLocks.clear();
        activeModelId.set(null);
        instance = null;
        isInitialized = false;
        Log.i(TAG, "MultiModelManager shutdown");
    }

    public boolean switchModel(String modelId) {
        Log.i(TAG, "Switching to model: " + modelId);

        ModelInfo modelInfo = modelMap.get(modelId);
        if (modelInfo == null) {
            Log.e(TAG, "Model not found: " + modelId);
            return false;
        }

        if (loadedModels.containsKey(modelId)) {
            ModelInstance inst = loadedModels.get(modelId);
            if (inst != null) {
                inst.lastAccessTime.set(System.currentTimeMillis());
                activeModelId.set(modelId);
                Log.i(TAG, "Switched to already loaded model: " + modelId);
                return true;
            }
        }

        long availableMemory = getAvailableMemoryMB();
        if (availableMemory < modelInfo.minRamMB) {
            Log.e(TAG, "Insufficient memory: required " + modelInfo.minRamMB + "MB, available " + availableMemory + "MB");
            return false;
        }

        if (!ensureCapacity(modelInfo)) {
            Log.e(TAG, "Cannot load more models, all " + MAX_LOADED_MODELS + " are in use");
            return false;
        }

        AIService aiService = AIService.getInstance(context);
        if (aiService != null) {
            String modelFileName = modelId + ".gguf";
            boolean switched = aiService.switchModelSafe(modelFileName);
            if (!switched) {
                Log.e(TAG, "AIService failed to switch to model: " + modelFileName);
                return false;
            }
        } else {
            Log.w(TAG, "AIService not available, model switch only tracked in MultiModelManager");
        }

        Log.i(TAG, "Loading model: " + modelId);
        ModelInstance newInstance = new ModelInstance(modelId, modelInfo);
        loadedModels.put(modelId, newInstance);
        activeModelId.set(modelId);

        Log.i(TAG, "Successfully loaded and switched to model: " + modelId +
                  " (memory: " + availableMemory + "MB, loaded: " + loadedModels.size() + "/" + MAX_LOADED_MODELS + ")" +
                  " (GPU layers: " + modelInfo.recommendedGpuLayers + ")");
        return true;
    }

    private boolean ensureCapacity(ModelInfo newModelInfo) {
        while (loadedModels.size() >= MAX_LOADED_MODELS) {
            ModelInstance lruInstance = findLeastRecentlyUsedModel();
            if (lruInstance != null && !lruInstance.modelId.equals(activeModelId.get())) {
                Log.i(TAG, "Unloading LRU model: " + lruInstance.modelId);
                unloadModel(lruInstance.modelId);
            } else {
                return false;
            }
        }
        return true;
    }

    private ModelInstance findLeastRecentlyUsedModel() {
        return loadedModels.values().stream()
                .min(Comparator.comparingLong(ModelInstance::getLastAccessTime))
                .orElse(null);
    }

    public void unloadModel(String modelId) {
        ReentrantLock lock = modelLocks.get(modelId);
        if (lock != null) {
            lock.lock();
            try {
                ModelInstance instance = loadedModels.remove(modelId);
                if (instance != null) {
                    Log.i(TAG, "Model unloaded: " + modelId);
                }
                if (activeModelId.get() != null && activeModelId.get().equals(modelId)) {
                    activeModelId.set(null);
                }
            } finally {
                lock.unlock();
            }
        }
    }

    public boolean isModelLoaded(String modelId) {
        return loadedModels.containsKey(modelId);
    }

    public String getActiveModelId() {
        return activeModelId.get();
    }

    public GpuProfile getCurrentGpuProfile() {
        return currentGpuProfile;
    }

    public GpuDatabase getGpuDatabase() {
        return gpuDatabase;
    }

    public GpuCapabilityDetector getGpuDetector() {
        return gpuDetector;
    }

    public MemoryStats getMemoryStats() {
        long totalMemory = getTotalMemoryMB();
        long availableMemory = getAvailableMemoryMB();
        long usedMemory = totalMemory - availableMemory;

        long modelMemory = 0;
        for (ModelInstance instance : loadedModels.values()) {
            modelMemory += instance.modelInfo.sizeMB;
        }

        return new MemoryStats(
                totalMemory,
                availableMemory,
                usedMemory,
                modelMemory,
                loadedModels.size(),
                MAX_LOADED_MODELS
        );
    }

    private long getAvailableMemoryMB() {
        var memoryInfo = gpuDetector.getMemoryUsageInfo();
        return memoryInfo.getAvailableMemoryMB();
    }

    private long getTotalMemoryMB() {
        var memoryInfo = gpuDetector.getMemoryUsageInfo();
        return memoryInfo.getTotalMemoryMB();
    }

    public static class ModelInstance {
        public final String modelId;
        public final ModelInfo modelInfo;
        public final long loadTime;
        public final AtomicLong lastAccessTime;

        public ModelInstance(String modelId, ModelInfo modelInfo) {
            this.modelId = modelId;
            this.modelInfo = modelInfo;
            this.loadTime = System.currentTimeMillis();
            this.lastAccessTime = new AtomicLong(System.currentTimeMillis());
        }

        public long getLastAccessTime() {
            return lastAccessTime.get();
        }
    }

    public static class MemoryStats {
        public final long totalMemory;
        public final long availableMemory;
        public final long usedMemory;
        public final long modelMemory;
        public final int modelCount;
        public final int maxModels;

        public MemoryStats(long totalMemory, long availableMemory, long usedMemory, long modelMemory, int modelCount, int maxModels) {
            this.totalMemory = totalMemory;
            this.availableMemory = availableMemory;
            this.usedMemory = usedMemory;
            this.modelMemory = modelMemory;
            this.modelCount = modelCount;
            this.maxModels = maxModels;
        }

        public float getMemoryUsagePercent() {
            return totalMemory > 0 ? (float) usedMemory / totalMemory * 100 : 0;
        }

        public float getModelMemoryPercent() {
            return totalMemory > 0 ? (float) modelMemory / totalMemory * 100 : 0;
        }
    }
}