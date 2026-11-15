package com.oilquiz.app.ai.tool;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Git 工具（2026-11-16）：让 Agent 具备版本管理能力。
 *
 * <p><b>为什么现在做这个</b>：产品要做"全 agent 功能"，而 Agent 改代码/写文档之后最需要的是
 * <b>可回滚</b>——没有版本管理，Agent 一改错就只能人工收拾。项目里已经引了 JGit
 * （{@code org.eclipse.jgit}，此前 0 引用），这里把它接成工具，Agent 才能自己
 * init / status / add / commit / log / diff / clone。</p>
 *
 * <p>设计取舍：</p>
 * <ul>
 *   <li><b>只做本地操作 + clone</b>：push/pull 涉及凭据管理（SSH key、token），
 *       属于安全敏感面，先不放开给模型自调用；</li>
 *   <li><b>默认仓库路径</b>取 app 私有目录下的 {@code agent-repo}，除非显式传 path；</li>
 *   <li>所有返回值都带 <b>实际执行的命令语义</b>（如 "commit 完成：a1b2c3d"），
 *       便于模型向用户复述。</li>
 * </ul>
 */
public class GitTool implements AITool {

    private static final String DEFAULT_REPO_DIR = "agent-repo";

    private final Context context;

    public GitTool() {
        this(null);
    }

    public GitTool(Context context) {
        this.context = context;
    }

    @Override
    public String getName() {
        return "git";
    }

