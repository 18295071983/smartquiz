package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Parallel tool products must not be drained by the wrong tool. */
public class ComponentCollectorTest {

    @Test
    public void parallelToolsKeepProducerOwnership() {
        ComponentCollector.clear();
        ComponentData weather = new ComponentData("info_card", null);
        ComponentData location = new ComponentData("location", null);
        ComponentCollector.collectForTool("ai_weather", weather);
        ComponentCollector.collectForTool("location", location);

        java.util.List<ComponentData> first = ComponentCollector.drainForTool("ai_weather");
        assertNotNull(first);
        assertEquals(1, first.size());
        assertEquals(weather, first.get(0));
        assertNull("weather completion must not take location's product",
                ComponentCollector.drainForTool("ai_weather"));

        java.util.List<ComponentData> second = ComponentCollector.drainForTool("location");
        assertNotNull(second);
        assertEquals(location, second.get(0));
        assertNull(ComponentCollector.drain());
    }

    @Test
    public void emptyProducerStillDrainsForCompatibility() {
        ComponentCollector.clear();
        ComponentData legacy = new ComponentData("info_card", null);
        ComponentCollector.collect(legacy);
        java.util.List<ComponentData> out = ComponentCollector.drainForTool("any_tool");
        assertNotNull(out);
        assertEquals(legacy, out.get(0));
    }
}
