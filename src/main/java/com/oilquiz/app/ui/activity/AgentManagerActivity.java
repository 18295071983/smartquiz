package com.oilquiz.app.ui.activity;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.oilquiz.app.R;

/**
 * Agent 管理页：手动管理 Agent 的长期记忆 / 动态工具 / 工作区 / 使用统计。
 *
 * 目标：Agent 不是黑箱子——记忆、工具、文件、学习数据都可见可管。
 * Tab：记忆 / 工具 / 工作区 / 统计
 */
public class AgentManagerActivity extends AppCompatActivity {

    private TabLayout tabLayout;
    private ViewPager2 viewPager;
    private final String[] tabTitles = {"🧠 记忆", "🔧 工具", "📁 工作区", "📊 统计"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_agent_manager);

        tabLayout = findViewById(R.id.tabLayout);
        viewPager = findViewById(R.id.viewPager);

        viewPager.setAdapter(new AgentTabAdapter(this));
        new TabLayoutMediator(tabLayout, viewPager,
                (tab, position) -> tab.setText(tabTitles[position])).attach();

        // 返回按钮
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
    }

    private static class AgentTabAdapter extends FragmentStateAdapter {

        AgentTabAdapter(@NonNull AppCompatActivity activity) {
            super(activity);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            switch (position) {
                case 0: return new MemoryManageFragment();
                case 1: return new ToolManageFragment();
                case 2: return new WorkspaceManageFragment();
                case 3: return new StatsManageFragment();
                default: return new MemoryManageFragment();
            }
        }

        @Override
        public int getItemCount() {
            return 4;
        }
    }

    /** 占位 Fragment 基类：统一把子类内容包进 ScrollView，支持列表滚动 */
    public abstract static class BaseManageFragment extends Fragment {

        /** 子类实现：返回实际管理内容 View（记忆/工具/工作区/统计） */
        protected abstract View buildContent(LayoutInflater inflater, ViewGroup container);

        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                                 android.os.Bundle savedInstanceState) {
            View content = buildContent(inflater, container);
            android.widget.ScrollView scrollView = new android.widget.ScrollView(requireContext());
            scrollView.setFillViewport(true);
            scrollView.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            scrollView.addView(content);
            return scrollView;
        }

        protected TextView createPlaceholder(ViewGroup container, String text) {
            TextView tv = new TextView(requireContext());
            tv.setText(text);
            tv.setTextSize(13);
            tv.setTextColor(0xFF94A3B8);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setPadding(0, dp(24), 0, 0);
            container.addView(tv);
            return tv;
        }

        protected int dp(float value) {
            return (int) android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_DIP, value,
                    requireContext().getResources().getDisplayMetrics());
        }
    }

    /** 记忆管理 Tab */
    public static class MemoryManageFragment extends BaseManageFragment {
        @Override
        protected View buildContent(@NonNull LayoutInflater inflater, ViewGroup container) {
            return new com.oilquiz.app.ai.agent.ui.AgentMemoryView(requireContext()).build(container);
        }
    }

    /** 动态工具管理 Tab */
    public static class ToolManageFragment extends BaseManageFragment {
        @Override
        protected View buildContent(@NonNull LayoutInflater inflater, ViewGroup container) {
            return new com.oilquiz.app.ai.agent.ui.AgentToolView(requireContext()).build(container);
        }
    }

    /** 工作区管理 Tab */
    public static class WorkspaceManageFragment extends BaseManageFragment {
        @Override
        protected View buildContent(@NonNull LayoutInflater inflater, ViewGroup container) {
            return new com.oilquiz.app.ai.agent.ui.AgentWorkspaceView(requireContext()).build(container);
        }
    }

    /** 统计管理 Tab */
    public static class StatsManageFragment extends BaseManageFragment {
        @Override
        protected View buildContent(@NonNull LayoutInflater inflater, ViewGroup container) {
            return new com.oilquiz.app.ai.agent.ui.AgentStatsView(requireContext()).build(container);
        }
    }
}
