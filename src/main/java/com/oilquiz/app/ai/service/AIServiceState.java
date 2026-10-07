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
    /** 进入当前阶段的时刻（用于 {@link #getStageLabel()} 的实时秒数），与 NPU 侧同一套做法 */
    private volatile long stageStartedAt = 0;
    
    private final Object lock = new Object();
    
    public ServiceStage getCurrentStage() {
        return currentStage;
    }
    
    public void setCurrentStage(ServiceStage stage, String message) {
        synchronized (lock) {
            this.currentStage = stage;
            this.stageMessage = message != null ? message : getDefaultMessageForStage(stage);
            this.progressPercent = getDefaultProgressForStage(stage);
            this.stageStartedAt = System.currentTimeMillis();
        }
    }
    
    public void setCurrentStage(ServiceStage stage, String message, int progress) {
        synchronized (lock) {
            this.currentStage = stage;
            this.stageMessage = message != null ? message : getDefaultMessageForStage(stage);
            this.progressPercent = progress;
            this.stageStartedAt = System.currentTimeMillis();
        }
    }

    /**
     * 状态栏文案：**短句 + 实时秒数**，如「模型加载中 6s」「本地推理就绪」。
     *
     * <p>与 NPU 侧（{@code NpuEngineState.getStageLabel()}）保持一致的做法与理由：
     * {@code progressPercent} 是各阶段入口写死的常量（5/15/40/70/90…），**不是测量值**，
     * 把它显示成百分比等于把常量伪装成进度。故文案只用"在做什么"+ 已耗时（调用方每秒刷新，
     * 秒数会走）。百分比仍保留在字段里，仅供进度条这类"未完成/完成"两态表达使用。</p>
     */
    public String getStageLabel() {
        String doing;
        switch (currentStage) {
            case NATIVE_LIBRARY_LOADING:
                doing = "引擎启动中";
                break;
            case MODEL_FILE_PREPARING:
                doing = "准备模型中";
                break;
            case MODEL_LOADING:
                doing = "模型加载中";
                break;
            case GPU_INITIALIZATION:
                doing = "初始化 GPU";
                break;
            case CPU_FALLBACK:
                doing = "改用 CPU 推理";
                break;
            case CHAT_CONTEXT_CREATING:
                doing = "准备上下文中";
                break;
            case INITIALIZED:
                return "本地推理就绪";
            case ERROR:
                return "AI 不可用";
            default:
                return "AI 待加载";
        }
        long startedAt = stageStartedAt;
        if (startedAt > 0) {
            long sec = (System.currentTimeMillis() - startedAt) / 1000L;
            if (sec >= 1) {
                return doing + " " + sec + "s";
            }
        }
        return doing;
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
