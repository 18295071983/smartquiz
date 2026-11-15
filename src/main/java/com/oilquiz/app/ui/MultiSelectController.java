package com.oilquiz.app.ui;

import android.view.View;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 多选控制器（2026-11-16）。
 *
 * <p><b>为什么补这个</b>：审计里「长按 → 进入多选 → 选中/取消 → 退出」这条链路在 UI 层
 * 完全没有实现（只有数据模型里有 {@code isSelected} 字段），各 Adapter 想加就得各写一遍。
 * 这里把它抽成一个可复用的状态机，配合 {@link UiFx#toggleSelection(View, boolean)} 使用。</p>
 *
 * <p>典型用法（列表 Adapter）：</p>
 * <pre>
 * MultiSelectController sel = new MultiSelectController(count -&gt; toolbar.setTitle("已选 " + count));
 * // 长按进入多选
 * UiFx.longPress(itemView, () -&gt; { sel.enter(); sel.toggle(position); notifyItemChanged(position); });
 * // 点击：多选中就切换，否则走正常点击
 * UiFx.click(itemView, () -&gt; {
 *     if (sel.isActive()) { sel.toggle(position); notifyItemChanged(position); }
 *     else openItem(position);
 * });
 * // 绑定视图时还原选中态
 * UiFx.toggleSelection(itemView, sel.isSelected(position));
 * </pre>
 */
public final class MultiSelectController {

    /** 选中数量变化回调（用于更新标题栏「已选 N 项」） */
    public interface OnCountChanged {
        void onCountChanged(int count);
    }

    /** 多选模式开关回调（进入/退出时切工具栏） */
    public interface OnModeChanged {
        void onModeChanged(boolean active);
    }

    private final Set<Integer> selected = new LinkedHashSet<>();
    private boolean active = false;
    private OnCountChanged countListener;
    private OnModeChanged modeListener;

    public MultiSelectController setOnCountChanged(OnCountChanged listener) {
        this.countListener = listener;
        return this;
    }

    public MultiSelectController setOnModeChanged(OnModeChanged listener) {
        this.modeListener = listener;
        return this;
    }

    /** 是否处于多选模式 */
    public boolean isActive() {
        return active;
    }

    /** 进入多选模式（长按触发） */
    public void enter() {
        if (active) {
            return;
        }
        active = true;
        if (modeListener != null) {
            modeListener.onModeChanged(true);
        }
    }

    /** 退出多选模式并清空选中 */
    public void exit() {
        if (!active) {
            return;
        }
        active = false;
        selected.clear();
        if (modeListener != null) {
            modeListener.onModeChanged(false);
        }
        notifyCount();
    }

    /** 切换某一项选中态；若是第一次进入则自动进入多选模式 */
    public void toggle(int position) {
        enter();
        if (!selected.remove(position)) {
            selected.add(position);
        }
        notifyCount();
        // 全取消时自动退出多选（类微信行为）
        if (selected.isEmpty()) {
            exit();
        }
    }

    /** 某项是否选中 */
    public boolean isSelected(int position) {
        return selected.contains(position);
    }

    /** 当前选中的位置集合（只读快照） */
    public Set<Integer> selectedPositions() {
        return new LinkedHashSet<>(selected);
    }

    /** 选中数量 */
    public int selectedCount() {
        return selected.size();
    }

    /** 全选/全不选 */
    public void selectAll(int itemCount) {
        if (selected.size() == itemCount) {
            selected.clear();
        } else {
            selected.clear();
            for (int i = 0; i < itemCount; i++) {
                selected.add(i);
            }
        }
        notifyCount();
    }

    private void notifyCount() {
        if (countListener != null) {
            countListener.onCountChanged(selected.size());
        }
    }
}
