package com.oilquiz.app.ai.importing.v2;

import android.content.Context;
import android.database.Cursor;
import android.util.Log;

import androidx.sqlite.db.SupportSQLiteDatabase;

import com.oilquiz.app.database.AppDatabase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 导入专属精简工具集管理器（仅 3 个导入刚需工具，Java 本地执行，无额外推理开销）。
 * <ol>
 *   <li>getColumnMeta —— 读取 question 表元数据（字段名/类型/非空约束）</li>
 *   <li>getSampleFileData —— 读取文件表头 + 前 15 行样例并自动截断（500 Token 以内）</li>
 *   <li>saveFieldMappingCache —— 持久化字段映射规则至本地缓存</li>
 * </ol>
 * 熔断规则：单次会话最多允许 2 次工具调用，防止模型无限循环调用卡死流程。
 */
public class ImportToolManager {

    private static final String TAG = "ImportToolManager";

    /** 单次会话工具调用上限（熔断） */
    public static final int MAX_TOOL_CALLS_PER_SESSION = 2;

    public static final String TOOL_GET_COLUMN_META = "getColumnMeta";
    public static final String TOOL_GET_SAMPLE_FILE_DATA = "getSampleFileData";
    public static final String TOOL_SAVE_MAPPING_CACHE = "saveFieldMappingCache";

    /** 工具定义 */
    public static class ToolDef {
        public final String name;
        public final String description;

        public ToolDef(String name, String description) {
            this.name = name;
            this.description = description;
        }
    }

    /** 模型请求的工具调用 */
    public static class ToolCall {
        public String name;
        public JSONObject params = new JSONObject();
    }

    private final Context context;
    private final List<ToolDef> toolList = new ArrayList<>();

    public ImportToolManager(Context context) {
        this.context = context.getApplicationContext();
        toolList.add(new ToolDef(TOOL_GET_COLUMN_META,
                "读取数据库question表的合法字段列表与类型"));
        toolList.add(new ToolDef(TOOL_GET_SAMPLE_FILE_DATA,
                "读取待导入文件的表头与前15行样例数据，参数file_path"));
        toolList.add(new ToolDef(TOOL_SAVE_MAPPING_CACHE,
                "保存字段映射缓存，参数table_finger/header_finger/mapping_json（三者齐全，与主流程缓存 key 一致）"));
    }

    public List<ToolDef> getToolList() {
        return toolList;
    }

    /** 生成提示词中的工具说明短文本 */
    public String buildToolHint() {
        StringBuilder sb = new StringBuilder();
        for (ToolDef t : toolList) {
            sb.append(t.name).append(": ").append(t.description).append('\n');
        }
        return sb.toString();
    }

    /**
     * 解析模型输出中的工具调用请求。
     * 识别形如 {"tool":"getColumnMeta","params":{}} 的 JSON；非工具调用返回 null。
     */
    public ToolCall parseToolRequest(String llmOutput) {
        String block = ImportOutputSanitizer.trimToJsonBlock(llmOutput);
        if (block == null) return null;
        try {
            JSONObject o = new JSONObject(block);
            String tool = o.optString("tool", "").trim();
            if (tool.isEmpty()) return null;
            // 仅接受白名单内工具，防止模型自创工具名
            boolean known = false;
            for (ToolDef t : toolList) {
                if (t.name.equals(tool)) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                Log.w(TAG, "模型请求了未知工具: " + tool);
                return null;
            }
            ToolCall call = new ToolCall();
            call.name = tool;
            JSONObject params = o.optJSONObject("params");
            if (params != null) call.params = params;
            return call;
        } catch (Exception e) {
            return null;
        }
    }

