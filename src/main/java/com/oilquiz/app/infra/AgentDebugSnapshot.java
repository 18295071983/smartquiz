package com.oilquiz.app.infra;

import android.content.Context;

import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.jni.SystemProbe;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * DEBUG-SNAPSHOT(2026-11-08)：调试桥/调试台的统一“事实快照”。
 *
 * <p>目的：外部注入的结果文件不再只有 TOKEN/THINK 文本，还能同时拿到
 * 模型、n_ctx、真实 KV 占用、进程 RSS/swap、SystemProbe 原始读数、KV 统计、
 * 生成阶段/速度。字段全部来自已有 Java API / SystemProbe，不新增推测值。</p>
 *
 * <p>写 JSON 时逐字段 try/catch：任何一项查询失败都不能让整份快照失败，
 * 否则又会回到“调试工具本身把信息弄丢”的问题。</p>
 */
public final class AgentDebugSnapshot {

    private AgentDebugSnapshot() {
    }

    public static JSONObject capture(Context ctx) {
        JSONObject root = new JSONObject();
        put(root, "ts", System.currentTimeMillis());
        put(root, "ts_text", new java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(new java.util.Date()));

        JSONObject model = new JSONObject();
        try {
            LlamaHelper.ModelMeta meta = LlamaHelper.getModelMeta();
            int chatCtx = LlamaHelper.getContextSize();
            int engineCtx = SystemProbe.engineContextSize();
            int runtimeCtx = chatCtx > 0 ? chatCtx : engineCtx;
            long[] engine = SystemProbe.engine();
            int realKv = SystemProbe.engineKvTokens();
            put(model, "initialized", LlamaHelper.isModelInitialized());
            put(model, "chatReady", chatCtx > 0);
            put(model, "architecture", LlamaHelper.getModelArchitecture());
            put(model, "contextSize", runtimeCtx);
            // 真实 KV 占用用 llama_memory_seq_pos_max+1；getContextUsedTokens 是
            // NativeChatContext 的记账值，二者在工具轮/裁剪后会分叉（真机见过 52 vs 4496）。
            put(model, "contextUsed", realKv);
            put(model, "contextUsedBookkeeping", chatCtx > 0 ? LlamaHelper.getContextUsedTokensBookkeeping() : 0);
            // 服务层实际读到的数：和 SystemProbe 的 realKv 对账；JNI real 符号缺失时这里会退化成 52。
            put(model, "llamaContextUsed", LlamaHelper.getContextUsedTokens());
            put(model, "llamaContextRemaining", LlamaHelper.getContextRemainingTokens());
            put(model, "contextRemaining", Math.max(0, runtimeCtx - realKv));
            put(model, "gpuLayers", LlamaHelper.getGPULayers());
            put(model, "threads", LlamaHelper.getThreadCount());
            put(model, "batch", LlamaHelper.getBatchSize());
            put(model, "memoryPoolMB", LlamaHelper.getMemoryPoolSize());
            put(model, "memoryUsageMB", LlamaHelper.getMemoryUsage());
            if (meta != null) {
                put(model, "nCtxTrain", meta.nCtxTrain);
                put(model, "nEmbd", meta.nEmbd);
                put(model, "nLayer", meta.nLayer);
                put(model, "nHead", meta.nHead);
                put(model, "nParams", meta.nParams);
                put(model, "name", meta.modelName);
            }
            // meta 还没填（initModel 尚未完成）时，用 SystemProbe 的真实引擎数组兜底，
            // 避免 start 快照里 nLayer/nParams 全是 0 被误读成"模型没加载"。
            if (engine.length > SystemProbe.ENGINE_KV_BYTES_PER_TOKEN) {
                if (meta == null || meta.nLayer <= 0) {
                    put(model, "nLayer", engine[SystemProbe.ENGINE_BLOCK_COUNT]);
                }
                if (meta == null || meta.nParams <= 0) {
                    put(model, "nParams", engine[SystemProbe.ENGINE_MODEL_PARAMS]);
                }
                put(model, "kvLayers", engine[SystemProbe.ENGINE_KV_LAYERS]);
                put(model, "headKv", engine[SystemProbe.ENGINE_HEAD_COUNT_KV]);
                put(model, "headDim", engine[SystemProbe.ENGINE_HEAD_DIM]);
                put(model, "kvBytesPerToken", engine[SystemProbe.ENGINE_KV_BYTES_PER_TOKEN]);
                put(model, "nRsSeq", engine[SystemProbe.ENGINE_N_RS_SEQ]);
            }
        } catch (Throwable t) {
            put(model, "error", String.valueOf(t));
        }
        put(root, "model", model);

        JSONObject runtime = new JSONObject();
        try {
            put(runtime, "inferenceSpeed", LlamaHelper.getInferenceSpeed());
            put(runtime, "decodeSpeed", LlamaHelper.getDecodeSpeed());
            put(runtime, "phaseSpeed", LlamaHelper.getPhaseSpeed());
            put(runtime, "prefillProgress", LlamaHelper.getPrefillProgress());
        } catch (Throwable t) {
            put(runtime, "error", String.valueOf(t));
        }
        put(root, "runtime", runtime);

        try {
            put(root, "kv", parse(LlamaHelper.getKvCacheStats()));
        } catch (Throwable t) {
            put(root, "kv_error", String.valueOf(t));
        }
        try {
            put(root, "gen", parse(LlamaHelper.getGenPhase()));
        } catch (Throwable t) {
            put(root, "gen_error", String.valueOf(t));
        }

        put(root, "memory_text", SystemProbe.describeMemory());
        put(root, "engine_text", SystemProbe.describeEngine());
        put(root, "pageSize", pageSize());
        put(root, "meminfo", toArray(SystemProbe.memory()));
        put(root, "self", toArray(SystemProbe.self()));
        put(root, "pressure", toArray(SystemProbe.pressure()));
        put(root, "malloc", toArray(SystemProbe.mallocInfo()));
        put(root, "engine", toArray(SystemProbe.engine()));
        put(root, "devices", devices());
        return root;
    }

