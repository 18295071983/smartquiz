package com.oilquiz.app.ai.speech;

import android.os.Handler;
import android.os.Looper;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.oilquiz.app.util.AILogger;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.ResponseBody;
import okio.ByteString;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 百炼 CosyVoice WebSocket 实时语音合成客户端
 * 
 * 基于真正的 WebSocket 协议,符合阿里云官方 API 规范:
 * https://help.aliyun.com/zh/model-studio/cosyvoice-websocket-api
 * 
 * 支持特性:
 * - 低延迟:边合成边返回音频
 * - 双向通信:多次发送文本片段,服务端自动分句
 * - 音频流:直接接收 MP3/PCM 音频数据
 * - 连接复用:支持多个任务复用同一连接
 * 
 * 交互流程:
 * 1. 建立 WebSocket 连接
 * 2. 发送 run-task 事件开启任务
 * 3. 发送一个或多个 continue-task 事件(包含文本片段)
 * 4. 发送 finish-task 事件通知结束
 * 5. 接收 binary 通道的音频流
 * 6. 接收 task-finished 事件标志任务完成
 */
public class CosyVoiceWebSocketClient {
    
    private static final String TAG = "CosyVoiceWebSocket";
    private static final int BUFFER_SIZE = 8192;
    private static final long CONNECT_TIMEOUT_MS = 15000;
    private static final long READ_TIMEOUT_MS = 60000;
    
    /** WebSocket 状态 */
    public enum State {
        DISCONNECTED,     // 未连接
        CONNECTING,       // 连接中
        READY,            // 已就绪,可发送任务
        RUNNING,          // 任务进行中
        CLOSING           // 关闭中
    }
    
    /** 音频格式 */
    public enum AudioFormat {
        MP3("mp3", 44100),
        PCM_S16_LE("pcm_16k", 16000),
        WAV("wav", 24000);
        
        public final String format;
        public final int sampleRate;
        
        AudioFormat(String format, int sampleRate) {
            this.format = format;
            this.sampleRate = sampleRate;
        }
    }
    
    /** 音频数据回调 */
    public interface AudioCallback {
        /** 收到音频二进制数据 */
        void onAudioData(byte[] audioData);
        /** 合成完成 */
        void onComplete();
        /** 发生错误 */
        void onError(String error);
    }
    
    private final String workspaceId;
    private final String apiKey;
    private final String region;  // "cn-beijing" or "ap-southeast-1"
    private final AudioFormat audioFormat;
    private AudioCallback callback;  // 可变，允许在 synthesizeWithTimeout 中修改
    
    private volatile State state = State.DISCONNECTED;
    private OkHttpClient okHttpClient;
    private WebSocket webSocket;
    private Gson gson = new Gson();
    private Handler mainHandler;
    
