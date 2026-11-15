package com.oilquiz.app.ai.tool;

import android.content.Context;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

/**
 * RemoteSdk —— 手机端访问电脑端会话的唯一入口（插件协议 v2）。
 *
 * 设计约定（这次重构的核心）：
 *   1. **会话 id 由电脑签发**。客户端永远不自己发明 session id；
 *   2. 客户端只持有一个稳定的"对话码（code）"，换了手机、重装、清缓存，靠 code 就能接回同一条对话；
 *   3. 所有返回都用 {@link #sessionIdOf(Map)} 取权威 id，避免各处自己去翻 session_id / dsh_session_id 字段。
 *
 * 与旧代码的关系：RemoteDshTool 里的 legacy 动作继续可用（内部走同一批路由），
 * 新功能（view / session / ack / stream）统一从这里进，避免再出现"两套实现"。
 */
public final class RemoteSdk {

    private final RemoteDshTool tool;

    public RemoteSdk(Context c) {
        this.tool = new RemoteDshTool(c.getApplicationContext());
    }

    public boolean isConfigured() {
        String u = tool.getBaseUrl();
        return u != null && !u.isEmpty();
    }

    public String baseUrl() {
        return tool.getBaseUrl();
    }

    private Map<String, Object> call(String path, String method, Map<String, Object> body, int timeoutSec) throws Exception {
        if (!isConfigured()) {
            throw new Exception("未配置电脑端地址：先用「扫码配对」或 action=set_config");
        }
        return tool.httpJson(tool.getBaseUrl() + path, method, body, timeoutSec);
    }

    /** 握手 + 能力协商：带上客户端 caps，电脑端据此裁剪卡片/事件 */
    public Map<String, Object> hello(String sessionId) throws Exception {
        return call("/v2/hello", "POST", RemoteCaps.helloBody(sessionId), 15);
    }

    public Map<String, Object> hello() throws Exception {
        return hello("");
    }

    /** 查某个对话码当前绑定到电脑上的哪条会话（权威答案） */
    public Map<String, Object> sessionState(String code) throws Exception {
        String c = code == null ? "" : code.trim();
        String q = c.isEmpty() ? "" : "?code=" + URLEncoder.encode(c, "UTF-8");
        return call("/v2/session" + q, "GET", null, 15);
    }

    /** 列电脑上的会话（手机只读，用来选"接到哪条"） */
    public Map<String, Object> listSessions(int limit) throws Exception {
        return call("/v2/session?action=list&limit=" + Math.max(1, Math.min(100, limit)), "GET", null, 20);
    }

    /**
     * 把某条电脑会话接到这个对话码上。
     *
     * @param bind true = 改绑定（以后 code 就指向它）；false = 只看不改
     */
    public Map<String, Object> attach(String sessionId, String code, boolean bind) throws Exception {
        Map<String, Object> b = new HashMap<>();
        b.put("action", "attach");
        b.put("session_id", sessionId);
        if (code != null && !code.trim().isEmpty()) b.put("code", code.trim());
        b.put("bind", bind);
        return call("/v2/session", "POST", b, 20);
    }

    /** 直连绑定（空 id = 清掉 @direct，下一次直连由电脑新建手机专属会话） */
    public Map<String, Object> directBind(String sessionId) throws Exception {
        Map<String, Object> b = new HashMap<>();
        b.put("action", "direct_bind");
        if (sessionId != null && !sessionId.trim().isEmpty()) {
            b.put("target_session_id", sessionId.trim());
        }
        // direct_bind 只有 legacy /session 路由处理（/v2/session 不支持该 action）
        return call("/session", "POST", b, 20);
    }

    /** 桥接/ACP 通道状态（GET /status）：手机上用它显示"运行中 / 排队 / 待审批 / 版本"。 */
    public Map<String, Object> status() throws Exception {
        return call("/status", "GET", null, 8);
    }

