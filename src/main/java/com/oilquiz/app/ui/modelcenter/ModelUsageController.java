package com.oilquiz.app.ui.modelcenter;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.usage.ModelUsageBridge;
import com.oilquiz.app.ai.usage.db.DatabaseManager;
import com.oilquiz.app.ai.usage.db.dao.UsageDao;
import com.oilquiz.app.ai.usage.db.entity.ModelEntity;
import com.oilquiz.app.theme.ThemeColors;
import com.oilquiz.app.util.AILogger;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 「用量」Tab 的控制器：把 {@code api_usage_log} 聚合结果渲染成概览 + 按模型明细。
 *
 * <p>这是使用侧此前完全缺失的那一半：写入路径一直在跑（改造后还补上了三条原本整类漏记的路径），
 * 但全项目没有任何一处读过 {@code UsageDao} 的聚合查询 —— 表里有数，界面上零呈现。</p>
 *
 * <p>只统计 token，不显示金额：本应用不加载价格配置表，成本列有意恒为 0，
 * 显示一个恒等于 0 的「花费」比不显示更坏。</p>
 */
public class ModelUsageController {

    private static final String TAG = "ModelUsageController";
    /** 与 ModelUsageRecorder 落库时的 userId 保持一致，否则查不到行 */
    private static final String LOCAL_USER = "local";

    /** 统计区间 */
    public enum Range { WEEK, MONTH, ALL }

    private final Context context;
    private final RecyclerView recycler;
    private final TextView overviewText;
    private final TextView caveatText;
    private final TextView rangeTitleText;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final RowsAdapter rowsAdapter = new RowsAdapter();

    private Range range = Range.MONTH;

    public ModelUsageController(Context context, RecyclerView recycler,
                                TextView overviewText, TextView caveatText,
                                TextView rangeTitleText) {
        this.context = context;
        this.recycler = recycler;
        this.overviewText = overviewText;
        this.caveatText = caveatText;
        this.rangeTitleText = rangeTitleText;

        recycler.setLayoutManager(new LinearLayoutManager(context));
        recycler.setAdapter(rowsAdapter);
    }

    public void setRange(Range range) {
        setRange(range, true);
    }

    /**
     * @param load 是否立即查询。页面初始化时用户可能根本不会切到「用量」Tab，
     *             这时不该为一次不看的统计去跑数据库。
     */
    public void setRange(Range range, boolean load) {
        this.range = range;
        if (load) {
            load();
        }
    }

    public Range getRange() {
        return range;
    }

    /** 切到用量 Tab 时调用：先保证 models 表与注册表一致，再查聚合 */
    public void load() {
        rowsAdapter.setLoading(true);
        executor.execute(() -> {
            Snapshot snapshot;
            try {
                // 打开页面时做一次全量对齐：注册表新增/删除的模型即时反映到用量页的命名上。
                // 必须用 Blocking 版：本任务下面就要 query()，异步版还没落库，
                // 首屏会读到对齐前的旧名（"先对齐再查询"的顺序是这段代码的前提）。
                ModelUsageBridge.syncFromRegistryBlocking(context);
                snapshot = query();
            } catch (Throwable t) {
                AILogger.w(TAG, "用量查询失败: " + t.getMessage());
                snapshot = Snapshot.empty();
            }
            final Snapshot result = snapshot;
            mainHandler.post(() -> render(result));
        });
    }

