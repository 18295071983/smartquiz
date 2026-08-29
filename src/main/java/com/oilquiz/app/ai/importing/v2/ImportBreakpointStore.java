package com.oilquiz.app.ai.importing.v2;

import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 双层断点续导状态存储（import_breakpoint.state）。
 * <p>
 * 任意阶段 APP 闪退/手动退出/系统杀进程，断点文件永久保存进度；
 * 下次启动导入工具自动读取断点，从失败位置继续执行。
 */
public final class ImportBreakpointStore {

    private static final String TAG = "ImportBreakpoint";

    /** 阶段常量 */
    public static final String STAGE_PARSE = "parse";   // Python 文件解析阶段
    public static final String STAGE_INGEST = "ingest"; // Java 入库阶段

    /** 断点状态 */
    public static class State {
        /** 当前阶段 parse/ingest */
        public String stage = STAGE_PARSE;
        /** 源文件绝对路径 */
        public String sourceFile;
        /** 源文件名 */
        public String sourceName;
        /** 字段映射 JSON（标准字段→源列名），恢复时无需重新推理 */
        public String mappingJson;
        /** Python 解析阶段：已处理的源文件数据行数 */
        public long parseRowIndex;
        /** LLM 识别的真实表头行号（0-based；-1=无表头；-2=未指定/自动检测），断点恢复时沿用 */
        public int headerRow = -2;
        /** 断点对应的工作表索引（-1=自动扫全部），多工作表导入时按 sheet 隔离断点 */
        public int sheetIndex = -1;
        /** 断点对应源文件大小（字节），-1=未记录（旧断点兼容）；恢复前校验源文件未被改动 */
        public long sourceFileSize = -1;
        /** 断点对应源文件最后修改时间（毫秒），-1=未记录（旧断点兼容） */
        public long sourceFileMtime = -1;
        /** 入库阶段：CSV 分片文件路径列表（逗号分隔） */
        public String csvChunks;
        /** 入库阶段：已入库的 CSV 数据行偏移量 */
        public long ingestOffset;
        public long updatedAt;

        public JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("stage", stage);
            o.put("sourceFile", sourceFile == null ? "" : sourceFile);
            o.put("sourceName", sourceName == null ? "" : sourceName);
            o.put("mappingJson", mappingJson == null ? "" : mappingJson);
            o.put("parseRowIndex", parseRowIndex);
            o.put("headerRow", headerRow);
            o.put("sheetIndex", sheetIndex);
            o.put("sourceFileSize", sourceFileSize);
            o.put("sourceFileMtime", sourceFileMtime);
            o.put("csvChunks", csvChunks == null ? "" : csvChunks);
            o.put("ingestOffset", ingestOffset);
            o.put("updatedAt", System.currentTimeMillis());
            return o;
        }

        public static State fromJson(JSONObject o) {
            State s = new State();
            s.stage = o.optString("stage", STAGE_PARSE);
            s.sourceFile = o.optString("sourceFile");
            s.sourceName = o.optString("sourceName");
            s.mappingJson = o.optString("mappingJson");
            s.parseRowIndex = o.optLong("parseRowIndex", 0);
            s.headerRow = o.optInt("headerRow", -2);
            s.sheetIndex = o.optInt("sheetIndex", -1);
            s.sourceFileSize = o.optLong("sourceFileSize", -1);
            s.sourceFileMtime = o.optLong("sourceFileMtime", -1);
            s.csvChunks = o.optString("csvChunks");
            s.ingestOffset = o.optLong("ingestOffset", 0);
            s.updatedAt = o.optLong("updatedAt", 0);
            return s;
        }
    }

    private ImportBreakpointStore() {
    }

    /** 读取断点；不存在或非法返回 null */
    public static State load() {
        File f = ImportDirs.breakpointFile();
        if (!f.exists() || f.length() == 0) return null;
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int read = 0;
            while (read < buf.length) {
                int n = fis.read(buf, read, buf.length - read);
                if (n < 0) break;
                read += n;
            }
            JSONObject o = new JSONObject(new String(buf, 0, read, StandardCharsets.UTF_8));
            State s = State.fromJson(o);
            // 断点源文件必须仍存在才有效
            if (s.sourceFile == null || s.sourceFile.isEmpty()
                    || !new File(s.sourceFile).exists()) {
                Log.i(TAG, "断点源文件不存在，忽略断点: " + s.sourceFile);
                return null;
            }
            // 断点恢复前校验源文件未被改动（大小或修改时间不一致 → 断点失效，全量重导）
            File src = new File(s.sourceFile);
            if (s.sourceFileSize > 0 && src.length() != s.sourceFileSize) {
                Log.w(TAG, "断点源文件大小已变化(记录=" + s.sourceFileSize + ", 实际=" + src.length()
                        + ")，断点失效，全量重导");
                return null;
            }
            if (s.sourceFileMtime > 0 && src.lastModified() != s.sourceFileMtime) {
                Log.w(TAG, "断点源文件修改时间已变化(记录=" + s.sourceFileMtime + ", 实际="
                        + src.lastModified() + ")，断点失效，全量重导");
                return null;
            }
            return s;
        } catch (Exception e) {
            Log.w(TAG, "读取断点失败: " + e.getMessage());
            return null;
        } finally {
            closeQuiet(fis);
        }
    }

    /** 保存断点（原子写：先写临时文件再改名）；自动记录源文件大小与修改时间供恢复校验 */
    public static void save(State state) {
        if (state == null) return;
        if (state.sourceFile != null && !state.sourceFile.isEmpty()) {
            File src = new File(state.sourceFile);
            if (src.exists()) {
                state.sourceFileSize = src.length();
                state.sourceFileMtime = src.lastModified();
            }
        }
        File f = ImportDirs.breakpointFile();
        File tmp = new File(f.getAbsolutePath() + ".tmp");
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(tmp);
            fos.write(state.toJson().toString().getBytes(StandardCharsets.UTF_8));
            fos.flush();
            closeQuiet(fos);
            fos = null;
            if (f.exists() && !f.delete()) {
                Log.w(TAG, "删除旧断点失败");
            }
            if (!tmp.renameTo(f)) {
                Log.w(TAG, "断点文件改名失败");
            }
        } catch (Exception e) {
            Log.w(TAG, "保存断点失败: " + e.getMessage());
        } finally {
            closeQuiet(fos);
        }
    }

    /** 清除断点（全部入库完成后调用） */
    public static void clear() {
        File f = ImportDirs.breakpointFile();
        if (f.exists()) {
            boolean ok = f.delete();
            Log.i(TAG, "清除断点文件: " + ok);
        }
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
