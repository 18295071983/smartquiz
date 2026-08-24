package com.oilquiz.app.ai.agent.online;

import android.content.Context;

import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 工作区管理 —— 统一 Agent 产生的文件目录（按业界标准：工作区 = 临时执行空间）。
 *
 * 职责划分（对齐 Claude Code / Codex 实践）：
 * - 工作区根目录 = 临时执行空间（scratchpad）：执行中间文件/缓存/进度/长对话摘要
 *   → 任务结束自动清理，不长期保留
 * - files/ 子目录 = 长期文件区：用户明确要求保留的产物（报告/图片/导出）
 *   → 不自动清理，管理页可见
 * - 长期记忆（用户偏好/事实）→ 独立记忆系统（AgentMemoryStore），不占工作区
 *
 * 目录位置（公共目录优先，防"私有目录找不到文件"）：
 * - 已授予"所有文件访问"(MANAGE_EXTERNAL_STORAGE, Android 11+) →
 *   公共目录 Download/OilQuiz/agent_workspace/（文件对用户/文件管理器/其他 App 可见，
 *   可直接分享/备份，无需复制）
 * - 未授权 → 回退应用私有目录 files/agent_workspace/（始终可用，分享时自动复制到公共目录）
 *
 * 目录结构：
 *   agent_workspace/
 *     tmp/    ← 临时执行缓存（自动清理）
 *     files/  ← 长期文件（保留）
 */
public class AgentWorkspace {

    private static final String TAG = "AgentWorkspace";
    private static final String WORKSPACE_DIR = "agent_workspace";
    private static final String TMP_DIR = "tmp";
    private static final String FILES_DIR = "files";
    /** 公共目录根：Download/OilQuiz（SDK 29+ 用户可见） */
    private static final String PUBLIC_ROOT = "OilQuiz";

    private final File workspaceDir;
    private final File tmpDir;
    private final File filesDir;
    /** 是否使用公共目录（true=Download/OilQuiz；false=私有目录回退） */
    private final boolean publicWorkspace;

    private static volatile AgentWorkspace instance;

