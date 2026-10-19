package com.oilquiz.app.ui.modelcenter;

import android.app.Activity;
import android.content.Context;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ui.adapter.OnlineModelAdapter;
import com.oilquiz.app.ui.dialog.OnlineModelConfigDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * 「模型」Tab 的控制器：注册列表 + 增删改 + 启用/切换 + 拉取模型列表。
 *
 * <p>抽出来的原因：这段逻辑此前在 {@code AICenterActivity} 和 {@code ModelSelectorActivity}
 * 各写了一份，两个页面跑的是同一个 {@link OnlineModelAdapter} 和同一个
 * {@link OnlineModelConfigDialog}，却要各自实现 7 个监听回调。结果是一边改了行为
 * （比如切换后补查上下文窗口）另一边不会跟着改。本类把它收成一处，
 * Activity 只做薄接线。</p>
 */
public class ModelRegistrationController implements OnlineModelAdapter.OnOnlineModelClickListener {

    /** 宿主页面需要提供的最小配合 */
    public interface Host {
        /** 切换当前使用的模型（由宿主决定走 InferenceRouter 还是直接 setActiveModel） */
        void onSwitchToConfig(OnlineModelManager.OnlineModelConfig config);

        /** 列表/活跃模型变化后刷新宿主上的其它展示（如顶栏当前模型） */
        void onModelsChanged();

        /** 列表为空时宿主的空态视图切换；默认无操作（模型中心自身不需要） */
        default void onEmptyStateChanged(boolean empty) {
        }

        /**
         * 点击的名称不在在线模型注册表里 —— AI 中心/模型选择页据此回落到**本地模型热切换**。
         * 默认无操作：该回落依赖宿主持有的本地推理服务，模型中心不承担本地模型职责。
         */
        default void onUnregisteredModelClick(String modelName) {
        }
    }

    private final Activity activity;
    private final Context context;
    private final OnlineModelManager modelManager;
    private final Host host;
    private final RecyclerView recycler;
    private OnlineModelAdapter adapter;
    private OnlineModelManager.ModelChangeListener changeListener;

    public ModelRegistrationController(Activity activity, RecyclerView recycler, Host host) {
        this.activity = activity;
        this.context = activity;
        this.recycler = recycler;
        this.host = host;
        this.modelManager = OnlineModelManager.getInstance(context);
    }

    /** 页面创建时调用：挂 RecyclerView、注册监听、首次填充 */
    public void start() {
        adapter = new OnlineModelAdapter(context, this);
        recycler.setLayoutManager(new LinearLayoutManager(context));
        recycler.setAdapter(adapter);

        changeListener = new OnlineModelManager.ModelChangeListener() {
            @Override
            public void onModelListChanged() {
                refresh();
                // 启用/禁用只发 list 变更（不发 active 变更），而禁用当前活跃模型会连带停用它，
                // 所以这里也要让宿主刷新"使用中"，否则顶栏停留在旧模型
                host.onModelsChanged();
            }

            @Override
            public void onActiveModelChanged(String activeModelId) {
                refresh();
                host.onModelsChanged();
            }
        };
        modelManager.addListener(changeListener);
        refresh();
    }

    /** 页面销毁时调用：摘掉监听，避免泄漏 Activity */
    public void stop() {
        if (changeListener != null) {
            modelManager.removeListener(changeListener);
            changeListener = null;
        }
    }

    public void refresh() {
        if (adapter == null || recycler == null) return;
        List<OnlineModelManager.OnlineModelConfig> configs = modelManager.getModelList();
        OnlineModelManager.OnlineModelConfig active = modelManager.getActiveModel();
        final String activeId = active != null ? active.id : null;
        final List<OnlineModelManager.OnlineModelConfig> snapshot = new ArrayList<>(configs);

        // RecyclerView 正在算布局或在动画中时直接更新会抛 IllegalStateException；
        // 另外 DiffUtil 可能因引用相同而跳过，需要一次强制刷新（AI 中心原本就这么做，口径保留）
        Runnable update = () -> {
            if (adapter == null) return;
            adapter.updateData(snapshot, activeId);
            adapter.notifyDataSetChanged();
        };
        if (recycler.isComputingLayout() || recycler.isAnimating()) {
            recycler.post(update);
        } else {
            update.run();
        }

        host.onEmptyStateChanged(snapshot.isEmpty());
    }

