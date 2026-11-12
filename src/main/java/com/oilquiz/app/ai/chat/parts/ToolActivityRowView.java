package com.oilquiz.app.ai.chat.parts;

import android.content.Context;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.chat.ui.ToolRowStyle;
import com.oilquiz.app.ai.chat.turn.AssistantTurn;
import com.oilquiz.app.ai.chat.util.ToolResultText;

/**
 * 工具活动行（parts §11.1 对齐 legacy 观感，2026-10-21）。
 *
 * <p>legacy 的组卡里每个工具是<b>一行紧凑</b> {@code R.layout.item_chat_v2_tool_row}
 * （{@code ChatAdapter#buildToolActivityRow}），点一行展开<b>该工具的结果全文</b>；
 * parts 早先渲成整张 {@code ToolCallCardView}，展开后是"一堆大卡"，与现网长得不一样。
 * 本类按同一个布局、同一套状态文案/色（{@link ToolRowStyle}）、同一个结果预览原语
 * （{@link ToolResultText#preview}）复现那一行，接管前后观感一致。</p>
 *
 * <p>两层展开是分开的：组卡折叠由 {@link PartHost#toolGroupExpanded} 管"整组露不露"，
 * 本类的行展开由 {@link PartHost#toolRowExpanded} 管"某一行的结果全文"。</p>
 */
final class ToolActivityRowView {

    /**
     * 把一次 shell 调用渲染成 {@code dsh_command} 原生卡。
     *
     * @return 非 shell 工具/命令提取失败时返回 null，调用方退回原来的行渲染
     */
    private static View renderCommandCard(Context ctx, AssistantTurn.Part call) {
        if (call == null || !ToolCommandCard.isShellToolName(call.toolName)) return null;
        org.json.JSONObject props = new org.json.JSONObject();
        try {
            props.put("toolName", call.toolName == null ? "" : call.toolName);
            props.put("args", call.args == null ? "" : call.args);
            props.put("status", call.status == null ? "running" : call.status);
            props.put("result", call.result == null ? "" : call.result);
        } catch (org.json.JSONException e) {
            return null;
        }
        org.json.JSONObject cmd = ToolCommandCard.build(props);
        if (cmd == null) return null;
        try {
            return com.oilquiz.app.ai.chat.component.ComponentRegistry.getInstance()
                    .render(ctx, com.oilquiz.app.ai.chat.component.ComponentData.of(ToolCommandCard.TYPE, cmd));
        } catch (Throwable t) {
            return null;
        }
    }

    private ToolActivityRowView() {
    }

