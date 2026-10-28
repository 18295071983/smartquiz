package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.oilquiz.app.ai.tool.AIToolManager.NameMatch;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具发现的"别名可见 + 命中方式自曝"回归（评审 REVIEW-2026-1029-01）。
 *
 * <p>背景：{@code document_parser} 原名 {@code python_file_ops}，2026-10-28 改名后写侧
 * （create/write/append/edit/diff）整体搬到 {@code file_generator}，旧名只留下一条别名映射。
 * 当时 {@code tool_registry(action=get)} 对旧名回的是<b>和精确命中一模一样</b>的措辞
 * （"已加载完整定义（下一轮可直接调用）"），而 {@code list}/{@code search} 又检不出别名——
 * 于是"搜不到却 get 得到"，模型没有机会怀疑自己叫错了名字，会照旧 schema 填 {@code action=edit}。</p>
 *
 * <p>钉住三条契约：</p>
 * <ol>
 *   <li>别名/回退结果必须在返回体里<b>自曝身份</b>（matched_by），且
 *       <b>只有精确命中</b>才允许出现"已加载完整定义"；</li>
 *   <li>别名要能被 {@code list}/{@code search} 检出，两边不再互相打脸；</li>
 *   <li>get 要带上工具级 description——拦截乱调靠的是那句"不写文件，写/改/差异用 file_generator"，
 *       不是靠执行期报错。</li>
 * </ol>
 */
@RunWith(MockitoJUnitRunner.class)
public class ToolRegistryAliasVisibilityTest {

    @Mock
    private Context context;

    private ToolRegistryTool registry;

    @Before
    public void setUp() {
        // 单例是静态字段；先用它把工具工厂建起来，再拿 tool_registry
        assertNotNull("AIToolManager 应能在纯 JVM 单测里初始化",
                AIToolManager.getInstance(context));
        registry = new ToolRegistryTool(context);
    }

    // ---------- 1. 命中方式判定 ----------

    @Test
    public void registeredNameResolvesAsExact() {
        AIToolManager.NameResolution r = AIToolManager.getInstance(context).resolveTool("document_parser");
        assertTrue("document_parser 是注册名，应解析成功", r.found());
        assertEquals("document_parser", r.tool);
        assertEquals("注册名本身必须判成 EXACT，别降级成 fuzzy", NameMatch.EXACT, r.match);
    }

    @Test
    public void legacyNameIsAliasNotFuzzyGuess() {
        AIToolManager.NameResolution r = AIToolManager.getInstance(context).resolveTool("python_file_ops");
        assertEquals("document_parser", r.tool);
        assertEquals("旧名是硬别名映射（resolveToolAlias），不是靠相似度猜的——"
                + "判成 FUZZY 会让措辞说'最相近'，那是错的解释", NameMatch.ALIAS, r.match);
    }

    @Test
    public void aliasesAreVisibleForDiscovery() {
        List<String> aliases = AIToolManager.getInstance(context).aliasesOf("document_parser");
        assertTrue("别名表里必须能查到旧名 python_file_ops，实际=" + aliases,
                aliases.contains("python_file_ops"));
    }

    // ---------- 2. get 的返回体自曝身份 ----------

    @Test
    public void getOnLegacyNameSelfIdentifiesAndPointsToWriter() {
        Map<String, Object> out = get("python_file_ops");
        assertEquals("document_parser", out.get("tool"));
        assertEquals("请求名要原样回显，调用方才看得出被替换了", "python_file_ops", out.get("requested_tool"));
        assertEquals("alias", out.get("matched_by"));
        String hint = String.valueOf(out.get("hint"));
        assertFalse("别名命中禁止说'已加载完整定义'——这正是把猜说成确定",
                hint.contains("已加载完整定义"));
        assertTrue("hint 要说明是历史名/别名", hint.contains("别名") || hint.contains("历史名"));
        String desc = String.valueOf(out.get("description"));
        assertTrue("get 必须带工具级描述：拦乱调靠的是'写/改/差异请用 file_generator'这句",
                desc.contains("file_generator"));
    }

