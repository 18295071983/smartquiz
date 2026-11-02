package com.oilquiz.app.ai.tool;

import com.oilquiz.app.ai.util.NetworkUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 抓取合规闸门：robots.txt 检查 + 按域名限速。
 *
 * <p>WHY(2026-11-02)：`docs/LICENSING_AND_COMPLIANCE.md` 里「抓取合规」两条一直是纸面规则
 * （robots、频率）。工具层不落实，等于没写。这里做成所有抓取入口（network_search / webpage_reader）
 * 共用的前置检查，规则本身可离线单测。</p>
 *
 * <p>策略：</p>
 * <ul>
 *   <li>robots.txt 按主机缓存（默认 1 小时），只认 {@code User-agent: *} 组；
 *       规则按「最长匹配优先、Allow 与 Disallow 等长时 Allow 胜」判定；</li>
 *   <li>robots.txt 取不到（404/超时）按"允许"处理，但显式 Disallow 一定拒绝；</li>
 *   <li>同一域名：并发 &le; 2，两次请求间隔 &ge; 1s；排队超过 8s 直接报错，不静默挂起。</li>
 * </ul>
 */
final class WebFetchPolicy {

    private WebFetchPolicy() {
    }

    /** 同一域名两次请求的最小间隔。 */
    static long MIN_INTERVAL_MS = 1000;
    /** 同一域名允许的并发数。 */
    static final int MAX_CONCURRENCY = 2;
    /** robots.txt 缓存时长。 */
    static final long ROBOTS_TTL_MS = 60 * 60 * 1000L;
    /** 排队等待上限：超过就报错，别把整条链路拖住。 */
    static final long MAX_WAIT_MS = 8000;

    // ==================== 对外 ====================

    /** 抓取前调用：先查 robots，再占并发与速率名额。 */
    static void beforeFetch(String url) throws Exception {
        String host = hostOf(url);
        if (host == null) return;
        checkRobots(url, host);
        acquire(host);
    }

    /** 抓取结束后释放并发名额（必须放在 finally）。 */
    static void afterFetch(String url) {
        String host = hostOf(url);
        if (host == null) return;
        HostState state = HOSTS.get(host);
        if (state != null) state.inFlight.release();
    }

    // ==================== robots ====================

    /** 站点规则：一组 Allow/Disallow 前缀。 */
    static final class RobotsRules {
        final List<String> allow = new ArrayList<>();
        final List<String> disallow = new ArrayList<>();

        /** 按「最长匹配优先、等长时 Allow 胜」判断路径是否可抓。 */
        boolean isAllowed(String path) {
            if (path == null || path.isEmpty()) path = "/";
            int bestLen = -1;
            boolean allowed = true;
            for (String rule : disallow) {
                if (rule.isEmpty()) continue;
                if (path.startsWith(rule) && rule.length() > bestLen) {
                    bestLen = rule.length();
                    allowed = false;
                }
            }
            for (String rule : allow) {
                if (rule.isEmpty()) continue;
                if (path.startsWith(rule) && rule.length() >= bestLen) {
                    bestLen = rule.length();
                    allowed = true;
                }
            }
            return allowed;
        }
    }

    private static final Map<String, RobotsCacheEntry> HOSTS_RULES = new ConcurrentHashMap<>();

    private static final class RobotsCacheEntry {
        final RobotsRules rules;
        final long fetchedAt;

        RobotsCacheEntry(RobotsRules rules, long fetchedAt) {
            this.rules = rules;
            this.fetchedAt = fetchedAt;
        }
    }

    static void checkRobots(String url, String host) throws Exception {
        if (url == null) return;
        String path = pathOf(url);
        RobotsRules rules = rulesFor(host);
        if (!rules.isAllowed(path)) {
            throw new Exception("该站点 robots.txt 禁止抓取该路径（合规检查，不是网络错误）: " + path);
        }
    }

