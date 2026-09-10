package com.oilquiz.app.ai.knowledge;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.util.Log;

import com.oilquiz.app.ai.speech.SpeechManager;
import com.oilquiz.app.ai.speech.SpeechRecognitionService;
import com.oilquiz.app.util.fileparser.FileContentExtractor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识库管理器：独立 SQLite 数据库 + FTS5 全文检索。
 *
 * <p>设计要点：
 * <ul>
 *   <li>使用独立的 knowledge_base.db，不占用/不影响主数据库（smartquiz_database），零迁移风险；</li>
 *   <li>FTS5 虚拟表做全文索引（title/category/keywords/content），bm25() 相关性排序；</li>
 *   <li>中文检索：unicode61 tokenizer 对 CJK 逐字建 token，查询时自动把中文连续段拆字并用 AND 组合，实现
 *       "包含全部关键词" 的检索；英文/数字按词 + 前缀匹配；</li>
 *   <li>查询串完全由白名单 token 生成（剥离 FTS5 特殊字符），参数化绑定，无 SQL/FTS 注入面；</li>
 *   <li>为将来向量语义检索（RAG）预留了 keywords 字段与扩展位（如 embedding 列），无需重构表结构。</li>
 * </ul>
 */
public class KnowledgeBaseManager {
    private static final String TAG = "KnowledgeBaseManager";
    private static final String DB_NAME = "knowledge_base.db";
    private static final int DB_VERSION = 1;

    /** 查询时返回的默认结果条数上限 */
    private static final int DEFAULT_TOP_K = 5;
    /** 结果条数上限（防止一次拉取过多） */
    private static final int MAX_TOP_K = 20;

    private static volatile KnowledgeBaseManager instance;
    private final Context context;
    private volatile KnowledgeDbHelper dbHelper;

    /** 单个知识块（内部数据结构） */
    public static class KnowledgeChunk {
        public long id;
        public String title;
        public String category;
        public String keywords;
        public String content;
        public String source;
        public long updatedAt;

        public KnowledgeChunk() {
        }

        public KnowledgeChunk(String title, String category, String keywords,
                              String content, String source) {
            this.title = title == null ? "" : title.trim();
            this.category = (category == null || category.trim().isEmpty()) ? "general" : category.trim();
            this.keywords = keywords == null ? "" : keywords.trim();
            this.content = content == null ? "" : content.trim();
            this.source = source == null ? "" : source.trim();
        }
    }

    private static class KnowledgeDbHelper extends SQLiteOpenHelper {
        KnowledgeDbHelper(Context context) {
            super(context, DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            createTables(db);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // 预留升级位：未来可在此处加 ALTER TABLE / 重建 FTS
            Log.i(TAG, "onUpgrade knowledge_base.db " + oldVersion + " -> " + newVersion);
        }

        private void createTables(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS kb_chunks ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "title TEXT NOT NULL,"
                    + "category TEXT NOT NULL DEFAULT 'general',"
                    + "keywords TEXT NOT NULL DEFAULT '',"
                    + "content TEXT NOT NULL,"
                    + "source TEXT NOT NULL DEFAULT '',"
                    + "created_at INTEGER NOT NULL,"
                    + "updated_at INTEGER NOT NULL"
                    + ")");
            // FTS5 全文索引：unicode61 tokenizer（Android 12+ 内置 SQLite >= 3.32，FTS5 稳定可用）。
            // 中文处理：unicode61 会把连续中文串（含相邻 ASCII）合并成一个长 token，导致中文关键词无法命中，
            // 因此专门提供 search_text 列：入库时对标题/分类/关键词/正文中的中文做逐字空格化，
            // 让每个汉字成为独立 token；检索时把中文连续段拆字 AND 组合，即实现"包含全部关键字"的中文检索。
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS kb_chunks_fts USING fts5("
                    + "title, category, keywords, search_text, content,"
                    + "tokenize = 'unicode61 remove_diacritics 2'"
                    + ")");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_kb_chunks_category ON kb_chunks(category)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_kb_chunks_title ON kb_chunks(title)");
        }
    }

    private KnowledgeBaseManager(Context context) {
        this.context = context.getApplicationContext();
        this.dbHelper = new KnowledgeDbHelper(this.context);
    }

    public static KnowledgeBaseManager getInstance(Context context) {
        if (instance == null) {
            synchronized (KnowledgeBaseManager.class) {
                if (instance == null) {
                    instance = new KnowledgeBaseManager(context);
                }
            }
        }
        return instance;
    }

