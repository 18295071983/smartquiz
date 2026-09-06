# -*- coding: utf-8 -*-
# AIChatViewModel.java: 在线思考 LiveData 流 + startOnlineInference 接线
import io, sys
path = r"D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ai\chat\viewmodel\AIChatViewModel.java"
with io.open(path, "r", encoding="utf-8") as f:
    src = f.read()

def rep(old, new, label, expect=1):
    global src
    c = src.count(old)
    if c != expect:
        print("[FAIL] %s: count=%d" % (label, c)); sys.exit(1)
    src = src.replace(old, new, expect)
    print("[OK] %s" % label)

# 1) 字段
rep(
'''    private final MutableLiveData<Boolean> initializationLiveData = new MutableLiveData<>(false);
''',
'''    private final MutableLiveData<Boolean> initializationLiveData = new MutableLiveData<>(false);
    /** 在线思考实时流：postValue 增量 token；postValue(null) 表示思考结束（顶部单行据此显示/隐藏） */
    private final MutableLiveData<String> onlineThinkingStream = new MutableLiveData<>();
''',
'field')

# 2) startOnlineInference 回调：onThinkingToken + onComplete
rep(
'''                inferenceRouter.generateStream(message, config, new StreamCallback() {
                    @Override
                    public void onStart() {
                        updateInferencePhase(ChatMessage.InferencePhase.ENCODING);
                    }

                    @Override
                    public void onToken(String token) {
                        handleStreamingToken(token);
                    }

                    @Override
                    public void onComplete(String fullText) {
                        completeGeneration(fullText);
                    }

                    @Override
                    public void onError(String error) {
                        handleGenerationError(error);
                    }
                });''',
'''                inferenceRouter.generateStream(message, config, new StreamCallback() {
                    @Override
                    public void onStart() {
                        updateInferencePhase(ChatMessage.InferencePhase.ENCODING);
                    }

                    @Override
                    public void onToken(String token) {
                        handleStreamingToken(token);
                    }

                    @Override
                    public void onThinkingToken(String token) {
                        // 在线思考：气泡思考区（handleThinkingToken 同步 msg.thinkingContent）
                        // + 顶部单行实时流（增量 token，null 由 onComplete 发）
                        if (token != null && !token.isEmpty()) {
                            handleThinkingToken(token);
                            onlineThinkingStream.postValue(token);
                        }
                    }

                    @Override
                    public void onComplete(String fullText) {
                        completeGeneration(fullText);
                        onlineThinkingStream.postValue(null);   // 思考结束，顶部单行隐藏
                    }

                    @Override
                    public void onError(String error) {
                        handleGenerationError(error);
                        onlineThinkingStream.postValue(null);
                    }
                });''',
'online callbacks')

# 3) getter
rep(
'''    public LiveData<Boolean> isGenerating() {''',
'''    public LiveData<String> getOnlineThinkingStream() {
        return onlineThinkingStream;
    }

    public LiveData<Boolean> isGenerating() {''',
'getter')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("VM ONLINE THINKING OK")
