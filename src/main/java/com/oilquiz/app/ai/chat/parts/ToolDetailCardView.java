package com.oilquiz.app.ai.chat.parts;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.chat.ToolMetaRegistry;
import com.oilquiz.app.ai.chat.component.ComponentColors;
import com.oilquiz.app.ai.chat.component.SimpleLineDiff;
import com.oilquiz.app.ai.chat.turn.AssistantTurn;
import com.oilquiz.app.theme.Tokens;
import com.oilquiz.app.ui.modal.AppSheet;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具详情专用卡（2026-11-18）。
 *
 * <p><b>问题</b>：工具详情一直是"一整段等宽文本"塞进弹层（{@code ToolDetailSheet}），
 * 参数/结果平铺、没有层级；读文件、跑命令、改代码、搜结果看起来一模一样，用户只能自己找。</p>
 *
 * <p><b>做法</b>：不引第三方卡片库（项目也没有合适的依赖），用现有原语拼一张"按类型分块"的卡：</p>
 * <ul>
 *   <li>头部：状态点 + 工具展示名 + 状态（运行中/已完成/失败）+ 耗时；</li>
 *   <li>参数区：只挑已知键（命令/路径/查询/匹配/链接/目标）做"标签 + 等宽值"，
 *       未知形状才退回 JSON 预览；</li>
 *   <li>正文区：editing 类工具（{@code old_string}/{@code new_string}）渲染 +/- 行级 diff；
 *       其余工具渲染结果块（等宽、可选中、超长截断并提示复制）；</li>
 *   <li>复制：弹层底部动作复制"参数 + 结果"全文。</li>
 * </ul>
 *
 * <p>纯 View 组装，便于单测按文本断言；颜色/间距全部走 {@link ComponentColors}/{@link Tokens}。</p>
 */
public final class ToolDetailCardView {

    /** 结果块最多展示的字符数：再长就不是"看"而是"复制"了，避免一次性 setText 卡主线程。 */
    static final int MAX_RESULT_CHARS = 20_000;

    private ToolDetailCardView() {
    }