    private OnlineModelManager.OnlineModelConfig findByName(String name) {
        if (name == null) return null;
        for (OnlineModelManager.OnlineModelConfig config : modelManager.getModelList()) {
            if (name.equals(config.name)) return config;
        }
        return null;
    }

    /** 打开新增/编辑表单；保存后同步 models 表（供用量页 join 名称） */
    private void showConfigDialog(OnlineModelManager.OnlineModelConfig existing) {
        // 编辑走 show(config)：对话框在 show 内部才 inflate 视图，
        // 此时 setExistingConfig 因控件尚未创建而是空操作（AI 中心同样用 show(config)）
        new OnlineModelConfigDialog(context)
                .setSaveListener(new OnlineModelConfigDialog.OnConfigSaveListener() {
                    @Override
                    public void onConfigSaved(OnlineModelManager.OnlineModelConfig saved) {
                        com.oilquiz.app.ai.usage.ModelUsageBridge.upsert(context, saved);
                        refresh();
                        host.onModelsChanged();
                    }

                    @Override
                    public void onConfigCancelled() {
                        // 取消不需要任何副作用：表单不落盘，列表也就无需刷新
                    }
                })
                .show(existing);
    }

    @Override
    public void onModelClick(String modelName) {
        OnlineModelManager.OnlineModelConfig config = findByName(modelName);
        if (config == null) {
            // 名称不在注册表里：交回宿主处理（AI 中心/模型选择页会尝试本地模型热切换），
            // 控制器不认识本地推理服务，不能替它做决定
            host.onUnregisteredModelClick(modelName);
            return;
        }
        host.onSwitchToConfig(config);
    }

    @Override
    public void onAddClick() {
        showConfigDialog(null);
    }

    @Override
    public void onEditClick(String modelName) {
        OnlineModelManager.OnlineModelConfig config = findByName(modelName);
        if (config == null) {
            Toast.makeText(context, "未找到该模型配置", Toast.LENGTH_SHORT).show();
            return;
        }
        showConfigDialog(config);
    }