    /**
     * 会话 token / 上下文 / 模型（GET /v2/usage，插件 v8.1+）。
     *
     * <p>2026-11-13：状态栏要显示"上下文占用 / 本轮 token / 模型"。
     * 插件侧读宿主 tokenMeter；会话不在电脑端内存时返回 ok:false + reason。</p>
     */
    public Map<String, Object> usage(String sessionId) throws Exception {
        String s = sessionId == null ? "" : sessionId.trim();
        String q = s.isEmpty() ? "" : "?session_id=" + URLEncoder.encode(s, "UTF-8");
        return call("/v2/usage" + q, "GET", null, 8);
    }

    /** 电脑端后台任务列表（插件按会话事件派生的状态，见 /v2/jobs）。 */
    public Map<String, Object> jobs() throws Exception {
        return jobs("");
    }

    /**
     * 按会话取后台任务（2026-11-15）：与甲板 jobs 插槽同口径，避免"1 个在跑 ·
     * 共 20 条"里的数字和面板看到的对不上。
     */
    public Map<String, Object> jobs(String sessionId) throws Exception {
        String s = sessionId == null ? "" : sessionId.trim();
        String q = s.isEmpty() ? "" : "?session_id=" + URLEncoder.encode(s, "UTF-8");
        return call("/v2/jobs" + q, "GET", null, 15);
    }

    /** 电脑端目标状态（事件派生）；sessionId 为空则列全部。 */
    public Map<String, Object> goal(String sessionId) throws Exception {
        String s = sessionId == null ? "" : sessionId.trim();
        String q = s.isEmpty() ? "" : "?session_id=" + URLEncoder.encode(s, "UTF-8");
        return call("/v2/goal" + q, "GET", null, 15);
    }

    /** 本进程用过的 MCP 服务（插件从 mcp__<server>__<tool> 工具名派生）。 */
    public Map<String, Object> mcp() throws Exception {
        return mcp("");
    }

    /** 按会话取 MCP 服务（2026-11-15）：与 mcp 插槽同口径，避免 A 会话的统计串到 B。 */
    public Map<String, Object> mcp(String sessionId) throws Exception {
        String s = sessionId == null ? "" : sessionId.trim();
        String q = s.isEmpty() ? "" : "?session_id=" + URLEncoder.encode(s, "UTF-8");
        return call("/v2/mcp" + q, "GET", null, 15);
    }

    /** 权限状态（沙箱 / 档位 / 审批策略的最近取值）。 */
    public Map<String, Object> permission() throws Exception {
        return call("/v2/permission", "GET", null, 10);
    }

    /**
     * 客户端可发起的白名单操作（{@code jobs.clear} / {@code resync}）。
     *
     * <p>服务端只认白名单，未知动作会 400 并返回 allowed 列表——不做通用 RPC。</p>
     */
    public Map<String, Object> action(String action, Map<String, Object> extra) throws Exception {
        Map<String, Object> b = new HashMap<>();
        b.put("action", action);
        if (extra != null) b.putAll(extra);
        return call("/v2/action", "POST", b, 15);
    }

    /** 服务端派生的统一视图（状态 / 自检 / 会话 / 时间线），带客户端 caps 让其裁剪 */
    public Map<String, Object> view(String sessionId) throws Exception {
        String s = sessionId == null ? "" : sessionId.trim();
        String q = "?caps=" + URLEncoder.encode(RemoteCaps.csv(), "UTF-8")
                + (s.isEmpty() ? "" : "&session_id=" + URLEncoder.encode(s, "UTF-8"));
        return call("/v2/view" + q, "GET", null, 20);
    }

    /**
     * 发一条消息。
     *
     * @param code      对话码（稳定身份，决定落到哪条电脑会话；首次会自动创建并回绑）
     * @param sessionId 已知的电脑会话 id；为空则完全由 code 决定
     */
    public Map<String, Object> send(String code, String sessionId, String text, int timeoutSec) throws Exception {
        Map<String, Object> b = new HashMap<>();
        b.put("text", text);
        b.put("timeout", Math.max(5, Math.min(600, timeoutSec)));
        if (code != null && !code.trim().isEmpty()) b.put("session_code", code.trim());
        if (sessionId != null && !sessionId.trim().isEmpty()) b.put("target_session_id", sessionId.trim());
        return call("/v2/message", "POST", b, Math.max(30, timeoutSec + 30));
    }