    @Override
    public String getDescription() {
        return "Git 版本管理：init(初始化)|status(工作区状态)|add(暂存)|commit(提交)|"
                + "log(提交历史)|diff(改动对比)|branch(分支列表/新建)|checkout(切换)|clone(克隆)";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("action", "操作：init|status|add|commit|log|diff|branch|checkout|clone");
        params.put("path", "仓库目录（可选，默认 app 私有目录 agent-repo）");
        params.put("message", "commit 用：提交信息");
        params.put("file", "add 用：文件路径（默认 . 表示全部）");
        params.put("limit", "log 用：返回条数，默认 10");
        params.put("name", "branch/checkout 用：分支名");
        params.put("url", "clone 用：仓库地址");
        return params;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        String action = str(parameters, "action", "status");
        File dir = resolveDir(parameters);

        try {
            switch (action) {
                case "init":
                    return doInit(dir);
                case "status":
                    return doStatus(dir);
                case "add":
                    return doAdd(dir, str(parameters, "file", "."));
                case "commit":
                    return doCommit(dir, str(parameters, "message", ""));
                case "log":
                    return doLog(dir, intOf(parameters, "limit", 10));
                case "branch":
                    return doBranch(dir, str(parameters, "name", null));
                case "diff":
                    return doDiff(dir);
                case "clone":
                    return doClone(dir, str(parameters, "url", null));
                default:
                    return AIToolResult.fail("未知 action: " + action
                            + "（可用：init|status|add|commit|log|diff|branch|clone）");
            }
        } catch (Exception e) {
            return AIToolResult.fail("git " + action + " 失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 各动作
    // ------------------------------------------------------------------

    private AIToolResult doInit(File dir) throws Exception {
        if (!dir.exists() && !dir.mkdirs()) {
            return AIToolResult.fail("无法创建目录：" + dir.getAbsolutePath());
        }
        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.init()
                .setDirectory(dir).setInitialBranch("main").call()) {
            Map<String, Object> info = new HashMap<>();
            info.put("path", dir.getAbsolutePath());
            info.put("branch", "main");
            return AIToolResult.success("仓库已初始化：" + dir.getAbsolutePath() + "（分支 main）", info);
        }
    }

    private AIToolResult doStatus(File dir) throws Exception {
        try (org.eclipse.jgit.api.Git git = open(dir)) {
            org.eclipse.jgit.api.Status st = git.status().call();
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("branch", git.getRepository().getBranch());
            info.put("added", new ArrayList<>(st.getAdded()));
            info.put("modified", new ArrayList<>(st.getModified()));
            info.put("removed", new ArrayList<>(st.getRemoved()));
            info.put("untracked", new ArrayList<>(st.getUntracked()));
            info.put("clean", st.isClean());
            String summary = "分支 " + git.getRepository().getBranch()
                    + "｜新增 " + st.getAdded().size()
                    + "｜修改 " + st.getModified().size()
                    + "｜删除 " + st.getRemoved().size()
                    + "｜未跟踪 " + st.getUntracked().size()
                    + (st.isClean() ? "（工作区干净）" : "");
            return AIToolResult.success(summary, info);
        }
    }

    private AIToolResult doAdd(File dir, String file) throws Exception {
        try (org.eclipse.jgit.api.Git git = open(dir)) {
            git.add().addFilepattern(file).call();
            return AIToolResult.success("已暂存：" + file);
        }
    }

    private AIToolResult doCommit(File dir, String message) throws Exception {
        if (message == null || message.trim().isEmpty()) {
            return AIToolResult.fail("commit 需要 message（提交信息）");
        }
        try (org.eclipse.jgit.api.Git git = open(dir)) {
            // 没有改动时 JGit 抛 EmptyCommitException 语义不清，这里先判空
            if (git.status().call().isClean()) {
                return AIToolResult.fail("工作区没有改动，无需提交");
            }
            org.eclipse.jgit.revwalk.RevCommit commit = git.commit()
                    .setMessage(message)
                    .setAuthor("OilQuiz Agent", "agent@oilquiz.app")
                    .setCommitter("OilQuiz Agent", "agent@oilquiz.app")
                    .call();
            Map<String, Object> info = new HashMap<>();
            info.put("commit", commit.getName());
            info.put("message", message);
            return AIToolResult.success("提交完成：" + commit.abbreviate(7).name() + " " + message, info);
        }
    }

    private AIToolResult doLog(File dir, int limit) throws Exception {
        try (org.eclipse.jgit.api.Git git = open(dir)) {
            Iterable<org.eclipse.jgit.revwalk.RevCommit> commits =
                    git.log().setMaxCount(Math.max(1, limit)).call();
            List<Map<String, Object>> list = new ArrayList<>();
            StringBuilder sb = new StringBuilder();
            for (org.eclipse.jgit.revwalk.RevCommit c : commits) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("hash", c.abbreviate(7).name());
                row.put("message", c.getShortMessage());
                row.put("time", c.getCommitTime());
                list.add(row);
                sb.append(c.abbreviate(7).name()).append("  ")
                        .append(c.getShortMessage()).append("\n");
            }
            Map<String, Object> info = new HashMap<>();
            info.put("commits", list);
            return AIToolResult.success(list.isEmpty() ? "还没有提交记录" : sb.toString().trim(), info);
        }
    }

    private AIToolResult doBranch(File dir, String name) throws Exception {
        try (org.eclipse.jgit.api.Git git = open(dir)) {
            if (name != null && !name.trim().isEmpty()) {
                git.checkout().setCreateBranch(true).setName(name).call();
                return AIToolResult.success("已创建并切换到分支：" + name);
            }
            List<String> names = new ArrayList<>();
            for (org.eclipse.jgit.lib.Ref ref : git.branchList().call()) {
                names.add(ref.getName().replace("refs/heads/", ""));
            }
            Map<String, Object> info = new HashMap<>();
            info.put("branches", names);
            info.put("current", git.getRepository().getBranch());
            return AIToolResult.success("分支：" + String.join(", ", names)
                    + "（当前 " + git.getRepository().getBranch() + "）", info);
        }
    }

    private AIToolResult doDiff(File dir) throws Exception {
        try (org.eclipse.jgit.api.Git git = open(dir)) {
            List<org.eclipse.jgit.diff.DiffEntry> entries = git.diff().call();
            if (entries.isEmpty()) {
                return AIToolResult.success("没有未暂存的改动");
            }
            StringBuilder sb = new StringBuilder();
            List<String> files = new ArrayList<>();
            for (org.eclipse.jgit.diff.DiffEntry e : entries) {
                files.add(e.getChangeType() + " " + e.getNewPath());
                sb.append(e.getChangeType()).append("  ").append(e.getNewPath()).append("\n");
            }
            Map<String, Object> info = new HashMap<>();
            info.put("changes", files);
            return AIToolResult.success(sb.toString().trim(), info);
        }
    }

    private AIToolResult doClone(File dir, String url) throws Exception {
        if (url == null || url.trim().isEmpty()) {
            return AIToolResult.fail("clone 需要 url");
        }
        if (dir.exists() && dir.list() != null && dir.list().length > 0) {
            return AIToolResult.fail("目标目录非空：" + dir.getAbsolutePath());
        }
        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.cloneRepository()
                .setURI(url)
                .setDirectory(dir)
                .call()) {
            Map<String, Object> info = new HashMap<>();
            info.put("path", dir.getAbsolutePath());
            info.put("branch", git.getRepository().getBranch());
            return AIToolResult.success("克隆完成：" + url + " → " + dir.getAbsolutePath(), info);
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private org.eclipse.jgit.api.Git open(File dir) throws Exception {
        return org.eclipse.jgit.api.Git.open(dir);
    }

    private File resolveDir(Map<String, Object> parameters) {
        String path = str(parameters, "path", null);
        if (path != null && !path.trim().isEmpty()) {
            return new File(path);
        }
        File base = context != null ? context.getFilesDir() : new File(".");
        return new File(base, DEFAULT_REPO_DIR);
    }

    private static String str(Map<String, Object> params, String key, String def) {
        Object v = params == null ? null : params.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private static int intOf(Map<String, Object> params, String key, int def) {
        try {
            return Integer.parseInt(String.valueOf(params.get(key)));
        } catch (Throwable t) {
            return def;
        }
    }
}