    @Test
    public void getOnRegisteredNameStillSaysCompleteDefinition() {
        Map<String, Object> out = get("document_parser");
        assertEquals("exact", out.get("matched_by"));
        assertTrue(String.valueOf(out.get("hint")).contains("已加载完整定义"));
    }

    // ---------- 3. list/search 检得出别名 ----------

    @Test
    @SuppressWarnings("unchecked")
    public void searchFindsToolByLegacyName() {
        Map<String, Object> p = new HashMap<>();
        p.put("action", "search");
        p.put("keyword", "python_file_ops");
        Map<String, Object> out = cast(registry.execute(p).getResult());
        List<Map<String, Object>> tools = (List<Map<String, Object>>) out.get("tools");
        assertNotNull("search 旧名应返回候选，而不是 0 条", tools);
        boolean hit = false;
        for (Map<String, Object> t : tools) {
            if ("document_parser".equals(t.get("name"))) {
                hit = true;
                assertEquals("命中原因要写明是别名", "alias", t.get("matched"));
                assertEquals("python_file_ops", t.get("matched_alias"));
            }
        }
        assertTrue("search(python_file_ops) 必须能检出 document_parser", hit);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void listFilterAcceptsLegacyName() {
        Map<String, Object> p = new HashMap<>();
        p.put("action", "list");
        p.put("keyword", "python_file_ops");
        Map<String, Object> out = cast(registry.execute(p).getResult());
        List<Map<String, Object>> tools = (List<Map<String, Object>>) out.get("tools");
        assertNotNull(tools);
        boolean hit = false;
        for (Map<String, Object> t : tools) {
            if ("document_parser".equals(t.get("name"))) hit = true;
        }
        assertTrue("list 按旧名筛也要能列出现名（否则模型仍以为这工具不存在）", hit);
    }

    // ---------- 4. 措辞分档（纯逻辑，不依赖 Android） ----------

    @Test
    public void onlyExactMayClaimCompleteDefinition() {
        assertTrue(ToolRegistryTool.hintFor(NameMatch.EXACT, "a", "a").contains("已加载完整定义"));
        assertFalse(ToolRegistryTool.hintFor(NameMatch.ALIAS, "python_file_ops", "document_parser")
                .contains("已加载完整定义"));
        assertFalse(ToolRegistryTool.hintFor(NameMatch.FUZZY, "ops", "file_generator")
                .contains("已加载完整定义"));
        assertFalse(ToolRegistryTool.hintFor(NameMatch.NORMALIZED, "PythonFileOps", "python_file_ops")
                .contains("已加载完整定义"));
    }

    @Test
    public void fuzzyAndAliasHintsNameBothSides() {
        String alias = ToolRegistryTool.hintFor(NameMatch.ALIAS, "python_file_ops", "document_parser");
        assertTrue(alias.contains("python_file_ops"));
        assertTrue("必须点名替换到的工具，否则模型还得再查一轮", alias.contains("document_parser"));
        String fuzzy = ToolRegistryTool.hintFor(NameMatch.FUZZY, "ops", "file_generator");
        assertTrue(fuzzy.contains("ops") && fuzzy.contains("file_generator"));
        assertEquals("exact", ToolRegistryTool.matchedByLabel(NameMatch.EXACT));
        assertEquals("alias", ToolRegistryTool.matchedByLabel(NameMatch.ALIAS));
        assertEquals("fuzzy", ToolRegistryTool.matchedByLabel(NameMatch.FUZZY));
        assertEquals("not_found", ToolRegistryTool.matchedByLabel(NameMatch.NONE));
    }

    // ---------- helper ----------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) {
        assertTrue("tool_registry 的结果应是原生 Map（Gson 出干净 JSON），实际类="
                        + (o == null ? "null" : o.getClass().getName()),
                o instanceof Map);
        return (Map<String, Object>) o;
    }

    private Map<String, Object> get(String tool) {
        Map<String, Object> p = new HashMap<>();
        p.put("action", "get");
        p.put("tool", tool);
        return cast(registry.execute(p).getResult());
    }
}
