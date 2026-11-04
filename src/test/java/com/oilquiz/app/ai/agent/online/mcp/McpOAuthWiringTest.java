package com.oilquiz.app.ai.agent.online.mcp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * OAuth 整条链路的接线断言（源码级）。
 *
 * <p>为什么用读源码而不是运行时：这条链的真价值在"401 之后用户能看到下一步"，
 * 而那需要浏览器里真登录一次——JVM 测不到。可它又是最容易被改回半截的地方：
 * 传输层不再抛授权异常、或管理页把「去授权」按钮撤掉、或 manifest 少了回调 scheme，
 * 表现都只是"授权点了没反应"。这三处都有唯一锚点，钉成源码断言最划算。</p>
 */
public class McpOAuthWiringTest {

    private static final Path MAIN = Paths.get("src/main");

    private static String read(String rel) throws Exception {
        Path p = MAIN.resolve(rel);
        assertTrue("文件不存在：" + p, Files.exists(p));
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    public void callbackSchemeIsRegisteredAndTheActivityIsReachableFromBrowser() throws Exception {
        String manifest = read("AndroidManifest.xml");
        String registered = McpConstants.OAUTH_CALLBACK_SCHEME;
        assertTrue("必须注册自定义回调 scheme，否则服务商回不来",
                manifest.contains("android:scheme=\"" + registered + "\""));
        assertTrue("回调页必须 exported，系统浏览器才拉得起",
                manifest.contains("McpOAuthFlowActivity"));
        assertTrue("要允许浏览器跳转类别", manifest.contains("android.intent.category.BROWSABLE"));
        assertTrue("回传页不该留在最近任务里", manifest.contains("android:excludeFromRecents=\"true\""));
    }

    @Test
    public void bothTransportsTurnA401IntoAnAuthorizationSignal() throws Exception {
        for (String transport : new String[]{
                "java/com/oilquiz/app/ai/agent/online/mcp/StreamableHttpMcpTransport.java",
                "java/com/oilquiz/app/ai/agent/online/mcp/SseMcpTransport.java"}) {
            String src = read(transport);
            assertTrue(transport + " 要把 401 转成授权信号",
                    src.contains("McpAuthRequiredException.fromFailure"));
            assertTrue(transport + " 要读 WWW-Authenticate",
                    src.contains("WWW-Authenticate"));
        }
    }

    @Test
    public void managerPersistsAuthorizationAndClearsItWithTheServer() throws Exception {
        String manager = read("java/com/oilquiz/app/ai/agent/online/mcp/McpManager.java");
        assertTrue("未授权的服务要落到可识别的状态", manager.contains("\"auth_required\""));
        assertTrue("令牌解析必须带上 OAuth 那一支", manager.contains("McpOAuthFlow.accessTokenFor"));
        assertTrue("401 时先试静默续期", manager.contains("McpOAuthFlow.refresh(appContext"));
        assertTrue("删服务必须连带撤销授权", manager.contains("if (removed) revokeAuthorization(id)"));
        // 授权最长阻塞 5 分钟（等用户在浏览器里点），必须落在自己的线程上，
        // 否则这期间每个服务的 tools/list 都排不上队
        assertTrue("授权走独立线程", manager.contains("authExecutor.execute"));
        int authorizeBody = manager.indexOf("public void authorizeServer");
        assertTrue("授权入口存在", authorizeBody > 0);
        assertFalse("授权不许排到同步线程上",
                manager.substring(authorizeBody, authorizeBody + 1400).contains("syncExecutor.execute(() -> McpOAuthFlow"));
    }

    @Test
    public void tokenStoreWritesSynchronouslySoAProcessKillCannotLoseTokens() throws Exception {
        String store = read("java/com/oilquiz/app/ai/agent/online/mcp/McpTokenStore.java");
        assertTrue("落盘用 commit（apply 会丢）", store.contains(".commit()"));
        assertFalse("令牌表不许用 apply()", store.contains(".apply()"));
        assertTrue("令牌要过 Keystore", store.contains("ApiKeyCipher.encrypt"));
        assertTrue("读取时走解密分支", store.contains("ApiKeyCipher.decrypt"));
    }

    @Test
    public void bothPagesExposeTheAuthorizationAction() throws Exception {
        String market = read("java/com/oilquiz/app/ui/activity/McpMarketActivity.java");
        assertTrue("向导里被 401 挡下要给「去授权」", market.contains("authorizeServer("));
        assertTrue("授权前要先落一条停用配置（令牌按 serverId 存）",
                market.contains("upsertServer(probed.withEnabled(false))"));
        assertTrue("授权成功要自动复测，不让用户再点一遍", market.contains("runProbe(e, draft)"));

        String managerPage = read("java/com/oilquiz/app/ui/activity/McpManagerActivity.java");
        assertTrue("管理页要有授权入口", managerPage.contains("startAuthorization(cfg)"));
        assertTrue("需授权状态要单独显示", managerPage.contains("\"需授权\""));
        assertTrue("撤销授权也要有真动作", managerPage.contains("revokeAuthorization("));
    }
}
