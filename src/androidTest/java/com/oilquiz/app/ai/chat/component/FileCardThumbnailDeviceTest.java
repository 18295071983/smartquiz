package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 真机验证聊天 file_card 的缩略图（2026-10-25）。
 *
 * <p>为什么要真机跑：缩略图是"卡片 View 先挂占位 ImageView → 后台线程找 PreviewEngine →
 * 主线程 setImageBitmap"，Robolectric 的影子 WebView/Canvas 既证明不了 md 真能出图，
 * 也看不出卡片排版。这里直接把卡片画到 Bitmap 落盘，可 adb pull 肉眼确认。</p>
 *
 * <p>运行：
 * <pre>
 * adb shell am instrument -w -e class com.oilquiz.app.ai.chat.component.FileCardThumbnailDeviceTest \
 *     com.oilquiz.app.test/androidx.test.runner.AndroidJUnitRunner
 * </pre></p>
 */
@RunWith(AndroidJUnit4.class)
public class FileCardThumbnailDeviceTest {

    private Context target;
    private Context themed;

    @Before
    public void setUp() {
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        themed = new ContextThemeWrapper(target, com.oilquiz.app.R.style.Theme_OilQuizApp);
    }

    @Test
    public void markdownFileCardShowsThumbnail() throws Exception {
        File md = new File(target.getCacheDir(), "card-thumb.md");
        write(md, "# 卡片缩略图验收\n\n正文包含 **粗体** 和 `行内代码`。\n\n"
                + "| 列 A | 列 B |\n|---|---|\n| 1 | 2 |\n\n"
                + "$$E = mc^2$$\n");
        View card = createCard(md);

        ImageView thumb = awaitThumbnail(card, 20_000);
        assertNotNull("md file_card 应该出现缩略图", thumb);
        Drawable drawable = thumb.getDrawable();
        assertTrue("缩略图应是位图: " + drawable,
                drawable instanceof BitmapDrawable && ((BitmapDrawable) drawable).getBitmap() != null);

        File out = new File(target.getExternalFilesDir(null), "file-card-thumb-device-test.png");
        saveCard(card, out);
    }

    @Test
    public void officeFileCardHasNoThumbnail() throws Exception {
        File docx = new File(target.getCacheDir(), "card-thumb.docx");
        write(docx, "not a real docx");
        View card = createCard(docx);

        // Office 不在缩略图白名单里：不应该起后台任务，也不该挂位图 ImageView
        Thread.sleep(1200);
        assertNull("docx 走 X5/TBS，不应挂缩略图", findBitmapImageView(card));
    }

    private View createCard(File file) throws Exception {
        JSONObject props = new JSONObject();
        props.put("name", file.getName());
        props.put("path", file.getAbsolutePath());
        props.put("size", (file.length() / 1024) + " KB");
        final ComponentData data = ComponentData.of("file_card", props);
        final View[] holder = new View[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> holder[0] = new FileCardView().createView(themed, data));
        assertNotNull("file_card 渲染失败", holder[0]);
        return holder[0];
    }

    /** 轮询等后台缩略图回填（主线程 setImageBitmap 后会变成 BitmapDrawable）。 */
    private static ImageView awaitThumbnail(View card, long timeoutMs) throws InterruptedException {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            ImageView found = findBitmapImageView(card);
            if (found != null) return found;
            Thread.sleep(50);
        }
        return null;
    }

    private static ImageView findBitmapImageView(View view) {
        if (view instanceof ImageView) {
            Drawable d = ((ImageView) view).getDrawable();
            // 卡片头部图标是 vector，只有缩略图是 BitmapDrawable
            if (d instanceof BitmapDrawable) return (ImageView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                ImageView found = findBitmapImageView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void saveCard(View card, File out) {
        int width = Math.min(1080, target.getResources().getDisplayMetrics().widthPixels) - 48;
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        card.layout(0, 0, card.getMeasuredWidth(), card.getMeasuredHeight());
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, card.getMeasuredWidth()),
                Math.max(1, card.getMeasuredHeight()), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.WHITE);
        card.draw(canvas);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            bmp.recycle();
        }
        assertTrue("卡片截图应已落盘: " + out, out.isFile() && out.length() > 0);
    }

    private static void write(File file, String text) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
