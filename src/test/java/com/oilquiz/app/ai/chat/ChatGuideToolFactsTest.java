package com.oilquiz.app.ai.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.oilquiz.app.ai.agent.online.OnlineAgentEngine;
import com.oilquiz.app.ai.agent.software.engine.AgentLoopEngine;

import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 聊天页「使用说明」弹框的工具口径不变量（2026-11-02 GUIDE-DYNAMIC-TOOLS）。
 *
 * <p><b>为什么钉这几条</b>：弹框此前把能力写死在 Java 字面量里——"本地常驻 5 个核心工具
 * （天气/时间/定位/搜索/记忆）"、"在线共 40+ 工具"、"调用到其他工具时自动动态加入"。
 * 实际注入集早已是 11 个且<b>不含定位</b>，"动态加入"对本地更是不成立（native PEG 按本次
 * tools 数组逐字枚举工具名，未注入 schema 的名字模型根本生成不出来；本地靠 tool_registry 的
 * call 代执行）。用户照着弹框里的说法提问，得到的是"我无法获取该信息"。这与历史上
 * "提示词核心清单硬编码≠CORE_TOOLS" 是同一个坑，只是残留在 UI 侧。</p>
 *
 * <p>所以这里要求：① UI 取数的入口与引擎注入集同源；② 每个会在弹框里露出的工具名都在
 * {@link ToolMetaRegistry} 名单内（表外会润色成 "Read File" 这种半生不熟的英文名）；
 * ③ Activity 与文案资源里不得再出现写死的数量/名单。</p>
 */
public class ChatGuideToolFactsTest {

    private static Object readEngineStatic(String field) throws Exception {
        Field f = AgentLoopEngine.class.getDeclaredField(field);
        f.setAccessible(true);
        return f.get(null);
    }

    /** UI 侧入口必须与引擎真正注入的核心集同源（漂移过一次就是因为两边各抄了一份） */
    @Test
    public void localCoreToolNamesComeFromTheInjectedSet() throws Exception {
        String[] injected = (String[]) readEngineStatic("DEFAULT_CORE_TOOLS");
        List<String> forUi = AgentLoopEngine.localCoreToolNames();
        assertEquals("localCoreToolNames() 与注入集条数不一致",
                injected.length, forUi.size());
        assertEquals("localCoreToolNames() 必须与注入集同序同值",
                Arrays.asList(injected), forUi);
    }

    /** 返回的是不可变副本：UI 改一下就把引擎的注入集改了，这个副作用绝不能有 */
    @Test
    public void localCoreToolNamesAreNotModifiable() {
        List<String> forUi = AgentLoopEngine.localCoreToolNames();
        try {
            forUi.add("whatever");
            org.junit.Assert.fail("localCoreToolNames() 返回了可修改的集合，UI 能改到引擎注入集");
        } catch (UnsupportedOperationException expected) {
            // 期望：unmodifiable 视图
        }
    }

    /** "定位"不在这一次的能力里：旧弹框把它写进本地清单，用户问了模型永远点不到 */
    @Test
    public void localCoreSetDoesNotAdvertiseLocation() {
        assertFalse("本地核心集没有 location，UI 不能再把它当成本地能力宣传",
                AgentLoopEngine.localCoreToolNames().contains("location"));
    }

    /** 弹框里露出的每个本地工具名都要在展示名单内（表外=给用户看内部名） */
    @Test
    public void everyLocalCoreToolHasAListedDisplayName() {
        for (String name : AgentLoopEngine.localCoreToolNames()) {
            assertTrue("工具 " + name + " 不在 ToolMetaRegistry 名单里，弹框/消息卡会露出原始名",
                    ToolMetaRegistry.isKnown(name));
        }
    }

    /** 在线常驻核心集同理 */
    @Test
    public void everyOnlineCoreToolHasAListedDisplayName() {
        for (String name : OnlineAgentEngine.coreTools()) {
            assertTrue("在线核心工具 " + name + " 不在 ToolMetaRegistry 名单里",
                    ToolMetaRegistry.isKnown(name));
        }
    }

    /** 常驻集不能超会上限，否则"其余按需发现"这句话自相矛盾 */
    @Test
    public void onlineCoreSetFitsTheSessionCap() {
        assertTrue("在线常驻核心集(" + OnlineAgentEngine.coreTools().size()
                        + ") 必须 <= 会话工具上限(" + OnlineAgentEngine.maxSessionToolCount() + ")",
                OnlineAgentEngine.coreTools().size() <= OnlineAgentEngine.maxSessionToolCount());
    }

