package com.oilquiz.app.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.util.List;

/**
 * 壁纸库护栏（2026-11-18）。
 *
 * <p>产品口径：默认<b>关闭壁纸</b>；内置壁纸全部换成实拍图，不再用代码画深色渐变。
 * 这里钉住：</p>
 * <ul>
 *   <li>{@code getMode} 在没存过偏好时返回关闭；</li>
 *   <li>内置库是 11 张真实 jpg（每张 > 100KB，来自 assets），不是程序生成的 png；</li>
 *   <li>重复调用不会重复铺/混入旧缓存；中文显示名与索引一一对应。</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
public class WallpaperStoreTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        ctx.getSharedPreferences("wallpaper_preferences", Context.MODE_PRIVATE)
                .edit().clear().commit();
        // 清掉上次测试/旧版本留下的内置壁纸缓存，强制按新版本重铺
        File dir = WallpaperStore.dir(ctx);
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    @Test
    public void defaultModeIsOff() {
        assertEquals("没存过壁纸偏好时默认必须是关闭",
                AppWallpaperManager.MODE_OFF, AppWallpaperManager.getMode(ctx));
    }

    @Test
    public void builtinLibraryIsRealPhotos() {
        List<File> list = WallpaperStore.ensureBuiltin(ctx);
        assertEquals("内置壁纸数量（与 BUILTIN_NAMES/BUILTIN_ASSETS 一致）", 11, list.size());
        for (File f : list) {
            assertTrue("内置壁纸必须是 jpg 实拍图:" + f.getName(), f.getName().endsWith(".jpg"));
            assertTrue("内置壁纸文件过小，可能没从 assets 拷贝成功:" + f.getName(),
                    f.length() > 100_000);
            assertTrue("内置壁纸必须能被识别为 builtin:" + f.getName(),
                    WallpaperStore.isBuiltin(f));
        }
        // 再调一次不能重复铺/换名
        assertEquals(11, WallpaperStore.ensureBuiltin(ctx).size());
    }

    @Test
    public void builtinNamesMatchIndexes() {
        List<File> list = WallpaperStore.ensureBuiltin(ctx);
        assertEquals("雪山峡谷", WallpaperStore.displayName(list.get(0)));
        assertEquals("镜面湖", WallpaperStore.displayName(list.get(10)));
        assertFalse("内置壁纸不可删", WallpaperStore.delete(ctx, list.get(0)));
        assertTrue("内置壁纸不能被当成用户壁纸", WallpaperStore.isBuiltin(list.get(2)));
    }
}
