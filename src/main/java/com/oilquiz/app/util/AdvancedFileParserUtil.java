package com.oilquiz.app.util;

import android.content.Context;
import android.util.Log;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 高级文件解析工具类
 * 支持PDF、HTML、压缩包等多种格式
 */
public class AdvancedFileParserUtil {
    private static final String TAG = "AdvancedFileParserUtil";

    // ========== PDF解析 ==========

    /**
     * 解析PDF文件获取文本内容
     */
    public static String parsePdfToText(File file) {
        if (file == null || !file.exists()) {
            Log.e(TAG, "PDF文件不存在");
            return null;
        }

        StringBuilder text = new StringBuilder();
        try (PdfReader reader = new PdfReader(file);
             PdfDocument pdfDoc = new PdfDocument(reader)) {

            int numPages = pdfDoc.getNumberOfPages();
            for (int i = 1; i <= numPages; i++) {
                String pageText = PdfTextExtractor.getTextFromPage(pdfDoc.getPage(i));
                text.append(pageText).append("\n\n");
            }

            Log.i(TAG, "PDF解析成功，共" + numPages + "页");
            return text.toString();
        } catch (Exception e) {
            Log.e(TAG, "解析PDF失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 获取PDF信息
     */
    public static Map<String, Object> getPdfInfo(File file) {
        Map<String, Object> info = new HashMap<>();
        if (file == null || !file.exists()) {
            return info;
        }

        try (PdfReader reader = new PdfReader(file);
             PdfDocument pdfDoc = new PdfDocument(reader)) {

            info.put("page_count", pdfDoc.getNumberOfPages());
            info.put("file_size", file.length());
            info.put("title", pdfDoc.getDocumentInfo().getTitle());
            info.put("author", pdfDoc.getDocumentInfo().getAuthor());
            info.put("subject", pdfDoc.getDocumentInfo().getSubject());
            info.put("keywords", pdfDoc.getDocumentInfo().getKeywords());

        } catch (Exception e) {
            Log.e(TAG, "获取PDF信息失败: " + e.getMessage(), e);
        }

        return info;
    }

    // ========== HTML解析 ==========

    /**
     * 解析HTML文件获取纯文本
     */
    public static String parseHtmlToText(File file) {
        if (file == null || !file.exists()) {
            Log.e(TAG, "HTML文件不存在");
            return null;
        }

        try {
            Document doc = Jsoup.parse(file, "UTF-8");
            return doc.text();
        } catch (IOException e) {
            Log.e(TAG, "解析HTML失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 解析HTML获取结构化数据
     */
    public static Map<String, Object> parseHtmlToStructuredData(File file) {
        Map<String, Object> result = new HashMap<>();
        if (file == null || !file.exists()) {
            return result;
        }

        try {
            Document doc = Jsoup.parse(file, "UTF-8");

            result.put("title", doc.title());
            result.put("plain_text", doc.text());
            result.put("links", WebParserUtil.getAllLinks(doc));
            result.put("images", WebParserUtil.getAllImages(doc));
            result.put("paragraphs", WebParserUtil.getAllParagraphs(doc));
            result.put("tables", WebParserUtil.getAllTables(doc));

            Log.i(TAG, "HTML解析成功");
        } catch (IOException e) {
            Log.e(TAG, "解析HTML失败: " + e.getMessage(), e);
        }

        return result;
    }

    // ========== 压缩包解析 ==========

    /**
     * 列出ZIP文件内容
     */
    public static List<Map<String, Object>> listZipContents(File file) {
        List<Map<String, Object>> contents = new ArrayList<>();
        if (file == null || !file.exists()) {
            return contents;
        }

        try (FileInputStream fis = new FileInputStream(file);
             BufferedInputStream bis = new BufferedInputStream(fis);
             ZipArchiveInputStream zis = new ZipArchiveInputStream(bis)) {

            ArchiveEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    Map<String, Object> fileInfo = new HashMap<>();
                    fileInfo.put("name", entry.getName());
                    fileInfo.put("size", entry.getSize());
                    fileInfo.put("is_directory", entry.isDirectory());
                    contents.add(fileInfo);
                }
            }

            Log.i(TAG, "ZIP文件解析成功，共" + contents.size() + "个文件");
        } catch (IOException e) {
            Log.e(TAG, "解析ZIP失败: " + e.getMessage(), e);
        }

        return contents;
    }

    /**
     * 从ZIP中提取文件
     */
    public static byte[] extractFileFromZip(File zipFile, String entryName) {
        if (zipFile == null || !zipFile.exists()) {
            return null;
        }

        try (FileInputStream fis = new FileInputStream(zipFile);
             BufferedInputStream bis = new BufferedInputStream(fis);
             ZipArchiveInputStream zis = new ZipArchiveInputStream(bis)) {

            ArchiveEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(entryName)) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = zis.read(buffer)) != -1) {
                        baos.write(buffer, 0, len);
                    }
                    return baos.toByteArray();
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "从ZIP提取文件失败: " + e.getMessage(), e);
        }

        return null;
    }

