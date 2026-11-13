package com.oilquiz.app.ai.chat.component.action;

import android.content.Context;

/**
 * 动作落点（2026-11-13，M4）。
 *
 * <p>一个 sink 就是"某类组件动作的一个去处"：电脑端宿主（DSH）、本机引擎回调、
 * 动态卡的页面跳转、以后的 MCP 工具……。{@link ActionRouter} 按注册顺序询问，
 * 第一个既 {@link #canHandle} 又能 {@link #send} 的 sink 拿走这次动作。</p>
 *
 * <p>约定：</p>
 * <ul>
 *   <li>{@link #canHandle} 必须是**便宜的纯判断**（不联网、不查库）；</li>
 *   <li>{@link #send} 返回 false 表示"我看懂了但没送出去"，路由器会继续问下一个；</li>
 *   <li>实现应当自己吞掉异常并返回 false，路由器也会兜住 throwable（故障隔离）。</li>
 * </ul>
 */
public interface ActionSink {

    /** 稳定标识，用于日志与自检（如 "dsh" / "local" / "mcp"） */
    String id();

    /** 这个 sink 是否有资格处理该动作（纯判断，允许误判为 true，由 send 兜底） */
    boolean canHandle(ActionEvent event);

    /** 真正投递；返回 true 表示已消费 */
    boolean send(Context context, ActionEvent event);
}
