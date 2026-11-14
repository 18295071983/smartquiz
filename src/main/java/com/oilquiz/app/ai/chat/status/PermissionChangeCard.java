package com.oilquiz.app.ai.chat.status;

import org.json.JSONObject;

/**
 * 权限槽的"变更卡"数据（2026-11-13）。
 *
 * <p>权限槽原来只是一行文案（沙箱 · 预设 · 策略），点了跳面板看不出能改什么。
 * 现在点文案弹出这张卡：列出可选的沙箱模式/审批策略，选完发 {@code permission.set}
 * 给电脑端插件。这些常量与 {@code /v2/action permission.set} 的取值一一对应。</p>
 */
public final class PermissionChangeCard {

    /** 沙箱模式（与 DSH sandbox/mode 事件取值一致） */
    public static final String[] SANDBOX_MODES = {
            "read-only", "workspace-write", "danger-full-access"
    };
    public static final String[] SANDBOX_LABELS = {
            "只读（只能读文件）",
            "可写工作区（推荐）",
            "完全访问（危险）",
    };

    /** 审批策略（与 approval/policy 事件取值一致） */
    public static final String[] POLICIES = {"never", "ask"};
    public static final String[] POLICY_LABELS = {
            "审批：从不询问（自动放行/拒绝）",
            "审批：每次询问（手机可点）",
    };

    private PermissionChangeCard() {
    }

    /** 弹窗条目：先沙箱、后策略（顺序即分组） */
    public static CharSequence[] items() {
        CharSequence[] out = new CharSequence[SANDBOX_LABELS.length + POLICY_LABELS.length];
        for (int i = 0; i < SANDBOX_LABELS.length; i += 1) out[i] = SANDBOX_LABELS[i];
        for (int i = 0; i < POLICY_LABELS.length; i += 1) {
            out[SANDBOX_LABELS.length + i] = POLICY_LABELS[i];
        }
        return out;
    }

    /** 预设值 → 中文标签（认不出就用原值，别把新预设藏掉） */
    public static String labelFor(String preset) {
        if (preset == null || preset.isEmpty()) return "";
        for (int i = 0; i < SANDBOX_MODES.length; i += 1) {
            if (SANDBOX_MODES[i].equals(preset)) return SANDBOX_LABELS[i];
        }
        return preset;
    }

    /** 点第 index 项时发什么请求（null = 越界） */
    public static JSONObject requestFor(int index) {
        JSONObject body = new JSONObject();
        try {
            body.put("action", "permission.set");
            if (index < 0) return null;
            if (index < SANDBOX_LABELS.length) {
                body.put("sandbox", SANDBOX_MODES[index]);
            } else if (index < SANDBOX_LABELS.length + POLICY_LABELS.length) {
                body.put("policy", POLICIES[index - SANDBOX_LABELS.length]);
            } else {
                return null;
            }
        } catch (Exception ignored) {
            return null;
        }
        return body;
    }

    /** 标题（带当前值，方便确认改的是什么） */
    public static String title(String currentDetail) {
        String cur = currentDetail == null ? "" : currentDetail.trim();
        return cur.isEmpty() ? "变更权限" : ("变更权限（当前：" + cur + "）");
    }
}
