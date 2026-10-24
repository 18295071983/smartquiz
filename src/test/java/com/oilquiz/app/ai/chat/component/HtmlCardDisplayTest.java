package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

/**
 * html 卡的显示档位归属（2026-10-24）。
 *
 * <p>html 从 {@code ALWAYS_INLINE_TYPES} 名单里摘出去了：自动挂的网页卡（工作区 .html 产物、
 * 工具结果里的 html 字段）跟随用户默认档，而"折叠等于正文消失"的那两类——mermaid/katex 模板、
 * 消息里内联的 component 标记——改由<b>生产者自己声明 {@code display=inline}</b>。
 * 这里把两侧口径都钉住：默认档生效、正文型仍整卡、带按钮的仍不许收起、
 * 以及抽屉里的档位开关真的能把它切回整卡。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class HtmlCardDisplayTest {

    private static ComponentData comp(String type, String json) {
        try {
            return new ComponentData(type, new org.json.JSONObject(json));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ==================== 产物型网页卡：跟随默认档 ====================

    @Test
    public void autoWebCardFollowsUserDefaultTier() {
        ComponentData web = comp("html",
                "{\"title\":\"report.html\",\"url\":\"file:///x/report.html\"}");
        assertFalse("html 不该再被类型名单硬按成整卡",
                ComponentDisplay.isAlwaysInline(web));
        assertEquals(ComponentDisplay.Mode.PEEK,
                ComponentDisplay.resolveMode(web, ComponentDisplay.Mode.PEEK));
        assertEquals(ComponentDisplay.Mode.INLINE,
                ComponentDisplay.resolveMode(web, ComponentDisplay.Mode.INLINE));
    }

    @Test
    public void explicitDeclarationStillBeatsDefaultTier() {
        ComponentData inline = comp("html", "{\"display\":\"inline\",\"html\":\"<p>x</p>\"}");
        assertEquals(ComponentDisplay.Mode.INLINE,
                ComponentDisplay.resolveMode(inline, ComponentDisplay.Mode.HIDDEN));
        ComponentData peek = comp("html", "{\"display\":\"peek\",\"html\":\"<p>x</p>\"}");
        assertEquals(ComponentDisplay.Mode.PEEK,
                ComponentDisplay.resolveMode(peek, ComponentDisplay.Mode.INLINE));
    }

    @Test
    public void htmlCardWithActionsIsNeverCollapsed() {
        // 通用底线：带 actions 的卡收起后用户点不到，回传链会断
        ComponentData withActions = comp("html",
                "{\"display\":\"hidden\",\"html\":\"<p>x</p>\","
                        + "\"actions\":[{\"label\":\"好\",\"value\":\"ok\"}]}");
        assertTrue(ComponentDisplay.isAlwaysInline(withActions));
        assertEquals(ComponentDisplay.Mode.INLINE,
                ComponentDisplay.resolveMode(withActions, ComponentDisplay.Mode.HIDDEN));
    }

    @Test
    public void peekLabelForWebCardIsNotItemCountJunk() {
        // 有标题（自动卡用文件名）：只出标题，不再重复 url 尾部，也不能掉进"N 项数据"
        assertEquals("report.html", ComponentDisplay.peekLabel(
                comp("html", "{\"title\":\"report.html\",\"url\":\"file:///x/report.html\"}")));
        // 没标题（模型内联 HTML）：类型中文名 + url 的文件名，而不是"N 项数据"
        assertEquals("网页 · page.html", ComponentDisplay.peekLabel(
                comp("html", "{\"url\":\"file:///x/page.html\"}")));
    }

    // ==================== 正文型 html：生产者自己声明 inline ====================

    @Test
    public void mermaidAndKatexTemplatesDeclareInlineDisplay() {
        List<MermaidMathSplitter.Segment> segs = MermaidMathSplitter.split(
                "前面说明\n```mermaid\ngraph TD;A-->B;\n```\n后面说明", false);
        ComponentData diagram = null;
        for (MermaidMathSplitter.Segment s : segs) {
            if (s.isComponent && s.component != null) {
                diagram = s.component;
                break;
            }
        }
        assertNotNull("mermaid 段必须切成 html 组件", diagram);
        assertEquals("html", diagram.type);
        assertTrue("模板必须走 INTERNAL_TEMPLATE（指纹可识别）", diagram.internalTemplate);
        assertEquals("模板是正文本身，默认档 peek 也不许把它收起",
                "inline", diagram.props.optString("display"));
        assertEquals(ComponentDisplay.Mode.INLINE,
                ComponentDisplay.resolveMode(diagram, ComponentDisplay.Mode.PEEK));
    }

    @Test
    public void inlineComponentMarkerDeclaresInlineForHtmlOnly() {
        ComponentData html = firstComponent(
                "```component:html\n{\"html\":\"<table><tr><td>1</td></tr></table>\"}\n```");
        assertNotNull(html);
        assertEquals("消息正文里的内联 html 就是正文，生产者得声明整卡",
                "inline", html.props.optString("display"));

        // 其它类型不强加 display——它们本来就该跟随默认档/自己的名单规则
        ComponentData table = firstComponent(
                "```component:table_card\n{\"title\":\"t\",\"headers\":[\"a\"],\"rows\":[[\"1\"]]}\n```");
        assertFalse("不该替非 html 类型塞 display", table.props.has("display"));

        // 模型自己写了 display 时尊重模型
        ComponentData declared = firstComponent(
                "```component:html\n{\"html\":\"<p>x</p>\",\"display\":\"peek\"}\n```");
        assertEquals("peek", declared.props.optString("display"));
    }

    @Test
    public void legacyTemplateCardsWithoutDisplayStayInline() {
        // 历史消息里持久化的 mermaid 卡是在 html 退出名单之前写的，props 没有 display；
        // 不靠指纹认回来，重新打开会话时流程图会被收成一行（等于图没了）
        ComponentData legacy = comp("html",
                "{\"html\":\"<!DOCTYPE html><html><head>"
                        + "<script src=\\\"js/mermaid.min.js\\\"></script></head><body>"
                        + "<div class=\\\"mermaid\\\">graph TD;A-->B;</div></body></html>\"}");
        assertFalse(legacy.props.has("display"));
        assertTrue("旧模板数据也得按正文整卡", ComponentDisplay.isAlwaysInline(legacy));
        assertEquals(ComponentDisplay.Mode.INLINE,
                ComponentDisplay.resolveMode(legacy, ComponentDisplay.Mode.PEEK));
        // 产物型网页卡不含指纹，仍然跟随默认档
        assertFalse(ComponentDisplay.isAlwaysInline(
                comp("html", "{\"title\":\"report.html\",\"url\":\"file:///x/report.html\"}")));
    }

    // ==================== 抽屉里的默认档真的能把它切回整卡 ====================

    /**
     * 走真实链路：设置项写 prefs（{@link ComponentDisplay#setDefaultTier}）→
     * {@link ComponentDisplay#defaultTier} 读回 → {@link ComponentDisplay#resolve} 定档。
     * 上一版只断言了 {@code resolveMode}（不碰 Context），这条把"用户把默认档设成 inline
     * 后网页卡回到整卡"那句话真正钉住——包括写进 prefs 的字符串形状。
     */
    @Test
    public void userTierSwitchDrivesWebCardBackToInline() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ComponentData web = comp("html",
                "{\"title\":\"report.html\",\"url\":\"file:///x/report.html\"}");

        // 出厂默认：没写过这个 key，按 peek —— 网页卡收成一行
        assertEquals(ComponentDisplay.Mode.PEEK, ComponentDisplay.defaultTier(ctx));
        assertEquals(ComponentDisplay.Mode.PEEK, ComponentDisplay.resolve(ctx, web));

        // 用户在抽屉里选"整卡"：写进 prefs 的串必须是 name() 的 inline，读回来才生效
        ComponentDisplay.setDefaultTier(ctx, "inline");
        assertEquals(ComponentDisplay.Mode.INLINE, ComponentDisplay.defaultTier(ctx));
        assertEquals(ComponentDisplay.Mode.INLINE, ComponentDisplay.resolve(ctx, web));

        // 切回摘要档 + 非法值都按 peek 落盘（默认档只有三档，写坏了不能把卡片全藏掉）
        ComponentDisplay.setDefaultTier(ctx, "peek");
        assertEquals(ComponentDisplay.Mode.PEEK, ComponentDisplay.resolve(ctx, web));
        ComponentDisplay.setDefaultTier(ctx, "whatever");
        assertEquals(ComponentDisplay.Mode.PEEK, ComponentDisplay.defaultTier(ctx));

        ComponentDisplay.setDefaultTier(ctx, ComponentDisplay.name(ComponentDisplay.Mode.PEEK));
    }

    private static ComponentData firstComponent(String content) {
        for (ComponentContentSplitter.Segment s : ComponentContentSplitter.split(content)) {
            if (s.isComponent && s.component != null) return s.component;
        }
        return null;
    }
}
