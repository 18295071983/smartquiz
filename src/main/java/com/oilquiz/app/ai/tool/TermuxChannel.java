package com.oilquiz.app.ai.tool;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.Toast;

/**
 * Termux 命令通道的独立体检/修复（2026-11-18）。
 *
 * <p>从「完整 Python 环境」页里拎出来的原因：本机 DSH、termux_exec、离线包导出都依赖
 * 这条通道，但它原先只在那一个页面里能修。现在任何页面都能调
 * {@link #check} / {@link #wake} / {@link #copyFixAndOpen}，DSH 启动前也会先体检。</p>
 *
 * <p>三档归因（不匹配文案，按 {@link TermuxEnvInstaller.ChannelResult#timeoutCause}）：
 * ① 没装 Termux / 没授 RUN_COMMAND；② 进程没起来（process_invisible）→ 唤醒；
 * ③ 进程活着但没回执（alive_no_reply）→ allow-external-apps 修复命令。</p>
 */
public final class TermuxChannel {

    public static final class Status {
        public final boolean installed;
        public final boolean permission;
        public final boolean ok;
        public final String error;
        public final String cause;

        Status(boolean installed, boolean permission, boolean ok, String error, String cause) {
            this.installed = installed;
            this.permission = permission;
            this.ok = ok;
            this.error = error;
            this.cause = cause;
        }

        public String summary() {
            if (!installed) return "✗ 没装 Termux（先在上方「安装 Termux」）";
            if (!permission) return "✗ 没授 RUN_COMMAND 权限（向导主按钮授权）";
            if (ok) return "✓ 可用";
            if ("allow_external_apps".equals(cause)) {
                return "✗ allow-external-apps 没开（一次性设置：复制修复命令，去 Termux 粘一次）";
            }
            if ("process_invisible".equals(cause)) return "✗ Termux 进程没起来（点「唤醒 Termux」）";
            if ("alive_no_reply".equals(cause)) return "✗ 进程在但不回执（多半 allow-external-apps 没开，复制修复命令）";
            return "✗ 不可用" + (error == null || error.isEmpty() ? "" : "：" + error);
        }

        public boolean needsAllowExternalApps() {
            return "allow_external_apps".equals(cause);
        }
    }

    private TermuxChannel() {
    }

    /** 8 秒轻量探测（只测通断，不改任何配置）。 */
    public static Status check(Context c) {
        boolean installed = TermuxEnvInstaller.termuxVersion(c) != null;
        boolean permission = TermuxEnvInstaller.hasRunCommandPermission(c);
        if (!installed || !permission) {
            return new Status(installed, permission, false, null, null);
        }
        TermuxEnvInstaller.ChannelResult r =
                TermuxEnvInstaller.runInTermuxAndWait(c, "echo QUIZ_CHANNEL_OK", 8);
        boolean ok = r != null && r.ok;
        String error = r == null ? null : r.error;
        String cause = r == null ? null : r.timeoutCause;
        // 2026-11-18：Termux 明确回执"allow-external-apps 没开"（RunCommandService Error Code 2），
        // 这不是超时，必须单独归因，否则 UI 会让人去"唤醒进程"白折腾。
        String raw = r == null ? "" : (String.valueOf(r.error) + " " + String.valueOf(r.errmsg));
        if (!ok && raw.toLowerCase().contains("allow-external-apps")) {
            cause = "allow_external_apps";
        }
        return new Status(true, true, ok, error, cause);
    }

    /** 后台唤醒 Termux 服务；失败则打开 Termux 让用户手动初始化。 */
    public static String wake(Context c) {
        String err = TermuxEnvInstaller.wakeServiceInBackground(c);
        if (err == null) return "已后台唤醒 Termux 服务，等 5~10 秒后点「复检」";
        boolean opened = TermuxEnvInstaller.openTermux(c);
        return "后台唤醒被系统拒绝：" + err + "\n"
                + (opened ? "已打开 Termux，等它出 $ 提示符后回来复检" : "请手动打开 Termux 并等它出 $ 提示符");
    }

    /** 复制 allow-external-apps 修复命令并打开 Termux（用户粘贴一次即可）。 */
    public static String copyFixAndOpen(Context c) {
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("termux-allowex",
                        TermuxEnvInstaller.ALLOWEX_FIX_COMMAND));
            }
        } catch (Throwable ignored) {
        }
        boolean opened = TermuxEnvInstaller.openTermux(c);
        return "已复制修复命令：\n" + TermuxEnvInstaller.ALLOWEX_FIX_COMMAND + "\n"
                + (opened ? "已打开 Termux：长按终端 → 粘贴 → 回车" : "请手动打开 Termux 粘贴执行");
    }

    /** 独立入口：检查 + 三项修复动作（远程连接页「本机 DSH」卡片用）。 */
    public static void showDialog(Activity act) {
        if (act == null || act.isFinishing()) return;
        final String[] actions = {"复制修复命令并打开 Termux", "复检通道", "唤醒 Termux 进程"};
        new Thread(() -> {
            Status st = check(act);
            act.runOnUiThread(() -> {
                if (act.isFinishing()) return;
                String tip = st.summary()
                        + "\n\n（本机 DSH / termux_exec 都依赖这条通道）";
                if (st.needsAllowExternalApps()) {
                    tip += "\n\n一次性设置：复制 → 打开 Termux → 长按终端粘贴 → 回车（会自动重载设置）。"
                            + "之后 App 才能自动下发命令。";
                }
                com.oilquiz.app.ui.modal.AppDialog.builder(act)
                        .setTitle("Termux 命令通道")
                        .setMessage(tip)
                        .setItems(actions, (d, which) -> {
                            if (which == 0) {
                                String msg = copyFixAndOpen(act);
                                Toast.makeText(act, msg, Toast.LENGTH_LONG).show();
                            } else if (which == 1) {
                                showDialog(act);
                            } else {
                                String msg = wake(act);
                                Toast.makeText(act, msg, Toast.LENGTH_LONG).show();
                            }
                        })
                        .setNegativeButton("关闭", null)
                        .show();
            });
        }, "termux-channel-dialog").start();
    }
}
