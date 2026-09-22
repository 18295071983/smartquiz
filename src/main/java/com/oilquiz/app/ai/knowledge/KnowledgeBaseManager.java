package com.oilquiz.app.ai.knowledge;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.util.Log;

import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.model.ProviderConfigManager;
import com.oilquiz.app.ai.service.OnlineInferenceService;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识库管理器：独立 SQLite 数据库 + 归一化文本列包含式检索 + 相关度排序 +（可选）在线语义重排。
 *
 * <h3>检索实现（v3 起不再依赖 FTS 虚拟表）</h3>
 * 历史包袱：v1 用 FTS5、v2 改 FTS4，都在真机上出过问题——
 * <ul>
 *   <li>部分设备内置 SQLite 未编译 FTS5（{@code no such module: fts5}）；</li>
 *   <li>FTS4 的中文逐字 token 方案依赖 unicode61 分词器（部分 ROM 未编译）；</li>
 *   <li>FTS3/4 的布尔查询语法随编译开关变化：未启用 {@code SQLITE_ENABLE_FTS3_PARENTHESIS}
 *       时 {@code MATCH '"原" AND "子"'} 里的 AND 会被当成普通词，检索<b>永远返回空</b>。</li>
 * </ul>
 * 因此 v3 改为**零扩展依赖**的方案：{@code kb_chunks} 普通表新增归一化列 {@code search_text}
 * （标题+分类+关键词+正文，小写），检索时按查询词逐 token 拼 {@code LIKE '%token%'} 并 AND 组合，
 * 语义与原来的"包含全部关键词"一致，且在任何 SQLite 编译配置下都可用。
 * 相关度用 Java 侧打分（标题/关键词命中权重高），比原来仅按更新时间排序更准。
 * 召回规模是个人知识库量级（数千条以内），全表 LIKE 扫描的开销可忽略。
 *
 * <p>设计要点：
 * <ul>
 *   <li>使用独立的 knowledge_base.db，不占用/不影响主数据库（smartquiz_database），零迁移风险；</li>
 *   <li>中文检索：查询里的连续中文段按字拆开，逐字 AND（"包含全部关键字"语义）；英文/数字按词做子串匹配；</li>
 *   <li>查询串只做小写归一，不做 SQL 拼接，全部走参数化绑定（token 白名单来自正则，无注入面）；</li>
 *   <li>为将来向量语义检索（RAG）预留 keywords 字段与扩展位（如 embedding 列），无需重构表结构。</li>
 * </ul>
 */
public class KnowledgeBaseManager {
    private static final String TAG = "KnowledgeBaseManager";
    private static final String DB_NAME = "knowledge_base.db";
    /**
     * v2：FTS5 在部分设备未编译，改用 FTS4 并重建索引。
     * v3：彻底移除 FTS 虚拟表依赖（FTS4 的中文 token 方案与布尔查询语法在真机上仍不可靠），
     *     改普通表 + 归一化 search_text 列做包含式检索；升级时补列并回填。
     */
    private static final int DB_VERSION = 3;

    /** 查询时返回的默认结果条数上限 */
    private static final int DEFAULT_TOP_K = 5;
    /** 结果条数上限（防止一次拉取过多） */
    private static final int MAX_TOP_K = 20;
    /** 单次检索参与打分的候选上限（SQL 先按更新时间取最近 N 条候选，再在 Java 侧打分排序） */
    private static final int MAX_CANDIDATES = 300;
    /** 查询 token 数量上限（防止超长句子拼出病态查询） */
    private static final int MAX_QUERY_TOKENS = 12;
    /** 无匹配位置时摘要截取长度 */
    private static final int SNIPPET_FALLBACK_CHARS = 120;
    /** 摘要命中点前后保留的字符数 */
    private static final int SNIPPET_BEFORE = 40;
    private static final int SNIPPET_AFTER = 120;

    // ===== 在线语义重排（embedding 余弦重排 + rerank 精排）=====
    /** 语义重排候选上限（取召回结果的前 N 条做向量重排，控制在线调用次数与延迟） */
    private static final int SEMANTIC_CANDIDATES = 8;
    /** rerank 精排条数（对 embedding 重排后的前 N 条精排） */
    private static final int RERANK_TOP = 5;
    /** embedding 重排整体超时（含 query + 全部候选向量化） */
    private static final long EMBEDDING_TIMEOUT_MS = 20000;
    /** rerank 精排超时 */
    private static final long RERANK_TIMEOUT_MS = 10000;
    /** 向量化内容截断长度（防超长文档撑爆 token 限制） */
    private static final int EMBED_CONTENT_MAX = 300;

