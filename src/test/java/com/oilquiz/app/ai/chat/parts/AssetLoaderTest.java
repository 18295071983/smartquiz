package com.oilquiz.app.ai.chat.parts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 文件引用加载器（2026-11-18，文件回传第二批）：命中 / 单飞 / 取消 / 失败。
 *
 * <p>回调发生在后台线程，断言统一用 latch 等待，不 sleep 轮询。</p>
 */
public class AssetLoaderTest {

    private File cacheDir;

    @Before
    public void setUp() throws Exception {
        cacheDir = Files.createTempDirectory("asset_loader_test").toFile();
    }

    @After
    public void tearDown() {
        File[] files = cacheDir.listFiles();
        if (files != null) {
            for (File f : files) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        //noinspection ResultOfMethodCallIgnored
        cacheDir.delete();
    }

    @Test
    public void memoryHitSkipsSecondFetch() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        AssetLoader loader = new AssetLoader(cacheDir, (id, path) -> {
            fetches.incrementAndGet();
            return stream("hello");
        });

        File first = await(loader, "f1");
        File second = await(loader, "f1");

        assertEquals("同一 id 只下载一次", 1, fetches.get());
        assertEquals(first.getAbsolutePath(), second.getAbsolutePath());
    }

    @Test
    public void diskHitSkipsFetch() throws Exception {
        File cached = new File(cacheDir, "f2");
        Files.write(cached.toPath(), "cached".getBytes(StandardCharsets.UTF_8));
        AtomicInteger fetches = new AtomicInteger();
        AssetLoader loader = new AssetLoader(cacheDir, (id, path) -> {
            fetches.incrementAndGet();
            return stream("net");
        });

        File file = await(loader, "f2");

        assertEquals("磁盘有就直接用", 0, fetches.get());
        assertEquals("cached", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void concurrentRequestsShareOneDownload() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger fetches = new AtomicInteger();
        AssetLoader loader = new AssetLoader(cacheDir, (id, path) -> {
            fetches.incrementAndGet();
            gate.await(2, TimeUnit.SECONDS);
            return stream("shared");
        });

        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<File> a = new AtomicReference<>();
        AtomicReference<File> b = new AtomicReference<>();
        loader.request("f3", null, cb(a, done));
        loader.request("f3", null, cb(b, done));
        gate.countDown();

        assertTrue("两个回调都应完成", done.await(3, TimeUnit.SECONDS));
        assertEquals("并发同 id 只下载一次", 1, fetches.get());
        assertNotNull(a.get());
        assertEquals(a.get().getAbsolutePath(), b.get().getAbsolutePath());
    }

    @Test
    public void cancelStopsCallbackButKeepsDownloadedFile() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        AssetLoader loader = new AssetLoader(cacheDir, (id, path) -> {
            started.countDown();
            gate.await(2, TimeUnit.SECONDS);
            return stream("late");
        });

        AtomicInteger calls = new AtomicInteger();
        loader.request("f4", null, new AssetLoader.Callback() {
            @Override
            public void onReady(File file) {
                calls.incrementAndGet();
            }

            @Override
            public void onError(Throwable error) {
                calls.incrementAndGet();
            }
        });
        assertTrue(started.await(2, TimeUnit.SECONDS));
        loader.cancel("f4");
        gate.countDown();

        Thread.sleep(150); // 让后台线程跑完（下载仍应落盘）
        assertEquals("取消后不再回调", 0, calls.get());
        assertTrue("下载完成仍落盘供下次命中", new File(cacheDir, "f4").isFile());
    }

    @Test
    public void failureReportsErrorAndLeavesNoFile() throws Exception {
        AssetLoader loader = new AssetLoader(cacheDir, (id, path) -> {
            throw new java.io.IOException("boom");
        });
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        loader.request("f5", null, new AssetLoader.Callback() {
            @Override
            public void onReady(File file) {
                done.countDown();
            }

            @Override
            public void onError(Throwable e) {
                error.set(e);
                done.countDown();
            }
        });

        assertTrue(done.await(3, TimeUnit.SECONDS));
        assertNotNull("失败要回调 onError", error.get());
        assertFalse("失败不能留下目标文件", new File(cacheDir, "f5").isFile());
        assertFalse("临时文件也要清掉", new File(cacheDir, "f5.part").isFile());
    }

    // ---------------- helpers ----------------

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static AssetLoader.Callback cb(AtomicReference<File> target, CountDownLatch done) {
        return new AssetLoader.Callback() {
            @Override
            public void onReady(File file) {
                target.set(file);
                done.countDown();
            }

            @Override
            public void onError(Throwable error) {
                done.countDown();
            }
        };
    }

    private static File await(AssetLoader loader, String id) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<File> file = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        loader.request(id, null, new AssetLoader.Callback() {
            @Override
            public void onReady(File f) {
                file.set(f);
                done.countDown();
            }

            @Override
            public void onError(Throwable e) {
                error.set(e);
                done.countDown();
            }
        });
        assertTrue("等待加载完成", done.await(3, TimeUnit.SECONDS));
        assertNull("不应有错误", error.get());
        return file.get();
    }
}
