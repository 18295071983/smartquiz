package com.oilquiz.app.ai.tool;

import android.content.Context;

import com.oilquiz.app.ai.agent.online.AgentWorkspace;
import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;
import com.oilquiz.app.ai.tool.openai.ParamDefinition;
import com.oilquiz.app.ai.tool.openai.StructuredParamTool;
import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 工作区工具：管理 Agent 产生的文件（图片、导出、临时数据）。
 *
 * 动作：
 * - list: 列出工作区文件（名称/大小/时间）
 * - path: 获取工作区根目录路径
 * - read: 读取文本文件内容（前 5000 字符）
 * - delete: 删除工作区文件
 * - clear: 清空工作区
 *
 * 工具（如 image_gen/file_generator）生成的文件可存放到工作区，
 * 后续通过本工具查看/清理，实现文件生命周期管理。
 */
@Tool(
    value = "workspace",
    description = "Agent 工作区：管理 Agent 产生的文件（图片/导出/临时数据）。生成文件后用 list 查看、read 读取、delete 清理；**写坏了可用 versions 看历史版本、restore 回退**（file_generator 覆盖写与 excel_tool 保存前都会自动留 原名.ver1..ver5）",
    category = "file",
    aliases = {"工作区", "agent_workspace", "workspace_files"},
    actions = {
        @Action(name = "list", description = "列出工作区文件"),
        @Action(name = "path", description = "获取工作区根目录路径"),
        @Action(name = "read", description = "读取工作区文本文件内容"),
        @Action(name = "versions", description = "列出某文件的历史版本（原名.ver1..ver5，1=最近一版）"),
        @Action(name = "restore", description = "把文件恢复到第 N 版（默认 1=最近）；会覆盖当前内容，必须传 confirm=true；恢复前先把当前内容备份成新的一版，所以恢复本身也可回退"),
        @Action(name = "delete", description = "删除工作区文件"),
        @Action(name = "clear", description = "清空工作区")
    },
    params = {
        @Param(name = "action", type = "string", description = "操作：list(默认)/path/read/versions/restore/delete/clear", required = false),
        @Param(name = "fileName", type = "string", description = "文件名（工作区内相对名），read/versions/restore/delete 用", required = false),
        @Param(name = "version", type = "integer", description = "restore 用：恢复到第几版（1..5，默认 1=最近一版）", required = false),
        @Param(name = "confirm", type = "boolean", description = "破坏性操作确认（delete/clear/restore 必须传 true）", required = false)
    }
)
public class WorkspaceTool implements AITool, StructuredParamTool {

    private static final String TAG = "WorkspaceTool";
    private final Context context;

    public WorkspaceTool() {
        this.context = null;
    }

    public WorkspaceTool(Context context) {
        this.context = context;
    }

    @Override
    public String getName() {
        return "workspace";
    }

    @Override
    public String getDescription() {
        return "Agent 工作区：管理 Agent 产生的文件（图片/导出/临时数据）。生成文件后用 list 查看、read 读取、delete 清理；"
                + "写坏了用 versions 看历史版本、restore 回退（file_generator 覆盖写与 excel_tool 保存前都会自动留 原名.ver1..ver5）。"
                + "action: list|path|read|versions|restore|delete|clear";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("action", "操作：list(列出)|path(工作区路径)|read(读文本文件)|versions(列历史版本)|restore(回退到第N版)|delete(删除)|clear(清空)");
        params.put("fileName", "文件名（工作区内相对名），read/versions/restore/delete 用");
        params.put("version", "restore 用：恢复到第几版（1..5，默认 1=最近一版）");
        params.put("confirm", "破坏性操作确认（delete/clear/restore 必须传 true，防误删误覆盖）");
        return params;
    }

