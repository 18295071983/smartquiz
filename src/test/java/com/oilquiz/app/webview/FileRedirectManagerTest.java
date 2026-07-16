package com.oilquiz.app.webview;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * FileRedirectManager 单元测试
 */
public class FileRedirectManagerTest {

    private FileRedirectManager manager;

    @Before
    public void setUp() {
        manager = FileRedirectManager.getInstance();
        manager.reset();
    }

    @After
    public void tearDown() {
        manager.reset();
    }

    @Test
    public void testSingleton() {
        FileRedirectManager instance1 = FileRedirectManager.getInstance();
        FileRedirectManager instance2 = FileRedirectManager.getInstance();
        assertSame("应该是同一个实例", instance1, instance2);
    }

    @Test
    public void testInitialize() {
        assertFalse("初始化前应该为 false", manager.isInitialized());

        manager.initialize(mgr -> {
            mgr.addRule("/test/file.txt", "/redirected/file.txt");
        });

        assertTrue("初始化后应该为 true", manager.isInitialized());
    }

    @Test(expected = IllegalStateException.class)
    public void testDoubleInitialize() {
        manager.initialize(mgr -> {
            mgr.addRule("/test/file.txt", "/redirected/file.txt");
        });

        // 第二次初始化应该抛出异常
        manager.initialize(mgr -> {
            mgr.addRule("/test/file2.txt", "/redirected/file2.txt");
        });
    }

    @Test(expected = IllegalStateException.class)
    public void testStrictModeEmptyRules() {
        // 严格模式下，空规则应该抛出异常
        manager.initialize(mgr -> {
            // 不添加任何规则
        });
    }

    @Test
    public void testAddRule() {
        manager.initialize(mgr -> {
            mgr.addRule("/source/file.txt", "/target/file.txt");
        });

        assertEquals("应该有一个规则", 1, manager.getAllRules().size());
    }

