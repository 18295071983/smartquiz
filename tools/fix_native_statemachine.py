# -*- coding: utf-8 -*-
# native 生成流程状态机（第二轮：函数体局部替换，只改 generateStreamIncremental + chatJson）
import io, sys

path = r"D:\qzq\smartquiz\src\main\cpp\native-lib.cpp"
with io.open(path, "r", encoding="utf-8") as f:
    src = f.read()

# ---- 全局唯一替换（已验证 count=1）----
def rep_global(old, new, label):
    global src
    c = src.count(old)
    if c != 1:
        print("[FAIL] %s: count=%d" % (label, c)); sys.exit(1)
    src = src.replace(old, new, 1)
    print("[OK] %s" % label)

# ---- 函数体内局部替换 ----
def rep_in_func(sig, end_marker, old, new, label):
    global src
    i = src.find(sig)
    if i < 0:
        print("[FAIL] %s: sig not found" % label); sys.exit(1)
    j = src.find(end_marker, i)
    if j < 0:
        print("[FAIL] %s: end_marker not found" % label); sys.exit(1)
    j += len(end_marker)
    body = src[i:j]
    c = body.count(old)
    if c != 1:
        print("[FAIL] %s: in-func count=%d" % (label, c)); sys.exit(1)
    src = src[:i] + body.replace(old, new, 1) + src[j:]
    print("[OK] %s" % label)

INCR_SIG = '    bool generateStreamIncremental(const std::string& prompt'
INCR_END = '// 单次生成路径：接收消息列表，用 llama_chat_apply_template'

# ========== A. 状态机骨架 ==========
rep_global(
'''    std::string mThinkStartTag;
    std::vector<std::string> mThinkEndTags;
    
public:''',
'''    std::string mThinkStartTag;
    std::vector<std::string> mThinkEndTags;

    // ===== 生成流程状态机（native）=====
    // 统一管理生成阶段与停止原因，替代散落的布尔/字符串状态，
    // 保证 IDLE -> PREPROCESS -> THINKING/GENERATING -> COMPLETE/ERROR 迁移显式可观测。
    enum class GenPhase {
        IDLE,       // 空闲
        PREPROCESS, // prompt 构建/tokenize/eval
        THINKING,   // 思考段生成中
        GENERATING, // 正文生成中
        COMPLETE,   // 正常完成（EOS/stop_word/ctx_full/timeout/max_tokens/user_stop）
        ERROR       // 出错
    };
    enum class StopCause {
        NONE, EOS, STOP_WORD, CTX_FULL, TIMEOUT, TOKEN_LIMIT, USER_STOP, MAX_TOKENS, ERROR
    };
    GenPhase phase = GenPhase::IDLE;
    StopCause stopCause = StopCause::NONE;

    static const char* phaseName(GenPhase p) {
        switch (p) {
            case GenPhase::IDLE: return "IDLE";
            case GenPhase::PREPROCESS: return "PREPROCESS";
            case GenPhase::THINKING: return "THINKING";
            case GenPhase::GENERATING: return "GENERATING";
            case GenPhase::COMPLETE: return "COMPLETE";
            case GenPhase::ERROR: return "ERROR";
        }
        return "?";
    }
    static const char* stopName(StopCause c) {
        switch (c) {
            case StopCause::NONE: return "NONE";
            case StopCause::EOS: return "EOS";
            case StopCause::STOP_WORD: return "STOP_WORD";
            case StopCause::CTX_FULL: return "CTX_FULL";
            case StopCause::TIMEOUT: return "TIMEOUT";
            case StopCause::TOKEN_LIMIT: return "TOKEN_LIMIT";
            case StopCause::USER_STOP: return "USER_STOP";
            case StopCause::MAX_TOKENS: return "MAX_TOKENS";
            case StopCause::ERROR: return "ERROR";
        }
        return "?";
    }
    void setPhase(GenPhase p, const char* where) {
        if (phase != p) {
            LOGI("[GenSM] %s: %s -> %s", where, phaseName(phase), phaseName(p));
            phase = p;
        }
    }
    void setStop(StopCause c, const char* where) {
        stopCause = c;
        LOGI("[GenSM] stop cause: %s (%s)", stopName(c), where);
    }

public:''',
'state machine skeleton')

