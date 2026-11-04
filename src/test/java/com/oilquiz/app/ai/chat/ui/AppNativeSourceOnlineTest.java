package com.oilquiz.app.ai.chat.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.oilquiz.app.ai.engine.contract.GenSignal;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 2026-11-04 真机日志：在线对话时每秒 ~10 条 {@code nativeGetKvCacheStats: helper context not
 * initialized}。根因是状态条的数据源把「是否在线」写死成 false —— 在线模式下 llama 上下文
 * 根本没建，却仍按本地引擎每个 token 取一次 KV 统计。
 *
 * <p>这里钉住两条：① 注入的路由判定要真的生效；② 在线时状态条一次引擎调用都不许发。
 */
@RunWith(RobolectricTestRunner.class)
public class AppNativeSourceOnlineTest {

    @Test
    public void injectedRouteIsHonoured() {
        assertTrue(new AppNativeSource(null, () -> true).isUsingOnlineModel());
        assertFalse(new AppNativeSource(null, () -> false).isUsingOnlineModel());
    }

    @Test
    public void noRouteMeansLocalForBackwardCompatibility() {
        assertFalse(new AppNativeSource(null).isUsingOnlineModel());
    }

    @Test
    public void routeFailureFallsBackToLocal() {
        AppNativeSource broken = new AppNativeSource(null, () -> {
            throw new IllegalStateException("router not ready");
        });
        assertFalse(broken.isUsingOnlineModel());
    }

    @Test
    public void onlineStatusBarNeverTouchesTheEngine() {
        Context ctx = ApplicationProvider.getApplicationContext();
        TextView phase = new TextView(ctx);
        TextView kv = new TextView(ctx);
        AtomicInteger kvCalls = new AtomicInteger();

        GenerationStatusBar.NativeSource online = new GenerationStatusBar.NativeSource() {
            @Override
            public boolean isUsingOnlineModel() {
                return true;
            }

            @Override
            public GenSignal.KvStats getKvStats() {
                kvCalls.incrementAndGet();
                return null;
            }
        };

        new GenerationStatusBar(ctx, phase, kv, online).refresh();
        assertEquals("在线模式不该去问 native 要 KV 统计", 0, kvCalls.get());
    }
}
