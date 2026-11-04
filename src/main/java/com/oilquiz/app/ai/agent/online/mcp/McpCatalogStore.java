package com.oilquiz.app.ai.agent.online.mcp;

import android.content.Context;

import com.oilquiz.app.util.AILogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 每个 MCP 服务"上次同步到的目录"落盘（工具 + 资源 + 模板 + 提示）。
 *
 * <p>为什么要落盘：不落地时，每次冷启动到首次 {@code tools/list} 返回之间，
 * {@code mcp_toolbox} 搜出来是空的——而那段窗口恰好是用户开口第一句话的时候。
 * 表现出来就是"昨天还能用的地图工具，今天重启后第一次问它说没有"。</p>
 *
 * <p>落的是<b>缓存不是权威</b>：文件带 {@code syncedAtMs}，读取时原样交给上层标注"上次同步于…"，
 * 后台同步成功后整体覆盖。远端可能已经删掉某个工具，冷启动缓存里却还在——
 * 那种调用会以远端错误收场（不会假装成功），并且下一轮同步就把目录修正了。</p>
 *
 * <p>写用"临时文件 + rename"：进程在写一半时被杀不会留下半个 JSON 让下次加载直接失败。</p>
 */
public class McpCatalogStore {

    private static final String TAG = "McpCatalogStore";
    private static final String DIR_NAME = "mcp_catalog";

    /** 一次加载结果。{@link #syncedAtMs} 为 0 表示没有可用缓存。 */
    public static final class Loaded {
        public final long syncedAtMs;
        public final List<McpClient.McpToolInfo> tools;
        public final List<McpClient.McpResourceInfo> resources;
        public final List<McpClient.McpResourceInfo> templates;
        public final List<McpClient.McpPromptInfo> prompts;
        public final JSONObject capabilities;

        Loaded(long syncedAtMs, List<McpClient.McpToolInfo> tools,
               List<McpClient.McpResourceInfo> resources,
               List<McpClient.McpResourceInfo> templates,
               List<McpClient.McpPromptInfo> prompts, JSONObject capabilities) {
            this.syncedAtMs = syncedAtMs;
            this.tools = tools;
            this.resources = resources;
            this.templates = templates;
            this.prompts = prompts;
            this.capabilities = capabilities != null ? capabilities : new JSONObject();
        }
    }

    private final File dir;

    public McpCatalogStore(Context context) {
        this.dir = new File(context.getApplicationContext().getFilesDir(), DIR_NAME);
        if (!dir.exists()) {
            // 建不上也不致命：读不到缓存就等于没有缓存，功能退回"同步完才可用"
            boolean made = dir.mkdirs();
            if (!made && !dir.isDirectory()) {
                AILogger.w(TAG, "目录建不出来: " + dir.getAbsolutePath());
            }
        }
    }

    private File fileFor(String serverId) {
        String safe = McpServerConfig.normalizeId(serverId);
        return new File(dir, safe + ".json");
    }

