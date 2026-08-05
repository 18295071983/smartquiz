package com.oilquiz.app.util;

import android.util.Log;

import java.nio.charset.StandardCharsets;

/**
 * 导入链路乱码诊断追踪器。
 * 在导入的每个关键节点调用 trace()，打印：
 *  1. 文本前 N 字符
 *  2. 文本字节（UTF-8 十六进制），用于检测是否有编码转换错误
 *  3. 是否出现 U+FFFD 替换符 / 私用区字符
 *
 * 用法：ImportDebugTracer.trace("阶段名", text);
 */
public class ImportDebugTracer {
    private static final String TAG = "ImportDebug";
    private static final int MAX_CHARS = 120;
    private static final int MAX_BYTES = 60;
    private static final boolean ENABLED = true; // 测试期间开启

    public static void trace(String stage, String text) {
        if (!ENABLED) return;
        if (text == null) {
            Log.d(TAG, stage + " [NULL]");
            return;
        }
        int len = text.length();
        String preview = text.substring(0, Math.min(MAX_CHARS, len));
        byte[] bytes = preview.getBytes(StandardCharsets.UTF_8);
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < Math.min(MAX_BYTES, bytes.length); i++) {
            hex.append(String.format("%02X ", bytes[i]));
        }
        // 检测乱码标志
        int fffd = 0, pu = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 0xFFFD) fffd++;
            else if (c >= 0xE000 && c <= 0xF8FF) pu++;
        }
        Log.d(TAG, String.format(
                "=== %s === len=%d chars, U+FFFD=%d, PU=%d%n" +
                "TEXT_PREVIEW: %s%n" +
                "BYTES_HEX  : %s",
                stage, len, fffd, pu, preview, hex.toString().trim()));
    }

    /** 打印一个 Question 对象关键内容的预览 */
    public static void traceQuestion(String stage, Object q) {
        if (!ENABLED) return;
        try {
            StringBuilder sb = new StringBuilder();
            java.lang.reflect.Method getQ = q.getClass().getMethod("getQuestionText");
            java.lang.reflect.Method getA = q.getClass().getMethod("getOptionA");
            java.lang.reflect.Method getB = q.getClass().getMethod("getOptionB");
            java.lang.reflect.Method getC = q.getClass().getMethod("getOptionC");
            java.lang.reflect.Method getD = q.getClass().getMethod("getOptionD");
            java.lang.reflect.Method getAns = q.getClass().getMethod("getCorrectAnswer");
            String qt = (String) getQ.invoke(q);
            String a  = (String) getA.invoke(q);
            String b  = (String) getB.invoke(q);
            String c  = (String) getC.invoke(q);
            String d  = (String) getD.invoke(q);
            String ans= (String) getAns.invoke(q);
            sb.append("Q   : ").append(snip(qt)).append('\n');
            sb.append("  A : ").append(snip(a)).append('\n');
            sb.append("  B : ").append(snip(b)).append('\n');
            sb.append("  C : ").append(snip(c)).append('\n');
            sb.append("  D : ").append(snip(d)).append('\n');
            sb.append("  Ans: ").append(snip(ans));
            Log.d(TAG, "=== Question " + stage + " ===\n" + sb);
        } catch (Exception e) {
            Log.d(TAG, "=== Question " + stage + " === [反射失败]: " + e);
        }
    }

    private static String snip(String s) {
        if (s == null) return "[null]";
        if (s.length() <= 40) return s;
        return s.substring(0, 40) + "...";
    }
}
