package com.oilquiz.app.util;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 智能文件编码识别与读取工具类
 *
 * 支持的编码检测（按优先级）：
 * 1. BOM检测（UTF-8 BOM、UTF-16 LE/BE）
 * 2. UTF-8有效性验证（多字节序列合法性）
 * 3. GB18030/GBK 中文编码启发式检测
 * 4. 降级到默认平台编码
 *
 * 解决用户痛点：Windows 导出的 CSV/TXT/JSON 多为 GBK/GB18030，
 * 直接按 UTF-8 读取会出现中文乱码；反之亦然。
 */
public class CharsetDetector {
    private static final String TAG = "CharsetDetector";

    /** 中文常见 200 字（用于乱码评分） */
    static final String COMMON_CHINESE = "的一是了我不人在他有这个上们来到时大地为子中你说生国年着就那和要她出也得里后自以会家可下而过天去能对小多然于心学么之都好看起发当没成只如事把还用第样道想作种开美总从无情己面最女但现前些所同日手又行意动方期它头经长儿回位分爱老因很给名法间斯知世什两次使身者被高已亲其进此话常与活正感见明问力理尔点文几定本公特做外孩相果西片走将月十实向声车全信重三机工物气每并别真太打新比才便夫再书部水像眼低任满深取啊足";

    // 候选编码列表（按检测优先级排列）
    public static final String UTF_8 = "UTF-8";
    public static final String UTF_8_BOM = "UTF-8"; // BOM 不影响解码，Java 会自动跳过
    public static final String GB18030 = "GB18030"; // 覆盖 GBK、GB2312
    public static final String GBK = "GBK";
    public static final String GB2312 = "GB2312";
    public static final String UTF_16LE = "UTF-16LE";
    public static final String UTF_16BE = "UTF-16BE";
    public static final String ISO_8859_1 = "ISO-8859-1";
    public static final String US_ASCII = "US-ASCII";

    private static final int MAX_SAMPLE_BYTES = 64 * 1024; // 最多采样 64KB

    /**
     * 检测结果
     */
    public static class DetectionResult {
        public final String charset;
        public final float confidence; // 0~1，置信度
        public final boolean hasBOM;
        public final String description;

        DetectionResult(String charset, float confidence, boolean hasBOM, String description) {
            this.charset = charset;
            this.confidence = confidence;
            this.hasBOM = hasBOM;
            this.description = description;
        }

        @Override
        public String toString() {
            return charset + " (confidence=" + String.format("%.2f", confidence * 100)
                    + "%, BOM=" + hasBOM + ", " + description + ")";
        }
    }

    /**
     * 对文件进行编码检测并返回最可能的编码结果
     */
    public static DetectionResult detectCharset(File file) throws IOException {
        if (file == null || !file.exists() || !file.isFile()) {
            throw new IOException("文件不存在或不可读");
        }
        try (FileInputStream fis = new FileInputStream(file);
             BufferedInputStream bis = new BufferedInputStream(fis)) {
            byte[] sample = readSample(bis, MAX_SAMPLE_BYTES);
            return detectFromBytes(sample);
        }
    }

