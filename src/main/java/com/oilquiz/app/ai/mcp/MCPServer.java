package com.oilquiz.app.ai.mcp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.oilquiz.app.util.AILogger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP (Model Context Protocol) 服务器连接管理
 *
 * 功能：
 * - 管理本地 MCP 服务器进程（stdio 模式）
 * - 处理 JSON-RPC 2.0 通信
 - 工具发现和调用
 * - 资源管理
 *
 * @author AI Team
 * @since 2024
 */
public class MCPServer {
    private static final String TAG = "MCPServer";
    private static final String JSONRPC_VERSION = "2.0";
    private static final long REQUEST_TIMEOUT_MS = 30000;

    private final String serverId;
    private final String serverName;
    private final ServerConfig config;
    private final ExecutorService executor;

    private Process serverProcess;
    private PrintWriter processInput;
    private BufferedReader processOutput;
    private BufferedReader processError;

    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicBoolean isConnecting = new AtomicBoolean(false);
    private final AtomicInteger requestId = new AtomicInteger(0);

    // 待处理的请求
    private final Map<String, CompletableFuture<JSONObject>> pendingRequests = new ConcurrentHashMap<>();

    // 服务器能力
    private ServerCapabilities capabilities;
    private List<MCPTool> availableTools = new ArrayList<>();

    // 回调监听
    private ServerStateListener stateListener;

