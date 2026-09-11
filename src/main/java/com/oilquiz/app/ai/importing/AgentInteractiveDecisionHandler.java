package com.oilquiz.app.ai.importing;

import com.oilquiz.app.ai.importing.v2.ImportMain;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 智能体驱动的交互决策处理器 —— 智能体导入时四个决策点不再弹系统对话框，
 * 而是把决策载荷发布到任务状态（{@code import_status} 的 {@code pendingDecision}），
 * 由智能体创建多功能 ui_component 与用户交互，再用 {@code import_decide} 回传选择。
 * <p>
 * 导入线程在决策点阻塞等待回传；超时（5 分钟）或任务被取消 → 按【取消】处理（未确认不导入），
 * 与 {@link InteractiveImportDecisionHandler} 的语义一致，避免后台无人值守时未经确认写入题库。
 * <p>
 * 决策点（与 ImportMain 四决策点一一对应）：
 * <ol>
 *   <li>mapping：字段映射确认（可回传修改后的映射）</li>
 *   <li>preview：数据预览与错误处理（全部导入 / 仅导入完整题目 / 取消）</li>
 *   <li>fill：智能填充（AI 填充 / 跳过缺字段行 / 取消）</li>
 *   <li>ingest：最终入库确认</li>
 * </ol>
 */
public class AgentInteractiveDecisionHandler implements ImportMain.InteractionHandler {

    private static final String TAG = "AgentImportDecision";
    /** 单次决策等待智能体回传超时（毫秒）：超时按取消处理，避免永久阻塞导入线程 */
    private static final long WAIT_TIMEOUT_MS = 5 * 60 * 1000L;

    private final QuestionImportTaskManager.TaskStatus status;
    private final AtomicInteger seq = new AtomicInteger();

    public AgentInteractiveDecisionHandler(QuestionImportTaskManager.TaskStatus status) {
        this.status = status;
    }

    // ==================== 四个决策点 ====================

