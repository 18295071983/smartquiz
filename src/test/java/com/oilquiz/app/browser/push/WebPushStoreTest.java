package com.oilquiz.app.browser.push;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class WebPushStoreTest {

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        File file = new File(context.getFilesDir(), "web_push_subscriptions.json");
        file.delete();
    }

    @Test
    public void saveAndLoadRoundTrips() {
        WebPushStore store = new WebPushStore(context);
        WebPushSubscription s = new WebPushSubscription();
        s.id = "push_1";
        s.endpoint = "https://example.com/inbox";
        s.method = "POST";
        s.intervalMinutes = 30;
        s.seenIds.add("m1");
        List<WebPushSubscription> list = new ArrayList<>();
        list.add(s);
        store.save(list);

        List<WebPushSubscription> loaded = new WebPushStore(context).load();
        assertEquals(1, loaded.size());
        assertEquals("push_1", loaded.get(0).id);
        assertEquals("POST", loaded.get(0).method);
        assertEquals(30, loaded.get(0).intervalMinutes);
        assertEquals(1, loaded.get(0).seenIds.size());
    }

    @Test
    public void corruptFileLoadsEmpty() throws Exception {
        File file = new File(context.getFilesDir(), "web_push_subscriptions.json");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write("{broken".getBytes("UTF-8"));
        }
        assertTrue(new WebPushStore(context).load().isEmpty());
    }

    @Test
    public void missingFileLoadsEmpty() {
        assertTrue(new WebPushStore(context).load().isEmpty());
    }
}
