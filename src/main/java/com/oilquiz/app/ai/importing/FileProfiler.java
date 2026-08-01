package com.oilquiz.app.ai.importing;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 题库文件识别器(动态导入流水线第 1 环)。
 * <p>
 * 中文题库文件常为 GBK/GB2312 编码(非 UTF-8),当前 UTF-8 直读会乱码。
 * 本类先探测编码再读取,并采样头部/尾部 + 预估题量,供后续结构识别与分块使用。
 * <p>
 * 纯 Java 工具类,无 Android Context 依赖,不引入第三方库。
 */
public class FileProfiler {

    /** 编码探测最大读取字节数(前 64KB),避免大文件 OOM */
    private static final int DETECT_MAX_BYTES = 64 * 1024;
    /** 头部采样字符数(前 4KB) */
    private static final int HEAD_SAMPLE_CHARS = 4096;
    /** 尾部采样字符数(后 1KB) */
    private static final int TAIL_SAMPLE_CHARS = 1024;
    /** 无题号模式时,按文本长度估算题量的除数(每题约 200 字符) */
    private static final int CHARS_PER_QUESTION = 200;
    /** GBK 启发式判断的汉字占比阈值 */
    private static final float GBK_HAN_RATIO_THRESHOLD = 0.3f;

    // 题号正则(用于题量预估)
    /** 第 N 题 */
    private static final Pattern RE_DI_NUM = Pattern.compile("第\\s*\\d+\\s*题");
    /** 行首数字 + 点/顿号/右括号 */
    private static final Pattern RE_LINE_NUM = Pattern.compile("^\\s*\\d+\\s*[\\.、\\)]", Pattern.MULTILINE);
    /** Q + 数字 */
    private static final Pattern RE_Q_NUM = Pattern.compile("Q\\s*\\d+", Pattern.CASE_INSENSITIVE);
    /** Question + 数字 */
    private static final Pattern RE_QUESTION_NUM = Pattern.compile("Question\\s*\\d+", Pattern.CASE_INSENSITIVE);
    /** 行首 [N] */
    private static final Pattern RE_BRACKET_NUM = Pattern.compile("^\\s*\\[\\s*\\d+\\s*\\]", Pattern.MULTILINE);