    /** 调试台一行/多行摘要；只用于展示，不参与决策。 */
    public static String summary(Context ctx) {
        StringBuilder sb = new StringBuilder();
        try {
            LlamaHelper.ModelMeta meta = LlamaHelper.getModelMeta();
            int nCtx = LlamaHelper.getContextSize();
            if (nCtx <= 0) nCtx = SystemProbe.engineContextSize();
            sb.append("MODEL  ").append(meta != null ? meta.modelName : "?")
                    .append("  nCtx=").append(nCtx)
                    .append("  kv=").append(SystemProbe.engineKvTokens())
                    .append("(bookkeeping=").append(LlamaHelper.getContextUsedTokensBookkeeping()).append(")")
                    .append("  gpuLayers=").append(LlamaHelper.getGPULayers())
                    .append("  pool=").append(LlamaHelper.getMemoryPoolSize()).append("MB\n");
        } catch (Throwable t) {
            sb.append("MODEL  error=").append(t).append('\n');
        }
        try {
            sb.append("MEM    ").append(SystemProbe.describeMemory()).append('\n');
        } catch (Throwable t) {
            sb.append("MEM    error=").append(t).append('\n');
        }
        try {
            sb.append("ENGINE ").append(SystemProbe.describeEngine()).append('\n');
        } catch (Throwable t) {
            sb.append("ENGINE error=").append(t).append('\n');
        }
        try {
            sb.append("KV     ").append(LlamaHelper.getKvCacheStats());
        } catch (Throwable t) {
            sb.append("KV     error=").append(t);
        }
        return sb.toString();
    }

    private static long pageSize() {
        long[] m = SystemProbe.memory();
        return m.length > SystemProbe.MEM_PAGE_SIZE_BYTES
                ? m[SystemProbe.MEM_PAGE_SIZE_BYTES] : 0;
    }

    private static JSONArray toArray(long[] values) {
        JSONArray a = new JSONArray();
        if (values == null) return a;
        for (long v : values) a.put(v);
        return a;
    }

    private static JSONArray devices() {
        JSONArray a = new JSONArray();
        try {
            long[] d = SystemProbe.devices();
            String[] names = SystemProbe.deviceNames();
            if (d.length == 0) return a;
            int count = (int) d[0];
            for (int i = 0; i < count; i++) {
                int base = 1 + i * 4;
                if (base + 3 >= d.length) break;
                JSONObject o = new JSONObject();
                put(o, "type", d[base]);
                put(o, "memoryKnown", d[base + 1] != 0);
                put(o, "freeBytes", d[base + 2]);
                put(o, "totalBytes", d[base + 3]);
                if (names != null && i < names.length) put(o, "name", names[i]);
                a.put(o);
            }
        } catch (Throwable t) {
            put(a, "error", String.valueOf(t));
        }
        return a;
    }

    private static Object parse(String json) {
        if (json == null || json.trim().isEmpty()) return JSONObject.NULL;
        try {
            return new JSONObject(json);
        } catch (Throwable t) {
            return json;
        }
    }

    private static void put(JSONObject o, String key, Object value) {
        try {
            o.put(key, value == null ? JSONObject.NULL : value);
        } catch (JSONException ignored) {
        }
    }

    private static void put(JSONArray a, String key, Object value) {
        // devices() 的 error 分支：JSONArray 不能放 key/value，退化为对象
        try {
            JSONObject o = new JSONObject();
            o.put(key, value);
            a.put(o);
        } catch (JSONException ignored) {
        }
    }
}
