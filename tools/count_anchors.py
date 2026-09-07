# -*- coding: utf-8 -*-
import io
src = io.open(r"D:\qzq\smartquiz\src\main\cpp\native-lib.cpp", encoding="utf-8").read()
anchors = {
 'ctx_full': 'if (n_past >= n_ctx - 4) {',
 'timeout': 'if (elapsed > TIMEOUT_SECONDS) {',
 'eog': 'if (llama_vocab_is_eog(vocab, new_token_id)) {',
 'eos_id': 'if (new_token_id == 151643',
 'stop_word': 'if (token.find("<|im_end|>") != std::string::npos ||',
 'decode_fail': 'llama_decode failed with code',
 'fallback_think': '[THINK_END]',
 'incr_end': 'incremental=%d)',
 'gen_end': 'Generated %d tokens in %lld s (stop=%s)',
 'incr_exception': 'Exception in generateStreamIncremental',
 'gen_exception': 'Exception in generateStream:',
 'STREAM_GEN_END': 'STREAM GENERATE END',
 'STREAM_INCR_END': 'STREAM GENERATE INCREMENTAL END',
}
for k, v in anchors.items():
    print("%-18s %d" % (k, src.count(v)))