    public MCPServer(@NonNull String serverName, @NonNull ServerConfig config) {
        this.serverId = UUID.randomUUID().toString();
        this.serverName = serverName;
        this.config = config;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "MCP-" + serverName);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 连接到 MCP 服务器
     */
    public CompletableFuture<Boolean> connect() {
        if (isConnected.get()) {
            return CompletableFuture.completedFuture(true);
        }

        if (isConnecting.compareAndSet(false, true)) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    doConnect();
                    return true;
                } catch (Exception e) {
                    AILogger.e(TAG, "Failed to connect to MCP server: " + serverName, e);
                    disconnect();
                    return false;
                } finally {
                    isConnecting.set(false);
                }
            }, executor);
        }

        return CompletableFuture.completedFuture(false);
    }

    private void doConnect() throws IOException, JSONException {
        // 启动服务器进程
        ProcessBuilder pb = new ProcessBuilder(config.getCommand());
        pb.environment().putAll(config.getEnvironment());
        pb.redirectErrorStream(false);

        serverProcess = pb.start();

        processInput = new PrintWriter(
            new OutputStreamWriter(serverProcess.getOutputStream(), StandardCharsets.UTF_8),
            true
        );
        processOutput = new BufferedReader(
            new InputStreamReader(serverProcess.getInputStream(), StandardCharsets.UTF_8)
        );
        processError = new BufferedReader(
            new InputStreamReader(serverProcess.getErrorStream(), StandardCharsets.UTF_8)
        );

        // 启动输出读取线程
        executor.submit(this::readOutputLoop);
        executor.submit(this::readErrorLoop);

        // 发送初始化请求
        JSONObject initResult = sendRequestBlocking("initialize", new JSONObject()
            .put("protocolVersion", "2024-11-05")
            .put("capabilities", new JSONObject()
                .put("tools", new JSONObject().put("listChanged", true))
                .put("resources", new JSONObject())
            )
            .put("clientInfo", new JSONObject()
                .put("name", "SmartQuiz")
                .put("version", "2.0.1")
            )
        );

        if (initResult != null) {
            capabilities = new ServerCapabilities(initResult.optJSONObject("capabilities"));
            isConnected.set(true);

            // 获取工具列表
            listTools();

            // 发送 initialized 通知
            sendNotification("notifications/initialized", new JSONObject());

            if (stateListener != null) {
                stateListener.onConnected(this);
            }

            AILogger.i(TAG, "MCP server connected: " + serverName);
        }
    }

    /**
     * 断开连接
     */
    public void disconnect() {
        if (!isConnected.compareAndSet(true, false)) {
            return;
        }

        try {
            // 发送关闭通知
            sendNotification("notifications/cancelled", new JSONObject()
                .put("reason", "Client disconnecting")
            );
        } catch (Exception e) {
            AILogger.w(TAG, "Error sending disconnect notification: " + e.getMessage());
        }

        // 关闭流
        closeQuietly(processInput);
        closeQuietly(processOutput);
        closeQuietly(processError);

        // 终止进程
        if (serverProcess != null) {
            serverProcess.destroy();
            try {
                if (!serverProcess.waitFor(5, TimeUnit.SECONDS)) {
                    serverProcess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                serverProcess.destroyForcibly();
            }
        }

        // 清理待处理请求
        for (CompletableFuture<JSONObject> future : pendingRequests.values()) {
            future.completeExceptionally(new IOException("Server disconnected"));
        }
        pendingRequests.clear();

        availableTools.clear();

        if (stateListener != null) {
            stateListener.onDisconnected(this);
        }

        AILogger.i(TAG, "MCP server disconnected: " + serverName);
    }

    /**
     * 获取工具列表
     */
    public CompletableFuture<List<MCPTool>> listTools() {
        return sendRequest("tools/list", new JSONObject())
            .thenApply(result -> {
                List<MCPTool> tools = new ArrayList<>();
                JSONArray toolsArray = result.optJSONArray("tools");
                if (toolsArray != null) {
                    for (int i = 0; i < toolsArray.length(); i++) {
                        try {
                            tools.add(new MCPTool(toolsArray.getJSONObject(i), this));
                        } catch (JSONException e) {
                            AILogger.w(TAG, "Failed to parse tool: " + e.getMessage());
                        }
                    }
                }
                availableTools = tools;
                return tools;
            });
    }

    /**
     * 调用工具
     */
    public CompletableFuture<JSONObject> callTool(String toolName, JSONObject arguments) {
        try {
            return sendRequest("tools/call", new JSONObject()
                .put("name", toolName)
                .put("arguments", arguments)
            );
        } catch (org.json.JSONException e) {
            CompletableFuture<JSONObject> future = new CompletableFuture<>();
            future.completeExceptionally(e);
            return future;
        }
    }

    /**
     * 发送请求（异步）
     */
    public CompletableFuture<JSONObject> sendRequest(String method, JSONObject params) {
        String id = String.valueOf(requestId.incrementAndGet());
        CompletableFuture<JSONObject> future = new CompletableFuture<>();

        pendingRequests.put(id, future);

        JSONObject request = new JSONObject();
        try {
            request.put("jsonrpc", JSONRPC_VERSION);
            request.put("id", id);
            request.put("method", method);
            if (params != null) {
                request.put("params", params);
            }
        } catch (JSONException e) {
            future.completeExceptionally(e);
            return future;
        }

        // 发送请求
        sendMessage(request.toString());

        // 设置超时
        executor.submit(() -> {
            try {
                Thread.sleep(REQUEST_TIMEOUT_MS);
                CompletableFuture<JSONObject> removed = pendingRequests.remove(id);
                if (removed != null && !removed.isDone()) {
                    removed.completeExceptionally(
                        new IOException("Request timeout: " + method)
                    );
                }
            } catch (InterruptedException ignored) {
            }
        });

        return future;
    }

    /**
     * 发送请求（同步阻塞）
     */
    private JSONObject sendRequestBlocking(String method, JSONObject params)
            throws IOException, JSONException {
        try {
            return sendRequest(method, params).get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new IOException("Request failed: " + method, e);
        }
    }

    /**
     * 发送通知（无需响应）
     */
    public void sendNotification(String method, JSONObject params) {
        JSONObject notification = new JSONObject();
        try {
            notification.put("jsonrpc", JSONRPC_VERSION);
            notification.put("method", method);
            if (params != null) {
                notification.put("params", params);
            }
            sendMessage(notification.toString());
        } catch (JSONException e) {
            AILogger.e(TAG, "Failed to create notification", e);
        }
    }

    private void sendMessage(String message) {
        if (processInput != null && !processInput.checkError()) {
            processInput.println(message);
            AILogger.d(TAG, "MCP -> " + serverName + ": " + message);
        }
    }

    private void readOutputLoop() {
        try {
            String line;
            while ((line = processOutput.readLine()) != null) {
                handleMessage(line);
            }
        } catch (IOException e) {
            if (isConnected.get()) {
                AILogger.e(TAG, "Error reading MCP server output", e);
                disconnect();
            }
        }
    }

    private void readErrorLoop() {
        try {
            String line;
            while ((line = processError.readLine()) != null) {
                AILogger.w(TAG, "MCP server [" + serverName + "] stderr: " + line);
            }
        } catch (IOException e) {
            // Ignore
        }
    }

    private void handleMessage(String message) {
        AILogger.d(TAG, "MCP <- " + serverName + ": " + message);

        try {
            JSONObject json = new JSONObject(message);

            // 处理响应
            if (json.has("id")) {
                String id = json.optString("id");
                CompletableFuture<JSONObject> future = pendingRequests.remove(id);
                if (future != null) {
                    if (json.has("error")) {
                        JSONObject error = json.getJSONObject("error");
                        future.completeExceptionally(new MCPException(
                            error.optInt("code", -1),
                            error.optString("message", "Unknown error")
                        ));
                    } else {
                        future.complete(json.optJSONObject("result"));
                    }
                }
            }

            // 处理服务器通知
            if (json.has("method")) {
                String method = json.getString("method");
                handleNotification(method, json.optJSONObject("params"));
            }

        } catch (JSONException e) {
            AILogger.e(TAG, "Failed to parse MCP message", e);
        }
    }

    private void handleNotification(String method, JSONObject params) {
        switch (method) {
            case "notifications/tools/list_changed":
                AILogger.i(TAG, "Tool list changed, refreshing...");
                listTools();
                break;
            case "notifications/resources/updated":
                if (stateListener != null) {
                    stateListener.onResourcesUpdated(this, params);
                }
                break;
            case "notifications/progress":
                if (stateListener != null) {
                    stateListener.onProgress(this, params);
                }
                break;
        }
    }

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }

    // Getters
    public String getServerId() {
        return serverId;
    }

    public String getServerName() {
        return serverName;
    }

    public boolean isConnected() {
        return isConnected.get();
    }

    public List<MCPTool> getAvailableTools() {
        return new ArrayList<>(availableTools);
    }

    public ServerCapabilities getCapabilities() {
        return capabilities;
    }

    public void setStateListener(ServerStateListener listener) {
        this.stateListener = listener;
    }

    /**
     * 服务器配置
     */
    public static class ServerConfig {
        private List<String> command;
        private Map<String, String> environment = new HashMap<>();

        public ServerConfig(List<String> command) {
            this.command = command;
        }

        public List<String> getCommand() {
            return command;
        }

        public Map<String, String> getEnvironment() {
            return environment;
        }

        public void setEnvironment(Map<String, String> env) {
            this.environment = env;
        }
    }

    /**
     * 服务器能力
     */
    public static class ServerCapabilities {
        public final boolean supportsTools;
        public final boolean supportsResources;
        public final boolean supportsPrompts;

        public ServerCapabilities(JSONObject caps) {
            this.supportsTools = caps != null && caps.has("tools");
            this.supportsResources = caps != null && caps.has("resources");
            this.supportsPrompts = caps != null && caps.has("prompts");
        }
    }

    /**
     * 服务器状态监听
     */
    public interface ServerStateListener {
        void onConnected(MCPServer server);
        void onDisconnected(MCPServer server);
        void onResourcesUpdated(MCPServer server, JSONObject params);
        void onProgress(MCPServer server, JSONObject params);
    }

    /**
     * MCP 异常
     */
    public static class MCPException extends Exception {
        public final int code;

        public MCPException(int code, String message) {
            super(message);
            this.code = code;
        }
    }
}
