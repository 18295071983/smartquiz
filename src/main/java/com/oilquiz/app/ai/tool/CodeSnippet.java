package com.oilquiz.app.ai.tool;

/**
 * 给模型看的代码摘要（行数 + 前 N 行）。
 *
 * <p>为什么需要：动态工具创建成功后，模型必须能知道"自己那份逻辑长什么样"，否则下一轮只能凭
 * 记忆盲改；但把整份代码贴在创建结果里，等于每次创建都把几十行源码灌进上下文（本项目已经在
 * 超时误重试上烧过 137 万→196 万 token）。所以创建出口只给"共几行 + 前 N 行"，
 * 全文交给一条显式的读取动作（show）—— 要花钱的时候由模型自己决定花。
 *
 * <p>两处创建入口共用本类（{@code ai_create_tool} 与 {@code create_dynamic_tool}），
 * 避免"摘要格式各写一份"又漂一次。
 */
public final class CodeSnippet {

    /** 单行最多展示字符数：长行（比如内嵌的模板字符串）截断，别把摘要撑成全文 */
    private static final int MAX_LINE_CHARS = 120;

    private CodeSnippet() {
    }

    /** 代码行数（空/未定义为 0） */
    public static int lineCount(String code) {
        if (code == null || code.trim().isEmpty()) {
            return 0;
        }
        return linesOf(code).length;
    }

    /**
     * 前 {@code maxLines} 行的摘要正文（不含抬头，抬头由调用方按自己的措辞写）。
     *
     * @return 摘要文本；代码为空时返回空串
     */
    public static String preview(String code, int maxLines) {
        if (code == null || code.trim().isEmpty() || maxLines <= 0) {
            return "";
        }
        String[] lines = linesOf(code);
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(maxLines, lines.length);
        for (int i = 0; i < shown; i++) {
            String line = lines[i];
            if (line.length() > MAX_LINE_CHARS) {
                line = line.substring(0, MAX_LINE_CHARS) + "…";
            }
            sb.append(line).append('\n');
        }
        int left = lines.length - shown;
        if (left > 0) {
            sb.append("…（其余 ").append(left).append(" 行略，看全文请用查看动作）\n");
        }
        return sb.toString();
    }

    /**
     * 按行切开，并去掉代码尾部那一个换行造成的"空行"。
     *
     * <p>不处理就会有 off-by-one：25 行的脚本以 \\n 结尾时 {@code split} 出来是 26 段，
     * 报给模型的"共几行 / 还剩几行"就都多算一行。
     */
    private static String[] linesOf(String code) {
        String body = code;
        // 先判 \r\n 再判 \n：反过来的话 CRLF 只会削掉 \n、留一个孤零零的 \r 变成"多一行"
        if (body.endsWith("\r\n")) {
            body = body.substring(0, body.length() - 2);
        } else if (body.endsWith("\n")) {
            body = body.substring(0, body.length() - 1);
        }
        return body.split("\r?\n");
    }
}