    /**
     * 文件识别结果。
     * <p>
     * 纯 POJO,可序列化,供流水线后续阶段(结构识别/分块)使用。
     */
    public static class FileProfile implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 探测到的编码名(如 "UTF-8"/"GBK"/"GB18030") */
        public String encoding;
        /** 头部采样(前 4KB 文本) */
        public String sampleHead;
        /** 尾部采样(后 1KB 文本) */
        public String sampleTail;
        /** 预估题量 */
        public int estimatedCount;
        /** 格式提示(如 "纯文本编号题"/"JSON"/"CSV"/"Markdown"/"混合") */
        public String formatHint;
    }

    /**
     * 探测文件:编码 + 采样 + 题量预估,失败返回 null。
     *
     * @param file 待识别的题库文件
     * @return 识别结果;文件不存在或读取失败时返回 null
     */
    public static FileProfile profile(File file) {
        if (file == null || !file.exists() || !file.isFile()) {
            return null;
        }
        try {
            FileProfile p = new FileProfile();
            // 1. 编码探测
            p.encoding = detectEncoding(file);
            // 2. 读取全文(流式)
            String text = readAllText(file, p.encoding);
            if (text == null) {
                return null;
            }
            // 3. 采样:头部前 4KB,尾部后 1KB
            p.sampleHead = text.length() > HEAD_SAMPLE_CHARS
                    ? text.substring(0, HEAD_SAMPLE_CHARS) : text;
            p.sampleTail = text.length() > TAIL_SAMPLE_CHARS
                    ? text.substring(text.length() - TAIL_SAMPLE_CHARS) : text;
            // 4. 题量预估
            p.estimatedCount = estimateCount(text);
            // 5. 格式提示
            p.formatHint = detectFormat(p.sampleHead);
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    // ======================== 编码探测 ========================

    /**
     * 探测文件编码。
     * 顺序:BOM(UTF-8/UTF-16LE/UTF-16BE) → UTF-8 严格解码 → GBK 启发式 → GB18030 → 默认 UTF-8。
     */
    private static String detectEncoding(File file) throws Exception {
        // 仅读前 64KB 做编码探测,避免大文件 OOM
        byte[] head = readHeadBytes(file, DETECT_MAX_BYTES);

        // 1. BOM 判断
        if (head.length >= 3
                && (head[0] & 0xFF) == 0xEF
                && (head[1] & 0xFF) == 0xBB
                && (head[2] & 0xFF) == 0xBF) {
            return "UTF-8";
        }
        if (head.length >= 2
                && (head[0] & 0xFF) == 0xFF
                && (head[1] & 0xFF) == 0xFE) {
            return "UTF-16LE";
        }
        if (head.length >= 2
                && (head[0] & 0xFF) == 0xFE
                && (head[1] & 0xFF) == 0xFF) {
            return "UTF-16BE";
        }

        // 2. UTF-8 严格解码(无替换符即视为 UTF-8)
        if (isStrictCharset(head, "UTF-8")) {
            return "UTF-8";
        }

        // 3. GBK 启发式:解码后是否含大量中文常见字
        if (looksLikeGbk(head)) {
            return "GBK";
        }

        // 4. GB18030(GBK 超集)
        if (isStrictCharset(head, "GB18030")) {
            return "GB18030";
        }

        // 5. 默认回退 UTF-8
        return "UTF-8";
    }

    /** 读取文件头部指定长度的字节 */
    private static byte[] readHeadBytes(File file, int maxBytes) throws Exception {
        long fileLen = file.length();
        int len = (int) Math.min(fileLen, maxBytes);
        if (len <= 0) {
            return new byte[0];
        }
        byte[] buf = new byte[len];
        int read = 0;
        try (FileInputStream fis = new FileInputStream(file)) {
            while (read < len) {
                int n = fis.read(buf, read, len - read);
                if (n < 0) break;
                read += n;
            }
        }
        if (read < len) {
            byte[] exact = new byte[read];
            System.arraycopy(buf, 0, exact, 0, read);
            return exact;
        }
        return buf;
    }

    /**
     * 用指定字符集严格解码(遇非法序列抛异常即视为不是该编码)。
     * 使用 {@link CodingErrorAction#REPORT} 模式,解码成功且不含替换符 U+FFFD 即通过。
     */
    private static boolean isStrictCharset(byte[] bytes, String charsetName) {
        if (bytes == null || bytes.length == 0) return false;
        try {
            Charset cs = Charset.forName(charsetName);
            CharsetDecoder decoder = cs.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            CharBuffer out = decoder.decode(ByteBuffer.wrap(bytes));
            // 额外检查:不应含替换符
            for (int i = 0; i < out.length(); i++) {
                if (out.charAt(i) == '\uFFFD') return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * GBK 启发式判断:用 GBK 解码后统计汉字(CJK 统一表意文字)占比,
     * 占比超过阈值则认为是 GBK。
     */
    private static boolean looksLikeGbk(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return false;
        try {
            String decoded = new String(bytes, "GBK");
            int han = 0;
            int sample = Math.min(decoded.length(), 1024);
            for (int i = 0; i < sample; i++) {
                char c = decoded.charAt(i);
                if (c >= 0x4E00 && c <= 0x9FFF) han++;
            }
            return sample > 0 && han * 1f / sample >= GBK_HAN_RATIO_THRESHOLD;
        } catch (Exception e) {
            return false;
        }
    }

    // ======================== 全文读取 ========================

    /** 用探测到的编码流式读取全文 */
    private static String readAllText(File file, String encoding) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), encoding))) {
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }

    // ======================== 题量预估 ========================

    /**
     * 题量预估:用正则计数常见题号模式,取最大者;
     * 若都为 0,按文本长度估算(每 200 字符约 1 题)。
     */
    private static int estimateCount(String text) {
        if (text == null || text.isEmpty()) return 0;
        int c1 = countMatches(RE_DI_NUM, text);
        int c2 = countMatches(RE_LINE_NUM, text);
        int c3 = countMatches(RE_Q_NUM, text);
        int c4 = countMatches(RE_QUESTION_NUM, text);
        int c5 = countMatches(RE_BRACKET_NUM, text);
        int max = Math.max(Math.max(Math.max(c1, c2), Math.max(c3, c4)), c5);
        if (max > 0) return max;
        // 无题号模式,按文本长度估算
        return text.length() / CHARS_PER_QUESTION;
    }

    /** 统计正则匹配次数 */
    private static int countMatches(Pattern p, String text) {
        int count = 0;
        Matcher m = p.matcher(text);
        while (m.find()) {
            count++;
        }
        return count;
    }

    // ======================== 格式提示 ========================

    /**
     * 格式提示:含 { 且像 JSON → "JSON";含 , 且首行像表头 → "CSV";
     * 含 # / - 标题 → "Markdown";含题号 → "纯文本编号题";否则 "混合"。
     */
    private static String detectFormat(String sample) {
        if (sample == null || sample.trim().isEmpty()) return "混合";
        String trimmed = sample.trim();
        // JSON:以 { 或 [ 开头,且含成对花括号
        if ((trimmed.startsWith("{") || trimmed.startsWith("["))
                && countChar(sample, '{') >= 1 && countChar(sample, '}') >= 1) {
            return "JSON";
        }
        // CSV:首行含多个逗号(像表头)
        int newlineIdx = sample.indexOf('\n');
        if (newlineIdx > 0) {
            String firstLine = sample.substring(0, newlineIdx).trim();
            if (countChar(firstLine, ',') >= 2) {
                return "CSV";
            }
        }
        // Markdown:含 # 标题或 - 列表标记
        if (sample.contains("#") || sample.contains("- ")) {
            return "Markdown";
        }
        // 含题号
        if (RE_DI_NUM.matcher(sample).find()
                || RE_LINE_NUM.matcher(sample).find()
                || RE_Q_NUM.matcher(sample).find()
                || RE_BRACKET_NUM.matcher(sample).find()) {
            return "纯文本编号题";
        }
        return "混合";
    }

    /** 统计字符出现次数 */
    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) n++;
        }
        return n;
    }
}