    /**
     * 结构化参数（{@link StructuredParamTool}）：把线上 schema 从"全 string"改回真实类型。
     *
     * <p>此前本类只实现 {@code getParameterDescriptions()}，走 Map 兜底后每个参数都被写成
     * {@code type:"string" / required:false}——类上 {@code @Param(type="boolean")} 的 confirm
     * 因此对模型显示成字符串，而 execute 按布尔语义判 {@code confirm=true}（
     * {@code boolParam} 两样都收，所以行为没错，错的是模型看到的类型）。</p>
     *
     * <p><b>为什么 required 全是 false</b>：action 缺省 list；fileName 只在 read/delete 下必要；
     * confirm 只在 delete/clear 下必要。扁平 schema 的 required 不区分 action，硬标必填会让
     * 「list 也要带 fileName」这种谎话进 prompt。条件约束写在 description，由 execute 缺参报错兜底。</p>
     */
    @Override
    public List<ParamDefinition> getParameterDefinitions() {
        List<ParamDefinition> defs = new ArrayList<>();
        defs.add(new ParamDefinition("action", "string",
                "操作：list(列出,默认)|path(工作区路径)|read(读文本文件)|versions(列历史版本)|restore(回退到第N版)|delete(删除)|clear(清空)",
                false, null,
                java.util.Arrays.asList("list", "path", "read", "versions", "restore", "delete", "clear")));
        defs.add(new ParamDefinition("fileName", "string",
                "文件名（工作区内相对名），read/versions/restore/delete 用", false));
        defs.add(new ParamDefinition("version", "integer",
                "restore 用：恢复到第几版（1..5，默认 1=最近一版）", false, 1));
        defs.add(new ParamDefinition("confirm", "boolean",
                "破坏性操作确认（delete/clear/restore 必须传 true，防误删误覆盖）", false));
        return defs;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            if (context == null) {
                return AIToolResult.fail("工作区工具未初始化");
            }
            AgentWorkspace ws = AgentWorkspace.getInstance(context);
            String action = parameters.get("action") != null
                    ? String.valueOf(parameters.get("action")) : "list";

            switch (action) {
                case "path": {
                    Map<String, Object> result = new HashMap<>();
                    result.put("path", ws.getWorkspacePath());
                    result.put("message", "工作区路径: " + ws.getWorkspacePath());
                    return AIToolResult.success(result);
                }
                case "read": {
                    String fileName = parameters.get("fileName") != null ? String.valueOf(parameters.get("fileName")) : "";
                    if (fileName.trim().isEmpty()) {
                        return AIToolResult.fail("缺少参数: fileName（action=read 需要，工作区内相对名）");
                    }
                    File f = locateWorkspaceFile(ws, fileName.trim());
                    if (f == null || !f.exists()) {
                        return AIToolResult.fail("文件不存在: " + fileName);
                    }
                    if (f.length() > 512 * 1024) {
                        return AIToolResult.fail("文件过大（>512KB），仅支持文本小文件");
                    }
                    StringBuilder content = new StringBuilder();
                    try (java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"))) {
                        String line;
                        int total = 0;
                        while ((line = reader.readLine()) != null && total < 5000) {
                            content.append(line).append("\n");
                            total += line.length();
                        }
                    }
                    Map<String, Object> result = new HashMap<>();
                    result.put("fileName", f.getName());
                    result.put("path", f.getAbsolutePath());
                    result.put("size", f.length());
                    result.put("content", content.toString());
                    result.put("message", "文件内容:\n" + content.toString());
                    return AIToolResult.success(result);
                }
                case "versions": {
                    String fileName = strParam(parameters, "fileName");
                    if (fileName.isEmpty()) {
                        return AIToolResult.fail("缺少参数: fileName（action=versions 需要，工作区内相对名）");
                    }
                    File f = locateVersionedFile(ws, fileName);
                    if (f == null) {
                        return AIToolResult.fail("只能查看工作区内的文件版本，拒绝越界路径: " + fileName);
                    }
                    List<FileVersionBackup.Version> vs = FileVersionBackup.versions(f);
                    Map<String, Object> result = new HashMap<>();
                    result.put("fileName", f.getName());
                    result.put("path", f.getAbsolutePath());
                    result.put("count", vs.size());
                    List<Map<String, Object>> items = new ArrayList<>();
                    StringBuilder summary = new StringBuilder();
                    for (FileVersionBackup.Version v : vs) {
                        Map<String, Object> item = new HashMap<>();
                        item.put("version", v.index);
                        item.put("name", v.file.getName());
                        item.put("size", v.size);
                        item.put("lastModified", v.lastModified);
                        items.add(item);
                        if (summary.length() > 0) summary.append("；");
                        summary.append("第").append(v.index).append("版 ")
                                .append(new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                                        .format(new java.util.Date(v.lastModified)))
                                .append(" ").append(formatSize(v.size));
                    }
                    result.put("versions", items);
                    result.put("message", vs.isEmpty()
                            ? "没有 " + f.getName() + " 的历史备份（只有被覆盖写之前才会留底；file_generator 覆盖写与 excel_tool 保存都会自动留 .ver1..ver5）"
                            : f.getName() + " 有 " + vs.size() + " 版备份（1=最近）: " + summary
                                    + "。回退用 workspace(action=restore, fileName=" + f.getName() + ", version=N, confirm=true)");
                    return AIToolResult.success(result);
                }
                case "restore": {
                    String fileName = strParam(parameters, "fileName");
                    if (fileName.isEmpty()) {
                        return AIToolResult.fail("缺少参数: fileName（action=restore 需要，工作区内相对名）");
                    }
                    // restore 会覆盖当前内容，和 delete 一样要求显式确认（防 prompt 注入把用户文件回退掉）
                    if (!boolParam(parameters, "confirm", false)) {
                        Map<String, Object> info = new HashMap<>();
                        info.put("requiresConfirm", true);
                        return AIToolResult.fail("恢复会覆盖当前内容，如需恢复请传 confirm=true 再次调用"
                                + "（放心：覆盖前会先把当前内容备份成新的一版，回退错了还能再退回来）", info);
                    }
                    File f = locateVersionedFile(ws, fileName.trim());
                    if (f == null) {
                        return AIToolResult.fail("只能恢复工作区内的文件，拒绝越界路径: " + fileName);
                    }
                    int n = intParam(parameters, "version", 1);
                    String err = FileVersionBackup.restore(f, n);
                    if (err != null) {
                        return AIToolResult.fail(err);
                    }
                    Map<String, Object> result = new HashMap<>();
                    result.put("status", "restored");
                    result.put("fileName", f.getName());
                    result.put("path", f.getAbsolutePath());
                    result.put("restoredVersion", n);
                    result.put("message", "已把 " + f.getName() + " 恢复到第 " + n + " 版（恢复前的内容也已备份）");
                    return AIToolResult.success(result);
                }
                case "delete": {
                    String fileName = parameters.get("fileName") != null ? String.valueOf(parameters.get("fileName")) : "";
                    if (fileName.trim().isEmpty()) {
                        return AIToolResult.fail("缺少参数: fileName（action=delete 需要，工作区内相对名）");
                    }
                    // 删除不可恢复：需 confirm=true（防 prompt 注入误删）
                    boolean confirm = boolParam(parameters, "confirm", false);
                    if (!confirm) {
                        Map<String, Object> info = new HashMap<>();
                        info.put("requiresConfirm", true);
                        return AIToolResult.fail("删除文件不可恢复，如需删除请传 confirm=true 再次调用", info);
                    }
                    boolean ok = ws.deleteFile(fileName.trim());
                    Map<String, Object> result = new HashMap<>();
                    result.put("status", ok ? "deleted" : "failed");
                    result.put("fileName", fileName.trim());
                    result.put("message", ok ? "已删除: " + fileName.trim() : "删除失败（文件不存在或在工作区外）");
                    return AIToolResult.success(result);
                }
                case "clear": {
                    // 清空不可恢复：需 confirm=true（防 prompt 注入误清）
                    boolean confirm = boolParam(parameters, "confirm", false);
                    if (!confirm) {
                        Map<String, Object> info = new HashMap<>();
                        info.put("requiresConfirm", true);
                        return AIToolResult.fail("清空工作区所有文件不可恢复，如需清空请传 confirm=true 再次调用", info);
                    }
                    int removed = 0;
                    for (AgentWorkspace.WorkspaceFile wf : ws.listFiles()) {
                        if (ws.deleteFile(wf.name)) removed++;
                    }
                    Map<String, Object> result = new HashMap<>();
                    result.put("removed", removed);
                    result.put("message", "已清空工作区，删除 " + removed + " 个文件");
                    return AIToolResult.success(result);
                }
                case "list":
                default: {
                    List<AgentWorkspace.WorkspaceFile> files = ws.listFiles();
                    Map<String, Object> result = new HashMap<>();
                    result.put("count", files.size());
                    result.put("workspacePath", ws.getWorkspacePath());
                    result.put("filesPath", ws.getFilesPath());
                    result.put("tmpPath", ws.getTmpPath());
                    List<Map<String, Object>> items = new ArrayList<>();
                    StringBuilder summary = new StringBuilder();
                    for (AgentWorkspace.WorkspaceFile f : files) {
                        Map<String, Object> item = new HashMap<>();
                        item.put("name", f.name);
                        item.put("size", f.size);
                        item.put("lastModified", f.lastModified);
                        item.put("zone", f.zone);
                        items.add(item);
                        if (summary.length() > 0) summary.append("\n");
                        String zoneTag = "files".equals(f.zone) ? "[长期] " : "tmp".equals(f.zone) ? "[临时] " : "";
                        summary.append(zoneTag).append(f.name).append(" (").append(formatSize(f.size)).append(")");
                    }
                    result.put("files", items);
                    result.put("message", files.isEmpty() ? "工作区为空" : "工作区文件:\n" + summary);
                    return AIToolResult.success(result);
                }
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Workspace tool error: " + e.getMessage(), e);
            return AIToolResult.fail("工作区工具出错: " + e.getMessage());
        }
    }

