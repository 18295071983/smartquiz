package com.oilquiz.app.ai.agent.online.mcp;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AlertDialog;

import com.oilquiz.app.SmartQuizApplication;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <p>没有前台 Activity 时一律拒绝：后台偷偷执行写操作比"工具暂时不可用"更糟。</p>
 *
 * <p><b>为什么改成清单而不是三个按钮</b>：真机上出现过"一次查地图被弹 6 次"，
 * 用户为了把答案看完会点最宽的那档——而他当时并不知道那一档的作用域是整个会话。
 * 现在把作用域<b>写进每一行文案</b>（这一次 / 本轮这个服务 / 本次对话这个服务 /
 * 本次对话所有 MCP 服务 / 拒绝），点之前就能看见范围；永久放开仍然只留在管理页。</p>
 */
public final class McpApprovalManager {

    private static final String TAG = "McpApprovalManager";
    /** 列表要读五行，比原来的三按钮多给一点时间 */
    private static final long APPROVAL_TIMEOUT_MS = 60_000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 通配服务档：本次对话内所有 MCP 服务都放行。 */
    private static final String ALL_SERVERS = "*";

    public enum Decision {
        DENY,
        ALLOW_ONCE,
        /** 本轮（一次用户消息触发的执行）内该服务都放行 */
        ALLOW_RUN,
        /** 本次对话内该服务都放行 */
        ALLOW_SESSION,
        /** 本次对话内所有 MCP 服务都放行 */
        ALLOW_SESSION_ALL,
        /** 拿不到会话号时的兜底档（等价于在配置里写 auto） */
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

