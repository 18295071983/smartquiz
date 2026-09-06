# -*- coding: utf-8 -*-
import re
s = open(r"D:\qzq\smartquiz\src\main\cpp\native-lib.cpp", encoding="utf-8").read()
# 找 chatJson 相关函数定义与 KV 管理点
pats = [r"chatJson", r"llama_kv_cache_clear", r"llama_new_context_with_model",
        r"kv_cache_clear", r"n_past", r"llama_kv_cache_seq_rm", r"FULL EVAL",
        r"first_call_or_invalidated", r"invalidate", r"g_sm", r"static .*context",
        r"llama_kv_cache_seq_cp", r"cachedNPast", r"getCachedNPast", r"clearKvCache"]
for p in pats:
    print("=== %s ===" % p)
    for m in list(re.finditer(p, s))[:14]:
        line = s.count("\n", 0, m.start()) + 1
        print("  L%d: %s" % (line, s[m.start()-30:m.start()+60].replace("\n", " ")))
