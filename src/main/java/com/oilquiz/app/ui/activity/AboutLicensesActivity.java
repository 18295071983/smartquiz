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

    /** 随包声明的固定路径；改路径时同步 assets 与 docs 两份副本 */
    static final String ASSET_PATH = "apk_shell/guides/THIRD_PARTY_NOTICES.md";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about_licenses);

        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            }
            toolbar.setNavigationOnClickListener(v -> finish());
        }

        TextView text = findViewById(R.id.licenses_text);
        String markdown = readAsset(ASSET_PATH);
        if (markdown == null || markdown.trim().isEmpty()) {
            text.setText("未找到第三方许可声明：assets/" + ASSET_PATH
                    + "\n请确认打包时包含 apk_shell/guides/THIRD_PARTY_NOTICES.md。");
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
