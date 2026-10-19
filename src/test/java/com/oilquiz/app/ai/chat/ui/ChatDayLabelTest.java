package com.oilquiz.app.ai.chat.ui;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 消息流日期分隔的判定与文案（ChatAdapter 的 v2 分隔行、空状态"最近"列表共用同一口径）。
 *
 * <p>时间戳全部由"固定的 now 基准 ± 天/小时"算出来，不依赖真实当前时间，
 * 所以凌晨跑测试也不会偶发失败。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class ChatDayLabelTest {

    private Context context;
    /** 基准时刻：2026-10-19 14:32:00 */
    private long now;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.OCTOBER, 19, 14, 32, 0);
        now = c.getTimeInMillis();
    }

    private long at(int year, int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month, day, hour, minute, 0);
        return c.getTimeInMillis();
    }

    @Test
    public void sameDayIsCalendarDayNotTwentyFourHours() {
        assertTrue("同一天内应判为同一天",
                ChatDayLabel.isSameDay(at(2026, Calendar.OCTOBER, 19, 0, 30),
                        at(2026, Calendar.OCTOBER, 19, 23, 59)));
        assertFalse("跨零点只差一小时，但属于两天",
                ChatDayLabel.isSameDay(at(2026, Calendar.OCTOBER, 19, 23, 30),
                        at(2026, Calendar.OCTOBER, 20, 0, 30)));
    }

    @Test
    public void dividerShowsTodayYesterdayAndDateWithTime() {
        assertEquals("今天 14:32", ChatDayLabel.divider(context, now, now));
        assertEquals("昨天 09:10",
                ChatDayLabel.divider(context, at(2026, Calendar.OCTOBER, 18, 9, 10), now));
        // 更早只到日，但仍带时分
        String older = ChatDayLabel.divider(context, at(2026, Calendar.OCTOBER, 12, 21, 3), now);
        assertTrue("应含 M月d日：" + older, older.startsWith("10月12日"));
        assertTrue("应带时分：" + older, older.endsWith("21:03"));
    }

    @Test
    public void listTimeCollapsesTodayToTimeOnly() {
        // 基准一律显式传入：不传就等于让用例依赖"今天真的是今天"，明天就红
        assertEquals("14:32", ChatDayLabel.listTime(context, now, now));
        assertEquals("昨天 09:10",
                ChatDayLabel.listTime(context, at(2026, Calendar.OCTOBER, 18, 9, 10), now));
        assertEquals("10月12日",
                ChatDayLabel.listTime(context, at(2026, Calendar.OCTOBER, 12, 21, 3), now));
    }

    @Test
    public void dividerAndListTimeShareTheSameDayBoundary() {
        // 过了零点，"今天"就必须换人：00:05 那条属于今天，昨晚 23:30 那条才是昨天。
        // 只看时间差的话两条只差 35 分钟，很容易判成同一天 —— 分隔行的位置就是这么错的。
        long pastMidnight = at(2026, Calendar.OCTOBER, 20, 0, 5);
        long beforeMidnight = at(2026, Calendar.OCTOBER, 19, 23, 30);
        assertEquals("今天 00:05", ChatDayLabel.divider(context, pastMidnight, pastMidnight));
        assertEquals("昨天 23:30", ChatDayLabel.divider(context, beforeMidnight, pastMidnight));
        assertEquals("00:05", ChatDayLabel.listTime(context, pastMidnight, pastMidnight));
        assertEquals("昨天 23:30", ChatDayLabel.listTime(context, beforeMidnight, pastMidnight));
        assertFalse("跨零点两条不属于同一日历日",
                ChatDayLabel.isSameDay(beforeMidnight, pastMidnight));
    }
}
