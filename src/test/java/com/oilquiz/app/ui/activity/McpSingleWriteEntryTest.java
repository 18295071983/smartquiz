package com.oilquiz.app.ui.activity;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * MCP 配置写入口的唯一性（源码级契约）。
 *
 * <p>2026-11-04 之前有两处能写 MCP 配置：API 配置页的「＋ 添加 / 🗑 删除」卡片和管理页的完整编辑。
 * 两处规则不一样，API 配置页那条不做占位符校验、不引密钥就直接 enabled=true，
 * 于是同一屏能造出"显示已添加、其实必然连不上"的条目，而错误只在管理页暴露。
 * 现在 API 配置页只报状态与跳转，安装走市场页且必须先探测。</p>
 *
 * <p>这类"入口只剩一个"的约束，运行时测不出来（没人会去点已经删掉的按钮），
 * 所以按源码断言：写接口一旦再被 API 配置页引用，测试就红。</p>
 */
public class McpSingleWriteEntryTest {

    private static final Path SRC = Paths.get("src/main/java/com/oilquiz/app");

    private static String read(String rel) throws Exception {
        Path p = SRC.resolve(rel);
        assertTrue("文件不存在：" + p, Files.exists(p));
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    public void apiConfigPageNoLongerWritesMcpConfig() throws Exception {
        String api = read("ui/activity/ApiConfigActivity.java");
        assertFalse("API 配置页不该再写 MCP 配置：" + firstHit(api, "upsertServer("),
                api.contains("upsertServer("));
        assertFalse("API 配置页不该再删 MCP 服务", api.contains("removeServer("));
        assertFalse("旧的常量清单已经删掉，不该还有引用", api.contains("McpChinaCatalog"));
    }

    @Test
    public void apiConfigPageStillPointsAtBothRealPages() throws Exception {
        String api = read("ui/activity/ApiConfigActivity.java");
        assertTrue("要能跳市场安装", api.contains("McpMarketActivity.class"));
        assertTrue("要能跳管理页改配置", api.contains("McpManagerActivity.class"));
    }

    @Test
    public void marketInstallsThroughTheProbeFirstPath() throws Exception {
        String market = read("ui/activity/McpMarketActivity.java");
        assertTrue("市场页必须读 JSON 清单，而不是自己写死条目",
                market.contains("McpMarketCatalog"));
        assertTrue("装之前要校验端点", market.contains("McpMarketInstaller.validateInstall"));
        assertTrue("装之前要真跑一次 initialize + tools/list", market.contains(".probe("));
        assertFalse("配置对象只能由 McpMarketInstaller 组装（防止绕过探测直接 enabled=true）",
                market.contains("new McpServerConfig("));
    }

    @Test
    public void brokenBuiltinTemplatesAreNotSeededOnFirstRun() throws Exception {
        String store = read("ai/agent/online/mcp/McpConfigStore.java");
        assertFalse("首启不再预置模板", store.contains("builtinTemplates"));
        assertFalse("不得再内置别人账号下发的分享端点", store.contains("9fe79ff0"));
    }

    @Test
    public void legacyHardcodedCatalogIsGone() throws Exception {
        assertFalse("Java 常量清单应已被 JSON 取代",
                Files.exists(SRC.resolve("ai/agent/online/mcp/McpChinaCatalog.java")));
        assertTrue("JSON 清单必须随包", Files.exists(
                Paths.get("src/main/assets/mcp_market_catalog.json")));
    }

    private static String firstHit(String haystack, String needle) {
        int i = haystack.indexOf(needle);
        if (i < 0) return "（未找到）";
        int line = haystack.substring(0, i).split("\n", -1).length;
        return "第 " + line + " 行";
    }
}
