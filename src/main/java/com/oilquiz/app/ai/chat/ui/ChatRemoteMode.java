package com.oilquiz.app.ai.chat.ui;

import android.content.Context;

/**
 * 聊天页「本地 / 远程」的唯一判定入口（2026-11-14，M8-P0-1）。
 *
 * <p>为什么要有这个类：远程化点散在 {@code AIChatActivity} 里，
 * 以前每加一处就再抄一遍 {@code isConfigured(this) && isConnected(this)}，
 * 抄漏一处就是"某个角落还按本地渲染"。现在只允许问这里。</p>
 *
 * <p>三态，避免"配对过但没连上"把整页当成远程：</p>
 * <ul>
 *   <li>本地：未配对 → 完全现状</li>
 *   <li>已配对未连：顶栏提示"去重连"，其余本地</li>
 *   <li>远程：已配对 + 已连接 → 外壳全部按远程语义</li>
 * </ul>
 */
public final class ChatRemoteMode {

    private ChatRemoteMode() {
    }

    /** 已配对（配置里存了电脑地址与令牌）。 */
    public static boolean isConfigured(Context ctx) {
        try {
            return com.oilquiz.app.ai.tool.RemoteDshTool.isConfigured(ctx);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 通道在线（{@code RemoteConnection.isOnline()} 的同一判据）。 */
    public static boolean isConnected(Context ctx) {
        try {
            return com.oilquiz.app.ai.tool.RemoteDshTool.isConnected(ctx);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 已配对 + 已连接：外壳按远程渲染。 */
    public static boolean isActive(Context ctx) {
        return isConfigured(ctx) && isConnected(ctx);
    }

    /** 已配对但未连接：顶栏给"去重连"，其余按本地。 */
    public static boolean isDisconnected(Context ctx) {
        return isConfigured(ctx) && !isConnected(ctx);
    }
}
