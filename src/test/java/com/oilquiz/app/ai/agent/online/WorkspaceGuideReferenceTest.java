package com.oilquiz.app.ai.agent.online;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提示词里引用的工作区文档必须真的存在。
 *
 * <p>要防的失效：{@code AgentWorkspace} 的工具指引写"完整说明见工作区《XXX.md》"，
 * 而 {@code assets/apk_shell/guides/} 里没有这个文件（或被改名/删除）。运行时没有任何报错，
 * 模型只是照着指引去找一个不存在的文件 —— 白烧一轮，还可能回答"文档里说…"（它没读到任何东西）。
 * 2026-10-23 排查"手机上工具描述是旧的"时撞到的就是这一族。
 *
 * <p>断言从**源码派生**而不是手抄清单：抄一份引用名单，改名时就没人改它，测试会跟着一起瞎。
 */
public class WorkspaceGuideReferenceTest {

    /** 提示词里对文档的引用形如《LINUX_TOOLKIT_GUIDE.md》 */
    private static final Pattern GUIDE_REFERENCE = Pattern.compile("《([^》]+?\\.md)》");

    /** 代码生成的指南登记形如 m.put("使用速查表.md", ...) */
    private static final Pattern GENERATED_GUIDE = Pattern.compile("m\\.put\\(\"([^\"]+\\.md)\"");

    private static final File SOURCE = new File(
            "src/main/java/com/oilquiz/app/ai/agent/online/AgentWorkspace.java");
    private static final File ASSET_GUIDES = new File("src/main/assets/apk_shell/guides");

    @Test
    public void everyPromptReferencedGuideExists() throws Exception {
        assertTrue("找不到 AgentWorkspace.java（测试得从模块根目录跑）: " + SOURCE.getPath(),
                SOURCE.isFile());
        String source = new String(Files.readAllBytes(SOURCE.toPath()), StandardCharsets.UTF_8);

        Set<String> available = new LinkedHashSet<>();
        File[] assets = ASSET_GUIDES.listFiles();
        if (assets != null) {
            for (File f : assets) {
                if (f.isFile()) {
                    available.add(f.getName());
                }
            }
        }
        Matcher generated = GENERATED_GUIDE.matcher(source);
        while (generated.find()) {
            available.add(generated.group(1));
        }

        List<String> missing = new ArrayList<>();
        Matcher refs = GUIDE_REFERENCE.matcher(source);
        while (refs.find()) {
            String name = refs.group(1).trim();
            if (!available.contains(name)) {
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            fail("提示词引用了不存在的工作区文档 " + missing
                    + "；可用的有 " + available
                    + "。要么把文档放进 assets/apk_shell/guides/（或登记到 generatedGuides()），"
                    + "要么改掉引用 —— 别让模型照着指引去找没有的文件。");
        }
    }

    /** 反向守卫：assets 里有文档但提示词一次都没提，等于模型永远不会去读它 */
    @Test
    public void assetGuidesAreAtLeastOnceReferenced() throws Exception {
        String source = new String(Files.readAllBytes(SOURCE.toPath()), StandardCharsets.UTF_8);
        List<String> unreferenced = new ArrayList<>();
        File[] assets = ASSET_GUIDES.listFiles();
        if (assets == null) {
            return;
        }
        for (File f : assets) {
            if (!f.isFile()) {
                continue;
            }
            // THIRD_PARTY_NOTICES.md 这类给人看的许可声明，不指望模型主动读
            if (f.getName().startsWith("THIRD_PARTY")) {
                continue;
            }
            if (!source.contains("《" + f.getName() + "》")) {
                unreferenced.add(f.getName());
            }
        }
        if (!unreferenced.isEmpty()) {
            fail("assets 里有内置文档但提示词从未引用，模型不会知道去读：" + unreferenced
                    + "。请补引用，或确认它本就不该进工作区。");
        }
    }
}
