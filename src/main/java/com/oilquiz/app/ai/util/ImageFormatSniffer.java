package com.oilquiz.app.ai.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 图片编码嗅探（纯 JVM，不依赖 Android 框架，可单测）。
 *
 * 为什么按字节而不是扩展名：附件是 content:// 复制来的，文件名常与真实编码不符
 * （相册导出、相机改名、下载重命名都会骗过后缀判断）；而各家视觉接口都要求
 * data URI 前缀 / media_type 与实际字节一致，标错轻则被服务商 400，重则被静默忽略图片。
 */
public final class ImageFormatSniffer {

    public static final String JPEG = "image/jpeg";
    public static final String PNG = "image/png";
    public static final String GIF = "image/gif";
    public static final String WEBP = "image/webp";
    public static final String BMP = "image/bmp";
    public static final String TIFF = "image/tiff";
    public static final String HEIC = "image/heic";
    public static final String AVIF = "image/avif";
    public static final String UNKNOWN = "application/octet-stream";

    private ImageFormatSniffer() {
    }

    /** 嗅探文件真实图片 MIME；非图片或读不到时返回 application/octet-stream */
    public static String sniffMimeType(File file) {
        if (file == null || !file.exists()) return UNKNOWN;
        byte[] head = new byte[16];
        int read;
        try (InputStream in = new FileInputStream(file)) {
            read = in.read(head);
        } catch (Throwable t) {
            return UNKNOWN;
        }
        return sniffMimeType(head, read);
    }

    /** 纯字节嗅探 */
    public static String sniffMimeType(byte[] head, int len) {
        if (head == null || len <= 0) return UNKNOWN;
        if (len >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8
                && (head[2] & 0xFF) == 0xFF) return JPEG;
        if (len >= 4 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N'
                && head[3] == 'G') return PNG;
        if (len >= 4 && head[0] == 'G' && head[1] == 'I' && head[2] == 'F' && head[3] == '8') {
            return GIF;
        }
        if (len >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
            return WEBP;
        }
        if (len >= 2 && head[0] == 'B' && head[1] == 'M') return BMP;
        if (len >= 3 && (head[0] & 0xFF) == 0x49 && (head[1] & 0xFF) == 0x49
                && (head[2] & 0xFF) == 0x2A) return TIFF;
        if (len >= 4 && (head[0] & 0xFF) == 0x4D && (head[1] & 0xFF) == 0x4D
                && (head[3] & 0xFF) == 0x2A) return TIFF;
        // ISO-BMFF（HEIC/HEIF/AVIF）：偏移 4 起为 "ftyp"，随后 4 字节是 brand
        if (len >= 12 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') {
            String brand = new String(head, 8, 4, StandardCharsets.US_ASCII)
                    .toLowerCase(Locale.ROOT);
            if (brand.startsWith("hei") || brand.equals("mif1") || brand.equals("msf1")
                    || brand.equals("hevc") || brand.equals("hvc1")) return HEIC;
            if (brand.startsWith("avif") || brand.startsWith("avis")) return AVIF;
        }
        return UNKNOWN;
    }

    /**
     * 各家（OpenAI / Anthropic / Gemini / Qwen-VL）都能直接接收的编码。
     * HEIC/AVIF 不在其中：OpenAI 与 Anthropic 官方都明确不支持，必须客户端转 JPEG/PNG。
     */
    public static boolean isProviderAgnosticFormat(String mimeType) {
        if (mimeType == null) return false;
        return JPEG.equals(mimeType) || PNG.equals(mimeType)
                || WEBP.equals(mimeType) || GIF.equals(mimeType);
    }
}
