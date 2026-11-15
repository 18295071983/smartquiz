package com.oilquiz.app.ai.tool;

import android.content.Context;

import com.oilquiz.app.ai.chat.status.DockSlotBus;
import com.oilquiz.app.ai.chat.status.SlotKind;
import com.oilquiz.app.ai.chat.status.SlotPriorities;
import com.oilquiz.app.ai.chat.status.SlotText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.List;
import java.util.Map;

/**
 * 插件推来的插槽 → 甲板（C4，2026-11-14）。
 *
 * <p>以前甲板的内容全部由手机代码构造（`RemoteStatusSlots` 等），插件只能推
 * `ui_component` 命令。现在插件可以直接推 {@code ui/slot} 帧，字段与
 * {@link DockSlotBus.Slot} 一一对应；手机只做校验和渲染，**不再需要为每种新槽位发版**。</p>
 *
 * <p>帧格式（见 27 号《远程插件更新指南》）：</p>
 * <pre>
 * { "type":"ui/slot", "data": { id, title, detail, action?, priority?, level?,
 *                               warn?, kind?: state|progress|list|action,
 *                               progress?: 0-100, count?: N,
 *                               actions?: [{id,label,style?: primary|plain}],
 *                               board?: alert|task|asset|state,
 *                               pinned?, remove?, rev?, epoch?, ttl_ms? } }
 * </pre>
 *
 * <p>④（2026-11-15）：{@code kind} 有契约（见 {@link SlotKind}）：state（默认）/progress/list/action。
 * 未知 kind、必填不匹配都在这里归一化/降级成 state，与插件侧 {@code normalizeSlotFields}
 * 同规则同输出；旧帧（无 kind/actions）按 state 渲染，行为不变。</p>
 *
 * <p>§20（2026-11-15）：{@code rev} 是同一 id 单调递增的版本号，乱序旧帧由
 * {@link DockSlotBus} 丢弃；{@code epoch} 是插件实例世代（毫秒时间戳），插件重载后
 * rev 从 1 重来，App 靠它清水位/清旧插件槽；{@code ttl_ms} 把相对存活时间换算成绝对
 * {@code expireAt}，生产者失联后槽位会自己消失。</p>
 *
 * <p>§22（2026-11-15）：优先级可用命名档位 {@code level}（p0..p5，表在
 * {@link SlotPriorities}，插件侧同名同值）。解析顺序：<b>已知 level → 表换算</b>；
 * 没有 level（或 level 未知）→ 旧帧的 {@code priority} 数值；两者都无 → p4（默认档）。
 * 这样新旧帧都能排对，未知档位也不会把顺序搞乱。</p>
 *
 * <p>§22 另加两条同路径：{@link #applyAll(JSONArray)}（批量，快照用）与
 * {@link #pullSnapshotAsync(Context, String)}（连上后拉一次 {@code GET /v2/slots}，
 * 让甲板不必等生产者推）。</p>
 */
public final class RemoteSlotBridge {

    private static final String TAG = "RemoteSlotBridge";

    /** ttl 上限（24h）：防止异常帧把过期时间算到溢出/永久。 */
    private static final long MAX_TTL_MS = 24L * 3600L * 1000L;

    /** 快照失败后的静默重试间隔（只重试一次）。 */
    private static final long SNAPSHOT_RETRY_DELAY_MS = 600L;

    private RemoteSlotBridge() {
    }

    /** 应用一条插槽帧；返回 true 表示已处理 */
    public static boolean apply(JSONObject data) {
        if (data == null) return false;
        String id = data.optString("id", "").trim();
        if (id.isEmpty()) return false;
        long rev = Math.max(0L, data.optLong("rev", 0L));
        // §20 修订（2026-11-15）：插件重载后 rev 会从 1 重来，epoch 变大就清水位、清旧插件槽；
        // epoch 更小的帧是上一世代迟到的，整帧丢弃（否则重载后移除/更新会永久失效）。
        if (DockSlotBus.notePluginEpoch(Math.max(0L, data.optLong("epoch", 0L))) < 0) {
            return true;
        }
        if (data.optBoolean("remove", false)) {
            DockSlotBus.remove(id, rev);
            return true;
        }
        /**
         * 2026-11-14：插槽"完成后消失"的统一约定。
         *
         * <p>插件历史上用"发一条空帧"（只有 id、没有 title/detail）表示移除，
         * 但这里以前只有 {@code remove:true} 才真的删 —— 于是"压缩中/子代理/任务"
         * 结束后槽位只是变成空壳留在总线里，甲板会挂一条空白。现在两种写法等价。</p>
         */
        String title = SlotText.cleanField(data, "title");
        String detail = SlotText.cleanField(data, "detail");
        int rawCount = data.optInt("count", 0);
        if (title.isEmpty() && detail.isEmpty() && rawCount <= 0) {
            DockSlotBus.remove(id, rev);
            return true;
        }
        // ④：kind/progress/actions 与插件 normalizeSlotFields 同口径；坏帧降级 state 不丢帧
        SlotKind.Normalized normalized = SlotKind.normalize(data);
        if (normalized.hasWarnings()) {
            logi("[dock] kind 归一化 id=" + id + " → " + normalized.kind
                    + "：" + String.join("；", normalized.warnings));
        }
        // 降级后可能只剩脏文本（例如 kind=action 的 title 是 [object Object]）：按空帧移除。
        if (SlotKind.STATE.equals(normalized.kind)
                && title.isEmpty() && detail.isEmpty() && normalized.count <= 0) {
            DockSlotBus.remove(id, rev);
            return true;
        }
        long ttlMs = Math.min(Math.max(0L, data.optLong("ttl_ms", 0L)), MAX_TTL_MS);
        long expireAt = ttlMs > 0 ? System.currentTimeMillis() + ttlMs : 0L;
        DockSlotBus.update(new DockSlotBus.Slot(
                id,
                resolvePriority(data),
                title,
                detail,
                data.optString("action", ""),
                data.optBoolean("warn", false),
                normalized.kind,
                data.optString("board", "state"),
                data.optBoolean("pinned", false),
                normalized.count,
                rev,
                expireAt,
                "plugin",
                normalized.progress,
                normalized.actions));
        return true;
    }

