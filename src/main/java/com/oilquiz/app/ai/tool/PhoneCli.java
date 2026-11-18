package com.oilquiz.app.ai.tool;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 手机端 CLI（2026-11-18）——电脑远程调试手机 App / agent 的统一命令面。
 *
 * <p>为什么是"App 内置解释器"而不是独立二进制：不用 root / adb / Termux，
 * 直接用 App 自己的权限（工具系统、当前 Activity、日志），电脑端只通过插件转发一条
 * {@code cli.exec}（最终走 {@link RemoteCommandExecutor} 的命令队列），
 * 结果按 {@code cmd_ack} 原路回传。</p>
 *
 * <p>命令是**白名单**（{@link #exec} 里的 switch），插件端无法扩权。
 * 当前 P1 只做只读命令：help / version / tools list / tools show / ui dump / ui find / logcat(降级)。
 * exec 级（input / tools call / app start）在 P2 接入，并强制手机端确认。</p>
 */
public final class PhoneCli {

    private PhoneCli() {
    }

    /** 执行结果：退出码 + stdout + stderr（与普通 CLI 语义一致）。 */
    public static final class Result {
        public final int code;
        public final String stdout;
        public final String stderr;

        Result(int code, String stdout, String stderr) {
            this.code = code;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
        }
    }

    /** 执行一条命令（argv 为完整命令行字符串，支持双引号）。 */
    public static Result exec(Context ctx, String argv) {
        try {
            String[] parts = split(argv);
            if (parts.length == 0) {
                return new Result(2, help(), "");
            }
            String cmd = parts[0].toLowerCase();
            switch (cmd) {
                case "help":
                    return new Result(0, help(), "");
                case "version":
                    return new Result(0, "phone-cli/1.0 · " + Build.MANUFACTURER + " " + Build.MODEL
                            + " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")", "");
                case "tools":
                    return tools(ctx, parts);
                case "ui":
                    return ui(ctx, parts);
                case "logcat":
                    // 非 root 读不到系统日志，先明确告诉调用方，别假装成功
                    return new Result(1, "", "logcat 需要 READ_LOGS（非 root 不可用）；用 phone.event.* 或 App 内日志接口");
                default:
                    return new Result(2, "", "未知命令: " + cmd + "（help 查看可用命令）");
            }
        } catch (Throwable t) {
            return new Result(1, "", String.valueOf(t));
        }
    }

    // ==================== tools ====================

    private static Result tools(Context ctx, String[] parts) {
        AIToolManager mgr = AIToolManager.getInstance(ctx);
        String sub = parts.length > 1 ? parts[1].toLowerCase() : "list";
        if ("list".equals(sub)) {
            List<String> names = new ArrayList<>(mgr.getRegisteredToolNames());
            java.util.Collections.sort(names);
            StringBuilder sb = new StringBuilder();
            sb.append("tools=").append(names.size()).append('\n');
            for (String name : names) {
                String desc = "";
                try {
                    AITool tool = mgr.getTool(name);
                    if (tool != null && tool.getDescription() != null) {
                        desc = oneLine(tool.getDescription(), 72);
                    }
                } catch (Throwable ignored) {
                }
                sb.append(name).append(" — ").append(desc).append('\n');
            }
            return new Result(0, sb.toString(), "");
        }
        if ("show".equals(sub)) {
            if (parts.length < 3) {
                return new Result(2, "", "用法: tools show <name>");
            }
            String name = parts[2];
            AITool tool = mgr.getTool(name);
            if (tool == null) {
                return new Result(1, "", "找不到工具: " + name);
            }
            StringBuilder sb = new StringBuilder();
            sb.append("name: ").append(name).append('\n');
            sb.append("desc: ").append(tool.getDescription() == null ? "" : tool.getDescription()).append('\n');
            Map<String, String> params = tool.getParameterDescriptions();
            if (params != null && !params.isEmpty()) {
                sb.append("params:\n");
                for (Map.Entry<String, String> e : params.entrySet()) {
                    sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
                }
            }
            return new Result(0, sb.toString(), "");
        }
        return new Result(2, "", "用法: tools list | tools show <name>");
    }

    // ==================== ui ====================

    private static Result ui(Context ctx, String[] parts) {
        String sub = parts.length > 1 ? parts[1].toLowerCase() : "dump";
        Activity act = com.oilquiz.app.SmartQuizApplication.getCurrentActivity();
        if (act == null) {
            return new Result(1, "", "当前没有前台 Activity");
        }
        View root = act.getWindow() == null ? null : act.getWindow().getDecorView();
        if (root == null) {
            return new Result(1, "", "拿不到根 View");
        }
        if ("dump".equals(sub)) {
            int maxDepth = 12;
            boolean json = false;
            for (int i = 2; i < parts.length; i++) {
                String p = parts[i];
                if ("--json".equals(p)) {
                    json = true;
                } else if (p.startsWith("--max-depth")) {
                    String v = p.contains("=") ? p.substring(p.indexOf('=') + 1)
                            : (i + 1 < parts.length ? parts[i + 1] : null);
                    try {
                        maxDepth = Integer.parseInt(String.valueOf(v).trim());
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (json) {
                JSONArray arr = new JSONArray();
                collectJson(root, 0, maxDepth, arr, ctx.getResources());
                return new Result(0, arr.toString(), "");
            }
            StringBuilder sb = new StringBuilder();
            sb.append("activity=").append(act.getClass().getSimpleName())
                    .append(" size=").append(root.getWidth()).append('x').append(root.getHeight())
                    .append('\n');
            collectText(root, 0, maxDepth, sb, ctx.getResources());
            return new Result(0, sb.toString(), "");
        }
        if ("find".equals(sub)) {
            String want = null;
            for (int i = 2; i < parts.length; i++) {
                String p = parts[i];
                if (p.startsWith("--text=")) {
                    want = p.substring("--text=".length());
                } else if ("--text".equals(p) && i + 1 < parts.length) {
                    want = parts[i + 1];
                }
            }
            if (want == null || want.isEmpty()) {
                return new Result(2, "", "用法: ui find --text=<关键字>");
            }
            StringBuilder sb = new StringBuilder();
            int hits = findText(root, want, sb, ctx.getResources());
            return new Result(hits > 0 ? 0 : 1,
                    hits > 0 ? sb.toString() : "未找到: " + want, "");
        }
        return new Result(2, "", "用法: ui dump [--max-depth N] [--json] | ui find --text=<关键字>");
    }

    private static void collectText(View v, int depth, int maxDepth, StringBuilder sb, Resources res) {
        if (v == null || depth > maxDepth) return;
        sb.append(repeat("  ", depth)).append(v.getClass().getSimpleName());
        String id = idName(v, res);
        if (!id.isEmpty()) sb.append('#').append(id);
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (!TextUtils.isEmpty(t)) {
                String s = t.toString().replace('\n', ' ');
                sb.append(" \"").append(oneLine(s, 60)).append('"');
            }
        }
        sb.append(' ').append(bounds(v));
        if (v.getVisibility() != View.VISIBLE) sb.append(" (").append(visName(v)).append(')');
        sb.append('\n');
        if (v instanceof ViewGroup && depth < maxDepth) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectText(g.getChildAt(i), depth + 1, maxDepth, sb, res);
            }
        }
    }

    private static void collectJson(View v, int depth, int maxDepth, JSONArray arr, Resources res) {
        if (v == null || depth > maxDepth) return;
        try {
            JSONObject o = new JSONObject();
            o.put("depth", depth);
            o.put("class", v.getClass().getSimpleName());
            String id = idName(v, res);
            if (!id.isEmpty()) o.put("id", id);
            if (v instanceof TextView) {
                CharSequence t = ((TextView) v).getText();
                if (!TextUtils.isEmpty(t)) o.put("text", oneLine(t.toString(), 120));
            }
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            JSONArray b = new JSONArray();
            b.put(loc[0]).put(loc[1]).put(v.getWidth()).put(v.getHeight());
            o.put("bounds", b);
            o.put("visible", v.getVisibility() == View.VISIBLE);
            arr.put(o);
            if (v instanceof ViewGroup && depth < maxDepth) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    collectJson(g.getChildAt(i), depth + 1, maxDepth, arr, res);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static int findText(View v, String want, StringBuilder sb, Resources res) {
        if (v == null) return 0;
        int hits = 0;
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (!TextUtils.isEmpty(t) && t.toString().contains(want)) {
                sb.append(v.getClass().getSimpleName());
                String id = idName(v, res);
                if (!id.isEmpty()) sb.append('#').append(id);
                sb.append(" \"").append(oneLine(t.toString(), 60)).append("\" ")
                        .append(bounds(v)).append('\n');
                hits++;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                hits += findText(g.getChildAt(i), want, sb, res);
            }
        }
        return hits;
    }

    private static String idName(View v, Resources res) {
        try {
            int id = v.getId();
            if (id == View.NO_ID) return "";
            return res.getResourceEntryName(id);
        } catch (Throwable t) {
            return "";
        }
    }

    private static String bounds(View v) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        Rect r = new Rect(loc[0], loc[1], loc[0] + v.getWidth(), loc[1] + v.getHeight());
        return "[" + r.left + "," + r.top + "," + r.right + "," + r.bottom + "]";
    }

    private static String visName(View v) {
        switch (v.getVisibility()) {
            case View.INVISIBLE:
                return "invisible";
            case View.GONE:
                return "gone";
            default:
                return "visible";
        }
    }

    // ==================== 工具 ====================

    private static String help() {
        return "phone-cli 1.0（只读 P1）\n"
                + "  help                              显示本帮助\n"
                + "  version                           版本/机型\n"
                + "  tools list                        列出手机端全部工具\n"
                + "  tools show <name>                 某工具的描述与参数\n"
                + "  ui dump [--max-depth N] [--json]  当前前台 View 树\n"
                + "  ui find --text=<关键字>           按文字找控件（bounds）\n"
                + "\nP2 将加入（需手机端确认）：input tap/swipe/key/text、tools call、app start、screen。\n";
    }

    /** 简单分词：支持双引号包裹（"a b"）。 */
    static String[] split(String argv) {
        if (argv == null) return new String[0];
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quote = false;
        for (int i = 0; i < argv.length(); i++) {
            char c = argv.charAt(i);
            if (c == '"') {
                quote = !quote;
                continue;
            }
            if (!quote && Character.isWhitespace(c)) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                continue;
            }
            cur.append(c);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    private static String oneLine(String s, int max) {
        String v = s == null ? "" : s.replace('\n', ' ').replace('\r', ' ').trim();
        return v.length() > max ? v.substring(0, max) + "…" : v;
    }

    private static String repeat(String unit, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(unit);
        return sb.toString();
    }
}
