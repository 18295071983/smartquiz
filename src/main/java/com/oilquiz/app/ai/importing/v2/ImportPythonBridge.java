package com.oilquiz.app.ai.importing.v2;

import android.content.Context;
import android.util.Log;

import com.chaquo.python.Python;
import com.chaquo.python.PyObject;

import com.chaquo.python.android.AndroidPlatform;

import org.json.JSONObject;

/**
 * Python 文件预处理层桥接（Chaquopy）。
 * <p>
 * 分层权限隔离：Python 仅负责文件解析、CSV 中转，仅操作公共存储目录
 * （/storage/emulated/0/OilQuiz/），无数据库访问权限；
 * 海量题库文本不进入模型上下文。
 * <p>
 * 对应 Python 模块：src/main/python/import_preprocessor.py
 */
public class ImportPythonBridge {

    private static final String TAG = "ImportPythonBridge";
    private static final String MODULE_NAME = "import_preprocessor";

    private static volatile ImportPythonBridge instance;

    private final Context context;
    private PyObject module;
    private boolean initialized = false;
    /** 最近一次初始化失败的明确原因（如"公共存储权限缺失"），供上层 UI 展示 */
    private static volatile String lastInitError;

    private ImportPythonBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    public static ImportPythonBridge getInstance(Context context) {
        if (instance == null) {
            synchronized (ImportPythonBridge.class) {
                if (instance == null) {
                    instance = new ImportPythonBridge(context);
                }
            }
        }
        return instance;
    }

    public synchronized boolean ensureReady() {
        if (initialized) return true;
        // 前置权限检查：导入管线目录在公共存储 /storage/emulated/0/OilQuiz/（ImportDirs），
        // 未授权时 Python 打开文件会抛 PermissionError，必须主动检测而非假设已授权。
        if (!hasPublicStoragePermission()) {
            lastInitError = "公共存储权限缺失，Python 无法读写 /storage/emulated/0/OilQuiz/（请先授予\"所有文件访问\"权限）";
            Log.e(TAG, lastInitError);
            return false;
        }
        try {
            if (!Python.isStarted()) {
                Python.start(new AndroidPlatform(context));
            }
            module = Python.getInstance().getModule(MODULE_NAME);
            initialized = module != null;
            if (initialized) {
                lastInitError = null;
            } else {
                lastInitError = "Python 预处理模块加载失败: " + MODULE_NAME;
            }
            Log.i(TAG, "Python 预处理模块加载: " + (initialized ? "成功" : "失败"));
            return initialized;
        } catch (Throwable t) {
            lastInitError = "Python 初始化失败: " + t.getMessage();
            Log.e(TAG, lastInitError, t);
            return false;
        }
    }

    /** 最近一次初始化失败的明确原因（供上层 UI 展示） */
    public static String getLastInitError() {
        return lastInitError;
    }