    private SQLiteDatabase getReadableDb() {
        KnowledgeDbHelper helper = dbHelper;
        if (helper == null) {
            synchronized (this) {
                if (dbHelper == null) {
                    dbHelper = new KnowledgeDbHelper(context);
                }
                helper = dbHelper;
            }
        }
        return helper.getReadableDatabase();
    }

    private SQLiteDatabase getWritableDb() {
        KnowledgeDbHelper helper = dbHelper;
        if (helper == null) {
            synchronized (this) {
                if (dbHelper == null) {
                    dbHelper = new KnowledgeDbHelper(context);
                }
                helper = dbHelper;
            }
        }
        return helper.getWritableDatabase();
    }

    // ==================== 检索 ====================

    /**
     * 全文检索知识库。
     *
     * @param query    检索关键词（中文/英文/数字混合均可，自动安全转义）
     * @param category 分类过滤，null 或空表示不过滤
     * @param topK     返回条数上限，<=0 时使用默认值
     * @return JSON 数组，元素含 id/title/category/keywords/content/source/snippet
     */
    public synchronized JSONArray search(String query, String category, int topK) {
        JSONArray results = new JSONArray();
        Cursor cursor = null;
        try {
            SQLiteDatabase db = getReadableDb();
            int limit = (topK <= 0) ? DEFAULT_TOP_K : Math.min(topK, MAX_TOP_K);
            boolean hasQuery = query != null && !query.trim().isEmpty();
            boolean hasCategory = category != null && !category.trim().isEmpty();

            String sql;
            String[] args;
            String matchQuery = hasQuery ? buildMatchQuery(query) : null;

            if (hasQuery && matchQuery != null) {
                if (hasCategory) {
                    sql = "SELECT c.id, c.title, c.category, c.keywords, c.source, c.updated_at, c.content, "
                            + "snippet(kb_chunks_fts, 4, '[', ']', '…', 20) AS snip "
                            + "FROM kb_chunks_fts JOIN kb_chunks c ON c.id = kb_chunks_fts.rowid "
                            + "WHERE kb_chunks_fts MATCH ? AND c.category = ? "
                            + "ORDER BY bm25(kb_chunks_fts) LIMIT ?";
                    args = new String[]{matchQuery, category.trim(), String.valueOf(limit)};
                } else {
                    sql = "SELECT c.id, c.title, c.category, c.keywords, c.source, c.updated_at, c.content, "
                            + "snippet(kb_chunks_fts, 4, '[', ']', '…', 20) AS snip "
                            + "FROM kb_chunks_fts JOIN kb_chunks c ON c.id = kb_chunks_fts.rowid "
                            + "WHERE kb_chunks_fts MATCH ? "
                            + "ORDER BY bm25(kb_chunks_fts) LIMIT ?";
                    args = new String[]{matchQuery, String.valueOf(limit)};
                }
            } else if (hasCategory) {
                // 无关键词：按分类列出最近条目
                sql = "SELECT id, title, category, keywords, source, updated_at, content, "
                        + "substr(content, 1, 120) AS snip FROM kb_chunks "
                        + "WHERE category = ? ORDER BY updated_at DESC LIMIT ?";
                args = new String[]{category.trim(), String.valueOf(limit)};
            } else {
                // 无关键词无分类：列出最近条目
                sql = "SELECT id, title, category, keywords, source, updated_at, content, "
                        + "substr(content, 1, 120) AS snip FROM kb_chunks "
                        + "ORDER BY updated_at DESC LIMIT ?";
                args = new String[]{String.valueOf(limit)};
            }

            cursor = db.rawQuery(sql, args);
            while (cursor.moveToNext()) {
                JSONObject item = new JSONObject();
                item.put("id", cursor.getLong(0));
                item.put("title", cursor.getString(1));
                item.put("category", cursor.getString(2));
                item.put("keywords", cursor.getString(3));
                item.put("source", cursor.getString(4));
                item.put("updated_at", cursor.getLong(5));
                item.put("content", cursor.getString(6));
                item.put("snippet", cursor.getString(7) == null ? "" : cursor.getString(7));
                results.put(item);
            }
        } catch (Exception e) {
            Log.e(TAG, "search 失败: " + e.getMessage(), e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return results;
    }

    // ==================== 增删 ====================

    /**
     * 添加单条知识块。
     *
     * @return 新条目 id；失败返回 -1
     */
    public synchronized long addChunk(String title, String category, String keywords,
                                      String content, String source) {
        KnowledgeChunk chunk = new KnowledgeChunk(title, category, keywords, content, source);
        if (chunk.title.isEmpty() && chunk.content.isEmpty()) {
            Log.w(TAG, "addChunk 被拒绝：标题与内容均为空");
            return -1;
        }
        if (chunk.content.isEmpty()) {
            chunk.content = chunk.title;
        }
        long now = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDb();
        db.beginTransaction();
        try {
            long id = db.insertOrThrow("kb_chunks", null, chunkValues(chunk, now));
            insertFts(db, id, chunk);
            db.setTransactionSuccessful();
            return id;
        } catch (Exception e) {
            Log.e(TAG, "addChunk 失败: " + e.getMessage(), e);
            return -1;
        } finally {
            db.endTransaction();
        }
    }

    /**
     * 批量添加知识块。
     *
     * @param items JSON 数组，元素字段：title/category/keywords/content/source
     * @return 成功添加的条数
     */
    public synchronized int addBatch(JSONArray items) {
        if (items == null) {
            return 0;
        }
        int added = 0;
        long now = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDb();
        db.beginTransaction();
        try {
            for (int i = 0; i < items.length(); i++) {
                JSONObject obj = items.optJSONObject(i);
                if (obj == null) {
                    continue;
                }
                KnowledgeChunk chunk = fromJson(obj);
                if (chunk == null || (chunk.title.isEmpty() && chunk.content.isEmpty())) {
                    continue;
                }
                if (chunk.content.isEmpty()) {
                    chunk.content = chunk.title;
                }
                long id = db.insertOrThrow("kb_chunks", null, chunkValues(chunk, now));
                insertFts(db, id, chunk);
                added++;
            }
            db.setTransactionSuccessful();
        } catch (Exception e) {
            Log.e(TAG, "addBatch 失败: " + e.getMessage(), e);
        } finally {
            db.endTransaction();
        }
        return added;
    }

    /**
     * 导入知识 JSON。
     * 支持两种结构：
     * 1. {"chunks":[...]} 顶层带 chunks 数组（Python 预处理脚本输出格式）；
     * 2. 直接是数组 [...]。
     *
     * @return 成功添加的条数
     */
    public synchronized int importJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return 0;
        }
        try {
            String trimmed = json.trim();
            if (trimmed.startsWith("[")) {
                return addBatch(new JSONArray(trimmed));
            }
            JSONObject root = new JSONObject(trimmed);
            JSONArray chunks = root.optJSONArray("chunks");
            if (chunks == null) {
                return 0;
            }
            return addBatch(chunks);
        } catch (JSONException e) {
            Log.e(TAG, "importJson 解析失败: " + e.getMessage());
            return 0;
        }
    }