    static RobotsRules rulesFor(String host) {
        RobotsCacheEntry cached = HOSTS_RULES.get(host);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.fetchedAt < ROBOTS_TTL_MS) {
            return cached.rules;
        }
        RobotsRules rules = fetchRobots(host);
        HOSTS_RULES.put(host, new RobotsCacheEntry(rules, now));
        return rules;
    }

    /** 抓 robots.txt：失败一律当"无限制"，但成功解析到的 Disallow 会被严格执行。 */
    private static RobotsRules fetchRobots(String host) {
        RobotsRules rules = new RobotsRules();
        String text = get("https://" + host + "/robots.txt");
        if (text == null) text = get("http://" + host + "/robots.txt");
        if (text == null) return rules;
        parseInto(text, rules);
        return rules;
    }

    private static String get(String url) {
        try {
            OkHttpClient client = NetworkUtil.getClient().newBuilder()
                    .callTimeout(3, TimeUnit.SECONDS)
                    .build();
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return null;
                String body = response.body().string();
                return body.length() > 512 * 1024 ? body.substring(0, 512 * 1024) : body;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析 robots.txt 里 {@code User-agent: *} 组的规则。
     * 只认 `*` 组（不伪装成具体爬虫），`Crawl-delay` 不解析（限速用全局固定值）。
     */
    static void parseInto(String text, RobotsRules rules) {
        if (text == null) return;
        boolean inStarGroup = false;
        boolean sawUserAgent = false;

        for (String rawLine : text.split("\n")) {
            String line = rawLine;
            int comment = line.indexOf('#');
            if (comment >= 0) line = line.substring(0, comment);
            line = line.trim();
            if (line.isEmpty()) continue;

            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();

            if (key.equals("user-agent")) {
                // 一个新的 user-agent 组开始；只有 * 组才收集规则
                inStarGroup = value.equals("*");
                sawUserAgent = true;
                continue;
            }
            if (!sawUserAgent || !inStarGroup) continue;

            if (key.equals("disallow") && !value.isEmpty()) {
                rules.disallow.add(value);
            } else if (key.equals("allow") && !value.isEmpty()) {
                rules.allow.add(value);
            }
        }
    }

    // ==================== 限速 ====================

    private static final Map<String, HostState> HOSTS = new ConcurrentHashMap<>();

    private static final class HostState {
        final Semaphore inFlight = new Semaphore(MAX_CONCURRENCY, true);
        long nextSlotMs = 0;
    }

    static void acquire(String host) throws Exception {
        HostState state = HOSTS.computeIfAbsent(host, h -> new HostState());
        if (!state.inFlight.tryAcquire(MAX_WAIT_MS, TimeUnit.MILLISECONDS)) {
            throw new Exception("同一域名并发请求过多，已放弃本次抓取（限速保护）: " + host);
        }
        long slot;
        synchronized (state) {
            long now = System.currentTimeMillis();
            slot = Math.max(now, state.nextSlotMs);
            state.nextSlotMs = slot + MIN_INTERVAL_MS;
        }
        long wait = slot - System.currentTimeMillis();
        if (wait > MAX_WAIT_MS) {
            state.inFlight.release();
            throw new Exception("该域名请求排队过久（" + wait + "ms），已放弃本次抓取（限速保护）: " + host);
        }
        if (wait > 0) Thread.sleep(wait);
    }

    // ==================== URL 小工具 ====================

    static String hostOf(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            return u.getHost() == null ? null : u.getHost().toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    private static String pathOf(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            String path = u.getPath();
            if (path == null || path.isEmpty()) path = "/";
            if (u.getQuery() != null) path = path + "?" + u.getQuery();
            return path;
        } catch (Exception e) {
            return "/";
        }
    }

    /** 测试用：清空缓存（避免用例之间互相污染）。 */
    static void resetForTest() {
        HOSTS.clear();
        HOSTS_RULES.clear();
    }
}
