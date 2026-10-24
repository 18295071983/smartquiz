package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.oilquiz.app.MainActivity;
import com.oilquiz.app.R;
import com.oilquiz.app.util.PrivacyConsent;

/**
 * 隐私政策同意页（2026-10-24，独立 Launcher）。
 *
 * <p>为什么单独做一个 Launcher 而不是只弹在 MainActivity 里：从通知栏、深链、或系统恢复的
 * 任意 Activity 都可能绕过首页。把入口收成一个专门页面后，冷启动路径只有两条——
 * 已同意：立刻转 {@link MainActivity}；未同意：停在此页，除"查看政策"外无路可走。</p>
 *
 * <p>拒绝即 {@code finishAffinity()} 退出，不初始化任何业务、不联网。</p>
 */
public class PrivacyConsentActivity extends AppCompatActivity {

    private boolean forwarded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (PrivacyConsent.isAccepted(this)) {
            goMain();
            return;
        }

        setContentView(R.layout.activity_privacy_consent);

        findViewById(R.id.btn_agree).setOnClickListener(v -> {
            PrivacyConsent.accept(this);
            goMain();
        });
        findViewById(R.id.btn_view).setOnClickListener(v ->
                startActivity(new Intent(this, PrivacyPolicyActivity.class)));
        findViewById(R.id.btn_refuse).setOnClickListener(v -> finishAffinity());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从政策页返回时如果已经同意（理论上不会），直接进主界面
        if (!forwarded && PrivacyConsent.isAccepted(this)) {
            goMain();
        }
    }

    private void goMain() {
        if (forwarded) return;
        forwarded = true;
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
