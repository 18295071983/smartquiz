package com.oilquiz.app.ai.agent.online.mcp;

import com.oilquiz.app.util.AILogger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 等待浏览器回传授权码的请求登记表（state → 正在等的那次授权）。
 *
 * <p>为什么用 state 当唯一钥匙：回传有两个入口（自定义 scheme 走 {@link McpOAuthFlowActivity}，
 * 环回走 {@link McpLoopbackReceiver} 的本地 HTTP 服务），而发起方在另一条线程上等。
 * 两边都只按 state 定位，state 对不上就直接丢——这同时挡住了"别的 App/别的标签页递进来一个
 * 伪造回调"（OAuth 2.0 第 10.12 条要求的那道校验）。</p>
 *
 * <p>同一时刻只允许一条在等：并发起两条授权时，用户在浏览器里只能完成一个回传，
 * 另一条必然超时，与其静默超时不如当场说清。</p>
 */
public final class McpOAuthPending {

    private static final String TAG = "McpOAuthPending";

    /** 一次进行中的授权。 */
    public static final class Waiter {
        public final String state;
        public final String serverId;
        public final String codeVerifier;
        public final String clientId;
        public final String redirectUri;
        public final String resource;
        public final McpOAuthEndpoints.AuthorizationServer authorizationServer;

        Waiter(String state, String serverId, String codeVerifier, String clientId,
               String redirectUri, String resource,
               McpOAuthEndpoints.AuthorizationServer authorizationServer) {
            this.state = state;
            this.serverId = serverId;
            this.codeVerifier = codeVerifier;
            this.clientId = clientId;
            this.redirectUri = redirectUri;
            this.resource = resource;
            this.authorizationServer = authorizationServer;
        }
    }

    /** 回传结果：拿到 code，或者拿到 error，或者什么都没拿到。 */
    public static final class Result {
        public final String code;
        public final String error;
        public final String errorDescription;

        /** 两条回传通道（自定义 scheme 的 Activity、环回监听）都从这里构造，跨包可用。 */
        public Result(String code, String error, String errorDescription) {
            this.code = code == null ? "" : code;
            this.error = error == null ? "" : error;
            this.errorDescription = errorDescription == null ? "" : errorDescription;
        }

        public boolean hasCode() {
            return !code.isEmpty();
        }

        public boolean hasError() {
            return !error.isEmpty();
        }
    }

    private static final Map<String, Waiter> WAITERS = new LinkedHashMap<>();
    private static final Object LOCK = new Object();
    private static volatile Result lastResult;
    private static volatile boolean signalled;

    private McpOAuthPending() {}

    /** 登记一次等待；已有等待时返回 false（调用方应提示"先完成上一条授权"）。 */
    public static boolean begin(Waiter waiter) {
        synchronized (LOCK) {
            if (!WAITERS.isEmpty()) {
                AILogger.w(TAG, "已有授权在等回传，拒绝并发登记: " + waiter.serverId);
                return false;
            }
            WAITERS.put(waiter.state, waiter);
            lastResult = null;
            signalled = false;
            return true;
        }
    }

    /** 当前是否有等待（UI 用来决定"去授权"按钮是否可点）。 */
    public static boolean hasPending() {
        synchronized (LOCK) {
            return !WAITERS.isEmpty();
        }
    }

    /**
     * 回传入口：按 state 定位等待，命中就唤醒发起方。
     *
     * @return 是否命中一条等待；false = state 不认识（伪造/过期/重复回传），调用方只需丢弃
     */
    public static boolean deliver(String state, Result result) {
        if (state == null || state.isEmpty()) {
            AILogger.w(TAG, "回传没带 state，丢弃");
            return false;
        }
        synchronized (LOCK) {
            Waiter waiter = WAITERS.get(state);
            if (waiter == null) {
                AILogger.w(TAG, "回传的 state 与当前等待不匹配，丢弃（可能是别的 App 递进来的）");
                return false;
            }
            lastResult = result;
            signalled = true;
            LOCK.notifyAll();
            return true;
        }
    }

    /** 发起方阻塞等待回传。 */
    public static Result awaitResult(String state, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (LOCK) {
            while (!signalled) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) return null;
                try {
                    LOCK.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return lastResult;
        }
    }

    /** 无论成败都要收尾：删掉等待，避免下一次回传命中陈旧条目。 */
    public static void finish(String state) {
        synchronized (LOCK) {
            WAITERS.remove(state);
            lastResult = null;
            signalled = false;
        }
    }

    /** 测试用：清空登记表。 */
    public static void clearForTest() {
        synchronized (LOCK) {
            WAITERS.clear();
            lastResult = null;
            signalled = false;
        }
    }
}
