package com.oilquiz.app.ai.chat;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.oilquiz.app.ui.adapter.AttachmentAdapter;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * 真机验证 .md 附件的缩略图（2026-10-25）。
 *
 * <p>两处入口都用真机渲染并截图：
 * <ul>
 *   <li>输入区附件行（{@link AttachmentAdapter}，即 item_attachment_file）；</li>
 *   <li>已发送消息的附件行（{@link MessageAttachmentAdapter}，即 item_message_attachment_file）。</li>
 * </ul>
 * 只看编译过不算数：缩略图是"后台线程找引擎 → 主线程回填 ImageView"，而且附件行有
 * ViewHolder 复用，必须真机确认真的能出图。</p>
 *
 * <p>运行：
 * <pre>
 * adb shell am instrument -w -e class com.oilquiz.app.ai.chat.AttachmentThumbnailDeviceTest \
 *     com.oilquiz.app.test/androidx.test.runner.AndroidJUnitRunner
 * </pre></p>
 */
@RunWith(AndroidJUnit4.class)
public class AttachmentThumbnailDeviceTest {

    private Context target;
    private Context themed;

    @Before
    public void setUp() {
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        themed = new ContextThemeWrapper(target, com.oilquiz.app.R.style.Theme_OilQuizApp);
    }

    @Test
    public void inputAttachmentRowShowsMarkdownThumbnail() throws Exception {
        File md = markdown("input-row-thumb.md");
        final ChatMessage.Attachment attachment = attachmentOf(md);

        final AttachmentAdapter adapter = new AttachmentAdapter(themed,
                Collections.singletonList(attachment), null);
        final RecyclerView.ViewHolder[] holder = new RecyclerView.ViewHolder[1];
        runOnMain(() -> {
            FrameLayout parent = new FrameLayout(themed);
            holder[0] = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0));
            adapter.onBindViewHolder(holder[0], 0);
        });

        ImageView thumb = holder[0].itemView.findViewById(com.oilquiz.app.R.id.attachment_file_thumb);
        assertNotNull("输入区附件行应有缩略图 ImageView", thumb);
        assertTrue("输入区附件行应显示 md 缩略图", awaitThumbnail(thumb, 20_000));
        save(holder[0].itemView, "attachment-input-row-thumb-device-test.png");
    }

    @Test
    public void sentFileRowShowsMarkdownThumbnail() throws Exception {
        File md = markdown("row-thumb.md");
        final ChatMessage.Attachment attachment = attachmentOf(md);

        final MessageAttachmentAdapter adapter = new MessageAttachmentAdapter();
        final RecyclerView.ViewHolder[] holder = new RecyclerView.ViewHolder[1];
        runOnMain(() -> {
            FrameLayout parent = new FrameLayout(themed);
            adapter.setAttachments(Collections.singletonList(attachment));
            holder[0] = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0));
            adapter.onBindViewHolder(holder[0], 0);
        });

        ImageView thumb = holder[0].itemView.findViewById(com.oilquiz.app.R.id.file_thumb);
        assertNotNull("已发送附件行应有缩略图 ImageView", thumb);
        assertTrue("已发送附件行应显示 md 缩略图", awaitThumbnail(thumb, 20_000));
        save(holder[0].itemView, "attachment-row-thumb-device-test.png");
    }

    private ChatMessage.Attachment attachmentOf(File md) {
        ChatMessage.Attachment attachment = new ChatMessage.Attachment(
                "file", Uri.fromFile(md).toString(), md.getName(), md.length());
        attachment.localFilePath = md.getAbsolutePath();
        return attachment;
    }

    private File markdown(String name) throws Exception {
        File md = new File(target.getCacheDir(), name);
        // 带时间戳：缓存按内容哈希，避免命中上一轮（可能是空图）的缓存
        String text = "# 附件缩略图验收 " + System.currentTimeMillis() + "\n\n"
                + "正文包含 **粗体** 和 `行内代码`。\n\n"
                + "| 列 A | 列 B |\n|---|---|\n| 1 | 2 |\n\n$$E = mc^2$$\n";
        try (FileOutputStream fos = new FileOutputStream(md)) {
            fos.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return md;
    }

    private void runOnMain(Runnable runnable) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(runnable);
    }

    /** 轮询等后台缩略图回填到目标 ImageView（只看这个控件，别把行内箭头图标误认成缩略图）。 */
    private static boolean awaitThumbnail(ImageView view, long timeoutMs) throws InterruptedException {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            Drawable d = view.getDrawable();
            if (view.getVisibility() == View.VISIBLE && d instanceof BitmapDrawable
                    && ((BitmapDrawable) d).getBitmap() != null) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    /** 把控件画进 PNG，方便 adb pull 肉眼确认。 */
    private void save(View view, String fileName) {
        int width = Math.min(900, target.getResources().getDisplayMetrics().widthPixels - 48);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, view.getMeasuredWidth()),
                Math.max(1, view.getMeasuredHeight()), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.WHITE);
        view.draw(canvas);
        File out = new File(target.getExternalFilesDir(null), fileName);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            bmp.recycle();
        }
        assertTrue("截图应已落盘: " + out, out.isFile() && out.length() > 0);
    }
}
