package com.oilquiz.app.ui.activity;

import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.chat.render.MarkdownRenderer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 开源许可 / 第三方组件声明页（2026-10-24）。
 *
 * <p>为什么要有这个页面：Apache-2.0 要求分发时保留版权与许可声明，LGPL/GPL 要求用户能拿到
 * 源码获取方式。之前这些只写在 {@code assets/apk_shell/guides/THIRD_PARTY_NOTICES.md}
 * （启动后恢复到工作区 files/），普通用户根本找不到——等于"声明了但看不见"。</p>
 *
 * <p>内容直接读随包 asset，不复制一份到代码里，避免两份许可文本漂移；改声明只改那一份 md
 * （以及 docs/ 下的同内容副本）。渲染用应用已有的 {@link MarkdownRenderer}（Markwon +
 * 表格 + 链接插件），条目里的链接可点。</p>
 */
public class AboutLicensesActivity extends AppCompatActivity {

    private static final String TAG = "AboutLicenses";

    /** 默认展示第三方许可声明；子类可覆盖 {@link #assetPath()} 复用同一套渲染 */
    static final String DEFAULT_ASSET = "apk_shell/guides/THIRD_PARTY_NOTICES.md";

    /** 子类可覆盖：要展示的 Markdown 资源路径（如隐私政策） */
    protected String assetPath() {
        return DEFAULT_ASSET;
    }

    /** 子类可覆盖：页面标题 */
    protected String pageTitle() {
        return "开源许可";
    }

    /** 子类可覆盖：是否在标题栏菜单里提供「撤回隐私授权」（隐私政策页用） */
    protected boolean supportsWithdrawConsent() {
        return false;
    }

    private static final int MENU_WITHDRAW_CONSENT = 0x51;

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        if (supportsWithdrawConsent()) {
            menu.add(0, MENU_WITHDRAW_CONSENT, 0, "撤回隐私授权");
        }
        return super.onCreateOptionsMenu(menu) || supportsWithdrawConsent();
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == MENU_WITHDRAW_CONSENT) {
            showWithdrawConsentDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** 撤回同意：清掉同意状态并回到首页（首页门禁会再次弹出政策确认） */
    private void showWithdrawConsentDialog() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("撤回隐私授权")
                .setMessage("撤回后，需要联网/权限的功能将停止使用；下次启动会重新要求你阅读并同意《隐私政策》。\n\n确定撤回吗？")
                .setPositiveButton("撤回", (d, w) -> {
                    com.oilquiz.app.util.PrivacyConsent.revoke(this);
                    android.content.Intent intent = new android.content.Intent(this,
                            com.oilquiz.app.MainActivity.class);
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                            | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about_licenses);

        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setTitle(pageTitle());
            setSupportActionBar(toolbar);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            }
            toolbar.setNavigationOnClickListener(v -> finish());
        }

        TextView text = findViewById(R.id.licenses_text);
        String markdown = readAsset(assetPath());
        if (markdown == null || markdown.trim().isEmpty()) {
            text.setText("未找到文档：assets/" + assetPath()
                    + "\n请确认打包时包含该文件。");
            return;
        }
        text.setText(MarkdownRenderer.render(markdown, this));
        text.setMovementMethod(LinkMovementMethod.getInstance());
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    /** 读 assets 文本（UTF-8）。失败返回 null，由调用方显示降级提示，不抛给用户。 */
    private String readAsset(String path) {
        try (InputStream in = getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            Log.e(TAG, "读取许可声明失败: " + path + " -> " + e.getMessage());
            return null;
        }
    }
}