    /** 执行工具，返回结果 JSON 字符串（交给模型继续推理） */
    public String executeTool(ToolCall call) {
        if (call == null) return "{\"error\":\"empty tool call\"}";
        try {
            switch (call.name) {
                case TOOL_GET_COLUMN_META:
                    return execGetColumnMeta();
                case TOOL_GET_SAMPLE_FILE_DATA:
                    return execGetSampleFileData(call.params);
                case TOOL_SAVE_MAPPING_CACHE:
                    return execSaveMappingCache(call.params);
                default:
                    return "{\"error\":\"unknown tool\"}";
            }
        } catch (Exception e) {
            Log.w(TAG, "工具执行异常 " + call.name + ": " + e.getMessage());
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    /** 熔断判断 */
    public boolean reachToolLimit(int currentCount) {
        return currentCount >= MAX_TOOL_CALLS_PER_SESSION;
    }

    // ==================== 工具实现 ====================

    private String execGetColumnMeta() throws Exception {
        JSONObject result = new JSONObject();
        JSONArray columns = new JSONArray();
        List<String> names = new ArrayList<>();

        AppDatabase db = AppDatabase.getDatabase(context);
        SupportSQLiteDatabase sqlite = db.getOpenHelper().getReadableDatabase();
        Cursor cursor = sqlite.query("PRAGMA table_info(question)");
        try {
            while (cursor.moveToNext()) {
                JSONObject col = new JSONObject();
                String name = cursor.getString(cursor.getColumnIndexOrThrow("name"));
                col.put("name", name);
                col.put("type", cursor.getString(cursor.getColumnIndexOrThrow("type")));
                col.put("notnull", cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) != 0);
                columns.put(col);
                names.add(name);
            }
        } finally {
            cursor.close();
        }

        result.put("status", "success");
        result.put("table", "question");
        result.put("columns", columns);
        result.put("fields", join(names));
        return result.toString();
    }

    private String execGetSampleFileData(JSONObject params) throws Exception {
        String filePath = params.optString("file_path", "").trim();
        if (filePath.isEmpty()) {
            return "{\"error\":\"缺少参数 file_path\"}";
        }
        JSONObject sample = ImportPythonBridge.getInstance(context).sampleFile(filePath, 15, -1);
        if (sample == null) {
            return "{\"error\":\"文件采样失败: " + filePath + "\"}";
        }
        // 短文本截断，控制在 500 Token 以内
        JSONArray rows = sample.optJSONArray("rows");
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                JSONArray row = rows.optJSONArray(i);
                if (row == null) continue;
                for (int j = 0; j < row.length(); j++) {
                    String cell = row.optString(j, "");
                    if (cell.length() > 60) {
                        row.put(j, cell.substring(0, 60) + "…");
                    }
                }
            }
        }
        return sample.toString();
    }

    private String execSaveMappingCache(JSONObject params) throws Exception {
        String tableFinger = params.optString("table_finger", "");
        String headerFinger = params.optString("header_finger", "");
        String mappingJson = params.optString("mapping_json", "");
        if (tableFinger.isEmpty() || mappingJson.isEmpty()) {
            return "{\"error\":\"缺少参数 table_finger 或 mapping_json\"}";
        }
        // 缓存 key 必须与主流程 ImportMain 完全一致（表结构指纹 + 表头指纹）。
        // 缺 header_finger 时无法生成安全 key，放弃保存，防止脏 key 污染 map_cache.json。
        if (headerFinger.isEmpty()) {
            return "{\"error\":\"缺少参数 header_finger，无法生成与主流程一致的缓存 key，已放弃保存\"}";
        }
        JSONObject m = new JSONObject(ImportOutputSanitizer.trimToJsonBlock(mappingJson) != null
                ? ImportOutputSanitizer.trimToJsonBlock(mappingJson) : mappingJson);
        Map<String, String> map = new LinkedHashMap<>();
        java.util.Iterator<String> it = m.keys();
        while (it.hasNext()) {
            String k = it.next();
            map.put(k, m.optString(k, ""));
        }
        ImportMapCache.save(ImportMapCache.buildCacheKey(tableFinger, headerFinger), map);
        return "{\"status\":\"saved\"}";
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}
