package com.oilquiz.app.infra;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.oilquiz.app.R;
import com.oilquiz.app.theme.ThemeColors;
import com.oilquiz.app.theme.Tokens;
import com.oilquiz.app.ui.MultiSelectController;
import com.oilquiz.app.ui.UiDecor;
import com.oilquiz.app.ui.UiFx;

import java.util.ArrayList;
import java.util.List;

/**
 * 列表与加载态工具包（2026-11-16）：补齐审计里缺失的 6 项能力。
 *
 * <ol>
 *   <li><b>ItemDecoration</b>（此前 0 用）—— 分隔线由装饰器统一画，不再靠 margin 硬凑；</li>
 *   <li><b>ConcatAdapter</b>（0 用）—— 三种 section 拼一个列表；</li>
 *   <li><b>多选模式</b>（UI 层此前没有）—— 长按进入、点选切换、全取消自动退出；</li>
 *   <li><b>骨架屏</b>（此前没有）—— 加载占位 + 呼吸动画；</li>
 *   <li><b>FloatingActionButton</b>（0 用）—— 悬浮主操作；</li>
 *   <li><b>ViewStub</b>（0 用）—— 见 {@link com.oilquiz.app.ui.UiDecor#inflateOnce}。</li>
 * </ol>
 */
public class ListKitGalleryActivity extends AppCompatActivity {

