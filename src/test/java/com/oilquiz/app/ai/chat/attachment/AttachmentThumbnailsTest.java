package com.oilquiz.app.ai.chat.attachment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.net.Uri;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import com.oilquiz.app.ai.chat.ChatMessage;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 附件缩略图加载器的口径（2026-10-25）。
 *
 * <p>输入区 chip / 已发送附件行 / 聊天文件卡共用这一份判断：哪些类型走专用 PreviewEngine、
 * 附件里的哪个字段才是能直接读的本地文件。Office 会落到 X5/TBS，这里必须保持 false。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class AttachmentThumbnailsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void markdownTextPdfImageAreSupported() throws Exception {
        assertTrue(AttachmentThumbnails.supports(file("a.md", 16)));
        assertTrue("扩展名大小写不敏感", AttachmentThumbnails.supports(file("README.MARKDOWN", 16)));
        assertTrue(AttachmentThumbnails.supports(file("x.mkd", 16)));
        assertTrue(AttachmentThumbnails.supports(file("a.txt", 16)));
        assertTrue(AttachmentThumbnails.supports(file("a.csv", 16)));
        assertTrue(AttachmentThumbnails.supports(file("a.pdf", 16)));
        assertTrue(AttachmentThumbnails.supports(file("a.png", 16)));
    }

    @Test
    public void officeAndUnknownAreNotSupported() throws Exception {
        assertFalse("docx 走 X5/TBS", AttachmentThumbnails.supports(file("a.docx", 16)));
        assertFalse(AttachmentThumbnails.supports(file("a.pptx", 16)));
        assertFalse(AttachmentThumbnails.supports(file("a.xlsx", 16)));
        assertFalse(AttachmentThumbnails.supports(file("a.bin", 16)));
        assertFalse(AttachmentThumbnails.supports(null));
        assertFalse("空文件没必要生成", AttachmentThumbnails.supports(file("empty.md", 0)));
    }

    @Test
    public void localFilePrefersLocalPathThenFileUrl() throws Exception {
        File md = file("att.md", 16);
        String fileUrl = Uri.fromFile(md).toString();

        ChatMessage.Attachment withLocal = new ChatMessage.Attachment("file", fileUrl, md.getName(), md.length());
        withLocal.localFilePath = md.getAbsolutePath();
        assertEquals(md.getAbsolutePath(), AttachmentThumbnails.localFileOf(withLocal).getAbsolutePath());

        // 历史消息恢复后可能只剩 url（file://），也能找到本地文件
        ChatMessage.Attachment urlOnly = new ChatMessage.Attachment("file", fileUrl, md.getName(), md.length());
        assertEquals(md.getAbsolutePath(), AttachmentThumbnails.localFileOf(urlOnly).getAbsolutePath());
    }

    @Test
    public void remoteOrMissingAttachmentHasNoLocalFile() throws Exception {
        assertNull(AttachmentThumbnails.localFileOf(null));
        ChatMessage.Attachment remote = new ChatMessage.Attachment("file", "https://example.com/a.md", "a.md", 1);
        assertNull(AttachmentThumbnails.localFileOf(remote));
        ChatMessage.Attachment missing = new ChatMessage.Attachment("file",
                "file:///sdcard/Download/not-exists.md", "not-exists.md", 1);
        assertNull(AttachmentThumbnails.localFileOf(missing));
    }

    private File file(String name, int bytes) throws Exception {
        File f = tmp.newFile(name);
        if (bytes > 0) {
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(new byte[bytes]);
            }
        }
        return f;
    }
}
