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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP 服务管理器（全局单例）—— MCP 基础设施的对外入口。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>从 {@link McpConfigStore} 加载服务配置，为每个启用的 server 维护一个 {@link McpClient}；</li>
 *   <li>把远端 {@code tools/list} 保存为目录；模型经 {@code mcp_toolbox.get} 命中某个工具时，
 *       才把该工具以 {@code mcp__{serverId}__{toolName}} 按需注册进 {@link OnlineToolRegistry}
 *       （deferred loading，避免几十上百个外部工具一次性挤进工具集）；</li>
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
    /** 由 OnlineToolManager 构造时注入：避免同步线程反查单例时发生递归构造 */
    private volatile OnlineToolRegistry registryRef;
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();
    /** 每个 server 当前已注册到 registry 的工具名（用于刷新时精确注销） */
    private final Map<String, Set<String>> registeredTools = new ConcurrentHashMap<>();
    /**
     * 每个 server 最近一次成功同步的工具目录（保留 description 与原始 inputSchema）。
     * mcp_toolbox 的 search/get 只读这里，不再去 registry 反查（registry 只有定义快照）。
     */
    private final Map<String, List<McpClient.McpToolInfo>> catalogs = new ConcurrentHashMap<>();
    /** 本地注册名 → 远端原始工具名：名称可能被规范化/截断，不能再靠字符串截取反推 */
    private final Map<String, String> localToRemote = new ConcurrentHashMap<>();
    /** 本地注册名 → serverId：serverId 本身可能含 "__"，不能只靠名字切分 */
    private final Map<String, String> localToServer = new ConcurrentHashMap<>();
    /** serverId + \0 + 远端名 → 本地注册名：目录搜索时避免 O(n²) 反查 */
    private final Map<String, String> remoteToLocal = new ConcurrentHashMap<>();
    /** serverId → 状态文案（ok:N / error:... / disabled） */
    private final Map<String, String> status = new ConcurrentHashMap<>();
    /** serverId → 最近一次因 list_changed 自动刷新的时间（防服务端高频通知打爆同步线程） */
    private final Map<String, Long> lastAutoRefresh = new ConcurrentHashMap<>();
    /** 首次全量同步完成的信号：应用刚启动时 mcp_toolbox 可短暂等待，避免搜到空目录 */
    private final Object syncLock = new Object();
    private volatile boolean initialSyncDone;
    private volatile long lastSyncAtMs;
    /** serverId → 最近一次同步令牌：配置变更/更新同步后，旧任务的网络结果必须丢弃 */
    private final Map<String, Long> syncTokens = new ConcurrentHashMap<>();
    /** 正在同步的 server 数（搜索结果为空时用于区分“真没有”与“还在拉”） */
    private final AtomicInteger activeSyncs = new AtomicInteger();
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
     * 注入在线工具注册表。必须在 {@link #init()} 之前调用；否则同步线程会通过
     * {@code OnlineToolManager.getInstance()} 反查，可能在后者构造完成前递归创建第二个实例。
     */
    public void attachRegistry(OnlineToolRegistry registry) {
        this.registryRef = registry;
    }

    private OnlineToolRegistry registry() {
        OnlineToolRegistry r = registryRef;
        if (r == null) {
            r = OnlineToolManager.getInstance(appContext).getRegistry();
            registryRef = r;
        }
        return r;
    }

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
                    synchronized (this) {
                        status.put(cfg.id, "disabled");
                        // 兜底：配置可能被外部直接改成停用，旧目录/按需注册的工具/连接都要撤掉
                        McpClient oldClient = clients.remove(cfg.id);
                        if (oldClient != null) oldClient.close();
                        syncTokens.remove(cfg.id);
                        lastAutoRefresh.remove(cfg.id);
                        removeRegisteredTools(cfg.id);
                        catalogs.remove(cfg.id);
                    }
                    continue;
                }
                syncServer(cfg);
            }
            lastSyncAtMs = System.currentTimeMillis();
        } catch (Exception e) {
            AILogger.e(TAG, "syncAll failed: " + e.getMessage(), e);
        } finally {
            synchronized (syncLock) {
                initialSyncDone = true;
                syncLock.notifyAll();
            }
        }
    }

    /** 同步单个 server：初始化连接 → listTools → 安装目录（工具按需注册，不一次全塞进 registry）。 */
    private void syncServer(McpServerConfig cfg) {
        activeSyncs.incrementAndGet();
        long token = System.nanoTime();
        syncTokens.put(cfg.id, token);
        try {
            status.put(cfg.id, "syncing");
            McpClient client = clients.computeIfAbsent(cfg.id,
                    id -> {
                        McpClient c = new McpClient(resolvedCfg(cfg));
                        // 服务端声明 tools.listChanged 时会推通知；这里做 3s 防抖后后台重拉
                        c.setToolsChangedListener(() -> scheduleToolsRefresh(id));
                        return c;
                    });
            client.initialize();
            List<McpClient.McpToolInfo> tools = client.listTools();
            if (!installCatalog(cfg, tools, token)) {
                AILogger.i(TAG, "丢弃过期同步结果: " + cfg.id);
                return;
            }
            status.put(cfg.id, "ok:" + tools.size());
            AILogger.i(TAG, "MCP server synced: " + cfg.id + " (" + tools.size() + " tools, lazy registration)");
        } catch (Exception e) {
            if (isCurrentSync(cfg.id, token)) {
                status.put(cfg.id, "error:" + abbreviate(e.getMessage()));
                AILogger.e(TAG, "syncServer failed (" + cfg.id + "): " + e.getMessage(), e);
            }
        } finally {
            activeSyncs.decrementAndGet();
            synchronized (syncLock) {
                syncLock.notifyAll();
            }
        }
    }

    private boolean isCurrentSync(String serverId, long token) {
        Long current = syncTokens.get(serverId);
        return current != null && current == token;
    }

    /**
     * 安装目录：先注销该 server 之前按需注册的工具，再为全部工具分配稳定的本地名。
     *
     * <p><b>为什么不在这里全量注册</b>：注册表是给模型直调用的，schema 会进入后续请求；
     * 一个 server 几十上百个工具全注册，会让 tool_registry 目录和内存都被外部工具淹掉。
     * 这里只保留“目录 + 名字映射”，模型经 {@code mcp_toolbox.get} 命中某个工具时才
     * {@link #ensureRegistered} 注册那一个（与业界 deferred loading 对齐）。</p>
     */
    private synchronized boolean installCatalog(McpServerConfig cfg,
                                                List<McpClient.McpToolInfo> tools,
                                                long token) {
        // 进入锁后再校验一次：防止“网络返回 → 配置被改删 → 这里回填旧目录”的竞态
        if (!isCurrentSync(cfg.id, token)) return false;
        // 1) 注销上一轮已按需注册的工具，并清掉该 server 的名字映射
        Set<String> old = registeredTools.remove(cfg.id);
        if (old != null && !old.isEmpty()) {
            // 只使用已注入的 registry：不要在持有 McpManager 锁时去构造 OnlineToolManager，
            // 否则会和“OnlineToolManager 构造 → 调 McpManager.init”形成锁顺序反转
            OnlineToolRegistry registry = registryRef;
            if (registry != null) {
                for (String name : old) {
                    try {
                        registry.unregisterTool(name);
                    } catch (Exception ignored) {
                    }
                }
            }
            for (String name : old) {
                localToRemote.remove(name);
                localToServer.remove(name);
            }
        }
        List<String> staleLocals = new ArrayList<>();
        for (Map.Entry<String, String> e : localToServer.entrySet()) {
            if (cfg.id.equals(e.getValue())) staleLocals.add(e.getKey());
        }
        for (String name : staleLocals) {
            localToRemote.remove(name);
            localToServer.remove(name);
        }
        String keyPrefix = cfg.id + "\u0000";
        List<String> staleRemoteKeys = new ArrayList<>();
        for (String k : remoteToLocal.keySet()) {
            if (k.startsWith(keyPrefix)) staleRemoteKeys.add(k);
        }
        for (String k : staleRemoteKeys) remoteToLocal.remove(k);

        // 2) 为目录内每个工具分配本地名（不注册）；名字稳定，后续 ensureRegistered 直接用
        Set<String> used = new HashSet<>();
        for (McpClient.McpToolInfo t : tools) {
            String localName = localNameFor(cfg.id, t.name, used);
            used.add(localName);
            localToRemote.put(localName, t.name);
            localToServer.put(localName, cfg.id);
            remoteToLocal.put(remoteKey(cfg.id, t.name), localName);
        }
        catalogs.put(cfg.id, new ArrayList<>(tools));
        return true;
    }

    private static String remoteKey(String serverId, String remoteName) {
        return serverId + "\u0000" + remoteName;
    }

    /**
     * 本地注册名：{@code mcp__{serverId}__{规范化后的远端名}}，总长 ≤ 64。
     *
     * <p>远端工具名是外部输入，可能带点号/斜杠/中文或超过 OpenAI function 名限制；
     * 直接注册会被服务商拒绝或截断后撞名。这里统一替换非法字符并在同 server 内加后缀去重，
     * 真实远端名保存在 {@link #localToRemote}，执行时映射回去。</p>
     */
    private String localNameFor(String serverId, String remoteToolName, Set<String> usedInThisSync) {
        // serverId 也参与 OpenAI function 名：规范化并给远端名留至少 8 字符空间
        String sidPart = sanitizeName(serverId);
        if (sidPart.isEmpty()) sidPart = "server";
        if (sidPart.length() > 40) sidPart = sidPart.substring(0, 40);
        String prefix = McpConstants.TOOL_PREFIX + sidPart + "__";
        int maxPart = Math.max(8, McpConstants.MAX_TOOL_NAME_LENGTH - prefix.length());
        String base = sanitizeName(remoteToolName);
        if (base.isEmpty()) base = "tool";
        if (base.length() > maxPart) base = base.substring(0, maxPart);
        String candidate = prefix + base;
        int suffix = 2;
        while (usedInThisSync.contains(candidate) || localToRemote.containsKey(candidate)) {
            String tail = "_" + suffix++;
            int keep = Math.max(1, maxPart - tail.length());
            String head = base.length() > keep ? base.substring(0, keep) : base;
            candidate = prefix + head + tail;
        }
        return candidate;
    }

    private static String argsPreview(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) return "";
        try {
            String s = String.valueOf(arguments);
            return s.length() > 600 ? s.substring(0, 600) + "…" : s;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 参数值转 JSON：模型可能把嵌套对象/数组以原生 Map/List 传进来，
     * 直接 String.valueOf 会变成 "{a=b}" 这种非法 JSON，tools/call 必然解析失败。
     */
    private static Object toJsonValue(Object v) throws org.json.JSONException {
        if (v == null) return JSONObject.NULL;
        if (v instanceof JSONObject || v instanceof org.json.JSONArray
                || v instanceof String || v instanceof Number || v instanceof Boolean) {
            return v;
        }
        if (v instanceof Map) {
            JSONObject o = new JSONObject();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                o.put(String.valueOf(e.getKey()), toJsonValue(e.getValue()));
            }
            return o;
        }
        if (v instanceof Iterable) {
            org.json.JSONArray a = new org.json.JSONArray();
            for (Object item : (Iterable<?>) v) a.put(toJsonValue(item));
            return a;
        }
        return String.valueOf(v);
    }

    /** 只保留 OpenAI function 名允许的字符，并把连续非法字符压成一个下划线。 */
    private static String sanitizeName(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        boolean lastUnderscore = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (ok) {
                sb.append(c);
                lastUnderscore = c == '_';
            } else if (!lastUnderscore) {
                sb.append('_');
                lastUnderscore = true;
            }
        }
        String out = sb.toString();
        while (out.startsWith("_")) out = out.substring(1);
        while (out.endsWith("_")) out = out.substring(0, out.length() - 1);
        return out;
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
            // 优先用注册映射：serverId 本身可能含 "__"，规范化也可能改了名字
            String serverId = localToServer.get(toolName);
            String remoteName = localToRemote.get(toolName);
            if (serverId == null) {
                serverId = toolName.substring(McpConstants.TOOL_PREFIX.length(), firstSep);
            }
            if (remoteName == null) {
                remoteName = toolName.substring(firstSep + 2);
            }
            return executeRemote(serverId, remoteName, arguments, toolName);
        } catch (Exception e) {
            AILogger.e(TAG, "execute failed: " + toolName, e);
            return AIToolResult.fail("MCP 工具执行异常: " + e.getMessage(), info);
        }
    }

    /**
     * 按 serverId + 远端原始名执行（mcp_toolbox 用）。
     * 与 {@link #execute} 共用同一段转发逻辑，避免两套错误口径。
     */
    public AIToolResult executeRemote(String serverId, String remoteName,
                                      Map<String, Object> arguments) {
        return executeRemote(serverId, remoteName, arguments,
                McpConstants.TOOL_PREFIX + serverId + "__" + remoteName);
    }

    private AIToolResult executeRemote(String serverId, String remoteName,
                                       Map<String, Object> arguments, String displayName) {
        Map<String, Object> info = new HashMap<>();
        info.put("toolName", displayName);
        info.put("serverId", serverId);
        info.put("remoteTool", remoteName);
        try {
            McpClient client = clients.get(serverId);
            if (client == null) {
                return AIToolResult.fail("MCP server 未连接: " + serverId
                        + "（请先在 MCP 管理页启用并同步）", info);
            }
            try {
                client.initialize(); // 幂等
            } catch (Exception e) {
                return AIToolResult.fail("MCP server 初始化失败: " + e.getMessage(), info);
            }

            // 逐工具审批：只读工具自动；destructive/无注解默认首次询问；
            // 用户可选“始终允许”写入配置（auto），或设为 deny 直接阻断。
            McpToolEntry entry = findTool(serverId, remoteName);
            String approval = effectiveApproval(serverId, remoteName);
            if (McpConstants.APPROVAL_DENY.equals(approval)) {
                return AIToolResult.fail("MCP 工具已被禁止执行: " + serverId + "/" + remoteName
                        + "（可在 MCP 管理页「工具 → 审批策略」改为询问/自动）", info);
            }
            if (McpConstants.APPROVAL_ASK.equals(approval)) {
                McpApprovalManager.Result ar = McpApprovalManager.request(
                        entry != null ? entry.serverName : serverId,
                        remoteName,
                        entry != null ? entry.description : "",
                        argsPreview(arguments));
                if (ar.decision == McpApprovalManager.Decision.DENY) {
                    return AIToolResult.fail("MCP 工具未执行：" + ar.reason, info);
                }
                if (ar.decision == McpApprovalManager.Decision.ALLOW_ALWAYS) {
                    setToolApproval(serverId, remoteName, McpConstants.APPROVAL_AUTO);
                }
            }

            JSONObject argsJson = new JSONObject();
            if (arguments != null) {
                for (Map.Entry<String, Object> e : arguments.entrySet()) {
                    Object v = e.getValue();
                    if (v == null) continue;
                    argsJson.put(e.getKey(), toJsonValue(v));
                }
            }
            McpClient.McpCallResult result = client.callTool(remoteName, argsJson);
            if (result.isError) {
                return AIToolResult.fail("MCP 工具执行失败: " + result.text, info);
            }
            return AIToolResult.success(result.text, info);
        } catch (Exception e) {
            AILogger.e(TAG, "executeRemote failed: " + serverId + "/" + remoteName, e);
            String message = e.getMessage() != null ? e.getMessage() : e.toString();
            if (message.contains("HTTP 401") || message.contains("HTTP 403")) {
                message += "（鉴权失败：到 MCP 管理页检查该服务的 🔑 令牌引用）";
            } else if (message.contains("HTTP 404")) {
                message += "（端点不存在：检查 URL 与传输类型是否匹配）";
            }
            return AIToolResult.fail("MCP 工具执行异常: " + message, info);
        }
    }

    // ==================== 工具目录（mcp_toolbox 的搜索源） ====================

    /** 一个已同步的远端工具（含 server 信息），供 toolbox search/get 使用。 */
    public static class McpToolEntry {
        public final String serverId;
        public final String serverName;
        public final String remoteName;
        public final String localName;
        public final String description;
        public final JSONObject inputSchema;
        public final JSONObject annotations;
        public final String serverStatus;

        McpToolEntry(String serverId, String serverName, String remoteName, String localName,
                     String description, JSONObject inputSchema, JSONObject annotations,
                     String serverStatus) {
            this.serverId = serverId;
            this.serverName = serverName;
            this.remoteName = remoteName;
            this.localName = localName;
            this.description = description != null ? description : "";
            this.inputSchema = inputSchema != null ? inputSchema : new JSONObject();
            this.annotations = annotations;
            this.serverStatus = serverStatus != null ? serverStatus : "";
        }

        public Map<String, Object> toSearchItem() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("server", serverName);
            m.put("server_id", serverId);
            m.put("tool", remoteName);
            m.put("description", abbreviateText(description, 360));
            if (annotations != null) {
                if (annotations.has("readOnlyHint")) m.put("read_only", annotations.optBoolean("readOnlyHint"));
                if (annotations.has("destructiveHint"))
                    m.put("destructive", annotations.optBoolean("destructiveHint"));
            }
            return m;
        }
    }

    /** 当前全部已同步工具的快照（按 server 名 + 工具名排序，保证输出稳定）。 */
    public List<McpToolEntry> catalogSnapshot() {
        List<McpToolEntry> out = new ArrayList<>();
        Map<String, McpServerConfig> cfgById = new HashMap<>();
        for (McpServerConfig cfg : store.load()) cfgById.put(cfg.id, cfg);
        for (Map.Entry<String, List<McpClient.McpToolInfo>> e : catalogs.entrySet()) {
            String serverId = e.getKey();
            McpServerConfig cfg = cfgById.get(serverId);
            String serverName = cfg != null ? cfg.name : serverId;
            String st = status.getOrDefault(serverId, "");
            if ("disabled".equals(st)) continue; // 防御：停用服务的目录不应再暴露给搜索
            for (McpClient.McpToolInfo t : e.getValue()) {
                out.add(new McpToolEntry(serverId, serverName, t.name,
                        localNameForLookup(serverId, t.name), t.description,
                        t.inputSchema, t.annotations, st));
            }
        }
        out.sort(Comparator.comparing((McpToolEntry x) -> x.serverName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(x -> x.remoteName, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /**
     * 按关键词搜索已同步工具。多个词按 AND 匹配（名称/描述/server 名都参与），
     * 精确名 > 名称包含 > server 名 > 描述；空关键词返回目录前 N 个。
     */
    public List<McpToolEntry> searchTools(String keyword, int limit) {
        int max = limit <= 0 ? 8 : Math.min(limit, 20);
        String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        String[] terms = kw.isEmpty() ? new String[0] : kw.split("\\s+");
        List<ScoredEntry> scored = new ArrayList<>();
        for (McpToolEntry e : catalogSnapshot()) {
            String name = e.remoteName.toLowerCase(Locale.ROOT);
            String desc = e.description.toLowerCase(Locale.ROOT);
            String server = (e.serverName + " " + e.serverId).toLowerCase(Locale.ROOT);
            String hay = name + " " + desc + " " + server;
            boolean all = true;
            for (String term : terms) {
                if (!hay.contains(term)) {
                    all = false;
                    break;
                }
            }
            if (!all) continue;
            int score = 0;
            if (!kw.isEmpty()) {
                if (name.equals(kw)) score += 100;
                else if (name.contains(kw)) score += 60;
                if (server.contains(kw)) score += 30;
                if (desc.contains(kw)) score += 20;
            }
            scored.add(new ScoredEntry(e, score));
        }
        scored.sort((a, b) -> {
            int c = Integer.compare(b.score, a.score);
            if (c != 0) return c;
            c = a.entry.serverName.compareToIgnoreCase(b.entry.serverName);
            if (c != 0) return c;
            return a.entry.remoteName.compareToIgnoreCase(b.entry.remoteName);
        });
        List<McpToolEntry> out = new ArrayList<>();
        for (ScoredEntry s : scored) {
            if (out.size() >= max) break;
            out.add(s.entry);
        }
        return out;
    }

    private static class ScoredEntry {
        final McpToolEntry entry;
        final int score;

        ScoredEntry(McpToolEntry entry, int score) {
            this.entry = entry;
            this.score = score;
        }
    }

    /**
     * 解析一个工具：server 可省略；tool 支持远端名、本地注册名、以及 {@code server:tool}。
     * 找不到或跨 server 重名（无法判定）时返回 null，由调用方列出候选。
     */
    public McpToolEntry findTool(String serverHint, String toolHint) {
        String server = serverHint == null ? "" : serverHint.trim();
        String tool = toolHint == null ? "" : toolHint.trim();
        if (tool.isEmpty()) return null;

        // “server:tool” 合并写法：仅当冒号左边能匹配到已知 server 时才拆
        if (server.isEmpty()) {
            int colon = tool.indexOf(':');
            if (colon > 0) {
                String left = tool.substring(0, colon).trim();
                if (matchesServer(left)) {
                    server = left;
                    tool = tool.substring(colon + 1).trim();
                }
            }
        }
        if (tool.isEmpty()) return null;

        List<McpToolEntry> exact = new ArrayList<>();
        List<McpToolEntry> contains = new ArrayList<>();
        String t = tool.toLowerCase(Locale.ROOT);
        for (McpToolEntry e : catalogSnapshot()) {
            if (!server.isEmpty() && !serverMatches(e, server)) continue;
            String rn = e.remoteName.toLowerCase(Locale.ROOT);
            String ln = e.localName.toLowerCase(Locale.ROOT);
            if (rn.equals(t) || ln.equals(t)) {
                exact.add(e);
            } else if (rn.contains(t) || ln.contains(t)) {
                contains.add(e);
            }
        }
        if (exact.size() == 1) return exact.get(0);
        if (exact.size() > 1) return null; // 重名，必须带 server
        if (contains.size() == 1) return contains.get(0);
        return null;
    }

    /** 工具短名 → 本地注册名（引擎在 toolbox get 成功后把该工具注入本轮工具集）。 */
    public String localNameFor(String serverHint, String toolHint) {
        McpToolEntry e = findTool(serverHint, toolHint);
        return e != null ? e.localName : null;
    }

    /** 显式审批策略；未设置返回空串（由注解推断）。 */
    public String getToolApproval(String serverId, String remoteName) {
        for (McpServerConfig cfg : store.load()) {
            if (cfg.id.equals(serverId)) return cfg.approvalFor(remoteName);
        }
        return "";
    }

    /**
     * 设置/清除某工具的审批策略（auto/ask/deny；空或 default = 跟随注解）。
     * 只改配置，不重建连接、不触发同步。
     */
    public synchronized void setToolApproval(String serverId, String remoteName, String policy) {
        if (serverId == null || remoteName == null || remoteName.isEmpty()) return;
        String p = (policy == null || "default".equalsIgnoreCase(policy)) ? "" : policy;
        if (!p.isEmpty()) p = normalizeApproval(p);
        for (McpServerConfig cfg : store.load()) {
            if (cfg.id.equals(serverId)) {
                store.upsert(cfg.withToolApproval(remoteName, p));
                AILogger.audit(TAG, "MCP approval set " + serverId + "/" + remoteName
                        + " -> " + (p.isEmpty() ? "default" : p));
                return;
            }
        }
    }

    /** UI 列表用：直接按目录里的 McpToolInfo 推断，避免每个工具都重建整张目录。 */
    public String effectiveApproval(String serverId, McpClient.McpToolInfo info) {
        if (info == null) return McpConstants.APPROVAL_ASK;
        String explicit = getToolApproval(serverId, info.name);
        if (!explicit.isEmpty()) return normalizeApproval(explicit);
        return inferApproval(info.annotations);
    }

    private static String normalizeApproval(String policy) {
        if (McpConstants.APPROVAL_AUTO.equals(policy)
                || McpConstants.APPROVAL_ASK.equals(policy)
                || McpConstants.APPROVAL_DENY.equals(policy)) {
            return policy;
        }
        // 配置被手工改坏/旧版本遗留的未知值：退回最安全的“询问”，不能当自动放行
        return McpConstants.APPROVAL_ASK;
    }

    static String inferApproval(JSONObject annotations) {
        if (annotations != null) {
            if (annotations.has("readOnlyHint") && annotations.optBoolean("readOnlyHint")) {
                return McpConstants.APPROVAL_AUTO;
            }
            if (annotations.has("destructiveHint") && annotations.optBoolean("destructiveHint")) {
                return McpConstants.APPROVAL_ASK;
            }
            if (annotations.has("readOnlyHint") && !annotations.optBoolean("readOnlyHint")) {
                return McpConstants.APPROVAL_ASK;
            }
        }
        // 没有注解时不能假设只读：首次调用确认一次，用户可选“始终允许”
        return McpConstants.APPROVAL_ASK;
    }

    /** 按 serverId + 远端名查审批策略（UI/测试/执行用）。 */
    public String effectiveApproval(String serverId, String remoteName) {
        // 显式 deny/auto 必须优先于目录：即使目录没同步到，也不能让 deny 失效
        String explicit = getToolApproval(serverId, remoteName);
        if (!explicit.isEmpty()) return normalizeApproval(explicit);
        McpToolEntry e = findTool(serverId, remoteName);
        return e != null ? inferApproval(e.annotations) : McpConstants.APPROVAL_ASK;
    }

    /**
     * 按需把一个目录工具注册进 OnlineToolRegistry（deferred loading 的落地）。
     * 已注册直接返回；注册失败返回 null（search/get/call 仍可用，只是不能“下一轮直调”）。
     */
    public String ensureRegistered(McpToolEntry e) {
        if (e == null || e.localName == null || e.localName.isEmpty()) return null;
        if ("disabled".equals(e.serverStatus)) return null;
        // 先在锁外拿到 registry：OnlineToolManager 构造会回调 McpManager.init，锁内调用会死锁
        OnlineToolRegistry registry = registry();
        synchronized (this) {
            // 目录可能刚被重新安装：旧 entry 的本地名已失效就不要注册
            String currentLocal = remoteToLocal.get(remoteKey(e.serverId, e.remoteName));
            if (currentLocal == null || !currentLocal.equals(e.localName)) return null;
            Set<String> set = registeredTools.computeIfAbsent(
                    e.serverId, k -> java.util.Collections.synchronizedSet(new HashSet<>()));
            if (set.contains(e.localName)) return e.localName;
            try {
                McpClient.McpToolInfo info = new McpClient.McpToolInfo(
                        e.remoteName, e.description, e.inputSchema, e.annotations);
                ToolDefinition def = buildToolDefinition(e.localName, info);
                boolean ok = registry.registerTool(
                        e.localName, e.description, McpConstants.TOOL_CATEGORY, def);
                if (!ok) {
                    AILogger.w(TAG, "MCP 按需注册失败（可能重名）: " + e.localName);
                    return null;
                }
                set.add(e.localName);
                localToRemote.put(e.localName, e.remoteName);
                localToServer.put(e.localName, e.serverId);
                remoteToLocal.put(remoteKey(e.serverId, e.remoteName), e.localName);
                return e.localName;
            } catch (Exception ex) {
                AILogger.w(TAG, "MCP 按需注册异常: " + e.localName + " - " + ex.getMessage());
                return null;
            }
        }
    }

    private boolean matchesServer(String hint) {
        String h = hint.trim().toLowerCase(Locale.ROOT);
        if (h.isEmpty()) return false;
        for (McpServerConfig cfg : store.load()) {
            if (cfg.id.equalsIgnoreCase(h) || cfg.name.equalsIgnoreCase(h)) return true;
        }
        return false;
    }

    private static boolean serverMatches(McpToolEntry e, String hint) {
        if (e.serverId.equalsIgnoreCase(hint) || e.serverName.equalsIgnoreCase(hint)) return true;
        // 允许"阿里云"这类部分名；多命中时 findTool 会因歧义返回 null，不会乱选
        return hint.trim().length() >= 2
                && e.serverName.toLowerCase(Locale.ROOT).contains(hint.trim().toLowerCase(Locale.ROOT));
    }

    /** 某个 server 的已同步目录（管理页展示用）。 */
    public List<McpClient.McpToolInfo> getCatalogForServer(String serverId) {
        List<McpClient.McpToolInfo> list = catalogs.get(serverId);
        return list != null ? new ArrayList<>(list) : new ArrayList<>();
    }

    /** 所有 server 的状态摘要（toolbox 空状态/模型判断下一步用）。 */
    public List<Map<String, Object>> serverSummaries() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (McpServerConfig cfg : store.load()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("server_id", cfg.id);
            m.put("server", cfg.name);
            m.put("enabled", cfg.enabled);
            m.put("transport", cfg.transport != null ? cfg.transport.name() : "STREAMABLE_HTTP");
            String st = status.getOrDefault(cfg.id, cfg.enabled ? "未同步" : "disabled");
            m.put("status", st);
            List<McpClient.McpToolInfo> list = catalogs.get(cfg.id);
            m.put("tool_count", list != null ? list.size() : 0);
            out.add(m);
        }
        return out;
    }

    /**
     * 等待首次全量同步（应用刚启动时 mcp_toolbox 可能先于网络同步被调用）。
     * 只等首次；后续 refresh 不阻塞工具调用。
     */
    public boolean awaitInitialSync(long timeoutMs) {
        if (initialSyncDone) return true;
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
        synchronized (syncLock) {
            while (!initialSyncDone) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) break;
                try {
                    syncLock.wait(remain);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return initialSyncDone;
        }
    }

    public long getLastSyncAtMs() {
        return lastSyncAtMs;
    }

    /** 是否有 server 正在后台连接/拉工具（toolbox 用来区分“真没有”和“还没拉完”）。 */
    public boolean isSyncing() {
        return activeSyncs.get() > 0;
    }

    private static String abbreviateText(String s, int max) {
        if (s == null) return "";
        String oneLine = s.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() > max ? oneLine.substring(0, max) + "…" : oneLine;
    }

    private String localNameForLookup(String serverId, String remoteName) {
        String v = remoteToLocal.get(remoteKey(serverId, remoteName));
        return v != null ? v : "";
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
    public synchronized McpServerConfig upsertServer(McpServerConfig cfg) {
        if (cfg == null || cfg.id == null || cfg.id.isEmpty()) {
            AILogger.w(TAG, "拒绝保存无效 MCP 配置（缺 id）");
            return cfg;
        }
        McpServerConfig saved = store.upsert(cfg);
        McpClient old = clients.remove(cfg.id); // 配置变化后重建连接
        if (old != null) old.close();
        // 先让在跑的旧同步失效，再清目录；顺序反了会被旧结果回填
        syncTokens.remove(cfg.id);
        removeRegisteredTools(cfg.id);
        catalogs.remove(cfg.id);
        lastAutoRefresh.remove(cfg.id);
        if (cfg.enabled) {
            status.put(cfg.id, "syncing");
            syncExecutor.execute(() -> syncServer(store.load().stream()
                    .filter(c -> c.id.equals(cfg.id)).findFirst().orElse(cfg)));
        } else {
            status.put(cfg.id, "disabled");
        }
        return saved;
    }

    /** 删除 server（同时注销其工具）。 */
    public synchronized boolean removeServer(String id) {
        boolean removed = store.remove(id);
        McpClient client = clients.remove(id);
        if (client != null) client.close();
        status.remove(id);
        catalogs.remove(id);
        lastAutoRefresh.remove(id);
        syncTokens.remove(id); // 旧同步结果不得回填已删除的 server
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

    /** 密钥分支保存/删除后调用：所有引用该键名的 server 重建连接，立即用上新值。 */
    public void refreshTokenRef(String tokenRef) {
        if (tokenRef == null || tokenRef.trim().isEmpty()) return;
        for (McpServerConfig cfg : store.load()) {
            if (tokenRef.equals(cfg.tokenRef)) refreshServer(cfg.id);
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
                // 设为新引用时清掉旧的直接令牌：resolveToken 优先 token，不清会永远用旧值
                McpServerConfig next = (tokenRef != null && !tokenRef.isEmpty())
                        ? cfg.withToken("") : cfg;
                upsertServer(next.withTokenRef(tokenRef));
                return;
            }
        }
    }

    /**
     * 解析一个 server 实际使用的令牌（明文 token 优先；空则按 tokenRef 从密钥库取）。
     * 外部（管理页展示 / 排查）可调用。
     */
    public String resolveToken(McpServerConfig cfg) {
        if (cfg == null) return "";
        if (cfg.token != null && !cfg.token.isEmpty() && !cfg.token.startsWith("enc:")) {
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
        if (cfg.token != null && !cfg.token.isEmpty() && !cfg.token.startsWith("enc:")) return cfg;
        // 解密失败的 enc: 串不能当 Bearer 发出去，按空令牌处理并尝试 tokenRef
        McpServerConfig base = (cfg.token != null && cfg.token.startsWith("enc:"))
                ? cfg.withToken("") : cfg;
        String resolved = resolveToken(base);
        return (resolved.isEmpty() || resolved.startsWith("enc:")) ? base : base.withToken(resolved);
    }

    // ==================== 密钥与服务的匹配 ====================

    /** MCP 服务所属服务商（用于推荐匹配的密钥，避免"乱引"不兼容的 key）。 */
    public enum McpProvider { ALIYUN, TENCENT, MODELSCOPE, UNKNOWN }

    /**
     * 按 server 的 URL / 名称推断服务商。无法判定返回 UNKNOWN
     * （此时引用全部密钥都只是"试试"，必须提示用户确认）。
     */
    public McpProvider guessProvider(McpServerConfig cfg) {
        if (cfg == null) return McpProvider.UNKNOWN;
        String text = ((cfg.name != null ? cfg.name : "") + " "
                + (cfg.url != null ? cfg.url : "")).toLowerCase();
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

    /** 强制重新同步单个 server（同时重建连接，确保 tokenRef 指向的新密钥生效）。 */
    public synchronized void refreshServer(String id) {
        for (McpServerConfig cfg : store.load()) {
            if (cfg.id.equals(id) && cfg.enabled) {
                status.put(id, "syncing");
                syncTokens.remove(id); // 旧同步结果不得回填本次刷新
                syncExecutor.execute(() -> {
                    McpClient old = clients.remove(id);
                    if (old != null) old.close();
                    syncServer(cfg);
                });
                return;
            }
        }
        AILogger.w(TAG, "refreshServer: 未找到启用的 server " + id);
    }

    /**
     * 服务端 {@code notifications/tools/list_changed} → 后台重拉目录。
     * 3 秒防抖：服务端可能连续推多次，同步线程只有一条，没必要每次都跑。
     */
    private void scheduleToolsRefresh(String serverId) {
        long now = System.currentTimeMillis();
        Long last = lastAutoRefresh.get(serverId);
        if (last != null && now - last < 3000L) return;
        lastAutoRefresh.put(serverId, now);
        AILogger.i(TAG, "MCP tools/list_changed，准备重新同步: " + serverId);
        syncExecutor.execute(() -> {
            for (McpServerConfig cfg : store.load()) {
                if (cfg.id.equals(serverId) && cfg.enabled) {
                    syncServer(cfg);
                    return;
                }
            }
        });
    }

    /** 全量刷新：重建全部连接并重新同步（管理页「全部刷新」用，密钥改动也会生效）。 */
    public synchronized void refreshAll() {
        for (McpServerConfig cfg : store.load()) {
            if (cfg.enabled) {
                status.put(cfg.id, "syncing");
                syncTokens.remove(cfg.id); // 让刷新前仍在跑的旧同步失效
            }
        }
        syncExecutor.execute(() -> {
            for (McpClient c : clients.values()) {
                try {
                    c.close();
                } catch (Exception ignored) {
                }
            }
            clients.clear();
            syncAll();
        });
    }

    /**
     * 市场安装向导用的"测一下"：用一份临时配置跑 initialize + tools/list，
     * 只报告结果，<b>不建长连接、不写目录、不注册工具、不落盘</b>。
     *
     * <p>为什么单独做而不复用 {@link #syncServer}：同步会装目录、改状态、留连接，
     * 拿它当校验就意味着"还没确认能用就先污染运行态"。安装向导要的是
     * 一个纯读探针——通过了才由调用方显式启用（与"持久化要显式选择"一致）。</p>
     *
     * <p>会发起真实网络请求，必须在后台线程调用。</p>
     */
    public ProbeResult probe(McpServerConfig cfg) {
        if (cfg == null) return ProbeResult.fail("配置为空");
        if (!McpServerConfig.isUsableEndpoint(cfg.url)) {
            return ProbeResult.fail("端点不可用（需 http(s) 且不含 {占位符}）："
                    + McpServerConfig.maskUrl(cfg.url));
        }
        McpClient client = null;
        try {
            client = new McpClient(resolvedCfg(cfg));
            client.initialize();
            List<McpClient.McpToolInfo> tools = client.listTools();
            List<String> names = new ArrayList<>();
            for (McpClient.McpToolInfo t : tools) {
                names.add(t.name);
            }
            AILogger.i(TAG, "MCP 探测通过: " + cfg.id + " tools=" + tools.size());
            return ProbeResult.ok(tools.size(), names, client.getServerInfoText(),
                    client.getNegotiatedProtocolVersion());
        } catch (Exception e) {
            AILogger.w(TAG, "MCP 探测失败 (" + cfg.id + "): " + e.getMessage());
            return ProbeResult.fail(abbreviate(e.getMessage()));
        } finally {
            if (client != null) {
                try {
                    client.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** {@link #probe} 的结果。 */
    public static final class ProbeResult {
        public final boolean ok;
        public final int toolCount;
        public final List<String> toolNames;
        /** 服务端自报 serverInfo（name + version） */
        public final String serverInfo;
        public final String protocolVersion;
        public final String error;

        private ProbeResult(boolean ok, int toolCount, List<String> toolNames,
                            String serverInfo, String protocolVersion, String error) {
            this.ok = ok;
            this.toolCount = toolCount;
            this.toolNames = toolNames == null
                    ? Collections.<String>emptyList() : Collections.unmodifiableList(toolNames);
            this.serverInfo = serverInfo == null ? "" : serverInfo;
            this.protocolVersion = protocolVersion == null ? "" : protocolVersion;
            this.error = error == null ? "" : error;
        }

        static ProbeResult ok(int toolCount, List<String> toolNames,
                              String serverInfo, String protocolVersion) {
            return new ProbeResult(true, toolCount, toolNames, serverInfo, protocolVersion, "");
        }

        static ProbeResult fail(String error) {
            return new ProbeResult(false, 0, new ArrayList<String>(), "", "", error);
        }
    }

    private void removeRegisteredTools(String serverId) {
        Set<String> old = registeredTools.remove(serverId);
        // 不在这里构造 OnlineToolManager：本方法可能被持有 McpManager 锁的配置路径调用
        OnlineToolRegistry registry = registryRef;
        if (old != null && !old.isEmpty() && registry != null) {
            for (String name : old) {
                try {
                    registry.unregisterTool(name);
                } catch (Exception ignored) {
                }
            }
        }
        // 名字映射无论有没有“按需注册”过都要清：目录已删，旧名字不应再能解析
        List<String> staleLocals = new ArrayList<>();
        for (Map.Entry<String, String> e : localToServer.entrySet()) {
            if (serverId.equals(e.getValue())) staleLocals.add(e.getKey());
        }
        for (String name : staleLocals) {
            localToRemote.remove(name);
            localToServer.remove(name);
        }
        String keyPrefix = serverId + "\u0000";
        List<String> staleRemoteKeys = new ArrayList<>();
        for (String k : remoteToLocal.keySet()) {
            if (k.startsWith(keyPrefix)) staleRemoteKeys.add(k);
        }
        for (String k : staleRemoteKeys) remoteToLocal.remove(k);
    }

    private static String abbreviate(String message) {
        if (message == null) return "未知错误";
        return message.length() > 120 ? message.substring(0, 120) + "..." : message;
    }
}
