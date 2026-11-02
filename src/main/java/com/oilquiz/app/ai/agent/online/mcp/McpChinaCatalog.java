package com.oilquiz.app.ai.agent.online.mcp;

import java.util.ArrayList;
import java.util.List;

/**
 * 国内 MCP 服务精选清单（市场「🇨🇳 国内」页签数据源）。
 *
 * <p>与官方 Registry（国际）不同，国内 MCP 服务主要分布在各家云厂商的 MCP 广场：
 * 阿里云百炼、腾讯云开发者社区等。这些服务大多需要先开通（拿专属端点/密钥），
 * 本清单内置有官方文档依据的名称、端点格式、鉴权方式与开通路径，便于一键复制/引导添加。</p>
 *
 * <p>数据来源（2026-11 快照）：阿里云帮助中心（百炼 / MaxCompute / 云监控）、
 * 腾讯云开发者社区 MCP 广场、腾讯位置服务官方文档。</p>
 */
public final class McpChinaCatalog {

    private McpChinaCatalog() {}

    /** 条目类型：URL_READY=端点固定可一键添加；GUIDE_ONLY=需开通后拿专属端点再手动添加 */
    public enum Kind { URL_READY, GUIDE_ONLY }

    public static class ChinaEntry {
        public final String id;
        public final String title;
        public final String description;
        /** 端点格式文本（可复制；GUIDE_ONLY 为模板说明） */
        public final String endpointText;
        /** 鉴权说明 */
        public final String authNote;
        /** 开通/接入指引 */
        public final String guide;
        public final Kind kind;
        /** URL_READY 时的传输（仅 kind==URL_READY 有效） */
        public final McpServerConfig.Transport transport;

        public ChinaEntry(String id, String title, String description, String endpointText,
                          String authNote, String guide, Kind kind, McpServerConfig.Transport transport) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.endpointText = endpointText;
            this.authNote = authNote;
            this.guide = guide;
            this.kind = kind;
            this.transport = transport;
        }
    }

    /** 国内精选清单（有官方文档依据，2026-11）。 */
    public static List<ChinaEntry> entries() {
        List<ChinaEntry> list = new ArrayList<>();

        list.add(new ChinaEntry(
                "aliyun-maxcompute", "阿里云 MaxCompute MCP",
                "用自然语言查询/分析 MaxCompute 数仓数据（SQL、表、任务）。",
                "https://mcp.cn-hangzhou.maxcompute.aliyun.com/mcp",
                "Bearer 阿里云 AccessKey（管理页「🔗 密钥」引用）",
                "阿里云控制台开通 MaxCompute 并创建 AccessKey，加到密钥分支后引用即可。",
                Kind.URL_READY, McpServerConfig.Transport.STREAMABLE_HTTP));

        list.add(new ChinaEntry(
                "aliyun-bailian", "阿里云百炼 MCP 广场",
                "阿里官方 MCP 服务（高德地图、网页解析 WebParser、天气等），开通后按 mcpCode 调用。",
                "https://dashscope.aliyuncs.com/api/v1/mcps/{mcpCode}/mcp",
                "Bearer DASHSCOPE_API_KEY（管理页「🔗 密钥」引用）",
                "进入百炼控制台 → MCP 广场开通所需服务 → 复制该服务的 mcpCode 与 API Key → "
                        + "用「＋ 添加」填入端点（把 {mcpCode} 换成实际值）并把 API Key 加入密钥分支后引用。",
                Kind.GUIDE_ONLY, null));

        list.add(new ChinaEntry(
                "modelscope-mcp", "魔搭 MCP 广场（ModelScope）",
                "阿里旗下开源模型社区 MCP 广场：1.2 万+ MCP 服务（Hosted 云端托管 / Local 本地），"
                        + "含官方 fetch 网页抓取、高德地图、法律检索、支付宝订阅等。",
                "https://modelscope.cn/mcp（服务详情页点「连接」生成 SSE URL）",
                "部分服务免 key 直连；需 key 的按服务商申请（详情页 GitHub 入口可查申请路径）",
                "打开魔搭 MCP 广场 → 筛选「Hosted」→ 进服务详情页点「连接」（需 key 的服务先填 key）→ "
                        + "复制生成的 SSE URL → App 里「＋ 添加」选 SSE 传输填入。",
                Kind.GUIDE_ONLY, null));

        list.add(new ChinaEntry(
                "tencent-map", "腾讯位置服务 MCP",
                "地理编码、路线规划、地点检索等位置能力。",
                "https://mcp.map.qq.com/sse?key=你的Key",
                "URL query 携带 key（申请于腾讯位置服务控制台）",
                "腾讯位置服务控制台创建应用拿 key，添加到管理页时端点直接填 "
                        + "https://mcp.map.qq.com/sse?key=你的Key（key 已带在 URL 里，无需引用密钥）。",
                Kind.URL_READY, McpServerConfig.Transport.SSE));

        list.add(new ChinaEntry(
                "tencent-cloud-mcp", "腾讯云 MCP 广场",
                "腾讯官方与热门第三方 MCP（云开发 CloudBase、AI 等），Hosted 托管模式。",
                "（按开通下发的端点 URL）",
                "按服务类型：密钥 / Bearer",
                "腾讯云开发者社区 → MCP 广场（cloud.tencent.com）选择服务开通 → 复制下发的 "
                        + "Hosted 端点 URL 与密钥 → 用「＋ 添加」加入管理页。",
                Kind.GUIDE_ONLY, null));

        list.add(new ChinaEntry(
                "tencent-cloudbase", "腾讯云开发 CloudBase MCP",
                "AI 原生后端：数据库、登录认证、云函数、容器托管、网站托管一站式集成。",
                "https://tcb-api.cloud.tencent.com/（按环境下发）",
                "CloudBase 密钥（Bearer）",
                "腾讯云 CloudBase 控制台开通环境 → 获取 MCP 接入端点与密钥 → "
                        + "用「＋ 添加」加入管理页并填密钥。",
                Kind.GUIDE_ONLY, null));

        list.add(new ChinaEntry(
                "aliyun-cms", "阿里云云监控 MCP",
                "查询云产品监控指标、告警与资源状态。",
                "https://openapi-mcp.cn-hangzhou.aliyuncs.com/s/Cms/cms/9fe79ff0bf5748e6923530c5097ec359/mcp",
                "Bearer 阿里云 AccessKey（管理页「🔗 密钥」引用）",
                "阿里云控制台开通云监控并创建 AccessKey，加到密钥分支后引用即可。",
                Kind.URL_READY, McpServerConfig.Transport.STREAMABLE_HTTP));

        return list;
    }
}
