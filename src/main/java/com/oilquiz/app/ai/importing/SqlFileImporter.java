package com.oilquiz.app.ai.importing;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.oilquiz.app.model.Question;
import com.oilquiz.app.util.CharsetDetector;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * SQL 题库导入器。
 * <p>
 * 支持两类文件：
 * <ul>
 *   <li><b>SQLite 数据库文件</b>（.db/.sqlite/.sqlite3 或 SQLite 魔数头）：
 *       只读打开，自动挑选最像题库的表，按列名做字段映射后逐行提取；</li>
 *   <li><b>SQL 脚本文件</b>（.sql）：解析 INSERT INTO ... (列) VALUES (...) 语句，
 *       按列名做字段映射后逐行提取。</li>
 * </ul>
 * </p>
 */
public final class SqlFileImporter {

    private static final String TAG = "SqlFileImporter";
    /** 单文件最大提取行数保护 */
    private static final int MAX_ROWS = 50000;

    private SqlFileImporter() {
    }

    /** SQL 导入结果 */
    public static class SqlImportResult {
        /** 有效题目（题干非空） */
        public final List<Question> validQuestions = new ArrayList<>();
        /** 错误/提示信息 */
        public final List<String> messages = new ArrayList<>();
        /** 数据总行数（所有候选表合计） */
        public int totalRows;
        /** 因题干为空等原因跳过的行数 */
        public int skippedRows;
        /** 批内去重剔除的行数 */
        public int dedupedRows;
        /** 实际使用的表名 */
        public String tableUsed = "";
        /** 映射描述（标准字段 ← 源列名） */
        public String mappingDesc = "";
        /** 耗时（毫秒） */
        public long importTimeMs;

        public boolean isSuccess() {
            return !validQuestions.isEmpty();
        }
    }

    /** 解析出的源数据表 */
    private static class TableData {
        final String name;
        final List<String> columns = new ArrayList<>();
        final List<List<String>> rows = new ArrayList<>();

        TableData(String name) {
            this.name = name;
        }
    }

    // ==================== 入口 ====================

    /** 判断文件是否为 SQL 导入可处理类型（.sql 或 SQLite 数据库） */
    public static boolean isSqlFile(File file) {
        if (file == null || !file.exists()) return false;
        String n = file.getName().toLowerCase(Locale.ROOT);
        if (n.endsWith(".sql") || n.endsWith(".db") || n.endsWith(".sqlite")
                || n.endsWith(".sqlite3") || n.endsWith(".db3")) {
            return true;
        }
        return hasSqliteMagic(file);
    }

    /**
     * 执行 SQL 导入解析（同步方法，需在后台线程调用）。
     */
    public static SqlImportResult importFromFile(File file) {
        SqlImportResult result = new SqlImportResult();
        long start = System.currentTimeMillis();
        try {
            if (hasSqliteMagic(file)) {
                importFromDatabase(file, result);
            } else {
                importFromSqlScript(file, result);
            }
        } catch (Exception e) {
            Log.e(TAG, "SQL导入异常: " + e.getMessage(), e);
            result.messages.add("SQL导入失败: " + e.getMessage());
        }
        result.importTimeMs = System.currentTimeMillis() - start;
        Log.i(TAG, "SQL导入完成: 表=" + result.tableUsed + " 有效=" + result.validQuestions.size()
                + " 跳过=" + result.skippedRows + " 耗时=" + result.importTimeMs + "ms");
        return result;
    }

    // ==================== SQLite 数据库文件 ====================

    private static void importFromDatabase(File file, SqlImportResult result) {
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            List<String> tableNames = new ArrayList<>();
            Cursor tc = db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' "
                            + "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' "
                            + "AND name NOT LIKE 'android_metadata'", null);
            try {
                while (tc.moveToNext()) {
                    tableNames.add(tc.getString(0));
                }
            } finally {
                tc.close();
            }
            if (tableNames.isEmpty()) {
                result.messages.add("数据库中没有任何数据表");
                return;
            }