    /** 回执：告诉电脑"这条会话我已收到 seq 之前的全部消息" */
    public Map<String, Object> ack(String sessionId, long seq) throws Exception {
        Map<String, Object> b = new HashMap<>();
        b.put("session_id", sessionId);
        b.put("seq", seq);
        return call("/v2/ack", "POST", b, 15);
    }

    /**
     * 心跳（"呼吸"）：一次拿到在线状态 / 落后条数 gap / 回执水位线。
     * RTT 由调用方自己计时（本地 t0 → t1）。
     */
    public Map<String, Object> pulse(String sessionId, long since) throws Exception {
        Map<String, Object> b = new HashMap<>();
        b.put("session_id", sessionId == null ? "" : sessionId.trim());
        b.put("since", Math.max(0, since));
        b.put("client_id", "android");
        return call("/v2/pulse", "POST", b, 10);
    }

    /** 轻量对账（摘要 + 回执水位线） */
    public Map<String, Object> reconcile(String sessionId) throws Exception {        String q = "?light=1&session_id=" + URLEncoder.encode(sessionId, "UTF-8");
        return call("/v2/reconcile" + q, "GET", null, 20);
    }

    /** SSE 事件流地址（客户端自己用 HttpURLConnection 读）；带 caps 让服务端过滤审批事件 */
    public String streamUrl(String sessionId, long since) throws Exception {
        return tool.getBaseUrl() + "/v2/stream?session_id="
                + URLEncoder.encode(sessionId, "UTF-8") + "&since=" + Math.max(0, since)
                + "&caps=" + URLEncoder.encode(RemoteCaps.csv(), "UTF-8");
    }

    /**
     * 拉一段事件流（最多 waitMs 毫秒），返回期间收到的事件。
     * 用途：手机"看一眼电脑刚才发生了什么"，以及断线后按 since 补拉。
     */
    public java.util.List<Map<String, Object>> events(String sessionId, long since, long waitMs) throws Exception {
        final java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        RemoteStream stream = new RemoteStream();
        stream.open(streamUrl(sessionId, since), tool.getToken(), waitMs, new RemoteStream.Listener() {
            @Override
            public void onEvent(Map<String, Object> event) {
                synchronized (out) { out.add(event); }
            }

            @Override
            public void onEnd(String reason) {
                done.countDown();
            }
        });
        done.await(waitMs + 3000, java.util.concurrent.TimeUnit.MILLISECONDS);
        stream.stop();
        synchronized (out) { return new java.util.ArrayList<>(out); }
    }

    /**
     * 从任意 v2 响应里取"电脑签发的会话 id"。
     * 统一在这里取，避免每个调用点自己找 session_id / dsh_session_id / session.id。
     */
    public static String sessionIdOf(Map<String, Object> resp) {
        if (resp == null) return "";
        Object s = resp.get("session");
        if (s instanceof Map) {
            Object id = ((Map<?, ?>) s).get("id");
            if (id != null) return String.valueOf(id).trim();
        }
        Object id = resp.get("session_id");
        if (id == null) id = resp.get("dsh_session_id");
        return id == null ? "" : String.valueOf(id).trim();
    }

    /** 会话是否"已绑定到真实电脑会话"（needs_first_message = false） */
    public static boolean isBound(Map<String, Object> resp) {
        if (resp == null) return false;
        Object s = resp.get("session");
        if (s instanceof Map) {
            Object b = ((Map<?, ?>) s).get("bound");
            if (b instanceof Boolean) return (Boolean) b;
        }
        return !sessionIdOf(resp).isEmpty();
    }
}