    /**
     * 放行登记表：key 是 {@code run:会话内本轮::serverId} / {@code 会话::serverId} /
     * {@code 会话::*}。只在内存，不落配置。
     *
     * <p>LRU 上限 128：会话与轮次都在涨，没上限就是进程存活期内只增不减的集合。</p>
     */
    private static final LinkedHashMap<String, Boolean> ALLOWED =
            new LinkedHashMap<String, Boolean>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 128;
                }
            };

    private McpApprovalManager() {}

    // ==================== 登记表 ====================

    /**
     * 本轮放行的 key 带上会话号。
     *
     * <p>run id 是按 8 位十六进制随机生成的，理论上两个会话可能撞出同一个 id；
     * 撞上的后果是"另一个会话的放行被这边继承"。带上会话号就不用赌随机数。</p>
     */
    private static String runKey(String sessionId, String runId, String serverId) {
        String r = runId == null ? "" : runId.trim();
        if (r.isEmpty()) return "";
        String s = sessionId == null ? "" : sessionId.trim();
        return "run:" + s + ":" + r + "::" + serverId;
    }

    private static String sessionKey(String sessionId, String serverId) {
        String s = sessionId == null ? "" : sessionId.trim();
        if (s.isEmpty()) return "";
        return s + "::" + serverId;
    }

    /** 这一次调用是否已经被放行过（本轮 / 本会话该服务 / 本会话所有服务）。 */
    public static boolean isAllowed(String sessionId, String runId, String serverId) {
        String server = serverId == null ? "" : serverId.trim();
        List<String> keys = new ArrayList<>();
        String run = runKey(sessionId, runId, server);
        if (!run.isEmpty()) keys.add(run);
        String sess = sessionKey(sessionId, server);
        if (!sess.isEmpty()) keys.add(sess);
        String sessAll = sessionKey(sessionId, ALL_SERVERS);
        if (!sessAll.isEmpty()) keys.add(sessAll);
        if (keys.isEmpty()) return false;
        synchronized (ALLOWED) {
            for (String k : keys) {
                if (Boolean.TRUE.equals(ALLOWED.get(k))) return true;
            }
        }
        return false;
    }

    /** 按用户选中的作用域登记放行。 */
    public static void record(Decision decision, String sessionId, String runId, String serverId) {
        String server = serverId == null ? "" : serverId.trim();
        String key;
        switch (decision) {
            case ALLOW_RUN:
                key = runKey(sessionId, runId, server);
                break;
            case ALLOW_SESSION:
                key = sessionKey(sessionId, server);
                break;
            case ALLOW_SESSION_ALL:
                key = sessionKey(sessionId, ALL_SERVERS);
                break;
            default:
                return;     // 允许一次不落登记；ALLOW_ALWAYS 由调用方写进配置
        }
        if (key.isEmpty()) return;
        synchronized (ALLOWED) {
            ALLOWED.put(key, Boolean.TRUE);
        }
        AILogger.audit(TAG, "放行范围已登记: " + key + "（" + decision.name() + "，仅本进程内存）");
    }

    /** 会话被删/清空时撤掉它的放行记录。 */
    public static void clearSession(String sessionId) {
        String s = sessionId == null ? "" : sessionId.trim();
        if (s.isEmpty()) return;
        String prefix = s + "::";
        synchronized (ALLOWED) {
            java.util.Iterator<String> it = ALLOWED.keySet().iterator();
            while (it.hasNext()) {
                if (it.next().startsWith(prefix)) it.remove();
            }
        }
    }

    /** 一轮回结束（一次用户消息执行完）后撤掉本轮放行。 */
    public static void clearRun(String sessionId, String runId) {
        String r = runId == null ? "" : runId.trim();
        if (r.isEmpty()) return;
        String s = sessionId == null ? "" : sessionId.trim();
        String suffix = ":" + r + "::";
        String sessionPrefix = "run:" + s + suffix;
        // 没有会话号时登记的 key 用的是空串段（run::rId::server），这里两种都清掉
        synchronized (ALLOWED) {
            java.util.Iterator<String> it = ALLOWED.keySet().iterator();
            while (it.hasNext()) {
                String key = it.next();
                if (key.startsWith(sessionPrefix) || (s.isEmpty() && key.startsWith("run:" + suffix))) {
                    it.remove();
                }
            }
        }
    }

    /** 测试/排障用：当前登记了多少条。 */
    public static int allowCount() {
        synchronized (ALLOWED) {
            return ALLOWED.size();
        }
    }

    /** 单测之间清场（登记表是进程级静态，不隔离会互相污染）。 */
    public static void clearAllForTest() {
        synchronized (ALLOWED) {
            ALLOWED.clear();
        }
    }

    // ==================== 弹框 ====================

    /**
     * 请求用户确认。
     *
     * @param sessionId   当前会话（决定"本次对话"两档是否可用）
     * @param runId       当前这一轮（决定"本轮"那一档是否可用）
     * @param serverName  展示用 server 名
     * @param serverId    真实 serverId（登记作用域用它，展示用 serverName）
     * @param toolName    远端工具名
     * @param description 工具说明（外部提供，仅展示）
     * @param argsPreview 参数预览（外部提供）
     * @param basis       为什么要问（服务商注解 / 名称启发式 / 用户设定）
     */
    public static Result request(String sessionId, String runId, String serverName,
                                 String serverId, String toolName, String description,
                                 String argsPreview, String basis) {
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
        final String session = sessionId == null ? "" : sessionId.trim();
        final String run = runId == null ? "" : runId.trim();

        Runnable show = () -> {
            try {
                if (done.get()) return; // 已超时/被中断，不再弹窗
                String desc = description == null ? "" : description.trim();
                if (desc.length() > 400) desc = desc.substring(0, 400) + "…";
                String args = argsPreview == null ? "" : argsPreview.trim();
                if (args.length() > 400) args = args.substring(0, 400) + "…";
                StringBuilder msg = new StringBuilder();
                msg.append("服务：").append(serverName).append("\n");
                msg.append("工具：").append(toolName).append("\n");
                if (!desc.isEmpty()) msg.append("说明：").append(desc).append("\n");
                if (!args.isEmpty()) msg.append("参数：").append(args).append("\n");
                if (basis != null && !basis.trim().isEmpty()) {
                    msg.append("为什么要确认：").append(basis.trim()).append("\n");
                }
                msg.append("\n每一行的作用域都写在文字里；要永久放开请去"
                        + " MCP 管理 → 工具 → 审批策略 → 自动允许。");

                List<String> options = new ArrayList<>();
                List<Decision> decisions = new ArrayList<>();
                options.add("允许一次（只这一次）");
                decisions.add(Decision.ALLOW_ONCE);
                if (!run.isEmpty()) {
                    options.add("本轮都允许（这个服务的后续调用）");
                    decisions.add(Decision.ALLOW_RUN);
                }
                if (!session.isEmpty()) {
                    options.add("本次对话都允许（这个服务）");
                    decisions.add(Decision.ALLOW_SESSION);
                    options.add("本次对话都允许（所有 MCP 服务）");
                    decisions.add(Decision.ALLOW_SESSION_ALL);
                } else {
                    // 拿不到会话号（非 Agent 路径）才给这一档，作用等同在配置里写 auto
                    options.add("始终允许这个工具");
                    decisions.add(Decision.ALLOW_ALWAYS);
                }
                options.add("拒绝");
                decisions.add(Decision.DENY);

                AlertDialog.Builder b = new AlertDialog.Builder(activity)
                        .setTitle("允许执行 MCP 工具？")
                        .setMessage(msg.toString())
                        .setItems(toArray(options), (d, which) -> finish(done, latch, out,
                                new Result(decisions.get(which), options.get(which))))
                        .setNegativeButton("取消", (d, w) -> finish(done, latch, out,
                                new Result(Decision.DENY, "用户取消")));
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

    private static String[] toArray(List<String> in) {
        return in.toArray(new String[0]);
    }

    private static void finish(AtomicBoolean done, CountDownLatch latch,
                               Result[] out, Result result) {
        if (!done.compareAndSet(false, true)) return;
        out[0] = result;
        latch.countDown();
    }
}