    @Override
    public Decision onMappingReady(Map<String, String> mapping, List<String> headers,
                                   String sourceName, String docHint, String mappingSource) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("sourceName", sourceName == null ? "" : sourceName);
            payload.put("docHint", docHint == null ? "" : docHint);
            payload.put("mappingSource", sourceNameOf(mappingSource));
            JSONObject m = new JSONObject();
            if (mapping != null) {
                for (Map.Entry<String, String> e : mapping.entrySet()) {
                    m.put(e.getKey(), e.getValue() == null ? JSONObject.NULL : e.getValue());
                }
            }
            payload.put("mapping", m);
            JSONArray hs = new JSONArray();
            if (headers != null) {
                for (String h : headers) hs.put(h);
            }
            payload.put("headers", hs);
        } catch (Exception ignored) {
        }
        String msg = "字段映射来源：" + sourceNameOf(mappingSource)
                + (docHint != null && !docHint.isEmpty() ? "\n题库说明：" + docHint : "")
                + "\n请确认映射方案（可在 payload.mapping 查看 标准字段←源列名）；如需修正可回传 mapping。";
        Result r = await("mapping", "确认字段映射", msg,
                opts("确认继续", "取消导入"), payload);
        if (r.cancelled) return cancel();
        Decision d = new Decision();
        if (r.mapping != null && !r.mapping.isEmpty()) {
            d.newMapping = r.mapping;
        }
        return d;
    }

    @Override
    public Decision onPreviewReady(ImportMain.QualityPreview preview, List<File> chunks) {
        JSONObject payload = qualityPayload(preview);
        String msg = "数据预览（入库前）：总行 " + n(preview == null ? 0 : preview.totalRows)
                + "，有效写入 " + n(preview == null ? 0 : preview.writtenRows)
                + "，无效/重复跳过 " + n(preview == null ? 0 : preview.skippedCount)
                + "，文件内重复题 " + n(preview == null ? 0 : preview.duplicateCount)
                + "。如何处理缺字段/不完整的行？";
        Result r = await("preview", "确认数据预览", msg,
                opts("全部导入", "仅导入完整题目", "取消导入"), payload);
        if (r.cancelled) return cancel();
        Decision d = new Decision();
        d.skipIncomplete = (r.choice == 1);
        d.previewConsumed = true;
        return d;
    }

    @Override
    public Decision onFillReady(ImportMain.QualityPreview preview, int missingCount) {
        if (missingCount <= 0) {
            return new Decision(); // 无缺失，无需交互
        }
        JSONObject payload = qualityPayload(preview);
        try {
            payload.put("missingCount", missingCount);
        } catch (Exception ignored) {
        }
        String msg = "检测到 " + missingCount + " 行缺字段。是否用 AI 智能填充缺失字段？";
        Result r = await("fill", "确认智能填充", msg,
                opts("AI 填充缺失字段", "跳过缺字段行", "取消导入"), payload);
        if (r.cancelled) return cancel();
        Decision d = new Decision();
        d.fillEnabled = (r.choice == 0);
        if (r.choice == 1) {
            d.skipIncomplete = true;
        }
        return d;
    }

    @Override
    public Decision onFinalConfirm(ImportMain.QualityPreview preview, ImportMain.ImportSummary summary) {
        JSONObject payload = qualityPayload(preview);
        long imported = summary == null ? 0 : summary.imported;
        long dup = summary == null ? 0 : summary.duplicated;
        long failed = summary == null ? 0 : summary.failed;
        try {
            JSONObject sm = new JSONObject();
            sm.put("imported", imported);
            sm.put("duplicated", dup);
            sm.put("failed", failed);
            payload.put("summary", sm);
        } catch (Exception ignored) {
        }
        String msg = "解析完成，即将入库：预计导入 " + n(imported) + " 题，重复 " + n(dup)
                + " 题，失败 " + n(failed) + " 题。确认写入题库？";
        Result r = await("ingest", "确认入库", msg,
                opts("确认入库", "取消导入"), payload);
        if (r.cancelled) return cancel();
        return new Decision();
    }

    // ==================== 决策发布与等待 ====================

    /** 决策结果（内部） */
    private static class Result {
        boolean cancelled;
        int choice;
        Map<String, String> mapping;
    }

    /**
     * 发布决策点并阻塞等待智能体回传：
     * 1. 构造 {@link QuestionImportTaskManager.PendingDecision} 并挂到 taskStatus；
     * 2. 阻塞等待 import_decide 回传（或超时/取消）；
     * 3. 清除挂载点，返回选择结果（未提交→取消）。
     */
    private Result await(String type, String title, String message, JSONArray options, JSONObject payload) {
        Result r = new Result();
        if (status == null) {
            return r; // 无状态载体：放行（不应发生）
        }
        QuestionImportTaskManager.PendingDecision pd =
                new QuestionImportTaskManager.PendingDecision(nextId(type), type, title, message);
        copy(options, pd.options);
        copy(payload, pd.payload);
        status.pendingDecision = pd;
        android.util.Log.i(TAG, "等待智能体回传决策点: type=" + type + " id=" + pd.decisionId);
        boolean ok = pd.await(WAIT_TIMEOUT_MS);
        status.pendingDecision = null;
        if (!ok || !pd.isSubmitted()) {
            android.util.Log.w(TAG, "决策点等待超时/被取消，按取消处理: " + type);
            r.cancelled = true;
            return r;
        }
        int idx = pd.choice();
        int lastIdx = options.length() - 1; // 末项=取消
        if (idx < 0 || idx >= options.length() || idx == lastIdx) {
            r.cancelled = true;
            return r;
        }
        r.choice = idx;
        r.mapping = toMap(pd.submittedMapping());
        return r;
    }

    private String nextId(String type) {
        return type + "-" + System.currentTimeMillis() + "-" + seq.incrementAndGet();
    }

    private Decision cancel() {
        Decision d = new Decision();
        d.action = Decision.CANCEL;
        return d;
    }

    // ==================== 工具方法 ====================

    private static JSONArray opts(String... items) {
        JSONArray a = new JSONArray();
        for (String s : items) {
            a.put((Object) s); // 显式走 put(Object)，规避不同 API 版本 put(String) 的受检异常差异
        }
        return a;
    }

    private static JSONObject qualityPayload(ImportMain.QualityPreview preview) {
        JSONObject p = new JSONObject();
        try {
            p.put("totalRows", preview == null ? 0 : preview.totalRows);
            p.put("writtenRows", preview == null ? 0 : preview.writtenRows);
            p.put("skippedCount", preview == null ? 0 : preview.skippedCount);
            p.put("duplicateCount", preview == null ? 0 : preview.duplicateCount);
            p.put("emptyQuestionCount", preview == null ? 0 : preview.emptyQuestionCount);
            p.put("incompleteCount", preview == null ? 0 : preview.incompleteCount);
            JSONObject mbf = new JSONObject();
            if (preview != null && preview.missingByField != null) {
                for (Map.Entry<String, Long> e : preview.missingByField.entrySet()) {
                    mbf.put(e.getKey(), e.getValue());
                }
            }
            p.put("missingByField", mbf);
        } catch (Exception ignored) {
        }
        return p;
    }

    /** JSONArray → JSONArray（逐项 put，避免共享可变引用） */
    private static void copy(JSONArray src, JSONArray dst) {
        if (src == null || dst == null) return;
        for (int i = 0; i < src.length(); i++) {
            dst.put(src.opt(i));
        }
    }

    /** JSONObject → JSONObject（逐键覆盖到目标，目标为 PendingDecision.payload 的最终字段） */
    private static void copy(JSONObject src, JSONObject dst) {
        if (src == null || dst == null) return;
        try {
            Iterator<String> it = src.keys();
            while (it.hasNext()) {
                String k = it.next();
                dst.put(k, src.opt(k));
            }
        } catch (Exception ignored) {
        }
    }

    private static Map<String, String> toMap(JSONObject obj) {
        if (obj == null) return null;
        Map<String, String> map = new LinkedHashMap<>();
        try {
            Iterator<String> it = obj.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object v = obj.opt(k);
                map.put(k, (v == null || v == JSONObject.NULL) ? null : String.valueOf(v));
            }
        } catch (Exception ignored) {
        }
        return map.isEmpty() ? null : map;
    }

    private static String n(long v) {
        return String.valueOf(v);
    }

    private static String sourceNameOf(String src) {
        if (src == null) return "AI 推理";
        switch (src) {
            case "cache": return "缓存命中";
            case "rules": return "规则识别";
            case "ai": return "AI 推理";
            case "fallback": return "兜底映射";
            default: return src;
        }
    }
}
