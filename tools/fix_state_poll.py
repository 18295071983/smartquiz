# -*- coding: utf-8 -*-
# AIChatActivity.java: 状态条独立周期轮询
# 解决：思考段（THINKING）不走 token 流式回调，状态条不更新；改为生命周期轮询
# onResume 启动 + onDestroy 停止，每 800ms 刷新一次 native 状态机 + KV
import io, sys
path = r"D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ui\activity\AIChatActivity.java"
with io.open(path, "r", encoding="utf-8") as f:
    src = f.read()

def rep(old, new, label, expect=1):
    global src
    c = src.count(old)
    if c != expect:
        print("[FAIL] %s: count=%d" % (label, c)); sys.exit(1)
    src = src.replace(old, new, expect)
    print("[OK] %s" % label)

# 1) 轮询字段（tvKvStats 声明后）
rep(
'''    private TextView tvGenPhase;
    private TextView tvKvStats;
''',
'''    private TextView tvGenPhase;
    private TextView tvKvStats;

    // 状态条独立轮询：思考段不走 token 流式回调，需定时刷新 native 状态机 + KV
    private static final long STATE_POLL_INTERVAL_MS = 800L;
    private final Handler statePollHandler = new Handler(Looper.getMainLooper());
    private boolean statePolling = false;
    private final Runnable statePollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!statePolling) return;
            refreshNativeStateUI();
            statePollHandler.postDelayed(this, STATE_POLL_INTERVAL_MS);
        }
    };
''',
'state poll fields')

# 2) refreshNativeStateUI 方法后加 start/stop 轮询方法
rep(
'''        } catch (Throwable t) {
            // 解析失败静默，不影响主流程
        }
    }

    /**
     * 上下文窗口格式化：>=1M 显示 "1M"（如 deepseek-v4 的 1048576），>=1K 显示 "64K"，否则原值。
     */''',
'''        } catch (Throwable t) {
            // 解析失败静默，不影响主流程
        }
    }

    /** 启动状态条轮询（onResume 时）：立即刷新一次 + 周期刷新，覆盖思考段无 token 回调的场景 */
    private void startStatePolling() {
        if (statePolling) return;
        statePolling = true;
        refreshNativeStateUI();
        statePollHandler.removeCallbacks(statePollRunnable);
        statePollHandler.postDelayed(statePollRunnable, STATE_POLL_INTERVAL_MS);
    }

    /** 停止状态条轮询（onDestroy 时） */
    private void stopStatePolling() {
        statePolling = false;
        statePollHandler.removeCallbacksAndMessages(null);
    }

    /**
     * 上下文窗口格式化：>=1M 显示 "1M"（如 deepseek-v4 的 1048576），>=1K 显示 "64K"，否则原值。
     */''',
'add start/stop polling methods')

# 3) onResume 末尾启动轮询
rep(
'''        // 再次进入页面时定位到最新消息。
        // 延迟执行：确保列表完成 layout（历史可能刚异步加载完成），直接 scrollToPosition 定位到底部
        messageList.postDelayed(() -> scrollToBottom(true), 80);
    }''',
'''        // 再次进入页面时定位到最新消息。
        // 延迟执行：确保列表完成 layout（历史可能刚异步加载完成），直接 scrollToPosition 定位到底部
        messageList.postDelayed(() -> scrollToBottom(true), 80);

        // 启动状态条轮询：思考段（THINKING）不产出正文 token，只能靠定时刷新拿到
        startStatePolling();
    }''',
'onResume start polling')

# 4) onDestroy 停止轮询
rep(
'''        super.onDestroy();
        try {
            // 释放语音输入录音器与 TTS 播放资源
            releaseSpeechRecorder();''',
'''        super.onDestroy();
        stopStatePolling();
        try {
            // 释放语音输入录音器与 TTS 播放资源
            releaseSpeechRecorder();''',
'onDestroy stop polling')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("STATE POLL OK")
