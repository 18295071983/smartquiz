package com.oilquiz.app.ai.service;

public class AIServiceState {
    
    public enum ServiceStage {
        UNINITIALIZED,
        NATIVE_LIBRARY_LOADING,
        MODEL_FILE_PREPARING,
        MODEL_LOADING,
        GPU_INITIALIZATION,
        CPU_FALLBACK,
        CHAT_CONTEXT_CREATING,
        INITIALIZED,
        ERROR
    }
    
    private volatile ServiceStage currentStage = ServiceStage.UNINITIALIZED;
    private volatile String stageMessage = "";
    private volatile int progressPercent = 0;
    private volatile long startTime = 0;
    private volatile String currentModelName = null;
    private volatile String errorMessage = null;
    private volatile long estimatedTimeMs = 0;
    
    private final Object lock = new Object();
    
    public ServiceStage getCurrentStage() {
        return currentStage;
    }
    
    public void setCurrentStage(ServiceStage stage, String message) {
        synchronized (lock) {
            this.currentStage = stage;
            this.stageMessage = message != null ? message : getDefaultMessageForStage(stage);
            this.progressPercent = getDefaultProgressForStage(stage);
        }
    }
    
    public void setCurrentStage(ServiceStage stage, String message, int progress) {
        synchronized (lock) {
            this.currentStage = stage;
            this.stageMessage = message != null ? message : getDefaultMessageForStage(stage);
            this.progressPercent = progress;
        }
    }
    
    public String getStageMessage() {
        return stageMessage;
    }
    
    public int getProgressPercent() {
        return progressPercent;
    }
    
    public void setProgressPercent(int progress) {
        synchronized (lock) {
            this.progressPercent = Math.max(0, Math.min(100, progress));
        }
    }
    
    public long getElapsedTimeMs() {
        if (startTime == 0) return 0;
        return System.currentTimeMillis() - startTime;
    }
    
    public void startTiming() {
        this.startTime = System.currentTimeMillis();
    }
    
    public long getStartTime() {
        return startTime;
    }
    
    public void setCurrentModelName(String modelName) {
        this.currentModelName = modelName;
    }
    
    public String getCurrentModelName() {
        return currentModelName;
    }
    
    public void setError(String errorMessage) {
        this.currentStage = ServiceStage.ERROR;
        this.errorMessage = errorMessage;
    }
    
    public String getErrorMessage() {
        return errorMessage;
    }
    
    public boolean isError() {
        return currentStage == ServiceStage.ERROR;
    }
    
    public boolean isInitialized() {
        return currentStage == ServiceStage.INITIALIZED;
    }
    
    public boolean isLoading() {
        return currentStage != ServiceStage.UNINITIALIZED 
                && currentStage != ServiceStage.INITIALIZED 
                && currentStage != ServiceStage.ERROR;
    }
    
    public long getEstimatedTimeMs() {
        return estimatedTimeMs;
    }
    
    public void setEstimatedTimeMs(long estimatedTimeMs) {
        this.estimatedTimeMs = estimatedTimeMs;
    }
    
    public String getStageDescription() {
        return stageMessage;
    }
    
    private String getDefaultMessageForStage(ServiceStage stage) {
        switch (stage) {
            case UNINITIALIZED: return "AI服务未初始化";
            case NATIVE_LIBRARY_LOADING: return "加载原生库...";
            case MODEL_FILE_PREPARING: return "准备模型文件...";
            case MODEL_LOADING: return "加载模型...";
            case GPU_INITIALIZATION: return "初始化GPU加速...";
            case CPU_FALLBACK: return "GPU初始化失败，切换到CPU模式...";
            case CHAT_CONTEXT_CREATING: return "创建对话上下文...";
            case INITIALIZED: return "AI服务已就绪";
            case ERROR: return "初始化失败";
            default: return "未知状态";
        }
    }
    
    private int getDefaultProgressForStage(ServiceStage stage) {
        switch (stage) {
            case UNINITIALIZED: return 0;
            case NATIVE_LIBRARY_LOADING: return 5;
            case MODEL_FILE_PREPARING: return 15;
            case MODEL_LOADING: return 40;
            case GPU_INITIALIZATION: return 70;
            case CPU_FALLBACK: return 75;
            case CHAT_CONTEXT_CREATING: return 90;
            case INITIALIZED: return 100;
            case ERROR: return 0;
            default: return 0;
        }
    }
    
    @Override
    public String toString() {
        return "AIServiceState{" +
                "stage=" + currentStage +
                ", progress=" + progressPercent + "%" +
                ", message='" + stageMessage + '\'' +
                ", elapsed=" + getElapsedTimeMs() + "ms" +
                '}';
    }
}
