package com.oilquiz.app.ai.service;

import com.oilquiz.app.ai.model.ApiModel;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@code cachedModelsJson} 的写出与读回必须互为逆运算。
 *
 * <p><b>为什么专门钉这一条</b>：这份缓存是思考强度档位、服务端输出上限、输入模态这些能力数据的
 * <b>唯一</b>持久化载体，读取侧（档位选择器、请求注入、max_tokens 上限、"是否支持图片"判定）
 * 全部依赖它。在线模型配置对话框此前自带一份只认 {@code id/name/contextLength} 的解析，
 * 而保存时又用这份残缺列表重新序列化去<b>覆盖</b> {@code cachedModelsJson} ——
 * 于是"打开编辑 → 点一次保存"就把档位与输出上限抹掉了，界面上毫无痕迹，
 * 用户只看到"思考强度没有获取到"。往返不丢字段是这条链的最低要求。</p>
 *
 * <p>纯静态方法测试，不需要 Android 上下文（org.json 的真实实现已由 testImplementation 引入）。</p>
 */
public class ModelCacheRoundTripTest {

    private static ApiModel fullModel() {
        ApiModel model = new ApiModel();
        model.id = "deepseek-reasoner";
        model.displayName = "DeepSeek-R1";
        model.contextLength = 128000;
        model.contextLengthFromApi = true;
        model.thinkingEffortLevels = new ArrayList<>(Arrays.asList("high", "medium", "low"));
        model.thinkingEffortDefault = "high";
        model.maxOutputTokens = 64000;
        model.inputModalities = new ArrayList<>(Arrays.asList("text", "image"));
        return model;
    }

    @Test
    public void roundTripKeepsEveryPersistedField() {
        ApiModel src = fullModel();
        src.ownedBy = "deepseek";

        ApiModel back = ModelListFetcher.modelFromCacheJson(ModelListFetcher.modelToCacheJson(src));

        assertEquals(src.id, back.id);
        assertEquals(src.displayName, back.displayName);
        // 拥有方：聚合网关里 id 会撞名，owned_by 是"这条是谁的"的唯一依据，不能丢
        assertEquals("deepseek", back.ownedBy);
        assertEquals("上下文窗口", src.contextLength, back.contextLength);
        assertEquals("窗口可信度", src.contextLengthFromApi, back.contextLengthFromApi);
        assertEquals("思考档位", src.thinkingEffortLevels, back.thinkingEffortLevels);
        assertEquals("默认档位", src.thinkingEffortDefault, back.thinkingEffortDefault);
        assertEquals("输出上限", src.maxOutputTokens, back.maxOutputTokens);
        assertEquals("输入模态", src.inputModalities, back.inputModalities);
        // 读取侧真正关心的两个派生结论
        assertTrue("档位选择器依据", back.hasThinkingEffortLevels());
        assertTrue("多模态判定依据", back.supportsImageInput());
    }

    @Test
    public void reSerializingAReadBackModelLosesNothing() {
        // 模拟对话框的实际动作：读缓存 → 改别处 → 用同一份列表重新序列化覆盖缓存。
        // 第二次的序列化结果必须与第一次逐字段等价（不比对 JSON 字符串本身：
        // org.json 的键序不受保证）。
        ApiModel first = fullModel();
        JSONObject firstJson = ModelListFetcher.modelToCacheJson(first);

        ApiModel back = ModelListFetcher.modelFromCacheJson(firstJson);
        JSONObject secondJson = ModelListFetcher.modelToCacheJson(back);

        assertEquals(firstJson.optString("id"), secondJson.optString("id"));
        assertEquals(firstJson.optString("name"), secondJson.optString("name"));
        assertEquals(firstJson.optInt("contextLength"), secondJson.optInt("contextLength"));
        assertEquals(firstJson.optBoolean("contextLengthFromApi"),
                secondJson.optBoolean("contextLengthFromApi"));
        assertEquals(String.valueOf(firstJson.optJSONArray("thinkingEffortLevels")),
                String.valueOf(secondJson.optJSONArray("thinkingEffortLevels")));
        assertEquals(firstJson.optString("thinkingEffortDefault"),
                secondJson.optString("thinkingEffortDefault"));
        assertEquals(firstJson.optInt("maxOutputTokens"), secondJson.optInt("maxOutputTokens"));
        assertEquals(String.valueOf(firstJson.optJSONArray("inputModalities")),
                String.valueOf(secondJson.optJSONArray("inputModalities")));
    }

    @Test
    public void sparseEntryReadsBackAsUnDeclaredRatherThanZeroFilled() throws Exception {
        // 只有 id 的旧缓存行：档位/上限/模态必须回落成"未声明"（空列表 + 0 + null），
        // 而不是被推断出一个看起来像真值的假数据
        JSONObject onlyId = new JSONObject();
        onlyId.put("id", "glm-4-flash");

        ApiModel back = ModelListFetcher.modelFromCacheJson(onlyId);

        assertEquals("glm-4-flash", back.id);
        // 未声明拥有方时保持 null，不填 "unknown" 之类的假来源（界面会显示"来源 unknown"）
        assertNull(back.ownedBy);
        assertEquals("展示名回落为 id", "glm-4-flash", back.displayName);
        assertEquals(0, back.contextLength);
        assertFalse(back.contextLengthFromApi);
        assertFalse("未声明即无档位", back.hasThinkingEffortLevels());
        assertFalse("未声明即不支持图片", back.supportsImageInput());
        assertEquals(0, back.maxOutputTokens);
    }

    @Test
    public void displayNameEqualingIdIsStillRestored() throws Exception {
        // 很多服务商的 /models 里 name 就等于 id。取数侧对这种情况返回 null（"没有额外的展示名"），
        // 但反序列化必须把 displayName 还原成 id 本身，否则列表那一行会显示成空白
        org.json.JSONArray cache = new org.json.JSONArray();
        ApiModel model = new ApiModel();
        model.id = "gpt-4o-mini";
        model.displayName = "gpt-4o-mini";
        cache.put(ModelListFetcher.modelToCacheJson(model));

        ApiModel back = ModelListFetcher.modelFromCacheJson(cache.getJSONObject(0));

        assertEquals("gpt-4o-mini", back.id);
        assertEquals("gpt-4o-mini", back.displayName);
        assertEquals("name 与 id 相同时取数侧不额外造展示名", null,
                ModelListFetcher.findDisplayNameInCache(cache.toString(), "gpt-4o-mini"));
    }
}
