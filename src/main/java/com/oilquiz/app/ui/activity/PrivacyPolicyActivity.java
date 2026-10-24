package com.oilquiz.app.ui.activity;

/**
 * 隐私政策页（2026-10-24）。
 *
 * <p>内容在 {@code assets/apk_shell/guides/PRIVACY_POLICY.md}（仓库内 {@code docs/PRIVACY_POLICY.md}
 * 是同内容副本），直接复用 {@link AboutLicensesActivity} 的 Markdown 渲染与返回逻辑。</p>
 */
public class PrivacyPolicyActivity extends AboutLicensesActivity {

    private static final String ASSET = "apk_shell/guides/PRIVACY_POLICY.md";

    @Override
    protected String assetPath() {
        return ASSET;
    }

    @Override
    protected String pageTitle() {
        return "隐私政策";
    }

    @Override
    protected boolean supportsWithdrawConsent() {
        return true;
    }
}
