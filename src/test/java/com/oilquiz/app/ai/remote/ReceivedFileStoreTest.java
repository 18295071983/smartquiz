package com.oilquiz.app.ai.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.After;
import org.junit.Test;

import java.io.File;

/** 已收文件登记处（2026-11-18，/ws/file 与渲染器的交接点）。 */
public class ReceivedFileStoreTest {

    @After
    public void tearDown() {
        ReceivedFileStore.clear();
    }

    @Test
    public void putThenPeekReturnsSameFile() {
        File f = new File("/tmp/a.png");
        ReceivedFileStore.put("id1", f, "a.png", "image/png");

        ReceivedFileStore.Entry e = ReceivedFileStore.peek("id1");
        assertNotNull(e);
        assertEquals(f, e.file);
        assertEquals("image/png", e.mime);
    }

    @Test
    public void awaitReturnsWhenFileArrives() throws Exception {
        Thread producer = new Thread(() -> {
            try {
                Thread.sleep(60);
            } catch (InterruptedException ignored) {
                return;
            }
            ReceivedFileStore.put("id2", new File("/tmp/b.png"), "b.png", "image/png");
        });
        producer.start();

        ReceivedFileStore.Entry e = ReceivedFileStore.await("id2", 2000);
        assertNotNull("文件到达后要能等到", e);
        producer.join();
    }

    @Test
    public void awaitTimesOutWithoutFile() {
        long start = System.currentTimeMillis();
        assertNull(ReceivedFileStore.await("missing", 120));
        long cost = System.currentTimeMillis() - start;
        assertEquals("超时按约定返回，不无限等", true, cost >= 100);
    }
}
