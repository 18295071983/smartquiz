package com.oilquiz.app.ai.chat.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import com.oilquiz.app.ui.activity.AIChatActivity;

/**
 * 打开 AI 聊天页的<b>唯一入口</b>。
 *
 * <p>为什么要有这个类（2026-10-19 查"每次退出重进多一条历史会话"时定位到）：
 * {@code AIChatActivity} 在清单里是默认 {@code launchMode=standard}，而应用内有 5 处
 * 用裸 {@code startActivity} 跳它（首页"AI 聊天"卡、AI 中心、工具箱、通知点击 ×2）。
 * 于是每进一次就<b>叠一个新实例</b>（logcat 里能看到同 pid 连续 6 次
 * {@code START ... AIChatActivity with LAUNCH_MULTIPLE}），而 {@code currentSessionId}
 * 是实例字段——每个实例各自恢复历史、各自在 {@code onStop} 保存，
 * 老实例那份还是 null → 每次都新建一条会话文件，同一段对话被抄成 N 份。</p>
 *
 * <p>用 {@code REORDER_TO_FRONT | SINGLE_TOP} 而不是 {@code CLEAR_TOP}：
 * 聊天页可能已经存在（栈里任何位置），把它提到前台并回调 {@code onNewIntent} 即可，
 * 不该顺手销毁它上面的中间页面；也不改清单的 launchMode，
 * 避免影响"从通知/工具箱进入"这些不同任务的场景。</p>
 */
public final class ChatNavigation {

    private ChatNavigation() {
    }

    /** 打开聊天页；已存在实例时把它提到前台复用，不再叠一层。 */
    public static void openChat(Context ctx) {
        if (ctx == null) return;
        Intent intent = new Intent(ctx, AIChatActivity.class);
        if (!(ctx instanceof Activity)) {
            // 从非 Activity 上下文（服务/工具回调）启动时必须带 NEW_TASK
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        ctx.startActivity(intent);
    }
}
