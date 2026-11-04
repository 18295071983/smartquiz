package com.oilquiz.app.ui.dialog;

import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.core.app.ApplicationProvider;

import com.oilquiz.app.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 音色选择弹窗的布局冒烟测试（2026-11-04 重做后）。
 *
 * <p>这类资源问题只在真机弹窗那一刻才炸（Material 主题不匹配、dimen 缺失、drawable 名字写错），
 * 编译期 AAPT 只管引用存在与否；这里用真实主题把两个布局各 inflate 一次，把空指针挡在提测前。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class VoiceSelectorDialogLayoutTest {

    @Test
    public void dialogLayoutInflatesWithAppTheme() {
        Context ctx = themedContext();
        View root = LayoutInflater.from(ctx).inflate(R.layout.dialog_tts_voice_selector, null);
        assertNotNull(root.findViewById(R.id.voice_recycler));
        assertNotNull(root.findViewById(R.id.et_voice_search));
        assertNotNull(root.findViewById(R.id.btn_voice_confirm));
        assertNotNull(root.findViewById(R.id.btn_voice_cancel));
        assertNotNull(root.findViewById(R.id.tv_voice_subtitle));
        assertNotNull(root.findViewById(R.id.tv_voice_empty));
    }

    @Test
    public void itemLayoutInflatesWithAppTheme() {
        Context ctx = themedContext();
        View item = LayoutInflater.from(ctx).inflate(R.layout.item_tts_voice, null);
        assertNotNull(item.findViewById(R.id.rb_voice_selected));
        assertNotNull(item.findViewById(R.id.tv_voice_name));
        assertNotNull(item.findViewById(R.id.tv_voice_id));
        assertNotNull(item.findViewById(R.id.btn_voice_preview));
    }

    /**
     * robolectric.properties 里 application 固定成 android.app.Application（避开 Chaquopy 的
     * .so 加载），清单里的应用主题不会生效；这里显式套上真机的 Theme.OilQuizApp，
     * 否则 ?attr/colorControlText 这类 Material 属性解析不出来，测出来的失败是假的。
     */
    private static Context themedContext() {
        return new ContextThemeWrapper(ApplicationProvider.getApplicationContext(),
                R.style.Theme_OilQuizApp);
    }
}
