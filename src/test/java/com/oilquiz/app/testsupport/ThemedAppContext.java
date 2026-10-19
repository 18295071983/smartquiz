package com.oilquiz.app.testsupport;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.oilquiz.app.R;

/**
 * 单测用的应用上下文。
 *
 * <p>为什么要包一层：{@code ThemeColors.get(Context, resId)} 对主题语义色
 * （primary / text_primary / text_secondary / surface 等）是走
 * {@code context.getTheme().resolveAttribute(?attr/colorXxx)} 的，解析不到时按设计返回 0
 * ——这是生产代码的既有口径，为了让用例变绿不该去改它。
 * 真机上取色都发生在 Activity 上，主题 {@code Theme.OilQuizApp} 由清单逐页叠加，attr 一定能解析；
 * 而 Robolectric 里 {@code ApplicationProvider.getApplicationContext()} 拿到的是
 * <b>没套主题</b>的 Application 上下文，于是这些颜色在单测里读出来是 0，
 * 表现为 {@code ColorResourceProviderTest} / {@code AppResourceManagerTest} /
 * {@code ResourceIntegrationTest} 成片 "color should not be 0" 失败。
 *
 * <p>所以这里把清单里的应用主题显式套到应用上下文上，让单测的取色环境与真机一致。
 * 注意 {@code ColorResourceProvider} 构造时会 {@code getApplicationContext()}，
 * 套在 ContextThemeWrapper 上会被脱掉，必须套在应用上下文本身才有效。
 */
public final class ThemedAppContext {

    private ThemedAppContext() {
    }

    /** 返回已套用 {@code R.style.Theme_OilQuizApp} 的应用上下文 */
    public static Context get() {
        Context app = ApplicationProvider.getApplicationContext();
        app.setTheme(R.style.Theme_OilQuizApp);
        return app;
    }
}
