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
                + "log(提交历史)|diff(改动对比)|branch(分支列表/新建)|checkout(切换)|clone(克隆)|"
                + "remote(远端列表)|push(推送)|pull(拉取)";
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
        params.put("remote", "push/pull 用：远端名（默认 origin）");
        params.put("credential", "push/pull 用：凭据别名（如 github；token 本身不传给模型）");
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
                case "remote":
                    return doRemote(dir);
                case "push":
                    return doPush(dir, parameters);
                case "pull":
                    return doPull(dir, parameters);
                default:
                    return AIToolResult.fail("未知 action: " + action
                            + "（可用：init|status|add|commit|log|diff|branch|clone|remote|push|pull）");
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

    /** 远端列表：显示 fetch/push URL，便于模型判断有没有配远端。 */
    private AIToolResult doRemote(File dir) throws Exception {
        try (org.eclipse.jgit.api.Git git = open(dir)) {
            org.eclipse.jgit.lib.StoredConfig cfg = git.getRepository().getConfig();
            StringBuilder sb = new StringBuilder();
            java.util.List<String> names = new ArrayList<>();
            for (String remote : cfg.getSubsections("remote")) {
                names.add(remote);
                sb.append(remote)
                        .append("  fetch=").append(cfg.getString("remote", remote, "url"))
                        .append("\n");
            }
            if (names.isEmpty()) {
                return AIToolResult.success("还没有配置远端（可用 clone 建立）");
            }
            return AIToolResult.success(sb.toString().trim());
        }
    }

    /**
     * 推送。凭据只从加密存储按别名取 —— 模型传的是 credential:"github"，不是明文 token。
     */
    private AIToolResult doPush(File dir, Map<String, Object> parameters) throws Exception {
        String remote = str(parameters, "remote", "origin");
        String branch = str(parameters, "name", null);
        try (org.eclipse.jgit.api.Git git = open(dir)) {
            org.eclipse.jgit.lib.Repository repo = git.getRepository();
            if (!hasRemote(repo, remote)) {
                return AIToolResult.fail("没有名为 " + remote + " 的远端（先用 clone 建立远端）");
            }
            if (branch == null || branch.trim().isEmpty()) {
                branch = repo.getBranch();
            }
            org.eclipse.jgit.api.PushCommand push = git.push().setRemote(remote);
            applyCredentials(push, parameters);
            push.add(branch);
            Iterable<org.eclipse.jgit.transport.PushResult> results = push.call();
            StringBuilder sb = new StringBuilder();
            for (org.eclipse.jgit.transport.PushResult r : results) {
                for (org.eclipse.jgit.transport.RemoteRefUpdate u : r.getRemoteUpdates()) {
                    sb.append(u.getRemoteName()).append(" → ").append(u.getStatus()).append("\n");
                }
            }
            return AIToolResult.success("推送完成（" + remote + "/" + branch + "）：\n"
                    + sb.toString().trim());
        }
    }

    /** 拉取。FF_ONLY：手机上只做快进，避免产生难收拾的合并冲突。 */
    private AIToolResult doPull(File dir, Map<String, Object> parameters) throws Exception {
        String remote = str(parameters, "remote", "origin");
        try (org.eclipse.jgit.api.Git git = open(dir)) {
            org.eclipse.jgit.lib.Repository repo = git.getRepository();
            if (!hasRemote(repo, remote)) {
                return AIToolResult.fail("没有名为 " + remote + " 的远端");
            }
            org.eclipse.jgit.api.PullCommand pull = git.pull()
                    .setRemote(remote)
                    .setFastForward(org.eclipse.jgit.api.MergeCommand.FastForwardMode.FF_ONLY);
            applyCredentials(pull, parameters);
            org.eclipse.jgit.api.PullResult result = pull.call();
            return AIToolResult.success("拉取完成：success=" + result.isSuccessful()
                    + (result.getMergeResult() != null
                    ? " merge=" + result.getMergeResult().getMergeStatus() : ""));
        }
    }

    /** 按别名套用凭据（不明文传 token）。 */
    private void applyCredentials(org.eclipse.jgit.api.TransportCommand<?, ?> command,
                                  Map<String, Object> parameters) {
        String alias = str(parameters, "credential", null);
        if (alias == null || alias.trim().isEmpty()) {
            return;
        }
        GitCredentials.Credential cred = GitCredentials.load(context, alias);
        if (cred == null) {
            throw new IllegalStateException("凭据别名不存在: " + alias + "（先在设置里保存）");
        }
        command.setCredentialsProvider(cred.toProvider());
    }

    private static boolean hasRemote(org.eclipse.jgit.lib.Repository repo, String remote) {
        java.util.Set<String> subs = repo.getConfig().getSubsections("remote");
        return subs != null && subs.contains(remote);
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
