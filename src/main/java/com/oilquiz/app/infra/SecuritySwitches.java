package com.oilquiz.app.infra;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 安全开关（对话工具与设置页共用同一份偏好，避免两边 key 写岔）。
 *
 * <p>存在的原因：shell 与 ssh 远程执行在外观上属于"高权限能力"，默认必须收着，
 * 由用户在设置页显式打开；打开时给一次风险说明，并把状态留痕。</p>
 */
public final class SecuritySwitches {

    /** 与 SystemResourceTool 历史偏好同名，老用户已有的值继续有效 */
    private static final String PREFS = "system_resource_prefs";
    private static final String KEY_SHELL_GUARD = "shell_guard_enabled";
    private static final String KEY_SSH_EXEC = "ssh_exec_enabled";
    private static final String KEY_DSH_NOTICE = "dsh_local_env_notice_shown";
    private static final String KEY_ALLOWED_TOOLS = "tool_approval_allowed";

    private SecuritySwitches() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** shell 防误操作黑名单（readonly）。默认开。 */
    public static boolean isShellGuardEnabled(Context context) {
        try {
            return prefs(context).getBoolean(KEY_SHELL_GUARD, true);
        } catch (Exception e) {
            return true;
        }
    }

    public static boolean setShellGuardEnabled(Context context, boolean enabled) {
        try {
            prefs(context).edit().putBoolean(KEY_SHELL_GUARD, enabled).apply();
            return prefs(context).getBoolean(KEY_SHELL_GUARD, true) == enabled;
        } catch (Exception e) {
            return false;
        }
    }

    /** SSH 远程执行（ssh_exec）。默认关，只允许用户在设置页打开。 */
    public static boolean isSshExecEnabled(Context context) {
        try {
            return prefs(context).getBoolean(KEY_SSH_EXEC, false);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean setSshExecEnabled(Context context, boolean enabled) {
        try {
            prefs(context).edit().putBoolean(KEY_SSH_EXEC, enabled).apply();
            return prefs(context).getBoolean(KEY_SSH_EXEC, false) == enabled;
        } catch (Exception e) {
            return false;
        }
    }

    /** 本地 Linux 环境（proot / 内置 DSH）的一次性说明是否已经展示过 */
    public static boolean isDshNoticeShown(Context context) {
        try {
            return prefs(context).getBoolean(KEY_DSH_NOTICE, false);
        } catch (Exception e) {
            return true;
        }
    }

    public static void markDshNoticeShown(Context context) {
        try {
            prefs(context).edit().putBoolean(KEY_DSH_NOTICE, true).apply();
        } catch (Exception ignored) {
            // 标记写不进去只影响"是否再次弹出"，不影响功能
        }
    }

    // ==================== 工具审批的全局放行 ====================
    // 语义：用户点了「始终允许」的工具，之后不再弹框（跨进程/跨会话有效），
    // 直到在 Agent 设置里一键清除。存字符串集合，保持稳定副本避免 SharedPreferences 共享引用问题。

    /** 已全局放行的工具名（副本，可安全遍历） */
    public static java.util.Set<String> allowedTools(Context context) {
        try {
            java.util.Set<String> raw = prefs(context).getStringSet(KEY_ALLOWED_TOOLS, null);
            return raw == null ? new java.util.LinkedHashSet<String>()
                    : new java.util.LinkedHashSet<>(raw);
        } catch (Exception e) {
            return new java.util.LinkedHashSet<>();
        }
    }

    public static boolean isToolAllowed(Context context, String toolName) {
        if (toolName == null || toolName.isEmpty()) {
            return false;
        }
        return allowedTools(context).contains(toolName);
    }

    public static boolean allowTool(Context context, String toolName) {
        if (toolName == null || toolName.isEmpty()) {
            return false;
        }
        try {
            java.util.Set<String> set = allowedTools(context);
            set.add(toolName);
            prefs(context).edit().putStringSet(KEY_ALLOWED_TOOLS, set).apply();
            return isToolAllowed(context, toolName);
        } catch (Exception e) {
            return false;
        }
    }

    public static int allowedToolCount(Context context) {
        return allowedTools(context).size();
    }

    /** 清除全部全局放行（设置页的"重置"） */
    public static boolean clearAllowedTools(Context context) {
        try {
            prefs(context).edit().remove(KEY_ALLOWED_TOOLS).apply();
            return allowedTools(context).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}
