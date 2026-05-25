package com.oilquiz.app.ai.mcp;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.oilquiz.app.util.AILogger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MCP (Model Context Protocol) 管理器
 *
 * 功能：
 * - 管理多个 MCP 服务器连接
 * - 服务器配置持久化
 * - 工具发现和聚合
 * - 服务器健康检查
 *
 * 单例模式，应用生命周期内保持
 */
public class MCPManager {
    private static final String TAG = "MCPManager";
    private static final String PREFS_NAME = "mcp_servers";
    private static final String KEY_SERVERS = "servers";

    private static volatile MCPManager instance;

    private final Context context;
    private final SharedPreferences prefs;
    private final Map<String, MCPServer> servers = new ConcurrentHashMap<>();
    private final List<MCPStateListener> listeners = new CopyOnWriteArrayList<>();

    private MCPManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        // 延迟加载保存的服务器，避免在初始化时连接
    }

    public static MCPManager getInstance(Context context) {
        if (instance == null) {
            synchronized (MCPManager.class) {
                if (instance == null) {
                    instance = new MCPManager(context);
                }
            }
        }
        return instance;
    }

    /**
     * 添加并连接 MCP 服务器
     */
    public CompletableFuture<Boolean> addServer(@NonNull String name, @NonNull MCPServer.ServerConfig config) {
        // 断开已存在的同名服务器
        removeServer(name);

        MCPServer server = new MCPServer(name, config);
        server.setStateListener(new MCPServer.ServerStateListener() {
            @Override
            public void onConnected(MCPServer server) {
                AILogger.i(TAG, "Server connected: " + name);
                notifyServerConnected(server);
            }

            @Override
            public void onDisconnected(MCPServer server) {
                AILogger.i(TAG, "Server disconnected: " + name);
                notifyServerDisconnected(server);
            }

            @Override
            public void onResourcesUpdated(MCPServer server, JSONObject params) {
                notifyResourcesUpdated(server, params);
            }

            @Override
            public void onProgress(MCPServer server, JSONObject params) {
                notifyProgress(server, params);
            }
        });

        servers.put(name, server);

        return server.connect()
            .thenApply(success -> {
                if (success) {
                    saveServerConfig(name, config);
                } else {
                    servers.remove(name);
                }
                return success;
            });
    }

    /**
     * 移除服务器
     */
    public void removeServer(@NonNull String name) {
        MCPServer server = servers.remove(name);
        if (server != null) {
            server.disconnect();
            removeServerConfig(name);
        }
    }

    /**
     * 获取所有服务器
     */
    public List<MCPServer> getAllServers() {
        return new ArrayList<>(servers.values());
    }

    /**
     * 获取已连接的服务器
     */
    public List<MCPServer> getConnectedServers() {
        List<MCPServer> connected = new ArrayList<>();
        for (MCPServer server : servers.values()) {
            if (server.isConnected()) {
                connected.add(server);
            }
        }
        return connected;
    }

    /**
     * 获取指定服务器
     */
    @Nullable
    public MCPServer getServer(@NonNull String name) {
        return servers.get(name);
    }

    /**
     * 获取所有可用工具
     */
    public List<MCPTool> getAllTools() {
        List<MCPTool> allTools = new ArrayList<>();
        for (MCPServer server : servers.values()) {
            if (server.isConnected()) {
                allTools.addAll(server.getAvailableTools());
            }
        }
        return allTools;
    }

    /**
     * 根据名称查找工具
     */
    @Nullable
    public MCPTool findTool(@NonNull String toolName) {
        for (MCPServer server : servers.values()) {
            if (server.isConnected()) {
                for (MCPTool tool : server.getAvailableTools()) {
                    if (tool.getName().equals(toolName)) {
                        return tool;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 断开所有服务器
     */
    public void disconnectAll() {
        for (MCPServer server : servers.values()) {
            server.disconnect();
        }
        servers.clear();
    }

    /**
     * 重新连接所有保存的服务器
     */
    public CompletableFuture<Void> reconnectAll() {
        List<CompletableFuture<Boolean>> futures = new ArrayList<>();

        for (Map.Entry<String, MCPServer.ServerConfig> entry : loadServerConfigs().entrySet()) {
            futures.add(addServer(entry.getKey(), entry.getValue()));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    // 配置持久化

    private void saveServerConfig(String name, MCPServer.ServerConfig config) {
        try {
            JSONObject serversJson = new JSONObject(prefs.getString(KEY_SERVERS, "{}"));
            JSONObject serverJson = new JSONObject();
            serverJson.put("command", new JSONArray(config.getCommand()));

            JSONObject envJson = new JSONObject();
            for (Map.Entry<String, String> entry : config.getEnvironment().entrySet()) {
                envJson.put(entry.getKey(), entry.getValue());
            }
            serverJson.put("environment", envJson);

            serversJson.put(name, serverJson);
            prefs.edit().putString(KEY_SERVERS, serversJson.toString()).apply();
        } catch (JSONException e) {
            AILogger.e(TAG, "Failed to save server config", e);
        }
    }

    private void removeServerConfig(String name) {
        try {
            JSONObject serversJson = new JSONObject(prefs.getString(KEY_SERVERS, "{}"));
            serversJson.remove(name);
            prefs.edit().putString(KEY_SERVERS, serversJson.toString()).apply();
        } catch (JSONException e) {
            AILogger.e(TAG, "Failed to remove server config", e);
        }
    }

    private Map<String, MCPServer.ServerConfig> loadServerConfigs() {
        Map<String, MCPServer.ServerConfig> configs = new HashMap<>();
        try {
            String jsonStr = prefs.getString(KEY_SERVERS, "{}");
            JSONObject serversJson = new JSONObject(jsonStr);

            Iterator<String> keys = serversJson.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                JSONObject serverJson = serversJson.getJSONObject(name);

                JSONArray cmdArray = serverJson.getJSONArray("command");
                List<String> command = new ArrayList<>();
                for (int i = 0; i < cmdArray.length(); i++) {
                    command.add(cmdArray.getString(i));
                }

                MCPServer.ServerConfig config = new MCPServer.ServerConfig(command);

                JSONObject envJson = serverJson.optJSONObject("environment");
                if (envJson != null) {
                    Map<String, String> env = new HashMap<>();
                    Iterator<String> envKeys = envJson.keys();
                    while (envKeys.hasNext()) {
                        String envKey = envKeys.next();
                        env.put(envKey, envJson.getString(envKey));
                    }
                    config.setEnvironment(env);
                }

                configs.put(name, config);
            }
        } catch (JSONException e) {
            AILogger.e(TAG, "Failed to load server configs", e);
        }
        return configs;
    }

    /**
     * 加载并连接保存的服务器（应在应用启动后调用）
     */
    public void loadSavedServers() {
        // 启动时自动连接保存的服务器
        Map<String, MCPServer.ServerConfig> configs = loadServerConfigs();
        for (Map.Entry<String, MCPServer.ServerConfig> entry : configs.entrySet()) {
            addServer(entry.getKey(), entry.getValue());
        }
    }

    // 监听器管理

    public void addListener(MCPStateListener listener) {
        listeners.add(listener);
    }

    public void removeListener(MCPStateListener listener) {
        listeners.remove(listener);
    }

    private void notifyServerConnected(MCPServer server) {
        for (MCPStateListener listener : listeners) {
            listener.onServerConnected(server);
        }
    }

    private void notifyServerDisconnected(MCPServer server) {
        for (MCPStateListener listener : listeners) {
            listener.onServerDisconnected(server);
        }
    }

    private void notifyResourcesUpdated(MCPServer server, JSONObject params) {
        for (MCPStateListener listener : listeners) {
            listener.onResourcesUpdated(server, params);
        }
    }

    private void notifyProgress(MCPServer server, JSONObject params) {
        for (MCPStateListener listener : listeners) {
            listener.onProgress(server, params);
        }
    }

    /**
     * MCP 状态监听器
     */
    public interface MCPStateListener {
        void onServerConnected(MCPServer server);
        void onServerDisconnected(MCPServer server);
        void onResourcesUpdated(MCPServer server, JSONObject params);
        void onProgress(MCPServer server, JSONObject params);
    }

    // 预置服务器配置

    /**
     * 创建文件系统 MCP 服务器配置（系统环境）
     */
    public static MCPServer.ServerConfig createFilesystemConfig(@NonNull String basePath) {
        List<String> command = Arrays.asList(
            "npx", "-y", "@modelcontextprotocol/server-filesystem",
            basePath
        );
        return new MCPServer.ServerConfig(command);
    }

    /**
     * 创建文件系统 MCP 服务器配置（Termux环境）
     */
    public static MCPServer.ServerConfig createFilesystemConfigTermux(@NonNull String basePath) {
        List<String> command = Arrays.asList(
            "/data/data/com.termux/files/usr/bin/npx",
            "-y",
            "@modelcontextprotocol/server-filesystem",
            basePath
        );
        MCPServer.ServerConfig config = new MCPServer.ServerConfig(command);
        // 设置Termux环境变量
        Map<String, String> env = new HashMap<>();
        env.put("PATH", "/data/data/com.termux/files/usr/bin:/data/data/com.termux/files/usr/bin/applets");
        env.put("HOME", "/data/data/com.termux/files/home");
        env.put("PREFIX", "/data/data/com.termux/files/usr");
        env.put("TMPDIR", "/data/data/com.termux/files/usr/tmp");
        config.setEnvironment(env);
        return config;
    }

    /**
     * 创建 SQLite MCP 服务器配置（系统环境）
     */
    public static MCPServer.ServerConfig createSQLiteConfig(@NonNull String dbPath) {
        List<String> command = Arrays.asList(
            "uvx", "-y", "mcp-server-sqlite",
            "--db-path", dbPath
        );
        return new MCPServer.ServerConfig(command);
    }

    /**
     * 创建 SQLite MCP 服务器配置（Termux环境）
     */
    public static MCPServer.ServerConfig createSQLiteConfigTermux(@NonNull String dbPath) {
        List<String> command = Arrays.asList(
            "/data/data/com.termux/files/usr/bin/uvx",
            "-y",
            "mcp-server-sqlite",
            "--db-path", dbPath
        );
        MCPServer.ServerConfig config = new MCPServer.ServerConfig(command);
        Map<String, String> env = new HashMap<>();
        env.put("PATH", "/data/data/com.termux/files/usr/bin:/data/data/com.termux/files/usr/bin/applets");
        env.put("HOME", "/data/data/com.termux/files/home");
        env.put("PREFIX", "/data/data/com.termux/files/usr");
        env.put("TMPDIR", "/data/data/com.termux/files/usr/tmp");
        config.setEnvironment(env);
        return config;
    }

    /**
     * 创建 Git MCP 服务器配置（系统环境）
     */
    public static MCPServer.ServerConfig createGitConfig(@NonNull String repoPath) {
        List<String> command = Arrays.asList(
            "uvx", "-y", "mcp-server-git",
            "--repository", repoPath
        );
        return new MCPServer.ServerConfig(command);
    }

    /**
     * 创建 Git MCP 服务器配置（Termux环境）
     */
    public static MCPServer.ServerConfig createGitConfigTermux(@NonNull String repoPath) {
        List<String> command = Arrays.asList(
            "/data/data/com.termux/files/usr/bin/uvx",
            "-y",
            "mcp-server-git",
            "--repository", repoPath
        );
        MCPServer.ServerConfig config = new MCPServer.ServerConfig(command);
        Map<String, String> env = new HashMap<>();
        env.put("PATH", "/data/data/com.termux/files/usr/bin:/data/data/com.termux/files/usr/bin/applets");
        env.put("HOME", "/data/data/com.termux/files/home");
        env.put("PREFIX", "/data/data/com.termux/files/usr");
        env.put("TMPDIR", "/data/data/com.termux/files/usr/tmp");
        config.setEnvironment(env);
        return config;
    }

    /**
     * 检查Termux是否已安装
     */
    public static boolean isTermuxInstalled() {
        return new File("/data/data/com.termux/files/usr/bin").exists();
    }

    /**
     * 检查Termux中是否安装了指定命令
     */
    public static boolean isTermuxCommandAvailable(String command) {
        return new File("/data/data/com.termux/files/usr/bin/" + command).exists();
    }

    /**
     * 创建自定义命令配置
     */
    public static MCPServer.ServerConfig createCustomConfig(@NonNull String... command) {
        return new MCPServer.ServerConfig(Arrays.asList(command));
    }
}
