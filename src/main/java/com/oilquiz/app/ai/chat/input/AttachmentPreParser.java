package com.oilquiz.app.ai.chat.input;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.manager.OCRManager;
import com.oilquiz.app.util.fileparser.FileContentExtractor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 附件预解析器（附件生命周期管理的核心模块）
 *
 * 设计目标：在消息发送给 Agent 之前，由系统完成附件解析，形成状态闭环：
 *   文件落盘 → 类型判断 → 文本提取/OCR → 结果缓存 → 确定状态注入模型上下文
 *
 * 解决的核心问题：
 * 1. 模型不再需要"猜测"附件状态（解析成功/失败/原因都由系统明确给出）
 * 2. 同一文件不重复解析（内存 + 磁盘缓存）
 * 3. PDF 自动分类：文字版 PDF 直接提取文本；扫描件 PDF 拆页 OCR
 * 4. 失败原因细分（文件不存在/提取为空/OCR超时/不支持的类型）
 */
public class AttachmentPreParser {

    private static final String TAG = "AttachmentPreParser";
    private static final int PARSE_TIMEOUT_SECONDS = 120; // 单个附件解析超时
    private static final int MIN_PDF_TEXT_LENGTH = 100;   // PDF 文本层内容低于此长度视为扫描件
    private static final int MAX_OCR_PAGES = 10;          // 扫描件 PDF 最多 OCR 页数（控制耗时）

    /** 解析状态（与用户可见状态一一对应） */
    public enum ParseStatus {
        SUCCESS,        // 解析成功，内容可用
        PARTIAL_SUCCESS,// 部分成功（如 PDF 部分页面识别失败）
        FAILED          // 解析失败，附带错误码和原因
    }

    /** 错误码（细分失败原因，避免笼统的"未检测到附件"） */
    public static final String ERR_FILE_NOT_FOUND = "FILE_NOT_FOUND";
    public static final String ERR_UNSUPPORTED_TYPE = "UNSUPPORTED_TYPE";
    public static final String ERR_PARSE_TIMEOUT = "PARSE_TIMEOUT";
    public static final String ERR_EXTRACT_EMPTY = "EXTRACT_EMPTY";
    public static final String ERR_OCR_FAILED = "OCR_FAILED";

    /**
     * 解析结果（确定性状态，直接注入模型上下文）
     */
    public static class ParseResult {
        public final String attachmentId;
        public ParseStatus status;
        public String content;       // 解析出的文本内容
        public String method;        // 解析方式：text_extract / online_ocr / local_ocr / pdf_pages_ocr
        public String errorCode;     // 失败错误码（成功时为 null）
        public String errorMessage;  // 失败原因描述
        public int pageCount;        // PDF 页数（非 PDF 为 0）
        public boolean fromCache;    // 是否命中缓存

        ParseResult(String attachmentId) {
            this.attachmentId = attachmentId;
        }

        public boolean isUsable() {
            return (status == ParseStatus.SUCCESS || status == ParseStatus.PARTIAL_SUCCESS)
                    && content != null && !content.trim().isEmpty();
        }
    }

    /** 进度回调（用于前端展示解析进度） */
    public interface ProgressCallback {
        void onProgress(int doneCount, int totalCount, String currentFile, String stage);
        void onAllCompleted(List<ParseResult> results);
    }

    private final Context context;
    private final OCRManager ocrManager;
    private final FileContentExtractor fileContentExtractor;
    private final ExecutorService executor;
    private final File cacheDir;

    // 内存缓存：cacheKey -> ParseResult
    private static final Map<String, ParseResult> memoryCache = new ConcurrentHashMap<>();

