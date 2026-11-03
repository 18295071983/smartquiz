package com.oilquiz.app.ai.agent.online.mcp;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AlertDialog;

import com.oilquiz.app.SmartQuizApplication;
import com.oilquiz.app.util.AILogger;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MCP 工具执行前的用户确认（逐工具审批的运行时部分）。
 *
 * <p>为什么要在执行线程阻塞等待：MCP 远端工具可能是写/删操作，不能默认放行；
 * 调用点在 {@link McpManager#executeRemote}（TOOL_EXECUTOR 后台线程），
 * 这里把对话框 post 到当前 Activity 的主线程，执行线程用 latch 等待用户选择。
 * 与 permission_manager 的 request_and_wait 是同一个交互模型。</p>
 *
 * <p>没有前台 Activity 时一律拒绝：后台偷偷执行写操作比“工具暂时不可用”更糟。</p>
 */
public final class McpApprovalManager {

    private static final String TAG = "McpApprovalManager";
    private static final long APPROVAL_TIMEOUT_MS = 45_000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public enum Decision {
        DENY,
        ALLOW_ONCE,
        ALLOW_ALWAYS
    }

    public static class Result {
        public final Decision decision;
        public final String reason;

        Result(Decision decision, String reason) {
            this.decision = decision;
            this.reason = reason;
        }
    }

    private McpApprovalManager() {
    }

    /**
     * 请求用户确认。
     *
     * @param serverName  展示用 server 名
     * @param toolName    远端工具名
     * @param description 工具说明（外部提供，仅展示）
     * @param argsPreview 参数预览（外部提供）
     */
    public static Result request(String serverName, String toolName,
                                 String description, String argsPreview) {
        Activity activity = SmartQuizApplication.getCurrentActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            AILogger.audit(TAG, "DENY(no-ui) " + serverName + "/" + toolName);
            return new Result(Decision.DENY,
                    "应用不在前台，无法确认该 MCP 工具的写入操作；请回到 App 重试，"
                            + "或在 MCP 管理页把该工具设为「自动允许」");
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean done = new AtomicBoolean(false);
        final Result[] out = {new Result(Decision.DENY, "用户未确认")};
        final AlertDialog[] dialogRef = new AlertDialog[1];

        Runnable show = () -> {
            try {
                if (done.get()) return; // 已超时/被中断，不再弹窗
                String desc = description == null ? "" : description.trim();
                if (desc.length() > 600) desc = desc.substring(0, 600) + "…";
                String args = argsPreview == null ? "" : argsPreview.trim();
                if (args.length() > 600) args = args.substring(0, 600) + "…";
                StringBuilder msg = new StringBuilder();
                msg.append("服务：").append(serverName).append("\n");
                msg.append("工具：").append(toolName).append("\n");
                if (!desc.isEmpty()) msg.append("说明：").append(desc).append("\n");
                if (!args.isEmpty()) msg.append("参数：").append(args).append("\n");
                msg.append("\n外部 MCP 工具可能修改真实数据，请确认后再执行。");

                AlertDialog.Builder b = new AlertDialog.Builder(activity)
                        .setTitle("允许执行 MCP 工具？")
                        .setMessage(msg.toString())
                        .setPositiveButton("允许一次", (d, w) -> finish(done, latch, out,
                                new Result(Decision.ALLOW_ONCE, "允许一次")))
                        .setNeutralButton("始终允许", (d, w) -> finish(done, latch, out,
                                new Result(Decision.ALLOW_ALWAYS, "始终允许此工具")))
                        .setNegativeButton("拒绝", (d, w) -> finish(done, latch, out,
                                new Result(Decision.DENY, "用户拒绝")));
                b.setOnCancelListener(d -> finish(done, latch, out,
                        new Result(Decision.DENY, "用户取消")));
                dialogRef[0] = b.show();
            } catch (Throwable t) {
                AILogger.w(TAG, "show approval dialog failed: " + t.getMessage());
                finish(done, latch, out, new Result(Decision.DENY,
                        "无法弹出确认框: " + t.getMessage()));
            }
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            show.run();
        } else {
            MAIN.post(show);
        }

        try {
            if (!latch.await(APPROVAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                finish(done, latch, out, new Result(Decision.DENY, "确认超时"));
                MAIN.post(() -> {
                    AlertDialog d = dialogRef[0];
                    if (d != null && d.isShowing()) d.dismiss();
                });
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finish(done, latch, out, new Result(Decision.DENY, "确认被中断"));
        }
        Result r = out[0];
        AILogger.audit(TAG, (r.decision == Decision.DENY ? "DENY " : "ALLOW ")
                + serverName + "/" + toolName + " reason=" + r.reason);
        return r;
    }

    private static void finish(AtomicBoolean done, CountDownLatch latch,
                               Result[] out, Result result) {
        if (!done.compareAndSet(false, true)) return;
        out[0] = result;
        latch.countDown();
    }
}
