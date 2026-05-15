package com.oilquiz.app.ui.activity;

import com.oilquiz.app.ui.base.BaseActivity;
import android.os.Bundle;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;

import com.oilquiz.app.R;
import com.oilquiz.app.infra.AppLogger;

public class AboutActivity extends BaseActivity {

    private static final String TAG = "AboutActivity";

    private TextView tvVersion;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_about;
    }

    @Override
    protected void initView() {
        tvVersion = findViewById(R.id.tv_version);
        
        // 设置Toolbar
        setupToolbar("关于应用");
    }

    @Override
    protected void initData() {
        AppLogger.i(TAG, "关于页面已启动");
    }

    @Override
    protected void initListener() {
        // 检查更新按钮
        findViewById(R.id.btn_check_update).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AppLogger.i(TAG, "检查更新");
                // 这里可以添加检查更新的逻辑
            }
        });
        
        // 隐私政策按钮
        findViewById(R.id.btn_privacy_policy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AppLogger.i(TAG, "查看隐私政策");
                // 这里可以添加打开隐私政策的逻辑
            }
        });
        
        // 用户协议按钮
        findViewById(R.id.btn_user_agreement).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AppLogger.i(TAG, "查看用户协议");
                // 这里可以添加打开用户协议的逻辑
            }
        });
    }
    
    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }
}
