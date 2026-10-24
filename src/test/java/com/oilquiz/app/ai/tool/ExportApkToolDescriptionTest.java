package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

/**
 * export_apk 的"模型实际收到什么"守卫（2026-10-24）。
 *
 * <p>在线 Agent 注入给模型的工具描述取的是 {@link AITool#getDescription()}
 * （{@code OnlineToolRegistry.buildToolJsonObject} → {@code AIToolManager.getToolDefinition}
 * → {@code createToolDefinitionFromAITool}），<b>不是</b> {@code @Tool} 注解里那份
 * （注解只被 {@code ToolSchemaExtractor} 用于依赖解析）。两套文本会各自漂移，
 * 而"描述里没有"直接等于"模型不会用"。</p>
 *
 * <p>这里钉住直播配置这条链路的关键词：漏一个就红，而不是等真机上问模型"你知道台标怎么配吗"。</p>
 */
public class ExportApkToolDescriptionTest {

    @Test
    public void runtimeDescriptionCoversLiveConfigAndLogos() {
        String desc = new ExportApkTool().getDescription();
        String[] must = {
                "live_url",                     // 一键直播入口
                "app.json",                     // 配置文件也能给直播配置
                "config/channels.m3u",          // 频道表放哪
                "config/logos",                 // 台标放哪
                "tvg-logo",                     // 频道表里指台标的字段
                "自适应台标",                    // 没给图标时的兜底
                "CONFIG_GUIDE.md",              // 详细目录规则的文档名
                "不要编造 M3U 地址"               // 真实源必须由用户提供
        };
        for (String kw : must) {
            assertTrue("运行时描述缺关键词[" + kw + "]，模型看不到就不会用；当前描述：" + desc,
                    desc.contains(kw));
        }
    }

    @Test
    public void paramDescriptionsPointAtConfigFolder() {
        Map<String, String> params = new ExportApkTool().getParameterDescriptions();
        String htmlDir = params.get("html_dir");
        String liveUrl = params.get("live_url");
        assertTrue("html_dir 说明要提配置目录: " + htmlDir,
                htmlDir != null && htmlDir.contains("config/channels.m3u"));
        assertTrue("html_dir 说明要提台标目录: " + htmlDir,
                htmlDir != null && htmlDir.contains("config/logos"));
        assertTrue("live_url 说明要提 tvg-logo: " + liveUrl,
                liveUrl != null && liveUrl.contains("tvg-logo"));
        assertTrue("live_url 说明要提自适应台标兜底: " + liveUrl,
                liveUrl != null && liveUrl.contains("自适应台标"));
    }
}