    /**
     * 公共目录读写权限是否已授予（导入管线目录 /storage/emulated/0/OilQuiz/）：
     * - Android 11+（R）：MANAGE_EXTERNAL_STORAGE（"所有文件访问"）
     * - Android 6~10（M~R）：READ/WRITE_EXTERNAL_STORAGE
     */
    public boolean hasPublicStoragePermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            return android.os.Environment.isExternalStorageManager();
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED
                    == context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    && android.content.pm.PackageManager.PERMISSION_GRANTED
                    == context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        return true;
    }

    /**
     * 采样：读取文件表头 + 前 N 行样例（短文本自动截断，500 Token 以内）。
     * <p>
     * 返回归一化约定：成功为 {"headers":[...],"rows":[...],"source_kind":...}；
     * 失败**不再返回 null**，而是 {"error":"..."} 归一化错误对象，调用方据此展示具体原因。
     *
     * @param sheetIndex Excel 用户选定工作表索引（-1=自动扫全部）
     */
    public JSONObject sampleFile(String filePath, int maxRows, int sheetIndex) {
        return callWithRetry("sample_file", "文件采样",
                py -> py.callAttr("sample_file", filePath, maxRows,
                        sheetIndex >= 0 ? Integer.valueOf(sheetIndex) : null));
    }

    /**
     * 全量解析：按映射规则清洗源文件，分片写出标准化 CSV，并实时写断点进度。
     *
     * @param mappingJson  标准字段 → 源列名 映射 JSON
     * @param outDir       CSV 输出目录（公共目录）
     * @param resumeRow    断点恢复起始数据行
     * @param chunkRows    单个 CSV 分片行数（防内存溢出）
     * @param breakpointPath 断点文件路径（Python 实时写 parseRowIndex）
     * @param specJson     Java 根据 question 表实际结构动态下发的字段规格 JSON：
     *                     {"std_columns":[...],"fill_fields":[...],"option_fields":{...}}，
     *                     为 null 时 Python 用内置默认值兜底
     * @param headerRow    LLM 识别的真实表头行号（0-based；-1=无表头；null=自动检测）
     * @return {"success":bool,"chunks":[...],"total_rows":n,"processed_rows":n,
     *          "missing":[{"chunk":path,"row":i,"questionText":..,"options":{..},"has":{..}}]}
     */
    public JSONObject parseFile(String filePath, String mappingJson, String outDir,
                                long resumeRow, int chunkRows, String breakpointPath,
                                String specJson, int sheetIndex, Integer headerRow,
                                String defaultQuestionType, String optionDelimiter) {
        return callWithRetry("parse_file", "全量解析",
                py -> py.callAttr("parse_file", filePath, mappingJson, outDir,
                        resumeRow, chunkRows, breakpointPath, specJson,
                        sheetIndex >= 0 ? Integer.valueOf(sheetIndex) : null,
                        headerRow, defaultQuestionType, optionDelimiter));
    }

    /**
     * 回写 AI 填充结果至 CSV 分片。
     *
     * @param fillsJson       {"行索引": {"category":..,"difficulty":..,"explanation":..}}
     * @param fillFieldsJson  Java 动态下发的可填充字段列表 JSON（如 ["category","difficulty"]），
     *                        为 null 时 Python 用内置默认值兜底
     * @return {"updated":n}
     */
    public JSONObject applyFills(String chunkPath, String fillsJson, String fillFieldsJson) {
        return callWithRetry("apply_fills", "回写填充",
                py -> py.callAttr("apply_fills", chunkPath, fillsJson, fillFieldsJson));
    }

    // ==================== 容错机制 ====================

    /** Python 调用函数式接口 */
    private interface PyCall {
        PyObject call(PyObject module) throws Exception;
    }

    /**
     * 统一调用包装：初始化失败/异常/返回不可解析时均返回归一化错误 JSON（含 error 字段），
     * 瞬时异常（Python 解释器抖动）自动重试一次，绝不返回 null。
     */
    private JSONObject callWithRetry(String opName, String opDesc, PyCall call) {
        if (!ensureReady()) {
            String reason = getLastInitError() != null ? getLastInitError() : "Python 环境初始化失败";
            return errorJson(opDesc + "失败: " + reason);
        }
        Throwable last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                JSONObject r = parseResult(call.call(module));
                if (r != null) return r;
                last = new IllegalStateException("Python 返回内容无法解析为 JSON");
            } catch (Throwable t) {
                last = t;
                Log.w(TAG, opDesc + "第 " + (attempt + 1) + " 次调用失败: " + t.getMessage());
            }
            // 重试前尝试重新加载模块（解释器异常后兜底）
            if (attempt == 0) {
                initialized = false;
                if (!ensureReady()) {
                    return errorJson(opDesc + "失败: Python 环境不可用");
                }
            }
        }
        Log.e(TAG, opDesc + "重试后仍失败", last);
        return errorJson(opDesc + "失败: " + lastLine(last));
    }

    /**
     * 兼容 Python 侧多种返回形态：
     * 1. JSON 字符串（json.dumps，标准约定）；
     * 2. Python dict/list（toJava 转换后按 Map/List 构建）；
     * 3. 其他类型 → 归一化错误 JSON。
     */
    private JSONObject parseResult(PyObject r) {
        if (r == null) return null;
        try {
            String s = r.toString();
            if (s != null) {
                s = s.trim();
                if (s.startsWith("{")) {
                    return new JSONObject(s);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "JSON 字符串解析失败，尝试对象转换: " + e.getMessage());
        }
        try {
            // Python dict → java.util.Map 兜底路径
            Object java = r.toJava(Object.class);
            if (java instanceof java.util.Map) {
                return new JSONObject((java.util.Map<?, ?>) java);
            }
        } catch (Exception e) {
            Log.w(TAG, "Python 返回对象转换失败: " + e.getMessage());
        }
        return null;
    }

    /** 归一化错误 JSON（与 Python 侧 {"success":false,"error":...} 结构对齐） */
    private static JSONObject errorJson(String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("success", false);
            o.put("error", message == null || message.isEmpty() ? "未知错误" : message);
        } catch (Exception ignored) {
        }
        return o;
    }

    /** 提取异常信息末行（Python 异常 toString 常为多行堆栈，末行才是真实原因） */
    private static String lastLine(Throwable t) {
        if (t == null) return "未知错误";
        String msg = t.getMessage();
        if (msg == null || msg.isEmpty()) msg = String.valueOf(t);
        String[] lines = msg.split("\\n");
        String last = lines[lines.length - 1].trim();
        return last.isEmpty() ? msg.trim() : last;
    }
}
