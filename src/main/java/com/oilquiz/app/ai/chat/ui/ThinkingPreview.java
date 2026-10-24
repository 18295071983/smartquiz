package com.oilquiz.app.ai.chat.ui;

import com.oilquiz.app.ai.chat.parser.ThinkingTagConfig;
import com.oilquiz.app.ai.jni.LlamaHelper;

/**
 * 思考折叠头的一句话摘要（2026-10-25 从 {@code ChatAdapter#thinkingPreview} 抽出的唯一实现）。
 *
 * <p>移植自 dsh 的 {@code ReasoningRow.latestCompletedParagraphFirstLine}：思考折叠起来时，
 * 头那行不该只写"深度思考"四个字，而要露出<b>当前思考到哪一步</b>——取"最新已完成段落"的首行，
 * 流式时末段还没写完就退到上一段，没有上一段时用进行中段的首行。点开仍是全文。</p>
 *
 * <p>为什么单独一个类：这条口径原本长在 {@code ChatAdapter} 私有方法里，parts 接管后的
 * {@code ReasoningPartRenderer} 折叠头也要用同一份（否则又出现"两份复制各写一遍"）。
 * {@code ActivityTimeline#preview} 目前是同一口径的精简版，其注释里的"S2 改成共用这份"下一步
 * 也应并到这里。</p>
 *
 * <p>输出不带省略号：要不要加"…"由调用方按布局决定（折叠头 singleLine + ellipsize 自己会收尾）。</p>
 */
public final class ThinkingPreview {

    /** 摘要最大字符数（与原 ChatAdapter 口径一致）。 */
    public static final int MAX_CHARS = 48;

    private ThinkingPreview() {
    }

    /**
     * 思考正文 → 一句话摘要（剥掉 chat template 的思考标签标记）。
     *
     * @param content   思考原文
     * @param streaming true=还在生成（末段视为进行中，退到最新已完成段落）；false=已定稿（取首段）
     */
    public static String of(String content, boolean streaming) {
        return preview(stripTagMarkers(content), streaming, MAX_CHARS);
    }

    /**
     * 摘要（不剥标签，调用方已经剥过时用）。
     *
     * <p>段落按双换行（{@code \n\n} 及以上）分隔：流式时末段后没有空行结尾视为"进行中"，
     * 摘要取上一个已完成段落；没有已完成段落时退回进行中段。非流式取首个段落
     * （思考的开篇即结论性内容）。去掉 {@code **} 强调与反引号、压缩空白，超长截断。</p>
     */
    public static String preview(String content, boolean streaming, int maxChars) {
        if (content == null || content.trim().isEmpty()) return "";
        String t = content.trim();
        String[] paras = t.split("\n\\s*\n");
        // 用末尾扫描判断末段是否进行中（避免 matches 正则在长文本上回溯）
        int idx;
        if (streaming) {
            boolean lastInProgress = !endsWithParagraphBreak(content);
            idx = (lastInProgress && paras.length >= 2) ? paras.length - 2 : paras.length - 1;
        } else {
            idx = 0; // 非流式：取首段（思考开篇）
        }
        if (idx < 0 || idx >= paras.length) idx = paras.length - 1;
        String target = paras[idx].trim();
        int nl = target.indexOf('\n');
        if (nl >= 0) target = target.substring(0, nl).trim();
        target = target.replace("**", "").replace("`", "");
        // 压缩连续空白为单空格（手动循环替代 replaceAll 正则）
        StringBuilder sb = new StringBuilder(target.length());
        boolean lastWasSpace = false;
        for (int i = 0; i < target.length(); i++) {
            char c = target.charAt(i);
            if (c == ' ' || c == '\t') {
                if (!lastWasSpace && sb.length() > 0) sb.append(' ');
                lastWasSpace = true;
            } else {
                sb.append(c);
                lastWasSpace = false;
            }
        }
        String out = sb.toString();
        if (maxChars > 0 && out.length() > maxChars) out = out.substring(0, maxChars);
        return out;
    }

    /**
     * 是否以"段落分隔符"结尾（{@code \n} 后跟可选空白再跟 {@code \n} + 可选空白）。
     * 等价于原 {@code (?s).*\n\s*\n\s*$} 正则，但只做 O(n) 尾部扫描、无回溯。
     */
    public static boolean endsWithParagraphBreak(String s) {
        if (s == null) return false;
        int len = s.length();
        int i = len - 1;
        while (i >= 0 && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i--;
        if (i < 0 || s.charAt(i) != '\n') return false;
        i--;
        while (i >= 0 && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i--;
        return i >= 0 && s.charAt(i) == '\n';
    }

    /**
     * 去掉思考标签标记（保留内容）：优先用 chat template 声明的标签（非硬编码），
     * 模板不可用时回退旧的 {@code <think>} 系正则，保持对未识别模型的兼容。
     *
     * <p>模板要过 JNI：单测/壳环境取不到时按"模板不可用"处理（回退正则），不让摘要把
     * 整条渲染链路带崩。</p>
     */
    public static String stripTagMarkers(String content) {
        if (content == null || content.isEmpty()) return content;
        ThinkingTagConfig cfg = null;
        try {
            cfg = LlamaHelper.getThinkingTags();
        } catch (Throwable ignored) {
            // JNI 未就绪：走下面的正则兜底
        }
        String cleaned = content;
        if (cfg != null) {
            try {
                cleaned = cfg.removeTagMarkers(content);
                if (!cfg.isAvailable()) {
                    cleaned = regexStrip(content);
                }
            } catch (Throwable ignored) {
                cleaned = regexStrip(content);
            }
        } else {
            cleaned = regexStrip(content);
        }
        return cleaned == null ? "" : cleaned.trim();
    }

    private static String regexStrip(String content) {
        return content.replaceAll("<think[^>]*>", "").replace("</think>", "").replace("<think>", "");
    }
}