    private static String formatSize(long size) {
        if (size < 1024) return size + "B";
        if (size < 1024 * 1024) return String.format(java.util.Locale.US, "%.1fKB", size / 1024.0);
        return String.format(java.util.Locale.US, "%.1fMB", size / 1024.0 / 1024.0);
    }

    private static String strParam(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int intParam(Map<String, Object> parameters, String key, int defaultValue) {
        Object value = parameters.get(key);
        if (value instanceof Number) return ((Number) value).intValue();
        if (value != null) {
            String s = String.valueOf(value).trim();
            if (!s.isEmpty()) {
                // 模型常把整数写成 "2" 之外还可能带小数尾巴（"2.0"），宽松收下；
                // 真收不下的再回落默认值，让 execute 用默认版本恢复而不是报错
                try {
                    return Integer.parseInt(s);
                } catch (NumberFormatException ignored) {
                    try {
                        return (int) Math.round(Double.parseDouble(s));
                    } catch (NumberFormatException stillIgnored) {
                        return defaultValue;
                    }
                }
            }
        }
        return defaultValue;
    }

    /**
     * 按名字找工作区里的文件：先按给定路径解析，命不中且不是带目录的路径时，
     * 再依次找 长期文件区 files/ → 临时区 tmp/。（原 read 的查找顺序，抽出来复用）
     */
    private static File locateWorkspaceFile(AgentWorkspace ws, String name) {
        File f = ws.resolveFile(name);
        if (!f.exists() && !name.contains("/")) {
            File inFiles = ws.resolveFileToFiles(name);
            if (inFiles.exists()) {
                f = inFiles;
            } else {
                File inTmp = ws.resolveFileToTmp(name);
                if (inTmp.exists()) f = inTmp;
            }
        }
        return f;
    }

    /**
     * versions/restore 用：定位"备份挂在哪个路径上"。
     *
     * <p>备份与主文件同目录（{@code 原名.ext.verN}），所以必须先确定主文件在哪一侧
     * （files/ 长期区还是 tmp/ 临时区），否则列出来的版本永远是空。主文件已经被删掉
     * 但备份还在时也要能找到——"写坏了又手删主文件"恰恰是最想回退的时候。</p>
     *
     * <p>restore 是<b>写</b>操作，所以多一道边界检查：模型传的绝对路径/越界名字一律拒绝，
     * 只能在 workspace 的三个目录内回退（read 只读，保持原有宽松查找）。</p>
     */
    private static File locateVersionedFile(AgentWorkspace ws, String name) {
        return locateVersionedFile(locateWorkspaceFile(ws, name), name, workspaceDirs(ws));
    }

    private static File[] workspaceDirs(AgentWorkspace ws) {
        return new File[] {ws.getFilesDir(), ws.getTmpDir(), ws.getWorkspaceDir()};
    }

    /**
     * {@link #locateVersionedFile(AgentWorkspace, String)} 的纯函数内核。
     *
     * <p>单独拆出来是因为 {@code AgentWorkspace} 的构造在 Robolectric 下会抛
     * {@code ArrayIndexOutOfBoundsException}（它读 {@code Environment.isExternalStorageManager()}，
     * 真机不会）——版本定位这套规则是"备份能不能被找到"的唯一入口，不能因为拿不到工作区就不测。</p>
     *
     * @param located 按名字宽松定位到的文件（可能不存在，但父目录有参考价值）
     * @param name    模型传来的原始名
     * @param dirs    工作区的三个目录：files/ 长期区、tmp/ 临时区、工作区根
     * @return 主文件路径（可能不存在，但父目录正确）；越界时为 null，调用方给出拒绝文案
     */
    static File locateVersionedFile(File located, String name, File[] dirs) {
        if (!isWithin(located, dirs)) {
            for (File dir : dirs) {
                if (dir == null) continue;
                File inside = new File(dir, new File(name).getName());
                if (isWithin(inside, dirs)) return inside;
            }
            return null;
        }
        if (located.exists()) return located;
        String base = new File(name).getName();
        for (File dir : dirs) {
            if (dir == null) continue;
            File candidate = new File(dir, base);
            if (!FileVersionBackup.versions(candidate).isEmpty()) return candidate;
        }
        return located;
    }

    /** 文件是否落在给定目录之内（用 canonicalPath 比，防 {@code ../} 穿越） */
    static boolean isWithin(File f, File[] dirs) {
        try {
            String target = f.getCanonicalPath();
            for (File dir : dirs) {
                if (dir == null) continue;
                String prefix = dir.getCanonicalPath();
                if (!prefix.endsWith(File.separator)) prefix = prefix + File.separator;
                if (target.startsWith(prefix) || target.equals(dir.getCanonicalPath())) return true;
            }
        } catch (Exception e) {
            AILogger.w(TAG, "工作区边界检查失败: " + e.getMessage());
        }
        return false;
    }

    private static boolean boolParam(Map<String, Object> parameters, String key, boolean defaultValue) {
        Object value = parameters.get(key);
        if (value instanceof Boolean) return (Boolean) value;
        if (value != null) {
            String s = String.valueOf(value).trim();
            if (s.equalsIgnoreCase("true") || s.equals("1")) return true;
            if (s.equalsIgnoreCase("false") || s.equals("0")) return false;
        }
        return defaultValue;
    }
}
