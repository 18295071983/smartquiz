package com.oilquiz.app.ai.knowledge;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabaseCorruptException;
import android.database.sqlite.SQLiteException;
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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
     * v4（2026-10-28）：search_text 从"原文小写"改为"空格分隔的 token 流"（中文 bigram、英文按词），
     *     查询同口径 + 空格边界整 token 匹配；升级时按新口径重建全表 search_text（数据迁移，不加列）。
     * v5（2026-11-10）：新增 kb_documents 文档层（一篇文档 = 一行，带 doc_hash/chunk_count），
     *     kb_chunks 补 doc_id/chunk_seq 两列做溯源，并补 source / updated_at / doc_id 三个索引。
     *     结构变更在 onUpgrade 里做（ALTER 幂等），**数据回填**放开库后的 ensureDocumentsV5()——同 v4 的理由。
     * v6（2026-11-10）：kb_chunks 补 embedding BLOB，存 float32 本地向量（写入侧算好、查询侧只算 query）。
     *     这是"真语义召回"：向量召回不依赖关键词命中，能捞回同义/口语化问法；关键词召回继续保留，
     *     两路用 RRF 融合。旧库该列为 NULL，由后台 backfillEmbeddings() 分批补算。
     */
    private static final int DB_VERSION = 6;

    /** 查询时返回的默认结果条数上限 */
    private static final int DEFAULT_TOP_K = 5;
    /** 结果条数上限（防止一次拉取过多） */
    private static final int MAX_TOP_K = 20;
    /**
     * 有查询时最多扫多少行（上限保护）。
     *
     * <p>v5 起召回从"全 token AND"改成"OR"，候选集会明显变大，再不能像 v4 那样
     * 先 `LIMIT 50` 再打分（那会把真正相关但排在第 51 行的块直接丢掉）。
     * 个人库量级（几千条）下 5000 等于全量扫，这里只是防脏数据/异常库失控。</p>
     */
    private static final int MAX_SCAN_ROWS = 5000;
    /** 查询 token 数量上限（OR 召回后放宽：旧 AND 语义下 12 已经是天花板） */
    private static final int MAX_QUERY_TOKENS = 24;
    /**
     * 覆盖率门槛（2026-11-10）：命中 token 数 / 总 token 数 低于此值不返回。
     *
     * <p>OR 召回必然会带进来"只命中一个泛词"的行，靠这个门槛把它们挡在结果之外；
     * 0.34 的取值含义：3 个 token 的查询至少命中 1 个、6 个 token 至少命中 2 个。</p>
     */
    private static final double MIN_COVERAGE = 0.34;
    /** 检索慢查询告警阈值（记日志用，不改行为） */
    private static final long SLOW_SEARCH_MS = 500;
    /** 无匹配位置时摘要截取长度 */
    private static final int SNIPPET_FALLBACK_CHARS = 120;
    /** 摘要命中点前后保留的字符数 */
    private static final int SNIPPET_BEFORE = 40;
    private static final int SNIPPET_AFTER = 120;

    // ===== 切块（导入文档时）=====
    /**
     * 短于此字数的块不单独成块。
     *
     * <p>Markdown 里"结论句"后面紧跟子标题时，结论句会被 flushChunk 切成只有一句话的独立块，
     * 检索命中它只能拿到没有下文的半句话（证据在下一块）。见 {@link #mergeTinyChunks}。</p>
     */
    private static final int MIN_CHUNK_CHARS = 150;
    /** 归并后单块字数上限：超过就宁可保持独立，避免把一块撑成"两段无关内容" */
    private static final int MAX_MERGED_CHARS = 1000;

    // ===== 在线语义重排（embedding 余弦重排 + rerank 精排）=====
    /** 语义重排候选上限（取召回结果的前 N 条做向量重排，控制在线调用次数与延迟） */
    private static final int SEMANTIC_CANDIDATES = 8;
    /** rerank 精排条数（对 embedding 重排后的前 N 条精排） */
    private static final int RERANK_TOP = 5;
    /** embedding 重排整体超时（含 query + 全部候选向量化）。
     *  v6 起从 20s 收到 4s：单条几百字的向量化正常在 1s 内完成，超过 4s 基本是服务不可用；
     *  这条路径在工具检索的同步链上，等 20s 等于把"服务挂了"转嫁成"主 agent 卡 20 秒"。 */
    private static final long EMBEDDING_TIMEOUT_MS = 4000;
    /** rerank 精排超时 */
    private static final long RERANK_TIMEOUT_MS = 10000;
    /** 向量化内容截断长度（防超长文档撑爆 token 限制） */
    private static final int EMBED_CONTENT_MAX = 300;

    // ===== v6 本地向量召回（RAG）=====
    /** 向量库元数据键：当前向量是用哪个模型算的；模型变了旧向量整批作废重建 */
    private static final String META_EMBEDDING_MODEL = "embedding_model";
    /** 一次回填最多处理多少块（后台分批，不阻塞写入与导入） */
    private static final int EMBED_BACKFILL_BATCH = 24;
    /** 全量回填最多跑多少批（4800 块；失败批会提前退出，不会无限重试） */
    private static final int EMBED_MAX_ROUNDS = 200;
    /** 语义召回的最低余弦（低于它视为无关，不靠向量把噪声灌进结果） */
    private static final double SEMANTIC_MIN_COS = 0.30;
    /** 语义召回候选倍数：limit × N 参与融合，保证向量独有命中能挤进最终榜 */
    private static final int SEMANTIC_RECALL_FACTOR = 3;
    /** RRF 融合常数：score = Σ 1/(K + rank)，越小头部越集中 */
    private static final double RRF_K = 60.0;
    /** 向量路连续失败多少次后停用 */
    private static final int SEMANTIC_FAIL_THRESHOLD = 2;
    /** 停用时长：期间静默走关键词（不报错、不等待超时、不再打 embedding 接口） */
    private static final long SEMANTIC_COOLDOWN_MS = 10 * 60 * 1000L;

    private static volatile KnowledgeBaseManager instance;
    private final Context context;
    private volatile KnowledgeDbHelper dbHelper;
    /** 最近一次失败的原始原因（供工具层向用户/模型如实回报，避免"检索失败"被当成"没找到"） */
    private volatile String lastError;
    /** 向量路连续失败次数（熔断用；成功一次即清零） */
    private volatile int semanticFailStreak;
    /** 向量路停用到什么时刻（毫秒时间戳）；期间静默只用关键词，不报错、不再等超时 */
    private volatile long semanticCooldownUntil;

    /** 单个知识块（内部数据结构） */
    public static class KnowledgeChunk {
        public long id;
        public String title;
        public String category;
        public String keywords;
        public String content;
        public String source;
        public long updatedAt;
        /** 所属文档 id（kb_documents.doc_id）；0 = 手工单条知识，不属于任何文档 */
        public long docId;
        /** 在该文档内的块序号（从 1 开始）；0 = 非文档块 */
        public int chunkSeq;

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
            // v3→v4 的数据重建**不放在 onUpgrade 里**：它跑在 SQLiteOpenHelper 的升级事务中，
            // 逐行 UPDATE 在单测实测会被外层事务吞掉（版本抬到 4、search_text 却没变）。
            // 改为开库后由 ensureSearchTextV4() 在独立事务里重建，见 getReadableDb()/getWritableDb()。
            if (oldVersion < 5) {
                // v4→v5：补文档层两列（表本身由上面的 createTables 建）
                try {
                    db.execSQL("ALTER TABLE kb_chunks ADD COLUMN doc_id INTEGER NOT NULL DEFAULT 0");
                } catch (Exception e) {
                    Log.i(TAG, "doc_id 列已存在或添加失败: " + e.getMessage());
                }
                try {
                    db.execSQL("ALTER TABLE kb_chunks ADD COLUMN chunk_seq INTEGER NOT NULL DEFAULT 0");
                } catch (Exception e) {
                    Log.i(TAG, "chunk_seq 列已存在或添加失败: " + e.getMessage());
                }
                // 数据回填同样不放在这里，见 ensureDocumentsV5()
            }
            if (oldVersion < 6) {
                // v5→v6：补本地向量列。数据（向量本身）不在这里算：
                // 每条都要调在线 embedding，放进升级事务会让开库卡到几秒甚至超时
                try {
                    db.execSQL("ALTER TABLE kb_chunks ADD COLUMN embedding BLOB");
                } catch (Exception e) {
                    Log.i(TAG, "embedding 列已存在或添加失败: " + e.getMessage());
                }
            }
        }

        /**
         * 按 v4 口径重建全表 search_text（bigram）。
         *
         * <p>为什么必须走 Java 逐行：SQL 里没法切 bigram。个人库量级（几千行）一次性事务内完成，
         * 失败只记日志——旧格式数据仍能按单字查询命中，不会因为迁移失败把知识库弄丢。</p>
         */
        private void rebuildSearchTextV4(SQLiteDatabase db) {
            Cursor cursor = null;
            try {
                List<Object[]> rows = new ArrayList<>();
                cursor = db.rawQuery("SELECT id, title, category, keywords, content FROM kb_chunks", null);
                while (cursor.moveToNext()) {
                    KnowledgeChunk chunk = new KnowledgeChunk(
                            safe(cursor.getString(1)), safe(cursor.getString(2)),
                            safe(cursor.getString(3)), safe(cursor.getString(4)), "");
                    // 绑定顺序必须与 SQL 一致：SET search_text = ? WHERE id = ?
                    rows.add(new Object[]{buildSearchText(chunk), cursor.getLong(0)});
                }
                cursor.close();
                cursor = null;
                // 不再自己开事务：onUpgrade 本身跑在 SQLiteOpenHelper 的升级事务里，
                // 嵌套 begin/endTransaction 在部分实现下会静默丢更新（真机/单测都踩过）
                for (Object[] row : rows) {
                    db.execSQL("UPDATE kb_chunks SET search_text = ? WHERE id = ?", row);
                }
                Log.i(TAG, "search_text v4 重建完成: " + rows.size() + " 行");
            } catch (Exception e) {
                Log.w(TAG, "search_text v4 重建失败: " + e.getMessage());
            } finally {
                if (cursor != null) cursor.close();
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
                    + "doc_id INTEGER NOT NULL DEFAULT 0,"
                    + "chunk_seq INTEGER NOT NULL DEFAULT 0,"
                    + "embedding BLOB,"
                    + "created_at INTEGER NOT NULL,"
                    + "updated_at INTEGER NOT NULL"
                    + ")");
            // v5 文档层：一篇导入的文档一行，块通过 kb_chunks.doc_id 关联
            // 为什么单独一张表：去重、整篇替换、溯源（"第几块"）都需要"文档"这个身份，
            // 只靠 source 字符串约定要么每次现算全篇哈希、要么没法判断块序。
            db.execSQL("CREATE TABLE IF NOT EXISTS kb_documents ("
                    + "doc_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "title TEXT NOT NULL DEFAULT '',"
                    + "category TEXT NOT NULL DEFAULT 'general',"
                    + "source TEXT NOT NULL DEFAULT '',"
                    + "doc_hash TEXT NOT NULL DEFAULT '',"
                    + "chunk_count INTEGER NOT NULL DEFAULT 0,"
                    + "created_at INTEGER NOT NULL,"
                    + "updated_at INTEGER NOT NULL"
                    + ")");
            db.execSQL("CREATE TABLE IF NOT EXISTS kb_meta (k TEXT PRIMARY KEY, v TEXT NOT NULL DEFAULT '')");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_kb_chunks_category ON kb_chunks(category)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_kb_chunks_title ON kb_chunks(title)");
            // v5 新增：去重/整篇删按 source 查、list 按 updated_at 排序、溯源自 doc_id
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_kb_chunks_source ON kb_chunks(source)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_kb_chunks_updated ON kb_chunks(updated_at)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_kb_chunks_doc ON kb_chunks(doc_id)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_kb_documents_source ON kb_documents(source)");
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

    /**
     * 仅测试用：关闭当前连接并丢弃单例，让下一次 {@link #getInstance} 重新开库。
     *
     * <p>Robolectric 每个测试方法都会重置 SQLite 环境，而单例里缓存的连接会变成
     * "Illegal connection pointer"——不重建的话，同一测试类的第 2 个用例起就全部失败。</p>
     */
    static synchronized void resetForTesting() {
        if (instance != null) {
            try {
                if (instance.dbHelper != null) instance.dbHelper.close();
            } catch (Throwable ignored) {
            }
            instance = null;
        }
    }

    private SQLiteDatabase getReadableDb() {
        return openWithRecovery(false);
    }

    private SQLiteDatabase getWritableDb() {
        return openWithRecovery(true);
    }

    /**
     * 取库连接：一次损坏自愈 + 开库后迁移。
     *
     * <p>为什么要把这三件事绑在一起：`ensure*` 迁移必须在拿到的这个连接上跑，而损坏恢复又必须
     * 在拿连接失败时介入。分散在调用方每个方法里既容易漏，也容易在恢复后还拿着旧 helper。</p>
     */
    private SQLiteDatabase openWithRecovery(boolean writable) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                SQLiteDatabase db = writable
                        ? ensureHelper().getWritableDatabase()
                        : ensureHelper().getReadableDatabase();
                dropLegacyFtsIfPresent(db);
                ensureSearchTextV4(db);
                ensureDocumentsV5(db);
                return db;
            } catch (SQLiteException e) {
                if (attempt == 0 && isCorruption(e)) {
                    recoverCorruptedDatabase(e);
                    continue;   // 重建后再开一次
                }
                throw e;
            }
        }
        throw new IllegalStateException("知识库数据库不可用（重建后仍打不开）");
    }

    /** 懒建/复用 helper（单例字段可能被损坏恢复清空） */
    private KnowledgeDbHelper ensureHelper() {
        KnowledgeDbHelper helper = dbHelper;
        if (helper != null) {
            return helper;
        }
        synchronized (this) {
            if (dbHelper == null) {
                dbHelper = new KnowledgeDbHelper(context);
            }
            return dbHelper;
        }
    }

    /**
     * 判断"这次开库失败是不是库文件坏了"。
     *
     * <p>只认明确的损坏信号：把普通的 SQL 错误也当损坏去重建库，等于用"丢光用户数据"来掩盖一个 bug。</p>
     */
    private static boolean isCorruption(SQLiteException e) {
        if (e instanceof SQLiteDatabaseCorruptException) {
            return true;
        }
        String msg = e.getMessage();
        if (msg == null) {
            return false;
        }
        String lower = msg.toLowerCase(Locale.ROOT);
        return lower.contains("malformed")
                || lower.contains("not a database")
                || lower.contains("sqlite_corrupt")
                || lower.contains("database disk image is malformed");
    }

    /**
     * 损坏自愈（2026-11-10）：把坏库文件**改名留档**（不删），再建一个空库。
     *
     * <p>补的是"坏一次就永久废掉"的死路：此前库文件损坏后每次调用都抛
     * {@code SQLiteDatabaseCorruptException}，用户只能清数据——把唯一资产一起清掉。
     * 现在原文件仍在 {@code knowledge_base.db.corrupted-<时间戳>}，还能按需抢救/上报排查。</p>
     */
    private void recoverCorruptedDatabase(SQLiteException cause) {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
        Log.e(TAG, "knowledge_base.db 疑似损坏（" + cause.getMessage() + "），改名留档后重建空库");
        try {
            if (dbHelper != null) {
                dbHelper.close();
            }
        } catch (Throwable ignored) {
            // 连接本身可能已经坏掉，关不掉不影响后续改名
        }
        dbHelper = null;
        File dbFile = context.getDatabasePath(DB_NAME);
        File dir = dbFile.getParentFile();
        for (String suffix : new String[]{"", "-wal", "-shm"}) {
            File from = new File(dir, DB_NAME + suffix);
            if (!from.exists()) {
                continue;
            }
            File to = new File(dir, DB_NAME + suffix + ".corrupted-" + stamp);
            if (!from.renameTo(to)) {
                Log.w(TAG, "损坏库留档失败（继续重建）: " + from.getName());
            }
        }
        lastError = "知识库数据库损坏，已重建空库；原文件保留为 " + DB_NAME + ".corrupted-" + stamp
                + "（如需抢救可从导出备份 import 回来）";
        synchronized (this) {
            dbHelper = new KnowledgeDbHelper(context);
        }
    }

    /**
     * v4 数据迁移（2026-10-28）：把 v3 的"原文 search_text"重建成 bigram token 流。
     *
     * <p>为什么不放在 {@code onUpgrade}：那条路跑在 SQLiteOpenHelper 的升级事务里，
     * 逐行 UPDATE 会被外层事务吞掉（单测实测：版本抬到 4、search_text 没变）。这里在开库后
     * 用独立事务重算一次，并用 prefs 标记保证只跑一次；失败只记日志——旧数据仍可按单字命中，
     * 不会把知识库弄坏。</p>
     */
    private void ensureSearchTextV4(SQLiteDatabase db) {
        if (searchTextV4Checked || db == null) return;
        synchronized (this) {
            if (searchTextV4Checked) return;
            searchTextV4Checked = true;
            try {
                // 迁移标记存在库里（kb_meta），不用 SharedPreferences：
                // 两者生命周期不同，prefs 被清/换机恢复数据时会出现"版本抬了、数据没重建"
                if (searchTextVersion(db) >= 4) return;
                dbHelper.rebuildSearchTextV4(db);
                db.execSQL("INSERT OR REPLACE INTO kb_meta(k, v) VALUES('search_text_version', '4')");
            } catch (Throwable t) {
                Log.w(TAG, "ensureSearchTextV4 failed: " + t.getMessage());
            }
        }
    }

    /** 读取库内的 search_text 格式版本（读不到按 0/旧版处理） */
    private static int searchTextVersion(SQLiteDatabase db) {
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT v FROM kb_meta WHERE k = 'search_text_version'", null);
            if (cursor.moveToFirst()) {
                return (int) Long.parseLong(cursor.getString(0));
            }
        } catch (Throwable ignored) {
            // 老库没有 kb_meta 表：当作旧版，触发重建
        } finally {
            if (cursor != null) cursor.close();
        }
        return 0;
    }

    private volatile boolean searchTextV4Checked = false;

    /** 文档层回填只跑一次的进程内标记（库内另有 kb_meta.documents_version 兜住跨进程） */
    private volatile boolean documentsV5Checked = false;

    /**
     * v5 数据回填（2026-11-10）：把 v4 时代的"一堆平块"聚合成"文档 + 块"。
     *
     * <p>回填口径：{@code source} 相同且非空的块归为一篇文档，块序按 id 升序编号；
     * {@code source} 为空的（手工 {@code add} 的单条知识）保持 {@code doc_id=0}，不属于任何文档。</p>
     *
     * <p>内存：**一次只装一篇**（几十块），不是一次性把全表读进内存——v4 的 search_text 回填就是
     * 全表进 List，万块级峰值 30MB，那是有 OOM 风险的写法，这里不再复制。</p>
     */
    private void ensureDocumentsV5(SQLiteDatabase db) {
        if (documentsV5Checked || db == null) {
            return;
        }
        synchronized (this) {
            if (documentsV5Checked) {
                return;
            }
            documentsV5Checked = true;
            try {
                if (metaInt(db, "documents_version") >= 5) {
                    return;
                }
                int docs = rebuildDocuments(db);
                db.execSQL("INSERT OR REPLACE INTO kb_meta(k, v) VALUES('documents_version', '5')");
                Log.i(TAG, "文档层 v5 回填完成: " + docs + " 篇");
            } catch (Throwable t) {
                // 回填失败不该让整个知识库不可用：块还在，只是没有文档身份（去重会回退到现算哈希）
                Log.w(TAG, "ensureDocumentsV5 失败: " + t.getMessage());
            }
        }
    }

    /** 按 source 聚合既有块、建文档记录并回写 doc_id/chunk_seq，返回构建的文档数 */
    private int rebuildDocuments(SQLiteDatabase db) {
        List<String> sources = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT source FROM kb_chunks WHERE source <> '' GROUP BY source", null);
            while (cursor.moveToNext()) {
                String s = cursor.getString(0);
                if (s != null && !s.trim().isEmpty()) {
                    sources.add(s);
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        if (sources.isEmpty()) {
            return 0;
        }
        int docs = 0;
        db.beginTransaction();
        try {
            for (String source : sources) {
                List<Object[]> rows = new ArrayList<>();
                StringBuilder sb = new StringBuilder();
                String title = "";
                String category = "general";
                long created = 0;
                long updated = 0;
                cursor = db.rawQuery("SELECT id, title, category, content, created_at, updated_at"
                        + " FROM kb_chunks WHERE source = ? ORDER BY id", new String[]{source});
                try {
                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(0);
                        if (rows.isEmpty()) {
                            title = safe(cursor.getString(1));
                            category = safe(cursor.getString(2));
                            created = cursor.getLong(4);
                        }
                        sb.append(safe(cursor.getString(3))).append('\n');
                        updated = Math.max(updated, cursor.getLong(5));
                        rows.add(new Object[]{id});
                    }
                } finally {
                    cursor.close();
                    cursor = null;
                }
                if (rows.isEmpty()) {
                    continue;
                }
                long docId = upsertDocument(db, title, category, source, md5(sb.toString()),
                        rows.size(), created, updated);
                for (int i = 0; i < rows.size(); i++) {
                    db.execSQL("UPDATE kb_chunks SET doc_id = ?, chunk_seq = ? WHERE id = ?",
                            new Object[]{docId, i + 1, rows.get(i)[0]});
                }
                docs++;
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return docs;
    }

    /** 读库内迁移标记（读不到/非法按 0 处理） */
    private static int metaInt(SQLiteDatabase db, String key) {
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT v FROM kb_meta WHERE k = ?", new String[]{key});
            if (cursor.moveToFirst()) {
                return (int) Long.parseLong(cursor.getString(0));
            }
        } catch (Throwable ignored) {
            // 老库没有 kb_meta 表：当作未迁移
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return 0;
    }

    /** 某篇文档的现状（去重只看这两项：哈希 + 块数） */
    private static final class DocState {
        long docId;
        int count;
        String hash = "";
    }

    /** 按 source 取文档记录（去重的主路径：O(1) 读 doc_hash，不再现算全篇） */
    private DocState findDocument(String source) {
        DocState state = new DocState();
        if (source == null || source.trim().isEmpty()) {
            return state;
        }
        Cursor cursor = null;
        try {
            SQLiteDatabase db = getReadableDb();
            cursor = db.rawQuery("SELECT doc_id, doc_hash, chunk_count FROM kb_documents"
                    + " WHERE source = ? ORDER BY doc_id DESC LIMIT 1", new String[]{source.trim()});
            if (cursor.moveToFirst()) {
                state.docId = cursor.getLong(0);
                state.hash = safe(cursor.getString(1));
                state.count = cursor.getInt(2);
            }
        } catch (Exception e) {
            // 查不出来不该阻断导入：退化成"当作没导过"，由调用方走旧口径现算
            Log.w(TAG, "findDocument 失败: " + e.getMessage());
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return state;
    }

    /** 插入或更新一篇文档记录，返回 doc_id */
    private static long upsertDocument(SQLiteDatabase db, String title, String category, String source,
                                       String hash, int chunkCount, long created, long updated) {
        ContentValues values = new ContentValues();
        values.put("title", title);
        values.put("category", (category == null || category.trim().isEmpty()) ? "general" : category);
        values.put("source", source);
        values.put("doc_hash", hash);
        values.put("chunk_count", chunkCount);
        values.put("updated_at", updated == 0 ? System.currentTimeMillis() : updated);
        Cursor cursor = db.rawQuery("SELECT doc_id FROM kb_documents WHERE source = ? LIMIT 1",
                new String[]{source});
        long docId = 0;
        try {
            if (cursor.moveToFirst()) {
                docId = cursor.getLong(0);
            }
        } finally {
            cursor.close();
        }
        if (docId > 0) {
            db.update("kb_documents", values, "doc_id = ?", new String[]{String.valueOf(docId)});
            return docId;
        }
        values.put("created_at", created == 0 ? System.currentTimeMillis() : created);
        return db.insertOrThrow("kb_documents", null, values);
    }

    /**
     * 重算某篇文档的哈希与块数（块被改动/删除后调用）。
     *
     * <p>为什么要重算而不是只维护计数：{@code doc_hash} 是去重的唯一依据，
     * 只改计数会让"内容变了却仍被判为没变"，下次导入被错误跳过。</p>
     */
    private void refreshDocument(long docId) {
        if (docId <= 0) {
            return;
        }
        Cursor cursor = null;
        try {
            SQLiteDatabase db = getWritableDb();
            cursor = db.rawQuery("SELECT content FROM kb_chunks WHERE doc_id = ? ORDER BY chunk_seq, id",
                    new String[]{String.valueOf(docId)});
            StringBuilder sb = new StringBuilder();
            int count = 0;
            while (cursor.moveToNext()) {
                count++;
                sb.append(safe(cursor.getString(0))).append('\n');
            }
            cursor.close();
            cursor = null;
            if (count == 0) {
                db.delete("kb_documents", "doc_id = ?", new String[]{String.valueOf(docId)});
                return;
            }
            ContentValues values = new ContentValues();
            values.put("doc_hash", md5(sb.toString()));
            values.put("chunk_count", count);
            values.put("updated_at", System.currentTimeMillis());
            db.update("kb_documents", values, "doc_id = ?", new String[]{String.valueOf(docId)});
        } catch (Exception e) {
            Log.w(TAG, "refreshDocument 失败: " + e.getMessage());
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
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
        return search(query, category, null, topK, semanticRerank);
    }

    /**
     * 全文检索 + 标签精确过滤（2026-10-28）。
     *
     * <p>{@code tag} 走的是**关键词字段的精确匹配**，不参与分词：一条知识可以用
     * {@code keywords} 存别名/同义词/标签（逗号分隔），模型拿"别名"检索时命中率会掉
     * （按字分词会把标签稀释在正文里），用 {@code tag} 就是等值命中。</p>
     *
     * @param tag 标签/别名（对 keywords 做整词匹配，大小写与中英文逗号都归一）；空=不过滤
     */
    public JSONArray search(String query, String category, String tag, int topK, boolean semanticRerank) {
        int limit = (topK <= 0) ? DEFAULT_TOP_K : Math.min(topK, MAX_TOP_K);
        boolean hasQuery = query != null && !query.trim().isEmpty();
        JSONArray results;
        if (hasQuery && semanticRerank && semanticHealthy() && countEmbeddings(category) > 0) {
            // v6：本地已有向量 → 关键词 + 向量两路召回，RRF 融合（能捞回关键词没命中的同义问法）
            results = hybridSearch(query, category, tag, limit);
        } else {
            // 向量还没回填（或显式没配模型）→ 保持 v5 行为：
            // 先关键词召回；semanticRerank=true 时再做旧的"在线 embedding 重排"（只重排、不扩召回）
            results = searchInternal(query, category, tag, limit);
            if (hasQuery && semanticRerank) {
                results = applySemanticRerank(results, query, limit);
            }
        }
        return results;
    }

    /**
     * 轻量索引（2026-10-28）：只回 id/title/category/keywords/source/updated_at，**不带正文**。
     *
     * <p>给"先看有哪些、再精确取某条"用：从前只有 search，一次就把 top5 的正文全吐出来，
     * 模型想确认"知识库里都有什么"时既贵又不准（正文长、token 高、还容易被截断）。
     * 索引条目约 30~60 字，50 条也就 2~3k 字，模型看清标题后再 {@link #getById}。</p>
     *
     * @param category 分类过滤，空/null 表示全部
     * @param limit    条数上限（<=0 用 20，最大 100）
     */
    public synchronized JSONArray listMeta(String category, int limit) {
        return listMeta(category, null, limit, 0);
    }

    /**
     * 轻量索引 + 标签精确过滤（2026-10-28）：按 keywords 里的别名/标签筛一批，
     * 再决定取哪条详情——不需要先模糊检索。
     *
     * @param tag 标签/别名（对 keywords 整词匹配）；空=不过滤
     */
    public synchronized JSONArray listMeta(String category, String tag, int limit) {
        return listMeta(category, tag, limit, 0);
    }

    /**
     * 轻量索引（v5 补分页）：多了 {@code offset}，解决"超过 100 条就再也看不到"的问题。
     *
     * <p>翻页口径是 {@code ORDER BY updated_at DESC, id DESC}：批量导入的块 {@code updated_at} 完全相同，
     * 只按时间排序时翻页会重复/漏行，加 id 兜底让顺序稳定。</p>
     *
     * @param offset 跳过前 N 条（>=0）；配合工具层的 {@code list(offset=…)} 做翻页
     */
    public synchronized JSONArray listMeta(String category, String tag, int limit, int offset) {
        lastError = null;
        int cap = limit <= 0 ? 20 : Math.min(limit, 100);
        int skip = Math.max(offset, 0);
        JSONArray out = new JSONArray();
        Cursor cursor = null;
        try {
            SQLiteDatabase db = getReadableDb();
            boolean hasCategory = category != null && !category.trim().isEmpty();
            boolean hasTag = tag != null && !tag.trim().isEmpty();
            java.util.List<String> args = new ArrayList<>();
            StringBuilder where = new StringBuilder();
            if (hasCategory) {
                where.append("category = ?");
                args.add(category.trim());
            }
            if (hasTag) {
                if (where.length() > 0) where.append(" AND ");
                where.append(tagClause());
                args.add("%," + normalizeTag(tag) + ",%");
            }
            String sql = "SELECT id, title, category, keywords, source, updated_at, doc_id, chunk_seq"
                    + " FROM kb_chunks"
                    + (where.length() > 0 ? " WHERE " + where : "")
                    + " ORDER BY updated_at DESC, id DESC LIMIT ? OFFSET ?";
            args.add(String.valueOf(cap));
            args.add(String.valueOf(skip));
            cursor = db.rawQuery(sql, args.toArray(new String[0]));
            while (cursor.moveToNext()) {
                JSONObject item = new JSONObject();
                item.put("id", cursor.getLong(0));
                item.put("title", safe(cursor.getString(1)));
                item.put("category", safe(cursor.getString(2)));
                item.put("keywords", safe(cursor.getString(3)));
                item.put("source", safe(cursor.getString(4)));
                item.put("updated_at", cursor.getLong(5));
                item.put("doc_id", cursor.getLong(6));
                item.put("chunk_seq", cursor.getInt(7));
                out.put(item);
            }
        } catch (Exception e) {
            lastError = "列出知识索引失败: " + e.getMessage();
            Log.e(TAG, "listMeta 失败: " + e.getMessage(), e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return out;
    }

    /**
     * 按 id 取单条完整知识（含正文）。找不到返回 null（原因见 {@link #getLastError()}）。
     *
     * <p>与 {@link #search} 的区别：这是**精确取**，不靠分词/打分，一次一条，
     * 不会因为模糊匹配把一堆无关片段塞进上下文。</p>
     */
    public synchronized JSONObject getById(long id) {
        lastError = null;
        if (id <= 0) {
            lastError = "id 无效: " + id;
            return null;
        }
        Cursor cursor = null;
        try {
            SQLiteDatabase db = getReadableDb();
            cursor = db.rawQuery("SELECT id, title, category, keywords, source, updated_at, content, doc_id, chunk_seq "
                    + "FROM kb_chunks WHERE id = ?", new String[]{String.valueOf(id)});
            if (!cursor.moveToFirst()) {
                lastError = "未找到 id=" + id + " 的知识";
                return null;
            }
            JSONObject item = new JSONObject();
            item.put("id", cursor.getLong(0));
            item.put("title", safe(cursor.getString(1)));
            item.put("category", safe(cursor.getString(2)));
            item.put("keywords", safe(cursor.getString(3)));
            item.put("source", safe(cursor.getString(4)));
            item.put("updated_at", cursor.getLong(5));
            item.put("content", safe(cursor.getString(6)));
            item.put("doc_id", cursor.getLong(7));
            item.put("chunk_seq", cursor.getInt(8));
            return item;
        } catch (Exception e) {
            lastError = "读取知识失败: " + e.getMessage();
            Log.e(TAG, "getById 失败: " + e.getMessage(), e);
            return null;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    /**
     * 精确检索（2026-10-28）：按 {@code id} 或 {@code 标题全等} 取，不做分词/模糊匹配。
     *
     * <p>为什么需要：现有 search 是"按字切 token + LIKE + 打分"，查"光合作用"会把
     * 含"光/合/作/用"任意组合的条目全召回，模型拿到的是一堆近似片段，没法精确命中。
     * 知道确切标题或 id 时走这里，命中就是命中、不命中就明确说没有。</p>
     */
    public synchronized JSONArray searchExact(Long id, String exactTitle, int limit) {
        lastError = null;
        int cap = limit <= 0 ? DEFAULT_TOP_K : Math.min(limit, MAX_TOP_K);
        JSONArray out = new JSONArray();
        Cursor cursor = null;
        try {
            SQLiteDatabase db = getReadableDb();
            if (id != null && id > 0) {
                JSONObject one = getById(id);
                if (one != null) out.put(one);
                return out;
            }
            if (exactTitle == null || exactTitle.trim().isEmpty()) {
                lastError = "精确检索需要 id 或 exact_title";
                return out;
            }
            cursor = db.rawQuery("SELECT id, title, category, keywords, source, updated_at, content, doc_id, chunk_seq "
                            + "FROM kb_chunks WHERE title = ? ORDER BY updated_at DESC LIMIT ?",
                    new String[]{exactTitle.trim(), String.valueOf(cap)});
            while (cursor.moveToNext()) {
                JSONObject item = new JSONObject();
                item.put("id", cursor.getLong(0));
                item.put("title", safe(cursor.getString(1)));
                item.put("category", safe(cursor.getString(2)));
                item.put("keywords", safe(cursor.getString(3)));
                item.put("source", safe(cursor.getString(4)));
                item.put("updated_at", cursor.getLong(5));
                item.put("content", safe(cursor.getString(6)));
                item.put("doc_id", cursor.getLong(7));
                item.put("chunk_seq", cursor.getInt(8));
                out.put(item);
            }
        } catch (Exception e) {
            lastError = "精确检索失败: " + e.getMessage();
            Log.e(TAG, "searchExact 失败: " + e.getMessage(), e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return out;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /**
     * 标签精确匹配的 SQL 片段：把 {@code keywords} 归一成 {@code ,a,b,c,} 再看是否包含 {@code ,tag,}。
     *
     * <p>归一 = 小写 + 去掉半角/全角空格 + 中文逗号转英文逗号，于是
     * "别名，同义词" 与 "别名,同义词" 等价；用逗号包边保证是**整词**命中——
     * 查 "光合" 不会命中 "光合作用"。</p>
     */
    private static String tagClause() {
        return "(',' || replace(replace(replace(lower(keywords), ' ', ''), '　', ''), '，', ',') || ',') LIKE ?";
    }

    /** 标签归一（与 {@link #tagClause()} 的 SQL 侧同口径） */
    private static String normalizeTag(String tag) {
        if (tag == null) return "";
        return tag.trim().toLowerCase(Locale.ROOT)
                .replace(" ", "").replace("　", "").replace("，", ",");
    }

    /**
     * 关键词检索主体（同步、持锁）。
     *
     * <p>v5（2026-11-10）改了两件事，都是为了修"整句查询恒 0 命中"：</p>
     * <ol>
     *   <li><b>召回从 AND 改成 OR</b>：旧版每个 token 都拼 {@code AND}，而 token 是跨词边界的
     *       bigram（"光气泄漏怎么处理" 切出 漏怎/么处，任何文档里都不存在），只要有一个 token
     *       匹配不上就整条归零。改成 OR 之后召回变宽，精度交给下面的覆盖率门槛。</li>
     *   <li><b>不再先 {@code LIMIT} 再打分</b>：旧版 {@code ORDER BY updated_at DESC LIMIT max(limit*10,50)}，
     *       命中集一大就只剩最近 50 行参与排序（批量导入时间戳全相同，实际取的是最早那批）。
     *       现在扫全部命中行（上限 {@link #MAX_SCAN_ROWS}），Java 侧按
     *       "覆盖率 → 词频分 → 更新时间"排序，最后只为入选的 limit 条回表取正文。</li>
     * </ol>
     */
    private synchronized JSONArray searchInternal(String query, String category, String tag, int limit) {
        lastError = null;
        JSONArray results = new JSONArray();
        Cursor cursor = null;
        long startedAt = System.currentTimeMillis();
        boolean hasQuery = query != null && !query.trim().isEmpty();
        boolean hasCategory = category != null && !category.trim().isEmpty();
        boolean hasTag = tag != null && !tag.trim().isEmpty();
        List<String> tokens = hasQuery ? tokenizeQuery(query) : new ArrayList<String>();
        if (hasQuery && tokens.isEmpty()) {
            // 查询里没有任何可检索字符（纯标点/空白）：退化为按分类/时间列出
            hasQuery = false;
        }
        try {
            SQLiteDatabase db = getReadableDb();

            StringBuilder where = new StringBuilder();
            List<String> args = new ArrayList<>();

            if (hasQuery) {
                // OR 召回：命中任意一个 token 都进候选，精度由覆盖率门槛 + 排序保证
                where.append('(');
                for (int i = 0; i < tokens.size(); i++) {
                    if (i > 0) {
                        where.append(" OR ");
                    }
                    // bigram 用"空格边界"整 token 匹配：不然 "光合" 会匹配到 "光合作" 里的子串；
                    // 单字查询没有边界可言，退回包含匹配（否则单字永远查不到）
                    String token = tokens.get(i);
                    if (token.length() >= 2) {
                        where.append("(' ' || search_text || ' ') LIKE ?");
                        args.add("% " + token + " %");
                    } else {
                        where.append("search_text LIKE ?");
                        args.add("%" + token + "%");
                    }
                }
                where.append(')');
            }
            if (hasCategory) {
                if (where.length() > 0) {
                    where.append(" AND ");
                }
                where.append("category = ?");
                args.add(category.trim());
            }
            if (hasTag) {
                if (where.length() > 0) {
                    where.append(" AND ");
                }
                where.append(tagClause());
                args.add("%," + normalizeTag(tag) + ",%");
            }
            if (!hasCategory) {
                // 分类隔离（2026-11-10 下推到 SQL）：不传 category 的检索默认排除系统工具定义库 tool_defs。
                // v4 时这层过滤在工具层 Java 侧、且发生在 top_k 截断**之后**——每轮召回会被工具定义
                // 白占名额（召 8 条有 5 条是工具用法，用户资料被挤掉）。放到 WHERE 里，命中集一开始就干净。
                // 需要工具定义时显式传 category="tool_defs"（OnlineToolManager 就是这么调的）。
                if (where.length() > 0) {
                    where.append(" AND ");
                }
                where.append("category <> 'tool_defs'");
            }

            // 无查询词（只按分类/标签列）：保持原行为——直接按更新时间取 limit 条
            if (!hasQuery) {
                String sql = "SELECT id, title, category, keywords, source, updated_at, content, doc_id, chunk_seq"
                        + " FROM kb_chunks"
                        + (where.length() > 0 ? " WHERE " + where : "")
                        + " ORDER BY updated_at DESC LIMIT ?";
                args.add(String.valueOf(limit));
                cursor = db.rawQuery(sql, args.toArray(new String[0]));
                while (cursor.moveToNext()) {
                    results.put(rowFromCursor(cursor, tokens, 0, 0));
                }
                return results;
            }

            // ===== 第一遍：扫全部命中行，只算覆盖率/分数，不驻留正文 =====
            // 候选行数可能上千，把每行正文都建成 JSONObject 会白白吃内存；
            // 这里只留 id + 分数，等排序选出 limit 条再回表取正文。
            String scanSql = "SELECT id, title, category, keywords, content, updated_at FROM kb_chunks WHERE "
                    + where + " LIMIT ?";
            args.add(String.valueOf(MAX_SCAN_ROWS));

            List<Long> ids = new ArrayList<>();
            List<Long> updateds = new ArrayList<>();
            List<Integer> scores = new ArrayList<>();
            List<Double> coverages = new ArrayList<>();
            int scanned = 0;
            cursor = db.rawQuery(scanSql, args.toArray(new String[0]));
            while (cursor.moveToNext()) {
                scanned++;
                String title = cursor.getString(1) == null ? "" : cursor.getString(1);
                String cat = cursor.getString(2) == null ? "" : cursor.getString(2);
                String keywords = cursor.getString(3) == null ? "" : cursor.getString(3);
                String content = cursor.getString(4) == null ? "" : cursor.getString(4);
                RowScore rs = scoreRow(tokens, title, cat, keywords, content);
                ids.add(cursor.getLong(0));
                updateds.add(cursor.getLong(5));
                scores.add(rs.score);
                coverages.add(rs.coverage);
            }
            cursor.close();
            cursor = null;

            if (scanned >= MAX_SCAN_ROWS) {
                Log.w(TAG, "检索命中行数触顶 " + MAX_SCAN_ROWS + "，超出部分未参与排序（query=" + query + "）");
            }

            // 排序键：覆盖率（越高越相关）→ 词频分 → 更新时间
            Integer[] order = new Integer[ids.size()];
            for (int i = 0; i < order.length; i++) {
                order[i] = i;
            }
            Arrays.sort(order, (a, b) -> {
                int cmp = Double.compare(coverages.get(b), coverages.get(a));
                if (cmp != 0) {
                    return cmp;
                }
                cmp = Integer.compare(scores.get(b), scores.get(a));
                if (cmp != 0) {
                    return cmp;
                }
                return Long.compare(updateds.get(b), updateds.get(a));
            });

            // 覆盖率门槛：OR 召回带进来的泛词行在这里被挡掉
            List<Long> topIds = new ArrayList<>();
            Map<Long, Integer> scoreById = new LinkedHashMap<>();
            Map<Long, Double> coverageById = new LinkedHashMap<>();
            for (int idx : order) {
                if (coverages.get(idx) < MIN_COVERAGE) {
                    break;   // 已按覆盖率降序，后面的只会更低
                }
                topIds.add(ids.get(idx));
                scoreById.put(ids.get(idx), scores.get(idx));
                coverageById.put(ids.get(idx), coverages.get(idx));
                if (topIds.size() >= limit) {
                    break;
                }
            }
            if (topIds.isEmpty()) {
                return results;
            }

            // ===== 第二遍：只为入选的 limit 条回表取正文（顺序按排名还原） =====
            StringBuilder in = new StringBuilder("id IN (");
            List<String> idArgs = new ArrayList<>();
            for (int i = 0; i < topIds.size(); i++) {
                if (i > 0) {
                    in.append(',');
                }
                in.append('?');
                idArgs.add(String.valueOf(topIds.get(i)));
            }
            in.append(')');
            cursor = db.rawQuery("SELECT id, title, category, keywords, source, updated_at, content, doc_id, chunk_seq "
                    + "FROM kb_chunks WHERE " + in, idArgs.toArray(new String[0]));
            Map<Long, JSONObject> fetched = new LinkedHashMap<>();
            while (cursor.moveToNext()) {
                JSONObject row = rowFromCursor(cursor, tokens,
                        scoreById.containsKey(cursor.getLong(0)) ? scoreById.get(cursor.getLong(0)) : 0,
                        coverageById.containsKey(cursor.getLong(0)) ? coverageById.get(cursor.getLong(0)) : 0);
                fetched.put(cursor.getLong(0), row);
            }
            for (Long id : topIds) {
                JSONObject row = fetched.get(id);
                if (row != null) {
                    results.put(row);
                }
            }
        } catch (Exception e) {
            lastError = e.getMessage();
            Log.e(TAG, "search 失败: " + e.getMessage(), e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
            long cost = System.currentTimeMillis() - startedAt;
            if (cost >= SLOW_SEARCH_MS) {
                Log.w(TAG, "检索耗时 " + cost + "ms（tokens=" + tokens.size() + "），已接近全表扫描成本上限");
            }
        }
        return results;
    }

    /** 把游标当前行转成结果 JSON（带 snippet / score / coverage） */
    private static JSONObject rowFromCursor(Cursor cursor, List<String> tokens, int score, double coverage)
            throws JSONException {
        JSONObject item = new JSONObject();
        String content = cursor.getString(6) == null ? "" : cursor.getString(6);
        item.put("id", cursor.getLong(0));
        item.put("title", cursor.getString(1) == null ? "" : cursor.getString(1));
        item.put("category", cursor.getString(2) == null ? "" : cursor.getString(2));
        item.put("keywords", cursor.getString(3) == null ? "" : cursor.getString(3));
        item.put("source", cursor.getString(4) == null ? "" : cursor.getString(4));
        item.put("updated_at", cursor.getLong(5));
        item.put("content", content);
        item.put("snippet", buildSnippet(content, tokens));
        item.put("score", score);
        item.put("coverage", Math.round(coverage * 100) / 100.0);
        // 溯源（v5）：doc_id + chunk_seq 合起来能说"这是某篇文档的第几块"；
        // doc_id=0 是手工单条知识，没有块序
        item.put("doc_id", cursor.getLong(7));
        item.put("chunk_seq", cursor.getInt(8));
        return item;
    }

    /**
     * 相关度打分：标题/分类/关键词命中权重 3，正文命中权重 1。
     * 每个 token 只计一次（SQL 已保证每个 token 至少在某处命中，因此得分恒 > 0）。
     */
    /** 单行打分结果：词频分 + 覆盖率（覆盖率是 v5 新增的主排序键） */
    private static final class RowScore {
        int score;
        double coverage;
    }

    /**
     * 相关度打分（v4 词频 + v5 覆盖率）：词频取对数 + 字段权重 + 正文长度归一，同时统计覆盖率。
     *
     * <p>为什么要把覆盖率拉进来：v4 是"全 token AND"，进到打分阶段的行必然**全部** token 命中，
     * 覆盖率恒等于 1，排序只剩"谁重复得多、谁更短"——那套 BM25 近似其实没起作用。
     * v5 改成 OR 召回后，覆盖率高才是"真的讲的是这件事"，这才是主排序键；词频分降为同覆盖率下的次序。</p>
     */
    private static RowScore scoreRow(List<String> tokens, String title, String category,
                                     String keywords, String content) {
        RowScore out = new RowScore();
        if (tokens == null || tokens.isEmpty()) {
            return out;
        }
        String head = ((title == null ? "" : title) + " " + (category == null ? "" : category)
                + " " + (keywords == null ? "" : keywords)).toLowerCase(Locale.ROOT);
        String body = content == null ? "" : content.toLowerCase(Locale.ROOT);
        double raw = 0;
        int hits = 0;
        for (String token : tokens) {
            int tfHead = countOccurrences(head, token);
            int tfBody = countOccurrences(body, token);
            if (tfHead > 0 || tfBody > 0) {
                hits++;
            }
            if (tfHead > 0) {
                raw += 3.0 * (1.0 + Math.log(1.0 + tfHead));
            }
            if (tfBody > 0) {
                raw += 1.0 * (1.0 + Math.log(1.0 + tfBody));
            }
        }
        out.coverage = (double) hits / tokens.size();
        if (raw > 0) {
            // 长度归一：1200 字以内的文档基本不惩罚，长文档轻微降权（BM25 的 b 项近似）
            double norm = 1.0 / (1.0 + body.length() / 1200.0);
            out.score = (int) Math.round(raw * norm * 10);
        }
        return out;
    }

    /** 子串出现次数（token 已小写；用于词频，不区分大小写） */
    private static int countOccurrences(String text, String token) {
        if (text == null || text.isEmpty() || token == null || token.isEmpty()) return 0;
        int count = 0;
        int from = 0;
        while (true) {
            int idx = text.indexOf(token, from);
            if (idx < 0) break;
            count++;
            from = idx + Math.max(1, token.length());
        }
        return count;
    }

    /** 查询分词：短横线/标点剥离，英文数字按词、中文按相邻两字（bigram），去重并限制数量 */
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-z0-9]+|[\\u4e00-\\u9fff]+");
    /** 非词字符（标点/空白/符号）：先把整句切成"语义段"，段内才切 bigram */
    private static final Pattern QUERY_BREAK = Pattern.compile("[^a-z0-9\\u4e00-\\u9fff]+");

    /**
     * 查询切分点（2026-11-10，v5）：中文虚词/疑问词，当作**段边界**而不是 token。
     *
     * <p>为什么必须切：入库侧和查询侧的 bigram 都是在"无标点的连续中文段"内按相邻两字生成的，
     * 所以「光气泄漏怎么处理」会切出 {@code 漏怎}、{@code 么处} 这种**任何文档里都不存在**的
     * token（它们跨越了词边界）。v4 又把这些 token 全 AND 起来，于是整句查询基本恒 0 命中。</p>
     *
     * <p>这里只收**双字以上**的虚词：单字虚词（在/和/有/为）不能当分隔符，否则"在线/和平/有机"
     * 这类实词会被切坏；单字虚词改在 token 生成后剔除（见 {@link #STOP_CHARS}）。</p>
     */
    private static final String[] QUERY_STOPWORDS = {
            "为什么", "怎么样", "怎么办", "什么样", "能不能", "有没有", "可不可以", "是什么", "有什么",
            "怎么", "怎样", "什么", "为何", "如何", "哪些", "哪个", "哪里", "是否", "多少",
            "可以", "需要", "应该", "请问", "帮我", "麻烦", "告诉", "说明", "介绍",
            "一下", "这个", "那个", "这些", "那些", "我们", "你们", "他们",
            "以及", "还有", "或者", "但是", "因为", "所以", "如果", "那么", "然后",
            "时候", "关于", "有关", "里面", "中的",
    };

    /** 按长度降序拼成"或"式正则，长的先切（否则"怎么样"会被"怎么"先啃掉一半） */
    private static final Pattern QUERY_STOPWORD_PATTERN = buildStopwordPattern();

    /** 单独成词时要剔除的单字虚词（它们从不单独检索，留着只会稀释覆盖率） */
    private static final String STOP_CHARS = "的了是吗呢吧啊嗯和与及或而但就都也还很要把被给对从到这那我你他它有";

    private static Pattern buildStopwordPattern() {
        String[] sorted = QUERY_STOPWORDS.clone();
        Arrays.sort(sorted, (a, b) -> Integer.compare(b.length(), a.length()));
        StringBuilder sb = new StringBuilder();
        for (String word : sorted) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(Pattern.quote(word));
        }
        return Pattern.compile(sb.toString());
    }

    /**
     * 查询分词（v5，2026-11-10）：先按标点/空白/虚词把整句切成"语义段"，再在每段内部切 bigram。
     *
     * <p>与入库侧 {@link #buildSearchText} 保持同一口径（bigram、空格分隔），但**不跨词边界**：
     * 「光气泄漏怎么处理」→ 光气/气泄/泄漏 + 处理，而不是把 漏怎/么处 也算进来。
     * 这样召回才能命中「光气泄漏应急处理流程」这类真实条目。</p>
     */
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
        for (String segment : QUERY_BREAK.split(s)) {
            if (unique.size() >= MAX_QUERY_TOKENS) {
                break;
            }
            for (String piece : QUERY_STOPWORD_PATTERN.split(segment)) {
                if (unique.size() >= MAX_QUERY_TOKENS) {
                    break;
                }
                Matcher m = TOKEN_PATTERN.matcher(piece);
                while (m.find()) {
                    if (unique.size() >= MAX_QUERY_TOKENS) {
                        break;
                    }
                    String t = m.group();
                    if (isAsciiRun(t)) {
                        unique.add(t);
                    } else if (t.length() == 1) {
                        unique.add(t);
                    } else {
                        // 中文段内切相邻两字：与入库侧 buildSearchText 同口径，
                        // 顺序敏感（"光合作用"→光合/合作/作用），比逐字 AND 精确得多
                        for (int i = 0; i + 1 < t.length() && unique.size() < MAX_QUERY_TOKENS; i++) {
                            unique.add(t.substring(i, i + 2));
                        }
                    }
                }
            }
        }
        // 单个 token 时不做单字虚词剔除：否则查"的"这类单字会直接变成空查询
        if (unique.size() > 1) {
            List<String> filtered = new ArrayList<>(unique.size());
            for (String t : unique) {
                if (t.length() == 1 && STOP_CHARS.indexOf(t.charAt(0)) >= 0) {
                    continue;
                }
                filtered.add(t);
            }
            if (!filtered.isEmpty()) {
                unique.clear();
                unique.addAll(filtered);
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

    // ==================== 本地向量召回（v6 / RAG）====================

    /**
     * 向量化能力。生产实现走在线 embedding 端点；单测注入确定性假向量，
     * 避免 Robolectric 里真的发网络请求。
     */
    interface Embedder {
        float[] embed(String text) throws Exception;
    }

    private volatile Embedder embedder;

    /** 本地向量模型（离线 ONNX）：惰性创建，构造时不加载任何东西 */
    private volatile com.oilquiz.app.ai.embedding.LocalEmbeddingModel localEmbedding;

    /** 本地向量模型句柄（只看状态、不触发加载）；文件在不在由 isAvailable() 判断 */
    com.oilquiz.app.ai.embedding.LocalEmbeddingModel localEmbedding() {
        com.oilquiz.app.ai.embedding.LocalEmbeddingModel m = localEmbedding;
        if (m == null) {
            synchronized (this) {
                m = localEmbedding;
                if (m == null) {
                    m = new com.oilquiz.app.ai.embedding.LocalEmbeddingModel(context);
                    localEmbedding = m;
                }
            }
        }
        return m;
    }

    /** 仅测试用：替换向量化实现（null = 恢复在线默认实现）；换实现即清掉熔断状态 */
    void setEmbedderForTesting(Embedder e) {
        this.embedder = e;
        resetSemanticBreaker();
    }

    /**
     * 挑一个真正能做 embedding 的端点。
     *
     * <p>优先当前对话模型所在端点（同一个 key 最省事）；它没声明 embedding 模型时，
     * 在已启用的端点里找一个声明了的——这样"DeepSeek 聊天 + 百炼/硅基流动向量"这种组合也能用，
     * 不必为了向量把主力模型换掉。都没有返回 null（调用方降级为纯关键词）。
     */
    private OnlineModelManager.OnlineModelConfig pickEmbeddingConfig(OnlineModelManager omm) {
        OnlineModelManager.OnlineModelConfig active = omm.getActiveModel();
        if (canEmbed(active)) return active;
        java.util.List<OnlineModelManager.OnlineModelConfig> all = omm.getEnabledModels();
        if (all != null) {
            for (OnlineModelManager.OnlineModelConfig cfg : all) {
                if (canEmbed(cfg)) return cfg;
            }
        }
        return null;
    }

    /** 该端点能否算向量：有地址、有 key、且服务商配置表声明了 embedding 模型 */
    private static boolean canEmbed(OnlineModelManager.OnlineModelConfig cfg) {
        if (cfg == null || isBlank(cfg.apiUrl) || isBlank(cfg.apiKey)) return false;
        try {
            return !isBlank(ProviderConfigManager.get().getServiceModel(cfg.apiUrl, "embedding"));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 向量路当前是否可用：熔断期内一律 false，调用方直接走关键词（不报错、不等待） */
    private boolean semanticHealthy() {
        return System.currentTimeMillis() >= semanticCooldownUntil;
    }

    /**
     * 记一次向量调用失败：连续失败达到阈值就停用一段时间。
     *
     * <p>目的是"静默不启用"：模型不支持 / 端点挂了 / key 失效时，不能让每次检索都白等一次超时，
     * 更不能把异常抛给主 agent。停用期间检索结果完全等同纯关键词，只是少了语义扩召回。</p>
     */
    private void noteSemanticFailure() {
        int n = ++semanticFailStreak;
        long now = System.currentTimeMillis();
        if (n >= SEMANTIC_FAIL_THRESHOLD && now >= semanticCooldownUntil) {
            semanticCooldownUntil = now + SEMANTIC_COOLDOWN_MS;
            Log.w(TAG, "向量召回连续失败 " + n + " 次，静默停用 "
                    + (SEMANTIC_COOLDOWN_MS / 60000) + " 分钟（期间只用关键词检索，不影响结果返回）");
        }
    }

    /** 向量调用成功：清掉失败计数与冷却，下次照常启用 */
    private void noteSemanticSuccess() {
        semanticFailStreak = 0;
        semanticCooldownUntil = 0L;
    }

    private void resetSemanticBreaker() {
        semanticFailStreak = 0;
        semanticCooldownUntil = 0L;
    }

    /**
     * 当前可用的向量化实现：注入的优先，其次本地 ONNX 模型，最后走在线 embedding。
     *
     * <p>本地优先的理由：零成本、离线、不依赖任何 key 与服务商能力；单条 300 字在手机上约几十毫秒，
     * 比一次在线调用更稳。文件不在时 {@code isAvailable()} 为 false，直接跳过，连加载都不尝试。</p>
     *
     * <p>配置不全（无 apiUrl/apiKey/embedding 模型）且本地也没有时返回 null，调用方据此降级为纯关键词——
     * "没配"不是错误，不能因此报错或返回空结果。</p>
     */
    private Embedder currentEmbedder() {
        Embedder injected = embedder;
        if (injected != null) return injected;

        com.oilquiz.app.ai.embedding.LocalEmbeddingModel local = localEmbedding();
        if (local.isAvailable()) {
            return local::embed;
        }
        try {
            OnlineModelManager omm = OnlineModelManager.getInstance(context);
            if (omm == null) return null;
            OnlineModelManager.OnlineModelConfig cfg = pickEmbeddingConfig(omm);
            if (cfg == null) return null;
            OnlineInferenceService ois = OnlineInferenceService.getInstance(context);
            if (ois == null) return null;
            return text -> {
                List<Float> vec = ois.generateEmbeddingAsync(text, cfg)
                        .get(EMBEDDING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (vec == null || vec.isEmpty()) return null;
                float[] out = new float[vec.size()];
                for (int i = 0; i < out.length; i++) out[i] = vec.get(i);
                return out;
            };
        } catch (Exception e) {
            Log.w(TAG, "取向量化实现失败，降级关键词检索: " + e.getMessage());
            return null;
        }
    }

    /** 当前向量的模型标识（本地固定为 local|模型名；在线为 apiUrl + 模型名）；都不可用返回 null */
    private String embeddingModelTag() {
        if (localEmbedding().isAvailable()) {
            return "local|" + com.oilquiz.app.ai.embedding.LocalEmbeddingModel.MODEL_ID;
        }
        try {
            OnlineModelManager omm = OnlineModelManager.getInstance(context);
            if (omm == null) return null;
            OnlineModelManager.OnlineModelConfig cfg = pickEmbeddingConfig(omm);
            if (cfg == null) return null;
            String model = ProviderConfigManager.get().getServiceModel(cfg.apiUrl, "embedding");
            if (isBlank(model)) return null;
            return cfg.apiUrl + "|" + model;
        } catch (Throwable t) {
            return null;
        }
    }

    /** float32 小端打包（1 万块 × 512 维 ≈ 20 MB，比 JSON 文本省一个数量级） */
    private static byte[] packVector(float[] vec) {
        if (vec == null || vec.length == 0) return null;
        ByteBuffer buf = ByteBuffer.allocate(vec.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : vec) buf.putFloat(v);
        return buf.array();
    }

    private static float[] unpackVector(byte[] bytes) {
        if (bytes == null || bytes.length < 4 || bytes.length % 4 != 0) return null;
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[bytes.length / 4];
        for (int i = 0; i < out.length; i++) out[i] = buf.getFloat();
        return out;
    }

    /** 余弦相似度（float 版）；维度不一致（换过模型、旧向量没重建）返回 -1，排到最后 */
    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) return -1;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) return -1;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** 读 kb_meta 字符串标记（表缺失/键缺失返回 null） */
    private static String metaString(SQLiteDatabase db, String key) {
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT v FROM kb_meta WHERE k = ?", new String[]{key});
            if (cursor.moveToFirst()) return cursor.getString(0);
        } catch (Throwable ignored) {
            // 老库没有 kb_meta 表
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    private static void metaSet(SQLiteDatabase db, String key, String value) {
        db.execSQL("INSERT OR REPLACE INTO kb_meta(k, v) VALUES(?, ?)", new Object[]{key, value});
    }

    /**
     * 为还没向量化的块补算向量（后台调用、分批、可续跑）。
     *
     * <p>为什么不在写入时算：addBatch 是导入路径，几百块 × 一次在线调用会把导入从秒级拖成分钟级。
     * 这里每次最多 {@link #EMBED_BACKFILL_BATCH} 块，断了下次接着扫 {@code embedding IS NULL}。</p>
     *
     * @param max 本次处理上限（<=0 用默认批大小）
     * @return 本次成功写入的条数（0 = 没有待补块 / 向量化不可用）
     */
    public int backfillEmbeddings(int max) {
        Embedder emb = currentEmbedder();
        if (emb == null) return 0;
        if (!semanticHealthy()) return 0;   // 已熔断：不再打 embedding 接口，等冷却结束

        // 模型换了（维度可能不同）：旧向量整批作废重建。判断放在读取之前
        String modelTag = embeddingModelTag();
        if (modelTag != null) {
            try {
                SQLiteDatabase db = getWritableDb();
                String old = metaString(db, META_EMBEDDING_MODEL);
                if (!modelTag.equals(old)) {
                    db.execSQL("UPDATE kb_chunks SET embedding = NULL");
                    metaSet(db, META_EMBEDDING_MODEL, modelTag);
                    Log.i(TAG, "embedding 模型变更，旧向量已作废待重建: " + modelTag);
                }
            } catch (Exception e) {
                Log.w(TAG, "检查 embedding 模型标记失败: " + e.getMessage());
            }
        }

        int limit = max <= 0 ? EMBED_BACKFILL_BATCH : Math.min(max, EMBED_BACKFILL_BATCH);
        List<Long> ids = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        Cursor cursor = null;
        try {
            SQLiteDatabase db = getReadableDb();
            cursor = db.rawQuery("SELECT id, title, content FROM kb_chunks WHERE embedding IS NULL LIMIT ?",
                    new String[]{String.valueOf(limit)});
            while (cursor.moveToNext()) {
                ids.add(cursor.getLong(0));
                String title = safe(cursor.getString(1));
                String content = safe(cursor.getString(2));
                String text = content.isEmpty() ? title : title + " " + content;
                texts.add(text.length() > EMBED_CONTENT_MAX ? text.substring(0, EMBED_CONTENT_MAX) : text);
            }
        } catch (Exception e) {
            Log.w(TAG, "取待向量化块失败: " + e.getMessage());
            return 0;
        } finally {
            if (cursor != null) cursor.close();
        }
        if (ids.isEmpty()) return 0;

        int done = 0;
        for (int i = 0; i < ids.size(); i++) {
            if (!semanticHealthy()) break;   // 本批已触发熔断：剩下的留到冷却之后再补
            try {
                byte[] packed = packVector(emb.embed(texts.get(i)));
                if (packed == null) {
                    noteSemanticFailure();
                    continue;
                }
                ContentValues cv = new ContentValues();
                cv.put("embedding", packed);
                getWritableDb().update("kb_chunks", cv, "id = ?", new String[]{String.valueOf(ids.get(i))});
                done++;
                noteSemanticSuccess();
            } catch (Throwable t) {
                // 单条失败不拖垮整批（限流 / 单条超长都只影响自己）；连续失败会触发熔断
                noteSemanticFailure();
                Log.w(TAG, "块 " + ids.get(i) + " 向量化失败: " + t.getMessage());
            }
        }
        return done;
    }

    /** 导入后自动补向量的后台线程（单线程：避免并发把 embedding 限额打爆） */
    private static final ExecutorService EMBED_WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "kb-embed-backfill");
        t.setDaemon(true);
        return t;
    });

    /** 测试可关掉自动回填，避免后台线程干扰断言 */
    private volatile boolean autoBackfill = true;

    void setAutoBackfillForTesting(boolean enabled) {
        this.autoBackfill = enabled;
    }

    /**
     * 补齐所有没有向量的块（同步，最多 {@link #EMBED_MAX_ROUNDS} 批）。
     *
     * <p>给"工具显式触发 / 老库首次接入 embedding 模型"用；会消耗在线额度。</p>
     */
    public int backfillAllEmbeddings() {
        int total = 0;
        for (int i = 0; i < EMBED_MAX_ROUNDS; i++) {
            int n = backfillEmbeddings(EMBED_BACKFILL_BATCH);
            if (n <= 0) break;   // 没待补块，或这一批全失败（不无限重试）
            total += n;
        }
        return total;
    }

    /** 写入成功后异步补向量；没配 embedding 时立刻返回，不产生任何后台任务 */
    private void scheduleBackfill() {
        if (!autoBackfill) return;
        try {
            if (currentEmbedder() == null) return;
        } catch (Throwable t) {
            return;
        }
        EMBED_WORKER.submit(() -> {
            try {
                backfillAllEmbeddings();
            } catch (Throwable t) {
                Log.w(TAG, "后台向量回填中断: " + t.getMessage());
            }
        });
    }

    /** 已有向量的块数（按分类条件）；0 表示"还没回填/没配模型"，此时不能走向量召回 */
    public synchronized int countEmbeddings(String category) {
        Cursor cursor = null;
        try {
            SQLiteDatabase db = getReadableDb();
            StringBuilder where = new StringBuilder("embedding IS NOT NULL");
            List<String> args = new ArrayList<>();
            if (category != null && !category.trim().isEmpty()) {
                where.append(" AND category = ?");
                args.add(category.trim());
            } else {
                where.append(" AND category <> 'tool_defs'");
            }
            cursor = db.rawQuery("SELECT COUNT(*) FROM kb_chunks WHERE " + where, args.toArray(new String[0]));
            if (cursor.moveToFirst()) return cursor.getInt(0);
        } catch (Exception e) {
            Log.w(TAG, "统计向量条数失败: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
        return 0;
    }

    /**
     * 语义召回（本地向量余弦）：不依赖关键词命中，能捞回同义 / 改写 / 口语化问法。
     *
     * <p>成本：查询侧只算 1 次 query 向量，其余全是本地点积（向量在写入侧就算好存库）。
     * 不可用时（没配模型 / 还没回填 / 调用失败）返回空数组——调用方必须保留关键词结果，
     * 不能因为向量路失败把"本来就找得到的东西"也弄丢。</p>
     */
    public JSONArray semanticSearch(String query, String category, int topK) {
        JSONArray empty = new JSONArray();
        if (query == null || query.trim().isEmpty()) return empty;
        if (!semanticHealthy()) return empty;
        int limit = topK <= 0 ? DEFAULT_TOP_K : Math.min(topK, MAX_TOP_K);
        Embedder emb = currentEmbedder();
        if (emb == null) return empty;

        float[] qVec;
        try {
            qVec = emb.embed(query.trim());
        } catch (Throwable t) {
            noteSemanticFailure();
            Log.w(TAG, "query 向量化失败，本次只用关键词召回: " + t.getMessage());
            return empty;
        }
        if (qVec == null || qVec.length == 0) {
            noteSemanticFailure();
            return empty;
        }
        noteSemanticSuccess();

        List<String> tokens = tokenizeQuery(query);
        Map<Long, Double> scoreById = new LinkedHashMap<>();
        List<Long> ids = new ArrayList<>();
        Cursor cursor = null;
        try {
            SQLiteDatabase db = getReadableDb();
            StringBuilder where = new StringBuilder("embedding IS NOT NULL");
            List<String> args = new ArrayList<>();
            if (category != null && !category.trim().isEmpty()) {
                where.append(" AND category = ?");
                args.add(category.trim());
            } else {
                where.append(" AND category <> 'tool_defs'");
            }
            cursor = db.rawQuery("SELECT id, embedding FROM kb_chunks WHERE " + where,
                    args.toArray(new String[0]));
            while (cursor.moveToNext()) {
                double sim = cosine(qVec, unpackVector(cursor.getBlob(1)));
                if (sim < SEMANTIC_MIN_COS) continue;   // 余弦太低的直接丢，别用向量灌噪声
                ids.add(cursor.getLong(0));
                scoreById.put(cursor.getLong(0), sim);
            }
        } catch (Exception e) {
            Log.w(TAG, "语义召回失败，降级关键词: " + e.getMessage());
            return empty;
        } finally {
            if (cursor != null) cursor.close();
        }
        if (ids.isEmpty()) return empty;

        ids.sort((a, b) -> Double.compare(scoreById.get(b), scoreById.get(a)));
        List<Long> topIds = ids.subList(0, Math.min(limit, ids.size()));

        JSONArray out = new JSONArray();
        Cursor cursor2 = null;
        try {
            SQLiteDatabase db = getReadableDb();
            StringBuilder in = new StringBuilder("id IN (");
            List<String> idArgs = new ArrayList<>();
            for (int i = 0; i < topIds.size(); i++) {
                if (i > 0) in.append(',');
                in.append('?');
                idArgs.add(String.valueOf(topIds.get(i)));
            }
            in.append(')');
            cursor2 = db.rawQuery("SELECT id, title, category, keywords, source, updated_at, content, doc_id, chunk_seq "
                    + "FROM kb_chunks WHERE " + in, idArgs.toArray(new String[0]));
            Map<Long, JSONObject> fetched = new LinkedHashMap<>();
            while (cursor2.moveToNext()) {
                JSONObject row = rowFromCursor(cursor2, tokens, 0, 0);
                long id = cursor2.getLong(0);
                double sim = scoreById.containsKey(id) ? scoreById.get(id) : 0;
                row.put("semantic_score", Math.round(sim * 100) / 100.0);
                fetched.put(id, row);
            }
            for (Long id : topIds) {
                JSONObject row = fetched.get(id);
                if (row != null) out.put(row);
            }
        } catch (Exception e) {
            Log.w(TAG, "语义召回回表失败: " + e.getMessage());
        } finally {
            if (cursor2 != null) cursor2.close();
        }
        return out;
    }

    /**
     * 混合召回：关键词（OR + 覆盖率）+ 向量（本地余弦），RRF 融合。
     *
     * <p>为什么用 RRF 而不是直接加分数：两路分数量纲不同（覆盖率 0~1、余弦 -1~1），
     * 按排名倒数相加不用调参；同一块两路都靠前会自然上浮，向量独有命中也能进榜。</p>
     */
    private JSONArray hybridSearch(String query, String category, String tag, int limit) {
        int wide = Math.min(MAX_TOP_K, Math.max(limit, limit * Math.max(1, SEMANTIC_RECALL_FACTOR)));
        JSONArray kw = searchInternal(query, category, tag, wide);
        JSONArray sem = isBlank(tag) ? semanticSearch(query, category, wide) : new JSONArray();

        Map<Long, Double> fused = new LinkedHashMap<>();
        Map<Long, JSONObject> rows = new LinkedHashMap<>();
        for (int i = 0; i < kw.length(); i++) {
            JSONObject o = kw.optJSONObject(i);
            if (o == null) continue;
            long id = o.optLong("id", -1);
            if (id < 0) continue;
            fused.put(id, (fused.containsKey(id) ? fused.get(id) : 0) + 1.0 / (RRF_K + i + 1));
            rows.put(id, o);
        }
        for (int i = 0; i < sem.length(); i++) {
            JSONObject o = sem.optJSONObject(i);
            if (o == null) continue;
            long id = o.optLong("id", -1);
            if (id < 0) continue;
            fused.put(id, (fused.containsKey(id) ? fused.get(id) : 0) + 1.0 / (RRF_K + i + 1));
            if (!rows.containsKey(id)) rows.put(id, o);   // 关键词那版带 snippet/coverage，优先保留
        }

        List<Long> order = new ArrayList<>(fused.keySet());
        order.sort((a, b) -> Double.compare(fused.get(b), fused.get(a)));
        JSONArray out = new JSONArray();
        for (int i = 0; i < order.size() && i < limit; i++) {
            JSONObject o = rows.get(order.get(i));
            if (o == null) continue;
            try {
                o.put("rrf_score", Math.round(fused.get(order.get(i)) * 1000) / 1000.0);
            } catch (JSONException ignored) {
                // put 失败不影响结果本体
            }
            out.put(o);
        }
        return out;
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
        if (!semanticHealthy()) return bm25Results;   // 熔断期内不做在线重排，直接原序返回
        try {
            OnlineModelManager omm = OnlineModelManager.getInstance(context);
            if (omm == null) return bm25Results;
            OnlineModelManager.OnlineModelConfig cfg = pickEmbeddingConfig(omm);
            if (cfg == null) return bm25Results;
            ProviderConfigManager pcm = ProviderConfigManager.get();
            String embModel = pcm.getServiceModel(cfg.apiUrl, "embedding");
            if (isBlank(embModel)) return bm25Results; // 配置表未声明 embedding 模型 → 不语义重排
            OnlineInferenceService ois = OnlineInferenceService.getInstance(context);
            if (ois == null) return bm25Results;

            int n = Math.min(bm25Results.length(), SEMANTIC_CANDIDATES);
            if (n < 2) return bm25Results;

            // 1) query 向量（超时兜底，失败降级；连续失败会熔断，之后不再进入本函数）
            List<Float> qVec = ois.generateEmbeddingAsync(query, cfg)
                    .get(EMBEDDING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (qVec == null || qVec.isEmpty()) {
                noteSemanticFailure();
                return bm25Results;
            }
            noteSemanticSuccess();

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
        long insertedId = -1;
        try {
            insertedId = db.insertOrThrow("kb_chunks", null, chunkValues(chunk, now));
            db.setTransactionSuccessful();
        } catch (Exception e) {
            lastError = e.getMessage();
            Log.e(TAG, "addChunk 失败: " + e.getMessage(), e);
        } finally {
            db.endTransaction();
        }
        if (insertedId > 0) {
            scheduleBackfill();
        }
        return insertedId;
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
        boolean committed = false;
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
            committed = true;
        } catch (Exception e) {
            lastError = e.getMessage();
            Log.e(TAG, "addBatch 失败: " + e.getMessage(), e);
        } finally {
            db.endTransaction();
        }
        // 事务没提交成功就是整体回滚：此时返回回滚前的计数等于骗人（"失败但加了 37 条"）
        if (committed && added > 0) {
            scheduleBackfill();
        }
        return committed ? added : 0;
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
                return importChunks(new JSONArray(trimmed));
            }
            JSONObject root = new JSONObject(trimmed);
            JSONArray chunks = root.optJSONArray("chunks");
            if (chunks == null) {
                lastError = "JSON 中缺少 chunks 数组（也不支持顶层对象格式）";
                return 0;
            }
            return importChunks(chunks);
        } catch (JSONException e) {
            lastError = "JSON 解析失败: " + e.getMessage();
            Log.e(TAG, "importJson 解析失败: " + e.getMessage());
            return 0;
        }
    }

    /**
     * 导入一组块（v5）：带 {@code source} 的按来源聚成一篇文档写入（保留文档层与块序），
     * 没有 {@code source} 的当手工条目（{@code doc_id=0}）。
     *
     * <p>为什么按 source 分组：Python 预处理脚本和 {@code export} 产出的 JSON 都是"一篇文档多个块、
     * 同 source"，逐块 addBatch 会把文档层丢掉——导回来的库没有文档身份，去重和溯源就都失效了。</p>
     */
    private int importChunks(JSONArray items) {
        if (items == null) {
            return 0;
        }
        Map<String, List<KnowledgeChunk>> bySource = new LinkedHashMap<>();
        List<KnowledgeChunk> loose = new ArrayList<>();
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
            if (chunk.source.isEmpty()) {
                loose.add(chunk);
                continue;
            }
            List<KnowledgeChunk> list = bySource.get(chunk.source);
            if (list == null) {
                list = new ArrayList<>();
                bySource.put(chunk.source, list);
            }
            list.add(chunk);
        }
        int added = 0;
        for (Map.Entry<String, List<KnowledgeChunk>> entry : bySource.entrySet()) {
            List<KnowledgeChunk> chunks = entry.getValue();
            JSONObject result = storeChunks(chunks, chunks.get(0).title, entry.getKey(), false);
            if (result.optBoolean("success")) {
                added += result.optInt("added");
            }
        }
        if (!loose.isEmpty()) {
            // 手工条目（无 source）回到 addBatch：它们不属于任何文档，doc_id 应为 0
            JSONArray looseJson = new JSONArray();
            for (KnowledgeChunk chunk : loose) {
                JSONObject obj = new JSONObject();
                try {
                    obj.put("title", chunk.title);
                    obj.put("category", chunk.category);
                    obj.put("keywords", chunk.keywords);
                    obj.put("content", chunk.content);
                    obj.put("source", chunk.source);
                } catch (JSONException ignored) {
                    // 普通字符串不会触发
                }
                looseJson.put(obj);
            }
            added += addBatch(looseJson);
        }
        return added;
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
                        + "请先在「模型」页的「语音模型」里为语音识别选择模型（如 qwen3-asr-flash / whisper-1），"
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

    /** 用户是否在「语音模型（识别 / 合成）」里显式选择了本地 SenseVoice（→ 识别本地优先），与 VoiceInputTool.isLocalAsrSelected 一致 */
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

    /**
     * 切块结果统一入库（事务），返回结果 JSON。
     *
     * <p>包内可见（非 private）：单测要直接喂 {@link KnowledgeChunk} 覆盖"重复导入/冲突"分支，
     * 走 {@code importDocument} 会连带依赖 FileContentExtractor（Robolectric 下不可靠）。</p>
     */
    JSONObject storeChunks(List<KnowledgeChunk> chunks, String docTitle, String filePath) {
        return storeChunks(chunks, docTitle, filePath, true);
    }

    /**
     * @param dedup 是否做"同一来源重复导入"检查。恢复备份（{@code import_file}）要传 false：
     *              那是用户显式要求的写入，不该因为库里已有同 source 就报冲突
     */
    JSONObject storeChunks(List<KnowledgeChunk> chunks, String docTitle, String filePath, boolean dedup) {
        JSONObject result = new JSONObject();
        int added = 0;
        long now = System.currentTimeMillis();
        String source = filePath == null ? "" : filePath.trim();

        // 重复导入检查（2026-11-10，v5 起走文档层）：同一个 source 已经入过库时
        //   · 内容哈希一致 → 跳过（避免"同一文件两次导入 = 库里两份"）
        //   · 内容不同     → 报冲突，要求先显式 delete(source=…) 再导（不静默删用户数据）
        // 哈希从 kb_documents.doc_hash 直接读（O(1)），不再像 v4 那样每次把全篇正文捞出来现算。
        String newHash = contentHash(chunks);
        if (dedup && !source.isEmpty()) {
            DocState existing = findDocument(source);
            if (existing.count <= 0) {
                // 兼容：文档层尚未回填/回填失败（只有块、没有文档身份）时，回退到按块现算
                SourceState legacy = inspectSource(source);
                existing.count = legacy.count;
                existing.hash = legacy.hash;
            }
            if (existing.count > 0) {
                if (newHash.equals(existing.hash)) {
                    try {
                        result.put("success", true);
                        result.put("added", 0);
                        result.put("skipped", true);
                        result.put("existing", existing.count);
                        result.put("title", docTitle);
                        result.put("source", filePath);
                        result.put("message", "该文件内容未变化，已跳过重复导入（库内已有 " + existing.count + " 块）");
                    } catch (JSONException ignored) {
                        // 普通字符串不会触发
                    }
                    return result;
                }
                try {
                    result.put("success", false);
                    result.put("conflict", true);
                    result.put("existing", existing.count);
                    result.put("title", docTitle);
                    result.put("source", filePath);
                    result.put("message", "该来源已导入过（" + existing.count + " 块）且内容与本次不同。"
                            + "如需用新版本替换，请先 knowledge_base(action=delete, source=…) 再重新导入；"
                            + "直接导入会在库里留下新旧两份");
                } catch (JSONException ignored) {
                    // 普通字符串不会触发
                }
                return result;
            }
        }

        try {
            SQLiteDatabase db = getWritableDb();
            db.beginTransaction();
            boolean committed = false;
            try {
                // 恢复备份（dedup=false）语义是"覆盖"：先清掉同 source 的旧块，
                // 否则 upsert 文档记录会把块数写小，而旧块还留在库里（数量和内容都对不上）
                if (!dedup && !source.isEmpty()) {
                    db.delete("kb_chunks", "source = ?", new String[]{source});
                }
                // 文档层先建（一篇一行），块再带 doc_id + 序号写入——
                // 这样"第几块"有了持久身份，去重也不必再扫全篇
                long docId = 0;
                if (!source.isEmpty()) {
                    String category = chunks.isEmpty() ? "general" : chunks.get(0).category;
                    docId = upsertDocument(db, docTitle, category, source, newHash, chunks.size(), now, now);
                }
                for (int i = 0; i < chunks.size(); i++) {
                    KnowledgeChunk chunk = chunks.get(i);
                    chunk.docId = docId;
                    chunk.chunkSeq = docId > 0 ? i + 1 : 0;
                    db.insertOrThrow("kb_chunks", null, chunkValues(chunk, now));
                    added++;
                }
                db.setTransactionSuccessful();
                committed = true;
            } finally {
                db.endTransaction();
            }
            if (!committed) {
                added = 0;
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
            if (!source.isEmpty()) {
                result.put("doc_id", findDocument(source).docId);
            }
            if (chunks.isEmpty()) {
                result.put("message", "未提取到可入库的文本内容（图片多为OCR未识别、音频多为无语音或过短）");
            }
        } catch (JSONException ignored) {
            // 正常字符串不会触发
        }
        if (added > 0) {
            scheduleBackfill();
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

    // ==================== 按来源去重（2026-11-10） ====================

    /** 某个来源当前在库里的状态：块数 + 内容哈希 */
    private static final class SourceState {
        int count;
        String hash = "";
    }

    /**
     * 读某个 source 的既有内容并算哈希，用于判断"这个文件是不是已经导过"。
     *
     * <p>不额外加 doc_hash 列：加列要抬 DB_VERSION、老数据还得回填一遍，
     * 而按 source 把正文拼起来现算哈希对这个量级（一篇文档几十块）完全够用。</p>
     */
    private SourceState inspectSource(String source) {
        SourceState state = new SourceState();
        if (source == null || source.trim().isEmpty()) {
            return state;
        }
        Cursor cursor = null;
        try {
            SQLiteDatabase db = getReadableDb();
            cursor = db.rawQuery("SELECT content FROM kb_chunks WHERE source = ? ORDER BY id",
                    new String[]{source});
            StringBuilder sb = new StringBuilder();
            while (cursor.moveToNext()) {
                state.count++;
                sb.append(cursor.getString(0) == null ? "" : cursor.getString(0)).append('\n');
            }
            state.hash = md5(sb.toString());
        } catch (Exception e) {
            // 查不出来不该阻断导入：退化成"当作没导过"
            Log.w(TAG, "检查重复导入失败: " + e.getMessage());
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return state;
    }

    /** 待入库块的正文哈希（与 {@link #inspectSource} 同口径拼接） */
    private static String contentHash(List<KnowledgeChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (KnowledgeChunk chunk : chunks) {
            sb.append(chunk.content == null ? "" : chunk.content).append('\n');
        }
        return md5(sb.toString());
    }

    private static String md5(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // MD5 一定存在；拿不到就退化成空串（调用方按"内容不同"处理，不会误跳）
            return "";
        }
    }

    // ==================== 文本切块（与 Python 预处理脚本逻辑对齐） ====================

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^#{1,6}\\s+.*$");
    private static final Pattern CJK_WORD_PATTERN = Pattern.compile("[\\u4e00-\\u9fff]{2,}");
    private static final Pattern EN_WORD_PATTERN = Pattern.compile("[a-zA-Z][a-zA-Z0-9]{1,}");

    /**
     * 把整篇文本按 Markdown 标题层级切块：
     * 标题路径（用 " - " 连接）作为块标题；无标题时用文档名；正文按空行分段，超长再硬切。
     */
    static List<KnowledgeChunk> chunkText(String text, String docTitle, String category, String source) {
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
        mergeTinyChunks(chunks);
        return chunks;
    }

    /**
     * 小块归并（2026-11-10）：把不足 {@link #MIN_CHUNK_CHARS} 的非首块并进前一块。
     *
     * <p>"标题下面先一句结论、再挂三条证据"这种结构，切块器会在子标题处 flush，
     * 结论句独立成一块、证据落到下一块；检索命中前者只返回没有下文的半句话。
     * 归并后合并长度超过 {@link #MAX_MERGED_CHARS} 则保持独立——宁碎不串味。</p>
     */
    private static void mergeTinyChunks(List<KnowledgeChunk> chunks) {
        for (int i = chunks.size() - 1; i >= 1; i--) {
            KnowledgeChunk cur = chunks.get(i);
            KnowledgeChunk prev = chunks.get(i - 1);
            String body = cur.content == null ? "" : cur.content;
            if (body.isEmpty() || body.length() >= MIN_CHUNK_CHARS) {
                continue;
            }
            String head = prev.content == null ? "" : prev.content;
            if (head.length() + body.length() + 1 > MAX_MERGED_CHARS) {
                continue;
            }
            prev.content = head.isEmpty() ? body : head + "\n" + body;
            chunks.remove(i);
        }
        // 首块也可能短（上级标题本身没正文，结论句直接落成了第 1 块）：并入后一块，标题沿用后一块的
        if (chunks.size() >= 2) {
            String body = chunks.get(0).content == null ? "" : chunks.get(0).content;
            if (!body.isEmpty() && body.length() < MIN_CHUNK_CHARS) {
                KnowledgeChunk next = chunks.get(1);
                String head = next.content == null ? "" : next.content;
                if (head.length() + body.length() + 1 <= MAX_MERGED_CHARS) {
                    next.content = body + "\n" + head;
                    chunks.remove(0);
                }
            }
        }
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
     * 按 id 更新一条知识（局部更新：传 null 或空串的字段保持原值）。
     *
     * <p>为什么需要：从前只有 add/delete，用户想改一条已入库的知识（错别字、过期结论、
     * 分类放错）只能先删再重加——id 会变、created_at 丢失，文档导入的多块知识还会被拆散。
     * 这里保持 id 不变，只刷新给定字段 + {@code search_text} + {@code updated_at}。</p>
     *
     * <p>合并后标题与内容不能同时为空（与 {@link #addChunk} 同一口径），否则拒绝并给出原因。</p>
     *
     * @return true=更新成功；false=未找到/校验失败/数据库错误（原因见 {@link #getLastError()}）
     */
    public synchronized boolean updateChunk(long id, String title, String category, String keywords,
                                            String content, String source) {
        lastError = null;
        if (id <= 0) {
            lastError = "id 无效: " + id;
            return false;
        }
        SQLiteDatabase db;
        try {
            db = getWritableDb();
        } catch (Exception e) {
            lastError = "知识库数据库不可用: " + e.getMessage();
            Log.e(TAG, "updateChunk 打开数据库失败: " + e.getMessage(), e);
            return false;
        }
        String oldTitle;
        String oldCategory;
        String oldKeywords;
        String oldContent;
        String oldSource;
        long oldDocId = 0;
        Cursor cursor = null;
        try {
            cursor = db.rawQuery(
                    "SELECT title, category, keywords, content, source, doc_id FROM kb_chunks WHERE id = ?",
                    new String[]{String.valueOf(id)});
            if (!cursor.moveToFirst()) {
                lastError = "未找到 id=" + id + " 的知识";
                return false;
            }
            oldTitle = cursor.getString(0);
            oldCategory = cursor.getString(1);
            oldKeywords = cursor.getString(2);
            oldContent = cursor.getString(3);
            oldSource = cursor.getString(4);
            oldDocId = cursor.getLong(5);
        } catch (Exception e) {
            lastError = "读取原记录失败: " + e.getMessage();
            Log.e(TAG, "updateChunk 读取原记录失败: " + e.getMessage(), e);
            return false;
        } finally {
            if (cursor != null) cursor.close();
        }

        // 局部更新：null/空白一律视为"不改这一项"（模型经常把不动的字段填成 ""）
        KnowledgeChunk merged = new KnowledgeChunk(
                keepIfBlank(title, oldTitle),
                keepIfBlank(category, oldCategory),
                keepIfBlank(keywords, oldKeywords),
                keepIfBlank(content, oldContent),
                keepIfBlank(source, oldSource));
        if (merged.title.isEmpty() && merged.content.isEmpty()) {
            lastError = "标题与内容不能同时为空";
            return false;
        }
        if (merged.content.isEmpty()) {
            merged.content = merged.title;
        }
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("title", merged.title);
        values.put("category", merged.category);
        values.put("keywords", merged.keywords);
        values.put("content", merged.content);
        values.put("source", merged.source);
        values.put("search_text", buildSearchText(merged));
        values.put("updated_at", now);
        // v5：改了 source 等于"这块换了归属文档"，得重新挂靠，否则会挂在旧文档下、把旧文档的哈希算错
        long targetDocId = oldDocId;
        String newSource = merged.source == null ? "" : merged.source.trim();
        String prevSource = oldSource == null ? "" : oldSource.trim();
        boolean sourceChanged = !newSource.equals(prevSource);
        if (sourceChanged) {
            if (newSource.isEmpty()) {
                values.put("doc_id", 0);
                values.put("chunk_seq", 0);
                targetDocId = 0;
            } else {
                targetDocId = upsertDocument(db, merged.title, merged.category, newSource, "", 1, now, now);
                values.put("doc_id", targetDocId);
                values.put("chunk_seq", nextChunkSeq(db, targetDocId));
            }
        }
        db.beginTransaction();
        try {
            int rows = db.update("kb_chunks", values, "id = ?", new String[]{String.valueOf(id)});
            db.setTransactionSuccessful();
            if (rows <= 0) {
                lastError = "未找到 id=" + id + " 的知识";
                return false;
            }
        } catch (Exception e) {
            lastError = e.getMessage();
            Log.e(TAG, "updateChunk 失败: " + e.getMessage(), e);
            return false;
        } finally {
            db.endTransaction();
        }
        // 事务外重算文档哈希/块数（内容或归属变了，去重依据必须跟着变）
        refreshDocument(oldDocId);
        if (targetDocId != oldDocId) {
            refreshDocument(targetDocId);
        }
        return true;
    }

    /** 文档内下一个块序号（空文档从 1 开始） */
    private static int nextChunkSeq(SQLiteDatabase db, long docId) {
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT COALESCE(MAX(chunk_seq), 0) + 1 FROM kb_chunks WHERE doc_id = ?",
                    new String[]{String.valueOf(docId)});
            return cursor.moveToFirst() ? cursor.getInt(0) : 1;
        } catch (Exception e) {
            return 1;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /** 空白保留原值（局部更新语义）；非空则用新值 */
    private static String keepIfBlank(String incoming, String old) {
        if (incoming == null || incoming.trim().isEmpty()) {
            return old == null ? "" : old;
        }
        return incoming.trim();
    }

    /**
     * 按标题精确定位唯一一条知识的 id（给"按标题更新"用）。
     *
     * @return 命中唯一一条时返回其 id；没有命中返回 -1；命中多条返回 -2（有歧义，必须改用 id）
     */
    public synchronized long findUniqueIdByTitle(String title) {
        lastError = null;
        if (title == null || title.trim().isEmpty()) {
            lastError = "标题为空";
            return -1L;
        }
        Cursor cursor = null;
        try {
            SQLiteDatabase db = getWritableDb();
            cursor = db.rawQuery("SELECT id FROM kb_chunks WHERE title = ? LIMIT 3",
                    new String[]{title.trim()});
            int n = cursor.getCount();
            if (n <= 0) {
                lastError = "未找到标题为「" + title.trim() + "」的知识";
                return -1L;
            }
            if (n > 1) {
                lastError = "标题「" + title.trim() + "」命中多条（文档导入的知识会按标题切块），"
                        + "请先 search 取得具体 id 再更新";
                return -2L;
            }
            cursor.moveToFirst();
            return cursor.getLong(0);
        } catch (Exception e) {
            lastError = "按标题查询失败: " + e.getMessage();
            Log.e(TAG, "findUniqueIdByTitle 失败: " + e.getMessage(), e);
            return -1L;
        } finally {
            if (cursor != null) cursor.close();
        }
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
            long docId = docIdOfChunk(db, id);
            db.beginTransaction();
            int rows;
            try {
                rows = db.delete("kb_chunks", "id = ?", new String[]{String.valueOf(id)});
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            if (rows > 0) {
                // 文档层要跟着变：少了一块，hash 和块数都不再对（否则下次导入被误判"没变"）
                refreshDocument(docId);
                return true;
            }
            return false;
        } catch (Exception e) {
            lastError = e.getMessage();
            Log.e(TAG, "deleteById 失败: " + e.getMessage(), e);
            return false;
        }
    }

    /** 取某个块所属的文档 id（0 = 不属于任何文档） */
    private static long docIdOfChunk(SQLiteDatabase db, long chunkId) {
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT doc_id FROM kb_chunks WHERE id = ?",
                    new String[]{String.valueOf(chunkId)});
            return cursor.moveToFirst() ? cursor.getLong(0) : 0;
        } catch (Exception e) {
            return 0;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /**
     * 按来源（通常是导入文件的绝对路径）删除整篇知识。
     *
     * <p>补的是导入侧的缺口：`import_document` 按 source 写了一整篇的块，
     * 但删除侧只有 id / title / category，改版文档没法整体替换（只能 list 之后一条条删）。</p>
     *
     * @return 删除的条数
     */
    public synchronized int deleteBySource(String source) {
        if (source == null || source.trim().isEmpty()) {
            return 0;
        }
        lastError = null;
        SQLiteDatabase db;
        try {
            db = getWritableDb();
        } catch (Exception e) {
            lastError = e.getMessage();
            Log.e(TAG, "deleteBySource 打开数据库失败: " + e.getMessage(), e);
            return 0;
        }
        db.beginTransaction();
        int deleted = 0;
        try {
            // v5：优先按文档身份删（同 source 的块 doc_id 一致），再用 source 兜底旧数据
            deleted = db.delete("kb_chunks",
                    "source = ? OR doc_id IN (SELECT doc_id FROM kb_documents WHERE source = ?)",
                    new String[]{source.trim(), source.trim()});
            db.delete("kb_documents", "source = ?", new String[]{source.trim()});
            db.setTransactionSuccessful();
        } catch (Exception e) {
            lastError = e.getMessage();
            Log.e(TAG, "deleteBySource 失败: " + e.getMessage(), e);
        } finally {
            db.endTransaction();
        }
        return deleted;
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
        List<Long> affectedDocs = new ArrayList<>();
        try {
            // 先记下受影响的文档，删完再逐篇重算（按标题删会把一篇文档删成残篇）
            Cursor cursor = db.rawQuery(
                    "SELECT DISTINCT doc_id FROM kb_chunks WHERE title = ? AND doc_id > 0",
                    new String[]{title.trim()});
            try {
                while (cursor.moveToNext()) {
                    affectedDocs.add(cursor.getLong(0));
                }
            } finally {
                cursor.close();
            }
            deleted = db.delete("kb_chunks", "title = ?", new String[]{title.trim()});
            db.setTransactionSuccessful();
        } catch (Exception e) {
            lastError = e.getMessage();
            Log.e(TAG, "deleteByTitle 失败: " + e.getMessage(), e);
        } finally {
            db.endTransaction();
        }
        for (Long docId : affectedDocs) {
            refreshDocument(docId);
        }
        return deleted;
    }

    /**
     * 按分类删除全部条目（2026-09-23：tool_defs 重建用，一次删光避免按 title 遍历
     * 因字段名/返回格式不匹配而漏删导致的重复条目残留）。
     *
     * @return 删除的条数
     */
    public synchronized int deleteByCategory(String category) {        if (category == null || category.trim().isEmpty()) {
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
            // v5：文档记录同步删，别留下没有块的孤儿文档（下次 list/去重会读到假数据）
            db.delete("kb_documents", "category = ?", new String[]{category.trim()});
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
     * 某分类的条数（2026-11-10：`ensureToolDefsInKnowledgeBase` 用它判断"是否真灌过"）。
     *
     * <p>为什么需要：入灌标记存在 SharedPreferences（`ai_agent_tooldefs/tool_defs_version`），
     * 而数据在 db。两者会错位——`clear(include_tool_defs=true)` 清掉了 tool_defs、标记还在，
     * 或者删库文件重来但 prefs 没清，都会让下次启动**以为已灌过、直接 return**，工具用法检索从此空着。
     * 所以判据必须是"标记对得上 **且** 库里真有货"。</p>
     *
     * @return 条数；查询失败返回 -1（调用方据此按"不确定"处理，仍按已灌过处理，不反复重建）
     */
    public synchronized int countByCategory(String category) {
        if (category == null || category.trim().isEmpty()) {
            return -1;
        }
        Cursor cursor = null;
        try {
            SQLiteDatabase db = getReadableDb();
            cursor = db.rawQuery("SELECT count(*) FROM kb_chunks WHERE category = ?",
                    new String[]{category.trim()});
            return cursor.moveToFirst() ? cursor.getInt(0) : -1;
        } catch (Exception e) {
            lastError = e.getMessage();
            Log.e(TAG, "countByCategory 失败: " + e.getMessage(), e);
            return -1;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /**
     * 清空知识库——**全清**，连 {@code category=tool_defs} 一起删。
     *
     * <p>2026-11-10 定稿：一度改成"默认保留 tool_defs"，后来撤掉了。理由是那条保护的唯一正当性
     * 是"清掉之后回不来"，而现在 {@code OnlineToolManager.ensureToolDefsInKnowledgeBase}
     * 会校验"版本标记对得上 **且** 库里真有 tool_defs 行"，清空后下次 Agent 初始化自动重灌，
     * 不需要抬 {@code TOOL_DEFS_VERSION}。既然能自愈，语义就该是最直白的"清空"，
     * 不该让用户/模型去判断要不要多传一个请求里的 flag。</p>
     *
     * @return 实际删除的条数
     */
    public synchronized int clear() {
        lastError = null;
        try {
            SQLiteDatabase db = getWritableDb();
            db.beginTransaction();
            try {
                int rows = db.delete("kb_chunks", null, null);
                db.delete("kb_documents", null, null);
                db.setTransactionSuccessful();
                resetSemanticBreaker();   // 库都清了，向量状态从头开始（避免带旧熔断/旧模型标记）
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

    // ==================== 导出备份（2026-11-10） ====================

    /**
     * 导出知识库为 JSON 文件，结构与 {@code import_json} 的 {@code {"chunks":[…]}} 一致，可直接导回。
     *
     * <p>补的是资产安全缺口：库里全是用户自己导入/沉淀的内容，删掉就没有源文件，
     * 但此前只有 import 没有 export。这里逐块流式写文件，不把整库拼成一个字符串（避免大库 OOM）。</p>
     *
     * @param filePath 目标文件绝对路径；null/空则写到 app 私有目录 {@code knowledge_export_<时间戳>.json}
     * @param category 只导出该分类；null/空导出全部
     * @return {"success":true,"file":..,"count":N} 或 {"success":false,"message":..}
     */
    public synchronized JSONObject exportJson(String filePath, String category) {
        lastError = null;
        Cursor cursor = null;
        try {
            SQLiteDatabase db = getReadableDb();
            File target = resolveExportFile(filePath);
            if (target == null) {
                return errorResult("无法确定导出路径：请传入可写的绝对路径");
            }
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return errorResult("无法创建导出目录: " + parent.getAbsolutePath());
            }

            boolean byCategory = category != null && !category.trim().isEmpty();
            String where = byCategory ? " WHERE category = ?" : "";
            String[] args = byCategory ? new String[]{category.trim()} : null;

            int count = 0;
            cursor = db.rawQuery("SELECT COUNT(*) FROM kb_chunks" + where, args);
            if (cursor.moveToFirst()) {
                count = cursor.getInt(0);
            }
            cursor.close();
            cursor = null;

            cursor = db.rawQuery("SELECT title, category, keywords, content, source, chunk_seq FROM kb_chunks"
                    + where + " ORDER BY id", args);
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(target), StandardCharsets.UTF_8));
            try {
                writer.write("{\"version\":1,\"exported_at\":" + System.currentTimeMillis()
                        + ",\"count\":" + count + ",\"chunks\":[");
                int written = 0;
                while (cursor.moveToNext()) {
                    if (written > 0) {
                        writer.write(',');
                    }
                    JSONObject item = new JSONObject();
                    item.put("title", cursor.getString(0) == null ? "" : cursor.getString(0));
                    item.put("category", cursor.getString(1) == null ? "" : cursor.getString(1));
                    item.put("keywords", cursor.getString(2) == null ? "" : cursor.getString(2));
                    item.put("content", cursor.getString(3) == null ? "" : cursor.getString(3));
                    item.put("source", cursor.getString(4) == null ? "" : cursor.getString(4));
                    // v5：把块序一起导出，导回来才能还原"第几块"（文件按 id 排序，即建库时的块序）
                    item.put("chunk_seq", cursor.getInt(5));
                    writer.write(item.toString());
                    written++;
                }
                writer.write("]}");
            } finally {
                writer.close();
            }

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("file", target.getAbsolutePath());
            result.put("count", count);
            if (byCategory) {
                result.put("category", category.trim());
            }
            if (count == 0) {
                result.put("message", "该范围内没有内容，导出的是空文件");
            }
            return result;
        } catch (Exception e) {
            lastError = e.getMessage();
            Log.e(TAG, "exportJson 失败: " + e.getMessage(), e);
            return errorResult("导出失败: " + e.getMessage());
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /** 导出目标文件：传了路径就用它，否则落到 app 私有目录下带时间戳的文件 */
    private File resolveExportFile(String filePath) {
        if (filePath != null && !filePath.trim().isEmpty()) {
            return new File(filePath.trim());
        }
        String name = "knowledge_export_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date()) + ".json";
        File dir = context.getExternalFilesDir(null);
        if (dir == null) {
            dir = context.getFilesDir();
        }
        return dir == null ? null : new File(dir, name);
    }

    // ==================== 统计 ====================

    /**
     * 知识库统计信息。
     *
     * @return {"total":N,"documents":N,"categories":{...},"last_updated":ts,"db_version":N}
     *         {@code db_version} 是**实际**库内版本（{@code PRAGMA user_version}），不是常量——
     *         库文件被替换/迁移失败时，读常量只会给出"一切正常"的假象
     */
    public synchronized JSONObject stats() {
        lastError = null;
        JSONObject result = new JSONObject();
        Cursor cursor = null;
        try {
            SQLiteDatabase db = getReadableDb();
            int total = 0;
            int documents = 0;
            long lastUpdated = 0;
            int dbVersion = 0;
            JSONObject categories = new JSONObject();

            cursor = db.rawQuery("SELECT COUNT(*) FROM kb_chunks", null);
            if (cursor.moveToFirst()) {
                total = cursor.getInt(0);
            }
            cursor.close();

            cursor = db.rawQuery("SELECT COUNT(*) FROM kb_documents", null);
            if (cursor.moveToFirst()) {
                documents = cursor.getInt(0);
            }
            cursor.close();

            cursor = db.rawQuery("SELECT MAX(updated_at) FROM kb_chunks", null);
            if (cursor.moveToFirst()) {
                lastUpdated = cursor.getLong(0);
            }
            cursor.close();

            // v6：已向量化的块数 + 当前向量模型标识（0 / 空 = 还没回填，此时 semantic 走旧重排路径）
            int embedded = 0;
            cursor = db.rawQuery("SELECT COUNT(*) FROM kb_chunks WHERE embedding IS NOT NULL", null);
            if (cursor.moveToFirst()) {
                embedded = cursor.getInt(0);
            }
            cursor.close();

            cursor = db.rawQuery("SELECT category, COUNT(*) FROM kb_chunks GROUP BY category ORDER BY COUNT(*) DESC", null);
            while (cursor.moveToNext()) {
                categories.put(cursor.getString(0), cursor.getInt(1));
            }
            cursor.close();

            cursor = db.rawQuery("PRAGMA user_version", null);
            if (cursor.moveToFirst()) {
                dbVersion = cursor.getInt(0);
            }
            cursor.close();
            cursor = null;

            result.put("total", total);
            result.put("documents", documents);
            result.put("embedded", embedded);
            // 导入后立刻看 stats 会比 total 小：后台回填还在分批跑，这里把"还差多少"写明，
            // 避免把异步进度当成"缺向量"（真缺时 pending 会一直不动）
            result.put("embedding_pending", Math.max(0, total - embedded));
            String embModel = metaString(db, META_EMBEDDING_MODEL);
            result.put("embedding_model", embModel == null ? "" : embModel);

            // v6.1：向量来源一眼可辨——本地模型 / 在线端点 / 都没有
            com.oilquiz.app.ai.embedding.LocalEmbeddingModel local = localEmbedding();
            String source;
            if (local.isAvailable()) {
                source = "local";
            } else if (local.state() == com.oilquiz.app.ai.embedding.LocalEmbeddingModel.State.DOWNLOADING) {
                source = "downloading";
            } else {
                source = "none";
                try {
                    OnlineModelManager omm = OnlineModelManager.getInstance(context);
                    if (omm != null && pickEmbeddingConfig(omm) != null) source = "online";
                } catch (Throwable ignored) {
                }
            }
            result.put("embedding_source", source);
            result.put("local_embedding", local.state().name().toLowerCase(java.util.Locale.ROOT));
            result.put("local_embedding_dir", local.dir().getAbsolutePath());
            result.put("categories", categories);
            result.put("last_updated", lastUpdated);
            result.put("db_version", dbVersion);
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
        values.put("doc_id", chunk.docId);
        values.put("chunk_seq", chunk.chunkSeq);
        values.put("created_at", now);
        values.put("updated_at", now);
        return values;
    }

    /**
     * 归一化检索文本（v4，2026-10-28）：把四段文本切成**空格分隔的 token 流**。
     *
     * <p>v3 存的是原文小写，查询按单字 LIKE，导致"光合作用"被拆成
     * 光/合/作/用 四个字 AND——任何同时含这四个字的文档都命中，顺序完全不管。
     * v4 改成 bigram：中文连续段切"相邻两字"（光合/合作/作用），英文/数字按词；
     * 查询侧同样切，SQL 用带空格边界的整 token 匹配，顺序敏感、假阳性大幅下降。</p>
     *
     * <p>为什么用 bigram 而不是真分词器：不引入依赖、对未登录词（专业术语、人名）也能命中，
     * 且这是 CJK 无词典检索的常用折中（Lucene 的 CJKAnalyzer 也是二元切分）。</p>
     */
    static String buildSearchText(KnowledgeChunk chunk) {
        if (chunk == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        appendTokens(sb, chunk.title);
        appendTokens(sb, chunk.category);
        appendTokens(sb, chunk.keywords);
        appendTokens(sb, chunk.content);
        return sb.toString().trim();
    }

    /** 追加一段文本的 token（空格分隔）：英文/数字按词，中文按相邻两字 */
    private static void appendTokens(StringBuilder out, String text) {
        if (text == null || text.isEmpty()) return;
        Matcher m = TOKEN_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String run = m.group();
            if (isAsciiRun(run)) {
                out.append(run).append(' ');
            } else {
                appendCjkBigrams(out, run);
            }
        }
    }

    /** 中文段切 bigram；单字段落保留单字（否则"光"这种查询永远命中不了） */
    private static void appendCjkBigrams(StringBuilder out, String run) {
        if (run.length() == 1) {
            out.append(run).append(' ');
            return;
        }
        for (int i = 0; i + 1 < run.length(); i++) {
            out.append(run, i, i + 2).append(' ');
        }
    }

    private static boolean isAsciiRun(String s) {
        if (s.isEmpty()) return false;
        char c = s.charAt(0);
        return c < 0x80;
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
