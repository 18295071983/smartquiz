package com.oilquiz.app.ai.importing.v2;

import android.content.ContentValues;
import android.database.Cursor;
import android.util.Log;

import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteStatement;

import com.oilquiz.app.database.AppDatabase;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Java 事务批量入库器（入库断点恢复）。
 * <p>
 * 自定义批量事务入库，不受原有 20 条分批限制：
 * <ul>
 *   <li>默认 60 条/批事务插入；数据库锁、字段异常自动拆分 30 条小批次重试</li>
 *   <li>错误行单独导出 error_rows.csv，正常数据持续入库</li>
 *   <li>读取入库断点偏移量，跳过已入库数据，实时更新断点进度</li>
 * </ul>
 */
public class ImportCsvIngestor {

    private static final String TAG = "ImportCsvIngestor";

    /** 默认批量大小 */
    private static final int BATCH_SIZE = 60;
    /** 失败降级批量大小 */
    private static final int SMALL_BATCH_SIZE = 30;

    /** 入库统计 */
    public static class IngestStats {
        public int imported;
        public int duplicated;
        public int failed;
        public long totalRows;
    }

    /** 入库进度回调 */
    public interface IngestListener {
        /** 进度：已处理行 / 总行 */
        void onProgress(long processed, long total);

        /** 保存入库断点偏移量 */
        void onOffsetChange(long globalOffset);
    }

    private final android.content.Context context;

