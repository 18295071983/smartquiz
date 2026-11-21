package com.oilquiz.app.ai.tool;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import com.oilquiz.app.SmartQuizApplication;
import com.oilquiz.app.infra.SecuritySwitches;
import com.oilquiz.app.util.AILogger;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 工具权限分级的执行拦截（2026-11-22 接入）。
 *
 * <p>只拦 {@link ToolPermissions#requiresEveryTime(String)} 为真的三档：
 * EXEC / NETWORK / DANGEROUS；READ_ONLY 与 WRITE_LOCAL 直接放行。</p>
 *
 * <p>交互沿用 {@code McpApprovalManager} 的范式：把确认框 post 到当前 Activity，
 * 执行线程用 latch 等待（60 秒超时即拒绝）。差别有两点：</p>
 * <ul>
 *   <li><b>主线程调用直接放行</b>：那是 App 内部 UI 自己发起的调用（导入、组件渲染等），
 *       用户已经在界面上操作了，再弹一次框纯属噪音；而且主线程等待必然死锁。</li>
 *   <li>没有前台 Activity 时一律拒绝（后台偷偷执行命令比"稍后重试"更糟）。</li>
 * </ul>
 *
 * <p><b>放行是全局的</b>（2026-11-22 调整）：用户点「始终允许」后写进
 * {@link SecuritySwitches}，跨进程/跨会话生效，直到在 Agent 设置里一键清除。
 * 早期版本是进程内"本次对话"，真机上表现为"过一阵又弹"，改成全局后不再反复打扰。
 * DANGEROUS 不提供全局选项，每次都必须确认。</p>
 */
public final class ToolApprovalGate {

    private static final String TAG = "ToolApprovalGate";
    private static final long TIMEOUT_MS = 60_000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private enum Decision { ALLOW_ONCE, ALLOW_GLOBAL, DENY }

    private ToolApprovalGate() {
    }

    /**
     * 执行前检查。
     *
     * @return {@code null} 表示允许执行；否则是给模型看的拒绝结果
     */
    public static AIToolResult check(String toolName, Map<String, Object> parameters) {
        ToolPermissions.Level level = ToolPermissions.levelOf(toolName);
        if (!ToolPermissions.requiresEveryTime(toolName)) {
            return null;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // UI 内部调用：用户已经在操作界面，不弹框、不阻塞
            AILogger.audit(TAG, "ALLOW(main-thread) " + toolName + " level=" + level);
            return null;
        }
        Activity activity = SmartQuizApplication.getCurrentActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            AILogger.audit(TAG, "DENY(no-ui) " + toolName + " level=" + level);
            return AIToolResult.fail("工具 " + toolName + "（" + levelLabel(level)
                    + "）需要用户确认，但应用不在前台。请回到 App 后重试。", info(toolName, level));
        }
        if (SecuritySwitches.isToolAllowed(activity, toolName)) {
            AILogger.audit(TAG, "ALLOW(global) " + toolName + " level=" + level);
            return null;
        }

        Decision decision = ask(activity, toolName, level, parameters);
        if (decision == Decision.DENY) {
            AILogger.audit(TAG, "DENY(user) " + toolName + " level=" + level);
            return AIToolResult.fail("用户拒绝了本次工具调用: " + toolName
                    + "。不要原样重试；先向用户说明要做什么。", info(toolName, level));
        }
        if (decision == Decision.ALLOW_GLOBAL) {
            boolean ok = SecuritySwitches.allowTool(activity, toolName);
            AILogger.audit(TAG, "ALLOW(global-recorded=" + ok + ") " + toolName + " level=" + level);
        } else {
            AILogger.audit(TAG, "ALLOW(once) " + toolName + " level=" + level);
        }
        return null;
    }

    /** 清空全局放行（设置页/测试用） */
    public static void clearAllowed(android.content.Context context) {
        SecuritySwitches.clearAllowedTools(context);
    }

    /** 通用授权结果：0=仅这次，1=始终允许，2=拒绝 */
    public static final int DECISION_ONCE = 0;
    public static final int DECISION_GLOBAL = 1;
    public static final int DECISION_DENY = 2;

    /**
     * 弹一个通用授权框（给 ssh_exec 这类"动作级"高风险操作复用，而不是工具级）。
     *
     * <p>调用前必须确认不在主线程（会阻塞等待）。没有前台 Activity 时直接返回拒绝。</p>
     */
    public static int requestApproval(android.content.Context context, String title,
                                      CharSequence message, boolean allowGlobal) {
        Activity activity = context instanceof Activity ? (Activity) context
                : SmartQuizApplication.getCurrentActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return DECISION_DENY;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean done = new AtomicBoolean(false);
        final int[] out = {DECISION_DENY};
        Runnable show = () -> {
            try {
                if (done.get()) {
                    return;
                }
                com.oilquiz.app.ui.modal.AppDialog.Builder builder =
                        com.oilquiz.app.ui.modal.AppDialog.builder(activity)
                                .setTitle(title)
                                .setMessage(message)
                                .setPositiveButton("仅这次允许", (d, w) -> {
                                    if (done.compareAndSet(false, true)) {
                                        out[0] = DECISION_ONCE;
                                        latch.countDown();
                                    }
                                })
                                .setNegativeButton("拒绝", (d, w) -> {
                                    if (done.compareAndSet(false, true)) {
                                        out[0] = DECISION_DENY;
                                        latch.countDown();
                                    }
                                })
                                .setOnCancelListener(d -> {
                                    if (done.compareAndSet(false, true)) {
                                        out[0] = DECISION_DENY;
                                        latch.countDown();
                                    }
                                });
                if (allowGlobal) {
                    builder.setNeutralButton("始终允许（全局）", (d, w) -> {
                        if (done.compareAndSet(false, true)) {
                            out[0] = DECISION_GLOBAL;
                            latch.countDown();
                        }
                    });
                }
                builder.show();
            } catch (Throwable t) {
                AILogger.w(TAG, "通用授权框弹出失败: " + t.getMessage());
                if (done.compareAndSet(false, true)) {
                    out[0] = DECISION_DENY;
                    latch.countDown();
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            show.run();
        } else {
            MAIN.post(show);
        }
        try {
            if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                if (done.compareAndSet(false, true)) {
                    out[0] = DECISION_DENY;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (done.compareAndSet(false, true)) {
                out[0] = DECISION_DENY;
            }
        }
        return out[0];
    }

    private static Decision ask(Activity activity, String toolName, ToolPermissions.Level level,
                                Map<String, Object> parameters) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean done = new AtomicBoolean(false);
        final Decision[] out = {Decision.DENY};

        final boolean dangerous = level == ToolPermissions.Level.DANGEROUS;
        final StringBuilder msg = new StringBuilder();
        msg.append("工具：").append(toolName).append("\n");
        msg.append("级别：").append(levelLabel(level)).append("\n");
        String preview = preview(parameters);
        if (!preview.isEmpty()) {
            msg.append("参数：").append(preview).append("\n");
        }
        msg.append("\n拒绝后本次调用不会执行。");
        if (dangerous) {
            msg.append("危险操作每次都必须确认，且会记入审计日志。");
        } else {
            msg.append("选「始终允许」后不再询问，可在「Agent 设置 → 安全开关」里一键重置。");
        }

        Runnable show = () -> {
            try {
                if (done.get()) {
                    return;
                }
                com.oilquiz.app.ui.modal.AppDialog.Builder builder =
                        com.oilquiz.app.ui.modal.AppDialog.builder(activity)
                                .setTitle("允许执行这个工具？")
                                .setMessage(msg.toString())
                                .setPositiveButton("仅这次允许", (d, w) -> finish(done, latch, out, Decision.ALLOW_ONCE))
                                .setNegativeButton("拒绝", (d, w) -> finish(done, latch, out, Decision.DENY))
                                .setOnCancelListener(d -> finish(done, latch, out, Decision.DENY));
                if (!dangerous) {
                    builder.setNeutralButton("始终允许（全局）",
                            (d, w) -> finish(done, latch, out, Decision.ALLOW_GLOBAL));
                }
                builder.show();
            } catch (Throwable t) {
                AILogger.w(TAG, "确认框弹出失败: " + t.getMessage());
                finish(done, latch, out, Decision.DENY);
            }
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            show.run();
        } else {
            MAIN.post(show);
        }

        try {
            if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                finish(done, latch, out, Decision.DENY);
                AILogger.w(TAG, "确认超时，按拒绝处理: " + toolName);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finish(done, latch, out, Decision.DENY);
        }
        return out[0];
    }

    private static void finish(AtomicBoolean done, CountDownLatch latch,
                               Decision[] out, Decision decision) {
        if (!done.compareAndSet(false, true)) {
            return;
        }
        out[0] = decision;
        latch.countDown();
    }

    private static Map<String, Object> info(String toolName, ToolPermissions.Level level) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("toolName", toolName);
        m.put("permissionLevel", level.name());
        return m;
    }

    private static String levelLabel(ToolPermissions.Level level) {
        switch (level) {
            case READ_ONLY:
                return "只读";
            case WRITE_LOCAL:
                return "本地可逆写";
            case EXEC:
                return "执行";
            case NETWORK:
                return "网络外发";
            case DANGEROUS:
                return "危险";
            default:
                return level.name();
        }
    }

    /** 参数摘要：只展示键值，截断到 300 字，避免把长文本/密钥整段弹出来 */
    private static String preview(Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : parameters.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            String v = String.valueOf(e.getValue());
            if (v.length() > 80) {
                v = v.substring(0, 80) + "...";
            }
            sb.append(e.getKey()).append('=').append(v);
            if (sb.length() > 300) {
                sb.append("...");
                break;
            }
        }
        return sb.toString();
    }
}
