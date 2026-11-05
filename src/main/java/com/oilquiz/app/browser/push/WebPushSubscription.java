package com.oilquiz.app.browser.push;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条网页后台提醒订阅。
 *
 * <p>页面提供一个 HTTP 收件箱地址，App 每 {@link #intervalMinutes} 分钟去拉一次，
 * 拉到的消息由 {@link WebPushNotifier} 弹系统通知。订阅本身不依赖 FCM/厂商通道。</p>
 */
public final class WebPushSubscription {
    public String id;
    public String endpoint;
    /** GET / POST */
    public String method = "GET";
    /** 请求头（JSON 对象字符串），用于 Authorization 等 */
    public String headersJson = "{}";
    /** POST 请求体，≤64KB */
    public String body = "";
    /** 轮询间隔，分钟（15..1440） */
    public int intervalMinutes = 15;
    /** 点击通知打开的地址模板，支持 {id}/{title}/{url} */
    public String openUrl = "";
    /** 注册时的页面地址，作为打开地址的最后兜底 */
    public String pageUrl = "";
    public String titleField = "title";
    public String bodyField = "body";
    public String idField = "id";
    public String urlField = "url";
    public long createdAt;
    public long lastPollAt;
    /** 已通知过的消息 id（环形裁剪，最多 100 条） */
    public List<String> seenIds = new ArrayList<>();
}
