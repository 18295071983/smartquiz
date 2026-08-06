package com.oilquiz.app.util;

import android.util.Log;

import com.oilquiz.app.model.Question;

import java.lang.reflect.Field;
import java.nio.charset.Charset;

/**
 * 乱码检测与修复工具类（保守策略）。
 * <p>
 * 核心原则：不误判正确文本。只在有明确乱码标志时才触发修复。
 * <p>
 * 明确乱码标志（只有这些才触发修复）：
 * <ol>
 *   <li>U+FFFD 替换符（UTF-8 解码失败产生的）</li>
 *   <li>U+E000~U+F8FF 私用区字符（二次编解码残留，占比 >30%）</li>
 *   <li>NUL 字节、BOM 残留混入文本</li>
 * </ol>
 * <p>
 * 不作为乱码依据的（避免误判专业文本）：
 * <ul>
 *   <li>非常用中文字比例高（石油化工题库正常包含大量专业术语）</li>
 *   <li>常见字比例低（专业领域文本完全正常）</li>
 * </ul>
 */
public class GarbledTextFixer {

    private static final String TAG = "GarbledTextFixer";

    /** UTF-8 替换符 */
    private static final char REPLACEMENT = '\uFFFD';

    /** 私用区范围 */
    private static final int PU_START = 0xE000;
    private static final int PU_END = 0xF8FF;

    /** 私用区字符占比阈值（超过30%才认为是乱码） */
    private static final float PU_RATIO_THRESHOLD = 0.3f;

    // ======================== 对外主 API ========================

    /**
     * 对单个 Question 对象所有 String 字段做基础清洗（NUL/BOM/控制字符）。
     * 只在有明确乱码标志时才尝试修复，不会误判正确文本。
     */
    public static Question fixQuestion(Question q) {
        if (q == null) return null;

        setFieldString(q, "questionText",    fixText(q.getQuestionText()));
        setFieldString(q, "optionA",         fixText(q.getOptionA()));
        setFieldString(q, "optionB",         fixText(q.getOptionB()));
        setFieldString(q, "optionC",         fixText(q.getOptionC()));
        setFieldString(q, "optionD",         fixText(q.getOptionD()));
        setFieldString(q, "correctAnswer",   fixText(q.getCorrectAnswer()));
        setFieldString(q, "category",        fixText(q.getCategory()));
        setFieldString(q, "questionType",    fixText(q.getQuestionType()));
        setFieldString(q, "explanation",     fixText(q.getExplanation()));
        setFieldString(q, "analysis",        fixText(getFieldString(q, "analysis")));
        setFieldString(q, "hint",            fixText(getFieldString(q, "hint")));
        setFieldString(q, "knowledgePoint",  fixText(getFieldString(q, "knowledgePoint")));
        setFieldString(q, "subCategory",     fixText(getFieldString(q, "subCategory")));
        setFieldString(q, "tags",            fixText(getFieldString(q, "tags")));
        setFieldString(q, "author",          fixText(getFieldString(q, "author")));
        setFieldString(q, "comment",         fixText(getFieldString(q, "comment")));
        setFieldString(q, "optionE",         fixText(getFieldString(q, "optionE")));
        setFieldString(q, "optionF",         fixText(getFieldString(q, "optionF")));
        setFieldString(q, "optionG",         fixText(getFieldString(q, "optionG")));
        setFieldString(q, "optionH",         fixText(getFieldString(q, "optionH")));
        setFieldString(q, "optionI",         fixText(getFieldString(q, "optionI")));
        setFieldString(q, "optionJ",         fixText(getFieldString(q, "optionJ")));
        setFieldString(q, "optionK",         fixText(getFieldString(q, "optionK")));
        setFieldString(q, "optionL",         fixText(getFieldString(q, "optionL")));
        setFieldString(q, "source",          fixText(getFieldString(q, "source")));
        setFieldString(q, "relatedQuestion", fixText(q.getRelatedQuestion()));

        return q;
    }

    /**
     * 对单个文本做乱码检测与修复（保守策略）。
     * 只在有明确乱码标志（替换符/私用区字符）时才尝试多路解码重试。
     * 正确的中文文本不会被误判。
     */
    public static String fixText(String text) {
        if (text == null) return "";
        String s = text.trim();
        if (s.isEmpty()) return "";

        // 1. 基础清洗：去掉 NUL、BOM、控制字符（不改正常文本）
        s = basicClean(s);
        if (s.isEmpty()) return "";

        // 2. 检测是否有明确乱码标志
        if (!hasDefiniteGarbleSigns(s)) {
            // 没有明确乱码标志 → 原样返回，不做任何解码重试
            return s;
        }

        // 3. 有明确乱码标志 → 尝试多路解码修复
        Log.w(TAG, "检测到明确乱码标志，尝试修复: " + s.substring(0, Math.min(50, s.length())));
        String best = tryRedecode(text);
        if (best != null && !best.isEmpty() && !hasDefiniteGarbleSigns(best)) {
            // 修复后没有乱码标志 → 采用修复结果
            Log.d(TAG, "乱码修复成功");
            return basicClean(best);
        }

        // 4. 修复失败 → 去掉替换符，保留原文（不置空，不破坏数据）
        return s.replace(String.valueOf(REPLACEMENT), "");
    }