            // 读取所有表的列与数据，挑最优表
            List<TableData> tables = new ArrayList<>();
            for (String tn : tableNames) {
                TableData td = readTable(db, tn);
                if (td != null && !td.columns.isEmpty()) {
                    tables.add(td);
                    result.totalRows += td.rows.size();
                }
            }
            extractFromTables(tables, result);
        } finally {
            if (db != null) {
                try {
                    db.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static TableData readTable(SQLiteDatabase db, String tableName) {
        TableData td = new TableData(tableName);
        Cursor cc = null;
        try {
            cc = db.rawQuery("PRAGMA table_info(\"" + tableName.replace("\"", "\"\"") + "\")", null);
            while (cc.moveToNext()) {
                td.columns.add(cc.getString(1));
            }
        } finally {
            if (cc != null) cc.close();
        }
        if (td.columns.isEmpty()) return td;

        Cursor rc = null;
        try {
            rc = db.rawQuery("SELECT * FROM \"" + tableName.replace("\"", "\"\"") + "\"", null);
            int colCount = rc.getColumnCount();
            while (rc.moveToNext() && td.rows.size() < MAX_ROWS) {
                List<String> row = new ArrayList<>(colCount);
                for (int i = 0; i < colCount; i++) {
                    if (rc.isNull(i)) {
                        row.add("");
                    } else {
                        row.add(String.valueOf(rc.getString(i)));
                    }
                }
                td.rows.add(row);
            }
        } catch (Exception e) {
            Log.w(TAG, "读取表 " + tableName + " 失败: " + e.getMessage());
        } finally {
            if (rc != null) rc.close();
        }
        return td;
    }

    // ==================== SQL 脚本文件 ====================

    private static void importFromSqlScript(File file, SqlImportResult result) throws Exception {
        StringBuilder sb = new StringBuilder();
        Object[] readerInfo = CharsetDetector.openBufferedReaderAutoDetect(file);
        BufferedReader reader = (BufferedReader) readerInfo[0];
        try {
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
                if (sb.length() > 30_000_000) break; // 30MB 保护
            }
        } finally {
            try {
                reader.close();
            } catch (Exception ignored) {
            }
        }

        Map<String, TableData> tableMap = parseInsertStatements(sb.toString(), result);
        if (tableMap.isEmpty()) {
            if (result.messages.isEmpty()) {
                result.messages.add("未找到可解析的 INSERT 语句"
                        + "（要求 INSERT INTO 表名 (列1,列2,...) VALUES (...) 格式）");
            }
            return;
        }
        List<TableData> tables = new ArrayList<>(tableMap.values());
        for (TableData td : tables) {
            result.totalRows += td.rows.size();
        }
        extractFromTables(tables, result);
    }

    /**
     * 解析 INSERT INTO 语句。仅信任带列清单的语句（无列清单无法安全映射，跳过并提示）。
     */
    private static Map<String, TableData> parseInsertStatements(String sql, SqlImportResult result) {
        Map<String, TableData> tableMap = new LinkedHashMap<>();
        int len = sql.length();
        int i = 0;
        int noColumnListCount = 0;
        while (i < len) {
            int idx = indexOfIgnoreCase(sql, "INSERT INTO", i);
            if (idx < 0) break;
            i = idx + "INSERT INTO".length();
            i = skipWhitespace(sql, i);

            // 表名（支持 `t`、"t"、[t]、schema.t）
            int[] nameEnd = new int[1];
            String tableName = parseIdentifier(sql, i, nameEnd);
            if (tableName == null) continue;
            i = skipWhitespace(sql, nameEnd[0]);
            if (i >= len) break;

            // 列清单（可选）
            List<String> columns = null;
            if (sql.charAt(i) == '(') {
                int[] parenEnd = new int[1];
                columns = parseIdentifierList(sql, i, parenEnd);
                i = skipWhitespace(sql, parenEnd[0]);
            }
            // 跳过可能的 ON CONFLICT 等子句直到 VALUES
            int valuesIdx = indexOfIgnoreCase(sql, "VALUES", i);
            if (valuesIdx < 0) break;
            i = skipWhitespace(sql, valuesIdx + "VALUES".length());

            if (columns == null || columns.isEmpty()) {
                noColumnListCount++;
                // 跳过本条语句的元组部分
                i = skipTuples(sql, i);
                continue;
            }

            TableData td = tableMap.get(tableName);
            if (td == null) {
                td = new TableData(tableName);
                td.columns.addAll(columns);
                tableMap.put(tableName, td);
            }

            // 解析一个或多个元组 (v1, v2, ...), (...)
            while (i < len) {
                i = skipWhitespace(sql, i);
                if (i >= len || sql.charAt(i) != '(') break;
                int[] tupleEnd = new int[1];
                List<String> row = parseTuple(sql, i, tupleEnd);
                i = tupleEnd[0];
                if (row != null) {
                    if (td.rows.size() < MAX_ROWS) {
                        td.rows.add(row);
                    }
                }
                i = skipWhitespace(sql, i);
                if (i < len && sql.charAt(i) == ',') {
                    i++;
                } else {
                    break;
                }
            }
            // 跳到语句结束（分号）
            while (i < len && sql.charAt(i) != ';') {
                if (sql.charAt(i) == '\'') {
                    i = skipSqlString(sql, i);
                } else {
                    i++;
                }
            }
        }
        if (noColumnListCount > 0) {
            result.messages.add("跳过 " + noColumnListCount
                    + " 条无列清单的 INSERT 语句（无法安全映射字段，请提供 INSERT INTO 表 (列...) VALUES (...) 格式）");
        }
        return tableMap;
    }

    // ==================== 映射 + 提取（共用） ====================

    /**
     * 从候选表中挑选最像题库的表，做映射防错校验后提取题目。
     */
    private static void extractFromTables(List<TableData> tables, SqlImportResult result) {
        if (tables.isEmpty()) {
            result.messages.add("没有可提取数据的表");
            return;
        }

        // 评分：必须同时映射到 questionText + correctAnswer，再比映射字段总数
        TableData best = null;
        Map<String, Integer> bestMapping = null;
        int bestScore = -1;
        for (TableData td : tables) {
            Map<String, Integer> mapping = FieldMappingRegistry.buildMappingFromHeaders(td.columns);
            boolean hasCore = mapping.containsKey("questionText") && mapping.containsKey("correctAnswer");
            int score = (hasCore ? 1000 : 0) + mapping.size() + (td.rows.isEmpty() ? 0 : 1);
            if (score > bestScore) {
                bestScore = score;
                best = td;
                bestMapping = mapping;
            }
        }
        if (best == null) {
            result.messages.add("未找到可用数据表");
            return;
        }
        result.tableUsed = best.name;

        Map<String, Integer> mapping = bestMapping;
        if (!mapping.containsKey("questionText") || !mapping.containsKey("correctAnswer")) {
            result.messages.add("表 [" + best.name + "] 的列名无法映射到题干/答案字段，"
                    + "已识别列: " + mapping.keySet() + "，源列: " + best.columns);
            return;
        }

        // 映射描述
        StringBuilder desc = new StringBuilder();
        for (Map.Entry<String, Integer> e : mapping.entrySet()) {
            int idx = e.getValue();
            String src = (idx >= 0 && idx < best.columns.size()) ? best.columns.get(idx) : "?";
            if (desc.length() > 0) desc.append("；");
            desc.append(e.getKey()).append("←").append(src);
        }
        result.mappingDesc = desc.toString();

        // ===== 逐行提取 =====
        Set<String> seen = new LinkedHashSet<>();
        for (List<String> row : best.rows) {
            Question q;
            try {
                q = FieldMappingRegistry.extractFromRow(row, mapping);
            } catch (Exception e) {
                result.skippedRows++;
                continue;
            }
            String qt = q.getQuestionText() == null ? "" : q.getQuestionText().trim();
            if (qt.isEmpty()) {
                result.skippedRows++;
                continue;
            }
            // 答案规范化（"A;B" → "AB" 等，仅纯字母答案生效）
            if (q.getCorrectAnswer() != null) {
                q.setCorrectAnswer(Question.normalizeChoiceAnswer(q.getCorrectAnswer()));
            }
            if (!seen.add(qt)) {
                result.dedupedRows++;
                continue;
            }
            result.validQuestions.add(q);
        }
        if (result.validQuestions.isEmpty()) {
            result.messages.add("表 [" + best.name + "] 共 " + best.rows.size() + " 行，"
                    + "但未提取到有效题目（题干均为空），请检查数据内容");
        }
    }

    // ==================== SQL 词法辅助 ====================

    private static int indexOfIgnoreCase(String s, String target, int from) {
        String ls = s.toLowerCase(Locale.ROOT);
        return ls.indexOf(target.toLowerCase(Locale.ROOT), from);
    }

    private static int skipWhitespace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    /** 解析标识符（支持反引号/双引号/方括号包裹，schema.table 取最后一段） */
    private static String parseIdentifier(String s, int i, int[] endOut) {
        if (i >= s.length()) return null;
        StringBuilder sb = new StringBuilder();
        char c = s.charAt(i);
        if (c == '`' || c == '"' || c == '[') {
            char close = (c == '[') ? ']' : c;
            i++;
            while (i < s.length() && s.charAt(i) != close) {
                sb.append(s.charAt(i));
                i++;
            }
            i++; // 跳过闭引号
        } else {
            while (i < s.length()) {
                char ch = s.charAt(i);
                if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '.') {
                    sb.append(ch);
                    i++;
                } else {
                    break;
                }
            }
        }
        endOut[0] = i;
        String name = sb.toString().trim();
        if (name.isEmpty()) return null;
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            name = name.substring(dot + 1);
        }
        return name;
    }

    /** 解析 (id1, id2, ...) 标识符清单，返回项列表，endOut 指向闭括号之后 */
    private static List<String> parseIdentifierList(String s, int i, int[] endOut) {
        List<String> list = new ArrayList<>();
        if (i >= s.length() || s.charAt(i) != '(') {
            endOut[0] = i;
            return list;
        }
        i++;
        while (i < s.length()) {
            i = skipWhitespace(s, i);
            if (i >= s.length()) break;
            if (s.charAt(i) == ')') {
                i++;
                break;
            }
            int[] e = new int[1];
            String id = parseIdentifier(s, i, e);
            if (id != null && !id.isEmpty()) {
                list.add(id);
                i = e[0];
            } else {
                i++;
            }
            i = skipWhitespace(s, i);
            if (i < s.length() && s.charAt(i) == ',') i++;
        }
        endOut[0] = i;
        return list;
    }

    /** 解析单个值元组 (...)，返回字符串值列表（NULL → ""），endOut 指向闭括号之后 */
    private static List<String> parseTuple(String s, int i, int[] endOut) {
        List<String> values = new ArrayList<>();
        if (i >= s.length() || s.charAt(i) != '(') {
            endOut[0] = i;
            return null;
        }
        i++;
        StringBuilder cur = new StringBuilder();
        boolean inString = false;
        boolean hasValue = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '\'') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                        cur.append('\''); // '' 转义
                        i += 2;
                        continue;
                    }
                    inString = false;
                    i++;
                    continue;
                }
                if (c == '\\' && i + 1 < s.length()) {
                    char next = s.charAt(i + 1);
                    if (next == 'n') cur.append('\n');
                    else if (next == 't') cur.append('\t');
                    else if (next == 'r') cur.append('\r');
                    else cur.append(next);
                    i += 2;
                    continue;
                }
                cur.append(c);
                i++;
                continue;
            }
            if (c == '\'') {
                inString = true;
                hasValue = true;
                i++;
                continue;
            }
            if (c == ',') {
                values.add(finishValue(cur, hasValue));
                cur.setLength(0);
                hasValue = false;
                i++;
                continue;
            }
            if (c == ')') {
                values.add(finishValue(cur, hasValue));
                endOut[0] = i + 1;
                return values;
            }
            cur.append(c);
            hasValue = true;
            i++;
        }
        endOut[0] = i;
        return values.isEmpty() ? null : values;
    }

    private static String finishValue(StringBuilder cur, boolean hasValue) {
        String v = cur.toString().trim();
        if (!hasValue && v.isEmpty()) return "";
        if (v.equalsIgnoreCase("NULL")) return "";
        return v;
    }

    /** 跳过一段元组区域（用于无列清单语句），返回分号或末尾位置 */
    private static int skipTuples(String s, int i) {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ';') return i;
            if (c == '\'') {
                i = skipSqlString(s, i);
            } else {
                i++;
            }
        }
        return i;
    }

    /** 跳过一个单引号字符串（含 '' 转义），返回字符串之后的位置 */
    private static int skipSqlString(String s, int i) {
        i++; // 跳过起始引号
        while (i < s.length()) {
            if (s.charAt(i) == '\'') {
                if (i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return i;
    }

    /** 检查文件头 16 字节是否为 SQLite 魔数 */
    private static boolean hasSqliteMagic(File file) {
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(file);
            byte[] head = new byte[16];
            int read = fis.read(head);
            if (read < 16) return false;
            String magic = new String(head, 0, 15, java.nio.charset.StandardCharsets.US_ASCII);
            return magic.equals("SQLite format 3");
        } catch (Exception e) {
            return false;
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
