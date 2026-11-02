package com.oilquiz.app.compliance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 许可文档的"不许漂移"约束。
 *
 * <p>背景：`THIRD_PARTY_NOTICES.md` 有两份（仓库 `docs/` 与随包 `assets/apk_shell/guides/`），
 * 文档里明写"两处改动需同步"。人工同步迟早漏一次，而这份文件是随 APK 分发的合规声明，
 * 漏一次就是"包里的声明和实际依赖不符"。这条测试把它钉住。</p>
 */
public class LicensingDocsTest {

    private static final Path DOCS = Paths.get("docs/THIRD_PARTY_NOTICES.md");
    private static final Path ASSET = Paths.get("src/main/assets/apk_shell/guides/THIRD_PARTY_NOTICES.md");
    private static final Path POLICY = Paths.get("docs/LICENSING_AND_COMPLIANCE.md");

    @Test
    public void noticesCopiesAreByteIdentical() throws Exception {
        assertTrue("随包副本不存在: " + ASSET, Files.exists(ASSET));
        assertTrue("仓库副本不存在: " + DOCS, Files.exists(DOCS));

        byte[] a = Files.readAllBytes(DOCS);
        byte[] b = Files.readAllBytes(ASSET);
        assertEquals("两份 THIRD_PARTY_NOTICES 必须逐字节一致（改一份就要同步另一份）",
                new String(a, StandardCharsets.UTF_8), new String(b, StandardCharsets.UTF_8));
    }

    /** 准入规则文档必须存在，且被声明文件引用（否则没人找得到）。 */
    @Test
    public void policyDocExistsAndIsLinked() throws Exception {
        assertTrue("缺少 docs/LICENSING_AND_COMPLIANCE.md", Files.exists(POLICY));

        String notices = new String(Files.readAllBytes(DOCS), StandardCharsets.UTF_8);
        assertTrue("THIRD_PARTY_NOTICES 要引用准入规则文档", notices.contains("LICENSING_AND_COMPLIANCE"));

        String policy = new String(Files.readAllBytes(POLICY), StandardCharsets.UTF_8);
        for (String must : new String[]{"GPL", "AGPL", "robots.txt", "个人信息", "verdict"}) {
            assertTrue("准入文档缺少关键条目: " + must, policy.contains(must));
        }
    }
}