# ========== B. incremental 入口 PREPROCESS ==========
rep_in_func(INCR_SIG, INCR_END,
'''    bool generateStreamIncremental(const std::string& prompt, int maxTokens, float temperature, float topP, int topK, bool enableThinking, TokenCallback callback) {
        if (isGenerating.exchange(true)) {''',
'''    bool generateStreamIncremental(const std::string& prompt, int maxTokens, float temperature, float topP, int topK, bool enableThinking, TokenCallback callback) {
        setPhase(GenPhase::PREPROCESS, "incr:entry");
        if (isGenerating.exchange(true)) {''',
'incr entry PREPROCESS')

# ========== C. 生成循环前 THINKING/GENERATING ==========
rep_in_func(INCR_SIG, INCR_END,
'''        // ===== 生成循环（与 generateStream 一致）=====
        int n_remain = maxTokens;''',
'''        // ===== 生成循环（与 generateStream 一致）=====
        setPhase(enableThinking ? GenPhase::THINKING : GenPhase::GENERATING, "incr:gen_loop");
        int n_remain = maxTokens;''',
'incr gen loop phase')

# ========== D. ctx_full ==========
rep_in_func(INCR_SIG, INCR_END,
'''            if (n_past >= n_ctx - 4) {
                LOGI("Context full, stopping generation (n_past=%d, n_ctx=%d)", n_past, n_ctx);
                stopReason = "ctx_full";
                break;
            }''',
'''            if (n_past >= n_ctx - 4) {
                LOGI("Context full, stopping generation (n_past=%d, n_ctx=%d)", n_past, n_ctx);
                setStop(StopCause::CTX_FULL, "incr:ctx_full");
                setPhase(GenPhase::COMPLETE, "incr:ctx_full");
                stopReason = "ctx_full";
                break;
            }''',
'incr ctx_full')

# ========== E. timeout ==========
rep_in_func(INCR_SIG, INCR_END,
'''            if (elapsed > TIMEOUT_SECONDS) {
                LOGI("TIMEOUT: Generation exceeded %d seconds", TIMEOUT_SECONDS);
                stopReason = "timeout";
                break;
            }''',
'''            if (elapsed > TIMEOUT_SECONDS) {
                LOGI("TIMEOUT: Generation exceeded %d seconds", TIMEOUT_SECONDS);
                setStop(StopCause::TIMEOUT, "incr:timeout");
                setPhase(GenPhase::COMPLETE, "incr:timeout");
                stopReason = "timeout";
                break;
            }''',
'incr timeout')

# ========== F1. EOG ==========
rep_in_func(INCR_SIG, INCR_END,
'''            if (llama_vocab_is_eog(vocab, new_token_id)) {
                LOGI("EOS token detected, stopping generation");
                stopReason = "eos";
                break;
            }''',
'''            if (llama_vocab_is_eog(vocab, new_token_id)) {
                LOGI("EOS token detected, stopping generation");
                setStop(StopCause::EOS, "incr:eog");
                setPhase(GenPhase::COMPLETE, "incr:eog");
                stopReason = "eos";
                break;
            }''',
'incr eog')

# ========== F2. eos_id ==========
rep_in_func(INCR_SIG, INCR_END,
'''                LOGI("Common EOS token ID detected: %d, stopping generation", new_token_id);
                stopReason = "eos";
                break;''',
'''                LOGI("Common EOS token ID detected: %d, stopping generation", new_token_id);
                setStop(StopCause::EOS, "incr:eos_id");
                setPhase(GenPhase::COMPLETE, "incr:eos_id");
                stopReason = "eos";
                break;''',
'incr eos_id')

# ========== G. stop_word ==========
rep_in_func(INCR_SIG, INCR_END,
'''                LOGI("Stop word detected in token, stopping generation");
                stopReason = "stop_word";
                break;''',
'''                LOGI("Stop word detected in token, stopping generation");
                setStop(StopCause::STOP_WORD, "incr:stop_word");
                setPhase(GenPhase::COMPLETE, "incr:stop_word");
                stopReason = "stop_word";
                break;''',
'incr stop_word')

