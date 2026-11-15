package com.oilquiz.app.ai.tool;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * GitTool 单元测试（2026-11-16）。
 *
 * <p>JGit 是纯 JVM 实现，所以这套流程（init → status → add → commit → log）能在本地单元测试里
 * 完整跑通，不需要真机。它同时验证了「Agent 可回滚」这条能力真的成立：
 * 建仓、写文件、暂存、提交、读历史。</p>
 */
public class GitToolTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private Map<String, Object> args(String action, File repo) {
        Map<String, Object> m = new HashMap<>();
        m.put("action", action);
        m.put("path", repo.getAbsolutePath());
        return m;
    }

    @Test
    public void fullFlow_init_status_add_commit_log() throws Exception {
        GitTool tool = new GitTool();
        File repo = new File(temp.getRoot(), "repo");

        assertTrue("init 应成功", tool.execute(args("init", repo)).isSuccess());

        // 写一个文件，status 应看到未跟踪
        File f = new File(repo, "hello.txt");
        try (FileWriter w = new FileWriter(f)) {
            w.write("hello agent");
        }
        AIToolResult status1 = tool.execute(args("status", repo));
        assertTrue("status 应成功", status1.isSuccess());
        assertTrue("应报告未跟踪文件: " + status1.getResult(),
                String.valueOf(status1.getResult()).contains("未跟踪 1"));

        // add + commit
        Map<String, Object> add = args("add", repo);
        add.put("file", ".");
        assertTrue("add 应成功", tool.execute(add).isSuccess());

        Map<String, Object> commit = args("commit", repo);
        commit.put("message", "first commit");
        AIToolResult commitResult = tool.execute(commit);
        assertTrue("commit 应成功: " + commitResult.getResult(), commitResult.isSuccess());
        assertTrue("应返回提交摘要: " + commitResult.getResult(),
                String.valueOf(commitResult.getResult()).contains("first commit"));

        // log 应能读回
        AIToolResult log = tool.execute(args("log", repo));
        assertTrue("log 应成功", log.isSuccess());
        assertTrue("历史应包含 first commit: " + log.getResult(),
                String.valueOf(log.getResult()).contains("first commit"));
    }

    @Test
    public void commitWithoutChanges_fails() throws Exception {
        GitTool tool = new GitTool();
        File repo = new File(temp.getRoot(), "repo2");
        tool.execute(args("init", repo));

        Map<String, Object> commit = args("commit", repo);
        commit.put("message", "empty");
        AIToolResult r = tool.execute(commit);
        assertTrue("无改动提交应失败", !r.isSuccess());
        assertNotNull(r.getErrorMessage());
        assertTrue("错误信息应说明无改动", r.getErrorMessage().contains("没有改动"));
    }

    @Test
    public void unknownAction_fails() {
        GitTool tool = new GitTool();
        AIToolResult r = tool.execute(args("frobnicate", temp.getRoot()));
        assertTrue(!r.isSuccess());
        assertTrue(String.valueOf(r.getErrorMessage()).contains("未知 action"));
    }
}
