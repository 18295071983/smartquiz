package com.oilquiz.app.ai.embedding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 本地向量模型的"静默不启用"守门：
 *
 * <ul>
 *   <li>模型没下 → 不可用、不尝试加载、embed 抛 IOException（上层换成关键词，不抛给主 agent）；</li>
 *   <li>文件在但跑不起来 → 记一次失败后就地封存，本次运行不再反复加载；</li>
 *   <li>词表不合法 → 同样只失败一次，不会每次检索都重新读一遍 21128 行。</li>
 * </ul>
 *
 * <p>真实推理（ONNX native）在 JVM 单测里加载不了，所以这里只钉"失败路径"，
 * 推理正确性靠真机验证。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class LocalEmbeddingModelTest {

    private Context context;
    private File dir;
    private LocalEmbeddingModel model;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        dir = new File(context.getFilesDir(), LocalEmbeddingModel.relativeDir());
        if (dir.exists()) {
            deleteRecursive(dir);
        }
        model = new LocalEmbeddingModel(dir);
    }

    @Test
    public void missingModelIsNotAvailableAndNeverLoads() {
        assertEquals(LocalEmbeddingModel.State.MISSING, model.state());
        assertEquals(false, model.isAvailable());

        try {
            model.embed("光气泄漏应急处置");
            fail("模型缺失时不该返回向量");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("未下载"));
        }
    }

    @Test
    public void brokenFilesFailOnceAndStopRetrying() throws Exception {
        dir.mkdirs();
        write(new File(dir, "vocab.txt"), "[PAD]\n[UNK]\n[CLS]\n[SEP]\n光\n气\n");
        // 2KB 垃圾字节：让 isAvailable() 通过，但 ONNX 一定加载不了
        byte[] junk = new byte[2048];
        writeBytes(new File(dir, "model.onnx"), junk);

        assertTrue(model.isAvailable());

        try {
            model.embed("光气泄漏");
            fail("垃圾模型不该返回向量");
        } catch (IOException expected) {
            // 第一次失败：加载失败
        }
        assertEquals(LocalEmbeddingModel.State.FAILED, model.state());

        try {
            model.embed("光气泄漏");
            fail("失败后不该重新加载");
        } catch (IOException expected) {
            assertTrue("第二次应直接拒绝，不再加载模型: " + expected.getMessage(),
                    expected.getMessage().contains("不再重试"));
        }
        assertEquals(LocalEmbeddingModel.State.FAILED, model.state());
    }

    @Test
    public void invalidVocabIsRejectedBeforeAnyInference() throws Exception {
        dir.mkdirs();
        write(new File(dir, "vocab.txt"), "只有\n几行\n没有特殊标记\n");
        byte[] junk = new byte[2048];
        writeBytes(new File(dir, "model.onnx"), junk);

        assertTrue(model.isAvailable());
        try {
            model.embed("光气泄漏");
            fail("词表不合法时不该返回向量");
        } catch (IOException expected) {
            assertTrue("应报词表问题: " + expected.getMessage(),
                    expected.getMessage().contains("词表") || expected.getMessage().contains("加载失败"));
        }
        assertEquals(LocalEmbeddingModel.State.FAILED, model.state());
    }

    @Test
    public void resetAllowsRetryAfterFailure() throws Exception {
        dir.mkdirs();
        write(new File(dir, "vocab.txt"), "[PAD]\n[UNK]\n[CLS]\n[SEP]\n");
        writeBytes(new File(dir, "model.onnx"), new byte[2048]);

        try {
            model.embed("x");
            fail();
        } catch (IOException ignored) {
        }
        model.reset();
        // reset 之后回到"文件在、未加载"，下次还能再试一次
        assertEquals(LocalEmbeddingModel.State.READY, model.state());
        assertTrue(model.isAvailable());
    }

    private static void write(File f, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void writeBytes(File f, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }

    private static void deleteRecursive(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }
}