    public ImportCsvIngestor(android.content.Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * 执行入库。
     *
     * @param chunkFiles    CSV 分片文件（按序）
     * @param startOffset   入库断点全局行偏移（跳过已入库数据）
     * @param listener      进度回调
     * @return 统计结果
     */
    public IngestStats ingest(List<File> chunkFiles, long startOffset, IngestListener listener) {
        IngestStats stats = new IngestStats();

        // 实时读取合法列，数据库增减字段无需修改代码
        Set<String> legalColumns = readLegalColumns();
        if (legalColumns.isEmpty()) {
            Log.e(TAG, "无法读取 question 表结构");
            return stats;
        }

        // 预统计总行数（含表头扣除）
        long total = 0;
        for (File f : chunkFiles) {
            total += Math.max(0, countDataRows(f));
        }
        stats.totalRows = total;

        long globalIndex = 0; // 全局数据行序号（不含表头）
        List<ContentValues> buffer = new ArrayList<>(BATCH_SIZE);

        for (File f : chunkFiles) {
            BufferedReader br = null;
            try {
                br = new BufferedReader(new InputStreamReader(
                        new FileInputStream(f), StandardCharsets.UTF_8));
                String headerLine = readLogicalLine(br);
                if (headerLine == null) continue;
                String[] headers = parseCsvLine(headerLine);

                String line;
                while ((line = readLogicalLine(br)) != null) {
                    if (line.trim().isEmpty()) continue;
                    long rowIdx = globalIndex++;
                    if (rowIdx < startOffset) continue; // 断点跳过

                    String[] cells = parseCsvLine(line);
                    ContentValues cv = rowToContentValues(headers, cells, legalColumns);
                    if (cv == null) {
                        stats.failed++;
                        appendErrorRow(line, "必填字段缺失");
                    } else {
                        buffer.add(cv);
                    }

                    if (buffer.size() >= BATCH_SIZE) {
                        flushBuffer(buffer, stats, listener);
                        if (listener != null) {
                            listener.onOffsetChange(rowIdx + 1);
                            listener.onProgress(rowIdx + 1, total);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "读取CSV分片失败 " + f.getName() + ": " + e.getMessage());
            } finally {
                closeQuiet(br);
            }
        }

        // 尾批
        if (!buffer.isEmpty()) {
            flushBuffer(buffer, stats, listener);
            if (listener != null) {
                listener.onOffsetChange(globalIndex);
                listener.onProgress(globalIndex, total);
            }
        }
        return stats;
    }

    // ==================== 内部实现 ====================

    private Set<String> readLegalColumns() {
        Set<String> cols = new HashSet<>();
        try {
            AppDatabase db = AppDatabase.getDatabase(context);
            SupportSQLiteDatabase sqlite = db.getOpenHelper().getReadableDatabase();
            Cursor c = sqlite.query("PRAGMA table_info(question)");
            try {
                while (c.moveToNext()) {
                    cols.add(c.getString(c.getColumnIndexOrThrow("name")));
                }
            } finally {
                c.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "PRAGMA 读取失败: " + e.getMessage());
        }
        return cols;
    }

    private long countDataRows(File f) {
        long count = -1; // 扣表头
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), StandardCharsets.UTF_8));
            while (readLogicalLine(br) != null) count++;
        } catch (Exception e) {
            return 0;
        } finally {
            closeQuiet(br);
        }
        return Math.max(0, count);
    }

    /** CSV 行 → ContentValues；核心必填缺失返回 null */
    private ContentValues rowToContentValues(String[] headers, String[] cells, Set<String> legal) {
        Map<String, String> row = new HashMap<>();
        for (int i = 0; i < headers.length && i < cells.length; i++) {
            row.put(headers[i].trim(), cells[i]);
        }
        String questionText = norm(row.get("questionText"));
        String correctAnswer = norm(row.get("correctAnswer"));
        if (questionText.isEmpty()) return null;

        ContentValues cv = new ContentValues();
        for (Map.Entry<String, String> e : row.entrySet()) {
            String col = e.getKey();
            if (!legal.contains(col) || "id".equals(col)) continue;
            String v = norm(e.getValue());
            switch (col) {
                case "difficulty":
                case "points":
                case "timeLimit":
                case "favorite":
                case "status":
                case "visibility":
                    // 数值列由下方数值分支处理
                    break;
                default:
                    if (!v.isEmpty()) cv.put(col, v);
            }
        }
        // 数值列强修正
        cv.put("difficulty", clampDifficulty(row.get("difficulty")));
        long now = System.currentTimeMillis();
        if (legal.contains("createdAt")) cv.put("createdAt", now);
        if (legal.contains("updatedAt")) cv.put("updatedAt", now);
        if (legal.contains("correctAnswer")) cv.put("correctAnswer", correctAnswer);
        return cv;
    }

    private int clampDifficulty(String raw) {
        if (raw == null) return 1;
        try {
            int v = Integer.parseInt(raw.trim());
            return (v >= 1 && v <= 3) ? v : 1;
        } catch (Exception e) {
            return ImportOutputSanitizer.parseTextDifficulty(raw);
        }
    }

    /** 事务刷入一批；失败自动拆分小批次重试 */
    private void flushBuffer(List<ContentValues> buffer, IngestStats stats,
                             IngestListener listener) {
        List<ContentValues> batch = new ArrayList<>(buffer);
        buffer.clear();
        insertBatch(batch, stats);
    }

    private void insertBatch(List<ContentValues> batch, IngestStats stats) {
        if (batch.isEmpty()) return;
        try {
            int inserted = doInsert(batch, stats);
            stats.imported += inserted;
        } catch (Exception e) {
            Log.w(TAG, "批量插入失败(" + batch.size() + "条)，拆分重试: " + e.getMessage());
            if (batch.size() <= 1) {
                stats.failed += batch.size();
                appendErrorRow(batch.toString(), e.getMessage());
                return;
            }
            // 拆半重试（最小到单条），自动适配数据库锁/字段异常
            int half = Math.max(1, batch.size() / 2);
            insertBatch(new ArrayList<>(batch.subList(0, half)), stats);
            insertBatch(new ArrayList<>(batch.subList(half, batch.size())), stats);
        }
    }

    /** 单事务插入一批，批内题干去重 + 与库内去重；返回实际插入条数，被去重丢弃的计入 stats.duplicated。
     *  重复题目不再直接丢弃：若新数据携带了库中为空的字段值（用户修复表格后重导），自动回填补齐 */
    private int doInsert(List<ContentValues> batch, IngestStats stats) {
        // 批内去重
        Set<String> seen = new HashSet<>();
        List<ContentValues> unique = new ArrayList<>(batch.size());
        List<String> texts = new ArrayList<>(batch.size());
        for (ContentValues cv : batch) {
            String key = normalizeKey(cv.getAsString("questionText"));
            if (seen.add(key)) {
                unique.add(cv);
                texts.add(cv.getAsString("questionText"));
            } else {
                stats.duplicated++;
            }
        }

        // 与库内去重（单条参数化查询批量探测）
        Set<String> existing = queryExisting(texts);
        List<ContentValues> toInsert = new ArrayList<>();
        List<ContentValues> dups = new ArrayList<>();
        for (ContentValues cv : unique) {
            String key = normalizeKey(cv.getAsString("questionText"));
            if (existing.contains(key)) {
                stats.duplicated++;
                dups.add(cv);
                continue;
            }
            toInsert.add(cv);
        }
        // 重复题缺失字段回填：用户在源表格补齐分类/解析等列后重新导入即可生效
        if (!dups.isEmpty()) {
            int backfilled = backfillGaps(dups);
            if (backfilled > 0) {
                Log.i(TAG, "重复题缺失字段回填: " + backfilled + " 条");
            }
        }
        if (toInsert.isEmpty()) return 0;

        AppDatabase db = AppDatabase.getDatabase(context);
        SupportSQLiteDatabase sqlite = db.getOpenHelper().getWritableDatabase();
        sqlite.beginTransaction();
        try {
            for (ContentValues cv : toInsert) {
                sqlite.insert("question",
                        android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT, cv);
            }
            sqlite.setTransactionSuccessful();
        } finally {
            sqlite.endTransaction();
        }
        return toInsert.size();
    }

    /**
     * 重复题缺失字段回填：对已存在于库中的题目，仅当库中字段为空且新数据非空时更新，
     * 绝不覆盖已有内容。返回实际更新的题目数。
     */
    private int backfillGaps(List<ContentValues> dups) {
        int updated = 0;
        try {
            AppDatabase db = AppDatabase.getDatabase(context);
            SupportSQLiteDatabase sqlite = db.getOpenHelper().getWritableDatabase();
            Set<String> legal = readLegalColumns();
            for (ContentValues cv : dups) {
                String text = cv.getAsString("questionText");
                if (text == null || text.trim().isEmpty()) continue;
                Cursor c = sqlite.query("SELECT id, category, explanation, correctAnswer FROM question "
                        + "WHERE questionText = ? LIMIT 1", new Object[]{text});
                try {
                    if (!c.moveToNext()) continue;
                    long id = c.getLong(0);
                    ContentValues upd = new ContentValues();
                    String dbCat = c.getString(1);
                    String dbExp = c.getString(2);
                    String dbAns = c.getString(3);
                    String newCat = cv.getAsString("category");
                    String newExp = cv.getAsString("explanation");
                    String newAns = cv.getAsString("correctAnswer");
                    if (legal.contains("category") && (dbCat == null || dbCat.trim().isEmpty())
                            && newCat != null && !newCat.trim().isEmpty()) {
                        upd.put("category", newCat.trim());
                    }
                    if (legal.contains("explanation") && (dbExp == null || dbExp.trim().isEmpty())
                            && newExp != null && !newExp.trim().isEmpty()) {
                        upd.put("explanation", newExp.trim());
                    }
                    if (legal.contains("correctAnswer") && (dbAns == null || dbAns.trim().isEmpty())
                            && newAns != null && !newAns.trim().isEmpty()) {
                        upd.put("correctAnswer", newAns.trim());
                    }
                    if (upd.size() > 0) {
                        if (legal.contains("updatedAt")) upd.put("updatedAt", System.currentTimeMillis());
                        sqlite.update("question",
                                android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE,
                                upd, "id = ?", new Object[]{id});
                        updated++;
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "重复题字段回填异常(不影响导入): " + e.getMessage());
        }
        return updated;
    }

    /** 批量探测库内已存在的题干（归一化比较）。
     *  查询失败抛出异常，由上层拆半重试最终降级为单条失败，
     *  绝不允许"静默跳过去重"导致整批重复入库。 */
    private Set<String> queryExisting(List<String> texts) {
        Set<String> result = new HashSet<>();
        if (texts.isEmpty()) return result;
        try {
            AppDatabase db = AppDatabase.getDatabase(context);
            SupportSQLiteDatabase sqlite = db.getOpenHelper().getReadableDatabase();
            StringBuilder sql = new StringBuilder(
                    "SELECT questionText FROM question WHERE questionText IN (");
            for (int i = 0; i < texts.size(); i++) {
                if (i > 0) sql.append(',');
                sql.append('?');
            }
            sql.append(')');
            Cursor c = sqlite.query(sql.toString(), texts.toArray(new Object[0]));
            try {
                while (c.moveToNext()) {
                    result.add(normalizeKey(c.getString(0)));
                }
            } finally {
                c.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "库内去重查询失败，整批拆半重试以避免重复入库: " + e.getMessage());
            throw new IllegalStateException("去重查询失败", e);
        }
        return result;
    }

    private void appendErrorRow(String rowLine, String reason) {
        File f = ImportDirs.errorRowsFile();
        OutputStreamWriter w = null;
        try {
            boolean newFile = !f.exists() || f.length() == 0;
            w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8);
            if (newFile) {
                w.write("错误原因,原始行\r\n");
            }
            String safe = rowLine.replace('\r', ' ').replace('\n', ' ');
            w.write("\"" + reason.replace("\"", "'") + "\",\"" + safe.replace("\"", "\"\"") + "\"\r\n");
        } catch (Exception e) {
            Log.w(TAG, "写错误行失败: " + e.getMessage());
        } finally {
            closeQuiet(w);
        }
    }

    // ==================== CSV 解析（支持引号/逗号/换行转义） ====================

    /** 简化 CSV 行解析（处理双引号包裹与内部转义） */
    public static String[] parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(ch);
                }
            } else {
                if (ch == '"') {
                    inQuotes = true;
                } else if (ch == ',') {
                    out.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(ch);
                }
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    /**
     * 读一条逻辑 CSV 行：若引号未闭合（引号字段内嵌换行）则继续拼接后续物理行，
     * 防止多行字段被 readLine 切碎导致列错位/脏数据。
     */
    public static String readLogicalLine(BufferedReader br) throws java.io.IOException {
        String line = br.readLine();
        if (line == null) return null;
        StringBuilder sb = new StringBuilder(line);
        while (countQuotes(sb) % 2 != 0) {
            String next = br.readLine();
            if (next == null) break;
            sb.append('\n').append(next);
        }
        return sb.toString();
    }

    private static int countQuotes(CharSequence s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '"') n++;
        }
        return n;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim();
    }

    private static String normalizeKey(String s) {
        if (s == null) return "";
        // 与 Python 侧 \s（含全角空格 U+3000）语义一致：统一剔除所有空白后比较，
        // 避免"全角/半角空格变体"绕过批内/库内去重造成重复入库
        return s.replaceAll("[\\s\\u3000]+", "");
    }

    private static void closeQuiet(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }
}
