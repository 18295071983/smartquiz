package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertTrue;

import androidx.test.core.app.ApplicationProvider;

import com.oilquiz.app.ai.tool.SystemUIComponentTool;
import com.oilquiz.app.ai.tool.ToolSchemaExtractor;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 非在线主路径（注解 / getParameterDescriptions）也要教新卡片与图型（2026-10-23）。
 *
 * <p>在线模型走 {@code AIToolManager} 的硬编码定义（见 {@code ToolSchemaVisibilityTest}），
 * 动态工具/本地引擎等路径走注解与参数表。两条路各有一份文案，漏一条就会出现
 * "换个引擎模型就不会用新卡"。这里把另一条也钉住。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class ComponentPropsSchemaVisibilityTest {

    @Test
    public void fallbackSchemaTeachesNewCardsAndTypes() {
        SystemUIComponentTool tool = new SystemUIComponentTool(
                ApplicationProvider.getApplicationContext());
        String viaMap = String.valueOf(tool.getParameterDescriptions().get("props"));
        String viaAnnotation = String.valueOf(ToolSchemaExtractor.extract(tool).paramDesc);

        String[] keywords = {
                "playlist", "agenda", "location", "diff",   // 四张新卡
                "fill|short",                                 // 题库卡新题型
                "area|stacked_bar|pie|scatter|radar",         // 图表扩型
                "script/onclick",                             // html 按来源分档（2026-10-24）
                ".html/.htm"                                  // 工作区网页产物自动出卡
        };
        for (String kw : keywords) {
            assertTrue("参数表缺: " + kw, viaMap.contains(kw));
            assertTrue("注解路径缺: " + kw, viaAnnotation.contains(kw));
        }
    }
}
