package com.oilquiz.app.ai.agent;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.List;

/**
 * {@code tool_guide_flow.json} 的只读装载器。
 *
 * <p>查找顺序照 {@code ProviderConfigManager}（providers.json）的口径，但**只读不写**：
 * 本阶段不做 SharedPreferences 双保险，也不在运行时回写文件——落盘属于显式选择，
 * 装载一个显示名词典不该带写盘副作用。</p>
 * <ol>
 *   <li>{@code filesDir/tool_guide_flow.json}（外部覆盖档，真机可直接推一份进去改词条）</li>
 *   <li>{@code assets/tool_guide_flow.json}（随包默认档）</li>
 * </ol>
 *
 * <p><b>失败即退回现状，不降级成半套配置</b>：文件缺失、Gson 解析失败、或
 * {@link ToolGuideFlowValidator} 报了任何 ERROR，整份配置就当没有——
 * {@link #terms(Context)} 返回 {@link ToolGuideTerms#EMPTY}，引导界面维持今天的行为
 * （动态兜底继续露原始值）。宁可少改一处显示，也不要让一份写坏的 JSON 影响引导可用性。</p>
 *
 * <p>结果常驻内存缓存；推新文件后调 {@link #invalidate()} 生效。缓存是进程级的，
 * 不做多 Context 区分（本类只应拿 applicationContext）。</p>
 */
public final class ToolGuideConfigLoader {

    private static final String TAG = "ToolGuideFlow";
    private static final String FILE_NAME = "tool_guide_flow.json";

    /** UTF-8 显式解码：靠平台默认字符集读中文会在非 UTF-8 默认值的机器上读出乱码而不是报错 */
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private static volatile ToolGuideFlowConfig cachedConfig;
    private static volatile ToolGuideTerms cachedTerms = ToolGuideTerms.EMPTY;
    private static volatile boolean loaded;

    private ToolGuideConfigLoader() {
    }

    /** 解析并校验通过的配置；没有可用配置时返回 null。 */
    public static ToolGuideFlowConfig config(Context context) {
        ensureLoaded(context);
        return cachedConfig;
    }

    /** 引导界面用的中文词典；没有可用配置时是空词典（所有查找返回 null → 保持原文）。 */
    public static ToolGuideTerms terms(Context context) {
        ensureLoaded(context);
        return cachedTerms;
    }

    /** 丢弃缓存，下次读取重新按 filesDir → assets 装载（外部推新文件后调用）。 */
    public static synchronized void invalidate() {
        loaded = false;
        cachedConfig = null;
        cachedTerms = ToolGuideTerms.EMPTY;
    }

    private static void ensureLoaded(Context context) {
        if (loaded) {
            return;
        }
        synchronized (ToolGuideConfigLoader.class) {
            if (loaded) {
                return;
            }
            try {
                loadOnce(context);
            } catch (Throwable t) {
                // 兜住一切：装载失败只意味着"显示名维持现状"，不能把引导本身带崩
                Log.w(TAG, "引导配置装载失败，保持原始字段名: " + t);
                cachedConfig = null;
                cachedTerms = ToolGuideTerms.EMPTY;
            }
            loaded = true;
        }
    }

    private static void loadOnce(Context context) {
        String json = readOverride(context);
        String source = "filesDir/" + FILE_NAME;
        if (json == null) {
            json = readAsset(context);
            source = "assets/" + FILE_NAME;
        }
        if (json == null) {
            Log.i(TAG, "没有引导配置文件（filesDir 与 assets 都没有 " + FILE_NAME + "），显示名维持原始字段");
            cachedConfig = null;
            cachedTerms = ToolGuideTerms.EMPTY;
            return;
        }
        ToolGuideFlowConfig config = new Gson().fromJson(json, ToolGuideFlowConfig.class);
        List<ToolGuideFlowValidator.Issue> issues = ToolGuideFlowValidator.validate(config);
        List<ToolGuideFlowValidator.Issue> errors = ToolGuideFlowValidator.errors(issues);
        if (!errors.isEmpty()) {
            Log.w(TAG, "引导配置有 " + errors.size() + " 个 ERROR，整份拒绝（来源 " + source + "）:\n"
                    + ToolGuideFlowValidator.render(errors));
            cachedConfig = null;
            cachedTerms = ToolGuideTerms.EMPTY;
            return;
        }
        cachedConfig = config;
        cachedTerms = ToolGuideTerms.fromConfig(config.terms);
        Log.i(TAG, "引导配置已装载（" + source + "）version=" + config.version
                + ", terms: params=" + cachedTerms.paramTermCount()
                + ", values=" + cachedTerms.valueTermCount()
                + ", tools=" + (config.tools == null ? 0 : config.tools.size())
                + ", composites=" + (config.composites == null ? 0 : config.composites.size())
                + ", warn=" + ToolGuideFlowValidator.warnings(issues).size());
    }

    /** 外部覆盖档；不存在或读失败返回 null（继续走随包档）。 */
    private static String readOverride(Context context) {
        if (context == null) {
            return null;
        }
        try {
            File file = new File(context.getFilesDir(), FILE_NAME);
            if (!file.isFile()) {
                return null;
            }
            return readAll(new java.io.FileInputStream(file));
        } catch (Throwable t) {
            Log.w(TAG, "读 filesDir 覆盖档失败，改用随包档: " + t);
            return null;
        }
    }

    private static String readAsset(Context context) {
        if (context == null) {
            return null;
        }
        try {
            return readAll(context.getAssets().open(FILE_NAME));
        } catch (Throwable t) {
            Log.i(TAG, "读 assets 引导配置失败: " + t);
            return null;
        }
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), UTF_8);
        } finally {
            try {
                in.close();
            } catch (java.io.IOException ignored) {
                // 关不上不影响已读到的内容
            }
        }
    }
}