    // ======================== 内部算法 ========================

    /** 基础清洗：去掉 NUL、BOM、0x00~0x1F 控制字符（不包括 \t\n\r） */
    private static String basicClean(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 0) continue;
            if (c == 0xFEFF || c == 0xFFFE) continue; // BOM
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') continue;
            // 注意：替换符 U+FFFD 不在这里去掉，留给后续逻辑判断
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 检测是否有明确的乱码标志（保守判断，不依赖常用字比例）。
     * 只有以下情况才返回 true：
     * 1. 存在 U+FFFD 替换符
     * 2. 私用区字符占比 >30%
     */
    private static boolean hasDefiniteGarbleSigns(String s) {
        if (s == null || s.isEmpty()) return false;
        int total = 0;
        int replacement = 0;
        int puChars = 0;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) { i++; continue; }
            total++;
            if (c == REPLACEMENT) {
                replacement++;
            } else if (c >= PU_START && c <= PU_END) {
                puChars++;
            }
        }

        // 替换符：只要有一个就是乱码
        if (replacement > 0) return true;
        // 私用区字符占比 >30%：乱码
        if (total > 0 && (float) puChars / total > PU_RATIO_THRESHOLD) return true;

        return false;
    }

    /**
     * 多路重试解码：把乱码字符串还原为字节，再用不同编码解码。
     * 只在有明确乱码标志时调用。
     */
    private static String tryRedecode(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String best = null;
        int bestScore = -1;

        Charset[] charsets = new Charset[] {
                Charset.forName("GBK"),
                Charset.forName("GB18030"),
                Charset.forName("UTF-8"),
                Charset.forName("ISO-8859-1"),
                Charset.forName("Big5"),
        };

        // 方案 A：用 ISO-8859-1 恢复原始字节，再按各 charset 解码
        try {
            byte[] bytesA = raw.getBytes("ISO-8859-1");
            for (Charset cs : charsets) {
                try {
                    String decoded = new String(bytesA, cs);
                    int score = scoreDecode(decoded);
                    if (score > bestScore) { bestScore = score; best = decoded; }
                } catch (Exception ignore) {}
            }
        } catch (Exception ignore) {}

        // 方案 B：用 UTF-8 取字节再按 GBK 解码
        try {
            byte[] bytesB = raw.getBytes("UTF-8");
            for (Charset cs : new Charset[]{Charset.forName("GBK"), Charset.forName("GB18030"), Charset.forName("Big5")}) {
                try {
                    String decoded = new String(bytesB, cs);
                    int score = scoreDecode(decoded);
                    if (score > bestScore) { bestScore = score; best = decoded; }
                } catch (Exception ignore) {}
            }
        } catch (Exception ignore) {}

        // 方案 C：按 GBK 取字节再按 UTF-8 解码
        try {
            byte[] bytesC = raw.getBytes("GBK");
            String decoded = new String(bytesC, "UTF-8");
            int score = scoreDecode(decoded);
            if (score > bestScore) { bestScore = score; best = decoded; }
        } catch (Exception ignore) {}

        return best;
    }

    /**
     * 解码后质量评分：替换符/私用区越少越好。
     * 不使用常用字比例（避免误判专业文本）。
     */
    private static int scoreDecode(String s) {
        if (s == null || s.isEmpty()) return -1;
        int score = 0;
        int replacement = 0;
        int puChars = 0;
        int total = 0;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) { i++; continue; }
            total++;
            if (c == REPLACEMENT) {
                replacement++;
            } else if (c >= PU_START && c <= PU_END) {
                puChars++;
            } else if (c >= 0x4E00 && c <= 0x9FFF) {
                // CJK 统一汉字区：正常中文，加分
                score += 2;
            } else if (c >= 0x20 && c <= 0x7E) {
                // ASCII 可打印字符：正常，加分
                score += 1;
            }
        }

        // 替换符扣分（严重）
        score -= replacement * 50;
        // 私用区字符扣分
        score -= puChars * 20;

        return score;
    }

    // ======================== 反射辅助 ========================

    private static void setFieldString(Object obj, String fieldName, String value) {
        try {
            Field f = obj.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(obj, value);
        } catch (Exception e) {
            // 字段可能不存在，忽略
        }
    }

    private static String getFieldString(Object obj, String fieldName) {
        try {
            Field f = obj.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            Object v = f.get(obj);
            return v == null ? "" : v.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
