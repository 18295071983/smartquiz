package com.oilquiz.app.ui.activity;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.performance.PerformanceDashboardFragment;

/**
 * PerformanceActivity - 性能监控容器页。
 *
 * 承载 {@link PerformanceDashboardFragment}：实时检测本地推理运行数据，
 * 规则引擎自动分析并给出可执行优化建议。
 */
public class PerformanceActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_performance);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("性能监控");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        if (savedInstanceState == null) {
            FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
            ft.replace(R.id.performance_container, new PerformanceDashboardFragment());
            ft.commit();
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
