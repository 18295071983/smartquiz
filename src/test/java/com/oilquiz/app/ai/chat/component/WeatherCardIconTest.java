package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.oilquiz.app.util.QWeatherIconFont;
import com.oilquiz.app.util.QWeatherIconMapper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * 天气卡图标映射（2026-10-29）：
 * 模型从 {@code ai_weather} 文本里看到的是图标代码（{@code 图标: 104}），
 * 手写 weather_card 时会把代码原样抄进 props.icon，卡片必须用本地和风图标包
 * （assets/fonts/qweather-icons.ttf）画图标，不能把 "104" 当文字显示。
 */
@RunWith(RobolectricTestRunner.class)
public class WeatherCardIconTest {

    @Test
    public void iconCodeRendersWithLocalIconFont() throws Exception {
        Context ctx = appContext();
        List<TextView> texts = renderTextViews(props("北京", "26℃", "多云", "104", null));
        TextView icon = iconView(ctx, texts);
        assertNotNull("没找到用图标字体渲染的图标格: " + dump(texts), icon);
        assertEquals("图标格应画 104 对应的和风图标字形",
                QWeatherIconFont.getIcon("104"), icon.getText().toString());
        assertFalse("不应把图标代码原样显示", containsText(texts, "104"));
    }

    @Test
    public void emojiIconIsRenderedWithLocalIconFont() throws Exception {
        Context ctx = appContext();
        List<TextView> texts = renderTextViews(props("北京", "26℃", "晴", "☀️", null));
        TextView icon = iconView(ctx, texts);
        assertNotNull("emoji 图标也应落到和风图标字形: " + dump(texts), icon);
        assertEquals(QWeatherIconFont.getIcon("100"), icon.getText().toString());
    }

    @Test
    public void missingIconFallsBackToWeatherText() throws Exception {
        Context ctx = appContext();
        List<TextView> texts = renderTextViews(props("北京", "26℃", "小雨", "", null));
        TextView icon = iconView(ctx, texts);
        assertNotNull("缺 icon 时应按天气文案选图标: " + dump(texts), icon);
        assertEquals(QWeatherIconFont.getIcon("305"), icon.getText().toString());
    }

    @Test
    public void forecastDayIconCodeIsMapped() throws Exception {
        Context ctx = appContext();
        JSONArray forecast = new JSONArray();
        forecast.put(new JSONObject().put("date", "10-30").put("text", "晴")
                .put("icon", "100").put("tempMin", "18℃").put("tempMax", "28℃"));
        List<TextView> texts = renderTextViews(props("北京", "26℃", "多云", null, forecast));
        assertTrue("预报行应画 100 对应的图标字形: " + dump(texts),
                containsText(texts, QWeatherIconFont.getIcon("100")));
        assertFalse("预报行不应显示图标代码", containsText(texts, "100"));
    }

    @Test
    public void indexIconCodeIsNotDrawnAsWeather() throws Exception {
        // 1001+ 是生活指数图标，不属于天气卡；退回天气文案兜底，也不能显示数字
        Context ctx = appContext();
        List<TextView> texts = renderTextViews(props("北京", "26℃", "小雨", "1001", null));
        assertEquals("指数代码应退回天气文案图标",
                QWeatherIconFont.getIcon("305"), iconView(ctx, texts).getText().toString());
        assertFalse("不应显示指数代码", containsText(texts, "1001"));
    }

    @Test
    public void unknownEmojiPassesThroughInsteadOfFakeWeatherIcon() throws Exception {
        List<TextView> texts = renderTextViews(props("北京", "26℃", "多云", "🌦️", null));
        assertTrue("认不出的 emoji 应原样展示: " + dump(texts), containsText(texts, "🌦️"));
    }

    @Test
    public void mapperNormalizesIconSources() {
        assertEquals("104", QWeatherIconMapper.normalizeIconCode("104"));
        assertEquals("104", QWeatherIconMapper.normalizeIconCode(" 104 "));
        assertEquals("104", QWeatherIconMapper.normalizeIconCode("104-fill"));
        assertNull(QWeatherIconMapper.normalizeIconCode("1004"));
        assertNull(QWeatherIconMapper.normalizeIconCode("⛅"));
        assertNull(QWeatherIconMapper.normalizeIconCode(""));

        assertEquals("100", QWeatherIconMapper.codeByEmoji("☀️"));
        assertEquals("101", QWeatherIconMapper.codeByEmoji("⛅"));
        assertNull(QWeatherIconMapper.codeByEmoji("🌦️"));

        assertEquals("305", QWeatherIconMapper.codeByText("小雨"));
        assertEquals("302", QWeatherIconMapper.codeByText("雷阵雨"));
        assertEquals("101", QWeatherIconMapper.codeByText("多云"));
        assertEquals("999", QWeatherIconMapper.codeByText(null));

        // 代码优先于文案；没图标时按文案；代码形态但非法时不画数字
        assertEquals("104", QWeatherIconMapper.resolveCardIconCode("104", "晴"));
        assertEquals("101", QWeatherIconMapper.resolveCardIconCode("⛅", "晴"));
        assertEquals("100", QWeatherIconMapper.resolveCardIconCode("", "晴"));
        assertEquals("305", QWeatherIconMapper.resolveCardIconCode("1001", "小雨"));
        assertNull(QWeatherIconMapper.resolveCardIconCode("1001", ""));
    }

    // ---- helpers ----

    private static Context appContext() {
        return ApplicationProvider.getApplicationContext();
    }

    private static JSONObject props(String city, String temp, String text, String icon,
                                    JSONArray forecast) throws Exception {
        JSONObject p = new JSONObject();
        p.put("city", city);
        p.put("temp", temp);
        p.put("text", text);
        if (icon != null) p.put("icon", icon);
        if (forecast != null) p.put("forecast", forecast);
        return p;
    }

    private static List<TextView> renderTextViews(JSONObject props) {
        WeatherCardView card = new WeatherCardView();
        View v = card.createView(appContext(), ComponentData.of("weather_card", props));
        List<TextView> out = new ArrayList<>();
        collectTextViews(v, out);
        return out;
    }

    private static void collectTextViews(View v, List<TextView> out) {
        if (v instanceof TextView) out.add((TextView) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectTextViews(g.getChildAt(i), out);
        }
    }

    /** 图标格 = 用和风图标字体渲染的 TextView */
    private static TextView iconView(Context ctx, List<TextView> views) {
        Typeface iconTypeface = QWeatherIconFont.getTypeface(ctx);
        for (TextView tv : views) {
            if (iconTypeface.equals(tv.getTypeface())) return tv;
        }
        return null;
    }

    private static boolean containsText(List<TextView> views, String text) {
        for (TextView tv : views) {
            if (text.contentEquals(tv.getText())) return true;
        }
        return false;
    }

    private static String dump(List<TextView> views) {
        StringBuilder sb = new StringBuilder();
        for (TextView tv : views) {
            sb.append('[').append(tv.getText()).append(']');
        }
        return sb.toString();
    }
}
