package com.oilquiz.app.ai.tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 手机端的能力声明（capability negotiation，2026-11-12）。
 *
 * <p>为什么需要：以前两端靠"版本号 + 猜"。客户端不知道自己能渲染什么，服务端不知道客户端是谁，
 * 于是出过这些事故：手机端没有 approval 块 → 审批只能弹窗；手机端有卡片渲染器但服务端还在
 * 下发 timeline → 白传流量；服务端新增事件类型 → 手机端静默丢弃、查不到原因。</p>
 *
 * <p>现在手机在 {@code /v2/hello}、{@code /v2/view}、{@code /events} 上带同一份 caps；
 * 电脑端（见插件 {@code caps.js}）按它裁剪卡片与事件。两边必须同名：
 * <b>加能力时改这里 + caps.js，两处顺序无所谓，名字必须一致</b>（{@code RemoteCapsTest} 会校验）。</p>
 */
public final class RemoteCaps {

    public static final int VERSION = 1;

    /**
     * 客户端支持的能力。与插件 {@code caps.js} 的 SERVER_CAPS 同名同义。
     *
     * <p>加一项就意味着"手机端真的能渲染/处理它"——不要为了好看提前声明，
     * 否则服务端会下发手机端处理不了的内容（正是以前的事故模式）。</p>
     */
    public static final String[] ALL = {
            "view.cards.status",
            "view.cards.checks",
            "view.cards.connections",
            "view.cards.session",
            "view.cards.approval",
            "view.cards.timeline",
            "view.cards.schedule",
            "view.cards.subagents",
            "view.cards.plan",
            "parts.approval",
            "parts.tool-cards",
            "parts.data-cards",
            "parts.stream",
            "parts.questions",
            "events.stream",
            "events.replay",
            "events.ack",
            "schedule.read",
            "schedule.write",
            "plan.read",
            "plan.write",
            "questions.answer",
            "subagents.panel",
            "cmd.ui",
            "cmd.shell",
    };

    private RemoteCaps() {
    }

    /** 逗号分隔（HTTP query / hello body 都用这一种表示） */
    public static String csv() {
        return String.join(",", ALL);
    }

    /** 客户端自报身份（服务端据此记录/排障，不做鉴权） */
    public static Map<String, Object> clientInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "smartquiz-android");
        m.put("kind", "android");
        m.put("ver", "2.0");
        return m;
    }

    /** {@code /v2/hello} 请求体 */
    public static Map<String, Object> helloBody(String sessionId) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("session_id", sessionId == null ? "" : sessionId);
        b.put("client", clientInfo());
        b.put("accept", java.util.Collections.singletonList("v2"));
        b.put("caps", csv());
        return b;
    }
}
