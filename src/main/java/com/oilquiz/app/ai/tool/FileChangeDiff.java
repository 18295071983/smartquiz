package com.oilquiz.app.ai.tool;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 写文件工具的"前后内容"快照器（2026-10-24）。
 *
 * <p>diff 卡此前只有渲染端、没有生产者。这里补的是<b>写侧</b>：在覆盖写/追加/替换前
 * 快照旧文本，写成功后把 {@code before}/{@code after} 放进 {@link AIToolResult#getAdditionalInfo()}
 * ——只走 UI 附加信息通道，模型可见的结果文本与缓存前缀完全不变。</p>
 *
 * <p>约束：只处理工作区内、已知文本扩展名、单边≤256KB 且≤400 行的文件；
 * 任一条件不满足就静默跳过（宁可不出 diff 卡，也不把大文件塞进 UI 线程做 LCS）。</p>
 */
public final class FileChangeDiff {

    /** additionalInfo 里的键：{title,path,before,after} */
    public static final String INFO_KEY = "diff";

    /** 单边最大字节：超过则不读（diff 卡是主线程 LCS，256KB/400 行已是上限） */
    private static final long MAX_BYTES = 256 * 1024L;
    /** 与 {@code SimpleLineDiff.MAX_LINES} 同口径 */
    private static final int MAX_LINES = 400;

    private static final Set<String> TEXT_EXT = new HashSet<>(Arrays.asList(
            "txt", "md", "markdown", "json", "xml", "yaml", "yml", "toml", "ini", "conf",
            "properties", "csv", "tsv", "log", "html", "htm", "css", "scss", "less",
            "js", "mjs", "cjs", "ts", "tsx", "jsx", "vue", "java", "kt", "kts", "py",
            "c", "h", "cpp", "hpp", "cc", "go", "rs", "rb", "php", "swift", "sh", "bat",
            "ps1", "sql", "gradle", "m3u", "m3u8"));

    private FileChangeDiff() {
    }

    /**
     * 读取文件当前文本快照；不存在/非文本/过大/超过行数上限时返回 null。
     * 写前、写后各调一次即可，不做任何缓存。
     */
    public static String snapshot(Context context, String path) {
        File file = resolve(context, path);
        if (file == null || !file.isFile()) return null;
        long length = file.length();
        if (length > MAX_BYTES) return null;
        if (!isTextFile(file.getName())) return null;
        if (length == 0) return ""; // 清空文件是合法修改：before 非空、after 为空时仍应出 diff
        StringBuilder sb = new StringBuilder((int) Math.min(length, 8192));
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) > 0) {
                sb.append(buf, 0, n);
                if (sb.length() > MAX_BYTES) {
                    return null; // 字符数已超字节上限，必然过大
                }
            }
        } catch (Throwable t) {
            return null; // 编码不支持/IO 失败：不影响主流程，只是不出 diff 卡
        }
        String text = sb.toString();
        return lineCount(text) > MAX_LINES ? null : text;
    }

    /**
     * 写后调用：旧快照存在且内容真的变了，才把 diff 载荷放进 additionalInfo。
     *
     * @param before {@link #snapshot(Context, String)} 在写前拿到的旧文本
     */
    public static void attach(Context context, AIToolResult result,
                              String path, String before) {
        attach(context, result, path, before, null);
    }

    /**
     * 同上，但由调用方给卡标题。
     *
     * <p>为什么要能改标题：{@code file_generator} 的 create/write/append/edit 都走这条 diff 卡，
     * 标题如果一律写"文件变更"，用户在卡上看不出这次到底是"覆盖写入"还是"改了 1 处"——
     * 而这正是"编辑动作有没有反馈到 UI"的观感来源（2026-10-28 用户提问）。</p>
     *
     * @param title 卡标题；null 时用默认「文件变更 · 文件名」
     */
    public static void attach(Context context, AIToolResult result,
                              String path, String before, String title) {
        if (result == null || !result.isSuccess() || before == null) {
            return;
        }
        Map<String, Object> info = result.getAdditionalInfo();
        if (info == null) {
            return;
        }
        String after = snapshot(context, path);
        if (after == null || before.equals(after)) {
            return;
        }
        Map<String, Object> diff = new HashMap<>();
        diff.put("title", title == null || title.isEmpty()
                ? "文件变更 · " + fileName(path) : title);
        diff.put("path", path == null ? "" : path);
        diff.put("before", before);
        diff.put("after", after);
        info.put(INFO_KEY, diff);
    }

    /**
     * 预览用（{@code file_generator(action=diff)}）：内容没落盘，"after" 是模型提议的新文本，
     * 所以不能像 {@link #attach} 那样回读文件。标题单独给，让卡片区分"已发生的变更"和"待确认的预览"。
     *
     * @param title 卡标题，如「变更预览 · report.md」
     */
    public static void attachPreview(AIToolResult result, String title,
                                     String path, String before, String after) {
        if (result == null || before == null || after == null) {
            return;
        }
        Map<String, Object> info = result.getAdditionalInfo();
        if (info == null || before.equals(after)) {
            return;
        }
        Map<String, Object> diff = new HashMap<>();
        diff.put("title", title);
        diff.put("path", path == null ? "" : path);
        diff.put("before", before);
        diff.put("after", after);
        diff.put("preview", Boolean.TRUE);
        info.put(INFO_KEY, diff);
    }

    /** 绝对路径/相对路径/file:// → 可读 File；找不到返回 null（不猜目录） */
    static File resolve(Context context, String path) {
        if (path == null || path.trim().isEmpty()) return null;
        String p = path.trim();
        if (p.startsWith("content://")) return null;
        if (p.startsWith("file://")) p = p.substring("file://".length());
        File direct = new File(p);
        if (direct.isFile()) return direct;
        if (context != null) {
            try {
                File resolved = com.oilquiz.app.ai.agent.online.AgentWorkspace
                        .getInstance(context).resolveExistingFile(p);
                if (resolved != null && resolved.isFile()) return resolved;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean isTextFile(String name) {
        if (name == null) return false;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot >= name.length() - 1) return false;
        return TEXT_EXT.contains(name.substring(dot + 1).toLowerCase(Locale.US));
    }

    private static int lineCount(String text) {
        if (text == null || text.isEmpty()) return 0;
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.endsWith("\n")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isEmpty() ? 0 : normalized.split("\n", -1).length;
    }

    private static String fileName(String path) {
        if (path == null) return "";
        String p = path.trim();
        int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        return slash >= 0 && slash < p.length() - 1 ? p.substring(slash + 1) : p;
    }
}
