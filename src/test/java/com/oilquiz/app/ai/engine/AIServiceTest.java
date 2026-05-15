package com.oilquiz.app.ai.engine;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;

@RunWith(AndroidJUnit4.class)
public class AIServiceTest {

    private AIService aiService;

    @Before
    public void setUp() {
        aiService = AIService.getInstance();
    }

    @Test
    public void testSingletonPattern() {
        AIService instance1 = AIService.getInstance();
        AIService instance2 = AIService.getInstance();
        
        assertSame("getInstance should return same instance", 
                instance1, instance2);
    }

    @Test
    public void testInitialState_NotGenerating() {
        assertFalse("Should not be generating initially", 
                aiService.isGenerating());
    }

    @Test
    public void testStopGeneration_WhenNotGenerating() {
        try {
            aiService.stopGeneration();
            assertTrue("Should handle stop gracefully when not generating", true);
        } catch (Exception e) {
            fail("Should not throw exception when stopping non-generation");
        }
    }

    @Test
    public void testGenerateStream_NullPrompt() {
        try {
            aiService.generateStream(null, null, 1000, null);
            fail("Should throw exception for null prompt");
        } catch (IllegalArgumentException e) {
            assertNotNull("Exception message should not be null", 
                    e.getMessage());
        }
    }

    @Test
    public void testGenerateStream_EmptyPrompt() {
        try {
            aiService.generateStream("", null, 1000, null);
            fail("Should throw exception for empty prompt");
        } catch (IllegalArgumentException e) {
            assertNotNull("Exception message should not be null",
                    e.getMessage());
        }
    }

    @Test
    public void testGenerateStream_NegativeMaxTokens() {
        try {
            aiService.generateStream("test prompt", null, -100, null);
            fail("Should throw exception for negative maxTokens");
        } catch (IllegalArgumentException e) {
            assertNotNull("Exception message should not be null",
                    e.getMessage());
        }
    }

    @Test
    public void testGenerateStream_ZeroMaxTokens() {
        try {
            aiService.generateStream("test prompt", null, 0, null);
            fail("Should throw exception for zero maxTokens");
        } catch (IllegalArgumentException e) {
            assertNotNull("Exception message should not be null",
                    e.getMessage());
        }
    }

    @Test
    public void testIsReady_DefaultState() {
        boolean ready = aiService.isReady();
        
        assertTrue("AIService should be ready by default", ready);
    }
}
