package com.oilquiz.app.ai.chat.history;

/**
 * 历史抽屉里"电脑上的对话"那一组的一行（2026-11-14）。
 *
 * <p>为什么要它：抽屉原本只列本机会话文件，用户想切到电脑上的另一条对话得先点顶栏进
 * 「选择电脑对话」——多绕一层。现在抽屉顶部直接列出电脑会话，点一条就切（强制同步）。</p>
 */
public final class RemoteSessionRow {

    /** 电脑端会话 id（切会话/同步都用它） */
    public final String sessionId;
    /** 电脑端标题（与桌面端一致） */
    public final String title;
    /** 工作区绝对路径（只在提示里用） */
    public final String cwd;
    /** 最后活动时间（毫秒，0 = 未知） */
    public final long updatedAt;
    /** 最后一句（列表副标题） */
    public final String last;
    /** 是否在桌面端侧栏里（workspace 账本登记过） */
    public final boolean desktop;
    /** 是不是电脑上"正在聊的那条" */
    public final boolean current;
    /** 手机上正在跟随的那条 */
    public final boolean following;

    public RemoteSessionRow(String sessionId, String title, String cwd, long updatedAt, String last,
                            boolean desktop, boolean current, boolean following) {
        this.sessionId = sessionId == null ? "" : sessionId;
        this.title = title == null ? "" : title;
        this.cwd = cwd == null ? "" : cwd;
        this.updatedAt = updatedAt;
        this.last = last == null ? "" : last;
        this.desktop = desktop;
        this.current = current;
        this.following = following;
    }

    /** 抽屉里显示的名字：标题为空就退化成短 id */
    public String displayTitle() {
        if (!title.isEmpty()) return title;
        String s = sessionId;
        return s.length() <= 14 ? s : "…" + s.substring(s.length() - 12);
    }
}
