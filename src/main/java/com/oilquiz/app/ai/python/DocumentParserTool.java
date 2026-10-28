package com.oilquiz.app.ai.python;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.tool.AIToolResult;
import com.oilquiz.app.ai.tool.BaseAITool;
import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;

import java.util.HashMap;
import java.util.Map;

/**
 * Python 文件读取与解析工具（预置脚本：标准库 + csv/json/xml + openpyxl + python-docx/python-pptx/pypdf）。
 *
 * <p><b>只做读</b>：
 * <ul>
 *   <li>read：读文本（UTF-8 → GB18030 → UTF-16 自动尝试编码）</li>
 *   <li>parse：解析 CSV(RFC4180标准库)/JSON/XML/Excel(.xlsx, openpyxl)/Word(.docx, python-docx，含表格)/
 *       PPT(.pptx, python-pptx，含表格与备注)/PDF(pypdf，含空密码解密；老格式 .doc/.ppt 提示另存新格式)</li>
 * </ul>
 *
 * <p>写侧已于 2026-10-28 整体迁到 {@code file_generator}（create/write/append/edit/diff）并从这里删除：
 * write→write、append→append、edit→edit、replace→edit(replace_all=true)、diff→diff。
 * 迁移理由是"写个文件不该起 Python 解释器"，也是模型把 python 当万能锤的一个入口。</p>
 *
 * <p>与 Java 版 file_reader/file_generator 互补：Python csv 标准库严格按 RFC4180 解析，
 * Excel/Word/PPT/PDF 的取值也走熟库。</p>
 */
@Tool(
    value = "document_parser",
    description = "文档解析（只读）：从 PDF / Word(docx) / PPT(pptx) / Excel(xlsx) / CSV / JSON / XML 里取正文、表格与备注，也能读任意文本。read=读文本(自动检测编码)；parse=按格式解析。**不写文件**——写/追加/改/看差异请用 file_generator(create/write/append/edit/diff)。底层用内置 docx/pptx/pypdf/openpyxl，调用方不用写代码。注意：工作区 tmp/ 是临时区，每轮对话结束自动清理；重要文件写到 files/ 长期区(不自动清，要清用 workspace(action=clear, confirm=true))",
    category = "file",
    aliases = {"python_file_ops", "python文件", "py_file", "python_file", "文件脚本", "文档解析"},
    actions = {
        @Action(name = "read", description = "读取文本文件(自动检测编码)"),
        @Action(name = "parse", description = "解析CSV/JSON/XML/Excel/Word/PPT/PDF文件")
    },
    params = {
        @Param(name = "action", type = "string", description = "操作: read/parse", required = true),
        @Param(name = "file_path", type = "string", description = "文件路径", required = true),
        @Param(name = "format", type = "string", description = "解析格式(parse用: csv/tsv/json/xml/xlsx/docx/pptx/pdf，留空按扩展名)", required = false),
        @Param(name = "max_rows", type = "integer", description = "最大行数(parse用，默认500)", required = false),
        @Param(name = "max_chars", type = "integer", description = "最大输出字符数(默认8000)", required = false),
        @Param(name = "sheet_index", type = "integer", description = "工作表索引(parse xlsx用，默认0)", required = false)
    }
)
public class DocumentParserTool extends BaseAITool {
    private static final String TAG = "DocumentParserTool";
    private final PythonToolManager toolManager;
    /** Python 侧执行预算（秒）：以前直接吃 PythonToolManager 的默认 30，与框架超时同点撞车；此处显式化，申报走 {@link PythonToolBudget} */
    private static final int EXEC_BUDGET_SECONDS = 30;

    public DocumentParserTool(Context context) {
        super("document_parser", "Python文件工具：读取与解析文件(标准库+openpyxl+docx/pptx/pypdf)");
        this.toolManager = PythonToolManager.getInstance(context);
    }