    /**
     * 解压GZIP文件
     */
    public static byte[] decompressGzip(File file) {
        if (file == null || !file.exists()) {
            return null;
        }

        try (FileInputStream fis = new FileInputStream(file);
             GZIPInputStream gis = new GZIPInputStream(fis);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[8192];
            int len;
            while ((len = gis.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }

            Log.i(TAG, "GZIP解压成功");
            return baos.toByteArray();
        } catch (IOException e) {
            Log.e(TAG, "解压GZIP失败: " + e.getMessage(), e);
            return null;
        }
    }

    // ========== 文件类型检测 ==========

    /**
     * 检测文件是否为PDF
     */
    public static boolean isPdfFile(File file) {
        String ext = FileParserUtil.getFileExtension(file);
        return "pdf".equalsIgnoreCase(ext);
    }

    /**
     * 检测文件是否为HTML
     */
    public static boolean isHtmlFile(File file) {
        String ext = FileParserUtil.getFileExtension(file);
        return "html".equalsIgnoreCase(ext) || "htm".equalsIgnoreCase(ext);
    }

    /**
     * 检测文件是否为ZIP
     */
    public static boolean isZipFile(File file) {
        String ext = FileParserUtil.getFileExtension(file);
        return "zip".equalsIgnoreCase(ext);
    }

    /**
     * 检测文件是否为GZIP
     */
    public static boolean isGzipFile(File file) {
        String ext = FileParserUtil.getFileExtension(file);
        return "gz".equalsIgnoreCase(ext) || "gzip".equalsIgnoreCase(ext);
    }

    /**
     * 智能解析文件（根据扩展名自动选择解析方式）
     */
    public static Map<String, Object> smartParseFile(File file) {
        Map<String, Object> result = new HashMap<>();
        if (file == null || !file.exists()) {
            result.put("success", false);
            result.put("error", "文件不存在");
            return result;
        }

        String ext = FileParserUtil.getFileExtension(file).toLowerCase();
        result.put("file_name", file.getName());
        result.put("file_size", file.length());
        result.put("file_extension", ext);

        try {
            switch (ext) {
                case "pdf":
                    String pdfText = parsePdfToText(file);
                    result.put("success", true);
                    result.put("content", pdfText);
                    result.put("file_type", "pdf");
                    result.put("pdf_info", getPdfInfo(file));
                    break;

                case "html":
                case "htm":
                    Map<String, Object> htmlData = parseHtmlToStructuredData(file);
                    result.put("success", true);
                    result.put("content", htmlData);
                    result.put("file_type", "html");
                    break;

                case "zip":
                    List<Map<String, Object>> zipContents = listZipContents(file);
                    result.put("success", true);
                    result.put("content", zipContents);
                    result.put("file_type", "zip");
                    break;

                case "txt":
                case "md":
                case "log":
                    String text = FileParserUtil.parseTextFile(file);
                    result.put("success", true);
                    result.put("content", text);
                    result.put("file_type", "text");
                    break;

                case "csv":
                    List<String[]> csvData = FileParserUtil.parseCsvFile(file);
                    result.put("success", true);
                    result.put("content", csvData);
                    result.put("file_type", "csv");
                    break;

                case "json":
                    Map<String, Object> jsonData = FileParserUtil.parseJsonToMap(file);
                    result.put("success", true);
                    result.put("content", jsonData);
                    result.put("file_type", "json");
                    break;

                default:
                    result.put("success", false);
                    result.put("error", "不支持的文件格式: " + ext);
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            Log.e(TAG, "智能解析文件失败: " + e.getMessage(), e);
        }

        return result;
    }
}
