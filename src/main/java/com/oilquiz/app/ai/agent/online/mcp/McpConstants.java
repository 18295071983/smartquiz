package com.oilquiz.app.ai.agent.online.mcp;

/**
 * MCP（Model Context Protocol）协议常量。
 *
 * <p>协议文档：https://modelcontextprotocol.io —— 客户端与服务端通过 JSON-RPC 2.0 通信，
 * 传输层支持经典 SSE 与 streamable HTTP 两种形态。</p>
 */
public final class McpConstants {
    private McpConstants() {}

    public static final String JSON_RPC_VERSION = "2.0";

    /** 最新协议版本（支持 streamable HTTP + 会话复用） */
    public static final String PROTOCOL_VERSION_LATEST = "2025-06-18";
    /** 经典版本（SSE 传输） */
    public static final String PROTOCOL_VERSION_LEGACY = "2024-11-05";

    // 生命周期方法
    public static final String METHOD_INITIALIZE = "initialize";
    public static final String METHOD_NOTIFICATION_INITIALIZED = "notifications/initialized";
    public static final String METHOD_PING = "ping";

    // 工具方法
    public static final String METHOD_TOOLS_LIST = "tools/list";
    public static final String METHOD_TOOLS_CALL = "tools/call";
    /** 服务端工具列表变更通知（无 id，客户端收到后重新 tools/list） */
    public static final String NOTIFICATION_TOOLS_CHANGED = "notifications/tools/list_changed";

    // SSE 事件名
    public static final String SSE_EVENT_ENDPOINT = "endpoint";
    public static final String SSE_EVENT_MESSAGE = "message";
    /** SSE 流结束标记（streamable HTTP 侧） */
    public static final String SSE_DATA_DONE = "[DONE]";

    // Header
    public static final String HEADER_MCP_SESSION_ID = "Mcp-Session-Id";
    public static final String HEADER_MCP_PROTOCOL_VERSION = "MCP-Protocol-Version";
    public static final String HEADER_AUTHORIZATION = "Authorization";

    // 工具命名：mcp__{serverId}__{toolName}
    public static final String TOOL_PREFIX = "mcp__";
    /** OpenAI function 名上限 64；远端名字必须规范化后才能注册 */
    public static final int MAX_TOOL_NAME_LENGTH = 64;
    /** MCP 工具在 OnlineToolRegistry 中的类别 */
    public static final String TOOL_CATEGORY = "mcp";

    /** 单次工具调用默认超时（与 OnlineToolManager 的 30s 一致） */
    public static final long DEFAULT_TIMEOUT_MS = 30_000L;
    /** tools/call 通常比 list 慢（远端查询/计算），给 60s；外层 toolbox 申报 90s 兜住 */
    public static final long CALL_TIMEOUT_MS = 60_000L;
    /** 工具结果文本截断上限（防超大结果撑爆上下文，与 OnlineToolManager.RESULT_MAX_LENGTH 一致） */
    public static final int RESULT_MAX_LENGTH = 16 * 1024;

    // 逐工具审批策略
    public static final String APPROVAL_AUTO = "auto";
    public static final String APPROVAL_ASK = "ask";
    public static final String APPROVAL_DENY = "deny";
}
