package com.oilquiz.app.util.render;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/**
 * 文件文本读取工具：UTF-8 优先，解码出乱码（替换字符）时回退 GBK。
 * 修复各渲染引擎用 FileReader(系统默认编码)导致 GBK 中文乱码的问题。
 */
public final class FileEncodingUtil {

    private FileEncodingUtil() {
    }

    /** 读取文件全部字节 */
    public static byte[] readAllBytes(File file) throws java.io.IOException {
        FileInputStream fis = new FileInputStream(file);
        try {
            byte[] buf = new byte[(int) Math.min(file.length(), Integer.MAX_VALUE)];
            int off = 0;
            int n;
            while (off < buf.length && (n = fis.read(buf, off, buf.length - off)) >= 0) {
                off += n;
            }
            return off == buf.length ? buf : java.util.Arrays.copyOf(buf, off);
        } finally {
            fis.close();
        }
    }

    /**
     * 读取文本：UTF-8 优先，含替换字符（\uFFFD）则尝试 GBK；均失败返回 UTF-8 解码结果。
     * @param maxChars 最多读取的字符数（超出截断），&lt;=0 不限制
     */
    public static String readText(File file, int maxChars) throws java.io.IOException {
        byte[] bytes = readAllBytes(file);
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        String text = utf8;
        if (utf8.indexOf('\uFFFD') >= 0) {
            try {
                text = new String(bytes, "GBK");
            } catch (Exception ignored) {
                text = utf8;
            }
        }
        if (maxChars > 0 && text.length() > maxChars) {
            return text.substring(0, maxChars);
        }
        return text;
    }

    /** HTML 转义（文本引擎输出 HTML 时防 XSS 与显示错乱） */
    public static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
