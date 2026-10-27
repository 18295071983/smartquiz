package com.oilquiz.app.ai.model;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 随包配置表与 SharedPreferences 备份的<b>版本闸</b>。
 *
 * <p><b>为什么专门测它</b>：加载优先级是 filesDir 覆盖 &gt; prefs 备份 &gt; assets。中间那级是
 * "上次成功解析的备份"，装包不会清；没有版本比较时，一份旧备份会把之后任何一次随包更新
 * 永久盖住，日志还照样打印 "loaded from SharedPreferences cache"——现象就是"表改了等于没改"，
 * 而且查起来没有任何报错。这类"沉默的失效"必须靠用例钉住，而不是靠人记得。</p>
 *
 * <p>纯静态、不依赖 Context/assets，本机可跑。</p>
 */
public class ProviderTableCacheVersionTest {

    @Test
    public void cacheIsTrustedOnlyWhenNotOlderThanBundledTable() {
        // 缓存严格更新（外部 updateFromJson 写过更新的表）→ 用缓存
        assertTrue(ProviderConfigManager.isCacheTrusted("2026-10-27-v1", "2026-09-11-v4"));
        assertTrue(ProviderConfigManager.isCacheTrusted("2026-09-11-v5", "2026-09-11-v4"));
        // 同版本也让位给随包：同版本应同内容，"同版本不同内容"只能是改了表没改版本号，
        // 让随包赢才能把这类旧缓存自愈掉
        assertFalse(ProviderConfigManager.isCacheTrusted("2026-09-11-v4", "2026-09-11-v4"));
        // 缓存更旧 → 让位给随包（这正是补全端点后"装了新包却不生效"的那条）
        assertFalse(ProviderConfigManager.isCacheTrusted("2026-09-11-v3", "2026-09-11-v4"));
        assertFalse(ProviderConfigManager.isCacheTrusted("2026-09-10-v9", "2026-09-11-v1"));
    }

    @Test
    public void missingOrUnparsableVersionFallsBackConservatively() {
        // 缓存没写版本号 = 旧包产物，让位给随包
        assertFalse(ProviderConfigManager.isCacheTrusted(null, "2026-09-11-v4"));
        assertFalse(ProviderConfigManager.isCacheTrusted("  ", "2026-09-11-v4"));
        // 随包版本读不到（没有 assets / 解析失败）→ 保留缓存：判不了不能让用户手上突然没有表
        assertTrue(ProviderConfigManager.isCacheTrusted("2026-09-11-v4", null));
        assertTrue(ProviderConfigManager.isCacheTrusted("2026-09-11-v4", ""));
        // 两边都解析不了 → 保持今天的行为（用缓存），不误删一份能用的表
        assertTrue(ProviderConfigManager.isCacheTrusted("custom", "2026-09-11-v4"));
        assertTrue(ProviderConfigManager.isCacheTrusted("2026-09-11-v4", "whatever"));
    }

    @Test
    public void versionCompareHandlesDateAndRevision() {
        assertEquals(0, ProviderConfigManager.compareTableVersion("2026-09-11-v4", "2026-09-11-v4"));
        assertTrue(ProviderConfigManager.compareTableVersion("2026-10-01-v1", "2026-09-30-v9") > 0);
        assertTrue(ProviderConfigManager.compareTableVersion("2026-09-11-v3", "2026-09-11-v4") < 0);
        // 没有 -vN 视为 v0，比任何 -vN 都旧
        assertTrue(ProviderConfigManager.compareTableVersion("2026-09-11", "2026-09-11-v1") < 0);
        // 解析不了 → 哨兵值，调用方据此"保留缓存"
        assertEquals(Integer.MIN_VALUE,
                ProviderConfigManager.compareTableVersion("2026/09/11", "2026-09-11-v1"));
        assertEquals(Integer.MIN_VALUE,
                ProviderConfigManager.compareTableVersion(null, "2026-09-11-v1"));
    }

    @Test
    public void versionIsReadOutOfRawJsonWithoutParsingTheWholeTable() {
        assertEquals("2026-09-11-v4",
                ProviderConfigManager.versionOf("{\n  \"version\": \"2026-09-11-v4\",\n  \"providers\": []\n}"));
        // 缩进/空格不同的写法也要认
        assertEquals("2026-10-27-v1", ProviderConfigManager.versionOf("{\"version\":\"2026-10-27-v1\"}"));
        assertNull(ProviderConfigManager.versionOf("{\"providers\": []}"));
        assertNull(ProviderConfigManager.versionOf(null));
    }
}