    /**
     * 对字节数组进行编码检测
     */
    public static DetectionResult detectFromBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new DetectionResult(UTF_8, 1.0f, false, "空文件，默认 UTF-8");
        }

        // 第 1 步：检测 BOM（最高优先级）
        if (bytes.length >= 3) {
            // UTF-8 BOM: EF BB BF
            if ((bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
                return new DetectionResult(UTF_8, 1.0f, true, "检测到 UTF-8 BOM");
            }
        }
        if (bytes.length >= 2) {
            // UTF-16 LE BOM: FF FE
            if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
                return new DetectionResult(UTF_16LE, 1.0f, true, "检测到 UTF-16 LE BOM");
            }
            // UTF-16 BE BOM: FE FF
            if ((bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
                return new DetectionResult(UTF_16BE, 1.0f, true, "检测到 UTF-16 BE BOM");
            }
        }

        // 第 2 步：UTF-8 有效性验证
        Utf8CheckResult utf8Check = checkUtf8Validity(bytes);
        if (utf8Check.isValidUtf8 && utf8Check.asciiRatio < 0.95f) {
            // 有效 UTF-8 且含多字节字符（非纯 ASCII），高置信度
            return new DetectionResult(UTF_8, 0.95f, false,
                    "UTF-8 序列全部有效，中文/符号字节占比: " + String.format("%.0f%%", (1 - utf8Check.asciiRatio) * 100));
        }

        // 第 3 步：GB18030/GBK 中文编码启发式检测
        GbCheckResult gbCheck = checkGb18030(bytes);
        if (gbCheck.validGbPairs > 0 && gbCheck.chineseRatio > 0.05f) {
            // GB18030 兼容 GBK/GB2312，直接用 GB18030
            float confidence = Math.min(0.92f, 0.5f + gbCheck.chineseRatio * 0.5f + gbCheck.validGbPairs * 0.005f);
            return new DetectionResult(GB18030, confidence, false,
                    String.format("检测到 GB18030 中文字符（有效双字节对 %d，中文占比 %.0f%%）",
                            gbCheck.validGbPairs, gbCheck.chineseRatio * 100));
        }

        // 第 4 步：纯 ASCII（中英文档常见表头英文），按 UTF-8 返回
        if (utf8Check.asciiRatio > 0.99f) {
            return new DetectionResult(UTF_8, 0.85f, false,
                    "纯 ASCII 文本，按 UTF-8 处理（兼容 GB18030 ASCII 区）");
        }

        // 第 5 步：降级判断
        if (gbCheck.validGbPairs > 0) {
            return new DetectionResult(GB18030, 0.6f, false, "疑似 GB18030 编码，置信度较低");
        }

        // 兜底：默认 UTF-8
        return new DetectionResult(UTF_8, 0.5f, false, "无法确定编码，默认 UTF-8");
    }

    // ============= UTF-8 验证 =============

    private static class Utf8CheckResult {
        boolean isValidUtf8;
        int invalidSequences;  // 非法序列数
        int multiByteChars;    // 多字节字符数
        float asciiRatio;      // ASCII 单字节占比
    }

    private static Utf8CheckResult checkUtf8Validity(byte[] bytes) {
        Utf8CheckResult r = new Utf8CheckResult();
        r.isValidUtf8 = true;
        int invalidSeq = 0;
        int multiByteCount = 0;
        int asciiCount = 0;
        int i = 0;
        while (i < bytes.length) {
            int b = bytes[i] & 0xFF;
            if (b < 0x80) {
                // ASCII (0xxxxxxx)
                asciiCount++;
                i++;
            } else if (b < 0xC2) {
                // 非法起始字节 (10xxxxxx 或 1111111x)
                invalidSeq++;
                r.isValidUtf8 = false;
                i++;
            } else if (b < 0xE0) {
                // 2 字节序列：110xxxxx 10xxxxxx
                if (i + 1 >= bytes.length || !isContinuation(bytes[i + 1])) {
                    invalidSeq++;
                    r.isValidUtf8 = false;
                    i++;
                } else {
                    multiByteCount++;
                    i += 2;
                }
            } else if (b < 0xF0) {
                // 3 字节序列：1110xxxx 10xxxxxx 10xxxxxx
                if (i + 2 >= bytes.length
                        || !isContinuation(bytes[i + 1])
                        || !isContinuation(bytes[i + 2])) {
                    invalidSeq++;
                    r.isValidUtf8 = false;
                    i++;
                } else {
                    multiByteCount++;
                    // 过滤 UTF-8 代理项
                    int codePoint = ((b & 0x0F) << 12) | ((bytes[i + 1] & 0x3F) << 6) | (bytes[i + 2] & 0x3F);
                    if (codePoint >= 0xD800 && codePoint <= 0xDFFF) {
                        r.isValidUtf8 = false;
                        invalidSeq++;
                    }
                    i += 3;
                }
            } else if (b < 0xF8) {
                // 4 字节序列：11110xxx 10xxxxxx 10xxxxxx 10xxxxxx
                if (i + 3 >= bytes.length
                        || !isContinuation(bytes[i + 1])
                        || !isContinuation(bytes[i + 2])
                        || !isContinuation(bytes[i + 3])) {
                    invalidSeq++;
                    r.isValidUtf8 = false;
                    i++;
                } else {
                    multiByteCount++;
                    i += 4;
                }
            } else {
                invalidSeq++;
                r.isValidUtf8 = false;
                i++;
            }
        }
        r.invalidSequences = invalidSeq;
        r.multiByteChars = multiByteCount;
        int nonAscii = bytes.length - asciiCount;
        r.asciiRatio = bytes.length == 0 ? 1.0f : (asciiCount * 1.0f / bytes.length);
        // 如果非法序列数较多且存在非 ASCII 字节 → 基本可以排除 UTF-8
        if (invalidSeq > Math.max(3, nonAscii / 30)) {
            r.isValidUtf8 = false;
        }
        return r;
    }

    private static boolean isContinuation(byte b) {
        return (b & 0xC0) == 0x80;
    }

    // ============= GB18030/GBK 启发式检测 =============

    private static class GbCheckResult {
        int validGbPairs;     // 有效 GB18030 双字节对数量
        int chineseCount;     // 中文字符数（粗略估算）
        float chineseRatio;   // 中文字符占非 ASCII 字节的比例
    }

    /**
     * 启发式检测 GB18030/GBK
     * GBK 编码范围：
     *  - 首字节：0x81 ~ 0xFE
     *  - 次字节：0x40 ~ 0x7E 或 0x80 ~ 0xFE
     * 常见中文 Unicode：U+4E00 ~ U+9FFF（基本区）
     */
    private static GbCheckResult checkGb18030(byte[] bytes) {
        GbCheckResult r = new GbCheckResult();
        int validPairs = 0;
        int chineseChars = 0;
        int candidateGbbBytes = 0;
        int i = 0;
        while (i < bytes.length - 1) {
            int b1 = bytes[i] & 0xFF;
            int b2 = bytes[i + 1] & 0xFF;
            // GBK/GB18030 双字节第一字节范围
            if (b1 >= 0x81 && b1 <= 0xFE) {
                // 第二字节范围
                if ((b2 >= 0x40 && b2 <= 0x7E) || (b2 >= 0x80 && b2 <= 0xFE)) {
                    validPairs++;
                    candidateGbbBytes += 2;
                    // 粗略判断是否为中文（常用汉字首字节多在 B0~F7 区间）
                    if (b1 >= 0xB0 && b1 <= 0xF7 && b2 >= 0xA1) {
                        chineseChars++;
                    }
                    i += 2;
                    continue;
                }
            }
            i++;
        }
        r.validGbPairs = validPairs;
        r.chineseCount = chineseChars;
        int nonAscii = 0;
        for (byte b : bytes) {
            if ((b & 0xFF) >= 0x80) nonAscii++;
        }
        r.chineseRatio = nonAscii == 0 ? 0.0f : (chineseChars * 2.0f / nonAscii);
        return r;
    }

    // ============= 读取采样 =============

    private static byte[] readSample(InputStream in, int maxBytes) throws IOException {
        byte[] buffer = new byte[maxBytes];
        int totalRead = 0;
        while (totalRead < maxBytes) {
            int n = in.read(buffer, totalRead, maxBytes - totalRead);
            if (n < 0) break;
            totalRead += n;
        }
        if (totalRead == maxBytes) {
            return buffer;
        }
        byte[] result = new byte[totalRead];
        System.arraycopy(buffer, 0, result, 0, totalRead);
        return result;
    }

    // ============= 便捷读取方法 =============

    /**
     * 按自动检测的编码读取文件全部内容为字符串
     *
     * @param file 目标文件
     * @return 解码后的文本内容（若检测失败降级为 UTF-8）
     */
    public static String readFileAutoDetect(File file) throws IOException {
        DetectionResult result = detectCharset(file);
        Log.d(TAG, "文件 [" + file.getName() + "] 检测结果: " + result);
        Charset charset;
        try {
            charset = Charset.forName(result.charset);
        } catch (Exception e) {
            charset = StandardCharsets.UTF_8;
        }
        StringBuilder sb = new StringBuilder();
        try (FileInputStream fis = new FileInputStream(file);
             InputStreamReader isr = new InputStreamReader(fis, charset);
             BufferedReader reader = new BufferedReader(isr)) {
            // 若检测到 UTF-8 BOM，手动跳过 BOM（部分 Java 版本 InputStreamReader 已处理，但保险起见）
            if (result.hasBOM && UTF_8.equals(result.charset)) {
                int firstChar = reader.read();
                if (firstChar != 0xFEFF) { // UTF-8 BOM 解码后就是 U+FEFF
                    sb.append((char) firstChar);
                }
            }
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
        }
        // ====================== 乱码二次校验 ======================
        // 检测用 UTF-8 解码后仍有大量乱码时，切换到 GB18030/GBK 重试
        String decoded = sb.toString();
        if (looksLikeGarbled(decoded) && !result.hasBOM) {
            Log.w(TAG, "UTF-8 解码后疑似乱码，切换到 GB18030 重试解码");
            try {
                byte[] bytes = readFileBytes(file);
                String retry = new String(bytes, GB18030);
                if (!looksLikeGarbled(retry)) {
                    return retry;
                }
                // GB18030 也不行，尝试 GBK
                String retry2 = new String(bytes, "GBK");
                if (!looksLikeGarbled(retry2)) {
                    return retry2;
                }
            } catch (Exception ignore) {}
        }
        return decoded;
    }

    /**
     * 轻量乱码检测（保守策略，不误判专业文本）。
     * 只在有明确乱码标志时返回 true：
     *  - 出现 UTF-8 替换符 (U+FFFD)
     *  - CJK 私用区字符(U+E000~F8FF)占比过高(>30%)
     * 不使用常用字比例判断（专业题库正常包含大量非常用字）。
     */
    static boolean looksLikeGarbled(String s) {
        if (s == null || s.isEmpty()) return false;
        int total = 0;
        int fffd = 0;
        int puChars = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) { i++; continue; }
            total++;
            if (c == 0xFFFD) fffd++;
            else if (c >= 0xE000 && c <= 0xF8FF) puChars++;
        }
        // 替换符：只要有一个就是乱码
        if (fffd > 0) return true;
        if (total < 10) return false;
        // 私用区字符占比 >30%：乱码
        if ((float) puChars / total > 0.3f) return true;
        return false;
    }

    /**
     * 按自动检测的编码创建 BufferedReader（供逐行读取使用）
     *
     * @param file 目标文件
     * @return [BufferedReader, 检测到的编码名] 数组，使用方记得 close
     */
    public static Object[] openBufferedReaderAutoDetect(File file) throws IOException {
        DetectionResult result = detectCharset(file);
        Log.d(TAG, "文件 [" + file.getName() + "] 检测结果: " + result);
        Charset charset;
        try {
            charset = Charset.forName(result.charset);
        } catch (Exception e) {
            charset = StandardCharsets.UTF_8;
        }
        // ====================== 乱码二次校验：读取前 1KB 预检，如发现乱码切换编码 ======================
        if (!result.hasBOM) {
            try {
                byte[] previewBytes = new byte[Math.min(4096, (int) file.length())];
                try (FileInputStream pFis = new FileInputStream(file)) {
                    int r = pFis.read(previewBytes);
                    if (r > 0) {
                        String preview = new String(previewBytes, 0, r, charset);
                        if (looksLikeGarbled(preview)) {
                            Charset[] tryCharsets = new Charset[] {
                                    Charset.forName(GB18030),
                                    Charset.forName("GBK"),
                                    StandardCharsets.UTF_8,
                                    Charset.forName("Big5"),
                            };
                            // 选择预览乱码最少的编码
                            Charset best = charset;
                            int bestBad = Integer.MAX_VALUE;
                            for (Charset cs : tryCharsets) {
                                String test = new String(previewBytes, 0, r, cs);
                                int score = 0;
                                for (int i = 0; i < test.length(); i++) {
                                    char c = test.charAt(i);
                                    if (c == 0xFFFD) score += 100;       // 替换符：严重乱码
                                    else if (c >= 0xE000 && c <= 0xF8FF) score += 10; // 私用区：乱码迹象
                                    else if (c >= 0x4E00 && c <= 0x9FFF) score -= 2;   // CJK 正常中文
                                    else if (c >= 0x20 && c <= 0x7E) score -= 1;       // ASCII 正常
                                }
                                if (score < bestBad) { bestBad = score; best = cs; }
                            }
                            if (best != charset) {
                                Log.d(TAG, "预览乱码检测：切换编码 " + result.charset + " → " + best.name());
                                charset = best;
                            }
                        }
                    }
                }
            } catch (Exception ignore) {}
        }

        FileInputStream fis = new FileInputStream(file);
        InputStreamReader isr = new InputStreamReader(fis, charset);
        BufferedReader reader = new BufferedReader(isr);
        // 跳过 UTF-8 BOM
        if (result.hasBOM && UTF_8.equals(result.charset)) {
            reader.mark(1);
            int firstChar = reader.read();
            if (firstChar != 0xFEFF) {
                reader.reset();
            }
        }
        return new Object[]{reader, result.charset, result};
    }

    /**
     * 尝试使用多个候选编码解码字节数组，返回第一个解码后无典型乱码的结果
     *
     * 适用于检测结果置信度较低的场景（短文本）
     *
     * @param bytes           原始字节
     * @param preferredCandidates 优先尝试的编码列表（可为 null，使用默认顺序）
     * @return 解码字符串
     */
    public static String decodeWithFallback(byte[] bytes, List<String> preferredCandidates) {
        List<String> candidates = new ArrayList<>();
        if (preferredCandidates != null) {
            candidates.addAll(preferredCandidates);
        }
        // 补齐默认候选
        for (String c : new String[]{GB18030, UTF_8, GBK, GB2312, ISO_8859_1}) {
            if (!candidates.contains(c)) {
                candidates.add(c);
            }
        }

        String best = null;
        int bestScore = Integer.MIN_VALUE;
        for (String charsetName : candidates) {
            String decoded;
            try {
                decoded = new String(bytes, Charset.forName(charsetName));
            } catch (Exception e) {
                continue;
            }
            int score = scoreDecodedString(decoded);
            if (score > bestScore) {
                bestScore = score;
                best = decoded;
            }
        }
        return best != null ? best : new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 对解码结果打分：常见合法中文字符越多分越高；Unicode 替换字符（乱码典型）越多分越低。
     */
    private static int scoreDecodedString(String s) {
        if (s == null) return Integer.MIN_VALUE;
        int score = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\uFFFD') {
                // Unicode 替换字符：强烈暗示乱码
                score -= 100;
            } else if (c >= '\u4E00' && c <= '\u9FFF') {
                // CJK 统一汉字
                score += 5;
            } else if (c >= '\u3400' && c <= '\u4DBF') {
                // CJK 扩展 A
                score += 4;
            } else if (c >= '\u3000' && c <= '\u303F') {
                // CJK 标点
                score += 2;
            } else if (c >= '\uFF00' && c <= '\uFFEF') {
                // 全角字符
                score += 1;
            } else if (c < 0x80) {
                // ASCII 基本字符
                score += 1;
            } else if (Character.isLetterOrDigit(c)) {
                score += 1;
            } else if (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t') {
                // 控制字符多往往是解码错误
                score -= 10;
            }
        }
        return score;
    }

    /**
     * 读取文件全部字节（通用辅助方法）
     */
    public static byte[] readFileBytes(File file) throws IOException {
        long len = file.length();
        if (len > Integer.MAX_VALUE) {
            throw new IOException("文件过大，暂不支持：" + len);
        }
        byte[] result = new byte[(int) len];
        try (FileInputStream fis = new FileInputStream(file)) {
            int offset = 0;
            while (offset < result.length) {
                int n = fis.read(result, offset, result.length - offset);
                if (n < 0) break;
                offset += n;
            }
            if (offset < result.length) {
                byte[] truncated = new byte[offset];
                System.arraycopy(result, 0, truncated, 0, offset);
                return truncated;
            }
            return result;
        }
    }
}
