package com.oilquiz.app.ai.tool;

import android.app.Activity;

/**
 * 「切到电脑上的某条会话」的唯一执行路径（2026-11-14）。
 *
 * <p>以前这段逻辑只写在「远程连接」页的选择框里（{@code RemoteDshConnectActivity#pickSessionRow}），
 * 现在聊天页的历史抽屉也要能直接切电脑会话，所以抽出来共用一份，避免两处行为漂移。</p>
 *
 * <p>步骤：写跟随身份 → 让电脑把 {@code @code:follow} 绑过去 → 重开通道（旧通道还挂在旧会话上）
 * → **强制同步**整条历史到本地（锚点 id、标题用电脑的）→ 回调本地会话 id。</p>
 */
public final class RemoteSessionSwitcher {

    private RemoteSessionSwitcher() {
    }

    /** 回调都在主线程 */
    public interface Callback {
        /** 进度文案（"正在同步电脑历史… 320 条事件"） */
        void onProgress(String text);

        /**
         * 完成。
         *
         * @param localSessionId 本地会话 id（同步成功时非空，可直接打开）
         * @param count          同步到的消息条数（0 = 电脑上这条还是空的）
         * @param title          电脑端的会话标题
         */
        void onDone(String localSessionId, int count, String title);

        /** 失败（跟随身份已经写上了，只是这次没同步成功） */
        void onFailed(String message);
    }

    /**
     * 切到指定电脑会话（异步；回调在主线程）。
     *
     * @param activity       用来 post 回主线程；销毁后回调会被丢弃
     * @param remoteSessionId 电脑端会话 id
     * @param title          电脑端标题（本地会话标题也用它）
     */
    public static void switchTo(Activity activity, String remoteSessionId, String title, Callback cb) {
        if (activity == null || remoteSessionId == null || remoteSessionId.isEmpty()) {
            if (cb != null) cb.onFailed("没有指定电脑会话");
            return;
        }
        final android.content.Context app = activity.getApplicationContext();
        final String t = title == null ? "" : title;
        // 身份先写：即使后面同步失败，下次连接也会跟随这条
        RemoteDshTool.setSyncTarget(app, remoteSessionId);
        RemoteDshTool.setSyncTitle(app, t);
        final Runnable progress0 = () -> {
            if (cb != null) cb.onProgress("正在从电脑同步「" + (t.isEmpty() ? "…" : t) + "」…");
        };
        activity.runOnUiThread(progress0);

        new Thread(() -> {
            try {
                new RemoteSdk(app).attach(remoteSessionId, "follow", true);
            } catch (Exception ignored) {
                // 电脑不可达：本地身份已写，下次连接会再对齐
            }
            // 换通道：老通道还挂在旧会话上，不重开会一直收旧会话的事件
            activity.runOnUiThread(() -> restartChannel(app));
            try {
                DshHistoryImporter.Result r = DshHistoryImporter.importSession(app, remoteSessionId, t,
                        new DshHistoryImporter.Progress() {
                            private long lastAt = 0L;

                            @Override public void onEvents(int count) {
                                long now = System.currentTimeMillis();
                                if (now - lastAt < 250) return;
                                lastAt = now;
                                activity.runOnUiThread(() -> {
                                    if (cb != null) cb.onProgress("正在同步电脑历史… " + count + " 条事件");
                                });
                            }

                            @Override public void onBuilding(int events) {
                                activity.runOnUiThread(() -> {
                                    if (cb != null) cb.onProgress("已收到 " + events + " 条事件，正在生成对话…");
                                });
                            }
                        });
                activity.runOnUiThread(() -> {
                    if (cb != null) cb.onDone(r == null ? "" : r.sessionId, r == null ? 0 : r.count,
                            r == null ? t : r.title);
                });
            } catch (Throwable e) {
                activity.runOnUiThread(() -> {
                    if (cb != null) cb.onFailed(String.valueOf(e.getMessage()));
                });
            }
        }, "remote-session-switch").start();
    }

    /** 重开全 App 唯一通道（切了跟随会话就得换一条） */
    static void restartChannel(android.content.Context c) {
        try {
            RemoteConnection conn = RemoteConnection.get(c);
            conn.stop();
            conn.start();
        } catch (Throwable ignored) {
            // 连不上时状态条会提示
        }
    }
}
