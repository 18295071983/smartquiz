package com.oilquiz.app.ai.python;

import android.util.Base64;
import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.tool.AIToolResult;
import com.oilquiz.app.ai.tool.BaseAITool;
import com.oilquiz.app.ai.tool.FileReaderTool;
import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Tool(
    value = "python_execute",
    description = "执行Python代码。脚本内置android_ui模块(真实显示在手机界面)：系统原生组件 dialog/progress/input/choice(create_component→component_id→update/close/get_result 阻塞取结果)；内置UI组件库(create_component('类型', props={...}) 渲染成聊天流卡片，props带actions可交互；类型列表见 ui_component 工具 component_type 参数；web=网页卡片、image=图片卡片)；便捷函数 ask_input/ask_choice/show_progress；脚本最后print输出作为结果返回。环境预装库(可直接import，无需安装)：requests、beautifulsoup4(bs4)、jieba、lxml、regex、numpy(np)、pandas(pd)、matplotlib(plt)、Pillow(PIL)、openpyxl、yaml、tabulate、python-dateutil、chardet、xlrd、reportlab、python-docx(docx→Word读写)、python-pptx(pptx→PPT读写)、pypdf(PDF读取/合并/拆分)、XlsxWriter(xlsxwriter→Excel写入，pptx图表依赖)；读写 Word/PPT/PDF 直接用 docx/pptx/pypdf，无需再装；绘制图表用matplotlib(先设中文字体)或Pillow。运行时已内置pip模块(import pip可用)。安装新Python包：① 优先 action=pip_install(package=包名)（pip.main编程式安装到filesDir/python_user_packages，装后本会话即可import）；② 纯Python包(py3-none-any wheel)最稳用 pip_install 工具(自研下载器，不依赖pip)；③ 编程式: import pip; pip.main(['install','--target','<可写目录>','包名'])。【禁止】用 subprocess 或 python -m pip（Chaquopy无独立python可执行文件，必然失败）",
    category = "python",
    aliases = {"python", "run_python", "python_code"},
    actions = {
        @Action(name = "execute_code", description = "执行Python代码"),
        @Action(name = "execute_task", description = "执行任务描述"),
        @Action(name = "run_file", description = "执行一个.py文件(先创建再执行)"),
        @Action(name = "pip_install", description = "安装Python包(如pandas, openpyxl等)"),
        @Action(name = "list_modules", description = "列出可用的Python模块")
    },
    params = {
        @Param(name = "code", type = "string", description = "Python代码", required = false),
        @Param(name = "task", type = "string", description = "任务描述", required = false),
        @Param(name = "context", type = "object", description = "上下文数据", required = false),
        @Param(name = "package", type = "string", description = "包名(用于pip_install)", required = false),
        @Param(name = "file_path", type = "string", description = "Python文件路径(用于run_file)", required = false),
        @Param(name = "file_name", type = "string", description = "Python文件名(用于run_file创建文件)", required = false),
        @Param(name = "args", type = "string", description = "运行参数(用于run_file)", required = false),
        @Param(name = "timeout", type = "integer", description = "执行超时秒数(默认30，范围5~120)", required = false)
    }
)
public class PythonExecuteTool extends BaseAITool {
    private static final String TAG = "PythonExecuteTool";
    /** 代码长度上限（字符），防止超大代码拖垮执行 */
    private static final int MAX_CODE_LENGTH = 200 * 1024;
    /** 默认执行超时（秒） */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private final Context context;
    private final PythonToolManager toolManager;
    private final FileReaderTool fileReaderTool;
    // 匹配文件路径的正则（用于检测文件解析任务）
    private static final Pattern FILE_PATH_PATTERN = Pattern.compile(
            "[\"']?([/\\\\]?[\\w./\\\\-]+\\.(xlsx|xls|csv|json|xml|tsv))[\"']?");
    
