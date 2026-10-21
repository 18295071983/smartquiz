package com.oilquiz.app.ai.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@link MessageAttachmentAdapter} 的数据入口加固（2026-10-22）。
 *
 * <p>原来两处会崩/会脏：① DiffUtil 比较里 {@code oldItem.attachment.id.equals(...)} 裸解引用。
 * {@code Attachment.id} 是 {@code final} 且两个构造器都赋值，但类没有无参构造，
 * Gson 恢复历史会话时会绕过构造器（ObjectConstructor）→ id 为 null，绑定时 NPE；
 * ② {@code setAttachments(null)} 只 clear 内部列表、不派发更新，adapter 还挂在 RecyclerView 上时
 * 旧条目留在屏幕；③ 列表里混进 null 元素同样在下游解引用时崩。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class MessageAttachmentAdapterTest {

    @Test
    public void nullListClearsItemCount() {
        MessageAttachmentAdapter adapter = new MessageAttachmentAdapter();
        adapter.setAttachments(new ArrayList<>(Arrays.asList(att("a"), att("b"))));
        assertEquals(2, adapter.getItemCount());

        adapter.setAttachments(null);
        assertEquals("null 列表要真正清空（原来只 clear 不派发）", 0, adapter.getItemCount());
    }

    @Test
    public void nullEntriesInListAreSkipped() {
        MessageAttachmentAdapter adapter = new MessageAttachmentAdapter();
        List<ChatMessage.Attachment> withNull = new ArrayList<>();
        withNull.add(att("a"));
        withNull.add(null);
        adapter.setAttachments(withNull); // 不抛
        assertEquals("null 元素应被跳过", 1, adapter.getItemCount());
    }

    /** 附件对象在、但 id 为 null（Gson 绕过构造器恢复出的历史形状）：比较时不许 NPE。 */
    @Test
    public void nullAttachmentIdDoesNotCrashDiff() {
        MessageAttachmentAdapter adapter = new MessageAttachmentAdapter();
        adapter.setAttachments(new ArrayList<>(Arrays.asList(attWithNullId())));
        assertEquals(1, adapter.getItemCount());

        // 再绑一次：DiffUtil 会拿新旧两条走 areItemsTheSame / areContentsTheSame
        adapter.setAttachments(new ArrayList<>(Arrays.asList(attWithNullId())));
        assertEquals(1, adapter.getItemCount());
    }

    @Test
    public void sameIdRebindKeepsOneItem() {
        MessageAttachmentAdapter adapter = new MessageAttachmentAdapter();
        ChatMessage.Attachment a = att("same");
        adapter.setAttachments(new ArrayList<>(Arrays.asList(a)));
        ChatMessage.Attachment b = att("same");
        b.status = ChatMessage.AttachmentStatus.COMPLETED;
        adapter.setAttachments(new ArrayList<>(Arrays.asList(b)));
        assertEquals(1, adapter.getItemCount());
    }

    // ---- helpers ----

    private static ChatMessage.Attachment att(String ignored) {
        return new ChatMessage.Attachment("image", "file:///sdcard/a.png", "a.png");
    }

    /** 造出 Gson 恢复历史数据时的形状：对象存在但 final id 为 null。 */
    private static ChatMessage.Attachment attWithNullId() {
        ChatMessage.Attachment a = att("x");
        try {
            java.lang.reflect.Field f = ChatMessage.Attachment.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(a, null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("测试前提不成立：无法把 id 置 null", e);
        }
        assertNull(a.id);
        return a;
    }
}
