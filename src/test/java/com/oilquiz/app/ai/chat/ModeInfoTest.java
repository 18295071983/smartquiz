package com.oilquiz.app.ai.chat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28})
public class ModeInfoTest {
    
    @Test
    public void testCreateNormalMode() {
        ModeInfo normalMode = ModeInfo.createNormalMode();
        
        assertNotNull(normalMode);
        assertEquals("normal", normalMode.id);
        assertEquals("普通模式", normalMode.name);
        assertEquals("💬", normalMode.icon);
        assertNotNull(normalMode.description);
        assertTrue(normalMode.isDefault);
    }
    
    @Test
    public void testCreateDeepThinkingMode() {
        ModeInfo deepMode = ModeInfo.createDeepThinkingMode();
        
        assertNotNull(deepMode);
        assertEquals("deep", deepMode.id);
        assertEquals("深度思考", deepMode.name);
        assertEquals("🧠", deepMode.icon);
        assertNotNull(deepMode.description);
        assertFalse(deepMode.isDefault);
    }
    
    @Test
    public void testCreateAgentMode() {
        ModeInfo agentMode = ModeInfo.createAgentMode();
        
        assertNotNull(agentMode);
        assertEquals("agent", agentMode.id);
        assertEquals("Agent模式", agentMode.name);
        assertEquals("🤖", agentMode.icon);
        assertNotNull(agentMode.description);
        assertFalse(agentMode.isDefault);
    }
    
    @Test
    public void testCreateCreativeMode() {
        ModeInfo creativeMode = ModeInfo.createCreativeMode();
        
        assertNotNull(creativeMode);
        assertEquals("creative", creativeMode.id);
        assertEquals("创作模式", creativeMode.name);
        assertEquals("✍️", creativeMode.icon);
        assertNotNull(creativeMode.description);
        assertFalse(creativeMode.isDefault);
    }
    
    @Test
    public void testGetDefaultModes() {
        List<ModeInfo> modes = ModeInfo.getDefaultModes();
        
        assertNotNull(modes);
        assertEquals(4, modes.size());
        
        boolean hasNormal = false, hasDeep = false, hasAgent = false, hasCreative = false;
        
        for (ModeInfo mode : modes) {
            switch (mode.id) {
                case "normal": 
                    hasNormal = true;
                    assertTrue(mode.isDefault);
                    break;
                case "deep": 
                    hasDeep = true; 
                    break;
                case "agent": 
                    hasAgent = true; 
                    break;
                case "creative": 
                    hasCreative = true; 
                    break;
            }
            
            assertNotNull(mode.name);
            assertNotNull(mode.icon);
            assertNotNull(mode.description);
            assertFalse(mode.icon.isEmpty());
            assertFalse(mode.name.isEmpty());
            assertFalse(mode.description.isEmpty());
        }
        
        assertTrue(hasNormal);
        assertTrue(hasDeep);
        assertTrue(hasAgent);
        assertTrue(hasCreative);
    }
    
    @Test
    public void testModeUniqueness() {
        List<ModeInfo> modes = ModeInfo.getDefaultModes();
        
        for (int i = 0; i < modes.size(); i++) {
            for (int j = i + 1; j < modes.size(); j++) {
                assertNotEquals(modes.get(i).id, modes.get(j).id);
            }
        }
    }
    
    @Test
    public void testModeIconsAreValidEmoji() {
        List<ModeInfo> modes = ModeInfo.getDefaultModes();
        
        String[] expectedEmojis = {"💬", "🧠", "🤖", "✍️"};
        
        for (int i = 0; i < modes.size(); i++) {
            assertEquals(expectedEmojis[i], modes.get(i).icon);
        }
    }
}
