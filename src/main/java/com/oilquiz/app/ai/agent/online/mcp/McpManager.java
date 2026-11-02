package com.oilquiz.app.ai.agent.online.mcp;

import android.content.Context;

import com.oilquiz.app.ai.agent.online.OnlineToolManager;
import com.oilquiz.app.ai.agent.online.OnlineToolRegistry;
import com.oilquiz.app.ai.model.APIConfig;
import com.oilquiz.app.ai.tool.AIToolResult;
import com.oilquiz.app.ai.tool.openai.ToolDefinition;
import com.oilquiz.app.ai.util.APIKeyManager;
import com.oilquiz.app.util.AILogger;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MCP 服务管理器（全局单例）—— MCP 基础设施的对外入口。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>从 {@link McpConfigStore} 加载服务配置，为每个启用的 server 维护一个 {@link McpClient}；</li>
 *   <li>把远端 {@code tools/list} 枚举出的工具转换为 {@link ToolDefinition}，
 *       以 {@code mcp__{serverId}__{toolName}} 命名注册进 {@link OnlineToolRegistry}
 *       （在线专用工具，走既有工具指南/调用链/使用统计管道）；</li>
 *   <li>执行桥接：{@link #execute} 把在线引擎的调用转发为远端 {@code tools/call}；</li>
 *   <li>管理接口：增删改 server、启停、刷新工具。</li>
 * </ul>
 *
 * <p>线程模型：网络全部在后台执行器进行；{@code execute} 由调用方线程
 * （OnlineToolManager 的 TOOL_EXECUTOR）触发，本身同步阻塞。</p>
 */
public class McpManager {

    private static final String TAG = "McpManager";

    private static volatile McpManager instance;

    private final Context appContext;
    private final McpConfigStore store;
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();
    /** 每个 server 当前已注册到 registry 的工具名（用于刷新时精确注销） */
    private final Map<String, Set<String>> registeredTools = new ConcurrentHashMap<>();
    /** serverId → 状态文案（ok:N / error:... / disabled） */
    private final Map<String, String> status = new ConcurrentHashMap<>();
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Mcp-Sync");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean initialized;

    private McpManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.store = new McpConfigStore(appContext);
    }

    public static synchronized McpManager getInstance(Context context) {
        if (instance == null) {
            instance = new McpManager(context);
        }
        return instance;
    }

    /** 供测试/重启时替换实例。 */
    public static synchronized void setInstance(McpManager manager) {
        instance = manager;
    }

    // ==================== 初始化 ====================

    /**
     * 加载配置并异步同步工具。幂等；网络操作走后台执行器，不阻塞调用线程。
     * 由 {@link OnlineToolManager} 构造时触发一次。
     */
    public synchronized void init() {
        if (initialized) return;
        initialized = true;
        syncExecutor.execute(this::syncAll);
    }

    // ==================== 工具同步 ====================

    /** 全量同步：对每个启用的 server 初始化 + 枚举工具 + 刷新注册。 */
    private void syncAll() {
        try {
            List<McpServerConfig> servers = store.load();
            for (McpServerConfig cfg : servers) {
                if (!cfg.enabled) {
                    status.put(cfg.id, "disabled");
                    continue;
                }
                syncServer(cfg);
            }
        } catch (Exception e) {
            AILogger.e(TAG, "syncAll failed: " + e.getMessage(), e);
        }
    }

    /** 同步单个 server：初始化连接 → listTools → 刷新 OnlineToolRegistry 注册。 */
    private void syncServer(McpServerConfig cfg) {
        OnlineToolRegistry registry = null;
        try {
            McpClient client = clients.computeIfAbsent(cfg.id,
                    id -> new McpClient(resolvedCfg(cfg)));
            client.initialize();
            List<McpClient.McpToolInfo> tools = client.listTools();

            registry = OnlineToolManager.getInstance(appContext).getRegistry();
            // 先注销该 server 上次注册的工具（远端工具变化后旧条目不残留）
            Set<String> old = registeredTools.remove(cfg.id);
            if (old != null) {
                for (String name : old) {
                    try {
                        registry.unregisterTool(name);
                    } catch (Exception ignored) {
                    }
                }
            }
            // 注册新工具
            Set<String> registered = new HashSet<>();
            for (McpClient.McpToolInfo t : tools) {
                String localName = toolNameFor(cfg.id, t.name);
                ToolDefinition def = buildToolDefinition(localName, t);
                registry.registerTool(localName, t.description, McpConstants.TOOL_CATEGORY, def);
                registered.add(localName);
            }
            registeredTools.put(cfg.id, registered);
            status.put(cfg.id, "ok:" + registered.size());
            AILogger.i(TAG, "MCP server synced: " + cfg.id + " (" + registered.size() + " tools)");
        } catch (Exception e) {
            status.put(cfg.id, "error:" + abbreviate(e.getMessage()));
            AILogger.e(TAG, "syncServer failed (" + cfg.id + "): " + e.getMessage(), e);
        }
    }

    /** 工具本地名：mcp__{serverId}__{toolName} */
    private static String toolNameFor(String serverId, String remoteToolName) {
        return McpConstants.TOOL_PREFIX + serverId + "__" + remoteToolName;
    }

    /** 把 MCP JSON Schema 参数定义转为项目 ToolDefinition。 */
    private static ToolDefinition buildToolDefinition(String localName, McpClient.McpToolInfo info) {
        ToolDefinition.Builder builder = ToolDefinition.builder(localName,
                info.description != null && !info.description.isEmpty()
                        ? info.description : "MCP 远端工具（server " + localName + "）");
        builder.category(McpConstants.TOOL_CATEGORY);
        builder.whenToUse("MCP 外部服务能力（" + localName + "），按需调用");

        JSONObject schema = info.inputSchema;
        if (schema != null) {
            JSONObject properties = schema.optJSONObject("properties");
            Set<String> required = new HashSet<>();
            org.json.JSONArray requiredArr = schema.optJSONArray("required");
            if (requiredArr != null) {
                for (int i = 0; i < requiredArr.length(); i++) {
                    required.add(String.valueOf(requiredArr.opt(i)));
                }
            }
            if (properties != null) {
                java.util.Iterator<String> keys = properties.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    JSONObject prop = properties.optJSONObject(key);
                    if (prop == null) continue;
                    String type = prop.optString("type", "string");
                    String desc = prop.optString("description", "");
                    boolean isReq = required.contains(key);
                    org.json.JSONArray enumArr = prop.optJSONArray("enum");
                    if (enumArr != null && enumArr.length() > 0) {
                        List<String> enumValues = new ArrayList<>();
                        for (int i = 0; i < enumArr.length(); i++) {
                            enumValues.add(String.valueOf(enumArr.opt(i)));
                        }
                        builder.addParameter(key, type, desc, isReq, null, enumValues);
                    } else {
                        builder.addParameter(key, type, desc, isReq);
                    }
                }
            }
        }
        return builder.build();
    }

    // ==================== 工具执行 ====================

    /** 是否为 MCP 工具（按命名前缀判定）。 */
    public static boolean isMcpTool(String toolName) {
        return toolName != null && toolName.startsWith(McpConstants.TOOL_PREFIX);
    }

    /**
     * 执行 MCP 工具。解析 serverId 与远端工具名，转发 {@code tools/call}。
     * 同步阻塞；调用方须在后台线程（OnlineToolManager 的 TOOL_EXECUTOR）。
     */
    public AIToolResult execute(String toolName, Map<String, Object> arguments) {
        Map<String, Object> info = new HashMap<>();
        info.put("toolName", toolName);
        try {
            int firstSep = toolName.indexOf("__", McpConstants.TOOL_PREFIX.length());
            if (firstSep < 0) {
                return AIToolResult.fail("MCP 工具名格式错误: " + toolName, info);
            }
            String serverId = toolName.substring(McpConstants.TOOL_PREFIX.length(), firstSep);
            String remoteName = toolName.substring(firstSep + 2);

            McpClient client = clients.get(serverId);
            if (client == null) {
                return AIToolResult.fail("MCP server 未连接: " + serverId + "（请先在 MCP 管理页启用并同步）", info);
            }
            try {
                client.initialize(); // 幂等
            } catch (Exception e) {
                return AIToolResult.fail("MCP server 初始化失败: " + e.getMessage(), info);
            }

            JSONObject argsJson = new JSONObject();
            if (arguments != null) {
                for (Map.Entry<String, Object> e : arguments.entrySet()) {
                    Object v = e.getValue();
                    if (v == null) continue;
                    argsJson.put(e.getKey(), v instanceof JSONObject
                            ? v
                            : (v instanceof org.json.JSONArray ? v : String.valueOf(v)));
                }
            }
            McpClient.McpCallResult result = client.callTool(remoteName, argsJson);
            if (result.isError) {
                return AIToolResult.fail("MCP 工具执行失败: " + result.text, info);
            }
            return AIToolResult.success(result.text, info);
        } catch (Exception e) {
            AILogger.e(TAG, "execute failed: " + toolName, e);
            return AIToolResult.fail("MCP 工具执行异常: " + e.getMessage(), info);
        }
    }

    // ==================== 管理接口 ====================

    /** 当前全部 server 配置（含禁用）。 */
    public List<McpServerConfig> getServers() {
        return store.load();
    }

    /** serverId → 状态（ok:N / error:... / disabled / 未同步）。 */
    public Map<String, String> getStatus() {
        return new LinkedHashMap<>(status);
    }

    /** 新增或更新 server，并触发异步同步（启用时）。 */
    public McpServerConfig upsertServer(McpServerConfig cfg) {
        McpServerConfig saved = store.upsert(cfg);
        clients.remove(cfg.id); // 配置变化后重建连接
        registeredTools.remove(cfg.id);
        if (cfg.enabled) {
            syncExecutor.execute(() -> syncServer(store.load().stream()
                    .filter(c -> c.id.equals(cfg.id)).findFirst().orElse(cfg)));
        } else {
            status.put(cfg.id, "disabled");
            removeRegisteredTools(cfg.id);
        }
        return saved;
    }

    /** 删除 server（同时注销其工具）。 */
    public boolean removeServer(String id) {
        boolean removed = store.remove(id);
        McpClient client = clients.remove(id);
        if (client != null) client.close();
        status.remove(id);
        removeRegisteredTools(id);
        return removed;
    }

    /** 启停 server。 */
    public void setEnabled(String id, boolean enabled) {
        for (McpServerConfig cfg : store.load()) {
            if (cfg.id.equals(id)) {
                upsertServer(cfg.withEnabled(enabled));
                return;
            }
        }
    }

    /** 更新令牌。 */
    public void updateToken(String id, String token) {
        for (McpServerConfig cfg : store.load()) {
            if (cfg.id.equals(id)) {
                upsertServer(cfg.withToken(token));
                return;
            }
        }
    }

    /**
     * 更新令牌引用：不存明文，而是绑定 APIKeyManager 中已存密钥的键名
     * （APIConfig 的 name 或 saveAPIKey 的 service）。令牌留空时运行时按引用取值。
     */
    public void updateTokenRef(String id, String tokenRef) {
        for (McpServerConfig cfg : store.load()) {
            if (cfg.id.equals(id)) {
                upsertServer(cfg.withTokenRef(tokenRef));
                return;
            }
        }
    }

    /**
     * 解析一个 server 实际使用的令牌（明文 token 优先；空则按 tokenRef 从密钥库取）。
     * 外部（管理页展示 / 排查）可调用。
     */
    public String resolveToken(McpServerConfig cfg) {
        if (cfg.token != null && !cfg.token.isEmpty()) {
            return cfg.token;
        }
        String ref = cfg.tokenRef;
        if (ref != null && !ref.isEmpty()) {
            APIKeyManager keys = APIKeyManager.getInstance(appContext);
            String refNorm = ref.trim();
            // 1) APIConfig 列表按 name 匹配（用户在线模型配置里的密钥，如百炼/DASHSCOPE）
            //    忽略大小写与首尾空格，避免"引用匹配密钥"后因名称不一致而解析失败
            List<APIConfig> configs = keys.getAllAPIConfigs();
            for (APIConfig c : configs) {
                if (c != null && c.getName() != null && refNorm.equalsIgnoreCase(c.getName().trim())) {
                    String k = c.getApiKey();
                    if (k != null && !k.isEmpty()) return k;
                }
            }
            // 2) saveAPIKey 体系按 service 名
            String k = keys.getAPIKey(refNorm);
            if (k != null && !k.isEmpty()) return k;
        }
        return "";
    }

    /** 派生一个 client 可直接使用的配置（tokenRef → 解析出明文 token）。 */
    private McpServerConfig resolvedCfg(McpServerConfig cfg) {
        if (cfg.token != null && !cfg.token.isEmpty()) return cfg;
        String resolved = resolveToken(cfg);
        return resolved.isEmpty() ? cfg : cfg.withToken(resolved);
    }

    // ==================== 密钥与服务的匹配 ====================

    /** MCP 服务所属服务商（用于推荐匹配的密钥，避免"乱引"不兼容的 key）。 */
    public enum McpProvider { ALIYUN, TENCENT, MODELSCOPE, UNKNOWN }

    /**
     * 按 server 的 URL / 名称推断服务商。无法判定返回 UNKNOWN
     * （此时引用全部密钥都只是"试试"，必须提示用户确认）。
     */
    public McpProvider guessProvider(McpServerConfig cfg) {
        String text = (cfg.name + " " + cfg.url).toLowerCase();
        if (text.contains("aliyun") || text.contains("dashscope") || text.contains("bailian")
                || text.contains("aliyuncs") || text.contains("maxcompute")
                || text.contains("阿里") || text.contains("百炼")) {
            return McpProvider.ALIYUN;
        }
        if (text.contains("tencent") || text.contains("qq.com") || text.contains("tcb-api")
                || text.contains("cloudbase") || text.contains("腾讯") || text.contains("位置服务")) {
            return McpProvider.TENCENT;
        }
        if (text.contains("modelscope") || text.contains("魔搭")
                || text.contains("model-scope") || text.contains("ms.show")) {
            return McpProvider.MODELSCOPE;
        }
        return McpProvider.UNKNOWN;
    }

    /** 判断一段密钥描述（配置名/服务类型/host/键名）是否属于该服务商。 */
    private static boolean providerTextMatches(McpProvider p, String text) {
        if (text == null) return false;
        String t = text.toLowerCase();
        switch (p) {
            case ALIYUN:
                return t.contains("aliyun") || t.contains("dashscope") || t.contains("bailian")
                        || t.contains("aliyuncs") || t.contains("maxcompute")
                        || t.contains("阿里") || t.contains("百炼");
            case TENCENT:
                return t.contains("tencent") || t.contains("qq") || t.contains("tcb")
                        || t.contains("cloudbase") || t.contains("腾讯") || t.contains("位置");
            case MODELSCOPE:
                return t.contains("modelscope") || t.contains("魔搭")
                        || t.contains("model-scope") || t.contains("ms.show");
            default:
                return false;
        }
    }

    /**
     * 找出与该 server 服务商匹配的密钥引用候选。
     *
     * @return 每项为 {显示文案, 引用键名}；服务商无法判定（UNKNOWN）时返回空表，
     *         由调用方提示用户手动确认全部密钥。
     */
    public List<String[]> findMatchingKeyRefs(McpServerConfig cfg) {
        List<String[]> matches = new ArrayList<>();
        McpProvider p = guessProvider(cfg);
        if (p == McpProvider.UNKNOWN) return matches;
        APIKeyManager keys = APIKeyManager.getInstance(appContext);
        // 1) 在线配置（APIConfig）：按 名称/服务类型/host 判定
        for (APIConfig c : keys.getAllAPIConfigs()) {
            String k = c.getApiKey();
            if (k == null || k.isEmpty()) continue;
            String probe = c.getName() + " " + c.getServiceType() + " "
                    + (c.getApiHost() == null ? "" : c.getApiHost());
            if (providerTextMatches(p, probe)) {
                matches.add(new String[]{"在线配置·" + c.getName() + " (" + c.getServiceType() + ")", c.getName()});
            }
        }
        // 2) 密钥库（saveAPIKey 体系）：按键名判定
        for (String s : keys.getAllServiceKeys()) {
            String k = keys.getAPIKey(s);
            if (k == null || k.isEmpty()) continue;
            if (providerTextMatches(p, s)) {
                matches.add(new String[]{"密钥库·" + s, s});
            }
        }
        return matches;
    }

    /** 全部可引用密钥（含与当前服务不匹配的），供"查看全部"兜底使用。 */
    public List<String[]> findAllKeyRefs() {
        List<String[]> all = new ArrayList<>();
        APIKeyManager keys = APIKeyManager.getInstance(appContext);
        for (APIConfig c : keys.getAllAPIConfigs()) {
            String k = c.getApiKey();
            if (k == null || k.isEmpty()) continue;
            all.add(new String[]{"在线配置·" + c.getName() + " (" + c.getServiceType() + ")", c.getName()});
        }
        for (String s : keys.getAllServiceKeys()) {
            String k = keys.getAPIKey(s);
            if (k == null || k.isEmpty()) continue;
            all.add(new String[]{"密钥库·" + s, s});
        }
        return all;
    }

    /** 服务商中文名（UI 提示用）。 */
    public static String providerLabel(McpProvider p) {
        switch (p) {
            case ALIYUN:
                return "阿里云系";
            case TENCENT:
                return "腾讯系";
            case MODELSCOPE:
                return "魔搭（ModelScope）";
            default:
                return "未知/通用";
        }
    }

    /** 强制重新同步单个 server。 */
    public void refreshServer(String id) {
        syncExecutor.execute(() -> {
            for (McpServerConfig cfg : store.load()) {
                if (cfg.id.equals(id) && cfg.enabled) {
                    syncServer(cfg);
                    return;
                }
            }
            AILogger.w(TAG, "refreshServer: 未找到启用的 server " + id);
        });
    }

    /** 全量刷新：重新同步所有启用的 server（管理页「全部刷新」用）。 */
    public void refreshAll() {
        syncExecutor.execute(this::syncAll);
    }

    private void removeRegisteredTools(String serverId) {
        try {
            OnlineToolRegistry registry = OnlineToolManager.getInstance(appContext).getRegistry();
            Set<String> old = registeredTools.remove(serverId);
            if (old != null) {
                for (String name : old) {
                    try {
                        registry.unregisterTool(name);
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception e) {
            AILogger.w(TAG, "removeRegisteredTools failed: " + e.getMessage());
        }
    }

    private static String abbreviate(String message) {
        if (message == null) return "未知错误";
        return message.length() > 120 ? message.substring(0, 120) + "..." : message;
    }
}
