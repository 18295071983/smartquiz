package com.oilquiz.app.ai.agent.online.mcp;

import org.json.JSONObject;

/**
 * MCP 传输层抽象。一个 {@link McpClient} 持有一个传输实例。
 *
 * <p>两种实现：</p>
 * <ul>
 *   <li>{@link SseMcpTransport} —— 经典 SSE：connect 建立 GET 事件流，send 走 POST message 端点，
 *       响应经 SSE 流回传；</li>
 *   <li>{@link StreamableHttpMcpTransport} —— streamable HTTP：send 直接 POST，响应为单 JSON
 *       或 SSE 流，会话经 {@code Mcp-Session-Id} header 复用。</li>
 * </ul>
 *
 * <p>所有实现都是同步语义（send 阻塞至拿到对应 id 的响应或超时），调用方须在后台线程使用；
 * 与 {@link com.oilquiz.app.ai.agent.online.OnlineToolManager} 的 TOOL_EXECUTOR 配合即可。</p>
 */
public interface McpTransport {

    /**
     * 建立底层连接（SSE 建流并启动读取线程；streamable HTTP 无持久连接，connect 为空操作）。
     * 幂等：重复调用不重复建流。
     */
    void connect() throws Exception;

    /**
     * 发送一条 JSON-RPC 请求并等待对应 id 的响应。
     *
     * @param request   待发送的请求（含唯一 id）
     * @param timeoutMs 等待响应的超时（毫秒）
     * @return 服务端响应消息（含 result 或 error 字段）；超时抛 {@link java.util.concurrent.TimeoutException}
     */
    JSONObject send(JSONObject request, long timeoutMs) throws Exception;

    /** 关闭连接（SSE 取消流、完成所有挂起等待）。 */
    void close();

    /** 连接是否处于可用状态。 */
    boolean isConnected();

    /**
     * 服务端通知回调（目前只用于 {@code notifications/tools/list_changed}）。
     * 默认忽略；实现必须快速返回，不要在读取线程里做网络/重同步。
     */
    default void setNotificationListener(Runnable listener) {
    }

    /**
     * 协商出的协议版本：streamable HTTP 的后续请求需带 {@code MCP-Protocol-Version}。
     * 默认忽略（SSE 不需要该 header）。
     */
    default void setProtocolVersion(String version) {
    }

    /**
     * 启动服务端主动通知流（目前是 streamable HTTP 的 GET SSE）。
     * SSE 传输本身已有长连事件流，默认空实现。
     */
    default void startNotifications() {
    }
}
