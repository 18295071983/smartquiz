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

    private final File workspaceDir;
    private final File tmpDir;
    private final File filesDir;

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

    private AgentWorkspace(Context context) {
        this.workspaceDir = new File(context.getFilesDir(), WORKSPACE_DIR);
        this.tmpDir = new File(workspaceDir, TMP_DIR);
        this.filesDir = new File(workspaceDir, FILES_DIR);
        ensureDirs();
    }

    private void ensureDirs() {
        if (!workspaceDir.exists()) workspaceDir.mkdirs();
        if (!tmpDir.exists()) tmpDir.mkdirs();
        if (!filesDir.exists()) filesDir.mkdirs();
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

    /** 在指定文件名前拼接工作区路径 */
    public File resolveFile(String fileName) {
        if (fileName == null || fileName.isEmpty()) return null;
        File f = new File(fileName);
        if (f.isAbsolute()) return f;
        return new File(getWorkspaceDir(), fileName);
    }

    /** 解析到长期文件区（用户保留文件） */
    public File resolveFileToFiles(String fileName) {
        if (fileName == null || fileName.isEmpty()) return null;
        File f = new File(fileName);
        if (f.isAbsolute()) return f;
        return new File(getFilesDir(), fileName);
    }

    /** 解析到临时缓存区（执行中间文件） */
    public File resolveFileToTmp(String fileName) {
        if (fileName == null || fileName.isEmpty()) return null;
        File f = new File(fileName);
        if (f.isAbsolute()) return f;
        return new File(getTmpDir(), fileName);
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