    public static AgentWorkspace getInstance(Context context) {
        if (instance == null) {
            synchronized (AgentWorkspace.class) {
                if (instance == null) {
                    instance = new AgentWorkspace(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    /** 重建实例（权限变化后调用：私有目录 ↔ 公共目录切换） */
    public static void rebuildInstance(Context context) {
        synchronized (AgentWorkspace.class) {
            instance = new AgentWorkspace(context.getApplicationContext());
        }
    }

    private AgentWorkspace(Context context) {
        // 公共目录：Download/OilQuiz/agent_workspace（需"所有文件访问"权限，Android 11+）
        File publicWs = null;
        boolean hasPermission = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R
                || android.os.Environment.isExternalStorageManager();
        if (hasPermission) {
            try {
                java.io.File downloadDir = android.os.Environment
                        .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
                if (downloadDir != null) {
                    publicWs = new File(downloadDir, PUBLIC_ROOT + "/" + WORKSPACE_DIR);
                    if (!publicWs.exists()) publicWs.mkdirs();
                    if (!publicWs.isDirectory() || !publicWs.canWrite()) {
                        AILogger.w(TAG, "公共工作区不可写，回退私有目录: " + publicWs.getAbsolutePath());
                        publicWs = null;
                    }
                }
            } catch (Throwable t) {
                AILogger.w(TAG, "公共工作区检测失败，回退私有目录: " + t.getMessage());
                publicWs = null;
            }
        }
        if (publicWs != null) {
            this.workspaceDir = publicWs;
            this.publicWorkspace = true;
            AILogger.i(TAG, "Agent workspace (public): " + publicWs.getAbsolutePath());
        } else {
            this.workspaceDir = new File(context.getFilesDir(), WORKSPACE_DIR);
            this.publicWorkspace = false;
            AILogger.i(TAG, "Agent workspace (private fallback): " + workspaceDir.getAbsolutePath());
        }
        this.tmpDir = new File(workspaceDir, TMP_DIR);
        this.filesDir = new File(workspaceDir, FILES_DIR);
        ensureDirs();
    }

    private void ensureDirs() {
        if (!workspaceDir.exists()) workspaceDir.mkdirs();
        if (!tmpDir.exists()) tmpDir.mkdirs();
        if (!filesDir.exists()) filesDir.mkdirs();
    }

    /** 工作区是否位于公共目录（Download/OilQuiz） */
    public boolean isPublicWorkspace() {
        return publicWorkspace;
    }

    /** 公共目录权限是否已授予（Android 11+ 的 MANAGE_EXTERNAL_STORAGE；旧版本恒 true） */
    public static boolean hasPublicStoragePermission() {
        return android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R
                || android.os.Environment.isExternalStorageManager();
    }

    /** 工作区根目录（不存在则创建） */
    public File getWorkspaceDir() {
        ensureDirs();
        return workspaceDir;
    }

    /** 工作区路径字符串 */
    public String getWorkspacePath() {
        return getWorkspaceDir().getAbsolutePath();
    }

    /** 临时缓存目录（执行中间文件/进度/长对话摘要，任务结束自动清理） */
    public File getTmpDir() {
        ensureDirs();
        return tmpDir;
    }

    /** 长期文件目录（用户保留的产物，不自动清理） */
    public File getFilesDir() {
        ensureDirs();
        return filesDir;
    }

    /** 临时缓存目录路径 */
    public String getTmpPath() {
        return getTmpDir().getAbsolutePath();
    }

    /** 长期文件目录路径 */
    public String getFilesPath() {
        return getFilesDir().getAbsolutePath();
    }

    /** 在指定文件名前拼接工作区路径（相对名拒绝 `..` 路径穿越；绝对路径原样放行由调用方把关） */
    public File resolveFile(String fileName) {
        return resolveSafely(getWorkspaceDir(), fileName);
    }

    /** 解析到长期文件区（用户保留文件） */
    public File resolveFileToFiles(String fileName) {
        return resolveSafely(getFilesDir(), fileName);
    }

    /** 解析到临时缓存区（执行中间文件） */
    public File resolveFileToTmp(String fileName) {
        return resolveSafely(getTmpDir(), fileName);
    }

    /** 安全拼接：拒绝相对路径中的 `..` 段（防 prompt 注入越界读写工作区外文件），返回 null 表示非法 */
    private File resolveSafely(File base, String fileName) {
        if (fileName == null || fileName.isEmpty()) return null;
        File f = new File(fileName);
        if (f.isAbsolute()) return f;
        // 拒绝路径穿越：任何 .. 段都视为非法（Windows 反斜杠也归一处理）
        String normalized = fileName.replace('\\', '/');
        String[] segments = normalized.split("/");
        for (String seg : segments) {
            if ("..".equals(seg)) {
                AILogger.w(TAG, "拒绝路径穿越: " + fileName);
                return null;
            }
        }
        return new File(base, fileName);
    }

    /**
     * 解析路径为真实存在的文件（供 file_card/file_list 的打开/分享兜底）。
     * 模型传给 UI 组件的路径常不精确（相对名 "report.md"、缩写 "files/报告.pdf"、
     * 或幻觉的公共路径 "/storage/emulated/0/OilQuiz/xxx"），逐一兜底：
     * 1. content:// → 无法转 File，返回 null（调用方保留 URI 处理）
     * 2. file:// 前缀剥离
     * 3. 绝对路径存在 → 直接返回
     * 4. 相对路径 → 依次尝试 files/ → tmp/ → 工作区根
     * 5. 仍找不到 → 按文件名（basename）在 files/、tmp/、工作区根搜索
     */
    public File resolveExistingFile(String pathOrName) {
        if (pathOrName == null || pathOrName.trim().isEmpty()) return null;
        String p = pathOrName.trim();
        if (p.startsWith("content://")) return null;
        if (p.startsWith("file://")) {
            android.net.Uri u = android.net.Uri.parse(p);
            p = u != null ? u.getPath() : null;
            if (p == null || p.isEmpty()) return null;
        }
        ensureDirs();
        File direct = new File(p);
        if (direct.isAbsolute()) {
            if (direct.isFile()) return direct;
            // 绝对路径不存在（如幻觉的公共路径）→ 按文件名兜底搜索
            return searchByName(direct.getName());
        }
        // 相对路径：长期文件区 → 临时区 → 工作区根
        File inFiles = new File(filesDir, p);
        if (inFiles.isFile()) return inFiles;
        File inTmp = new File(tmpDir, p);
        if (inTmp.isFile()) return inTmp;
        File inRoot = new File(workspaceDir, p);
        if (inRoot.isFile()) return inRoot;
        // 纯文件名兜底搜索（模型常只传文件名/去掉目录前缀）
        String name = p;
        int lastSlash = p.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < p.length() - 1) {
            name = p.substring(lastSlash + 1);
        }
        return searchByName(name);
    }

    /** 按文件名在工作区 files/、tmp/、根目录搜索（返回第一个匹配的文件） */
    private File searchByName(String name) {
        if (name == null || name.isEmpty()) return null;
        ensureDirs();
        for (File dir : new File[]{filesDir, tmpDir, workspaceDir}) {
            File[] list = dir.listFiles();
            if (list != null) {
                for (File f : list) {
                    if (f.isFile() && f.getName().equals(name)) return f;
                }
            }
        }
        return null;
    }

    /** 列出工作区文件（按修改时间倒序；含 tmp/files 标记） */
    public List<WorkspaceFile> listFiles() {
        List<WorkspaceFile> result = new ArrayList<>();
        ensureDirs();
        // 长期文件区
        File[] files = filesDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) {
                    result.add(new WorkspaceFile(f.getName(), f.length(), f.lastModified(), "files"));
                }
            }
        }
        // 临时缓存区
        File[] tmps = tmpDir.listFiles();
        if (tmps != null) {
            for (File f : tmps) {
                if (f.isFile()) {
                    result.add(new WorkspaceFile(f.getName(), f.length(), f.lastModified(), "tmp"));
                }
            }
        }
        // 按修改时间倒序
        result.sort((a, b) -> Long.compare(b.lastModified, a.lastModified));
        return result;
    }

    /** 删除工作区文件（仅限工作区内，防越权） */
    public boolean deleteFile(String fileName) {
        File f = resolveFile(fileName);
        if (f == null) return false;
        try {
            String ws = getWorkspaceDir().getCanonicalPath();
            String target = f.getCanonicalPath();
            if (!target.startsWith(ws + File.separator) && !target.equals(ws)) {
                AILogger.w(TAG, "Delete blocked: outside workspace: " + target);
                return false;
            }
            return f.delete();
        } catch (Exception e) {
            AILogger.w(TAG, "Delete workspace file failed: " + e.getMessage());
            return false;
        }
    }

    /** 清理临时缓存区（任务结束调用：清空 tmp/ 下所有文件） */
    public int clearTmp() {
        int removed = 0;
        File[] tmps = getTmpDir().listFiles();
        if (tmps != null) {
            for (File f : tmps) {
                if (f.isFile() && f.delete()) removed++;
            }
        }
        return removed;
    }

    /**
     * 迁移私有目录旧工作区文件到公共目录（有权限时调用一次）。
     * 复制 files/ 长期文件（tmp/ 临时文件不迁移——任务结束本就清理）。
     * @return 迁移的文件数
     */
    public int migrateFromPrivate(Context context) {
        if (!publicWorkspace || context == null) return 0;
        try {
            java.io.File privateWs = new java.io.File(context.getFilesDir(), WORKSPACE_DIR);
            java.io.File privateFiles = new java.io.File(privateWs, FILES_DIR);
            if (!privateFiles.exists() || !privateFiles.isDirectory()) return 0;
            java.io.File[] old = privateFiles.listFiles();
            if (old == null) return 0;
            int migrated = 0;
            for (java.io.File f : old) {
                if (!f.isFile()) continue;
                java.io.File dest = new java.io.File(filesDir, f.getName());
                if (dest.exists()) continue; // 目标已存在不覆盖
                // 原子迁移：先写 .tmp 再 rename，避免写一半崩溃留下半截文件
                java.io.File tmp = new java.io.File(filesDir, f.getName() + ".tmp");
                try (java.io.InputStream is = new java.io.FileInputStream(f);
                     java.io.OutputStream os = new java.io.FileOutputStream(tmp)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        os.write(buf, 0, n);
                    }
                } catch (Exception e) {
                    tmp.delete(); // 失败清理半截文件
                    AILogger.w(TAG, "迁移文件失败: " + f.getName() + " - " + e.getMessage());
                    continue;
                }
                if (tmp.renameTo(dest)) {
                    migrated++;
                } else {
                    tmp.delete();
                    AILogger.w(TAG, "迁移重命名失败: " + f.getName());
                }
            }
            if (migrated > 0) {
                AILogger.i(TAG, "Migrated " + migrated + " files from private workspace to public");
            }
            return migrated;
        } catch (Throwable t) {
            AILogger.w(TAG, "迁移工作区失败: " + t.getMessage());
            return 0;
        }
    }

    /** 工作区文件信息 */
    public static class WorkspaceFile {
        public final String name;
        public final long size;
        public final long lastModified;
        /** 所在区域: "files"(长期) / "tmp"(临时) / "root"(根) */
        public final String zone;

        public WorkspaceFile(String name, long size, long lastModified) {
            this(name, size, lastModified, "root");
        }

        public WorkspaceFile(String name, long size, long lastModified, String zone) {
            this.name = name;
            this.size = size;
            this.lastModified = lastModified;
            this.zone = zone;
        }
    }
}
