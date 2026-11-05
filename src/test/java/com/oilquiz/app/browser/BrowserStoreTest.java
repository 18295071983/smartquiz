package com.oilquiz.app.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Field;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class BrowserStoreTest {

    private Context context;

    @Before
    public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("browser_store", Context.MODE_PRIVATE)
                .edit().clear().commit();
        Field instance = BrowserStore.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    @Test
    public void history_dedupesAndMovesToFront() {
        BrowserStore store = BrowserStore.get(context);
        store.recordVisit("https://a.com/1", "A");
        store.recordVisit("https://b.com/2", "B");
        store.recordVisit("https://a.com/1", "A2");

        List<BrowserStore.Entry> history = store.getHistory(null, 0);
        assertEquals(2, history.size());
        assertEquals("https://a.com/1", history.get(0).url);
        assertEquals("A2", history.get(0).title);
        assertEquals("https://b.com/2", history.get(1).url);
    }

    @Test
    public void history_ignoresNonWebUrls() {
        BrowserStore store = BrowserStore.get(context);
        store.recordVisit("file:///data/a.html", "local");
        store.recordVisit(BrowserUrlNormalizer.HOME_URL, "home");
        store.recordVisit(null, null);
        assertTrue(store.getHistory(null, 0).isEmpty());
    }

    @Test
    public void history_isCapped() {
        BrowserStore store = BrowserStore.get(context);
        for (int i = 0; i < BrowserStore.MAX_HISTORY + 25; i++) {
            store.recordVisit("https://site.test/" + i, "P" + i);
        }
        List<BrowserStore.Entry> history = store.getHistory(null, 0);
        assertEquals(BrowserStore.MAX_HISTORY, history.size());
        assertEquals("https://site.test/" + (BrowserStore.MAX_HISTORY + 24), history.get(0).url);
    }

    @Test
    public void history_searchesTitleAndUrl() {
        BrowserStore store = BrowserStore.get(context);
        store.recordVisit("https://news.example.com/a", "今日头条");
        store.recordVisit("https://code.example.com/b", "Repository");

        assertEquals(1, store.getHistory("头条", 0).size());
        assertEquals(1, store.getHistory("code.example", 0).size());
        assertEquals(2, store.getHistory("example.com", 0).size());
    }

    @Test
    public void bookmarks_toggleAndSurviveHistoryClear() {
        BrowserStore store = BrowserStore.get(context);
        assertTrue(store.toggleBookmark("https://a.com", "A"));
        assertTrue(store.isBookmarked("https://a.com"));
        assertEquals(1, store.getBookmarks().size());

        store.recordVisit("https://a.com", "A");
        store.clearHistory();
        assertTrue(store.getHistory(null, 0).isEmpty());
        assertEquals(1, store.getBookmarks().size());

        assertFalse(store.toggleBookmark("https://a.com", "A"));
        assertFalse(store.isBookmarked("https://a.com"));
        assertTrue(store.getBookmarks().isEmpty());
    }

    @Test
    public void corruptedJson_isTreatedAsEmpty() {
        context.getSharedPreferences("browser_store", Context.MODE_PRIVATE)
                .edit().putString("history", "{not-json").commit();
        BrowserStore store = BrowserStore.get(context);
        assertTrue(store.getHistory(null, 0).isEmpty());

        store.recordVisit("https://a.com", "A");
        assertEquals(1, store.getHistory(null, 0).size());
    }
}