    // ==================== 文档直接导入 ====================

    /** 单文件导入大小上限：10MB */
    private static final long MAX_DOC_SIZE = 10L * 1024 * 1024;
    /** 提取文本最大字符数（超出截断，防止内存与块数失控） */
    private static final int MAX_TEXT_CHARS = 5_000_000;
    /** 音频识别超时（秒）：音频可能较长，放宽到 120s */
    private static final long AUDIO_ASR_TIMEOUT_SECONDS = 120;
    /** 支持直接走语音识别（ASR）的音频扩展名 */
    private static final Set<String> AUDIO_EXTS = new HashSet<>(Arrays.asList(
            "mp3", "wav", "m4a", "aac", "amr", "ogg", "flac", "wma", "opus", "3gp"));

    /**
     * 直接导入文件到知识库：文档（Word/Excel/TXT/MD/CSV/PDF/HTML）、图片（OCR）、音频（ASR 语音识别转写）→
     * 按标题/段落切块 → 入库。复用现有解析与识别能力，无需用户手动转换。
     * 音频直接调用项目内语音识别模型（在线 qwen3-asr/whisper 或本地 SenseVoice），不做多余的音频提取/转码步骤。
     *
     * @param filePath 文件绝对路径
     * @param category 知识分类，null 或空则 general
     * @param title    指定标题（优先于文件名；图片/音频文件名通常无意义，建议传入）
     * @return {"success":true,"added":N,"title":..,"source":..} 或 {"success":false,"message":..}
     */
    public synchronized JSONObject importDocument(String filePath, String category, String title) {
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            return errorResult("文件不存在: " + filePath);
        }
        if (file.length() > MAX_DOC_SIZE) {
            return errorResult("文件过大（超过10MB）: " + filePath);
        }
        String cat = (category == null || category.trim().isEmpty()) ? "general" : category.trim();
        String fileName = file.getName();
        String docTitle = (title == null || title.trim().isEmpty()) ? fileName : title.trim();
        String ext = getExtension(fileName);

