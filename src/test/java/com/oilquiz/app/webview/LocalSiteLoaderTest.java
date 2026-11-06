package com.oilquiz.app.webview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceResponse;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * LocalSiteLoader：本地多文件站点的 file-URL XHR 替代（2026-11-06）。
 *
 * <p>钉住：注册目录后，index.html 里的 fetch('data.json') 能经
 * appassets.androidplatform.net 取到；越出注册目录的 ../ 被拒。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class LocalSiteLoaderTest {

    @Test
    public void servesRegisteredDirAndBlocksTraversal() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File dir = new File(context.getCacheDir(), "local_site_test");
        assertTrue(dir.mkdirs() || dir.isDirectory());
        try (FileOutputStream out = new FileOutputStream(new File(dir, "data.json"))) {
            out.write("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
        }
        try (FileOutputStream out = new FileOutputStream(new File(dir.getParentFile(), "secret.txt"))) {
            out.write("secret".getBytes(StandardCharsets.UTF_8));
        }

        LocalSiteLoader loader = LocalSiteLoader.getInstance(context);
        String base = loader.baseUrlForDir(dir);
        assertNotNull(base);
        assertTrue(base.startsWith("https://" + LocalSiteLoader.DOMAIN + "/local/"));

        WebResourceResponse ok = loader.shouldInterceptRequest(Uri.parse(base + "data.json"));
        assertNotNull("注册目录内的 fetch 应命中 loader", ok);
        assertEquals("application/json", ok.getMimeType());
        assertEquals("{\"ok\":true}", read(ok.getData()));

        WebResourceResponse escape = loader.shouldInterceptRequest(Uri.parse(base + "../secret.txt"));
        assertNotNull(escape);
        assertTrue("越界请求不能读到 secret",
                !"secret".equals(read(escape.getData())));
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream input = in) {
            byte[] buf = new byte[256];
            int n;
            StringBuilder sb = new StringBuilder();
            while ((n = input.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            return sb.toString();
        }
    }
}
