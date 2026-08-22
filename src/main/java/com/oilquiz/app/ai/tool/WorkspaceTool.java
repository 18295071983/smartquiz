package com.oilquiz.app.ai.tool;

import android.content.Context;

import com.oilquiz.app.ai.agent.online.AgentWorkspace;
import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;
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
    description = "Agent 工作区：管理 Agent 产生的文件（图片/导出/临时数据）。生成文件后用 list 查看、read 读取、delete 清理",
    category = "file",
    aliases = {"工作区", "agent_workspace", "workspace_files"},
    actions = {
        @Action(name = "list", description = "列出工作区文件"),
        @Action(name = "path", description = "获取工作区根目录路径"),
        @Action(name = "read", description = "读取工作区文本文件内容"),
        @Action(name = "delete", description = "删除工作区文件"),
        @Action(name = "clear", description = "清空工作区")
    },
    params = {
        @Param(name = "action", type = "string", description = "操作：list(默认)/path/read/delete/clear", required = false),
        @Param(name = "fileName", type = "string", description = "文件名（工作区内相对名），read/delete 用", required = false),
        @Param(name = "confirm", type = "boolean", description = "删除/清空确认（delete/clear 必须传 true）", required = false)
    }
)
public class WorkspaceTool implements AITool {

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
        return "Agent 工作区：管理 Agent 产生的文件（图片/导出/临时数据）。生成文件后用 list 查看、read 读取、delete 清理。action: list|path|read|delete|clear";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("action", "操作：list(列出)|path(工作区路径)|read(读文本文件)|delete(删除)|clear(清空)");
        params.put("fileName", "文件名（工作区内相对名），read/delete 用");
        params.put("confirm", "删除/清空确认（delete/clear 必须传 true，防误删）");
        return params;
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
                        return AIToolResult.fail("read 需要 fileName 参数");
                    }
                    File f = ws.resolveFile(fileName.trim());
                    // 兼容：文件名未带路径时按"长期文件区 → 工作区根 → 临时区"顺序查找
                    if (!f.exists() && !fileName.contains("/")) {
                        File inFiles = ws.resolveFileToFiles(fileName.trim());
                        if (inFiles.exists()) f = inFiles;
                        else {
                            File inTmp = ws.resolveFileToTmp(fileName.trim());
                            if (inTmp.exists()) f = inTmp;
                        }
                    }
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
                case "delete": {
                    String fileName = parameters.get("fileName") != null ? String.valueOf(parameters.get("fileName")) : "";
                    if (fileName.trim().isEmpty()) {
                        return AIToolResult.fail("delete 需要 fileName 参数");
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