    /** 保存一个服务的目录。返回 false 表示没落成功（不影响内存目录继续可用）。 */
    public synchronized boolean save(String serverId, long syncedAtMs,
                                     List<McpClient.McpToolInfo> tools,
                                     List<McpClient.McpResourceInfo> resources,
                                     List<McpClient.McpResourceInfo> templates,
                                     List<McpClient.McpPromptInfo> prompts,
                                     JSONObject capabilities) {
        if (serverId == null || serverId.isEmpty()) return false;
        try {
            JSONObject root = new JSONObject();
            root.put("serverId", serverId);
            root.put("syncedAtMs", syncedAtMs);
            root.put("capabilities", capabilities != null ? capabilities : new JSONObject());
            root.put("tools", toolsToJson(tools));
            root.put("resources", resourcesToJson(resources));
            root.put("templates", resourcesToJson(templates));
            root.put("prompts", promptsToJson(prompts));

            File target = fileFor(serverId);
            File tmp = new File(target.getAbsolutePath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(target)) {
                // Windows/某些 ROM 上目标已存在时 rename 会失败：先删再试一次
                if (!target.delete() && !tmp.renameTo(target)) {
                    tmp.delete();
                    AILogger.w(TAG, "目录落盘失败（rename）: " + serverId);
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            AILogger.e(TAG, "目录落盘失败 (" + serverId + "): " + e.getMessage());
            return false;
        }
    }

    /** 读取缓存目录；没有、损坏或 id 不符都返回 null。 */
    public synchronized Loaded load(String serverId) {
        if (serverId == null || serverId.isEmpty()) return null;
        File f = fileFor(serverId);
        if (!f.isFile()) return null;
        try {
            byte[] raw = readAll(f);
            JSONObject root = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            if (!serverId.equals(root.optString("serverId"))) {
                AILogger.w(TAG, "缓存文件里的 serverId 不匹配，忽略: " + serverId);
                return null;
            }
            return new Loaded(root.optLong("syncedAtMs", 0),
                    toolsFromJson(root.optJSONArray("tools")),
                    resourcesFromJson(root.optJSONArray("resources")),
                    resourcesFromJson(root.optJSONArray("templates")),
                    promptsFromJson(root.optJSONArray("prompts")),
                    root.optJSONObject("capabilities"));
        } catch (Exception e) {
            AILogger.w(TAG, "缓存目录读取失败，按无缓存处理 (" + serverId + "): " + e.getMessage());
            return null;
        }
    }

    /** 服务被删除或停用时清掉它的缓存，避免下次同名服务读到旧目录。 */
    public synchronized boolean delete(String serverId) {
        if (serverId == null || serverId.isEmpty()) return false;
        File f = fileFor(serverId);
        boolean gone = !f.exists() || f.delete();
        File tmp = new File(f.getAbsolutePath() + ".tmp");
        if (tmp.exists()) tmp.delete();
        return gone;
    }

    /** 缓存了多少个服务的目录（管理页/排障用）。 */
    public synchronized int cachedServerCount() {
        String[] names = dir.list();
        if (names == null) return 0;
        int n = 0;
        for (String name : names) {
            if (name.endsWith(".json")) n++;
        }
        return n;
    }

    // ==================== 序列化 ====================

    private static JSONArray toolsToJson(List<McpClient.McpToolInfo> tools) {
        JSONArray arr = new JSONArray();
        if (tools == null) return arr;
        for (McpClient.McpToolInfo t : tools) {
            if (t == null || t.name == null || t.name.isEmpty()) continue;
            try {
                JSONObject o = new JSONObject();
                o.put("name", t.name);
                o.put("description", t.description);
                o.put("inputSchema", t.inputSchema != null ? t.inputSchema : new JSONObject());
                if (t.annotations != null) o.put("annotations", t.annotations);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        return arr;
    }

    private static List<McpClient.McpToolInfo> toolsFromJson(JSONArray arr) {
        List<McpClient.McpToolInfo> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String name = o.optString("name");
            if (name.isEmpty()) continue;
            out.add(new McpClient.McpToolInfo(name, o.optString("description", ""),
                    o.optJSONObject("inputSchema") != null
                            ? o.optJSONObject("inputSchema") : new JSONObject(),
                    o.optJSONObject("annotations")));
        }
        return out;
    }

    private static JSONArray resourcesToJson(List<McpClient.McpResourceInfo> items) {
        JSONArray arr = new JSONArray();
        if (items == null) return arr;
        for (McpClient.McpResourceInfo r : items) {
            if (r == null || r.uri.isEmpty()) continue;
            try {
                arr.put(new JSONObject().put("uri", r.uri).put("name", r.name)
                        .put("description", r.description).put("mimeType", r.mimeType));
            } catch (Exception ignored) {
            }
        }
        return arr;
    }

    private static List<McpClient.McpResourceInfo> resourcesFromJson(JSONArray arr) {
        List<McpClient.McpResourceInfo> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String uri = o.optString("uri");
            if (uri.isEmpty()) continue;
            out.add(new McpClient.McpResourceInfo(uri, o.optString("name", uri),
                    o.optString("description", ""), o.optString("mimeType", "")));
        }
        return out;
    }

    private static JSONArray promptsToJson(List<McpClient.McpPromptInfo> items) {
        JSONArray arr = new JSONArray();
        if (items == null) return arr;
        for (McpClient.McpPromptInfo p : items) {
            if (p == null || p.name.isEmpty()) continue;
            try {
                arr.put(new JSONObject().put("name", p.name)
                        .put("description", p.description)
                        .put("arguments", p.arguments != null ? p.arguments : new JSONArray()));
            } catch (Exception ignored) {
            }
        }
        return arr;
    }

    private static List<McpClient.McpPromptInfo> promptsFromJson(JSONArray arr) {
        List<McpClient.McpPromptInfo> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String name = o.optString("name");
            if (name.isEmpty()) continue;
            out.add(new McpClient.McpPromptInfo(name, o.optString("description", ""),
                    o.optJSONArray("arguments")));
        }
        return out;
    }

    private static byte[] readAll(File f) throws IOException {
        java.io.InputStream in = new java.io.FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
