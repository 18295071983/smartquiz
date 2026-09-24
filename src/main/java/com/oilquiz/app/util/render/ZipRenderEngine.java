package com.oilquiz.app.util.render;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 压缩包渲染引擎：ZIP 条目列表（文件名/大小/压缩率），不解压不执行。
 * 覆盖 zip；rar/7z 因无内置解压库，交"用其他应用打开"。
 */
public class ZipRenderEngine implements FileRenderEngine {

    private static final String[] ZIP_EXTENSIONS = {".zip", ".jar", ".apk", ".docx", ".xlsx", ".pptx"};
    private static final int MAX_ENTRIES = 500;
    private static final int MAX_NAME_LEN = 120;

    @Override
    public boolean canRender(File file) {
        if (file == null) return false;
        String name = file.getName().toLowerCase();
        for (String ext : ZIP_EXTENSIONS) {
            if (name.endsWith(ext)) return true;
        }
        return false;
    }

    @Override
    public String getEngineName() {
        return "压缩包列表查看器";
    }

    @Override
    public String getFileTypeDescription(File file) {
        if (file == null) return "压缩包";
        String name = file.getName().toLowerCase();
        if (name.endsWith(".zip")) return "ZIP 压缩包";
        if (name.endsWith(".jar")) return "JAR 归档";
        if (name.endsWith(".apk")) return "APK 安装包";
        if (name.endsWith(".docx")) return "Word 文档(zip)";
        if (name.endsWith(".xlsx")) return "Excel 表格(zip)";
        if (name.endsWith(".pptx")) return "PPT 演示(zip)";
        return "压缩包";
    }

    @Override
    public void render(File file, RenderCallback callback) {
        if (file == null || !file.exists()) {
            callback.onError("压缩包文件不存在");
            return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("📦 ").append(file.getName()).append("\n");
            sb.append("大小：").append(file.length() / 1024).append("KB\n");
            sb.append("──────────────\n");
            int count = 0;
            long totalUncompressed = 0;
            try (InputStream fis = new java.io.FileInputStream(file);
                 ZipInputStream zis = new ZipInputStream(fis, StandardCharsets.UTF_8)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    count++;
                    if (count > MAX_ENTRIES) {
                        sb.append("……（超过 ").append(MAX_ENTRIES).append(" 条，仅显示前 ").append(MAX_ENTRIES).append(" 条）\n");
                        break;
                    }
                    totalUncompressed += entry.getSize();
                    String name = entry.getName();
                    if (name.length() > MAX_NAME_LEN) {
                        name = name.substring(0, MAX_NAME_LEN) + "…";
                    }
                    long size = entry.getSize();
                    String sizeStr = size >= 1024 * 1024
                            ? String.format("%.1fMB", size / 1024.0 / 1024.0)
                            : (size >= 1024 ? (size / 1024) + "KB" : size + "B");
                    String ratio = entry.getCompressedSize() > 0 && size > 0
                            ? String.format("压缩率%d%%", Math.round(100.0 * entry.getCompressedSize() / size)) : "";
                    sb.append("• ").append(name).append("  ").append(sizeStr)
                            .append(ratio.isEmpty() ? "" : " " + ratio).append("\n");
                    zis.closeEntry();
                }
            }
            sb.append("──────────────\n");
            sb.append("共 ").append(count).append(" 个文件条目");
            callback.onProgress(100);
            callback.onSuccess(sb.toString());
        } catch (Exception e) {
            android.util.Log.e("ZipRenderEngine", "render zip failed: " + e.getMessage(), e);
            callback.onError("压缩包解析失败: " + e.getMessage());
        }
    }
}
