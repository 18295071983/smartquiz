package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 聊天 file_card 的缩略图口径（2026-10-25）。
 *
 * <p>缩略图只在"有专用 PreviewEngine"的类型上加；Office（docx/pptx/xlsx）会落到 X5/TBS，
 * 依赖主线程与 TBS 内核，聊天列表批量取图不能走那条路——这里把边界钉住，避免以后随手
 * 加扩展名把 X5 拖进卡片渲染。</p>
 */
public class FileCardThumbnailTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void markdownGetsThumbnail() throws Exception {
        assertTrue(FileCardView.supportsThumbnail(file("a.md", 16)));
        assertTrue("扩展名大小写不敏感", FileCardView.supportsThumbnail(file("README.MARKDOWN", 16)));
        assertTrue(FileCardView.supportsThumbnail(file("x.mkd", 16)));
    }

    @Test
    public void textPdfImageGetThumbnail() throws Exception {
        assertTrue(FileCardView.supportsThumbnail(file("a.txt", 16)));
        assertTrue(FileCardView.supportsThumbnail(file("a.json", 16)));
        assertTrue(FileCardView.supportsThumbnail(file("a.pdf", 16)));
        assertTrue(FileCardView.supportsThumbnail(file("a.png", 16)));
        assertTrue(FileCardView.supportsThumbnail(file("a.jpeg", 16)));
    }

    @Test
    public void officeAndUnknownAreSkipped() throws Exception {
        assertFalse("docx 走 X5/TBS，不接", FileCardView.supportsThumbnail(file("a.docx", 16)));
        assertFalse(FileCardView.supportsThumbnail(file("a.pptx", 16)));
        assertFalse(FileCardView.supportsThumbnail(file("a.xlsx", 16)));
        assertFalse(FileCardView.supportsThumbnail(file("a.bin", 16)));
    }

    @Test
    public void missingOrEmptyFileIsSkipped() throws Exception {
        assertFalse(FileCardView.supportsThumbnail(null));
        assertFalse(FileCardView.supportsThumbnail(new File(tmp.getRoot(), "nope.md")));
        assertFalse("空文件没必要生成", FileCardView.supportsThumbnail(file("empty.md", 0)));
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