    /** 申报单次执行所需超时（毫秒），口径见 {@link PythonToolBudget} */
    @Override
    public long executionTimeoutMs(Map<String, Object> args) {
        return PythonToolBudget.declaredMs(EXEC_BUDGET_SECONDS);
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            if (!toolManager.isInitialized()) {
                if (!toolManager.initialize()) {
                    return AIToolResult.fail("Python 工具初始化失败，请检查 Chaquopy 配置");
                }
            }
            String action = parameters.get("action") != null
                    ? String.valueOf(parameters.get("action")) : "read";
            // 老会话/缓存 schema 里可能还带着 write/edit/diff：必须显式失败。
            // 若这里静默落到 read，模型会以为"写成功了"，而文件其实一个字节没变——比报错危险得多。
            String removed = removedActionMessage(action);
            if (removed != null) {
                return AIToolResult.fail(removed);
            }
            // 只读：不再有写动作，也就没有 diff 快照/变更卡
            String code = buildCode(action, parameters);
            PythonToolManager.ExecutionResult result = toolManager.executeCode(code, null, EXEC_BUDGET_SECONDS);
            return formatResult(result, action);
        } catch (Exception e) {
            Log.e(TAG, "Error: " + e.getMessage(), e);
            return AIToolResult.fail("Python文件工具失败: " + errText(e));
        }
    }

    /**
     * 已移除的写动作 → 明确的失败文案（纯函数，便于单测）。
     *
     * <p>为什么必须显式失败：{@link #buildCode} 对未知 action 会落到 read。老会话的缓存 schema 里
     * 还留着 {@code action=write}，若直接落到 read，模型会看到"文件内容"并以为写入成功——
     * 静默错误比报错难查得多。</p>
     *
     * @return null 表示这个 action 仍然支持
     */
    static String removedActionMessage(String action) {
        if (action == null) return null;
        switch (action) {
            case "write":
            case "append":
            case "edit":
            case "replace":
            case "diff":
                return "document_parser 已不再支持 action=" + action
                        + "：写文件/追加/改文件/差异预览已迁到 file_generator"
                        + "（create/write/append/edit/diff），请改调它";
            default:
                return null;
        }
    }

    /**
     * 只有读取与解析两条路。
     *
     * <p>写文件/追加/改文件/预览差异已整体迁到 {@code file_generator}（create/write/append/edit/diff），
     * 这里不再保留任何写动作的脚本——"名字带 python 的写入口"本身就是模型走捷径的理由。</p>
     */
    private String buildCode(String action, Map<String, Object> parameters) {
        String path = strParam(parameters, "file_path", "");
        String format = strParam(parameters, "format", null);
        int maxRows = intParam(parameters, "max_rows", 500);
        int maxChars = intParam(parameters, "max_chars", 8000);
        int sheetIndex = intParam(parameters, "sheet_index", 0);

        if ("parse".equals(action)) {
            return buildParseScript(path, format, maxRows, maxChars, sheetIndex);
        }
        return buildReadScript(path, maxChars);
    }

    /** 读文本：UTF-8 → GB18030 → UTF-16 自动尝试 */
    private String buildReadScript(String path, int maxChars) {
        return String.format(
            "# -*- coding: utf-8 -*-\n" +
            "import os\n" +
            "path = %s\n" +
            "max_chars = %d\n" +
            "\n" +
            "if not os.path.exists(path):\n" +
            "    print('文件不存在: ' + path)\n" +
            "    raise SystemExit\n" +
            "\n" +
            "content = None\n" +
            "used = ''\n" +
            "for enc in ('utf-8', 'gb18030', 'utf-16'):\n" +
            "    try:\n" +
            "        with open(path, 'r', encoding=enc) as f:\n" +
            "            content = f.read()\n" +
            "        used = enc\n" +
            "        break\n" +
            "    except (UnicodeDecodeError, UnicodeError):\n" +
            "        continue\n" +
            "if content is None:\n" +
            "    with open(path, 'rb') as f:\n" +
            "        content = f.read().decode('utf-8', errors='replace')\n" +
            "    used = 'utf-8(替换无效字节)'\n" +
            "\n" +
            "size = os.path.getsize(path)\n" +
            "lines = content.count('\\n') + (0 if content.endswith('\\n') else 1)\n" +
            "print('文件: ' + path)\n" +
            "print('大小: %%d字节' %% size)\n" +
            "print('行数: %%d' %% lines)\n" +
            "print('编码: ' + used)\n" +
            "print('==内容==')\n" +
            "if len(content) > max_chars:\n" +
            "    print(content[:max_chars] + '\\n...(截断，原始%%d字符)' %% len(content))\n" +
            "else:\n" +
            "    print(content)\n",
            quoteString(path), maxChars
        );
    }

    /** 解析 CSV/JSON/XML/Excel */
    private String buildParseScript(String path, String format, int maxRows, int maxChars, int sheetIndex) {
        return String.format(
            "# -*- coding: utf-8 -*-\n" +
            "import os, json, sys\n" +
            "path = %s\n" +
            "format_hint = %s\n" +
            "max_rows = %d\n" +
            "max_chars = %d\n" +
            "sheet_index = %d\n" +
            "\n" +
            "if not os.path.exists(path):\n" +
            "    print('文件不存在: ' + path)\n" +
            "    raise SystemExit\n" +
            "\n" +
            "ext = os.path.splitext(path)[1].lower().lstrip('.')\n" +
            "fmt = (format_hint or ext).lower()\n" +
            "\n" +
            "if fmt in ('csv', 'tsv'):\n" +
            "    import csv\n" +
            "    delim = '\\t' if fmt == 'tsv' else ','\n" +
            "    rows = []\n" +
            "    with open(path, 'r', encoding='utf-8', errors='replace', newline='') as f:\n" +
            "        reader = csv.reader(f, delimiter=delim)\n" +
            "        for i, r in enumerate(reader):\n" +
            "            if i > max_rows:\n" +
            "                break\n" +
            "            rows.append(r)\n" +
            "    if rows:\n" +
            "        print('表头: ' + ' | '.join(rows[0]))\n" +
            "    print('行数: %%d' %% (len(rows) - 1 if rows else 0))\n" +
            "    print('==数据==')\n" +
            "    for r in rows[1:max_rows + 1]:\n" +
            "        print(' | '.join(r))\n" +
            "\n" +
            "elif fmt == 'json':\n" +
            "    with open(path, 'r', encoding='utf-8', errors='replace') as f:\n" +
            "        obj = json.load(f)\n" +
            "    s = json.dumps(obj, ensure_ascii=False, indent=1)\n" +
            "    print('类型: ' + type(obj).__name__)\n" +
            "    print('==内容==')\n" +
            "    if len(s) > max_chars:\n" +
            "        print(s[:max_chars] + '\\n...(截断，原始%%d字符)' %% len(s))\n" +
            "    else:\n" +
            "        print(s)\n" +
            "\n" +
            "elif fmt == 'xml':\n" +
            "    import xml.etree.ElementTree as ET\n" +
            "    tree = ET.parse(path)\n" +
            "    root = tree.getroot()\n" +
            "    print('根元素: ' + root.tag)\n" +
            "    items = []\n" +
            "    for el in root.iter():\n" +
            "        if el.text and el.text.strip():\n" +
            "            items.append(el.tag + ': ' + el.text.strip()[:120])\n" +
            "        if len(items) >= 50:\n" +
            "            break\n" +
            "    for it in items:\n" +
            "        print(it)\n" +
            "\n" +
            "elif fmt in ('xlsx', 'xls'):\n" +
            "    try:\n" +
            "        import openpyxl\n" +
            "    except Exception as e:\n" +
            "        print('openpyxl不可用: ' + str(e))\n" +
            "        raise SystemExit\n" +
            "    wb = openpyxl.load_workbook(path, read_only=True, data_only=True)\n" +
            "    print('工作表: ' + ', '.join(wb.sheetnames))\n" +
            "    names = wb.sheetnames\n" +
            "    if sheet_index >= len(names):\n" +
            "        sheet_index = 0\n" +
            "    ws = wb[names[sheet_index]]\n" +
            "    print('当前表: ' + names[sheet_index])\n" +
            "    count = 0\n" +
            "    for row in ws.iter_rows(values_only=True):\n" +
            "        if row is None:\n" +
            "            continue\n" +
            "        cells = ['' if c is None else str(c) for c in row]\n" +
            "        if not any(str(c).strip() for c in cells):\n" +
            "            continue\n" +
            "        print(' | '.join(cells))\n" +
            "        count += 1\n" +
            "        if count >= max_rows:\n" +
            "            break\n" +
            "    print('输出行数: %%d' %% count)\n" +
            "    wb.close()\n" +
            "\n" +
            "elif fmt == 'docx':\n" +
            "    try:\n" +
            "        import docx\n" +
            "    except Exception as e:\n" +
            "        print('python-docx不可用: ' + str(e))\n" +
            "        raise SystemExit\n" +
            "    doc = docx.Document(path)\n" +
            "    print('段落数: %%d' %% len(doc.paragraphs))\n" +
            "    print('表格数: %%d' %% len(doc.tables))\n" +
            "    print('==正文==')\n" +
            "    used = 0\n" +
            "    for p in doc.paragraphs:\n" +
            "        t = (p.text or '').strip()\n" +
            "        if not t:\n" +
            "            continue\n" +
            "        print(t)\n" +
            "        used += len(t)\n" +
            "        if used >= max_chars:\n" +
            "            print('...(正文超长已截断)')\n" +
            "            break\n" +
            "    for ti, tb in enumerate(doc.tables[:5]):\n" +
            "        print('==表格%%d==' %% (ti + 1))\n" +
            "        for ri, row in enumerate(tb.rows):\n" +
            "            if ri >= max_rows:\n" +
            "                break\n" +
            "            print(' | '.join((c.text or '').strip() for c in row.cells))\n" +
            "\n" +
            "elif fmt == 'pptx':\n" +
            "    try:\n" +
            "        from pptx import Presentation\n" +
            "    except Exception as e:\n" +
            "        print('python-pptx不可用: ' + str(e))\n" +
            "        raise SystemExit\n" +
            "    prs = Presentation(path)\n" +
            "    print('幻灯片数: %%d' %% len(prs.slides))\n" +
            "    for si, slide in enumerate(prs.slides):\n" +
            "        if si >= max_rows:\n" +
            "            break\n" +
            "        print('==第%%d页==' %% (si + 1))\n" +
            "        for shape in slide.shapes:\n" +
            "            if getattr(shape, 'has_text_frame', False):\n" +
            "                t = shape.text_frame.text.strip()\n" +
            "                if t:\n" +
            "                    print(t)\n" +
            "            if getattr(shape, 'has_table', False):\n" +
            "                for row in shape.table.rows:\n" +
            "                    print(' | '.join((c.text or '').strip() for c in row.cells))\n" +
            "        try:\n" +
            "            nt = slide.notes_slide.notes_text_frame.text.strip()\n" +
            "            if nt:\n" +
            "                print('[备注] ' + nt)\n" +
            "        except Exception:\n" +
            "            pass\n" +
            "\n" +
            "elif fmt == 'pdf':\n" +
            "    try:\n" +
            "        import pypdf\n" +
            "    except Exception as e:\n" +
            "        print('pypdf不可用: ' + str(e))\n" +
            "        raise SystemExit\n" +
            "    reader = pypdf.PdfReader(path)\n" +
            "    if getattr(reader, 'is_encrypted', False):\n" +
            "        try:\n" +
            "            reader.decrypt('')\n" +
            "            print('[提示] PDF 已加密，用空密码解开')\n" +
            "        except Exception:\n" +
            "            print('PDF 已加密且需要密码，无法解析')\n" +
            "            raise SystemExit\n" +
            "    print('页数: %%d' %% len(reader.pages))\n" +
            "    used = 0\n" +
            "    for pi, page in enumerate(reader.pages):\n" +
            "        if pi >= max_rows:\n" +
            "            break\n" +
            "        try:\n" +
            "            t = (page.extract_text() or '').strip()\n" +
            "        except Exception as e:\n" +
            "            t = '[第%%d页提取失败: %%s]' %% (pi + 1, e)\n" +
            "        if not t:\n" +
            "            continue\n" +
            "        print('==第%%d页==' %% (pi + 1))\n" +
            "        print(t)\n" +
            "        used += len(t)\n" +
            "        if used >= max_chars:\n" +
            "            print('...(超长已截断)')\n" +
            "            break\n" +
            "\n" +
            "elif fmt in ('doc', 'ppt'):\n" +
            "    print('暂不支持老格式 .' + fmt + '（python-docx/python-pptx 只处理 .docx/.pptx），请先另存为新格式')\n" +
            "\n" +
            "else:\n" +
            "    print('不支持的格式: ' + fmt + '（支持 csv/tsv/json/xml/xlsx/docx/pptx/pdf）')\n",
            quoteString(path), quoteString(format), maxRows, maxChars, sheetIndex
        );
    }

    private AIToolResult formatResult(PythonToolManager.ExecutionResult result, String action) {
        Map<String, Object> info = new HashMap<>();
        info.put("action", action);
        info.put("attempts", result.attempts);
        if (result.success) {
            String output = result.stdout != null && !result.stdout.isEmpty()
                    ? result.stdout : (result.result != null ? result.result : "");
            info.put("stdout", output);
            return new AIToolResult(output, info, true);
        }
        String error = result.error != null ? result.error
                : (result.stderr != null && !result.stderr.isEmpty() ? result.stderr : "未知错误");
        info.put("error", error);
        return new AIToolResult("Python文件工具失败: " + error, info, false);
    }

    // ===== 参数工具 =====

    private String strParam(Map<String, Object> p, String key, String def) {
        Object v = p.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private int intParam(Map<String, Object> p, String key, int def) {
        Object v = p.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private String quoteString(String s) {
        if (s == null) return "None";
        return "\"" + s.replace("\\", "\\\\")
                      .replace("\"", "\\\"")
                      .replace("\n", "\\n")
                      .replace("\r", "\\r")
                      .replace("\t", "\\t") + "\"";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("action", "操作: read(读文本)/parse(解析CSV/JSON/XML/Excel/Word docx/PPT pptx/PDF)");
        params.put("file_path", "文件路径(必填)");
        params.put("format", "解析格式(parse用: csv/tsv/json/xml/xlsx/docx/pptx/pdf，留空按扩展名)");
        params.put("max_rows", "最大行数(parse用，默认500)");
        params.put("max_chars", "最大输出字符数(默认8000)");
        params.put("sheet_index", "工作表索引(parse xlsx用，默认0)");
        return params;
    }
}
