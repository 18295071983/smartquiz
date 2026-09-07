package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.content.res.ColorStateList;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.oilquiz.app.R;

/**
 * 快捷工具 chip 模块：统一管理 AI 对话界面快捷区的工具入口 chip。
 *
 * 职责：
 * 1. 绑定 XML 静态工具 chip 的点击事件（查天气/搜索/数据库/文件/定位/应用/聚合工具包/清空对话）；
 * 2. 动态构建快捷 chip（聚合方案：出行准备 / 学习查询 / 网页研究）；
 * 3. 集中维护"快捷工具 → 工具引导"的映射，新增加入口只需在此登记，宿主界面代码不被污染。
 *
 * 渲染与执行回调给宿主（如 AIChatActivity）完成：本模块不持有对话框/执行逻辑，
 * 只负责 chip 的构建与点击路由。颜色与抽屉静态快捷 chip 统一（tertiary_container 系）。
 */
public class QuickToolChipModule {

    /** 宿主回调：chip 点击后的动作路由 */
    public interface Callback {
        /** 弹出工具引导（toolId 为工具注册名，如 ai_weather / app_toolkit） */
        void onToolGuide(String toolId);

        /** 弹出聚合方案引导（flowId 如 go_out / study / research） */
        void onCompositeGuide(String flowId);

        /** 清空当前对话 */
        void onClearChat();
    }

    private final Context context;
    private final Callback callback;

    public QuickToolChipModule(Context context, Callback callback) {
        this.context = context;
        this.callback = callback;
    }

    /**
     * 绑定 XML 静态工具 chip 的点击事件。任一 chip 可为 null（未找到时跳过）。
     *
     * @param chipWeather  查天气 → ai_weather
     * @param chipSearch   搜索   → network_search
     * @param chipDatabase 数据库 → database
     * @param chipFile     文件   → file_reader
     * @param chipLocation 定位   → location
     * @param chipApp      应用   → app_operation
     * @param chipCalc     聚合工具包 → app_toolkit
     * @param chipClear    清空对话
     */
    public void bindStaticToolChips(Chip chipWeather, Chip chipSearch, Chip chipDatabase,
                                    Chip chipFile, Chip chipLocation, Chip chipApp,
                                    Chip chipCalc, Chip chipClear) {
        bindToolChip(chipWeather, "ai_weather");
        bindToolChip(chipSearch, "network_search");
        bindToolChip(chipDatabase, "database");
        bindToolChip(chipFile, "file_reader");
        bindToolChip(chipLocation, "location");
        bindToolChip(chipApp, "app_operation");
        bindToolChip(chipCalc, "app_toolkit");   // 聚合工具包
        if (chipClear != null) {
            chipClear.setOnClickListener(v -> callback.onClearChat());
        }
    }

    /** 为单个工具 chip 绑定点击 → 工具引导 */
    private void bindToolChip(Chip chip, final String toolId) {
        if (chip != null) {
            chip.setOnClickListener(v -> callback.onToolGuide(toolId));
        }
    }

    /**
     * 向 ChipGroup 追加动态快捷 chip（聚合方案）。
     * 颜色与抽屉静态快捷 chip 统一（tertiary_container / on_tertiary_container）。
     */
    public void addQuickChips(ChipGroup group) {
        if (group == null) {
            return;
        }
        // 聚合方案入口（更多工具能力统一收进"聚合工具包"，避免快捷区堆叠）
        addChip(group, "🚗 出行准备", () -> callback.onCompositeGuide("go_out"));
        addChip(group, "📚 学习查询", () -> callback.onCompositeGuide("study"));
        addChip(group, "🔍 网页研究", () -> callback.onCompositeGuide("research"));
    }

    /** 创建并追加一个快捷 chip */
    private Chip addChip(ChipGroup group, String label, final Runnable action) {
        Chip chip = new Chip(context);
        chip.setText(label);
        chip.setChipBackgroundColor(ColorStateList.valueOf(
                context.getColor(R.color.tertiary_container)));
        chip.setTextColor(context.getColor(R.color.on_tertiary_container));
        chip.setChipStrokeWidth(0f);
        chip.setClickable(true);
        chip.setOnClickListener(v -> action.run());
        group.addView(chip);
        return chip;
    }
}