    private Snapshot query() {
        UsageDao dao = DatabaseManager.getInstance(context).usageDao();
        long start = rangeStartMillis();
        List<UsageDao.ModelUsageRow> rows = start <= 0
                ? dao.getModelUsageDetailSince(LOCAL_USER, 0L)
                : dao.getModelUsageDetailSince(LOCAL_USER, start);
        if (rows == null) rows = new ArrayList<>();

        Snapshot snapshot = new Snapshot();
        snapshot.rows = rows;
        Map<String, ModelEntity> index = ModelUsageBridge.indexByModelId(context);
        for (UsageDao.ModelUsageRow row : rows) {
            snapshot.totalTokens += row.getTotalTokens();
            snapshot.totalCalls += row.getCalls();
            snapshot.totalFailures += row.getFailures();
            snapshot.durationSum += row.getDurationSum();
            snapshot.durationCalls += row.getDurationCalls();
            snapshot.totalInput += row.getInputTokens();
            snapshot.totalOutput += row.getOutputTokens();
            snapshot.totalCache += row.getCacheHitTokens();
            ModelEntity entity = row.getModelId() == null ? null : index.get(row.getModelId());
            snapshot.labels.add(entity != null ? entity.getDisplayName() : null);
            snapshot.found.add(entity != null);
        }
        return snapshot;
    }

    private long rangeStartMillis() {
        long now = System.currentTimeMillis();
        switch (range) {
            case WEEK:
                return now - 7L * 24 * 60 * 60 * 1000;
            case MONTH:
                return now - 30L * 24 * 60 * 60 * 1000;
            case ALL:
            default:
                return 0L;
        }
    }

    private void render(Snapshot snapshot) {
        overviewText.setText(buildOverview(snapshot));
        rangeTitleText.setText(rangeTitle() + " · " + snapshot.rows.size() + " 个模型");
        caveatText.setText("说明：仅统计本机记录的调用；失败为服务商返回错误/未完成的调用；"
                + "耗时仅统计已计时的调用（文本对话路径：普通/工具流/Responses），"
                + "embedding/rerank/图片生成未计时；金额不在本应用呈现（不加载价格表）。"
                + "单次耗时未采集，故不提供平均时延。非会话类调用（知识库/OCR/题目修复等）"
                + "与被记录的会话共用同一张表，因此此处不按会话拆分。");
        rowsAdapter.submit(snapshot);
    }

    private String rangeTitle() {
        switch (range) {
            case WEEK: return "近 7 天";
            case MONTH: return "近 30 天";
            default: return "全部记录";
        }
    }

    private String buildOverview(Snapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        long successCalls = Math.max(0, snapshot.totalCalls - snapshot.totalFailures);
        sb.append(rangeTitle()).append("共 ").append(snapshot.totalCalls).append(" 次调用");
        if (snapshot.totalFailures > 0) {
            // 失败行是零 token 记录：不拆开的话"N 次调用"会把失败的也算成正常调用
            sb.append("（成功 ").append(successCalls)
                    .append(" · 失败 ").append(snapshot.totalFailures).append("）");
        }
        sb.append("，").append(group(snapshot.totalTokens)).append(" tokens");
        sb.append("\n输入 ").append(group(snapshot.totalInput))
                .append(" · 输出 ").append(group(snapshot.totalOutput));
        if (snapshot.totalCache > 0) {
            sb.append(" · 缓存命中 ").append(group(snapshot.totalCache));
        }
        // 失败率是"突然不能用"的最直接指标；失败数已并入首行，这里只补比例与耗时
        if (snapshot.totalFailures > 0 && snapshot.totalCalls > 0) {
            sb.append((char) 10).append("失败率 ")
                    .append(String.format(Locale.getDefault(), "%.1f%%",
                            100.0 * snapshot.totalFailures / snapshot.totalCalls));
        }
        if (snapshot.durationCalls > 0) {
            sb.append(" · 平均耗时 ").append(snapshot.durationSum / snapshot.durationCalls)
                    .append(" ms（").append(snapshot.durationCalls).append(" 次已计时）");
        }
        return sb.toString();
    }

    /** 千分位分组，读数比裸数字快 */
    private static String group(long value) {
        return String.format(Locale.getDefault(), "%,d", value);
    }

    public void destroy() {
        executor.shutdownNow();
    }

