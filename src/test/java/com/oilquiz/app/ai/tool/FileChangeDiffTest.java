package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * diff 卡的生产者快照器（2026-10-24）：只收文本、只收小文件、写前/写后对比。
 */
@RunWith(RobolectricTestRunner.class)
public class FileChangeDiffTest {

    private Context context;
    private File dir;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        dir = new File(context.getCacheDir(), "diff-" + System.nanoTime());
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    @After
    public void tearDown() {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }

    @Test
    public void attachAddsBeforeAndAfterWhenContentChanged() throws Exception {
        File file = write("report.md", "old line\nsecond");
        String before = FileChangeDiff.snapshot(context, file.getAbsolutePath());
        assertEquals("old line\nsecond", before);

        write("report.md", "new line\nsecond");
        AIToolResult result = AIToolResult.success("ok", new HashMap<String, Object>());
        FileChangeDiff.attach(context, result, file.getAbsolutePath(), before);

        Map<String, Object> diff = asMap(result.getAdditionalInfo().get(FileChangeDiff.INFO_KEY));
        assertNotNull(diff);
        assertEquals("old line\nsecond", diff.get("before"));
        assertEquals("new line\nsecond", diff.get("after"));
        assertEquals("文件变更 · report.md", diff.get("title"));
    }

    @Test
    public void unchangedFileDoesNotAttachDiff() throws Exception {
        File file = write("same.txt", "same");
        String before = FileChangeDiff.snapshot(context, file.getAbsolutePath());
        AIToolResult result = AIToolResult.success("ok", new HashMap<String, Object>());
        FileChangeDiff.attach(context, result, file.getAbsolutePath(), before);
        assertNull(result.getAdditionalInfo().get(FileChangeDiff.INFO_KEY));
    }

    @Test
    public void existingEmptyFileCanBecomeAllAddDiff() throws Exception {
        File file = write("empty.txt", "");
        String before = FileChangeDiff.snapshot(context, file.getAbsolutePath());
        assertEquals("", before);

        write("empty.txt", "first line");
        AIToolResult result = AIToolResult.success("ok", new HashMap<String, Object>());
        FileChangeDiff.attach(context, result, file.getAbsolutePath(), before);

        Map<String, Object> diff = asMap(result.getAdditionalInfo().get(FileChangeDiff.INFO_KEY));
        assertNotNull(diff);
        assertEquals("", diff.get("before"));
        assertEquals("first line", diff.get("after"));
    }

    @Test
    public void newFileAndBinaryFileAreSkipped() throws Exception {
        assertNull(FileChangeDiff.snapshot(context,
                new File(dir, "missing.txt").getAbsolutePath()));

        File binary = write("image.bin", "not-text");
        assertNull(FileChangeDiff.snapshot(context, binary.getAbsolutePath()));
    }

    @Test
    public void tooManyLinesAreSkipped() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 401; i++) {
            sb.append("line ").append(i).append('\n');
        }
        File file = write("large.txt", sb.toString());
        assertNull(FileChangeDiff.snapshot(context, file.getAbsolutePath()));
    }

    private File write(String name, String content) throws Exception {
        File file = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }
}
