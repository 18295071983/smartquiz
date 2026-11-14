package com.oilquiz.app.ai.chat.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 远程顶栏副标题的拼装口径（2026-11-14，M8-P0-2）。
 *
 * <p>这段文案是"这条会话连着电脑"最直接的证据：格式一漂，用户就分不清自己是在用手机模型
 * 还是在用电脑。所以把它抽成纯函数并钉住四种形态（有/无待处理、有/未选会话、阶段为空）。</p>
 */
public class RemoteSubtitleTest {

    @Test
    public void fullFormWithPending() {
        assertEquals("电脑 · 生成中 0:12｜跟随「修复 ask」 · 待处理 2",
                ChatUiV2Chrome.remoteSubtitle("生成中 0:12", "跟随「修复 ask」", 2));
    }

    @Test
    public void noPendingSectionWhenZero() {
        assertEquals("电脑 · 空闲｜跟随「会话」",
                ChatUiV2Chrome.remoteSubtitle("空闲", "跟随「会话」", 0));
        assertEquals("电脑 · 空闲｜跟随「会话」",
                ChatUiV2Chrome.remoteSubtitle("空闲", "跟随「会话」", -3));
    }

    @Test
    public void emptyPhaseFallsBackToIdle() {
        assertEquals("电脑 · 空闲｜跟随「会话」",
                ChatUiV2Chrome.remoteSubtitle("", "跟随「会话」", 0));
        assertEquals("电脑 · 空闲｜跟随「会话」",
                ChatUiV2Chrome.remoteSubtitle(null, "跟随「会话」", 0));
    }

    @Test
    public void unselectedSessionSaysSo() {
        assertEquals("电脑 · 空闲｜未选会话",
                ChatUiV2Chrome.remoteSubtitle("空闲", "", 0));
        assertEquals("电脑 · 空闲｜未选会话 · 待处理 1",
                ChatUiV2Chrome.remoteSubtitle("空闲", null, 1));
    }
}