        if (isAudioExt(ext)) {
            return importAudioFile(file, cat, docTitle, filePath);
        }
        return importTextOrImageFile(file, cat, docTitle, filePath);
    }

    private static String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return (dot >= 0 && dot < fileName.length() - 1)
                ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static boolean isAudioExt(String ext) {
        return AUDIO_EXTS.contains(ext);
    }

    /**
     * 音频文件：直接用项目内 ASR 模型转写为文字（不做"提取音频"等多余步骤）→ 切块入库。
     * 模型选择逻辑与 agent 语音识别工具 VoiceInputTool 保持一致：
     * 1. 在线/本地任一可用即可识别（isAnyAsrAvailable）；
     * 2. 用户在"功能专用模型"中显式选择本地 SenseVoice 作为语音识别模型 → 本地优先（不走在线）；
     * 3. 否则在线 ASR 可用时优先在线，在线不可用才走本地。
     */
    private JSONObject importAudioFile(File file, String category, String docTitle, String filePath) {
        try {
            SpeechManager speech = SpeechManager.getInstance(context);
            if (!speech.isAnyAsrAvailable()) {
                return errorResult("语音识别服务不可用：未配置在线语音识别模型，且本地语音识别不可用。"
                        + "请先在模型设置中为'语音识别'选择模型（如 qwen3-asr-flash / whisper-1），"
                        + "或确认本地语音模型已加载");
            }
            // 与 VoiceInputTool 一致：用户显式选择本地 SenseVoice 作为语音识别专用模型 → 本地优先
            boolean preferLocal = isLocalAsrSelected();
            CompletableFuture<SpeechRecognitionService.RecognitionResult> future;
            if (!preferLocal && speech.isAsrAvailable()) {
                // 在线 ASR（qwen3-asr / whisper 等），语言自动检测
                future = speech.recognizeSpeech(file, null);
            } else {
                // 本地 SenseVoice
                future = speech.recognizeSpeechLocal(file, null);
            }
            SpeechRecognitionService.RecognitionResult rr =
                    future.get(AUDIO_ASR_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            String text = (rr == null || rr.text == null) ? "" : rr.text.trim();
            if (text.isEmpty()) {
                return errorResult("未能识别出语音内容（音频过短、无人声或格式不支持）");
            }
            if (text.length() > MAX_TEXT_CHARS) {
                text = text.substring(0, MAX_TEXT_CHARS);
            }
            Log.i(TAG, "音频识别完成: " + file.getName() + " 模型=" + (rr != null ? rr.modelName : "?")
                    + " 字数=" + text.length());
            return storeChunks(chunkText(text, docTitle, category, filePath), docTitle, filePath);
        } catch (Exception e) {
            Log.e(TAG, "importAudioFile 失败: " + e.getMessage(), e);
            return errorResult("音频识别或导入失败: " + e.getMessage());
        }
    }

    /** 用户是否在"功能专用模型"中显式选择了本地 SenseVoice（→ 识别本地优先），与 VoiceInputTool.isLocalAsrSelected 一致 */
    private boolean isLocalAsrSelected() {
        try {
            return SpeechManager.LOCAL_ASR_ID.equals(
                    com.oilquiz.app.ai.model.OnlineModelManager.getInstance(context)
                            .getFeatureModelId(com.oilquiz.app.ai.model.OnlineModelManager.FEATURE_ASR));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 文本/Office/PDF/HTML/图片：复用 FileContentExtractor（图片走 OCR）→ 切块入库。
     */
    private JSONObject importTextOrImageFile(File file, String category, String docTitle, String filePath) {
        try {
            FileContentExtractor extractor = new FileContentExtractor(context);
            String text = extractor.extractContent(Uri.fromFile(file)).get(60, TimeUnit.SECONDS);
            if (text == null || text.startsWith("不支持的文件类型")
                    || text.startsWith("文件解析失败") || text.startsWith("无法确定文件类型")
                    || text.startsWith("OCR识别失败")) {
                return errorResult(text == null ? "解析结果为空" : text);
            }
            if (text.length() > MAX_TEXT_CHARS) {
                text = text.substring(0, MAX_TEXT_CHARS);
                Log.w(TAG, "importDocument 文本超长已截断: " + file.getName());
            }
            return storeChunks(chunkText(text, docTitle, category, filePath), docTitle, filePath);
        } catch (Exception e) {
            Log.e(TAG, "importTextOrImageFile 失败: " + e.getMessage(), e);
            return errorResult("文档解析或导入失败: " + e.getMessage());
        }
    }

    /** 切块结果统一入库（事务），返回结果 JSON */
    private JSONObject storeChunks(List<KnowledgeChunk> chunks, String docTitle, String filePath) {
        JSONObject result = new JSONObject();
        int added = 0;
        long now = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDb();
        db.beginTransaction();
        try {
            for (KnowledgeChunk chunk : chunks) {
                long id = db.insertOrThrow("kb_chunks", null, chunkValues(chunk, now));
                insertFts(db, id, chunk);
                added++;
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        try {
            result.put("success", true);
            result.put("added", added);
            result.put("title", docTitle);
            result.put("source", filePath);
            if (chunks.isEmpty()) {
                result.put("message", "未提取到可入库的文本内容（图片多为OCR未识别、音频多为无语音或过短）");
            }
        } catch (JSONException ignored) {
            // 正常字符串不会触发
        }
        return result;
    }

    /** 构造失败结果 JSON（内部处理 JSONException） */
    private static JSONObject errorResult(String message) {
        JSONObject result = new JSONObject();
        try {
            result.put("success", false);
            result.put("message", message);
        } catch (JSONException ignored) {
            // JSONObject.put 仅对非法值抛异常，message 为普通字符串不会触发
        }
        return result;
    }

    // ==================== 文本切块（与 Python 预处理脚本逻辑对齐） ====================

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^#{1,6}\\s+.*$");
    private static final Pattern CJK_WORD_PATTERN = Pattern.compile("[\\u4e00-\\u9fff]{2,}");
    private static final Pattern EN_WORD_PATTERN = Pattern.compile("[a-zA-Z][a-zA-Z0-9]{1,}");

    /**
     * 把整篇文本按 Markdown 标题层级切块：
     * 标题路径（用 " - " 连接）作为块标题；无标题时用文档名；正文按空行分段，超长再硬切。
     */
    private static List<KnowledgeChunk> chunkText(String text, String docTitle, String category, String source) {
        List<KnowledgeChunk> chunks = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return chunks;
        }
        List<String> headings = new ArrayList<>();
        List<Integer> levels = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();

        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                buffer.append('\n');
                continue;
            }
            if (MARKDOWN_HEADING.matcher(line).matches()) {
                int level = 0;
                while (level < line.length() && line.charAt(level) == '#') {
                    level++;
                }
                String headingText = line.substring(level).replaceAll("#+\\s*$", "").trim();
                if (headingText.isEmpty()) {
                    buffer.append(line).append('\n');
                    continue;
                }
                flushChunk(chunks, headings, buffer, docTitle, category, source);
                while (!levels.isEmpty() && levels.get(levels.size() - 1) >= level) {
                    levels.remove(levels.size() - 1);
                    headings.remove(headings.size() - 1);
                }
                levels.add(level);
                headings.add(headingText);
            } else {
                buffer.append(line).append('\n');
            }
        }
        flushChunk(chunks, headings, buffer, docTitle, category, source);
        return chunks;
    }

    private static void flushChunk(List<KnowledgeChunk> chunks, List<String> headings,
                                   StringBuilder buffer, String docTitle, String category, String source) {
        String body = buffer.toString().trim();
        buffer.setLength(0);
        if (body.isEmpty()) {
            return;
        }
        String title = headings.isEmpty() ? docTitle : String.join(" - ", headings);
        for (String seg : splitLongText(body, 800)) {
            chunks.add(new KnowledgeChunk(title, category, extractKeywords(title), seg, source));
        }
    }

    /** 超长块按空行拆分，段落仍超长则按句号/换行硬切（对齐 Python 脚本的 split_long_text） */
    private static List<String> splitLongText(String body, int chunkSize) {
        List<String> segments = new ArrayList<>();
        if (body.length() <= chunkSize) {
            segments.add(body);
            return segments;
        }
        String[] paras = body.split("\\n\\s*\\n");
        for (String para : paras) {
            para = para.trim();
            if (para.isEmpty()) {
                continue;
            }
            while (para.length() > chunkSize) {
                int cut = para.lastIndexOf('。', chunkSize);
                if (cut < chunkSize / 3) {
                    cut = para.lastIndexOf('\n', chunkSize);
                }
                if (cut < chunkSize / 3) {
                    cut = chunkSize;
                }
                segments.add(para.substring(0, cut).trim());
                para = para.substring(cut).trim();
            }
            if (!para.isEmpty()) {
                segments.add(para);
            }
        }
        return segments;
    }

    /** 从标题提取关键词：连续中文词（>=2字）+ 英文词（对齐 Python 脚本 extract_keywords） */
    private static String extractKeywords(String title) {
        if (title == null || title.isEmpty()) {
            return "";
        }
        java.util.LinkedHashSet<String> words = new java.util.LinkedHashSet<>();
        Matcher m1 = CJK_WORD_PATTERN.matcher(title);
        while (m1.find()) {
            words.add(m1.group());
        }
        Matcher m2 = EN_WORD_PATTERN.matcher(title);
        while (m2.find()) {
            words.add(m2.group().toLowerCase(Locale.ROOT));
        }
        return String.join(",", words);
    }

    /**
     * 按 id 删除知识块。
     */
    public synchronized boolean deleteById(long id) {
        if (id <= 0) {
            return false;
        }
        SQLiteDatabase db = getWritableDb();
        db.beginTransaction();
        try {
            db.delete("kb_chunks_fts", "rowid = ?", new String[]{String.valueOf(id)});
            int rows = db.delete("kb_chunks", "id = ?", new String[]{String.valueOf(id)});
            db.setTransactionSuccessful();
            return rows > 0;
        } catch (Exception e) {
            Log.e(TAG, "deleteById 失败: " + e.getMessage(), e);
            return false;
        } finally {
            db.endTransaction();
        }
    }

    /**
     * 按标题（精确匹配）删除知识块。
     *
     * @return 删除的条数
     */
    public synchronized int deleteByTitle(String title) {
        if (title == null || title.trim().isEmpty()) {
            return 0;
        }
        SQLiteDatabase db = getWritableDb();
        db.beginTransaction();
        int deleted = 0;
        Cursor cursor = null;
        try {
            cursor = db.query("kb_chunks", new String[]{"id"}, "title = ?",
                    new String[]{title.trim()}, null, null, null);
            List<Long> ids = new ArrayList<>();
            while (cursor.moveToNext()) {
                ids.add(cursor.getLong(0));
            }
            cursor.close();
            cursor = null;
            for (Long id : ids) {
                db.delete("kb_chunks_fts", "rowid = ?", new String[]{String.valueOf(id)});
                db.delete("kb_chunks", "id = ?", new String[]{String.valueOf(id)});
                deleted++;
            }
            db.setTransactionSuccessful();
        } catch (Exception e) {
            Log.e(TAG, "deleteByTitle 失败: " + e.getMessage(), e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
            db.endTransaction();
        }
        return deleted;
    }

    /**
     * 清空知识库。
     *
     * @return 清空的条数
     */
    public synchronized int clear() {
        SQLiteDatabase db = getWritableDb();
        db.beginTransaction();
        try {
            int rows = db.delete("kb_chunks", null, null);
            db.delete("kb_chunks_fts", null, null);
            db.setTransactionSuccessful();
            return rows;
        } catch (Exception e) {
            Log.e(TAG, "clear 失败: " + e.getMessage(), e);
            return 0;
        } finally {
            db.endTransaction();
        }
    }

    // ==================== 统计 ====================

    /**
     * 知识库统计信息。
     *
     * @return {"total":N,"categories":{category:count,...},"last_updated":ts}
     */
    public synchronized JSONObject stats() {
        JSONObject result = new JSONObject();
        Cursor cursor = null;
        try {
            SQLiteDatabase db = getReadableDb();
            int total = 0;
            long lastUpdated = 0;
            JSONObject categories = new JSONObject();

            cursor = db.rawQuery("SELECT COUNT(*) FROM kb_chunks", null);
            if (cursor.moveToFirst()) {
                total = cursor.getInt(0);
            }
            cursor.close();

            cursor = db.rawQuery("SELECT MAX(updated_at) FROM kb_chunks", null);
            if (cursor.moveToFirst()) {
                lastUpdated = cursor.getLong(0);
            }
            cursor.close();

            cursor = db.rawQuery("SELECT category, COUNT(*) FROM kb_chunks GROUP BY category ORDER BY COUNT(*) DESC", null);
            while (cursor.moveToNext()) {
                categories.put(cursor.getString(0), cursor.getInt(1));
            }
            cursor.close();
            cursor = null;

            result.put("total", total);
            result.put("categories", categories);
            result.put("last_updated", lastUpdated);
        } catch (Exception e) {
            Log.e(TAG, "stats 失败: " + e.getMessage(), e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return result;
    }

    // ==================== 内部工具方法 ====================

    private static android.content.ContentValues chunkValues(KnowledgeChunk chunk, long now) {
        android.content.ContentValues values = new android.content.ContentValues();
        values.put("title", chunk.title);
        values.put("category", chunk.category);
        values.put("keywords", chunk.keywords);
        values.put("content", chunk.content);
        values.put("source", chunk.source);
        values.put("created_at", now);
        values.put("updated_at", now);
        return values;
    }

    private static void insertFts(SQLiteDatabase db, long id, KnowledgeChunk chunk) {
        android.content.ContentValues ftsValues = new android.content.ContentValues();
        // 注意：FTS5 表显式指定 rowid（不可用 docid 作为列名，实测多版本 SQLite 均不识别）
        ftsValues.put("rowid", id);
        ftsValues.put("title", cjkSpaceOut(chunk.title));
        ftsValues.put("category", cjkSpaceOut(chunk.category));
        ftsValues.put("keywords", cjkSpaceOut(chunk.keywords));
        // search_text：中文逐字空格化后的正文，保证中文关键词可命中
        ftsValues.put("search_text", cjkSpaceOut(chunk.content));
        ftsValues.put("content", chunk.content);
        db.insertOrThrow("kb_chunks_fts", null, ftsValues);
    }

    /**
     * 把字符串中的中文字符逐字空格化（如 "原子结构" -> "原 子 结 构 "）。
     * unicode61 以空白切分 token，空格化后每个汉字成为独立 token，中文检索才能命中。
     * 非中文（ASCII 字母数字、标点、空白）保持原样。
     */
    private static String cjkSpaceOut(String s) {
        if (s == null || s.isEmpty()) {
            return s == null ? "" : s;
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isCjk(c)) {
                sb.append(c).append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isCjk(char c) {
        // CJK 统一表意文字及其扩展区
        return (c >= '\u3400' && c <= '\u4DBF')
                || (c >= '\u4E00' && c <= '\u9FFF')
                || (c >= '\uF900' && c <= '\uFAFF');
    }

    private static KnowledgeChunk fromJson(JSONObject obj) {
        try {
            String title = obj.optString("title", "");
            String category = obj.optString("category", "general");
            String keywords = obj.optString("keywords", "");
            String content = obj.optString("content", "");
            String source = obj.optString("source", "");
            return new KnowledgeChunk(title, category, keywords, content, source);
        } catch (Exception e) {
            return null;
        }
    }

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-z0-9]+|[\\u4e00-\\u9fff]+");

    /**
     * 把用户原始查询构造成安全的 FTS5 MATCH 表达式：
     * <ul>
     *   <li>只保留英文/数字词与中文连续段，其余字符（FTS5 特殊字符 : ( ) " * 等）一律剥离；</li>
     *   <li>英文/数字词原样 + "*" 前缀匹配；</li>
     *   <li>中文连续段按字拆开，用引号包裹并用 AND 组合（unicode61 对 CJK 逐字建 token，AND 组合即
     *       "包含全部关键字" 语义）；</li>
     *   <li>所有词之间用 AND 连接。</li>
     * </ul>
     */
    private static String buildMatchQuery(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return null;
        }
        List<String> tokens = new ArrayList<>();
        Matcher m = TOKEN_PATTERN.matcher(s);
        while (m.find()) {
            String t = m.group();
            if (t.matches("[a-z0-9]+")) {
                tokens.add(t + "*");
            } else {
                for (int i = 0; i < t.length(); i++) {
                    tokens.add("\"" + t.charAt(i) + "\"");
                }
            }
        }
        if (tokens.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) {
                sb.append(" AND ");
            }
            sb.append(tokens.get(i));
        }
        return sb.toString();
    }
}