    /**
     * §22：批量应用（快照 {@code /v2/slots} 与任何多帧入口共用）。
     *
     * <p>与逐条 {@link #apply(JSONObject)} 完全同一路径：rev 乱序、epoch 世代、TTL、
     * 空帧移除的语义都不变；顺序按数组原序，插件侧已按 priority 升序排好。</p>
     *
     * @return 已处理的帧数（含移除帧；坏帧/无 id 帧跳过）
     */
    public static int applyAll(JSONArray frames) {
        if (frames == null) return 0;
        int applied = 0;
        for (int i = 0; i < frames.length(); i += 1) {
            JSONObject o = frames.optJSONObject(i);
            if (o == null) continue;
            if (apply(o)) applied += 1;
        }
        return applied;
    }

    /**
     * §22：连接建立后拉一次快照（后台线程，不阻塞调用方）。
     *
     * <p>断线重连后甲板以前要等生产者下一次推帧才有内容；插件 {@code /v2/slots}
     * 从内存里的槽位状态重建当前该显示的那一组，这里拉到后逐条走 {@link #apply}，
     * 与推帧同一条路径。失败静默重试一次，仍失败只打一行日志（不打扰用户）。</p>
     */
    public static void pullSnapshotAsync(Context context, String sessionId) {
        if (context == null) return;
        final Context app = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        final String sid = sessionId == null ? "" : sessionId.trim();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                pullSnapshotWithRetry(app, sid);
            }
        }, "dsh-slots-snapshot");
        t.setDaemon(true);
        t.start();
    }

    /** 两次尝试（一次重试）的拉取封装；任何异常都不外抛。 */
    private static void pullSnapshotWithRetry(Context app, String sid) {
        for (int attempt = 1; attempt <= 2; attempt += 1) {
            try {
                int n = pullSnapshotOnce(app, sid);
                logi("[dock] 快照拉取完成 session=" + sid + " 条数=" + n
                        + (attempt > 1 ? "（重试后成功）" : ""));
                return;
            } catch (Throwable t) {
                logi("[dock] 快照拉取失败 session=" + sid + " 第" + attempt + "次: "
                        + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
                if (attempt == 1) {
                    try {
                        Thread.sleep(SNAPSHOT_RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    /**
     * 同步拉一次快照（诊断/真机日志用；后台线程调用）。
     *
     * @return 应用到总线的条数
     * @throws Exception 未配置地址 / 网络失败 / 服务端 ok=false
     */
    public static int pullSnapshotOnce(Context c, String sid) throws Exception {
        RemoteDshTool tool = new RemoteDshTool(c);
        String base = tool.getBaseUrl();
        if (base == null || base.trim().isEmpty()) throw new Exception("未配置电脑端地址");
        String url = base.replaceAll("/+$", "") + "/v2/slots?session_id="
                + URLEncoder.encode(sid == null ? "" : sid, "UTF-8");
        Map<String, Object> resp = tool.httpJson(url, "GET", null, 12);
        if (resp == null) throw new Exception("空响应");
        if (!Boolean.TRUE.equals(resp.get("ok"))) {
            throw new Exception(String.valueOf(resp.get("error")));
        }
        Object raw = resp.get("slots");
        int applied = 0;
        if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                if (!(o instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) o;
                if (apply(new JSONObject(m))) applied += 1;
            }
        }
        return applied;
    }

    /**
     * §22：帧 → 优先级数值。
     *
     * <ol>
     *   <li>{@code level} 是已知档位（p0..p5）→ 用 {@link SlotPriorities} 换算；</li>
     *   <li>否则有 {@code priority} → 旧帧数值（旧插件/旧面板继续按数值排序）；</li>
     *   <li>两者都没有（或 level 未知且没给 priority）→ {@link SlotPriorities#DEFAULT}（p4）。</li>
     * </ol>
     */
    static int resolvePriority(JSONObject data) {
        if (data == null) return SlotPriorities.DEFAULT;
        String level = data.optString("level", "").trim();
        if (!level.isEmpty() && SlotPriorities.isKnown(level)) {
            return SlotPriorities.of(level);
        }
        if (data.has("priority")) {
            return data.optInt("priority", SlotPriorities.DEFAULT);
        }
        return SlotPriorities.DEFAULT;
    }

    /** 与 {@link DockSlotBus} 同口径的日志兜底（纯 JVM 单测没有真实 Log）。 */
    private static void logi(String message) {
        try {
            android.util.Log.i(TAG, message);
        } catch (Throwable ignored) {
            // 单测环境忽略
        }
    }
}