    @Override
    public void onDeleteClick(String modelName) {
        final OnlineModelManager.OnlineModelConfig config = findByName(modelName);
        if (config == null) return;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                .setTitle("删除模型配置")
                .setMessage("确定删除「" + modelName + "」？历史用量记录会保留，"
                        + "但其模型名此后显示为「已移除的模型」。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> {
                    // 成对的密钥条目一并删：否则服务商密钥页会残留一条指向已不存在配置的记录
                    try {
                        com.oilquiz.app.ai.util.APIKeyManager manager =
                                com.oilquiz.app.ai.util.APIKeyManager.getInstance(context);
                        if (manager.getAPIConfigById(config.id) != null) {
                            manager.deleteAPIConfig(config.id);
                        }
                    } catch (Exception ignored) {
                    }
                    modelManager.removeModel(config.id);
                    // 注册表删了，models 表里的名字映射也要跟着收，否则用量页挂着僵尸名
                    com.oilquiz.app.ai.usage.ModelUsageBridge.syncFromRegistry(context);
                    refresh();
                    host.onModelsChanged();
                })
                .show();
    }

    @Override
    public void onEnableToggle(String modelName, boolean enabled) {
        OnlineModelManager.OnlineModelConfig config = findByName(modelName);
        if (config == null) return;
        config.enabled = enabled;
        modelManager.save();

        // 与服务商密钥页同步启用态（两处状态必须一起走，否则密钥页显示不一致）
        try {
            com.oilquiz.app.ai.util.APIKeyManager manager =
                    com.oilquiz.app.ai.util.APIKeyManager.getInstance(context);
            com.oilquiz.app.ai.model.APIConfig apiConfig = manager.getAPIConfigById(config.id);
            if (apiConfig != null && apiConfig.isActive() != enabled) {
                apiConfig.setActive(enabled);
                manager.saveAPIConfig(apiConfig);
            }
        } catch (Exception ignored) {
        }

        // 禁用的是当前活跃模型时，必须停用，否则推理链还在用一个已被禁用的配置
        if (!enabled) {
            OnlineModelManager.OnlineModelConfig active = modelManager.getActiveModel();
            if (active != null && active.id.equals(config.id)) {
                modelManager.stopActiveModel();
            }
        }

        refresh();
        host.onModelsChanged();
    }

    @Override
    public void onFetchModelsClick(String modelName) {
        OnlineModelManager.OnlineModelConfig config = findByName(modelName);
        if (config == null) {
            Toast.makeText(context, "未找到该模型配置", Toast.LENGTH_SHORT).show();
            return;
        }
        if (config.apiKey == null || config.apiKey.isEmpty()) {
            Toast.makeText(context, "请先填写 API 密钥", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(context, "正在获取模型列表...", Toast.LENGTH_SHORT).show();
        final String targetId = config.id;
        com.oilquiz.app.ai.service.ModelListFetcher
                .getInstance(context)
                .fetchModels(config.apiUrl, config.apiKey)
                .thenAccept(models -> activity.runOnUiThread(() -> {
                    if (models == null || models.isEmpty()) {
                        Toast.makeText(context, "未获取到模型列表", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    try {
                        org.json.JSONArray json = new org.json.JSONArray();
                        for (com.oilquiz.app.ai.model.ApiModel model : models) {
                            json.put(com.oilquiz.app.ai.service.ModelListFetcher
                                    .modelToCacheJson(model));
                        }
                        modelManager.saveCachedModels(targetId, json.toString());
                        Toast.makeText(context, "获取成功，共 " + models.size() + " 个模型",
                                Toast.LENGTH_SHORT).show();
                        refresh();
                        host.onModelsChanged();
                    } catch (Exception e) {
                        Toast.makeText(context, "保存模型列表失败: " + e.getMessage(),
                                Toast.LENGTH_SHORT).show();
                    }
                }))
                .exceptionally(e -> {
                    activity.runOnUiThread(() -> Toast.makeText(context,
                            "获取失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                    return null;
                });
    }

    @Override
    public void onModelSelected(String modelName, String selectedModel) {
        final OnlineModelManager.OnlineModelConfig config = findByName(modelName);
        if (config == null) {
            Toast.makeText(context, "未找到该模型配置", Toast.LENGTH_SHORT).show();
            return;
        }
        modelManager.saveSelectedModel(config.id, selectedModel);
        // 换模型后异步补查该模型的真实上下文窗口（带持久化缓存，命中则零额外请求）：
        // Agent 的历史压缩阈值与用量页的窗口标注都吃这个值
        try {
            java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return com.oilquiz.app.ai.service.OnlineInferenceService
                            .getInstance(context).queryContextWindowFromAPI(config);
                } catch (Throwable t) {
                    return null;
                }
            }).thenAccept(window -> {
                if (window != null && window > 0) {
                    config.contextWindow = window;
                    config.contextWindowFromApi = true;
                    modelManager.updateModelConfig(config);
                    activity.runOnUiThread(this::refresh);
                }
            });
        } catch (Throwable ignored) {
            // 补查失败静默，保留配置表推断值
        }
        host.onSwitchToConfig(config);
        Toast.makeText(context, "已切换到 " + selectedModel, Toast.LENGTH_SHORT).show();
        refresh();
    }
}
