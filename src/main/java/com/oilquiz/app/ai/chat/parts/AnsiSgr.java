package com.oilquiz.app.ai.chat.parts;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import com.oilquiz.app.ai.chat.component.ComponentColors;

/**
 * 终端 ANSI SGR 着色（2026-11-18，第三批）。
 *
 * <p>只处理颜色/加粗/重置（{@code 0/1/30-37/39/90-97}）和"丢弃其它 CSI 控制序列"，
 * 不做完整终端模拟（光标、清屏、滚动区一律忽略）。颜色映射到主题 token，不写死 ANSI 色值，
 * 深浅色都能看。</p>
 */
final class AnsiSgr {

    private AnsiSgr() {
    }

    static CharSequence render(Context ctx, String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        SpannableStringBuilder out = new SpannableStringBuilder();
        StringBuilder seg = new StringBuilder();
        int fg = 0;
        boolean bold = false;
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == 0x1B && i + 1 < raw.length() && raw.charAt(i + 1) == '[') {
                // CSI：参数字节 0x20–0x3F，最终字节 0x40–0x7E；只有 'm' 是 SGR，其余整段丢弃
                int end = i + 2;
                while (end < raw.length()) {
                    char ch = raw.charAt(end);
                    if (ch >= 0x20 && ch <= 0x3F) {
                        end++;
                    } else {
                        break;
                    }
                }
                if (end >= raw.length()) {
                    break;
                }
                char finalByte = raw.charAt(end);
                if (finalByte == 'm') {
                    // 先按"上一段生效的样式"落盘，再解析新样式；否则 reset 会把上一段颜色抹掉
                    flush(ctx, out, seg, fg, bold);
                    String params = raw.substring(i + 2, end);
                    for (int code : parse(params)) {
                        if (code == 0) { fg = 0; bold = false; }
                        else if (code == 1) { bold = true; }
                        else if (code == 22) { bold = false; }
                        else if (code == 39) { fg = 0; }
                        else if ((code >= 30 && code <= 37) || (code >= 90 && code <= 97)) { fg = code; }
                        // 其它（45 背景、4 下划线…）忽略：SGR 背色在聊天流里太吵
                    }
                }
                i = end + 1;
                continue;
            }
            seg.append(c);
            i++;
        }
        flush(ctx, out, seg, fg, bold);
        return out;
    }

    private static int[] parse(String params) {
        if (params.isEmpty()) {
            return new int[]{0};
        }
        String[] parts = params.split(";");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = parts[i].isEmpty() ? 0 : Integer.parseInt(parts[i]);
            } catch (Throwable t) {
                out[i] = 0;
            }
        }
        return out;
    }

    private static void flush(Context ctx, SpannableStringBuilder out, StringBuilder seg,
                              int fg, boolean bold) {
        if (seg.length() == 0) {
            return;
        }
        int start = out.length();
        out.append(seg);
        int end = out.length();
        seg.setLength(0);
        int color = colorOf(ctx, fg);
        if (color != 0) {
            out.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (bold) {
            out.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static int colorOf(Context ctx, int code) {
        switch (code) {
            case 30: case 90: return ComponentColors.textTertiary(ctx);
            case 31: case 91: return ComponentColors.error(ctx);
            case 32: case 92: return ComponentColors.success(ctx);
            case 33: case 93: return ComponentColors.warning(ctx);
            case 34: case 94: case 35: case 95: case 36: case 96: return ComponentColors.accent(ctx);
            case 37: case 97: return ComponentColors.textPrimary(ctx);
            default: return 0;
        }
    }
}
