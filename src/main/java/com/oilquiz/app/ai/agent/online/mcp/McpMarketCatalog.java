package com.oilquiz.app.ai.agent.online.mcp;

import android.content.Context;

import com.oilquiz.app.util.AILogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MCP 市场清单的读取层：{@code assets/mcp_market_catalog.json} → {@link McpMarketEntry}。
 *
 * <p>清单从 Java 常量搬到 JSON 的原因：条目要随厂商文档变（端点、鉴权、开通路径），
 * 放在资源文件里改数据不用动代码，字段也能被单测当契约校核。国际目录（官方 Registry）
 * 之后按同一结构合并进来，所以市场页不按"国内/国际"硬分列，而按能力位分列——
 * 用户要决定的是"现在能不能装"。</p>
 */
public final class McpMarketCatalog {

    private static final String TAG = "McpMarketCatalog";
    public static final String ASSET_FILE = "mcp_market_catalog.json";

    /** 一次加载结果：版本号 + 条目 + 数据问题（问题不挡渲染，供自检与测试断言）。 */
    public static final class Snapshot {
        public final String version;
        public final String disclaimer;
        public final List<McpMarketEntry> entries;
        public final List<String> problems;

        Snapshot(String version, String disclaimer,
                 List<McpMarketEntry> entries, List<String> problems) {
            this.version = version;
            this.disclaimer = disclaimer;
            this.entries = Collections.unmodifiableList(entries);
            this.problems = Collections.unmodifiableList(problems);
        }

        public static Snapshot empty(String why) {
            return new Snapshot("", "", Collections.<McpMarketEntry>emptyList(),
                    Collections.singletonList(why));
        }
    }

    private McpMarketCatalog() {}

    private static volatile Snapshot cached;

    /** 读内置清单（带缓存）。失败返回空快照并把原因放进 {@link Snapshot#problems}，不抛异常。 */
    public static Snapshot load(Context context) {
        Snapshot local = cached;
        if (local != null) return local;
        synchronized (McpMarketCatalog.class) {
            if (cached != null) return cached;
            Snapshot snap = readAsset(context);
            cached = snap;
            if (!snap.problems.isEmpty()) {
                AILogger.w(TAG, "清单数据问题 " + snap.problems.size() + " 条: " + snap.problems);
            }
            AILogger.i(TAG, "MCP 市场清单已加载: version=" + snap.version
                    + " entries=" + snap.entries.size());
            return cached;
        }
    }

    public static List<McpMarketEntry> entries(Context context) {
        return load(context).entries;
    }

    /** 按 id 查条目（市场卡片重绘、管理页显示来源用）；找不到返回 null。 */
    public static McpMarketEntry byId(Context context, String id) {
        if (id == null) return null;
        for (McpMarketEntry e : entries(context)) {
            if (e.id.equals(id)) return e;
        }
        return null;
    }

    /**
     * 关键字检索：命中名称/描述/服务商/标签/id。空串表示不过滤。
     *
     * <p>大小写统一走 {@link Locale#ROOT}：市场条目里混有英文域名与中文名，
     * 用默认 Locale 在土耳其等语言环境下会把 'I' 折错，搜索框就会"搜不到明明有的条目"。</p>
     */
    public static List<McpMarketEntry> search(Context context, String query) {
        List<McpMarketEntry> all = entries(context);
        if (query == null) return all;
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return all;
        List<McpMarketEntry> hits = new ArrayList<>();
        for (McpMarketEntry e : all) {
            if (contains(q, e.title) || contains(q, e.description) || contains(q, e.provider)
                    || contains(q, e.providerKey) || contains(q, e.id) || contains(q, e.tags)) {
                hits.add(e);
            }
        }
        return hits;
    }

    private static boolean contains(String q, String text) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(q);
    }

    private static boolean contains(String q, List<String> values) {
        for (String v : values) {
            if (contains(q, v)) return true;
        }
        return false;
    }

    /** 按能力位分组，顺序固定：能装的排前面，不兼容的垫底。 */
    public static Map<McpMarketEntry.Capability, List<McpMarketEntry>> groupByCapability(
            List<McpMarketEntry> entries) {
        Map<McpMarketEntry.Capability, List<McpMarketEntry>> grouped = new LinkedHashMap<>();
        for (McpMarketEntry.Capability c : CAPABILITY_ORDER) {
            grouped.put(c, new ArrayList<McpMarketEntry>());
        }
        for (McpMarketEntry e : entries) {
            List<McpMarketEntry> bucket = grouped.get(e.capability);
            if (bucket != null) bucket.add(e);
        }
        return grouped;
    }

    /** 分组的展示顺序（也是"这台手机现在能干什么"的阅读顺序：能装的三档在前，装不了的垫底）。 */
    public static final List<McpMarketEntry.Capability> CAPABILITY_ORDER =
            Collections.unmodifiableList(Arrays.asList(
                    McpMarketEntry.Capability.DIRECT_REMOTE,
                    McpMarketEntry.Capability.PROVISIONED_REMOTE,
                    McpMarketEntry.Capability.NEEDS_OAUTH,
                    McpMarketEntry.Capability.PORTAL_ONLY,
                    McpMarketEntry.Capability.LOCAL_ONLY));

    // ==================== 解析（与 Android 无关，单测可直接喂字符串） ====================

    /** 解析清单 JSON 文本。解析失败抛出由调用方兜住；条目级问题进 {@link Snapshot#problems}。 */
    public static Snapshot parse(String json) throws org.json.JSONException {
        List<String> problems = new ArrayList<>();
        JSONObject root = new JSONObject(json);
        int schema = root.optInt("schema", -1);
        if (schema != 1) {
            problems.add("清单 schema=" + schema + "，本版本只认 1，条目可能按旧结构解析");
        }
        List<McpMarketEntry> entries = new ArrayList<>();
        JSONArray arr = root.optJSONArray("entries");
        if (arr == null) {
            problems.add("清单缺 entries 数组");
            return new Snapshot(root.optString("version", ""), root.optString("disclaimer", ""),
                    entries, problems);
        }
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                problems.add("第 " + (i + 1) + " 条不是对象，已跳过");
                continue;
            }
            McpMarketEntry e = McpMarketEntry.fromJson(o, problems);
            if (e == null) continue;
            if (!seen.add(e.id)) {
                problems.add("丢弃重复 id 的条目：" + e.id);
                continue;
            }
            entries.add(e);
        }
        return new Snapshot(root.optString("version", ""), root.optString("disclaimer", ""),
                entries, problems);
    }

    // ==================== 资源读取 ====================

    private static Snapshot readAsset(Context context) {
        if (context == null) return Snapshot.empty("没有 Context，无法读取内置清单");
        try (InputStream in = context.getApplicationContext().getAssets().open(ASSET_FILE)) {
            return parse(readAll(in));
        } catch (Exception e) {
            AILogger.e(TAG, "读取 " + ASSET_FILE + " 失败: " + e.getMessage(), e);
            return Snapshot.empty("读取内置清单失败：" + e.getMessage());
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 测试用：清掉缓存，让下一次 {@link #load(Context)} 重新读资源。 */
    public static synchronized void clearCacheForTest() {
        cached = null;
    }
}