    /** 组装详情卡（可直接嵌 ScrollView / 弹层）。 */
    public static View build(Context ctx, AssistantTurn.Part call) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Tokens.spaceMd(ctx), Tokens.spaceMd(ctx),
                Tokens.spaceMd(ctx), Tokens.spaceMd(ctx));
        root.setBackground(cardBackground(ctx));

        String toolName = call == null || call.toolName == null ? "" : call.toolName;
        String display = displayName(toolName);
        String status = ToolCallPartRenderer.ToolCardProps.normalizeStatus(
                call == null ? null : call.status);
        Kind kind = kindOf(toolName);
        int accent = accentFor(ctx, kind);

        // 顶部 3dp 类型色条：一眼区分"这是 diff / 终端 / 文件 / 搜索"，而不是每张卡长得一样
        View strip = new View(ctx);
        GradientDrawable stripBg = new GradientDrawable();
        stripBg.setColor(accent);
        stripBg.setCornerRadius(Tokens.spaceXs(ctx));
        strip.setBackground(stripBg);
        LinearLayout.LayoutParams stripLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(3, Tokens.spaceXs(ctx)));
        stripLp.bottomMargin = Tokens.spaceSm(ctx);
        root.addView(strip, stripLp);

        root.addView(buildHeader(ctx, display, status, call, kind, accent));
        root.addView(buildMeta(ctx, call));
        root.addView(buildBody(ctx, call, status, kind));
        return root;
    }

    /** 弹层入口：标题 = 工具展示名，内容 = 详情卡，动作 = 复制/关闭。 */
    public static void showSheet(Context ctx, AssistantTurn.Part call) {
        if (ctx == null || call == null) {
            return;
        }
        String title = displayName(call.toolName);
        ScrollView scroll = new ScrollView(ctx);
        final View card = build(ctx, call);
        scroll.addView(card, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        String copyText = copyText(call);
        AppSheet.builder(ctx)
                .title(title)
                .content(scroll)
                .onDismiss(dialog -> AssetRenderer.cancel(card))
                .action("复制", false, v -> copy(ctx, copyText))
                .action("关闭", true, null)
                .show();
    }

    // ---------------- 头部 ----------------

    private static View buildHeader(Context ctx, String display, String status,
                                    AssistantTurn.Part call, Kind kind, int accent) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView dot = new TextView(ctx);
        dot.setText("●");
        dot.setTextSize(Tokens.textSizeSp(ctx, Tokens.TEXT_CAPTION_SM));
        dot.setTextColor(statusColor(ctx, status));
        row.addView(dot);

        // 类型徽标（终端/改动/文件/搜索/图片）：不同工具的卡片在头部就能区分开
        TextView badge = new TextView(ctx);
        badge.setText(kindLabel(kind));
        badge.setTextSize(Tokens.textSizeSp(ctx, Tokens.TEXT_MICRO));
        badge.setTextColor(accent);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setSingleLine(true);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        badgeLp.setMarginStart(Tokens.spaceSm(ctx));
        badgeLp.setMarginEnd(Tokens.spaceXs(ctx));
        row.addView(badge, badgeLp);

        TextView name = new TextView(ctx);
        name.setText(display);
        name.setTextSize(Tokens.textSizeSp(ctx, Tokens.TEXT_SUBTITLE));
        name.setTextColor(ComponentColors.textPrimary(ctx));
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nameLp.setMarginStart(Tokens.spaceSm(ctx));
        row.addView(name, nameLp);

        if (call != null && call.durationMs > 0L) {
            TextView duration = new TextView(ctx);
            duration.setText(formatDuration(call.durationMs));
            duration.setTextSize(Tokens.textSizeSp(ctx, Tokens.TEXT_CAPTION_SM));
            duration.setTextColor(ComponentColors.textTertiary(ctx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(Tokens.spaceSm(ctx));
            row.addView(duration, lp);
        }

        TextView statusView = new TextView(ctx);
        statusView.setText(statusText(status));
        statusView.setTextSize(Tokens.textSizeSp(ctx, Tokens.TEXT_CAPTION_SM));
        statusView.setTextColor(statusColor(ctx, status));
        row.addView(statusView);
        return row;
    }

    // ---------------- 参数 ----------------

    /**
     * 参数区：只展示已知键，未知形状退回 JSON 预览。
     * 返回值可能是空 View（无参数时），由调用方直接 addView。
     */
    private static View buildMeta(Context ctx, AssistantTurn.Part call) {
        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        JSONObject args = parseArgs(call == null ? null : call.args);
        if (args == null || args.length() == 0) {
            return column;
        }
        int rows = 0;
        rows += addMetaRow(ctx, column, "命令", args.optString("command", ""));
        rows += addMetaRow(ctx, column, "路径", firstNonEmpty(
                firstNonEmpty(args.optString("file_path", ""), args.optString("path", "")),
                com.oilquiz.app.ai.chat.util.ToolResultText.pathOf(
                        call == null ? null : call.result)));
        rows += addMetaRow(ctx, column, "类型", com.oilquiz.app.ai.chat.util.ToolResultText.typeOf(
                call == null ? null : call.result));
        rows += addMetaRow(ctx, column, "查询", args.optString("query", ""));
        rows += addMetaRow(ctx, column, "匹配", args.optString("pattern", ""));
        rows += addMetaRow(ctx, column, "链接", args.optString("url", ""));
        rows += addMetaRow(ctx, column, "目标", args.optString("target", ""));
        String content = args.optString("content", "");
        if (!content.isEmpty()) {
            rows += addMetaRow(ctx, column, "写入", content.split("\n", -1).length + " 行");
        }
        if (rows == 0) {
            // 没有已知键：给一段 JSON 预览，别把原始参数整段糊上去
            String pretty = args.toString();
            addSectionLabel(ctx, column, "参数", false);
            TextView json = monoText(ctx, pretty.length() > 600
                    ? pretty.substring(0, 600) + "…" : pretty);
            column.addView(json);
        }
        return column;
    }

    private static int addMetaRow(Context ctx, LinearLayout column, String label, String value) {
        if (TextUtils.isEmpty(value)) {
            return 0;
        }
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = Tokens.spaceXs(ctx);

        TextView key = new TextView(ctx);
        key.setText(label);
        key.setTextSize(Tokens.textSizeSp(ctx, Tokens.TEXT_CAPTION_SM));
        key.setTextColor(ComponentColors.textTertiary(ctx));
        key.setMinWidth(Tokens.space3xl(ctx));
        row.addView(key);

        TextView val = new TextView(ctx);
        val.setText(value.replaceAll("\\s+", " ").trim());
        val.setTextSize(Tokens.textSizeSp(ctx, Tokens.TEXT_CAPTION_SM));
        val.setTextColor(ComponentColors.textSecondary(ctx));
        val.setTypeface(Typeface.MONOSPACE);
        val.setSingleLine(true);
        val.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams valLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        valLp.setMarginStart(Tokens.spaceSm(ctx));
        row.addView(val, valLp);
        column.addView(row, rowLp);
        return 1;
    }

    // ---------------- 正文 ----------------

    private static View buildBody(Context ctx, AssistantTurn.Part call, String status, Kind kind) {
        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        String raw = call == null || call.result == null ? "" : call.result;
        // <path>/<type>/<content> 这类结构化结果先拆成人话，别把 XML 平铺给用户
        String result = com.oilquiz.app.ai.chat.util.ToolResultText.renderable(raw);

        if (call != null && "running".equals(status)) {
            return column; // 运行中还没结果：头部状态点已经表达，不占位
        }

        // 有 <ref> 就交给文件渲染器（图片/代码/diff/pdf/音视频/文件），文字只做补充
        ToolAssetRef ref = ToolAssetRef.parse(raw);
        if (ref == null) {
            // 过渡口径：工具还没回 <ref> 时，用结果里的 <path> 合成引用（id=路径哈希），
            // 由插件 file_get 现取；路径不可读时插件回错，渲染器出重试卡。
            String p = com.oilquiz.app.ai.chat.util.ToolResultText.pathOf(raw);
            if (!p.isEmpty()) {
                ref = ToolAssetRef.fromPath(p);
            }
        }
        if (ref != null) {
            addSectionLabel(ctx, column, ref.isImage() ? "图片" : "文件", true);
            column.addView(AssetRenderer.build(ctx, ref, displayName(call.toolName)));
            // 不再把结果里的代码串贴出来：文件类一律以"完整文件"为准（点击打开交给内置渲染器）
            return column;
        }

        // 网络图片：结果里直接给了 http(s) 链接（工具不一定有本地 path）
        String imageUrl = firstImageUrl(raw);
        if (imageUrl != null) {
            View webImage = AssetRenderer.buildUrl(ctx, imageUrl);
            if (webImage != null) {
                addSectionLabel(ctx, column, "图片", true);
                column.addView(webImage);
                return column;
            }
        }

        if (kind == Kind.EDIT) {
            List<SimpleLineDiff.Line> diff = diffOf(call);
            if (!diff.isEmpty()) {
                addSectionLabel(ctx, column, "改动", true);
                column.addView(diffView(ctx, diff));
                if (!result.trim().isEmpty()) {
                    addSectionLabel(ctx, column, "结果", true);
                    column.addView(resultView(ctx, result));
                }
                return column;
            }
        }
        if (kind == Kind.TERMINAL) {
            column.addView(terminalBlock(ctx, call, result));
            return column;
        }
        if (kind == Kind.READ && !result.trim().isEmpty()) {
            addSectionLabel(ctx, column, "内容", true);
            column.addView(codeView(ctx, numberLines(result, 300), langOf(call)));
            return column;
        }
        if (kind == Kind.SEARCH) {
            View list = searchList(ctx, result);
            if (list != null) {
                addSectionLabel(ctx, column, "匹配", true);
                column.addView(list);
                return column;
            }
        }
        if (TextUtils.isEmpty(result.trim())) {
            return column;
        }
        addSectionLabel(ctx, column, "结果", true);
        column.addView(resultView(ctx, result));
        return column;
    }

    /** 终端输出：ANSI SGR 着色（颜色/加粗），其余控制序列丢弃。 */
    private static View ansiView(Context ctx, String text) {
        TextView tv = monoText(ctx, "");
        tv.setText(AnsiSgr.render(ctx, text));
        tv.setTextIsSelectable(true);
        tv.setLineSpacing(0f, 1.35f);
        return tv;
    }

    /** 代码块：等宽 + 四类着色（关键字/字符串/注释/数字）。 */
    private static View codeView(Context ctx, String code, String lang) {
        TextView tv = monoText(ctx, "");
        tv.setText(CodeHighlighter.highlight(ctx, code, lang));
        tv.setTextIsSelectable(true);
        tv.setLineSpacing(0f, 1.35f);
        return tv;
    }

    /** 语言判定：优先看路径后缀，其次工具名（终端当 shell）。 */
    private static String langOf(AssistantTurn.Part call) {
        if (call == null) return "";
        org.json.JSONObject args = parseArgs(call.args);
        String path = args == null ? "" : firstNonEmpty(
                args.optString("file_path", ""), args.optString("path", ""));
        String p = path.toLowerCase(java.util.Locale.US);
        if (p.endsWith(".java")) return "java";
        if (p.endsWith(".kt") || p.endsWith(".kts")) return "kotlin";
        if (p.endsWith(".py")) return "python";
        if (p.endsWith(".json")) return "json";
        if (p.endsWith(".sh") || p.endsWith(".ps1")) return "shell";
        String n = call.toolName == null ? "" : call.toolName.toLowerCase(java.util.Locale.US);
        if (n.contains("python")) return "python";
        return "shell";
    }

    /**
     * grep 结果列表：把 {@code 文件:行:内容} 解析成"路径:行号 + 片段"的行，最多 30 条。
     * 解析不出就返回 null，交给通用结果块（不硬凑）。
     */
    private static View searchList(Context ctx, String result) {
        if (TextUtils.isEmpty(result)) return null;
        String[] lines = result.split("\n");
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(":(\\d+):");
        for (String line : lines) {
            java.util.regex.Matcher m = p.matcher(line);
            if (!m.find()) continue;
            String path = line.substring(0, m.start()).trim();
            String no = m.group(1);
            String text = line.substring(m.end()).trim();
            if (path.isEmpty()) continue;
            rows.add(new String[]{path, no, text});
            if (rows.size() >= 30) break;
        }
        if (rows.isEmpty()) return null;
        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        for (String[] r : rows) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Tokens.spaceSm(ctx);
            TextView head = new TextView(ctx);
            head.setText(r[0] + ":" + r[1]);
            head.setTextSize(Tokens.textSizeSp(ctx, Tokens.TEXT_CAPTION_SM));
            head.setTextColor(ComponentColors.accent(ctx));
            head.setTypeface(Typeface.MONOSPACE);
            head.setSingleLine(true);
            head.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            row.addView(head);
            if (!r[2].isEmpty()) {
                TextView body = new TextView(ctx);
                body.setText(r[2]);
                body.setTextSize(Tokens.textSizeSp(ctx, Tokens.TEXT_CAPTION_SM));
                body.setTextColor(ComponentColors.textSecondary(ctx));
                body.setMaxLines(2);
                body.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(body);
            }
            final String matchPath = r[0];
            row.setClickable(true);
            row.setOnClickListener(v -> ToolAssetPreview.show(ctx, matchPath));
            column.addView(row, lp);
        }
        if (lines.length > rows.size()) {
            TextView more = new TextView(ctx);
            more.setText("还有 " + (lines.length - rows.size()) + " 行未展开");
            more.setTextSize(Tokens.textSizeSp(ctx, Tokens.TEXT_CAPTION_SM));
            more.setTextColor(ComponentColors.textTertiary(ctx));
            column.addView(more);
        }
        return column;
    }

    /** 终端皮肤：`$ 命令` 提示行 + 输出块，和文件/搜索卡一眼分开。 */
    private static View terminalBlock(Context ctx, AssistantTurn.Part call, String result) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Tokens.spaceSm(ctx), Tokens.spaceSm(ctx),
                Tokens.spaceSm(ctx), Tokens.spaceSm(ctx));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ComponentColors.fieldBg(ctx));
        bg.setCornerRadius(Tokens.spaceSm(ctx));
        box.setBackground(bg);

        String command = "";
        org.json.JSONObject args = parseArgs(call == null ? null : call.args);
        if (args != null) {
            command = args.optString("command", "");
        }
        TextView prompt = new TextView(ctx);
        prompt.setText("$ " + command);
        prompt.setTextSize(Tokens.textSizeSp(ctx, Tokens.TEXT_CAPTION));
        prompt.setTextColor(ComponentColors.success(ctx));
        prompt.setTypeface(Typeface.MONOSPACE);
        prompt.setSingleLine(false);
        box.addView(prompt);

        if (!TextUtils.isEmpty(result.trim())) {
            View out = ansiView(ctx, result);
            out.setBackground(null);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Tokens.spaceXs(ctx);
            box.addView(out, lp);
        }
        return box;
    }

    /** 文件皮肤：结果加行号（上限 300 行），像代码而不是一段散文。 */
    private static String numberLines(String text, int maxLines) {
        String[] lines = text.split("\n", -1);
        int n = Math.min(lines.length, Math.max(1, maxLines));
        StringBuilder sb = new StringBuilder();
        int width = String.valueOf(n).length();
        for (int i = 0; i < n; i++) {
            String no = String.valueOf(i + 1);
            for (int p = no.length(); p < width; p++) {
                sb.append(' ');
            }
            sb.append(no).append("  ").append(lines[i]).append('\n');
        }
        if (lines.length > n) {
            sb.append("…（共 ").append(lines.length).append(" 行）");
        }
        return sb.toString().trim();
    }

    private static View resultView(Context ctx, String result) {
        String text = result;
        if (text.length() > MAX_RESULT_CHARS) {
            text = text.substring(0, MAX_RESULT_CHARS) + "\n…（已截断，复制可看全文）";
        }
        TextView tv = monoText(ctx, text);
        tv.setTextIsSelectable(true);
        tv.setLineSpacing(0f, 1.35f);
        return tv;
    }

    private static View diffView(Context ctx, List<SimpleLineDiff.Line> lines) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        int addBg = withAlpha(ComponentColors.success(ctx), 0.16f);
        int delBg = withAlpha(ComponentColors.error(ctx), 0.16f);
        for (SimpleLineDiff.Line line : lines) {
            int start = sb.length();
            String prefix = line.kind == SimpleLineDiff.Kind.ADD ? "+ "
                    : line.kind == SimpleLineDiff.Kind.DEL ? "- " : "  ";
            sb.append(prefix).append(line.text).append('\n');
            int end = sb.length();
            if (line.kind == SimpleLineDiff.Kind.ADD) {
                sb.setSpan(new android.text.style.BackgroundColorSpan(addBg), start, end,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (line.kind == SimpleLineDiff.Kind.DEL) {
                sb.setSpan(new android.text.style.BackgroundColorSpan(delBg), start, end,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        TextView tv = monoText(ctx, "");
        tv.setText(sb);
        tv.setTextIsSelectable(true);
        return tv;
    }

    /** 颜色加透明度（不写死 hex，保持主题色）。 */
    private static int withAlpha(int color, float alpha) {
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
        return (color & 0x00FFFFFF) | (a << 24);
    }

    /** 把 diff 行渲染成带 +/- 前缀的等宽文本（颜色由前景色统一，避免一行一个 Span 太重）。 */    private static String renderDiff(List<SimpleLineDiff.Line> lines) {
        StringBuilder sb = new StringBuilder();
        for (SimpleLineDiff.Line line : lines) {
            String prefix = line.kind == SimpleLineDiff.Kind.ADD ? "+ "
                    : line.kind == SimpleLineDiff.Kind.DEL ? "- " : "  ";
            sb.append(prefix).append(line.text).append('\n');
        }
        return sb.toString().trim();
    }

    private static List<SimpleLineDiff.Line> diffOf(AssistantTurn.Part call) {
        if (call == null) {
            return new ArrayList<>();
        }
        JSONObject args = parseArgs(call.args);
        if (args == null) {
            return new ArrayList<>();
        }
        String before = args.optString("old_string", "");
        String after = args.optString("new_string", "");
        if (before.isEmpty() || after.isEmpty()) {
            return new ArrayList<>();
        }
        return SimpleLineDiff.hunks(before, after, 3);
    }

    // ---------------- 小工具 ----------------

    private static TextView monoText(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(Tokens.textSizeSp(ctx, Tokens.TEXT_CAPTION));
        tv.setTextColor(ComponentColors.textSecondary(ctx));
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setPadding(Tokens.spaceSm(ctx), Tokens.spaceSm(ctx),
                Tokens.spaceSm(ctx), Tokens.spaceSm(ctx));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ComponentColors.fieldBg(ctx));
        bg.setCornerRadius(Tokens.spaceSm(ctx));
        tv.setBackground(bg);
        return tv;
    }

    private static void addSectionLabel(Context ctx, LinearLayout column, String text,
                                        boolean withTopGap) {
        TextView label = new TextView(ctx);
        label.setText(text);
        label.setTextSize(Tokens.textSizeSp(ctx, Tokens.TEXT_CAPTION_SM));
        label.setTextColor(ComponentColors.textTertiary(ctx));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = withTopGap ? Tokens.spaceMd(ctx) : Tokens.spaceSm(ctx);
        lp.bottomMargin = Tokens.spaceXs(ctx);
        column.addView(label, lp);
    }

    static GradientDrawable cardBackground(Context ctx) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ComponentColors.fieldBg(ctx));
        bg.setCornerRadius(ctx.getResources().getDimensionPixelSize(R.dimen.chat_v2_scene_radius));
        bg.setStroke(1, ComponentColors.border(ctx));
        return bg;
    }

    private static String firstImageUrl(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("https?://[^\\s\"'<>]+").matcher(text);
        while (m.find()) {
            String u = m.group();
            String lower = u.toLowerCase(java.util.Locale.US);
            if (lower.contains(".png") || lower.contains(".jpg") || lower.contains(".jpeg")
                    || lower.contains(".webp") || lower.contains(".gif")) {
                return u;
            }
        }
        return null;
    }

    /** 工具类型 → 专用皮肤（2026-11-18：每类一张，不再所有工具一个样）。 */
    enum Kind { TERMINAL, EDIT, READ, SEARCH, IMAGE, GENERIC }

    static Kind kindOf(String toolName) {
        String n = toolName == null ? "" : toolName.toLowerCase(java.util.Locale.US);
        if (n.contains("image")) return Kind.IMAGE;
        if (n.contains("edit") || n.contains("write") || n.contains("patch")
                || n.contains("apply")) return Kind.EDIT;
        if (n.contains("python") || n.contains("run_code") || n.contains("execute")
                || n.contains("pwsh") || n.contains("shell") || n.contains("bash")
                || n.contains("terminal") || n.contains("cmd")) return Kind.TERMINAL;
        if (n.contains("grep") || n.contains("search") || n.contains("find")
                || n.contains("glob")) return Kind.SEARCH;
        if (n.contains("read") || n.contains("cat") || n.contains("file")) return Kind.READ;
        return Kind.GENERIC;
    }

    static String kindLabel(Kind kind) {
        switch (kind) {
            case TERMINAL: return "终端";
            case EDIT: return "改动";
            case READ: return "文件";
            case SEARCH: return "搜索";
            case IMAGE: return "图片";
            default: return "工具";
        }
    }

    private static int accentFor(Context ctx, Kind kind) {
        switch (kind) {
            case TERMINAL: return ComponentColors.success(ctx);
            case EDIT: return ComponentColors.warning(ctx);
            case READ: return ComponentColors.accent(ctx);
            case SEARCH: return ComponentColors.accent(ctx);
            case IMAGE: return ComponentColors.accent(ctx);
            default: return ComponentColors.textTertiary(ctx);
        }
    }

    private static String displayName(String toolName) {
        if (toolName == null || toolName.trim().isEmpty()) {
            return "工具";
        }
        try {
            String display = ToolMetaRegistry.displayName(toolName.trim());
            return TextUtils.isEmpty(display) ? toolName : display;
        } catch (Throwable t) {
            return toolName;
        }
    }

    private static String statusText(String normalized) {
        if ("running".equals(normalized)) return "运行中";
        if ("failed".equals(normalized)) return "失败";
        return "已完成";
    }

    private static int statusColor(Context ctx, String normalized) {
        if ("running".equals(normalized)) return ComponentColors.accent(ctx);
        if ("failed".equals(normalized)) return ComponentColors.error(ctx);
        return ComponentColors.success(ctx);
    }

    private static String formatDuration(long ms) {
        return String.format(java.util.Locale.US, "%.1fs", ms / 1000.0);
    }

    private static JSONObject parseArgs(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return null;
        }
        try {
            return new JSONObject(raw);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String firstNonEmpty(String a, String b) {
        return TextUtils.isEmpty(a) ? b : a;
    }

    private static String copyText(AssistantTurn.Part call) {
        StringBuilder sb = new StringBuilder();
        if (call == null) {
            return "";
        }
        if (!TextUtils.isEmpty(call.args)) {
            sb.append("参数\n").append(call.args).append("\n\n");
        }
        if (!TextUtils.isEmpty(call.result)) {
            sb.append("结果\n").append(call.result);
        }
        return sb.toString().trim();
    }

    private static void copy(Context ctx, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("tool_detail", text));
            }
        } catch (Throwable ignored) {
            // 复制失败不影响查看
        }
    }
}