    /** 弹框代码不得再把数量/名单抄回字面量（历史写法逐条点名，防止"顺手补上"） */
    @Test
    public void chatActivityDoesNotHardcodeToolFacts() throws Exception {
        String src = activitySource();
        for (String stale : new String[]{
                "共40+工具", "40+工具", "5个核心工具", "常驻5个核心工具",
                "约2.6GB", "约0.5GB", "天气 ☁️ 时间 🕐 定位 📍"}) {
            assertFalse("AIChatActivity 又写死了工具/模型口径：" + stale, src.contains(stale));
        }
        assertTrue("本地清单必须从 AgentLoopEngine.localCoreToolNames() 动态取",
                src.contains("localCoreToolNames()"));
        assertTrue("在线清单必须从 OnlineAgentEngine.coreTools() 动态取",
                src.contains("OnlineAgentEngine.coreTools()"));
        assertTrue("在线总数必须取注册表实际启用数",
                src.contains("getEnabledToolCount()"));
        assertTrue("展示名必须走 ToolMetaRegistry，不自己拼内部名",
                src.contains("ToolMetaRegistry.displayName("));
    }

    /**
     * 「还没有配置模型」弹框全场只此一处（2026-11-02 实测发现第二套）。
     *
     * <p>发送路径 {@code processChatMessageNormal} 里曾内联抄了一份同款弹框，两份各改各的：
     * 一份说"40+工具/2.6GB"，另一份还多列了"0.8B 约0.5GB"。任何一处改了另一处不会跟着改，
     * 用户会看到两种互相矛盾的口径，所以这条按标题字符串的出现次数钉住"只有一处实现"。</p>
     */
    @Test
    public void noModelSetupGuideIsBuiltInOnePlaceOnly() throws Exception {
        String src = activitySource();
        int occurrences = src.split("R\\.string\\.h_c1d12ffc", -1).length - 1;
        assertEquals("「还没有配置模型」的构建点必须唯一（现在 " + occurrences + " 处）", 1, occurrences);
    }

    /** 文案资源里的数量只能是占位符，不能是抄来的数字 */
    @Test
    public void guideStringsUsePlaceholdersForCounts() throws Exception {
        String zh = new String(Files.readAllBytes(
                Paths.get("src/main/res/values/strings.xml")), StandardCharsets.UTF_8);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<string name=\"(chat_guide_[a-z_]+)\">(.*?)</string>", java.util.regex.Pattern.DOTALL)
                .matcher(zh);
        int seen = 0;
        while (m.find()) {
            seen++;
            String body = m.group(2);
            for (String stale : new String[]{"40+", "2.6GB", "5 个核心", "5个核心"}) {
                assertFalse(m.group(1) + " 里写死了口径：" + stale, body.contains(stale));
            }
        }
        assertTrue("没扫到任何 chat_guide_* 文案（资源名或标签写法变了？）", seen >= 15);
        assertTrue("常驻工具数量必须是占位符（由代码动态传入）",
                zh.contains("%1$d 个"));
    }

    /** 三语都要有同一批 key：正文曾经是纯 Java 中文字面量，英文/繁中转译根本覆盖不到 */
    @Test
    public void guideStringsExistInAllLocales() throws Exception {
        Set<String> keys = new HashSet<>();
        String zh = new String(Files.readAllBytes(
                Paths.get("src/main/res/values/strings.xml")), StandardCharsets.UTF_8);
        collectGuideKeys(zh, keys);
        assertTrue("至少要有一批 chat_guide_* 文案", keys.size() >= 15);
        for (String locale : new String[]{"values-en", "values-zh-rTW"}) {
            String text = new String(Files.readAllBytes(
                    Paths.get("src/main/res/" + locale + "/strings.xml")), StandardCharsets.UTF_8);
            Set<String> localeKeys = new HashSet<>();
            collectGuideKeys(text, localeKeys);
            assertTrue(locale + " 缺少引导文案：" + missing(keys, localeKeys),
                    localeKeys.containsAll(keys));
        }
    }

    private static void collectGuideKeys(String xml, Set<String> out) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("name=\"(chat_guide_[a-z_]+)\"").matcher(xml);
        while (m.find()) out.add(m.group(1));
    }

    private static String missing(Set<String> all, Set<String> have) {
        StringBuilder sb = new StringBuilder();
        for (String k : all) {
            if (!have.contains(k)) sb.append(sb.length() > 0 ? ", " : "").append(k);
        }
        return sb.toString();
    }

    private static String activitySource() throws Exception {
        return new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/oilquiz/app/ui/activity/AIChatActivity.java")),
                StandardCharsets.UTF_8);
    }
}