    public PythonExecuteTool(Context context) {
        super("python_execute", "执行Python代码。脚本内置android_ui模块(真实显示在手机界面)：系统原生组件 dialog/progress/input/choice(create_component→component_id→update/close/get_result 阻塞取结果)；内置UI组件库(create_component('类型', props={...}) 渲染成聊天流卡片，props带actions可交互；类型列表见 ui_component 工具 component_type 参数；web=网页卡片、image=图片卡片)；便捷函数 ask_input/ask_choice/show_progress；脚本最后print输出作为结果返回。环境预装库(可直接import，无需安装)：requests、beautifulsoup4(bs4)、jieba、lxml、regex、numpy(np)、pandas(pd)、matplotlib(plt)、Pillow(PIL)、openpyxl、yaml、tabulate、python-dateutil、chardet、xlrd、reportlab、python-docx(docx→Word读写)、python-pptx(pptx→PPT读写)、pypdf(PDF读取/合并/拆分)、XlsxWriter(xlsxwriter→Excel写入，pptx图表依赖)；读写 Word/PPT/PDF 直接用 docx/pptx/pypdf，无需再装；绘制图表用matplotlib(先设中文字体)或Pillow。运行时已内置pip模块(import pip可用)。安装新Python包：① 优先 action=pip_install(package=包名)（pip.main编程式安装到filesDir/python_user_packages，装后本会话即可import）；② 纯Python包(py3-none-any wheel)最稳用 pip_install 工具(自研下载器，不依赖pip)；③ 编程式: import pip; pip.main(['install','--target','<可写目录>','包名'])。【禁止】用 subprocess 或 python -m pip（Chaquopy无独立python可执行文件，必然失败）");
        this.context = context.getApplicationContext();
        this.toolManager = PythonToolManager.getInstance(context);
        this.fileReaderTool = new FileReaderTool(context);
    }
    
    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        Log.i(TAG, "Executing Python code");
        