# ========== H. think_end marker ==========
rep_in_func(INCR_SIG, INCR_END,
'''                    thinkingEnded = true;
                    callback("[THINK_END]", false, "");''',
'''                    thinkingEnded = true;
                    setPhase(GenPhase::GENERATING, "incr:think_end_marker");
                    callback("[THINK_END]", false, "");''',
'incr think_end marker')

# ========== I. think token limit ==========
rep_in_func(INCR_SIG, INCR_END,
'''                    thinkingEnded = true;
                    LOGI("Thinking token limit reached (%d), forcing THINK_END", thinkingTokens);
                    callback("[THINK_END]", false, "");''',
'''                    thinkingEnded = true;
                    setPhase(GenPhase::GENERATING, "incr:think_limit");
                    setStop(StopCause::TOKEN_LIMIT, "incr:think_limit");
                    LOGI("Thinking token limit reached (%d), forcing THINK_END", thinkingTokens);
                    callback("[THINK_END]", false, "");''',
'incr think limit')

# ========== J. decode 失败 ==========
rep_in_func(INCR_SIG, INCR_END,
'''            if (ret != 0) {
                LOGE("llama_decode failed with code: %d", ret);
                break;
            }
            generatedTokens.push_back(new_token_id);''',
'''            if (ret != 0) {
                LOGE("llama_decode failed with code: %d", ret);
                setStop(StopCause::ERROR, "incr:decode_fail");
                setPhase(GenPhase::ERROR, "incr:decode_fail");
                break;
            }
            generatedTokens.push_back(new_token_id);''',
'incr decode fail')

# ========== K. fallback THINK_END ==========
rep_in_func(INCR_SIG, INCR_END,
'''            callback("[THINK_END]", false, "");
            thinkingEnded = true;
        }

        callback(fullText, true, "");''',
'''            callback("[THINK_END]", false, "");
            thinkingEnded = true;
            setPhase(GenPhase::GENERATING, "incr:think_fallback_end");
        }

        callback(fullText, true, "");''',
'incr fallback think end')

# ========== L. 结尾 COMPLETE ==========
rep_in_func(INCR_SIG, INCR_END,
'''        LOGI("Generated %d tokens in %lld s (stop=%s, incremental=%d)", n_decode, elapsedTotal, stopReason.c_str(), (int)kvCache.isIncremental());

        return true;''',
'''        LOGI("Generated %d tokens in %lld s (stop=%s, incremental=%d, phase=%s)", n_decode, elapsedTotal, stopReason.c_str(), (int)kvCache.isIncremental(), phaseName(phase));
        setPhase(GenPhase::COMPLETE, "incr:end");

        return true;''',
'incr end COMPLETE')

# ========== M. exception ERROR ==========
rep_in_func(INCR_SIG, INCR_END,
'''        } catch (const std::exception& e) {
            LOGE("Exception in generateStreamIncremental: %s", e.what());
            llama_sampler_free(smpl);''',
'''        } catch (const std::exception& e) {
            LOGE("Exception in generateStreamIncremental: %s", e.what());
            setPhase(GenPhase::ERROR, "incr:exception");
            setStop(StopCause::ERROR, "incr:exception");
            llama_sampler_free(smpl);''',
'incr exception ERROR')

# ========== N. chatJson thinking START/END ==========
rep_global(
'''                            } else {
                                filtered.append(completePart, pos, open - pos);
                                isInThinking = true;
                                LOGI("chatJson: thinking START detected");
                                pos = open + thinkOpen.size();
                            }''',
'''                            } else {
                                filtered.append(completePart, pos, open - pos);
                                isInThinking = true;
                                setPhase(GenPhase::THINKING, "chatJson:think_start");
                                LOGI("chatJson: thinking START detected");
                                pos = open + thinkOpen.size();
                            }''',
'chatJson think start')

rep_global(
'''                            } else {
                                isInThinking = false;
                                LOGI("chatJson: thinking END detected");
                                pos = close + closeLen;
                            }''',
'''                            } else {
                                isInThinking = false;
                                setPhase(GenPhase::GENERATING, "chatJson:think_end");
                                LOGI("chatJson: thinking END detected");
                                pos = close + closeLen;
                            }''',
'chatJson think end')

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("ALL STATE MACHINE PATCHES OK")
