package com.oilquiz.app.webview;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * FileRedirectRule 单元测试
 */
public class FileRedirectRuleTest {

    @Test
    public void testExactMatch() {
        FileRedirectRule rule = new FileRedirectRule.Builder("/source/file.txt", "/target/file.txt")
                .redirectType(FileRedirectRule.RedirectType.EXACT)
                .build();

        assertTrue("应该精确匹配", rule.matches("/source/file.txt"));
        assertFalse("不应该匹配不同路径", rule.matches("/source/other.txt"));
        assertFalse("不应该匹配子路径", rule.matches("/source/file.txt/sub"));
    }

    @Test
    public void testPrefixMatch() {
        FileRedirectRule rule = new FileRedirectRule.Builder("/source/", "/target/")
                .redirectType(FileRedirectRule.RedirectType.PREFIX)
                .build();

        assertTrue("应该匹配子路径", rule.matches("/source/file.txt"));
        assertTrue("应该匹配深层子路径", rule.matches("/source/subfolder/file.txt"));
        assertFalse("不应该匹配不同前缀", rule.matches("/other/file.txt"));
    }

    @Test
    public void testWildcardMatch() {
        FileRedirectRule rule = new FileRedirectRule.Builder("/source/*.txt", "/target/*.txt")
                .redirectType(FileRedirectRule.RedirectType.WILDCARD)
                .build();

        assertTrue("应该匹配 txt 文件", rule.matches("/source/document.txt"));
        assertTrue("应该匹配另一个 txt 文件", rule.matches("/source/other.txt"));
        assertFalse("不应该匹配非 txt 文件", rule.matches("/source/document.pdf"));
    }

    @Test
    public void testWildcardWithQuestionMark() {
        FileRedirectRule rule = new FileRedirectRule.Builder("/source/file?.txt", "/target/file?.txt")
                .redirectType(FileRedirectRule.RedirectType.WILDCARD)
                .build();

        assertTrue("应该匹配 file1.txt", rule.matches("/source/file1.txt"));
        assertTrue("应该匹配 fileA.txt", rule.matches("/source/fileA.txt"));
        assertFalse("不应该匹配 file10.txt", rule.matches("/source/file10.txt"));
    }

    @Test
    public void testRegexMatch() {
        FileRedirectRule rule = new FileRedirectRule.Builder("/source/[a-z]+\\.txt", "/target/regex.txt")
                .redirectType(FileRedirectRule.RedirectType.REGEX)
                .build();

        assertTrue("应该匹配小写字母", rule.matches("/source/document.txt"));
        assertFalse("不应该匹配包含数字", rule.matches("/source/file1.txt"));
    }

    @Test
    public void testGetRedirectedPathExact() {
        FileRedirectRule rule = new FileRedirectRule.Builder("/source/file.txt", "/target/file.txt")
                .redirectType(FileRedirectRule.RedirectType.EXACT)
                .build();

        assertEquals("应该返回目标路径", "/target/file.txt", 
                rule.getRedirectedPath("/source/file.txt"));
    }

    @Test
    public void testGetRedirectedPathPrefix() {
        FileRedirectRule rule = new FileRedirectRule.Builder("/source/", "/target/")
                .redirectType(FileRedirectRule.RedirectType.PREFIX)
                .build();

        assertEquals("应该替换前缀", "/target/subfolder/file.txt",
                rule.getRedirectedPath("/source/subfolder/file.txt"));
    }

    @Test
    public void testBuilderDefaults() {
        FileRedirectRule rule = new FileRedirectRule.Builder("/source/file.txt", "/target/file.txt")
                .build();

        assertEquals("默认应该是 EXACT", FileRedirectRule.RedirectType.EXACT, rule.getRedirectType());
        assertTrue("默认应该是 required", rule.isRequired());
    }

    @Test
    public void testBuilderCustomValues() {
        FileRedirectRule rule = new FileRedirectRule.Builder("/source/", "/target/")
                .redirectType(FileRedirectRule.RedirectType.PREFIX)
                .required(false)
                .build();

        assertEquals("应该是 PREFIX", FileRedirectRule.RedirectType.PREFIX, rule.getRedirectType());
        assertFalse("应该是 not required", rule.isRequired());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testEmptySourcePath() {
        new FileRedirectRule.Builder("", "/target/file.txt");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testEmptyTargetPath() {
        new FileRedirectRule.Builder("/source/file.txt", "");
    }

    @Test
    public void testEquals() {
        FileRedirectRule rule1 = new FileRedirectRule.Builder("/source/file.txt", "/target/file.txt")
                .redirectType(FileRedirectRule.RedirectType.EXACT)
                .build();

        FileRedirectRule rule2 = new FileRedirectRule.Builder("/source/file.txt", "/target/other.txt")
                .redirectType(FileRedirectRule.RedirectType.EXACT)
                .build();

        FileRedirectRule rule3 = new FileRedirectRule.Builder("/source/file.txt", "/target/file.txt")
                .redirectType(FileRedirectRule.RedirectType.PREFIX)
                .build();

        assertEquals("相同源路径和类型应该相等", rule1, rule2);
        assertNotEquals("不同类型不应该相等", rule1, rule3);
    }

    @Test
    public void testHashCode() {
        FileRedirectRule rule1 = new FileRedirectRule.Builder("/source/file.txt", "/target/file.txt")
                .redirectType(FileRedirectRule.RedirectType.EXACT)
                .build();

        FileRedirectRule rule2 = new FileRedirectRule.Builder("/source/file.txt", "/target/other.txt")
                .redirectType(FileRedirectRule.RedirectType.EXACT)
                .build();

        assertEquals("相同源路径和类型应该有相同 hashCode", rule1.hashCode(), rule2.hashCode());
    }

    @Test
    public void testToString() {
        FileRedirectRule rule = new FileRedirectRule.Builder("/source/file.txt", "/target/file.txt")
                .redirectType(FileRedirectRule.RedirectType.EXACT)
                .required(true)
                .build();

        String str = rule.toString();
        assertTrue("应该包含 sourcePath", str.contains("/source/file.txt"));
        assertTrue("应该包含 targetPath", str.contains("/target/file.txt"));
        assertTrue("应该包含 redirectType", str.contains("EXACT"));
        assertTrue("应该包含 required", str.contains("true"));
    }

    @Test
    public void testGetSourcePath() {
        FileRedirectRule rule = new FileRedirectRule.Builder("/source/file.txt", "/target/file.txt")
                .build();

        assertEquals("/source/file.txt", rule.getSourcePath());
    }

    @Test
    public void testGetTargetPath() {
        FileRedirectRule rule = new FileRedirectRule.Builder("/source/file.txt", "/target/file.txt")
                .build();

        assertEquals("/target/file.txt", rule.getTargetPath());
    }

    @Test
    public void testComplexWildcardPattern() {
        FileRedirectRule rule = new FileRedirectRule.Builder("/source/**/*test*.js", "/target/test.js")
                .redirectType(FileRedirectRule.RedirectType.WILDCARD)
                .build();

        assertTrue("应该匹配复杂模式", rule.matches("/source/folder/subfolder/mytestfile.js"));
        assertTrue("应该匹配另一个复杂模式", rule.matches("/source/test.js"));
    }
}