        try {
            // 检查是否是特殊 action
            String action = (String) parameters.get("action");
            if ("pip_install".equals(action)) {
                return handlePipInstall(parameters);
            }
            if ("list_modules".equals(action)) {
                return handleListModules();
            }
            if ("run_file".equals(action)) {
                return handleRunFile(parameters);
            }
            
            String code = (String) parameters.get("code");
            String task = (String) parameters.get("task");

            // 容错解析 context：可能为 Map 或 JSON 字符串
            Map<String, Object> contextData = null;
            Object ctxObj = parameters.get("context");
            if (ctxObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) ctxObj;
                contextData = m;
            } else if (ctxObj instanceof String) {
                try {
                    org.json.JSONObject jo = new org.json.JSONObject((String) ctxObj);
                    contextData = new java.util.HashMap<>();
                    java.util.Iterator<String> keys = jo.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        contextData.put(key, jo.opt(key));
                    }
                } catch (Exception ignored) {
                    Log.w(TAG, "context 参数解析失败，忽略");
                }
            }
            
            // 尝试 Python 执行
            int timeout = parseTimeout(parameters.get("timeout"));
            AIToolResult pythonResult = tryPythonExecute(code, task, contextData, timeout);
            
            // 如果 Python 成功，直接返回
            if (pythonResult != null && pythonResult.isSuccess()) {
                return pythonResult;
            }
            
            // Python 失败，检查是否是文件解析任务，尝试回退到 Java 文件解析
            String inputText = code != null ? code : (task != null ? task : "");
            AIToolResult fallbackResult = tryFileParseFallback(inputText, parameters);
            if (fallbackResult != null) {
                Log.i(TAG, "Python failed, fallback to Java file parser succeeded");
                return fallbackResult;
            }
            
            // 回退也失败或不是文件任务，返回 Python 原始结果
            if (pythonResult != null) return pythonResult;
            return AIToolResult.fail("Python 执行失败，且无法通过文件解析回退");
            
        } catch (Exception e) {
            Log.e(TAG, "Error executing Python tool: " + e.getMessage(), e);
            return AIToolResult.fail("执行失败: " + e.getMessage());
        }
    }
    
    /**
     * 处理 pip install 请求
     * 修复：Android 上 subprocess 调用 pip 会因 dalvik-cache 权限失败
     * 改用 pip.main() 直接调用 + --target 安装到应用可写目录
     */
    private AIToolResult handlePipInstall(Map<String, Object> parameters) {
        String packageName = (String) parameters.get("package");
        if (packageName == null || packageName.isEmpty()) {
            return AIToolResult.fail("缺少参数: package");
        }
        
        // 获取应用可写的用户目录作为安装目标
        String userPipDir = new java.io.File(context.getFilesDir(), "python_user_packages").getAbsolutePath();
        
        // 使用 pip.main() 直接调用，避免 subprocess 的 dalvik-cache 权限问题
        // PYTHONDONTWRITEBYTECODE=1 防止写入 .pyc 到只读目录
        String pipCode =
            "import os, sys\n" +
            "os.environ['PYTHONDONTWRITEBYTECODE'] = '1'\n" +
            "os.environ['PIP_NO_COMPILE'] = '1'\n" +
            "\n" +
            "user_pkg_dir = '" + userPipDir.replace("\\", "\\\\") + "'\n" +
            "os.makedirs(user_pkg_dir, exist_ok=True)\n" +
            "\n" +
            "# 将用户包目录加入 sys.path\n" +
            "if user_pkg_dir not in sys.path:\n" +
            "    sys.path.insert(0, user_pkg_dir)\n" +
            "\n" +
            "def try_pip_install(pkg, index_url, trusted_host):\n" +
            "    args = [\n" +
            "        'install', pkg,\n" +
            "        '--target', user_pkg_dir,\n" +
            "        '-i', index_url,\n" +
            "        '--trusted-host', trusted_host,\n" +
            "        '--no-compile',\n" +
            "        '--disable-pip-version-check',\n" +
            "        '--quiet'\n" +
            "    ]\n" +
            "    # 兼容不同 pip 版本的调用方式\n" +
            "    try:\n" +
            "        import pip\n" +
            "        if hasattr(pip, 'main'):\n" +
            "            rc = pip.main(args)\n" +
            "            return rc == 0, f'pip.main returned {rc}'\n" +
            "        elif hasattr(pip, '_internal') and hasattr(pip._internal, 'main'):\n" +
            "            rc = pip._internal.main(args)\n" +
            "            return rc == 0, f'pip._internal.main returned {rc}'\n" +
            "        else:\n" +
            "            from pip._internal.cli.base_command import main as pip_main\n" +
            "            rc = pip_main(args)\n" +
            "            return rc == 0, f'pip_main returned {rc}'\n" +
            "    except SystemExit as e:\n" +
            "        return e.code == 0 or e.code is None, f'SystemExit: {e.code}'\n" +
            "    except Exception as e:\n" +
            "        msg = str(e)\n" +
            "        if 'No module' in msg and 'pip' in msg:\n" +
            "            msg = ('运行时 pip 模块不可用(' + msg + ')\n" +
            "                   '建议改用 pip_install 工具(自研下载器不依赖pip，装纯Python包) 或检查 pip 是否随包内置')\n" +
            "        return False, msg\n" +
            "\n" +
            "pkg = '" + packageName.replace("'", "\\'") + "'\n" +
            "\n" +
            "# 尝试阿里云镜像\n" +
            "ok, msg = try_pip_install(pkg,\n" +
            "    'https://mirrors.aliyun.com/pypi/simple',\n" +
            "    'mirrors.aliyun.com')\n" +
            "\n" +
            "if ok:\n" +
            "    print(f'成功安装 {pkg}')\n" +
            "    print(f'安装目录: {user_pkg_dir}')\n" +
            "    # 验证导入\n" +
            "    import importlib\n" +
            "    mod_name = pkg.replace('-', '_')\n" +
            "    try:\n" +
            "        importlib.invalidate_caches()\n" +
            "        mod = importlib.import_module(mod_name)\n" +
            "        ver = getattr(mod, '__version__', 'unknown')\n" +
            "        print(f'验证导入成功: {mod_name} v{ver}')\n" +
            "    except ImportError:\n" +
            "        print(f'包已安装，但导入名称可能不同，请查阅文档确认 import 名称')\n" +
            "else:\n" +
            "    print(f'阿里云镜像失败: {msg}')\n" +
            "    print('尝试清华备用镜像...')\n" +
            "    ok2, msg2 = try_pip_install(pkg,\n" +
            "        'https://pypi.tuna.tsinghua.edu.cn/simple',\n" +
            "        'pypi.tuna.tsinghua.edu.cn')\n" +
            "    if ok2:\n" +
            "        print(f'通过清华镜像成功安装 {pkg}')\n" +
            "    else:\n" +
            "        print(f'安装失败: {msg2}')\n";
        
        AIToolResult result = tryPythonExecute(pipCode, null, null, DEFAULT_TIMEOUT_SECONDS);
        if (result != null && result.isSuccess()) {
            return result;
        }
        return AIToolResult.fail("pip install " + packageName + " 失败，请检查包名是否正确或网络是否可用");
    }
    
    /**
     * 列出可用的 Python 模块
     */
    private AIToolResult handleListModules() {
        String listCode =
            "import sys, importlib.util\n" +
            "modules = []\n" +
            "# 检查标准库\n" +
            "stdlib = ['os','sys','json','csv','re','math','random','time','datetime',\n" +
            "  'hashlib','base64','sqlite3','xml','pathlib','tempfile','shutil','glob',\n" +
            "  'zipfile','pickle','statistics','decimal','collections','itertools',\n" +
            "  'functools','urllib','http','html','enum','dataclasses','logging',\n" +
            "  'configparser','string','textwrap','difflib','copy','struct','codecs',\n" +
            "  'bisect','heapq','array','queue','fnmatch','gzip','operator','calendar']\n" +
            "for m in stdlib:\n" +
            "  spec = importlib.util.find_spec(m)\n" +
            "  if spec:\n" +
            "    modules.append(('stdlib', m))\n" +
            "# 检查第三方库\n" +
            "third_party = ['requests','numpy','PIL','bs4','lxml','jieba','regex',\n" +
            "  'openpyxl','yaml','tabulate','dateutil','chardet','xlrd','pandas','matplotlib',\n" +
            "  'docx','pptx','pypdf','xlsxwriter']\n" +
            "for m in third_party:\n" +
            "  spec = importlib.util.find_spec(m)\n" +
            "  if spec:\n" +
            "    modules.append(('third-party', m))\n" +
            "print('=== 可用模块 ===')\n" +
            "for cat, name in modules:\n" +
            "  print(f'  [{cat}] {name}')\n" +
            "print(f'\\n总计: {len(modules)} 个模块')\n";
        
        AIToolResult result = tryPythonExecute(listCode, null, null, DEFAULT_TIMEOUT_SECONDS);
        if (result != null && result.isSuccess()) {
            return result;
        }
        // 回退：返回静态列表
        Map<String, Object> info = new HashMap<>();
        info.put("standard_library", new String[]{
            "os", "sys", "json", "csv", "re", "math", "random", "time", "datetime",
            "hashlib", "base64", "sqlite3", "xml", "pathlib", "tempfile", "shutil",
            "glob", "zipfile", "pickle", "statistics", "decimal", "collections",
            "itertools", "functools", "urllib", "http", "html", "enum", "dataclasses",
            "logging", "configparser", "string", "textwrap", "difflib", "copy",
            "struct", "codecs", "bisect", "heapq", "array", "queue", "fnmatch",
            "gzip", "operator", "calendar"
        });
        info.put("third_party", new String[]{
            "requests", "numpy", "PIL/Pillow", "bs4/BeautifulSoup", "lxml", "jieba", "regex",
            "openpyxl", "pyyaml", "tabulate", "python-dateutil", "chardet", "xlrd", "pandas",
            "matplotlib",
            "python-docx/docx(Word读写)", "python-pptx/pptx(PPT读写)", "pypdf(PDF读取/合并/拆分)",
            "XlsxWriter/xlsxwriter(Excel写入)"
        });
        return new AIToolResult(info, null);
    }
    
    /**
     * 处理 run_file action：创建并/或执行 Python 文件
     */
    private AIToolResult handleRunFile(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        String code = (String) parameters.get("code");
        String fileName = (String) parameters.get("file_name");
        String args = (String) parameters.get("args");
        
        if (fileName == null || fileName.isEmpty()) {
            fileName = "agent_script_" + System.currentTimeMillis() + ".py";
        }
        
        String pyCode;
        if (code != null && !code.isEmpty() && filePath == null) {
            // 有代码无路径：用 base64 安全传输代码，创建文件并执行
            String codeBase64 = Base64.encodeToString(code.getBytes(java.nio.charset.StandardCharsets.UTF_8), Base64.NO_WRAP);
            String escapedName = fileName.replace("'", "\\'");
            String argsPart = "";
            if (args != null && !args.isEmpty()) {
                String argsBase64 = Base64.encodeToString(args.getBytes(java.nio.charset.StandardCharsets.UTF_8), Base64.NO_WRAP);
                argsPart = ", __import__('base64').b64decode('" + argsBase64 + "').decode()";
            }
            pyCode =
                "import base64, os\n" +
                "from android_helper import create_python_file, run_python_file\n" +
                "_code = base64.b64decode('" + codeBase64 + "').decode('utf-8')\n" +
                "_path = create_python_file('" + escapedName + "', _code)\n" +
                "_result = run_python_file(_path" + argsPart + ")\n" +
                "print(f'文件: {_path}')\n" +
                "print('输出:')\n" +
                "print(_result)";
        } else if (filePath != null && !filePath.isEmpty()) {
            // 有文件路径：直接执行
            String escapedPath = filePath.replace("'", "\\'");
            String argsPart = "";
            if (args != null && !args.isEmpty()) {
                String argsBase64 = Base64.encodeToString(args.getBytes(java.nio.charset.StandardCharsets.UTF_8), Base64.NO_WRAP);
                argsPart = ", __import__('base64').b64decode('" + argsBase64 + "').decode()";
            }
            pyCode =
                "from android_helper import run_python_file\n" +
                "_result = run_python_file('" + escapedPath + "'" + argsPart + ")\n" +
                "print(_result)";
        } else {
            return AIToolResult.fail("需要提供 code(代码) 或 file_path(文件路径)");
        }
        
        AIToolResult result = tryPythonExecute(pyCode, null, null, DEFAULT_TIMEOUT_SECONDS);
        if (result != null && result.isSuccess()) {
            return result;
        }
        return AIToolResult.fail("Python 文件执行失败");
    }
    
    private AIToolResult tryPythonExecute(String code, String task, Map<String, Object> contextData, int timeout) {
        try {
            if (!toolManager.isInitialized()) {
                if (!toolManager.initialize()) {
                    Log.w(TAG, "Python init failed, will try file parse fallback");
                    return null;
                }
            }
            
            PythonToolManager.ExecutionResult result;
            if (code != null && !code.isEmpty()) {
                // 代码长度上限：防止超大代码拖垮执行（预导入 + exec 均耗时）
                if (code.length() > MAX_CODE_LENGTH) {
                    return AIToolResult.fail("代码过长（" + code.length() + " > " + MAX_CODE_LENGTH
                            + "字符），请拆分执行或改用文件");
                }
                String safeCode = buildSafeCode(code);
                result = toolManager.executeCode(safeCode, contextData, timeout);
            } else if (task != null && !task.isEmpty()) {
                result = toolManager.processTask(task, contextData);
            } else {
                return null;
            }
            return formatResult(result);
        } catch (Exception e) {
            Log.e(TAG, "Python execution error: " + e.getMessage(), e);
            // 返回带详细错误信息的结果，而不是 null
            Map<String, Object> info = new HashMap<>();
            info.put("error_type", e.getClass().getSimpleName());
            info.put("error_message", e.getMessage());
            return new AIToolResult(
                "执行失败: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                info,
                false
            );
        }
    }

    /** 解析超时参数（秒）：默认 30，范围 5~120 */
    private int parseTimeout(Object v) {
        int timeout = DEFAULT_TIMEOUT_SECONDS;
        if (v instanceof Number) {
            timeout = ((Number) v).intValue();
        } else if (v != null) {
            try {
                timeout = Integer.parseInt(String.valueOf(v).trim());
            } catch (Exception ignored) {
            }
        }
        return Math.max(5, Math.min(120, timeout));
    }
    
    /**
     * 当 Python 执行失败时，检查输入是否包含文件解析任务，
     * 如果是则自动回退到 Java 内置的文件解析工具。
     */
    private AIToolResult tryFileParseFallback(String inputText, Map<String, Object> parameters) {
        if (inputText == null || inputText.isEmpty()) return null;
        
        Matcher matcher = FILE_PATH_PATTERN.matcher(inputText);
        if (!matcher.find()) return null; // 没有检测到文件路径
        
        String filePath = matcher.group(1);
        String ext = matcher.group(2).toLowerCase();
        Log.i(TAG, "File parse fallback detected: " + filePath + " (" + ext + ")");
        
        // 预检查文件是否存在
        java.io.File file = new java.io.File(filePath);
        if (!file.exists()) {
            // 尝试在应用目录中查找
            java.io.File appFile = new java.io.File(context.getFilesDir(), filePath);
            if (appFile.exists()) {
                filePath = appFile.getAbsolutePath();
            } else {
                Log.w(TAG, "Fallback file not found: " + filePath);
                return null;
            }
        }
        
        // 根据文件扩展名选择具体解析方式
        String action;
        switch (ext) {
            case "xlsx": case "xls":
                action = "parse_excel";
                break;
            case "csv": case "tsv":
                action = "parse_csv";
                break;
            case "json":
                action = "parse_json";
                break;
            case "xml":
                action = "parse_xml";
                break;
            default:
                action = "read";
                break;
        }
        
        Map<String, Object> fileParams = new HashMap<>();
        fileParams.put("file_path", filePath);
        fileParams.put("action", action);
        
        try {
            AIToolResult result = fileReaderTool.execute(fileParams);
            if (result != null && result.isSuccess()) {
                Log.i(TAG, "File parse fallback succeeded with action=" + action);
            } else {
                Log.w(TAG, "File parse fallback returned success=false, action=" + action);
            }
            return result;
        } catch (Exception e) {
            Log.w(TAG, "File parse fallback also failed: " + e.getMessage());
            return null;
        }
    }
    
    private String buildSafeCode(String code) {
        StringBuilder sb = new StringBuilder();
        sb.append("# -*- coding: utf-8 -*-\n");
        
        // ===== 路径设置（优先，确保辅助模块和用户包可找到） =====
        sb.append("import sys, os\n");
        sb.append("sys.path.insert(0, '.')\n");
        sb.append("sys.path.insert(0, os.path.dirname(os.path.abspath('.')))\n");
        sb.append("sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath('.')), 'python'))\n");
        // pip 动态安装的包存放在用户目录
        String userPipDir = new java.io.File(context.getFilesDir(), "python_user_packages").getAbsolutePath();
        sb.append("sys.path.insert(0, '").append(userPipDir.replace("\\", "\\\\")).append("')\n\n");
        
        // ===== 标准库：基础与系统（Chaquopy保证可用） =====
        sb.append("import io, re, math, random, time, datetime, json, csv\n");
        sb.append("import hashlib, base64, string, textwrap, difflib\n");
        sb.append("import copy, struct, codecs, unicodedata\n");
        
        // ===== 标准库：数据结构 =====
        sb.append("from collections import Counter, OrderedDict, defaultdict, deque, namedtuple\n");
        sb.append("from itertools import chain, product, combinations, permutations, groupby\n");
        sb.append("from functools import reduce, partial, lru_cache, wraps\n");
        sb.append("import bisect, heapq, array, queue\n");
        
        // ===== 标准库：文件与路径 =====
        sb.append("import pathlib, tempfile, shutil, glob, fnmatch\n");
        sb.append("import zipfile, gzip, pickle\n");
        
        // ===== 标准库：网络与HTTP =====
        sb.append("import urllib.request, urllib.parse, urllib.error\n");
        sb.append("import http.client, html\n");
        
        // ===== 标准库：数据与统计 =====
        sb.append("import statistics, decimal, fractions\n");
        sb.append("import sqlite3, configparser, argparse, logging\n");
        
        // ===== 标准库：XML =====
        sb.append("import xml.etree.ElementTree as ET\n");
        
        // ===== 标准库：类型与枚举 =====
        sb.append("import enum, dataclasses, typing, contextlib\n");
        sb.append("import operator, calendar, locale\n\n");
        
        // ===== 第三方库：安全加载（失败不影响执行） =====
        // 轻量级纯Python库可以安全加载
        sb.append("# 第三方库安全加载\n");
        sb.append("_libs_loaded = []\n");
        String[] safeLibs = {
            "requests:import requests",
            "bs4:from bs4 import BeautifulSoup",
            "jieba:import jieba",
            "yaml:import yaml",
            "tabulate:from tabulate import tabulate",
            "dateutil:from dateutil import parser as dateutil_parser",
            "chardet:import chardet",
            "xlrd:import xlrd",
            "openpyxl:import openpyxl",
        };
        for (String lib : safeLibs) {
            String[] parts = lib.split(":", 2);
            sb.append("try:\n");
            sb.append("    ").append(parts[1]).append("\n");
            sb.append("    _libs_loaded.append('").append(parts[0]).append("')\n");
            sb.append("except Exception:\n");
            sb.append("    pass\n");
        }
        // 含 native C 扩展的库不自动加载，避免 SIGSEGV
        // Agent 代码中可按需 import numpy/pandas/PIL/lxml/regex/matplotlib
        // 文档类（纯 Python，但会拉起 lxml/Pillow）同样不自动加载，Agent 按需 import：
        //   读/写 Word: import docx（python-docx）    读/写 PPT: import pptx（python-pptx）
        //   PDF: import pypdf                          Excel 写入: import xlsxwriter
        sb.append("# numpy/PIL/lxml/regex/pandas/matplotlib 含 native 扩展，需 Agent 代码中显式 import\n");
        sb.append("# 文档类已预装、按需 import：docx(python-docx) / pptx(python-pptx) / pypdf / xlsxwriter\n\n");
        
        // ===== Android 辅助模块 =====
        sb.append("# Android 环境辅助模块（文件/CSV/SQLite/XML/数据统计）\n");
        sb.append("try:\n");
        sb.append("    from android_helper import *\n");
        sb.append("except Exception:\n");
        sb.append("    pass\n\n");
        
        sb.append(code);
        return sb.toString();
    }
    
    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("code", "string, 要执行的 Python 代码（可选，上限200KB）");
        params.put("task", "string, 任务描述，如 '计算 3+5*2'、'分析数据 [1,2,3,4,5]'（可选）");
        params.put("context", "object, 上下文数据（可选）");
        params.put("timeout", "int, 执行超时秒数（默认30，范围5~120）");
        return params;
    }
    
    private AIToolResult formatResult(PythonToolManager.ExecutionResult result) {
        Map<String, Object> additionalInfo = new HashMap<>();
        additionalInfo.put("attempts", result.attempts);
        additionalInfo.put("success", result.success);
        
        if (result.code != null && !result.code.isEmpty()) {
            additionalInfo.put("code", result.code);
        }
        
        if (!result.fixes.isEmpty()) {
            additionalInfo.put("fixes_applied", result.fixes.size());
        }
        
        if (result.success) {
            StringBuilder output = new StringBuilder();
            output.append("执行成功\n\n");
            
            if (result.stdout != null && !result.stdout.isEmpty()) {
                output.append("=== 输出 ===\n");
                output.append(result.stdout).append("\n\n");
            }
            
            if (result.result != null && !result.result.isEmpty()) {
                output.append("=== 结果 ===\n");
                output.append(result.result).append("\n");
            }
            
            if (!result.fixes.isEmpty()) {
                output.append("\n=== 自动修复 ===\n");
                for (Map<String, String> fix : result.fixes) {
                    String type = fix.get("error_type");
                    if (type != null) {
                        output.append("- 修复: ").append(type).append("\n");
                    }
                }
            }
            
            additionalInfo.put("stdout", result.stdout);
            additionalInfo.put("result", result.result);
            
            return new AIToolResult(output.toString(), additionalInfo, true);
        } else {
            StringBuilder output = new StringBuilder();
            output.append("执行失败\n\n");
            
            output.append("尝试次数: ").append(result.attempts).append("\n\n");
            
            if (result.error != null) {
                output.append("=== 错误 ===\n");
                output.append(result.error).append("\n\n");
            }
            
            if (result.stderr != null && !result.stderr.isEmpty()) {
                output.append("=== 错误输出 ===\n");
                output.append(result.stderr).append("\n\n");
            }
            
            if (result.stdout != null && !result.stdout.isEmpty()) {
                output.append("=== 部分输出 ===\n");
                output.append(result.stdout).append("\n");
            }
            
            if (!result.fixes.isEmpty()) {
                output.append("\n=== 尝试的修复 ===\n");
                for (Map<String, String> fix : result.fixes) {
                    String type = fix.get("error_type");
                    if (type != null) {
                        output.append("- 尝试修复: ").append(type).append("\n");
                    }
                }
            }
            
            additionalInfo.put("error", result.error);
            additionalInfo.put("stderr", result.stderr);
            
            return new AIToolResult(output.toString(), additionalInfo, false);
        }
    }
}
