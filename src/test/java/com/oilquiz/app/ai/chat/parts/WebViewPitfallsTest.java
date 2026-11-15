package com.oilquiz.app.ai.chat.parts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.test.core.app.ApplicationProvider;

import com.oilquiz.app.ai.chat.component.ComponentData;
import com.oilquiz.app.ai.chat.component.ComponentLifecycle;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * WebView 坑位档案的护栏（2026-11-16）。
 *
 * <p>对应 HANDOVER「十六、WebView 坑位总表」里的两条「待验」：</p>
 * <ul>
 *   <li>坑 7：产物摘要没变就不能重建子树（否则焦点/滚动/播放进度全丢）；</li>
 *   <li>坑 14：释放必须走组件树遍历（裸 {@code removeAllViews} 会漏释放嵌套 WebView，
 *       渲染进程随滚动上涨）。</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
public class WebViewPitfallsTest {

    /** 记录 release 次数的假组件 View（模拟 HtmlWebView/播放器这类可释放组件）。 */
    static final class FakeReleasable extends View implements ComponentLifecycle.Releasable {
        int releases = 0;

        FakeReleasable(Context context) {
            super(context);
        }

        @Override
        public void releaseComponent() {
            releases += 1;
        }

        @Override
        public boolean isComponentReleased() {
            return releases > 0;
        }
    }

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    // ---------------- 坑 7：摘要一致就不重建 ----------------

    @Test
    public void digestIsStableForSameProducts() throws Exception {
        List<ComponentData> a = products("c1", "chart", "{\"v\":1}");
        List<ComponentData> b = products("c1", "chart", "{\"v\":1}");
        assertEquals(ProductCards.digestOf(a), ProductCards.digestOf(b));
    }

    @Test
    public void digestChangesWhenPropsChange() throws Exception {
        assertFalse(ProductCards.digestOf(products("c1", "chart", "{\"v\":1}"))
                .equals(ProductCards.digestOf(products("c1", "chart", "{\"v\":2}"))));
    }

    @Test
    public void digestIsOrderSensitive() throws Exception {
        List<ComponentData> ab = new ArrayList<>();
        ab.addAll(products("c1", "chart", "{}"));
        ab.addAll(products("c2", "table", "{}"));
        List<ComponentData> ba = new ArrayList<>();
        ba.addAll(products("c2", "table", "{}"));
        ba.addAll(products("c1", "chart", "{}"));
        assertFalse("顺序变化也要重建（不做 diff，避免产物被抹掉那类双写 bug）",
                ProductCards.digestOf(ab).equals(ProductCards.digestOf(ba)));
    }

    @Test
    public void unchangedRequiresMountedDigestAndChildren() throws Exception {
        LinearLayout host = new LinearLayout(ctx());
        String digest = "|c1:abc";

        assertFalse("空容器一律视为需要重建", ProductCards.unchanged(host, digest));

        host.addView(new View(ctx()));
        assertFalse("没记过摘要就不知道是否一致", ProductCards.unchanged(host, digest));

        ProductCards.markMounted(host, digest);
        assertTrue("摘要记录后应判定为未变化", ProductCards.unchanged(host, digest));
        assertFalse("摘要不同必须重建",
                ProductCards.unchanged(host, "|c1:def"));
    }

    @Test
    public void nullContainerIsSafe() {
        assertFalse(ProductCards.unchanged(null, "x"));
        ProductCards.markMounted(null, "x");
    }

    // ---------------- 坑 14：深度释放 ----------------

    @Test
    public void releaseAllWalksNestedTree() {
        LinearLayout root = new LinearLayout(ctx());
        FrameLayout middle = new FrameLayout(ctx());
        FakeReleasable deep = new FakeReleasable(ctx());
        FakeReleasable shallow = new FakeReleasable(ctx());

        middle.addView(deep);
        root.addView(shallow);
        root.addView(middle);

        ProductCards.releaseAll(root);

        assertEquals("嵌套两层的组件都要被释放", 1, deep.releases);
        assertEquals(1, shallow.releases);
        assertEquals("释放后容器必须清空", 0, root.getChildCount());
    }

    @Test
    public void releaseAllIsSafeOnEmptyOrNull() {
        ProductCards.releaseAll(null);
        LinearLayout empty = new LinearLayout(ctx());
        ProductCards.releaseAll(empty);
        assertEquals(0, empty.getChildCount());
    }

    @Test
    public void releaseRecursesThroughPlainViewGroups() {
        LinearLayout root = new LinearLayout(ctx());
        FrameLayout plain = new FrameLayout(ctx());
        FakeReleasable inside = new FakeReleasable(ctx());
        plain.addView(inside);
        root.addView(plain);

        ComponentLifecycle.release(root);

        assertEquals("中间是普通 ViewGroup 也要继续往下找", 1, inside.releases);
    }

    private static List<ComponentData> products(String id, String type, String propsJson)
            throws Exception {
        List<ComponentData> list = new ArrayList<>();
        ComponentData c = ComponentData.of(type, new JSONObject(propsJson));
        c.id = id;
        list.add(c);
        return list;
    }
}
