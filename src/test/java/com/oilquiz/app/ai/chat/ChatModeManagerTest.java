package com.oilquiz.app.ai.chat;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28})
public class ChatModeManagerTest {
    
    private ChatModeManager modeManager;
    
    @Before
    public void setUp() {
        modeManager = new ChatModeManager(
            org.robolectric.RuntimeEnvironment.application
        );
    }
    
    @Test
    public void testDefaultModeIsNormal() {
        assertEquals(ChatModeManager.ChatMode.NORMAL, modeManager.getCurrentMode());
    }
    
    @Test
    public void testAutoModeEnabledByDefault() {
        assertTrue(modeManager.isAutoModeEnabled());
    }
    
    @Test
    public void testManualModeSwitch() {
        modeManager.setManualMode(ChatModeManager.ChatMode.DEEP_THINKING);
        
        assertEquals(ChatModeManager.ChatMode.DEEP_THINKING, modeManager.getCurrentMode());
        assertFalse(modeManager.isAutoModeEnabled());
    }
    
    @Test
    public void testAllModesAccessible() {
        ChatModeManager.ChatMode[] modes = ChatModeManager.ChatMode.values();
        assertEquals(4, modes.length); // NORMAL, DEEP_THINKING, AGENT, CREATIVE
        
        boolean hasNormal = false, hasDeep = false, hasAgent = false, hasCreative = false;
        for (ChatModeManager.ChatMode mode : modes) {
            switch (mode) {
                case NORMAL: hasNormal = true; break;
                case DEEP_THINKING: hasDeep = true; break;
                case AGENT: hasAgent = true; break;
                case CREATIVE: hasCreative = true; break;
            }
        }
        
        assertTrue(hasNormal);
        assertTrue(hasDeep);
        assertTrue(hasAgent);
        assertTrue(hasCreative);
    }
    
    @Test
    public void testModeDisplayName() {
        assertEquals("普通", ChatModeManager.ChatMode.NORMAL.displayName);
        assertEquals("深度思考", ChatModeManager.ChatMode.DEEP_THINKING.displayName);
        assertEquals("Agent", ChatModeManager.ChatMode.AGENT.displayName);
        assertEquals("创作", ChatModeManager.ChatMode.CREATIVE.displayName);
    }
    
    @Test
    public void testDetermineModeWithSimpleQuestion() {
        ChatModeManager.ChatMode mode = modeManager.determineMode("你好");
        
        assertNotNull(mode);
        if (modeManager.isAutoModeEnabled()) {
            assertEquals(ChatModeManager.ChatMode.NORMAL, mode);
        }
    }
    
    @Test
    public void testDetermineModeWithComplexQuestion() {
        String complexQuestion = "请分析当前市场趋势，并给出详细的投资建议，包括风险评估和收益预测";
        ChatModeManager.ChatMode mode = modeManager.determineMode(complexQuestion);
        
        assertNotNull(mode);
    }
    
    @Test
    public void testSetGeneratingState() {
        assertFalse(modeManager.isGenerating());
        
        modeManager.setGeneratingState(true);
        assertTrue(modeManager.isGenerating());
        
        modeManager.setGeneratingState(false);
        assertFalse(modeManager.isGenerating());
    }
    
    @Test
    public void testModeChangeListener() {
        final boolean[] callbackFired = {false};
        final ChatModeManager.ChatMode[] capturedMode = new ChatModeManager.ChatMode[1];
        final boolean[] capturedAuto = new boolean[1];
        
        modeManager.setOnModeChangeListener((newMode, isAuto) -> {
            callbackFired[0] = true;
            capturedMode[0] = newMode;
            capturedAuto[0] = isAuto;
        });
        
        modeManager.setManualMode(ChatModeManager.ChatMode.AGENT);
        
        assertTrue(callbackFired[0]);
        assertEquals(ChatModeManager.ChatMode.AGENT, capturedMode[0]);
        assertFalse(capturedAuto[0]);
    }
}
