package com.oilquiz.app.ai.chat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 对话页「🔍 搜索」chip 的一键发问（2026-11-02）。
 *
 * <p>用户口径："我记得 ai 对话界面中的网络搜索 chip，你利用起来啊"。选择的是**一键发问**：
 * 点一下 = 把输入框里的问题交给模型并置位"本轮联网"，长按仍是原来的参数表单。
 * 这个信号必须**只对这一条消息生效**，否则一次点击会让之后每条提问都白烧一次搜索额度。</p>
 */
public class ChatSearchChipTest {

    private static String read(String p) throws Exception {
        return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8);
    }

    /** 信号本身：取走即清、可复位。 */
    @Test
    public void hintIsOneShot() {
        LiveSearchHint.clear();
        assertFalse("默认没有信号", LiveSearchHint.isPending());

        LiveSearchHint.request();
        assertTrue("点了之后处于待命", LiveSearchHint.isPending());
        assertTrue("第一次消费命中", LiveSearchHint.consume());
        assertFalse("消费后立刻失效", LiveSearchHint.consume());
        assertFalse(LiveSearchHint.isPending());
    }

    /** 过期信号不生效（防止"点了 chip 但没发出去"把标记留到很久以后的下一轮）。 */
    @Test
    public void staleHintExpires() throws Exception {
        LiveSearchHint.clear();
        LiveSearchHint.request();
        java.lang.reflect.Field f = LiveSearchHint.class.getDeclaredField("REQUESTED_AT");
        f.setAccessible(true);
        java.util.concurrent.atomic.AtomicLong at =
                (java.util.concurrent.atomic.AtomicLong) f.get(null);
        at.set(System.currentTimeMillis() - LiveSearchHint.TTL_MS - 1000);
        assertFalse("过期信号不算数", LiveSearchHint.isPending());
        assertFalse("过期信号也不能被消费", LiveSearchHint.consume());
        LiveSearchHint.clear();
    }

    /** chip 接线：短按=一键发问，长按=表单；空输入时宿主退化成表单，点击不变死路。 */
    @Test
    public void chipShortPressSendsLongPressOpensForm() throws Exception {
        String chip = read("src/main/java/com/oilquiz/app/ai/chat/component/QuickToolChipModule.java");
        assertTrue("搜索 chip 必须走专用绑定", chip.contains("bindSearchChip(chipSearch)"));
        assertTrue("短按 = onSearchAsk", chip.contains("chip.setOnClickListener(v -> callback.onSearchAsk())"));
        assertTrue("长按 = 参数表单", chip.contains("setOnLongClickListener") && chip.contains("onToolGuide"));

        String act = read("src/main/java/com/oilquiz/app/ui/activity/AIChatActivity.java");
        assertTrue("宿主必须实现一键发问", act.contains("public void onSearchAsk()"));
        assertTrue("发问前要置位一次性信号", act.contains("LiveSearchHint.request()"));
        assertTrue("发问走既有发送路径", act.contains("LiveSearchHint.request();\n") || act.contains("sendMessage();"));
        assertTrue("空输入退化成表单而不是死点", act.contains("showToolGuideDialog(\"network_search\")"));
    }

    /** 两条执行路径都要消费同一信号：在线闸门 + 本地引擎的本轮提示。 */
    @Test
    public void bothExecutionPathsHonourTheChip() throws Exception {
        String online = read("src/main/java/com/oilquiz/app/ai/service/OnlineInferenceService.java");
        assertTrue("在线侧要消费信号并越过意图闸",
                online.contains("LiveSearchHint.consume()")
                        && online.contains("!userForcedSearch && !needsLiveInfo(prompt)"));

        String local = read("src/main/java/com/oilquiz/app/ai/agent/software/engine/AgentLoopEngine.java");
        assertTrue("本地引擎要消费信号", local.contains("LiveSearchHint.consume()"));
        assertTrue("本地要在本轮 user 尾部告诉模型先联网", local.contains("【本轮联网】"));
        assertTrue("提示里要指定默认 action=ask", local.contains("默认 action=ask"));
    }
}
