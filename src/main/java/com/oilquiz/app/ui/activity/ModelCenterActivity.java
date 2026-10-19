package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.inference.InferenceRouter;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ui.base.BaseActivity;
import com.oilquiz.app.ui.modelcenter.ModelRegistrationController;
import com.oilquiz.app.ui.modelcenter.ModelUsageController;
import com.oilquiz.app.theme.ThemeColors;

/**
 * 模型中心：在线模型的「注册」与「用量」合并到同一入口。
 *
 * <p>为什么合并：注册列表此前在 {@code AICenterActivity} 与 {@code ModelSelectorActivity}
 * 各挂了一份（同一个 adapter、同一个对话框、各自实现 7 个回调），而调用量统计
 * （{@code ai/usage} 的 Room 表）从建成那天起没有任何读取方 —— 数在库里，界面上看不到。
 * 结果是想改个模型得进 AI 中心，想知道这个模型花了多少 token 得去别处翻，
 * 而后者根本不存在。</p>
 *
 * <p>本页只做接线：列表与表单行为在 {@link ModelRegistrationController}，
 * 用量聚合与渲染在 {@link ModelUsageController}。</p>
 */
public class ModelCenterActivity extends BaseActivity
        implements ModelRegistrationController.Host {

    private static final int TAB_MODELS = 0;
    private static final int TAB_USAGE = 1;

    private TabLayout tabLayout;
    private View containerModels;
    private View containerUsage;
    private TextView textActiveModel;

    private ModelRegistrationController registrationController;
    private ModelUsageController usageController;
    private InferenceRouter inferenceRouter;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_model_center;
    }

    @Override
    protected void initView() {
        tabLayout = findViewById(R.id.tabLayout);
        containerModels = findViewById(R.id.container_models);
        containerUsage = findViewById(R.id.container_usage);
        textActiveModel = findViewById(R.id.text_active_model);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        tabLayout.addTab(tabLayout.newTab().setText("模型"));
        tabLayout.addTab(tabLayout.newTab().setText("用量"));

        // 相邻职责入口：专用模型（ASR/TTS/NPU）与密钥/服务状态不是「注册在线模型」，
        // 但从这页应一步可达，不必退回旧页面去找
        findViewById(R.id.btn_goto_speech_models).setOnClickListener(v ->
                startActivity(new Intent(this, ModelSelectorActivity.class)));
        findViewById(R.id.btn_goto_api_config).setOnClickListener(v ->
                startActivity(new Intent(this, ApiConfigActivity.class)));
        findViewById(R.id.btn_goto_service_status).setOnClickListener(v ->
                startActivity(new Intent(this, AIServiceStatusActivity.class)));

        usageController = new ModelUsageController(this,
                findViewById(R.id.recycler_usage),
                (TextView) findViewById(R.id.text_usage_overview),
                (TextView) findViewById(R.id.text_usage_caveat),
                (TextView) findViewById(R.id.text_usage_range_title));

        MaterialButton week = findViewById(R.id.btn_range_week);
        MaterialButton month = findViewById(R.id.btn_range_month);
        MaterialButton all = findViewById(R.id.btn_range_all);
        week.setOnClickListener(v -> selectRange(ModelUsageController.Range.WEEK, true));
        month.setOnClickListener(v -> selectRange(ModelUsageController.Range.MONTH, true));
        all.setOnClickListener(v -> selectRange(ModelUsageController.Range.ALL, true));
    }

    @Override
    protected void initData() {
        inferenceRouter = InferenceRouter.getInstance(this);
        registrationController = new ModelRegistrationController(this,
                findViewById(R.id.recycler_models), this);
        registrationController.start();
        // 打开本页时把注册表回灌到旧体系，两页对同一条端点从此同一份名字与模型
        OnlineModelManager.getInstance(this).reconcileApiConfigsFromRegistry();
        // 初始区间只标记不查询：默认停在「模型」Tab，用量数据等切过去再取
        selectRange(ModelUsageController.Range.MONTH, false);
        updateActiveModelDisplay();
    }

    @Override
    protected void initListener() {
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                showTab(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                if (tab.getPosition() == TAB_USAGE) {
                    usageController.load();
                } else {
                    registrationController.refresh();
                }
            }
        });
    }

    private void showTab(int index) {
        boolean usage = index == TAB_USAGE;
        containerModels.setVisibility(usage ? View.GONE : View.VISIBLE);
        containerUsage.setVisibility(usage ? View.VISIBLE : View.GONE);
        if (usage) {
            usageController.load();
        } else {
            registrationController.refresh();
        }
    }

    private void selectRange(ModelUsageController.Range range, boolean load) {
        usageController.setRange(range, load);
        // TextButton 的 selected 态默认无视觉变化，这里用主色直接标出当前区间
        tintRangeButtons(range);
    }

    private void tintRangeButtons(ModelUsageController.Range active) {
        styleRangeButton(findViewById(R.id.btn_range_week), active == ModelUsageController.Range.WEEK);
        styleRangeButton(findViewById(R.id.btn_range_month), active == ModelUsageController.Range.MONTH);
        styleRangeButton(findViewById(R.id.btn_range_all), active == ModelUsageController.Range.ALL);
    }

    private void styleRangeButton(MaterialButton button, boolean active) {
        button.setTextColor(ThemeColors.attr(this, active
                ? R.attr.colorPrimary : R.attr.colorControlTextSecondary));
        button.setSelected(active);
    }

    // ==================== ModelRegistrationController.Host ====================

    @Override
    public void onSwitchToConfig(OnlineModelManager.OnlineModelConfig config) {
        // 与 AI 中心同一条切换口径：走路由而非直接改活跃 id，本地/在线的引擎接管才会一起跟上
        inferenceRouter.switchModel(config.id);
        updateActiveModelDisplay();
        registrationController.refresh();
    }

    @Override
    public void onModelsChanged() {
        updateActiveModelDisplay();
    }

    /**
     * 顶栏「使用中」必须与聊天页同源：读 {@link InferenceRouter}，而不是注册表的
     * {@code getActiveModel().name}。
     *
     * <p>两者不是一回事：注册表给的是用户起的**配置名**（如"小米"），路由给的是**实际调用的模型名**
     * （如"mimo-v2.6-flash"），且 NPU 引擎接管时路由会返回 NPU 侧模型而注册表仍指向在线配置。
     * 之前这里读注册表，于是同一时刻聊天页显示 mimo-v2.6-flash、这页显示"深度求索"，
     * 用户看到的就是"两个页面生效的模型不同步"。</p>
     */
    private void updateActiveModelDisplay() {
        if (textActiveModel == null) return;
        com.oilquiz.app.ai.model.InferenceType type = null;
        try {
            type = inferenceRouter.getCurrentInferenceType();
        } catch (Exception ignored) {
        }
        String modelName;
        try {
            modelName = inferenceRouter.getCurrentModelName();
        } catch (Exception e) {
            modelName = null;
        }
        if (modelName == null || modelName.isEmpty()) {
            // 路由拿不到当前模型时才回落到注册表口径（配置名）
            OnlineModelManager.OnlineModelConfig active =
                    OnlineModelManager.getInstance(this).getActiveModel();
            textActiveModel.setText(active == null ? "未选择模型" : "使用中：" + active.name);
            return;
        }
        textActiveModel.setText("使用中："
                + (type == null ? "" : type.getDisplayName() + " · ") + modelName);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从聊天页/模型选择页切换模型后回到本页必须重读，否则顶栏停在进入页面那一刻的旧值
        if (registrationController != null) registrationController.refresh();
        updateActiveModelDisplay();
    }

    /**
     * 供其它页面跳转进模型中心的入口（取代此前各自 startActivity(ModelSelectorActivity)）。
     */
    public static Intent intent(android.content.Context context) {
        return new Intent(context, ModelCenterActivity.class);
    }

    @Override
    protected void onDestroy() {
        if (registrationController != null) registrationController.stop();
        if (usageController != null) usageController.destroy();
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 默认落「模型」Tab
        showTab(TAB_MODELS);
    }
}