    private RecyclerView list;
    private TextView headerLabel;
    private MultiSelectController selection;
    private final List<String> items = new ArrayList<>();
    private RowAdapter rowAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ThemeColors.get(this, R.color.background));
        int pad = Tokens.space(this, Tokens.SPACE_XL);
        root.setPadding(pad, pad, pad, 0);

        TextView title = new TextView(this);
        title.setText("列表工具包 · ItemDecoration / ConcatAdapter / 多选 / 骨架屏 / FAB / ViewStub");
        Tokens.applyText(title, Tokens.TEXT_SUBTITLE, Tokens.Weight.SEMIBOLD,
                ThemeColors.get(this, R.color.text_primary));
        root.addView(title);

        headerLabel = new TextView(this);
        headerLabel.setText("长按任意行进入多选；已选 0 项");
        Tokens.applyText(headerLabel, Tokens.TEXT_FOOTNOTE, Tokens.Weight.REGULAR,
                ThemeColors.get(this, R.color.text_secondary));
        LinearLayout.LayoutParams hlLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlLp.setMargins(0, Tokens.space(this, Tokens.SPACE_SM), 0,
                Tokens.space(this, Tokens.SPACE_MD));
        headerLabel.setLayoutParams(hlLp);
        root.addView(headerLabel);

        for (int i = 1; i <= 12; i++) {
            items.add("列表项 " + i);
        }

        list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setBackgroundColor(ThemeColors.get(this, R.color.background));

        // ① ConcatAdapter：头部 section + 数据 section 拼成一个列表
        rowAdapter = new RowAdapter();
        ConcatAdapter concat = new ConcatAdapter(new SectionAdapter("SECTIONS · ConcatAdapter 拼装"),
                rowAdapter);
        list.setAdapter(concat);
        // ② ItemDecoration：分隔线（左侧缩进与文字对齐）
        list.addItemDecoration(UiDecor.divider(this, Tokens.space(this, Tokens.SPACE_LG)));
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ③ 骨架屏：加载占位（2 秒后换成真实列表提示）
        final LinearLayout skeletonBox = new LinearLayout(this);
        skeletonBox.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < 3; i++) {
            View sk = UiDecor.skeleton(this, Tokens.space(this, Tokens.SPACE_3XL));
            LinearLayout.LayoutParams skLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Tokens.space(this, Tokens.SPACE_3XL));
            skLp.setMargins(0, 0, 0, Tokens.space(this, Tokens.SPACE_MD));
            sk.setLayoutParams(skLp);
            UiDecor.startSkeleton(sk);
            skeletonBox.addView(sk);
        }
        root.addView(skeletonBox);
        list.postDelayed(() -> {
            skeletonBox.setVisibility(View.GONE);
            headerLabel.setText("长按任意行进入多选；骨架屏已在数据就绪后移除");
        }, 2000);

        // ④ 多选控制器
        selection = new MultiSelectController()
                .setOnCountChanged(count -> headerLabel.setText(
                        selection.isActive() ? "多选模式 · 已选 " + count + " 项" : "长按任意行进入多选"))
                .setOnModeChanged(active -> headerLabel.setText(
                        active ? "多选模式 · 已选 " + selection.selectedCount() + " 项" : "长按任意行进入多选"));

        // ⑤ FloatingActionButton：悬浮主操作
        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(ThemeColors.get(this, R.color.background));
        FrameLayout.LayoutParams rootLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        container.addView(root, rootLp);

        FloatingActionButton fab = new FloatingActionButton(this);
        fab.setImageResource(android.R.drawable.ic_input_add);
        GradientDrawable fabBg = new GradientDrawable();
        fabBg.setShape(GradientDrawable.OVAL);
        fabBg.setColor(ThemeColors.get(this, R.color.primary));
        fab.setBackground(fabBg);
        FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(
                Tokens.space(this, Tokens.SPACE_3XL) * 2, Tokens.space(this, Tokens.SPACE_3XL) * 2,
                Gravity.END | Gravity.BOTTOM);
        fabLp.setMargins(0, 0, Tokens.space(this, Tokens.SPACE_XL),
                Tokens.space(this, Tokens.SPACE_XL));
        fab.setLayoutParams(fabLp);
        UiFx.click(fab, () -> {
            items.add(0, "新增项 " + items.size());
            // 通知数据段自己的 adapter（ConcatAdapter 会自动换算偏移，别拿外层位置去 notify）
            rowAdapter.notifyItemInserted(0);
        });
        container.addView(fab);

        setContentView(container);
    }

    // ---------------- 适配器 ----------------

    /** 头部 section（演示 ConcatAdapter 的第一段） */
    private class SectionAdapter extends RecyclerView.Adapter<SectionAdapter.Holder> {
        private final String label;

        SectionAdapter(String label) {
            this.label = label;
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            int p = Tokens.space(parent.getContext(), Tokens.SPACE_LG);
            tv.setPadding(p, p, p, p);
            Tokens.applyText(tv, Tokens.TEXT_LABEL, Tokens.Weight.SEMIBOLD,
                    ThemeColors.get(parent.getContext(), R.color.primary));
            return new Holder(tv);
        }

        @Override
        public void onBindViewHolder(Holder holder, int position) {
            ((TextView) holder.itemView).setText(label);
        }

        @Override
        public int getItemCount() {
            return 1;
        }

        class Holder extends RecyclerView.ViewHolder {
            Holder(View itemView) {
                super(itemView);
            }
        }
    }

    /** 数据 section（支持长按多选，选中态用 UiFx.toggleSelection） */
    private class RowAdapter extends RecyclerView.Adapter<RowAdapter.Holder> {

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            int p = Tokens.space(parent.getContext(), Tokens.SPACE_LG);
            tv.setPadding(p, p, p, p);
            Tokens.applyText(tv, Tokens.TEXT_BODY_LG, Tokens.Weight.REGULAR,
                    ThemeColors.get(parent.getContext(), R.color.text_primary));
            return new Holder(tv);
        }

        @Override
        public void onBindViewHolder(Holder holder, int position) {
            final TextView tv = (TextView) holder.itemView;
            tv.setText(items.get(position));
            UiFx.toggleSelection(tv, selection != null && selection.isSelected(position));
            // 位置必须用 getBindingAdapterPosition() 并在回调里重新取：复用后旧 position 会失效，
            // 直接 notifyItemChanged(旧位置) 会抛 Inconsistency detected（真机踩到）。
            UiFx.longPress(tv, () -> {
                int pos = holder.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) {
                    return;
                }
                selection.toggle(pos);
                notifyItemChanged(pos);
            });
            UiFx.click(tv, () -> {
                if (selection == null || !selection.isActive()) {
                    return;
                }
                int pos = holder.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) {
                    return;
                }
                selection.toggle(pos);
                notifyItemChanged(pos);
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            Holder(View itemView) {
                super(itemView);
            }
        }
    }
}