    public AttachmentPreParser(Context context) {
        this.context = context.getApplicationContext();
        this.ocrManager = new OCRManager(this.context);
        this.fileContentExtractor = new FileContentExtractor(this.context);
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "AttachPreParser");
            t.setDaemon(true);
            return t;
        });
        this.cacheDir = new File(this.context.getCacheDir(), "parse_cache");
        if (!cacheDir.exists()) cacheDir.mkdirs();
    }

    /**
     * 异步预解析所有附件（带缓存与进度回调）
     *
     * @param attachments  附件列表
     * @param localPathMap 附件 url(Uri) -> 本地落盘路径
     * @param callback     进度/完成回调（主线程之外的线程调用，需自行切回 UI 线程）
     */
    public void parseAllAsync(List<ChatMessage.Attachment> attachments,
                              Map<Uri, String> localPathMap,
                              ProgressCallback callback) {
        executor.submit(() -> {
            List<ParseResult> results = new ArrayList<>();
            int total = attachments != null ? attachments.size() : 0;
            int done = 0;

            if (attachments != null) {
                for (ChatMessage.Attachment att : attachments) {
                    if (callback != null) {
                        callback.onProgress(done, total, att.name, "解析中");
                    }
                    String localPath = localPathMap != null ? localPathMap.get(Uri.parse(att.url)) : null;
                    ParseResult result = parseOne(att, localPath);
                    results.add(result);

                    // 回写附件状态字段（形成附件自身的状态闭环）
                    att.isExtracting = false;
                    if (result.isUsable()) {
                        att.isExtracted = true;
                        att.extractedContent = result.content;
                        att.extractionError = null;
                    } else {
                        att.isExtracted = false;
                        att.extractionError = "[" + result.errorCode + "] " + result.errorMessage;
                    }

                    done++;
                    if (callback != null) {
                        callback.onProgress(done, total, att.name,
                                result.isUsable() ? "解析完成" : "解析失败");
                    }
                }
            }

            if (callback != null) {
                callback.onAllCompleted(results);
            }
        });
    }

    /**
     * 解析单个附件（同步，内部使用缓存）
     */
    public ParseResult parseOne(ChatMessage.Attachment att, String localPath) {
        ParseResult result = new ParseResult(att.id);

        // 1. 文件状态检查
        if (localPath == null) {
            result.status = ParseStatus.FAILED;
            result.errorCode = ERR_FILE_NOT_FOUND;
            result.errorMessage = "附件未成功保存到本地，无法解析";
            return result;
        }
        File file = new File(localPath);
        if (!file.exists() || file.length() == 0) {
            result.status = ParseStatus.FAILED;
            result.errorCode = ERR_FILE_NOT_FOUND;
            result.errorMessage = "文件不存在或为空: " + file.getName();
            return result;
        }

        // 2. 缓存检查（同一文件不重复解析）
        String cacheKey = buildCacheKey(file);
        ParseResult cached = getFromCache(cacheKey);
        if (cached != null) {
            ParseResult reused = cloneResult(cached, att.id);
            reused.fromCache = true;
            Log.i(TAG, "解析缓存命中: " + att.name + ", method=" + reused.method);
            return reused;
        }

        // 3. 按类型分派解析
        long start = System.currentTimeMillis();
        try {
            String type = att.type != null ? att.type : "";
            String name = att.name != null ? att.name.toLowerCase() : "";
            boolean isImage = "image".equals(type) || name.endsWith(".jpg") || name.endsWith(".jpeg")
                    || name.endsWith(".png") || name.endsWith(".webp") || name.endsWith(".bmp");
            boolean isPdf = "pdf".equals(type) || name.endsWith(".pdf");

            if (isImage) {
                parseImage(att, file, result);
            } else if (isPdf) {
                parsePdf(att, file, result);
            } else {
                parseTextLike(att, file, result);
            }
        } catch (Exception e) {
            result.status = ParseStatus.FAILED;
            result.errorCode = ERR_OCR_FAILED;
            result.errorMessage = "解析异常: " + e.getMessage();
        }

        long cost = System.currentTimeMillis() - start;
        Log.i(TAG, "附件解析完成: " + att.name + ", status=" + result.status
                + ", method=" + result.method + ", cost=" + cost + "ms"
                + ", content_len=" + (result.content != null ? result.content.length() : 0));

        // 4. 写入缓存（成功和部分成功都缓存，失败不缓存以便重试）
        if (result.isUsable()) {
            putToCache(cacheKey, result);
        }
        return result;
    }

    // ==================== 分类解析 ====================

    /**
     * 图片：在线 OCR 优先，失败回退本地
     */
    private void parseImage(ChatMessage.Attachment att, File file, ParseResult result) {
        try {
            String text = ocrManager.recognizeFileOnlineFirst(file.getAbsolutePath(), "auto")
                    .get(PARSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (text == null || text.trim().isEmpty()
                    || text.startsWith("OCR识别失败") || text.equals("OCR未识别到文本")) {
                result.status = ParseStatus.FAILED;
                result.errorCode = ERR_OCR_FAILED;
                result.errorMessage = "图片 OCR 未识别到文字（可能图片模糊或无文字内容）";
                return;
            }
            result.status = ParseStatus.SUCCESS;
            result.content = text.trim();
            result.method = "online_ocr";
        } catch (java.util.concurrent.TimeoutException e) {
            result.status = ParseStatus.FAILED;
            result.errorCode = ERR_PARSE_TIMEOUT;
            result.errorMessage = "图片 OCR 超时（>" + PARSE_TIMEOUT_SECONDS + "秒）";
        } catch (Exception e) {
            result.status = ParseStatus.FAILED;
            result.errorCode = ERR_OCR_FAILED;
            result.errorMessage = "图片 OCR 失败: " + e.getMessage();
        }
    }

    /**
     * PDF：先判断是否有文字层
     * - 文字版 PDF：直接提取文本（快）
     * - 扫描件 PDF：拆页渲染 + 逐页 OCR（在线优先）
     */
    private void parsePdf(ChatMessage.Attachment att, File file, ParseResult result) {
        // 1. 先尝试文本层提取
        String textContent = null;
        try {
            textContent = fileContentExtractor.extractContent(Uri.fromFile(file))
                    .get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.w(TAG, "PDF 文本层提取失败: " + e.getMessage());
        }

        if (textContent != null && textContent.trim().length() >= MIN_PDF_TEXT_LENGTH) {
            // 文字版 PDF
            result.status = ParseStatus.SUCCESS;
            result.content = textContent.trim();
            result.method = "text_extract";
            return;
        }

        // 2. 扫描件 PDF：拆页 OCR
        parsePdfPagesWithOcr(file, result);
    }

    /**
     * 扫描件 PDF：逐页渲染为图片并 OCR（在线视觉模型优先）
     */
    private void parsePdfPagesWithOcr(File file, ParseResult result) {
        ParcelFileDescriptor fd = null;
        PdfRenderer renderer = null;
        try {
            fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(fd);
            int pageCount = renderer.getPageCount();
            result.pageCount = pageCount;

            int ocrPages = Math.min(pageCount, MAX_OCR_PAGES);
            StringBuilder sb = new StringBuilder();
            int failedPages = 0;

            for (int i = 0; i < ocrPages; i++) {
                PdfRenderer.Page page = renderer.openPage(i);
                int width = page.getWidth();
                int height = page.getHeight();
                float scale = 2.5f;
                if (Math.max(width, height) * scale > 3000) {
                    scale = 3000f / Math.max(width, height);
                }
                Bitmap bitmap = Bitmap.createBitmap(
                        Math.max((int) (width * scale), 100),
                        Math.max((int) (height * scale), 100),
                        Bitmap.Config.ARGB_8888);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                page.close();

                // 渲染页面临时文件 → 在线 OCR 优先
                File pageFile = new File(cacheDir, "pdf_page_" + System.currentTimeMillis() + "_" + i + ".jpg");
                try (FileOutputStream fos = new FileOutputStream(pageFile)) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos);
                }
                bitmap.recycle();

                try {
                    String pageText = ocrManager.recognizeFileOnlineFirst(pageFile.getAbsolutePath(), "auto")
                            .get(PARSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    if (pageText != null && !pageText.trim().isEmpty()
                            && !pageText.startsWith("OCR识别失败") && !pageText.equals("OCR未识别到文本")) {
                        sb.append("【第 ").append(i + 1).append(" 页】\n").append(pageText.trim()).append("\n\n");
                    } else {
                        failedPages++;
                    }
                } catch (Exception e) {
                    failedPages++;
                    Log.w(TAG, "PDF 第 " + (i + 1) + " 页 OCR 失败: " + e.getMessage());
                } finally {
                    pageFile.delete();
                }
            }

            if (sb.length() == 0) {
                result.status = ParseStatus.FAILED;
                result.errorCode = ERR_OCR_FAILED;
                result.errorMessage = "扫描件 PDF 共 " + pageCount + " 页，OCR 全部失败";
                return;
            }

            result.content = sb.toString().trim();
            result.method = "pdf_pages_ocr";
            if (failedPages > 0 || pageCount > ocrPages) {
                result.status = ParseStatus.PARTIAL_SUCCESS;
                result.errorMessage = "共 " + pageCount + " 页，成功识别 " + (ocrPages - failedPages) + " 页"
                        + (pageCount > ocrPages ? "（超过 " + MAX_OCR_PAGES + " 页的部分未处理）" : "");
            } else {
                result.status = ParseStatus.SUCCESS;
            }
        } catch (Exception e) {
            result.status = ParseStatus.FAILED;
            result.errorCode = ERR_OCR_FAILED;
            result.errorMessage = "PDF 拆页 OCR 失败: " + e.getMessage();
        } finally {
            try { if (renderer != null) renderer.close(); } catch (Exception ignored) {}
            try { if (fd != null) fd.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * 文本类文件（txt/csv/json/md 等）：直接提取
     */
    private void parseTextLike(ChatMessage.Attachment att, File file, ParseResult result) {
        try {
            String content = fileContentExtractor.extractContent(Uri.fromFile(file))
                    .get(30, TimeUnit.SECONDS);
            if (content == null || content.trim().isEmpty()) {
                result.status = ParseStatus.FAILED;
                result.errorCode = ERR_EXTRACT_EMPTY;
                result.errorMessage = "文件内容为空或无法解析该格式";
                return;
            }
            result.status = ParseStatus.SUCCESS;
            result.content = content.trim();
            result.method = "text_extract";
        } catch (Exception e) {
            result.status = ParseStatus.FAILED;
            result.errorCode = ERR_UNSUPPORTED_TYPE;
            result.errorMessage = "不支持的文件类型或解析失败: " + e.getMessage();
        }
    }

    // ==================== 缓存 ====================

    /**
     * 缓存 key = sha1(路径:大小:修改时间)，文件变化后自动失效
     */
    private String buildCacheKey(File file) {
        String raw = file.getAbsolutePath() + ":" + file.length() + ":" + file.lastModified();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    private ParseResult getFromCache(String cacheKey) {
        ParseResult mem = memoryCache.get(cacheKey);
        if (mem != null) return mem;

        // 磁盘缓存
        File cacheFile = new File(cacheDir, cacheKey + ".txt");
        if (!cacheFile.exists()) return null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(cacheFile), StandardCharsets.UTF_8))) {
            String method = reader.readLine();
            String errorCode = reader.readLine();
            String errorMessage = reader.readLine();
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            ParseResult r = new ParseResult(null);
            r.method = method;
            r.content = content.toString().trim();
            if (errorCode == null || errorCode.isEmpty() || "null".equals(errorCode)) {
                r.status = ParseStatus.SUCCESS;
            } else {
                r.status = ParseStatus.PARTIAL_SUCCESS;
                r.errorCode = errorCode;
                r.errorMessage = errorMessage;
            }
            memoryCache.put(cacheKey, r);
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    private void putToCache(String cacheKey, ParseResult result) {
        memoryCache.put(cacheKey, result);
        File cacheFile = new File(cacheDir, cacheKey + ".txt");
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(cacheFile), StandardCharsets.UTF_8)) {
            writer.write(result.method != null ? result.method : "text_extract");
            writer.write("\n");
            writer.write(result.status == ParseStatus.PARTIAL_SUCCESS && result.errorCode != null
                    ? result.errorCode : "null");
            writer.write("\n");
            writer.write(result.errorMessage != null ? result.errorMessage : "null");
            writer.write("\n");
            writer.write(result.content != null ? result.content : "");
        } catch (Exception e) {
            Log.w(TAG, "写入解析缓存失败: " + e.getMessage());
        }
    }

    private ParseResult cloneResult(ParseResult src, String attachmentId) {
        ParseResult r = new ParseResult(attachmentId);
        r.status = src.status;
        r.content = src.content;
        r.method = src.method;
        r.errorCode = src.errorCode;
        r.errorMessage = src.errorMessage;
        r.pageCount = src.pageCount;
        return r;
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