    /** 一次查询的结果快照（后台线程构造，主线程只读） */
    private static class Snapshot {
        List<UsageDao.ModelUsageRow> rows = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        List<Boolean> found = new ArrayList<>();
        long totalTokens;
        long totalCalls;
        long totalInput;
        long totalOutput;
        long totalCache;
        long totalFailures;
        long durationSum;
        long durationCalls;

        static Snapshot empty() {
            return new Snapshot();
        }
    }

    /** 明细行适配器 */
    private class RowsAdapter extends RecyclerView.Adapter<RowsAdapter.RowHolder> {
        private final Snapshot data = new Snapshot();
        private boolean loading = false;

        void setLoading(boolean loading) {
            this.loading = loading;
        }

        void submit(Snapshot snapshot) {
            data.rows.clear();
            data.labels.clear();
            data.found.clear();
            if (snapshot != null) {
                data.rows.addAll(snapshot.rows);
                data.labels.addAll(snapshot.labels);
                data.found.addAll(snapshot.found);
                data.totalTokens = snapshot.totalTokens;
                data.totalCalls = snapshot.totalCalls;
            }
            loading = false;
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return data.rows.size();
        }

        @Override
        public RowHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_model_usage, parent, false);
            return new RowHolder(view);
        }

        @Override
        public void onBindViewHolder(RowHolder holder, int position) {
            UsageDao.ModelUsageRow row = data.rows.get(position);
            boolean hasLabel = position < data.labels.size()
                    && data.labels.get(position) != null
                    && position < data.found.size() && data.found.get(position);
            String registeredName = hasLabel ? data.labels.get(position) : null;

            holder.model.setText(registeredName != null
                    ? registeredName + "（" + row.getModelId() + "）"
                    : (row.getModelId() == null ? "未知模型" : row.getModelId()));

            // 已移除的模型用提示色压暗：它不再是可切换的对象，只是历史留痕
            holder.model.setTextColor(ThemeColors.attr(context, registeredName != null
                    ? R.attr.colorControlText : R.attr.colorControlTextHint));

            int rowSuccess = Math.max(0, row.getCalls() - row.getFailures());
            holder.calls.setText(row.getFailures() > 0
                    ? "共 " + row.getCalls() + " 次 · 成功 " + rowSuccess + " · 失败 " + row.getFailures()
                    : row.getCalls() + " 次");
            holder.tokens.setText("共 " + group(row.getTotalTokens()) + " tokens"
                    + " · 入 " + group(row.getInputTokens())
                    + " · 出 " + group(row.getOutputTokens())
                    + (row.getCacheHitTokens() > 0
                        ? " · 缓存命中 " + group(row.getCacheHitTokens()) : ""));
            holder.meta.setText(buildMeta(row, registeredName));
        }

        private String buildMeta(UsageDao.ModelUsageRow row, String registeredName) {
            StringBuilder sb = new StringBuilder();
            sb.append("服务商 ").append(row.getProviderId() == null || row.getProviderId().isEmpty()
                    ? "未知" : row.getProviderId());
            if (registeredName == null) {
                sb.append(" · 已移除的模型（历史调用仍计入）");
            }
            if (row.getAvgDurationMs() > 0) {
                sb.append(" · 平均耗时 ").append(row.getAvgDurationMs()).append(" ms");
            }
            if (row.getLastCallTime() > 0) {
                sb.append(" · 最近 ").append(DATE_FORMAT.format(new Date(row.getLastCallTime())));
            }
            return sb.toString();
        }

        class RowHolder extends RecyclerView.ViewHolder {
            final TextView model;
            final TextView calls;
            final TextView tokens;
            final TextView meta;

            RowHolder(View itemView) {
                super(itemView);
                model = itemView.findViewById(R.id.text_usage_model);
                calls = itemView.findViewById(R.id.text_usage_calls);
                tokens = itemView.findViewById(R.id.text_usage_tokens);
                meta = itemView.findViewById(R.id.text_usage_meta);
            }
        }
    }

    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
}
