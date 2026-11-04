package com.oilquiz.app.ai.agent.online.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 审批与密钥存储两条口径的源码级契约。
 *
 * <p>都是"运行时看不见"的东西：审批默认档只在真机点第六次时才显出糟糕，
 * 明文 Key 只在有人翻配置文件时才难看。把它们钉成源码断言，改动时才会被拦住，
 * 而不是等下一次真机体验再发现。</p>
 */
public class McpApprovalAndSecretWiringTest {

    private static final Path SRC = Paths.get("src/main/java/com/oilquiz/app");

    private static String read(String rel) throws Exception {
        Path p = SRC.resolve(rel);
        assertTrue("文件不存在：" + p, Files.exists(p));
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    public void approvalFlowAsksOnlyWhenItHasToAndCanStopAskingForASession() throws Exception {
        String manager = read("ai/agent/online/mcp/McpManager.java");
        assertTrue("默认档要先看注解、再走名称启发式",
                manager.contains("inferApproval(entry.annotations, entry.remoteName, entry.description)")
                        || manager.contains("inferApproval(null, remoteName, \"\")"));
        assertTrue("放行必须先按作用域查，再决定弹不弹",
                manager.contains("McpApprovalManager.isAllowed(session, run, serverId)"));
        assertTrue("用户选的每一档范围都要真的登记",
                manager.contains("McpApprovalManager.record(ar.decision, session, run, serverId)"));
        assertTrue("会话号与轮号从 SessionContext 取，不能自己造",
                manager.contains("SessionContext.current()")
                        && manager.contains("SessionContext.currentRun()"));

        String approval = read("ai/agent/online/mcp/McpApprovalManager.java");
        assertTrue("弹框要写清为什么问", approval.contains("为什么要确认"));
        assertTrue("每一行都带作用域，点之前就能看见范围",
                approval.contains("允许一次（只这一次）")
                        && approval.contains("本轮都允许")
                        && approval.contains("本次对话都允许（这个服务）")
                        && approval.contains("本次对话都允许（所有 MCP 服务）"));
        // 永久档只在拿不到会话号时出现：正常对话路径给的是"本轮/本会话"，
        // 要永久就得去管理页明确设——用户在读回答读到一半时不该被给一键闭嘴的机会
        assertTrue("永久档必须有会话缺失的前提", approval.contains("拿不到会话号"));
    }

    @Test
    public void managerPageExplainsThePolicyItApplied() throws Exception {
        String page = read("ui/activity/McpManagerActivity.java");
        assertTrue("策略列表要显示判定依据", page.contains("mgr.approvalBasis(cfg.id, t)"));
        assertTrue("默认档文案不能再说成只看注解",
                page.contains("缺注解按名称判断"));
    }

    @Test
    public void queryKeyIsNeverStoredInPlainTextOnTheMarketPath() throws Exception {
        String installer = read("ai/agent/online/mcp/McpMarketInstaller.java");
        assertTrue("向导要收独立的密钥输入", installer.contains("secretFieldLabel"));
        assertTrue("带占位符的端点走专门的可用性判断",
                installer.contains("isUsableEndpointWithKeyPlaceholder"));

        String market = read("ui/activity/McpMarketActivity.java");
        assertTrue("草稿里带着密钥走探测与落盘", market.contains("draft.secret"));
        assertTrue("密钥框只在 URL 带 key 的服务上出现",
                market.contains("McpMarketInstaller.secretFieldLabel(e)"));

        String manager = read("ai/agent/online/mcp/McpManager.java");
        assertTrue("发请求前才把密钥拼回端点", manager.contains("injectSecret(base.url, resolved)"));
        assertTrue("拼进 URL 后不再重复发 Bearer", manager.contains(
                "return base.withUrl(McpServerConfig.injectSecret(base.url, resolved)).withToken(\"\");"));

        String page = read("ui/activity/McpManagerActivity.java");
        assertTrue("历史明文 Key 要有收口入口", page.contains("splitQuerySecret(cfg.url)"));
        assertTrue("收口要提示泄露风险", read("ui/activity/McpManagerActivity.java")
                .contains("如果你怀疑它已经泄露"));
    }

    @Test
    public void modelSeesTheApprovalVerdictAndItsReason() throws Exception {
        String toolbox = read("ai/tool/McpToolboxTool.java");
        assertTrue("get 的返回里要带当前审批策略", toolbox.contains("out.put(\"approval\""));
        assertTrue("还要带判定依据", toolbox.contains("out.put(\"approval_basis\""));
        assertTrue("提示文案要按新口径解释，而不是只说 destructive",
                toolbox.contains("readOnlyHint=false") && toolbox.contains("名称/描述像写操作"));
        assertFalse("旧的\"标注 destructive=true 才确认\"说法已不成立（多数服务商根本不发注解）",
                toolbox.contains("若 annotations 标注 destructive=true（写/删/改），执行前先向用户说明并确认"));
    }

    @Test
    public void catalogDeclaresSecretPlaceholderForUrlKeyServices() throws Exception {
        String json = new String(Files.readAllBytes(
                Paths.get("src/main/assets/mcp_market_catalog.json")), StandardCharsets.UTF_8);
        assertTrue("高德必须用占位符模板", json.contains("\"url\": \"https://mcp.amap.com/mcp?key={key}\""));
        assertTrue("腾讯位置服务必须用占位符模板",
                json.contains("\"url\": \"https://mcp.map.qq.com/mcp?key={key}&format=0\""));
        assertEquals("两条 URL 带 key 的服务都要声明 secretPlaceholder", 2,
                countOf(json, "\"secretPlaceholder\": \"key\""));

        // 清单里不许出现任何真实形状的 Key 值（16 位以上的字母数字）
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[?&](?:key|token|secret|api_key|apikey)=([A-Za-z0-9]{16,})")
                .matcher(json.replaceAll("\\{key\\}", "placeholder"));
        assertFalse("清单不许内置真实 Key：" + (m.find() ? m.group(0) : ""), m.find());
    }

    private static int countOf(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) n++;
        return n;
    }
}
