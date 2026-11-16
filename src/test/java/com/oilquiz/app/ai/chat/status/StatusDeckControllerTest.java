package com.oilquiz.app.ai.chat.status;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 甲板行选择（2026-11-13，M1 / 验收 A2）。
 *
 * <p>以前这套规则埋在 {@code AIChatActivity.renderDockSlots} 里，只能靠真机截图看。
 * 现在抽成纯函数 + FakeHost，直接钉住"进度条可见只留 1 条 / 不可见留 2 条 / 断连清空"。</p>
 */
public class StatusDeckControllerTest {

    private static DockSlotBus.Slot slot(String id, int priority) {
        return new DockSlotBus.Slot(id, priority, "标题-" + id, "详情-" + id, "", false);
    }

    private static final class FakeHost implements StatusDeckController.Host {
        final List<List<DockSlotBus.Slot>> renders = new ArrayList<>();

        @Override
        public void renderSlots(List<DockSlotBus.Slot> rows) {
            renders.add(new ArrayList<>(rows));
        }

        List<DockSlotBus.Slot> last() {
            return renders.isEmpty() ? new ArrayList<>() : renders.get(renders.size() - 1);
        }
    }

    private static List<String> ids(List<DockSlotBus.Slot> rows) {
        List<String> out = new ArrayList<>();
        for (DockSlotBus.Slot s : rows) out.add(s.id);
        return out;
    }

    @Test
    public void progressVisibleKeepsOnlyOneRow() {
        List<DockSlotBus.Slot> slots = Arrays.asList(slot("jobs", 50), slot("goal", 30), slot("mcp", 60));
        assertEquals(Arrays.asList("goal"), ids(StatusDeckController.select(slots, true, 1, 2)));
    }

    @Test
    public void progressHiddenKeepsTwoRows() {
        List<DockSlotBus.Slot> slots = Arrays.asList(slot("jobs", 50), slot("goal", 30), slot("mcp", 60));
        assertEquals(Arrays.asList("goal", "jobs"), ids(StatusDeckController.select(slots, false, 1, 2)));
    }

    @Test
    public void sortsByPriorityThenIdForStableOrder() {
        List<DockSlotBus.Slot> slots = Arrays.asList(slot("b", 10), slot("a", 10), slot("c", 5));
        assertEquals(Arrays.asList("c", "a", "b"), ids(StatusDeckController.select(slots, false, 3, 3)));
    }

    @Test
    public void dropsNullAndEmptyIds() {
        List<DockSlotBus.Slot> slots = Arrays.asList(null, slot("", 1), slot("ok", 9));
        assertEquals(Arrays.asList("ok"), ids(StatusDeckController.select(slots, false, 3, 3)));
    }

    @Test
    public void zeroLimitHidesEverything() {
        assertTrue(StatusDeckController.select(Arrays.asList(slot("x", 1)), false, 0, 0).isEmpty());
    }

    /**
     * 默认值口径（2026-11-16 订正）：{@code e7a0560b} 把甲板默认行数提到 2/5（配合 5 个 dock 插槽），
     * 控制器字段直接吃 {@link DshUiCatalogLoader#DEFAULT_MAX_*}。这条钉住"没配置时到底几行"，
     * 避免再出现"测试按 1 行、代码按 2 行"的漂移（旧断言就是这么红的）。
     */
    @Test
    public void defaultsFollowCatalogDefaults() {
        StatusDeckController c = new StatusDeckController(new FakeHost());
        assertEquals(DshUiCatalogLoader.DEFAULT_MAX_WITH_PROGRESS, c.maxWithProgress());
        assertEquals(DshUiCatalogLoader.DEFAULT_MAX_WITHOUT_PROGRESS, c.maxWithoutProgress());
        assertEquals("有进度条时的默认行数（产品口径 2 行）", 2, c.maxWithProgress());
        assertEquals("无进度条时的默认行数（产品口径 5 行）", 5, c.maxWithoutProgress());
    }

    /**
     * 调度路径：进度条可见/不可见时的取舍、断连清空。
     *
     * <p>显式 {@code applyCatalog(1/2)}：这条测的是控制器逻辑，不该随产品默认行数变化而红
     * ——"默认几行"由 {@link #defaultsFollowCatalogDefaults()} 单独钉。</p>
     */
    @Test
    public void controllerRendersOnSlotsChangedAndClears() {
        FakeHost host = new FakeHost();
        StatusDeckController c = new StatusDeckController(host);
        c.applyCatalog(DshUiCatalogLoader.parse(
                "{\"version\":1,\"deck\":{\"max_rows_with_progress\":1,\"max_rows_without_progress\":2}}"));

        c.onSlotsChanged(Arrays.asList(slot("jobs", 50), slot("goal", 30)), false);
        assertEquals(Arrays.asList("goal", "jobs"), ids(host.last()));

        c.onSlotsChanged(Arrays.asList(slot("jobs", 50), slot("goal", 30)), true);
        assertEquals(Arrays.asList("goal"), ids(host.last()));

        c.clear();
        assertTrue("断连/切会话应渲染空列表", host.last().isEmpty());
    }

    @Test
    public void catalogOverridesLimits() {
        FakeHost host = new FakeHost();
        StatusDeckController c = new StatusDeckController(host);
        c.applyCatalog(DshUiCatalogLoader.parse(
                "{\"version\":1,\"deck\":{\"max_rows_with_progress\":2,\"max_rows_without_progress\":3}}"));

        assertEquals(2, c.maxWithProgress());
        assertEquals(3, c.maxWithoutProgress());
        c.onSlotsChanged(Arrays.asList(slot("a", 1), slot("b", 2), slot("c", 3)), true);
        assertEquals(Arrays.asList("a", "b"), ids(host.last()));
    }
}
