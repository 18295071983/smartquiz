package com.oilquiz.app.ai.agent.online;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class OnlineToolIntentMapTest {

    @Test
    public void systemIntentExposesBothSystemTools() {
        List<String> tools = OnlineToolManager.getIntentToToolMap().get("system");
        assertTrue(tools != null && tools.contains("system_resource"));
        assertTrue(tools != null && tools.contains("system_connect"));
    }
}
