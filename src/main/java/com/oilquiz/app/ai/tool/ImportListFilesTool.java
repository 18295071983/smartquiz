package com.oilquiz.app.ai.tool;

import com.oilquiz.app.ai.importing.v2.ImportDirs;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 题库文件发现工具 —— 智能体全自动导入的第一环。
 * <p>
 * 扫描可导入的题库文件（Excel/CSV/JSON/Markdown/文本），返回路径清单，
 * 让智能体先 {@code import_list_files} 发现候选文件，再调 {@code import_start} 启动。
 * <p>
 * 扫描范围（存在才扫描）：应用公共目录 OilQuiz（含 source 子目录）、系统 Download 目录。
 * 按修改时间倒序，最多返回 50 个。
 */
public class ImportListFilesTool implements AITool {

    private Context context;

    public ImportListFilesTool() {
    }

    public ImportListFilesTool(Context context) {
        this.context = context;
    }

    @Override
    public String getName() {
        return "import_list_files";
    }

    @Override
    public String getDescription() {
        return "题库文件发现（智能体全自动导入第一步）：列出设备上可导入的题库文件"
                + "（Excel .xlsx/.xls、CSV、JSON、Markdown .md、文本 .txt），返回 JSON 数组"
                + " [{path,name,size,type,modified}]，按修改时间倒序。"
                + "扫描范围：应用公共目录 OilQuiz（含 source 子目录）与系统 Download 目录；"
                + "支持 dir 参数指定其他目录。拿到 path 后传给 import_start 启动导入。"
                + "用法：导入前先调本工具确认文件存在与路径，避免 import_start 报文件不存在。";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("dir", "要扫描的目录路径（可选，默认自动扫描公共目录与下载目录）");
        params.put("keyword", "文件名关键字过滤（可选，如\"数学\"\"期中\"）");
        return params;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            String dir = parameters.get("dir") != null ? String.valueOf(parameters.get("dir")) : null;
            String keyword = parameters.get("keyword") != null
                    ? String.valueOf(parameters.get("keyword")).toLowerCase(Locale.ROOT) : null;

            List<File> candidates = new ArrayList<>();
            if (dir != null && !dir.trim().isEmpty()) {
                File d = new File(dir.trim());
                if (!d.isDirectory()) {
                    return AIToolResult.fail("目录不存在或不可读: " + dir);
                }
                scanDir(d, candidates, keyword);
            } else {
                // 公共目录根 + source 子目录 + Download（存在才扫）
                File root = ImportDirs.publicRoot();
                scanDir(root, candidates, keyword);
                File source = ImportDirs.sourceDir();
                if (!source.getAbsolutePath().equals(root.getAbsolutePath())) {
                    scanDir(source, candidates, keyword);
                }
                if (android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS) != null) {
                    File download = android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS);
                    scanDir(download, candidates, keyword);
                }
            }

            // 按修改时间倒序，最多 50 个
            candidates.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            if (candidates.size() > 50) {
                candidates = new ArrayList<>(candidates.subList(0, 50));
            }

            JSONArray arr = new JSONArray();
            for (File f : candidates) {
                JSONObject o = new JSONObject();
                o.put("path", f.getAbsolutePath());
                o.put("name", f.getName());
                o.put("size", f.length());
                o.put("type", extOf(f.getName()));
                o.put("modified", f.lastModified());
                arr.put(o);
            }
            JSONObject result = new JSONObject();
            result.put("count", arr.length());
            result.put("files", arr);
            result.put("hint", "选 path 传给 import_start（filePath 参数）启动导入");
            return AIToolResult.success(result.toString());
        } catch (Exception e) {
            return AIToolResult.fail("扫描题库文件失败: " + e.getMessage());
        }
    }

    /** 可导入扩展名 */
    private static final List<String> SUPPORTED_EXTS = Arrays.asList(
            "xlsx", "xls", "csv", "json", "md", "txt");

    private void scanDir(File dir, List<File> out, String keyword) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile() && SUPPORTED_EXTS.contains(extOf(f.getName()))) {
                if (keyword == null || f.getName().toLowerCase(Locale.ROOT).contains(keyword)) {
                    out.add(f);
                }
            }
        }
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
