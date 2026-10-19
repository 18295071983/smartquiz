package com.oilquiz.app.ai.chat.ui;

import android.content.Context;

import com.oilquiz.app.R;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * 聊天流里的"日期"文案（demo {@code .day}：居中一行 今天 14:32 / 昨天 09:10 / 10月17日 21:03）。
 *
 * <p>为什么单独成类：日期分组的判定与文案口径在好几处要用——消息流按天插分隔、
 * 空状态"最近"列表要显示"今天→HH:mm、昨天→昨天 HH:mm、更早→M月d日"。
 * 原来这两套各写了一份 {@code isSameDay} + {@code SimpleDateFormat}，
 * 抽到这里统一，避免同一天在不同界面显示成两种样子。</p>
 *
 * <p>跨天判定用日历日（{@link Calendar#DAY_OF_YEAR} + 年 + era），
 * 不是"相隔 24 小时"——凌晨 00:30 的消息和前一天 23:30 的消息隔了 1 小时，
 * 但它们属于两天，分隔行必须出现在后者之后。</p>
 */
public final class ChatDayLabel {

    private ChatDayLabel() {
    }

    /** 同一日历日（含era，跨公元前/后安全） */
    public static boolean isSameDay(long a, long b) {
        Calendar ca = Calendar.getInstance();
        ca.setTimeInMillis(a);
        Calendar cb = Calendar.getInstance();
        cb.setTimeInMillis(b);
        return isSameDay(ca, cb);
    }

    /** 同一日历日 */
    public static boolean isSameDay(Calendar a, Calendar b) {
        if (a == null || b == null) return false;
        return a.get(Calendar.ERA) == b.get(Calendar.ERA)
                && a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    /**
     * 消息流分隔行文案：今天/昨天带时分，更早只到日。
     * 单独取 {@code now} 参数是为了可测——跨天/跨月用例不必等到凌晨再跑。
     */
    public static String divider(Context ctx, long ts, long now) {
        if (ctx == null) return "";
        Locale locale = Locale.getDefault();
        String time = new SimpleDateFormat("HH:mm", locale).format(new Date(ts));
        Calendar item = Calendar.getInstance();
        item.setTimeInMillis(ts);
        Calendar today = Calendar.getInstance();
        today.setTimeInMillis(now);
        if (isSameDay(item, today)) {
            return ctx.getString(R.string.chat_v2_today) + " " + time;
        }
        Calendar yesterday = Calendar.getInstance();
        yesterday.setTimeInMillis(now);
        yesterday.add(Calendar.DAY_OF_MONTH, -1);
        if (isSameDay(item, yesterday)) {
            return ctx.getString(R.string.chat_v2_yesterday) + " " + time;
        }
        String day = new SimpleDateFormat(
                ctx.getString(R.string.chat_v2_date_format), locale).format(new Date(ts));
        return day + " " + time;
    }

    /** 消息流分隔行文案（用当前时间做基准） */
    public static String divider(Context ctx, long ts) {
        return divider(ctx, ts, System.currentTimeMillis());
    }

    /**
     * 列表条目时间（历史抽屉 / 空状态"最近"）：今天只显示时分，
     * 昨天显示"昨天 HH:mm"，更早只显示到日。
     *
     * <p>{@code now} 显式传入（与 {@link #divider(Context, long, long)} 同理由）：
     * 单测要能用固定基准日期断言，而不是"今天跑得过、明天跑就红"。</p>
     */
    public static String listTime(Context ctx, long ts, long now) {
        if (ctx == null) return "";
        Locale locale = Locale.getDefault();
        String time = new SimpleDateFormat("HH:mm", locale).format(new Date(ts));
        Calendar item = Calendar.getInstance();
        item.setTimeInMillis(ts);
        Calendar today = Calendar.getInstance();
        today.setTimeInMillis(now);
        if (isSameDay(item, today)) return time;
        Calendar yesterday = Calendar.getInstance();
        yesterday.setTimeInMillis(now);
        yesterday.add(Calendar.DAY_OF_MONTH, -1);
        if (isSameDay(item, yesterday)) {
            return ctx.getString(R.string.chat_v2_yesterday) + " " + time;
        }
        return new SimpleDateFormat(
                ctx.getString(R.string.chat_v2_date_format), locale).format(new Date(ts));
    }

    /** 列表条目时间（用当前时间做基准） */
    public static String listTime(Context ctx, long ts) {
        return listTime(ctx, ts, System.currentTimeMillis());
    }
}