    /**
     * 工具参数的**人话预览**（工具卡那一行的副标题）。
     *
     * <p>以前直接 {@code desc.setText(call.args)}：手机上一整行原始 JSON
     * {@code {"command":"Start-Sleep -Seconds 3\n$adb = ..."}}，换行被吃掉、转义符裸露，
     * 完全没法读。现在按语义取一个字段：</p>
     * <ul>
     *   <li>{@code command} → 命令本体（shell 工具最该给人看的东西）</li>
     *   <li>{@code path/file_path/filePath} → 路径</li>
     *   <li>其他 JSON → {@code key=value · key=value}，最多截 80 字</li>
     *   <li>非 JSON → 原样（纯文本参数不动）</li>
     * </ul>
     */
    static String argsPreview(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty()) return "";
        if (!s.startsWith("{")) return s;
        try {
            org.json.JSONObject o = new org.json.JSONObject(s);
            String cmd = o.optString("command", "").trim();
            if (!cmd.isEmpty()) return cmd;
            String path = o.optString("file_path",
                    o.optString("filePath", o.optString("path", ""))).trim();
            if (!path.isEmpty()) return path;
            StringBuilder sb = new StringBuilder();
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                String v = String.valueOf(o.opt(k));
                if (v.isEmpty() || "null".equals(v)) continue;
                if (sb.length() > 0) sb.append(" · ");
                sb.append(k).append('=').append(v);
                if (sb.length() > 80) break;
            }
            return sb.toString();
        } catch (Throwable t) {
            // 坏 JSON 不拖垮渲染：原样显示
            return s;
        }
    }

    /**
     * 建一行（含可展开的结果全文区），返回的 View 直接 addView 进组卡折叠体。
     *
     * @param index 组内序号，首行不画分隔线（同 legacy）
     */
    static View build(Context ctx, ViewGroup parent, AssistantTurn.Part call, int index,
                      PartHost host) {
        // shell 类工具：直接出**终端命令卡**原生组件，而不是"工具名 + 一行参数"的行。
        // 分组工具卡（ToolGroupPartRenderer）走的就是这个方法，之前只改了单卡渲染器，
        // 命令还是显示成行 —— 这里补上，两条渲染路径长相才一致。
        View commandCard = renderCommandCard(ctx, call);
        if (commandCard != null) return commandCard;

        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View line = LayoutInflater.from(ctx).inflate(R.layout.item_chat_v2_tool_row, wrap, false);
        final String status = ToolCallPartRenderer.ToolCardProps.normalizeStatus(call.status);

        TextView name = line.findViewById(R.id.chat_v2_tool_row_name);
        if (name != null) {
            String t = call.toolName == null ? "" : call.toolName;
            name.setText(t.isEmpty() ? ctx.getString(R.string.chat_v2_tool_unknown) : t);
        }
        TextView desc = line.findViewById(R.id.chat_v2_tool_row_desc);
        if (desc != null) {
            String summary = argsPreview(call.args);
            desc.setText(summary);
            desc.setVisibility(summary.isEmpty() ? View.GONE : View.VISIBLE);
        }
        TextView state = line.findViewById(R.id.chat_v2_tool_row_time);
        if (state != null) {
            state.setText(ToolRowStyle.statusText(ctx, status));
            state.setTextColor(ToolRowStyle.statusColor(ctx, status));
        }
        View divider = line.findViewById(R.id.chat_v2_tool_row_divider);
        if (divider != null) {
            divider.setVisibility(index == 0 ? View.GONE : View.VISIBLE);
        }

        final String body = call.result == null ? "" : call.result.trim();
        final boolean hasBody = !body.isEmpty();
        final String rowId = call.id;
        boolean expanded = hasBody && host.toolRowExpanded(rowId);

        if (hasBody && line instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) line;
            // 行尾箭头：没有结果时不给"可点"的暗示（同 legacy）
            if (vg.getChildCount() > 1 && vg.getChildAt(1) instanceof ViewGroup) {
                ImageView chev = new ImageView(ctx);
                int sz = dp(ctx, 13);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(sz, sz);
                clp.setMarginStart(dp(ctx, 4));
                chev.setLayoutParams(clp);
                chev.setImageResource(R.drawable.ic_chevron_right);
                chev.setImageTintList(android.content.res.ColorStateList.valueOf(
                        androidx.core.content.ContextCompat.getColor(ctx, R.color.chat_v2_text_3)));
                chev.setAlpha(0.55f);
                chev.setRotation(expanded ? 90f : 0f);
                ((ViewGroup) vg.getChildAt(1)).addView(chev);
            }
        }
        wrap.addView(line);

        if (hasBody) {
            final TextView bodyTv = new TextView(ctx);
            bodyTv.setTextSize(12f);
            bodyTv.setLineSpacing(0f, 1.6f);
            bodyTv.setTextColor(androidx.core.content.ContextCompat.getColor(ctx,
                    R.color.chat_v2_text_2));
            bodyTv.setVisibility(expanded ? View.VISIBLE : View.GONE);
            bodyTv.setMovementMethod(LinkMovementMethod.getInstance());
            if (expanded) {
                applyBody(ctx, host, bodyTv, body, name0(ctx, call));
            }
            wrap.addView(bodyTv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            line.setOnClickListener(v -> {
                host.toggleToolRow(rowId);
                boolean now = host.toolRowExpanded(rowId); // 切换后回读，避免依赖宿主的返回值约定
                bodyTv.setVisibility(now ? View.VISIBLE : View.GONE);
                if (now) {
                    applyBody(ctx, host, bodyTv, body, name0(ctx, call));
                }
                if (v instanceof ViewGroup && ((ViewGroup) v).getChildCount() > 1
                        && ((ViewGroup) v).getChildAt(1) instanceof ViewGroup) {
                    ViewGroup row2 = (ViewGroup) ((ViewGroup) v).getChildAt(1);
                    for (int i = 0; i < row2.getChildCount(); i++) {
                        if (row2.getChildAt(i) instanceof ImageView) {
                            row2.getChildAt(i).setRotation(now ? 90f : 0f);
                        }
                    }
                }
                host.invalidateSize();
            });
        }
        return wrap;
    }

    /** 展开态正文：预览 + 截断时"点开全文"（与 legacy 同一原语，不另设限长口径）。 */
    private static void applyBody(Context ctx, PartHost host, TextView tv, String body, String title) {
        ToolResultText.Preview preview = ToolResultText.preview(body);
        tv.setText(preview.text);
        if (preview.truncated) {
            tv.setOnClickListener(v -> host.showFullResult(
                    ctx.getString(R.string.chat_v2_tool_result_title, title), body));
        }
    }

    private static String name0(Context ctx, AssistantTurn.Part call) {
        String t = call.toolName == null ? "" : call.toolName;
        return t.isEmpty() ? ctx.getString(R.string.chat_v2_tool_unknown) : t;
    }

    private static int dp(Context ctx, float v) {
        return (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics());
    }
}