    private static volatile KnowledgeBaseManager instance;
    private final Context context;
    private volatile KnowledgeDbHelper dbHelper;
    /** 最近一次失败的原始原因（供工具层向用户/模型如实回报，避免"检索失败"被当成"没找到"） */
    private volatile String lastError;

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
            Log.i(TAG, "onUpgrade knowledge_base.db " + oldVersion + " -> " + newVersion);
            // 幂等建表：老库可能缺列，先保证表结构存在再补列
            try {
                createTables(db);
            } catch (Exception e) {
                Log.w(TAG, "onUpgrade 建表失败（继续尝试补列）: " + e.getMessage());
            }
            if (oldVersion < 3) {
                // v2→v3：新增归一化检索列并回填；不再使用 FTS 虚拟表（历史表在 open 后按需清理）
                try {
                    db.execSQL("ALTER TABLE kb_chunks ADD COLUMN search_text TEXT NOT NULL DEFAULT ''");
                } catch (Exception e) {
                    // 列已存在（重复升级/半途失败）时忽略
                    Log.i(TAG, "search_text 列已存在或添加失败: " + e.getMessage());
                }
                backfillSearchText(db);
            }
        }

        private void createTables(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS kb_chunks ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "title TEXT NOT NULL,"
                    + "category TEXT NOT NULL DEFAULT 'general',"
                    + "keywords TEXT NOT NULL DEFAULT '',"
                    + "content TEXT NOT NULL,"
                    + "source TEXT NOT NULL DEFAULT '',"
                    + "search_text TEXT NOT NULL DEFAULT '',"
                    + "created_at INTEGER NOT NULL,"
                    + "updated_at INTEGER NOT NULL"
                    + ")");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_kb_chunks_category ON kb_chunks(category)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_kb_chunks_title ON kb_chunks(title)");
        }
    }

    /** 从旧数据回填归一化检索列（升级后调用；单条 UPDATE，失败只记录日志不抛出） */
    private static void backfillSearchText(SQLiteDatabase db) {
        try {
            db.execSQL("UPDATE kb_chunks SET search_text = lower("
                    + "coalesce(title,'') || ' ' || coalesce(category,'') || ' ' || "
                    + "coalesce(keywords,'') || ' ' || coalesce(content,'')) "
                    + "WHERE search_text IS NULL OR search_text = ''");
            Log.i(TAG, "search_text 回填完成");
        } catch (Exception e) {
            Log.w(TAG, "search_text 回填失败: " + e.getMessage());
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
        SQLiteDatabase db = helper.getReadableDatabase();
        dropLegacyFtsIfPresent(db);
        return db;
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
        SQLiteDatabase db = helper.getWritableDatabase();
        dropLegacyFtsIfPresent(db);
        return db;
    }

    /**
     * 清理历史版本的 FTS 虚拟表（v1=FTS5 / v2=FTS4）。v3 起检索不再依赖 FTS。
     * 只做一次、失败不影响任何功能：模块缺失时 DROP 会报 "no such module"，属预期情况。
     */
    private volatile boolean legacyFtsChecked = false;

    private void dropLegacyFtsIfPresent(SQLiteDatabase db) {
        if (legacyFtsChecked || db == null) {
            return;
        }
        legacyFtsChecked = true;
        Cursor cursor = null;
        try {
            cursor = db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='kb_chunks_fts'", null);
            boolean exists = cursor.moveToFirst();
            cursor.close();
            cursor = null;
            if (exists) {
                db.execSQL("DROP TABLE IF EXISTS kb_chunks_fts");
                Log.i(TAG, "已清理历史 FTS 虚拟表 kb_chunks_fts");
            }
        } catch (Exception e) {
            Log.w(TAG, "清理历史 FTS 表失败（忽略）: " + e.getMessage());
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /** 最近一次操作的失败原因；null 表示无错误 */
    public String getLastError() {
        return lastError;
    }

    // ==================== 检索 ====================

    /**
     * 全文检索知识库（默认不做语义重排，防止无关在线 embedding/rerank API 消费）。
     *
     * @param query    检索关键词（中文/英文/数字混合均可）
     * @param category 分类过滤，null 或空表示不过滤
     * @param topK     返回条数上限，<=0 时使用默认值
     * @return JSON 数组，元素含 id/title/category/keywords/content/source/snippet/score
     */
    public JSONArray search(String query, String category, int topK) {
        return search(query, category, topK, false);
    }

    /**
     * 全文检索知识库（可显式开启语义重排）。
     *
     * @param semanticRerank true=在线可用时对召回结果做 embedding 余弦重排 +（可选）rerank 精排；
     *                       默认 false=仅本地关键词检索，不消费任何在线 API。
     */
    public JSONArray search(String query, String category, int topK, boolean semanticRerank) {
        int limit = (topK <= 0) ? DEFAULT_TOP_K : Math.min(topK, MAX_TOP_K);
        JSONArray results = searchInternal(query, category, limit);
        boolean hasQuery = query != null && !query.trim().isEmpty();
        // 语义重排含网络调用（最长 20s+10s），放在锁外执行，避免长时间阻塞其他知识库操作
        if (hasQuery && semanticRerank) {
            results = applySemanticRerank(results, query, limit);
        }
        return results;
    }

    /** 关键词检索主体（同步、持锁）：LIKE 召回 → Java 侧打分为相关度排序 → 取前 limit 条 */
    private synchronized JSONArray searchInternal(String query, String category, int limit) {
        lastError = null;
        JSONArray results = new JSONArray();
        Cursor cursor = null;
        boolean hasQuery = query != null && !query.trim().isEmpty();
        boolean hasCategory = category != null && !category.trim().isEmpty();
        List<String> tokens = hasQuery ? tokenizeQuery(query) : new ArrayList<String>();
        if (hasQuery && tokens.isEmpty()) {
            // 查询里没有任何可检索字符（纯标点/空白）：退化为按分类/时间列出
            hasQuery = false;
        }
        try {
            SQLiteDatabase db = getReadableDb();

            StringBuilder where = new StringBuilder();
            List<String> args = new ArrayList<>();
            for (String token : tokens) {
                if (where.length() > 0) {
                    where.append(" AND ");
                }
                where.append("search_text LIKE ?");
                args.add("%" + token + "%");
            }
            if (hasCategory) {
                if (where.length() > 0) {
                    where.append(" AND ");
                }
                where.append("category = ?");
                args.add(category.trim());
            }
            // 有查询时多取候选用于打分排序；无查询时直接取 limit 条
            int fetchLimit = hasQuery
                    ? Math.min(MAX_CANDIDATES, Math.max(limit * 10, 50))
                    : limit;
            String sql = "SELECT id, title, category, keywords, source, updated_at, content FROM kb_chunks"
                    + (where.length() > 0 ? " WHERE " + where : "")
                    + " ORDER BY updated_at DESC LIMIT ?";
            args.add(String.valueOf(fetchLimit));

            List<JSONObject> items = new ArrayList<>();
            List<Integer> scores = new ArrayList<>();
            cursor = db.rawQuery(sql, args.toArray(new String[0]));
            while (cursor.moveToNext()) {
                JSONObject item = new JSONObject();
                String title = cursor.getString(1) == null ? "" : cursor.getString(1);
                String cat = cursor.getString(2) == null ? "" : cursor.getString(2);
                String keywords = cursor.getString(3) == null ? "" : cursor.getString(3);
                String content = cursor.getString(6) == null ? "" : cursor.getString(6);
                item.put("id", cursor.getLong(0));
                item.put("title", title);
                item.put("category", cat);
                item.put("keywords", keywords);
                item.put("source", cursor.getString(4) == null ? "" : cursor.getString(4));
                item.put("updated_at", cursor.getLong(5));
                item.put("content", content);
                item.put("snippet", buildSnippet(content, tokens));
                int score = scoreOf(tokens, title, cat, keywords, content);
                item.put("score", score);
                items.add(item);
                scores.add(score);
            }

            // 相关度降序 + 同分按更新时间降序（Java 侧稳定排序，替代原来仅按时间排序）
            if (hasQuery && items.size() > 1) {
                Integer[] order = new Integer[items.size()];
                for (int i = 0; i < order.length; i++) {
                    order[i] = i;
                }
                Arrays.sort(order, (a, b) -> {
                    int cmp = Integer.compare(scores.get(b), scores.get(a));
                    if (cmp != 0) {
                        return cmp;
                    }
                    long ta = items.get(a).optLong("updated_at", 0);
                    long tb = items.get(b).optLong("updated_at", 0);
                    return Long.compare(tb, ta);
                });
                for (int i = 0; i < order.length && i < limit; i++) {
                    results.put(items.get(order[i]));
                }
            } else {
                for (int i = 0; i < items.size() && i < limit; i++) {
                    results.put(items.get(i));
                }
            }
        } catch (Exception e) {
            lastError = e.getMessage();
            Log.e(TAG, "search 失败: " + e.getMessage(), e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return results;
    }

    /**
     * 相关度打分：标题/分类/关键词命中权重 3，正文命中权重 1。
     * 每个 token 只计一次（SQL 已保证每个 token 至少在某处命中，因此得分恒 > 0）。
     */
    private static int scoreOf(List<String> tokens, String title, String category,
                               String keywords, String content) {
        if (tokens == null || tokens.isEmpty()) {
            return 0;
        }
        String head = ((title == null ? "" : title) + " " + (category == null ? "" : category)
                + " " + (keywords == null ? "" : keywords)).toLowerCase(Locale.ROOT);
        String body = content == null ? "" : content.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String token : tokens) {
            if (head.contains(token)) {
                score += 3;
            } else if (body.contains(token)) {
                score += 1;
            }
        }
        return score;
    }

    /** 查询分词：短横线/标点剥离，英文数字按词、中文按字，去重并限制数量 */
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-z0-9]+|[\\u4e00-\\u9fff]+");

    static List<String> tokenizeQuery(String raw) {
        List<String> tokens = new ArrayList<>();
        if (raw == null) {
            return tokens;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return tokens;
        }
        Set<String> unique = new LinkedHashSet<>();
        Matcher m = TOKEN_PATTERN.matcher(s);
        while (m.find()) {
            String t = m.group();
            if (t.matches("[a-z0-9]+")) {
                unique.add(t);
            } else {
                // 中文连续段逐字切分：与"包含全部关键字"语义一致，且不依赖分词器
                for (int i = 0; i < t.length(); i++) {
                    unique.add(String.valueOf(t.charAt(i)));
                }
            }
            if (unique.size() >= MAX_QUERY_TOKENS) {
                break;
            }
        }
        tokens.addAll(unique);
        if (tokens.size() > MAX_QUERY_TOKENS) {
            return new ArrayList<>(tokens.subList(0, MAX_QUERY_TOKENS));
        }
        return tokens;
    }

    /** 生成命中片段：以第一个命中位置为中心截取窗口，并给窗口内的命中词加 [ ] 标记 */
    private static String buildSnippet(String content, List<String> tokens) {
        if (content == null) {
            return "";
        }
        String text = content.trim();
        if (text.isEmpty()) {
            return "";
        }
        if (tokens == null || tokens.isEmpty()) {
            return text.length() > SNIPPET_FALLBACK_CHARS
                    ? text.substring(0, SNIPPET_FALLBACK_CHARS) + "…" : text;
        }
        // 归一化后长度必须与原串一致才能用下标对齐（个别语言大小写转换会改变长度，此时退回原串）
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.length() != text.length()) {
            lower = text;
        }
        int first = -1;
        for (String token : tokens) {
            int idx = lower.indexOf(token);
            if (idx >= 0 && (first < 0 || idx < first)) {
                first = idx;
            }
        }
        if (first < 0) {
            return text.length() > SNIPPET_FALLBACK_CHARS
                    ? text.substring(0, SNIPPET_FALLBACK_CHARS) + "…" : text;
        }
        int start = Math.max(0, first - SNIPPET_BEFORE);
        int end = Math.min(text.length(), first + SNIPPET_AFTER);
        String window = text.substring(start, end);
        return (start > 0 ? "…" : "") + highlight(window, tokens) + (end < text.length() ? "…" : "");
    }

    /** 给窗口内的命中词加中括号（长词优先，避免短词抢先匹配） */
    private static String highlight(String window, List<String> tokens) {
        List<String> sorted = new ArrayList<>(tokens);
        sorted.sort((a, b) -> Integer.compare(b.length(), a.length()));
        StringBuilder sb = new StringBuilder(window.length() + 16);
        int i = 0;
        while (i < window.length()) {
            String matched = null;
            for (String token : sorted) {
                if (!token.isEmpty() && window.regionMatches(true, i, token, 0, token.length())) {
                    matched = token;
                    break;
                }
            }
            if (matched != null) {
                sb.append('[').append(window, i, i + matched.length()).append(']');
                i += matched.length();
            } else {
                sb.append(window.charAt(i));
                i++;
            }
        }
        return sb.toString();
    }

    // ==================== 在线语义重排 ====================

    /**
     * 关键词召回后做语义重排：
     * 1. 在线配置可用 + 配置表声明 embedding 模型 → query 与候选内容向量化，余弦相似度重排；
     * 2. 配置表声明 rerank 模型 → 对重排后前若干条 rerank 精排；
     * 3. 任一环节未配置/失败/超时 → 返回原序（不因重排失败返回空或报错）。
     */
    private JSONArray applySemanticRerank(JSONArray bm25Results, String query, int limit) {
        if (bm25Results == null || bm25Results.length() < 2 || limit <= 1) return bm25Results;
        try {
            OnlineModelManager omm = OnlineModelManager.getInstance(context);
            if (omm == null) return bm25Results;
            OnlineModelManager.OnlineModelConfig cfg = omm.getActiveModel();
            if (cfg == null || isBlank(cfg.apiUrl) || isBlank(cfg.apiKey)) return bm25Results;
            ProviderConfigManager pcm = ProviderConfigManager.get();
            String embModel = pcm.getServiceModel(cfg.apiUrl, "embedding");
            if (isBlank(embModel)) return bm25Results; // 配置表未声明 embedding 模型 → 不语义重排
            OnlineInferenceService ois = OnlineInferenceService.getInstance(context);
            if (ois == null) return bm25Results;

            int n = Math.min(bm25Results.length(), SEMANTIC_CANDIDATES);
            if (n < 2) return bm25Results;

            // 1) query 向量（超时兜底，失败降级）
            List<Float> qVec = ois.generateEmbeddingAsync(query, cfg)
                    .get(EMBEDDING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (qVec == null || qVec.isEmpty()) return bm25Results;

            // 2) 候选内容并行向量化 + 余弦相似度
            List<CompletableFuture<List<Float>>> futures = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                JSONObject item = bm25Results.optJSONObject(i);
                String content = item != null ? item.optString("content", "") : "";
                if (content.length() > EMBED_CONTENT_MAX) content = content.substring(0, EMBED_CONTENT_MAX);
                futures.add(ois.generateEmbeddingAsync(content, cfg));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]))
                    .get(EMBEDDING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            final List<Float> sims = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                List<Float> cVec = futures.get(i).getNow(null);
                sims.add((float) cosineSimilarity(qVec, cVec));
            }

            // 3) 按相似度降序稳定排序（同分保持原序）
            List<Integer> order = new ArrayList<>(n);
            for (int i = 0; i < n; i++) order.add(i);
            order.sort((a, b) -> Double.compare(sims.get(b), sims.get(a)));
            JSONArray reranked = new JSONArray();
            for (int idx : order) {
                JSONObject item = bm25Results.optJSONObject(idx);
                if (item != null) reranked.put(item);
            }
            for (int i = n; i < bm25Results.length(); i++) {
                JSONObject item = bm25Results.optJSONObject(i);
                if (item != null) reranked.put(item);
            }

            // 4) rerank 精排（配置表声明 rerank 模型时）
            String rerankModel = pcm.getServiceModel(cfg.apiUrl, "rerank");
            if (!isBlank(rerankModel) && reranked.length() >= 2) {
                int rt = Math.min(RERANK_TOP, reranked.length());
                List<String> docs = new ArrayList<>();
                List<JSONObject> originals = new ArrayList<>();
                for (int i = 0; i < rt; i++) {
                    JSONObject o = reranked.optJSONObject(i);
                    if (o == null) continue;
                    originals.add(o);
                    String content = o.optString("content", "");
                    if (content.length() > EMBED_CONTENT_MAX) content = content.substring(0, EMBED_CONTENT_MAX);
                    docs.add(content);
                }
                if (docs.size() >= 2) {
                    String resp = ois.rerankAsync(query, docs, cfg)
                            .get(RERANK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    List<Integer> newOrder = parseRerankOrder(resp, docs.size());
                    if (newOrder != null) {
                        JSONArray reordered = new JSONArray();
                        for (int idx : newOrder) {
                            if (idx >= 0 && idx < originals.size()) reordered.put(originals.get(idx));
                        }
                        for (int i = rt; i < reranked.length(); i++) {
                            JSONObject o = reranked.optJSONObject(i);
                            if (o != null) reordered.put(o);
                        }
                        if (reordered.length() == reranked.length()) reranked = reordered;
                    }
                }
            }
            return reranked;
        } catch (Exception e) {
            Log.w(TAG, "语义重排失败，降级关键词排序: " + e.getMessage());
            return bm25Results;
        }
    }

    /** 余弦相似度；任一向量为空/长度不一致返回 -1（排到最后） */
    private static double cosineSimilarity(List<Float> a, List<Float> b) {
        if (a == null || b == null || a.isEmpty() || a.size() != b.size()) return -1;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.size(); i++) {
            double x = a.get(i), y = b.get(i);
            dot += x * y;
            na += x * x;
            nb += y * y;
        }
        if (na == 0 || nb == 0) return -1;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /**
     * 解析 rerank 响应中的排序下标（OpenAI 兼容 results[] 或百炼 output.results[]）。
     * results 已按相关性降序，逐项取 index 即 docs 的新顺序；数量不符返回 null 不采用。
     */
    private static List<Integer> parseRerankOrder(String resp, int expected) {
        if (resp == null || resp.isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(resp);
            JSONArray arr = null;
            if (root.has("results") && !root.isNull("results")) {
                arr = root.optJSONArray("results");
            } else if (root.has("output") && !root.isNull("output")) {
                JSONObject out = root.optJSONObject("output");
                if (out != null && out.has("results") && !out.isNull("results")) {
                    arr = out.optJSONArray("results");
                }
            }
            if (arr == null || arr.length() != expected) return null;
            List<Integer> order = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null || !o.has("index") || o.isNull("index")) return null;
                order.add(o.optInt("index", -1));
            }
            for (int idx : order) {
                if (idx < 0 || idx >= expected) return null;
            }
            return order;
        } catch (Exception e) {
            Log.w(TAG, "rerank 响应解析失败: " + e.getMessage());
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    // ==================== 增删 ====================

    /**
     * 添加单条知识块。
     *
     * @return 新条目 id；失败返回 -1（失败原因见 {@link #getLastError()}）
     */
    public synchronized long addChunk(String title, String category, String keywords,
                                      String content, String source) {
        lastError = null;
        KnowledgeChunk chunk = new KnowledgeChunk(title, category, keywords, content, source);
        if (chunk.title.isEmpty() && chunk.content.isEmpty()) {
            lastError = "标题与内容均为空";
            Log.w(TAG, "addChunk 被拒绝：标题与内容均为空");
            return -1;
        }
        if (chunk.content.isEmpty()) {
            chunk.content = chunk.title;
        }
        long now = System.currentTimeMillis();
        SQLiteDatabase db;
        try {
            db = getWritableDb();
        } catch (Exception e) {
            lastError = "知识库数据库不可用: " + e.getMessage();
            Log.e(TAG, "addChunk 打开数据库失败: " + e.getMessage(), e);
            return -1;
        }
        db.beginTransaction();
        try {
            long id = db.insertOrThrow("kb_chunks", null, chunkValues(chunk, now));
            db.setTransactionSuccessful();
            return id;
        } catch (Exception e) {
            lastError = e.getMessage();
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
        lastError = null;
        if (items == null) {
            return 0;
        }
        int added = 0;
        long now = System.currentTimeMillis();
        SQLiteDatabase db;
        try {
            db = getWritableDb();
        } catch (Exception e) {
            lastError = "知识库数据库不可用: " + e.getMessage();
            Log.e(TAG, "addBatch 打开数据库失败: " + e.getMessage(), e);
            return 0;
        }
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
                db.insertOrThrow("kb_chunks", null, chunkValues(chunk, now));
                added++;
            }
            db.setTransactionSuccessful();
        } catch (Exception e) {
            lastError = e.getMessage();
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
        lastError = null;
        if (json == null || json.trim().isEmpty()) {
            lastError = "JSON 内容为空";
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
                lastError = "JSON 中缺少 chunks 数组（也不支持顶层对象格式）";
                return 0;
            }
            return addBatch(chunks);
        } catch (JSONException e) {
            lastError = "JSON 解析失败: " + e.getMessage();
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
    public JSONObject importDocument(String filePath, String category, String title) {
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
        try {
            SQLiteDatabase db = getWritableDb();
            db.beginTransaction();
            try {
                for (KnowledgeChunk chunk : chunks) {
                    db.insertOrThrow("kb_chunks", null, chunkValues(chunk, now));
                    added++;
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (Exception e) {
            lastError = e.getMessage();
            Log.e(TAG, "storeChunks 失败: " + e.getMessage(), e);
            return errorResult("写入知识库失败: " + e.getMessage());
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
        lastError = null;
        try {
            SQLiteDatabase db = getWritableDb();
            db.beginTransaction();
            try {
                int rows = db.delete("kb_chunks", "id = ?", new String[]{String.valueOf(id)});
                db.setTransactionSuccessful();
                return rows > 0;
            } finally {
                db.endTransaction();
            }
        } catch (Exception e) {
            lastError = e.getMessage();
            Log.e(TAG, "deleteById 失败: " + e.getMessage(), e);
            return false;
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
        lastError = null;
        SQLiteDatabase db;
        try {
            db = getWritableDb();
        } catch (Exception e) {
            lastError = e.getMessage();
            Log.e(TAG, "deleteByTitle 打开数据库失败: " + e.getMessage(), e);
            return 0;
        }
        db.beginTransaction();
        int deleted = 0;
        try {
            deleted = db.delete("kb_chunks", "title = ?", new String[]{title.trim()});
            db.setTransactionSuccessful();
        } catch (Exception e) {
            lastError = e.getMessage();
            Log.e(TAG, "deleteByTitle 失败: " + e.getMessage(), e);
        } finally {
            db.endTransaction();
        }
        return deleted;
    }

    /**
     * 按分类删除全部条目（2026-09-23：tool_defs 重建用，一次删光避免按 title 遍历
     * 因字段名/返回格式不匹配而漏删导致的重复条目残留）。
     *
     * @return 删除的条数
     */
    public synchronized int deleteByCategory(String category) {
        if (category == null || category.trim().isEmpty()) {
            return 0;
        }
        lastError = null;
        SQLiteDatabase db;
        try {
            db = getWritableDb();
        } catch (Exception e) {
            lastError = e.getMessage();
            Log.e(TAG, "deleteByCategory 打开数据库失败: " + e.getMessage(), e);
            return 0;
        }
        db.beginTransaction();
        int deleted = 0;
        try {
            deleted = db.delete("kb_chunks", "category = ?", new String[]{category.trim()});
            db.setTransactionSuccessful();
        } catch (Exception e) {
            lastError = e.getMessage();
            Log.e(TAG, "deleteByCategory 失败: " + e.getMessage(), e);
        } finally {
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
        lastError = null;
        try {
            SQLiteDatabase db = getWritableDb();
            db.beginTransaction();
            try {
                int rows = db.delete("kb_chunks", null, null);
                db.setTransactionSuccessful();
                return rows;
            } finally {
                db.endTransaction();
            }
        } catch (Exception e) {
            lastError = e.getMessage();
            Log.e(TAG, "clear 失败: " + e.getMessage(), e);
            return 0;
        }
    }

    // ==================== 统计 ====================

    /**
     * 知识库统计信息。
     *
     * @return {"total":N,"categories":{category:count,...},"last_updated":ts,"db_version":N}
     */
    public synchronized JSONObject stats() {
        lastError = null;
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
            result.put("db_version", DB_VERSION);
        } catch (Exception e) {
            lastError = e.getMessage();
            Log.e(TAG, "stats 失败: " + e.getMessage(), e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return result;
    }

    // ==================== 内部工具方法 ====================

    private static ContentValues chunkValues(KnowledgeChunk chunk, long now) {
        ContentValues values = new ContentValues();
        values.put("title", chunk.title);
        values.put("category", chunk.category);
        values.put("keywords", chunk.keywords);
        values.put("content", chunk.content);
        values.put("source", chunk.source);
        values.put("search_text", buildSearchText(chunk));
        values.put("created_at", now);
        values.put("updated_at", now);
        return values;
    }

    /** 归一化检索文本：标题 + 分类 + 关键词 + 正文，统一小写（中文不受影响） */
    static String buildSearchText(KnowledgeChunk chunk) {
        if (chunk == null) {
            return "";
        }
        return ((chunk.title == null ? "" : chunk.title) + " "
                + (chunk.category == null ? "" : chunk.category) + " "
                + (chunk.keywords == null ? "" : chunk.keywords) + " "
                + (chunk.content == null ? "" : chunk.content)).toLowerCase(Locale.ROOT);
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
}
