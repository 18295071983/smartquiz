package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * reminder → agenda 的条目转换（2026-10-24）：fireAt 拆成日期/时间，重复规则进 desc。
 */
public class ReminderAgendaProducerTest {

    @Test
    public void reminderEntryBecomesAgendaItem() {
        Calendar cal = Calendar.getInstance();
        cal.set(2026, Calendar.OCTOBER, 24, 9, 5, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long fireAt = cal.getTimeInMillis();

        ReminderScheduler.ReminderEntry entry = new ReminderScheduler.ReminderEntry(
                "r1", "晨会", fireAt, fireAt, "daily", "");
        List<Map<String, Object>> items = ReminderTool.agendaItems(Arrays.asList(entry));

        assertEquals(1, items.size());
        Map<String, Object> item = items.get(0);
        assertEquals(new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime()),
                item.get("date"));
        assertEquals(new SimpleDateFormat("HH:mm", Locale.US).format(cal.getTime()),
                item.get("time"));
        assertEquals("晨会", item.get("title"));
        assertEquals("todo", item.get("status"));
        String desc = String.valueOf(item.get("desc"));
        assertTrue(desc.contains("ID: r1"));
        assertTrue(desc.contains("每日"));
    }

    @Test
    public void agendaInfoCarriesTitleAndItems() {
        Calendar cal = Calendar.getInstance();
        cal.set(2026, Calendar.OCTOBER, 24, 18, 30, 0);
        cal.set(Calendar.MILLISECOND, 0);
        ReminderScheduler.ReminderEntry entry = new ReminderScheduler.ReminderEntry(
                "r2", "交作业", cal.getTimeInMillis(), cal.getTimeInMillis(), "none", "");

        Map<String, Object> info = ReminderTool.agendaInfo("提醒日程", Arrays.asList(entry));
        assertEquals("提醒日程", info.get("agenda_title"));
        assertNotNull(info.get("agenda_items"));
    }
}
