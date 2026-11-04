package com.oilquiz.app.ui.activity;

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
 * 模型选择页布局的冒烟测试（2026-11-04 重排后）。
 *
 * <p>这一页是纯 XML 改版：卡片换成 MaterialCardView、三行语音设置收进设置卡、分隔线改了
 * 属性引用。属性写错只会在真机打开页面那一刻炸，这里用真实主题 inflate 一次兜住。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class ModelSelectorLayoutTest {

    @Test
    public void pageInflatesWithAppTheme() {
        Context ctx = new ContextThemeWrapper(ApplicationProvider.getApplicationContext(),
                R.style.Theme_OilQuizApp);
        View root = LayoutInflater.from(ctx).inflate(R.layout.activity_model_selector, null);
        // 关键节点还在（改版只动视觉，id 一个都不能少）
        assertNotNull(root.findViewById(R.id.current_model_name));
        assertNotNull(root.findViewById(R.id.online_models_recycler));
        assertNotNull(root.findViewById(R.id.row_asr_model));
        assertNotNull(root.findViewById(R.id.row_tts_model));
        assertNotNull(root.findViewById(R.id.row_tts_voice));
        assertNotNull(root.findViewById(R.id.tv_asr_model_value));
        assertNotNull(root.findViewById(R.id.tv_tts_model_value));
        assertNotNull(root.findViewById(R.id.tv_tts_voice_value));
    }
}