    @Test
    public void testAddRuleWithBuilder() {
        manager.initialize(mgr -> {
            mgr.addRule(new FileRedirectRule.Builder("/source/file.txt", "/target/file.txt")
                    .redirectType(FileRedirectRule.RedirectType.EXACT)
                    .build());
        });

        assertEquals("应该有一个规则", 1, manager.getAllRules().size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDuplicateRule() {
        manager.initialize(mgr -> {
            mgr.addRule("/source/file.txt", "/target/file.txt");
            mgr.addRule("/source/file.txt", "/target/file2.txt"); // 重复规则
        });
    }

    @Test
    public void testGetRedirectedPathExactMatch() {
        manager.initialize(mgr -> {
            mgr.addRule(new FileRedirectRule.Builder("/source/file.txt", "/target/file.txt")
                    .redirectType(FileRedirectRule.RedirectType.EXACT)
                    .build());
        });

        String redirected = manager.getRedirectedPath("/source/file.txt");
        assertEquals("应该精确匹配", "/target/file.txt", redirected);
    }

    @Test
    public void testGetRedirectedPathPrefixMatch() {
        manager.initialize(mgr -> {
            mgr.addRule(new FileRedirectRule.Builder("/source/", "/target/")
                    .redirectType(FileRedirectRule.RedirectType.PREFIX)
                    .build());
        });

        String redirected = manager.getRedirectedPath("/source/subfolder/file.txt");
        assertEquals("应该前缀匹配", "/target/subfolder/file.txt", redirected);
    }

    @Test
    public void testGetRedirectedPathWildcardMatch() {
        manager.initialize(mgr -> {
            mgr.addRule(new FileRedirectRule.Builder("/source/*.txt", "/target/*.txt")
                    .redirectType(FileRedirectRule.RedirectType.WILDCARD)
                    .build());
        });

        String redirected = manager.getRedirectedPath("/source/document.txt");
        assertEquals("应该通配符匹配", "/target/*.txt", redirected);
    }

    @Test(expected = FileRedirectException.class)
    public void testGetRedirectedPathNoRule() {
        manager.initialize(mgr -> {
            mgr.addRule("/source/file.txt", "/target/file.txt");
        });

        // 未配置规则的路径应该抛出异常
        manager.getRedirectedPath("/unconfigured/path.txt");
    }

    @Test(expected = FileRedirectException.class)
    public void testGetRedirectedPathEmptyPath() {
        manager.initialize(mgr -> {
            mgr.addRule("/source/file.txt", "/target/file.txt");
        });

        // 空路径应该抛出异常
        manager.getRedirectedPath("");
    }

    @Test
    public void testHasRedirectRule() {
        manager.initialize(mgr -> {
            mgr.addRule("/source/file.txt", "/target/file.txt");
        });

        assertTrue("应该有规则", manager.hasRedirectRule("/source/file.txt"));
        assertFalse("应该没有规则", manager.hasRedirectRule("/other/file.txt"));
    }

    @Test
    public void testRemoveRule() {
        manager.initialize(mgr -> {
            mgr.addRule(new FileRedirectRule.Builder("/source/file.txt", "/target/file.txt")
                    .redirectType(FileRedirectRule.RedirectType.EXACT)
                    .build());
        });

        assertTrue("应该移除成功", manager.removeRule("/source/file.txt", FileRedirectRule.RedirectType.EXACT));
        assertFalse("再次移除应该失败", manager.removeRule("/source/file.txt", FileRedirectRule.RedirectType.EXACT));
    }

    @Test
    public void testClearRules() {
        manager.initialize(mgr -> {
            mgr.addRule("/source/file1.txt", "/target/file1.txt");
            mgr.addRule("/source/file2.txt", "/target/file2.txt");
        });

        assertEquals("应该有两个规则", 2, manager.getAllRules().size());

        manager.clearRules();
        assertEquals("应该没有规则", 0, manager.getAllRules().size());
    }

    @Test
    public void testStrictMode() {
        assertTrue("默认应该启用严格模式", manager.isStrictMode());

        manager.setStrictMode(false);
        assertFalse("应该禁用严格模式", manager.isStrictMode());
    }

    @Test
    public void testNonStrictModeEmptyRules() {
        manager.setStrictMode(false);

        // 非严格模式下，空规则不应该抛出异常
        manager.initialize(mgr -> {
            // 不添加任何规则
        });

        assertTrue("应该初始化成功", manager.isInitialized());
    }

    @Test(expected = FileRedirectException.class)
    public void testNonStrictModeNoRule() {
        manager.setStrictMode(false);

        manager.initialize(mgr -> {
            mgr.addRule("/source/file.txt", "/target/file.txt");
        });

        // 即使非严格模式，未配置规则的路径也应该抛出异常
        manager.getRedirectedPath("/unconfigured/path.txt");
    }

    @Test
    public void testGetAllRulesImmutable() {
        manager.initialize(mgr -> {
            mgr.addRule("/source/file.txt", "/target/file.txt");
        });

        try {
            manager.getAllRules().add(new FileRedirectRule.Builder("/other/file.txt", "/target/file.txt").build());
            fail("应该抛出 UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // 预期行为
        }
    }

    @Test(expected = IllegalStateException.class)
    public void testAddRuleBeforeInitialize() {
        // 初始化前添加规则应该抛出异常
        manager.addRule("/source/file.txt", "/target/file.txt");
    }

    @Test(expected = IllegalStateException.class)
    public void testGetRedirectedPathBeforeInitialize() {
        // 初始化前获取重定向路径应该抛出异常
        manager.getRedirectedPath("/source/file.txt");
    }

    @Test
    public void testRulePriority() {
        manager.initialize(mgr -> {
            // 先添加前缀规则
            mgr.addRule(new FileRedirectRule.Builder("/source/", "/target/prefix/")
                    .redirectType(FileRedirectRule.RedirectType.PREFIX)
                    .build());
            // 再添加精确规则
            mgr.addRule(new FileRedirectRule.Builder("/source/specific.txt", "/target/exact/specific.txt")
                    .redirectType(FileRedirectRule.RedirectType.EXACT)
                    .build());
        });

        // 精确规则应该优先
        String exactRedirect = manager.getRedirectedPath("/source/specific.txt");
        assertEquals("精确规则应该优先", "/target/exact/specific.txt", exactRedirect);

        // 其他路径应该使用前缀规则
        String prefixRedirect = manager.getRedirectedPath("/source/other.txt");
        assertEquals("应该使用前缀规则", "/target/prefix/other.txt", prefixRedirect);
    }

    @Test
    public void testChainedRules() {
        manager.initialize(mgr -> {
            mgr.addRule("/source/file.txt", "/target/file.txt")
                    .addRule("/source2/file.txt", "/target2/file.txt")
                    .addRule("/source3/file.txt", "/target3/file.txt");
        });

        assertEquals("应该有三个规则", 3, manager.getAllRules().size());
    }

    @Test
    public void testReset() {
        manager.initialize(mgr -> {
            mgr.addRule("/source/file.txt", "/target/file.txt");
        });

        assertTrue("应该已初始化", manager.isInitialized());
        assertEquals("应该有一个规则", 1, manager.getAllRules().size());

        manager.reset();

        assertFalse("应该未初始化", manager.isInitialized());
        assertEquals("应该没有规则", 0, manager.getAllRules().size());
        assertTrue("应该启用严格模式", manager.isStrictMode());
    }
}
