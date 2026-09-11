# -*- coding: utf-8 -*-
import io

p = r'D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ai\service\OnlineInferenceService.java'
with io.open(p, 'r', encoding='utf-8') as f:
    s = f.read()

# ---------- 1. generateAsync 开头：套重试循环 ----------
old_head = '''    public CompletableFuture<String> generateAsync(String prompt, OnlineModelManager.OnlineModelConfig config,
                                                  List<ChatMessage> history, int maxTokens, boolean enableTools) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String apiUrl = config.apiUrl;'''
new_head = '''    public CompletableFuture<String> generateAsync(String prompt, OnlineModelManager.OnlineModelConfig config,
                                                  List<ChatMessage> history, int maxTokens, boolean enableTools) {
        return CompletableFuture.supplyAsync(() -> {
            Exception lastError = null;
            for (int attempt = 0; attempt < MAX_RETRY_ATTEMPTS; attempt++) {
                try {
                String apiUrl = config.apiUrl;'''
assert old_head in s, 'generateAsync head not found'
s = s.replace(old_head, new_head, 1)

# ---------- 2. generateAsync 结尾：重试判断 ----------
old_tail = '''            } catch (Exception e) {
                AILogger.e(TAG, "Async generate failed: " + e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }, executor);
    }'''
new_tail = '''                } catch (Exception e) {
                    lastError = e;
                    if (attempt < MAX_RETRY_ATTEMPTS - 1 && isRetryableOnlineError(e)) {
                        AILogger.w(TAG, "在线请求瞬时错误(" + e.getMessage() + ")，" + RETRY_DELAY_MS + "ms 后重试");
                        try { Thread.sleep(RETRY_DELAY_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                        continue;
                    }
                    break;
                }
            }
            AILogger.e(TAG, "Async generate failed: " + (lastError != null ? lastError.getMessage() : "unknown"), lastError);
            throw new RuntimeException(lastError != null ? lastError : new RuntimeException("Async generate failed"));
        }, executor);
    }'''
assert old_tail in s, 'generateAsync tail not found'
s = s.replace(old_tail, new_tail, 1)

# ---------- 3. generateOnceAsync 注释 + 开头 ----------
old_once_head = '''    public CompletableFuture<String> generateOnceAsync(String prompt,
            OnlineModelManager.OnlineModelConfig config, int maxTokens) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String apiUrl = config.apiUrl;'''
new_once_head = '''    public CompletableFuture<String> generateOnceAsync(String prompt,
            OnlineModelManager.OnlineModelConfig config, int maxTokens) {
        return CompletableFuture.supplyAsync(() -> {
            Exception lastError = null;
            for (int attempt = 0; attempt < MAX_RETRY_ATTEMPTS; attempt++) {
                try {
                String apiUrl = config.apiUrl;'''
assert old_once_head in s, 'generateOnceAsync head not found'
s = s.replace(old_once_head, new_once_head, 1)

# ---------- 4. generateOnceAsync 结尾 ----------
old_once_tail = '''            } catch (Exception e) {
                AILogger.e(TAG, "generateOnce failed: " + e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }, executor);
    }'''
new_once_tail = '''                } catch (Exception e) {
                    lastError = e;
                    if (attempt < MAX_RETRY_ATTEMPTS - 1 && isRetryableOnlineError(e)) {
                        AILogger.w(TAG, "generateOnce 瞬时错误(" + e.getMessage() + ")，" + RETRY_DELAY_MS + "ms 后重试");
                        try { Thread.sleep(RETRY_DELAY_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                        continue;
                    }
                    break;
                }
            }
            AILogger.e(TAG, "generateOnce failed: " + (lastError != null ? lastError.getMessage() : "unknown"), lastError);
            throw new RuntimeException(lastError != null ? lastError : new RuntimeException("generateOnce failed"));
        }, executor);
    }

    /** 瞬时错误自动重试次数（429 限流 / 5xx 服务端抖动 / 网络超时） */
    private static final int MAX_RETRY_ATTEMPTS = 2;
    private static final long RETRY_DELAY_MS = 1000L;

    /** 是否值得重试的瞬时错误：限流 429、服务端 5xx、连接/读取超时等（业务 400/401/403 不重试） */
    private static boolean isRetryableOnlineError(Throwable t) {
        String m = t != null && t.getMessage() != null ? t.getMessage() : "";
        if (m.contains("429") || m.contains("500") || m.contains("502") || m.contains("503")
                || m.contains("504") || m.contains("Read timed out") || m.contains("connect timed out")
                || m.contains("Connection") || m.contains("connect") || m.contains("timed out")
                || m.contains("timeout") || m.contains("Socket") || m.contains("refused")) {
            return true;
        }
        return false;
    }'''
assert old_once_tail in s, 'generateOnceAsync tail not found'
s = s.replace(old_once_tail, new_once_tail, 1)

with io.open(p, 'w', encoding='utf-8', newline='') as f:
    f.write(s)
print('全部替换成功')
