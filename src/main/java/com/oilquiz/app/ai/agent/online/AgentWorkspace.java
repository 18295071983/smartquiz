package com.oilquiz.app.ai.agent.online;

import android.content.Context;

import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 工作区管理 —— 统一 Agent 产生的文件目录。
 *
 * 所有 Agent 生成/下载的文件默认存放于 filesDir/agent_workspace/，
 * 提供 list / read / delete / path 能力，使 Agent 能管理自己产生的文件
 * （图片生成、文件导出、临时数据等），实现"工作区"闭环。
 */
public class AgentWorkspace {

    private static final String TAG = "AgentWorkspace";
    private static final String WORKSPACE_DIR = "agent_workspace";

    private final File workspaceDir;

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
        if (!workspaceDir.exists()) {
            workspaceDir.mkdirs();
        }
    }

    /** 工作区根目录（不存在则创建） */
    public File getWorkspaceDir() {
        if (!workspaceDir.exists()) workspaceDir.mkdirs();
        return workspaceDir;
    }

    /** 工作区路径字符串 */
    public String getWorkspacePath() {
        return getWorkspaceDir().getAbsolutePath();
    }

    /** 在指定文件名前拼接工作区路径 */
    public File resolveFile(String fileName) {
        if (fileName == null || fileName.isEmpty()) return null;
        File f = new File(fileName);
        if (f.isAbsolute()) return f;
        return new File(getWorkspaceDir(), fileName);
    }

    /** 列出工作区文件（按修改时间倒序） */
    public List<WorkspaceFile> listFiles() {
        List<WorkspaceFile> result = new ArrayList<>();
        File[] files = getWorkspaceDir().listFiles();
        if (files == null) return result;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File f : files) {
            if (f.isFile()) {
                result.add(new WorkspaceFile(f.getName(), f.length(), f.lastModified()));
            }
        }
        return result;
    }

    /** 删除工作区文件（仅限工作区内，防越权） */
    public boolean deleteFile(String fileName) {
        File f = resolveFile(fileName);
        if (f == null) return false;
        try {
            // 安全检查：目标必须在工作区内
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

    /** 工作区文件信息 */
    public static class WorkspaceFile {
        public final String name;
        public final long size;
        public final long lastModified;

        public WorkspaceFile(String name, long size, long lastModified) {
            this.name = name;
            this.size = size;
            this.lastModified = lastModified;
        }
    }
}
