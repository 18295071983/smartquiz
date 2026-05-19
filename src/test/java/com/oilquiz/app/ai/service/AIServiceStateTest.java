package com.oilquiz.app.ai.service;

import org.junit.Test;
import org.junit.Before;
import org.junit.After;

import static org.junit.Assert.*;

public class AIServiceStateTest {

    private AIServiceState serviceState;

    @Before
    public void setUp() {
        serviceState = new AIServiceState();
    }

    @After
    public void tearDown() {
    }

    @Test
    public void testInitialState() {
        assertEquals(AIServiceState.ServiceStage.UNINITIALIZED, 
            serviceState.getCurrentStage());
        assertEquals(0, serviceState.getProgressPercent());
        assertNotNull(serviceState.getStageMessage());
    }

    @Test
    public void testStateTransition() throws InterruptedException {
        serviceState.startTiming();
        
        serviceState.setCurrentStage(
            AIServiceState.ServiceStage.NATIVE_LIBRARY_LOADING, 
            "加载原生库中...", 
            10
        );
        
        assertEquals(AIServiceState.ServiceStage.NATIVE_LIBRARY_LOADING, 
            serviceState.getCurrentStage());
        assertEquals(10, serviceState.getProgressPercent());
        assertEquals("加载原生库中...", serviceState.getStageMessage());
    }

    @Test
    public void testElapsedTime() throws InterruptedException {
        serviceState.startTiming();
        
        Thread.sleep(50);
        
        long elapsed = serviceState.getElapsedTimeMs();
        assertTrue("Elapsed time should be >= 50ms", elapsed >= 50);
    }

    @Test
    public void testDefaultProgressForStage() {
        serviceState.setCurrentStage(AIServiceState.ServiceStage.MODEL_LOADING, null);
        
        assertEquals(40, serviceState.getProgressPercent());
    }

    @Test
    public void testProgressClamping() {
        serviceState.setCurrentStage(
            AIServiceState.ServiceStage.MODEL_LOADING, 
            "测试", 
            -10
        );
        assertEquals(0, serviceState.getProgressPercent());
        
        serviceState.setCurrentStage(
            AIServiceState.ServiceStage.MODEL_LOADING, 
            "测试", 
            150
        );
        assertEquals(100, serviceState.getProgressPercent());
    }

    @Test
    public void testIsInitialized() {
        serviceState.setCurrentStage(
            AIServiceState.ServiceStage.INITIALIZED, 
            "完成", 
            100
        );
        assertTrue(serviceState.isInitialized());
        assertFalse(serviceState.isLoading());
    }

    @Test
    public void testIsError() {
        serviceState.setError("Test error");
        
        assertTrue(serviceState.isError());
        assertEquals("Test error", serviceState.getErrorMessage());
    }

    @Test
    public void testIsLoading() {
        assertFalse(serviceState.isLoading());
        
        serviceState.setCurrentStage(
            AIServiceState.ServiceStage.MODEL_LOADING, 
            "加载中", 
            50
        );
        assertTrue(serviceState.isLoading());
    }

    @Test
    public void testCurrentModelName() {
        assertNull(serviceState.getCurrentModelName());
        
        serviceState.setCurrentModelName("Qwen-2.5-7B");
        assertEquals("Qwen-2.5-7B", serviceState.getCurrentModelName());
    }

    @Test
    public void testEstimatedTime() {
        assertEquals(0, serviceState.getEstimatedTimeMs());
        
        serviceState.setEstimatedTimeMs(15000);
        assertEquals(15000, serviceState.getEstimatedTimeMs());
    }

    @Test
    public void testStageOrder() {
        AIServiceState.ServiceStage[] stages = AIServiceState.ServiceStage.values();
        
        assertEquals(9, stages.length);
        assertEquals(AIServiceState.ServiceStage.UNINITIALIZED.ordinal(), 0);
        assertEquals(AIServiceState.ServiceStage.INITIALIZED.ordinal(), 7);
        assertEquals(AIServiceState.ServiceStage.ERROR.ordinal(), 8);
    }

    @Test
    public void testToString() {
        String str = serviceState.toString();
        assertNotNull(str);
        assertTrue(str.contains("AIServiceState"));
    }
}