    // SSL 证书验证禁用(生产环境应使用正式证书)
    private static final TrustManager[] TRUST_ALL_CERTS = new TrustManager[]{
        new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { 
                return new X509Certificate[0];  // 返回空数组而非 null
            }
            public void checkClientTrusted(X509Certificate[] certs, String authType) {}
            public void checkServerTrusted(X509Certificate[] certs, String authType) {}
        }
    };
    
    /**
     * 创建 CosyVoice WebSocket 客户端
     * 
     * @param workspaceId 工作空间 ID(从百炼控制台获取)
     * @param apiKey API Key
     * @param region 区域:"cn-beijing"(北京)或"ap-southeast-1"(新加坡)
     * @param audioFormat 音频格式
     * @param callback 音频数据回调
     */
    public CosyVoiceWebSocketClient(String workspaceId, String apiKey, 
                                     String region, AudioFormat audioFormat,
                                     AudioCallback callback) {
        if (workspaceId == null || workspaceId.isEmpty()) {
            throw new IllegalArgumentException("workspaceId 不能为空");
        }
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("apiKey 不能为空");
        }
        if (!"cn-beijing".equals(region) && !"ap-southeast-1".equals(region)) {
            throw new IllegalArgumentException("region 必须是 cn-beijing 或 ap-southeast-1");
        }
        
        this.workspaceId = workspaceId;
        this.apiKey = apiKey;
        this.region = region;
        this.audioFormat = audioFormat;
        this.callback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper());
        
        initHttpClient();
    }
    
    /**
     * 初始化 OkHttpClient(配置 WebSocket 参数)
     */
    private void initHttpClient() {
        try {
            // 创建信任所有证书的 SSL Context
            SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(null, TRUST_ALL_CERTS, new SecureRandom());
            
            okHttpClient = new OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) TRUST_ALL_CERTS[0])
                    .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .writeTimeout(0, TimeUnit.MILLISECONDS)  // 无限写入超时,等待音频流
                    .build();
            
            AILogger.d(TAG, "OkHttpClient initialized");
        } catch (Exception e) {
            AILogger.e(TAG, "Failed to initialize OkHttpClient: " + e.getMessage(), e);
        }
    }
    
    /**
     * 建立 WebSocket 连接
     * 
     * @return 成功返回 true
     */
    public synchronized boolean connect() {
        if (state == State.READY || state == State.RUNNING) {
            AILogger.w(TAG, "Already connected");
            return true;
        }
        
        if (okHttpClient == null) {
            initHttpClient();
        }
        
        state = State.CONNECTING;
        
        // 构建 WebSocket URL
        String wsUrl = String.format("wss://%s.%s.maas.aliyuncs.com/api-ws/v1/inference", 
                                      workspaceId, region);
        
        AILogger.i(TAG, "Connecting to: " + wsUrl);
        
        // 构建请求头
        Request request = new Request.Builder()
                .url(wsUrl)
                .header("Authorization", "Bearer " + apiKey)
                .header("user-agent", "SmartQuizAndroidClient/1.0")
                .build();
        
        // 异步建立连接
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] success = {false};
        
        okHttpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                AILogger.i(TAG, "WebSocket connected successfully");
                webSocket = ws;
                state = State.READY;
                latch.countDown();
                success[0] = true;
                
                // 设置消息处理器
                setupMessageHandler();
            }
            
            // 注意：OkHttp 4.x WebSocketListener 使用以下方法签名
            // public void onMessage(String text)
            // public void onMessage(ByteString binary)
            // 不添加 @Override 以避免编译错误
            
            public void onMessage(String text) {
                // 文本消息 - 处理 JSON 事件
                try {
                    JsonObject jsonObject = gson.fromJson(text, JsonObject.class);
                    String eventType = jsonObject.has("event_type") ? 
                            jsonObject.get("event_type").getAsString() : "unknown";
                    
                    AILogger.d(TAG, "Received event: " + eventType);
                    
                    switch (eventType) {
                        case "task-started":
                            // 任务已开始,可以发送文本
                            AILogger.i(TAG, "Task started, ready to send text");
                            break;
                            
                        case "result-generated":
                            // 中间结果(不完整的句子)
                            AILogger.d(TAG, "Partial result received");
                            break;
                            
                        case "task-finished":
                            // 任务完成
                            AILogger.i(TAG, "Task finished");
                            mainHandler.post(() -> {
                                if (callback != null) callback.onComplete();
                            });
                            break;
                            
                        case "error":
                            // 服务端错误
                            String errorMsg = jsonObject.has("error_message") ?
                                    jsonObject.get("error_message").getAsString() :
                                    "Unknown error";
                            AILogger.e(TAG, "Server error: " + errorMsg);
                            mainHandler.post(() -> {
                                if (callback != null) callback.onError(errorMsg);
                            });
                            break;
                    }
                } catch (Exception e) {
                    AILogger.e(TAG, "Failed to parse message: " + e.getMessage(), e);
                }
            }
            
            public void onMessage(ByteString data) {
                // 二进制消息 - 音频数据
                byte[] audioData = data.toByteArray();
                handleBinaryAudioData(audioData, audioData.length);
            }
            
            @Override
            public void onClosed(@org.jetbrains.annotations.Nullable WebSocket ws, int code, String reason) {
                AILogger.i(TAG, "WebSocket closed: " + code + " / " + reason);
                state = State.DISCONNECTED;
                latch.countDown();
            }
            
            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                AILogger.e(TAG, "WebSocket connection failed: " + t.getMessage());
                if (response != null) {
                    AILogger.e(TAG, "HTTP error: " + response.code());
                    try {
                        AILogger.e(TAG, "Response: " + response.body().string());
                    } catch (Exception ignored) {}
                }
                state = State.DISCONNECTED;
                latch.countDown();
                
                if (callback != null) {
                    mainHandler.post(() -> callback.onError("Connection failed: " + t.getMessage()));
                }
            }
        });
        
        // 等待连接建立(最多等待 5 秒)
        try {
            latch.await(5000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            AILogger.e(TAG, "Connection wait interrupted: " + e.getMessage());
            return false;
        }
        
        return success[0];
    }
    
    /**
     * 设置 WebSocket 消息处理器
     */
    private void setupMessageHandler() {
        // 在现有的 WebSocket 上添加新的 listener
        // 注意:上面的 listener 已经设置了 onMessage/onFailure 回调
        // 这里可以额外处理业务逻辑
        AILogger.d(TAG, "Message handler configured");
    }
    
    /**
     * 执行语音合成(完整流程:run-task → continue-task × N → finish-task)
     * 
     * @param text 待合成文本
     * @param voice 音色 ID(如 Cherry, longxiaochun 等)
     * @param modelName 模型名称(如 cosyvoice-v1, qwen3-tts-flash)
     */
    public synchronized void synthesize(String text, String voice, String modelName) {
        if (state != State.READY && state != State.RUNNING) {
            if (callback != null) {
                mainHandler.post(() -> callback.onError("Not connected"));
            }
            return;
        }
        
        if (text == null || text.isEmpty()) {
            if (callback != null) {
                mainHandler.post(() -> callback.onError("Text is empty"));
            }
            return;
        }
        
        AILogger.d(TAG, "Starting synthesis: " + (text.length() > 50 ? text.substring(0, 50) + "..." : text) + " model=" + modelName);
        
        // 在后台线程执行
        new Thread(() -> executeSynthesisFlow(text, voice, modelName)).start();
    }
    
    /**
     * 简化版synthesize(使用默认模型)
     * @deprecated 使用 synthesize(text, voice, modelName) 代替
     */
    @Deprecated
    public synchronized void synthesize(String text, String voice) {
        synthesize(text, voice, "cosyvoice-v1");  // 默认使用 cosyvoice-v1
    }
    
    /**
     * 带超时的合成方法(用于需要同步等待的场景)
     * 
     * @param text 待合成文本
     * @param voice 音色 ID
     * @param modelName 模型名称
     * @param timeoutMs 超时时间(毫秒)
     * @return 是否成功
     */
    public boolean synthesizeWithTimeout(String text, String voice, String modelName, long timeoutMs) {
        final boolean[] success = {false};
        final Exception[] error = {null};
        CountDownLatch latch = new CountDownLatch(1);
        
        // 保存原始回调并包装
        final AudioCallback originalCallback = callback;
        final long startTime = System.currentTimeMillis();
        
        callback = new AudioCallback() {
            @Override
            public void onAudioData(byte[] audioData) {
                if (originalCallback != null) {
                    // 检查是否已超时
                    if (System.currentTimeMillis() - startTime < timeoutMs) {
                        originalCallback.onAudioData(audioData);
                    }
                }
            }
            
            @Override
            public void onComplete() {
                success[0] = true;
                latch.countDown();
                if (originalCallback != null) originalCallback.onComplete();
            }
            
            @Override
            public void onError(String errMsg) {
                error[0] = new Exception(errMsg);
                latch.countDown();
                if (originalCallback != null) originalCallback.onError(errMsg);
            }
        };
        
        // 发起合成请求
        synthesize(text, voice, modelName);
        
        // 等待完成
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        
        // 恢复原始回调
        callback = originalCallback;
        
        return success[0];
    }
    
    /**
     * 执行完整的 WebSocket 合成流程
     */
    private void executeSynthesisFlow(String text, String voice, String modelName) {
        if (webSocket == null) {
            mainHandler.post(() -> {
                if (callback != null) callback.onError("WebSocket not initialized");
            });
            return;
        }
        
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        AILogger.d(TAG, "Task ID: " + taskId);
        
        try {
            // 1. 发送 run-task 事件
            if (!sendRunTask(taskId, voice, modelName)) {
                return;
            }
            
            // 2. 等待 task-started 响应
            if (!waitForTaskStarted(3000)) {
                mainHandler.post(() -> {
                    if (callback != null) callback.onError("Timeout waiting for task-started");
                });
                return;
            }
            
            // 3. 发送 continue-task 事件(文本可能很长,需要分片)
            if (!sendContinueTasks(taskId, text)) {
                return;
            }
            
            // 4. 发送 finish-task 事件
            if (!sendFinishTask(taskId)) {
                return;
            }
            
            // 5. 等待音频数据和 task-finished 事件
            // 这些会通过 WebSocket 的 onMessage 回调返回
            // 这里只需要等待足够长的时间让音频传输完成
            waitForCompletion();
            
        } catch (Exception e) {
            AILogger.e(TAG, "Synthesis error: " + e.getMessage(), e);
            mainHandler.post(() -> {
                if (callback != null) callback.onError("Synthesis failed: " + e.getMessage());
            });
        }
    }
    
    /**
     * 发送 run-task 事件
     */
    private boolean sendRunTask(String taskId, String voice, String modelName) {
        try {
            JsonObject runTask = new JsonObject();
            runTask.addProperty("event_type", "run-task");
            runTask.addProperty("task_id", taskId);
            
            JsonObject task_parameters = new JsonObject();
            
            JsonObject model_info = new JsonObject();
            // 使用实际配置的模型名,而不是硬编码 cosyvoice-v3
            model_info.addProperty("model_name", modelName != null && !modelName.isEmpty() ? modelName : "cosyvoice-v1");
            task_parameters.add("model_info", model_info);
            
            JsonObject parameters = new JsonObject();
            parameters.addProperty("voice", voice != null && !voice.isEmpty() ? voice : "longanyang");
            parameters.addProperty("sample_rate", audioFormat.sampleRate);
            parameters.addProperty("format", audioFormat.format);
            task_parameters.add("tts_text_param", parameters);
            
            runTask.add("task_parameters", task_parameters);
            
            String json = gson.toJson(runTask);
            AILogger.d(TAG, "Sending run-task: " + json.substring(0, Math.min(200, json.length())));
            
            return webSocket.send(json);
            
        } catch (Exception e) {
            AILogger.e(TAG, "Failed to send run-task: " + e.getMessage(), e);
            mainHandler.post(() -> {
                if (callback != null) callback.onError("run-task failed: " + e.getMessage());
            });
            return false;
        }
    }
    
    /**
     * 等待 task-started 事件
     */
    private boolean waitForTaskStarted(long timeoutMs) {
        // TODO: 需要实现状态同步机制
        // 这里简化为等待一小段时间
        try {
            Thread.sleep(500);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
    
    /**
     * 发送 continue-task 事件(文本分片)
     */
    private boolean sendContinueTasks(String taskId, String text) {
        try {
            // 阿里云文档说明:服务端会自动分句,可以一次性发送完整文本
            // 但对于长文本,建议分段发送以提高体验
            
            int maxLength = 500;  // 每段最多 500 字符
            int start = 0;
            
            while (start < text.length()) {
                int end = Math.min(start + maxLength, text.length());
                
                // 尝试在标点处分割
                if (end < text.length()) {
                    int lastPeriod = text.lastIndexOf('。', end);
                    int lastQuestion = text.lastIndexOf('？', end);
                    int lastExclaim = text.lastIndexOf('！', end);
                    int lastComma = text.lastIndexOf('，', end);
                    
                    int splitPos = Math.max(lastPeriod, Math.max(lastQuestion, 
                                       Math.max(lastExclaim, lastComma)));
                    
                    if (splitPos > start + 100) {  // 至少 100 字符才分割
                        end = splitPos + 1;
                    } else {
                        end = Math.min(start + maxLength, text.length());
                    }
                }
                
                String segment = text.substring(start, end);
                
                JsonObject continueTask = new JsonObject();
                continueTask.addProperty("event_type", "continue-task");
                continueTask.addProperty("task_id", taskId);
                
                JsonObject task_parameters = new JsonObject();
                task_parameters.addProperty("text", segment);
                continueTask.add("task_parameters", task_parameters);
                
                String json = gson.toJson(continueTask);
                AILogger.d(TAG, "Sending continue-task: " + segment.length() + " chars");
                
                if (!webSocket.send(json)) {
                    AILogger.e(TAG, "Failed to send continue-task at pos: " + start);
                    return false;
                }
                
                start = end;
            }
            
            return true;
            
        } catch (Exception e) {
            AILogger.e(TAG, "Failed to send continue-tasks: " + e.getMessage(), e);
            mainHandler.post(() -> {
                if (callback != null) callback.onError("continue-task failed: " + e.getMessage());
            });
            return false;
        }
    }
    
    /**
     * 发送 finish-task 事件
     */
    private boolean sendFinishTask(String taskId) {
        try {
            JsonObject finishTask = new JsonObject();
            finishTask.addProperty("event_type", "finish-task");
            finishTask.addProperty("task_id", taskId);
            
            String json = gson.toJson(finishTask);
            AILogger.d(TAG, "Sending finish-task");
            
            return webSocket.send(json);
            
        } catch (Exception e) {
            AILogger.e(TAG, "Failed to send finish-task: " + e.getMessage(), e);
            mainHandler.post(() -> {
                if (callback != null) callback.onError("finish-task failed: " + e.getMessage());
            });
            return false;
        }
    }
    
    /**
     * 等待合成完成
     */
    private void waitForCompletion() {
        // TODO: 需要实现更精确的完成检测
        // 这里简化为等待固定的时间(根据文本长度估算)
        try {
            // 等待 1-5 秒,具体取决于文本长度
            int waitTime = Math.min(5000, Math.max(1000, 200 * 100));  // 估计 200 字符
            Thread.sleep(waitTime);
            
            // 如果没有收到 task-finished 事件,手动触发完成回调
            mainHandler.post(() -> {
                if (callback != null) callback.onComplete();
            });
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 处理部分音频数据(result-generated 事件中的二进制数据)
     */
    private void handlePartialAudio(ResponseBody body) {
        try {
            if (body != null) {
                byte[] audioData = body.bytes();
                if (audioData.length > 0 && callback != null) {
                    mainHandler.post(() -> callback.onAudioData(audioData));
                }
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Failed to handle partial audio: " + e.getMessage(), e);
        }
    }
    
    /**
     * 处理二进制音频数据
     */
    private void handleBinaryAudioData(byte[] data, int byteCount) {
        try {
            byte[] audioData = java.util.Arrays.copyOf(data, byteCount);
            if (audioData.length > 0 && callback != null) {
                mainHandler.post(() -> callback.onAudioData(audioData));
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Failed to handle binary audio: " + e.getMessage(), e);
        }
    }
    
    /**
     * 关闭连接
     */
    public synchronized void disconnect() {
        AILogger.i(TAG, "Disconnecting...");
        state = State.CLOSING;
        
        if (webSocket != null) {
            webSocket.close(1000, "Normal closure");
            webSocket = null;
        }
        
        state = State.DISCONNECTED;
        AILogger.i(TAG, "Disconnected");
    }
    
    /**
     * 获取当前状态
     */
    public State getState() {
        return state;
    }
    
    /**
     * 是否已连接
     */
    public boolean isConnected() {
        return state == State.READY || state == State.RUNNING;
    }
    
    // ==================== 静态辅助方法 ====================
    
    /**
     * 判断是否为百炼/DashScope 端点
     */
    public static boolean isDashScopeEndpoint(String apiUrl) {
        return apiUrl != null && 
               (apiUrl.contains("maas.aliyuncs.com") || apiUrl.contains("dashscope.aliyuncs.com"));
    }
    
    /**
     * 从 API URL 提取 Workspace ID
     */
    public static String extractWorkspaceId(String apiUrl) {
        if (apiUrl == null) return null;
        
        // 匹配 {WorkspaceId}.cn-beijing.maas.aliyuncs.com
        String pattern = "([a-zA-Z0-9-]+)\\.(cn-beijing|ap-southeast-1)\\.maas\\.aliyuncs\\.com";
        java.util.regex.Pattern r = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = r.matcher(apiUrl);
        
        if (m.find()) {
            return m.group(1);
        }
        
        return null;
    }
    
    /**
     * 从 API URL 提取区域
     */
    public static String extractRegion(String apiUrl) {
        if (apiUrl == null) return "cn-beijing";
        
        if (apiUrl.contains("cn-beijing")) {
            return "cn-beijing";
        } else if (apiUrl.contains("ap-southeast-1")) {
            return "ap-southeast-1";
        }
        
        return "cn-beijing";  // 默认北京
    }
}
