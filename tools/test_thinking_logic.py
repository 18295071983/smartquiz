# -*- coding: utf-8 -*-
"""本地复刻 Java 逻辑验证：splitThinkingAndContent + stripThinkingSections 的边界用例。"""

SP = " "
S_TAG = SP + "thinking"          # 空格分隔起始标签
E_TAG = SP + "response"          # 空格分隔结束标签

def split(full, start_tag, end_tags):
    if full is None:
        return ["", ""]
    thinkStart = full.find(start_tag)
    if thinkStart < 0:
        return [full, ""]
    contentStart = thinkStart + len(start_tag)
    close, closeLen = -1, 0
    for tag in end_tags:
        p = full.find(tag, contentStart)
        if p >= 0 and (close < 0 or p < close):
            close, closeLen = p, len(tag)
    if close >= 0:
        thinking = full[contentStart:close].strip()
        content = (full[:thinkStart] + full[close + closeLen:]).strip()
    else:
        thinking = full[contentStart:].strip()
        content = full[:thinkStart].strip()
    return [content, thinking]

def strip_sections(text):
    if not text:
        return ""
    s = text
    for _ in range(8):
        parts = split(s, S_TAG, [E_TAG])
        content = parts[0]
        if content == s:
            return content
        s = content
        if not content.strip():
            break
    return s

def T(*segments):
    return "".join(segments)

cases = [
    (T(S_TAG, "思考内容", E_TAG, "这是正文"), "这是正文"),                 # 闭合，正文在后
    (T("这是正文", S_TAG, "思考内容", E_TAG), "这是正文"),                 # 闭合，思考在尾部
    (T(S_TAG, "思考内容"), ""),                                            # 未闭合，思考即全部
    (T("这是正文", S_TAG, "思考内容"), "这是正文"),                        # 未闭合，思考残留
    (T(S_TAG, "思考1", E_TAG, "正文1", S_TAG, "思考2"), "正文1"),          # 多段+未闭合第二段
    (T(S_TAG, "思考1", E_TAG, "正文1", S_TAG, "思考2", E_TAG, "正文2"), "正文1正文2"),  # 两段闭合
    ("完全正常文本，没有思考标签", "完全正常文本，没有思考标签"),          # 无思考
    (T(S_TAG, "思考 内容", E_TAG), ""),                                    # 闭合，全部是思考
    (T("  正文在中间  ", S_TAG, "思考", E_TAG, "  结尾"), "正文在中间 结尾"),  # 思考夹在中间
]

all_ok = True
for inp, exp in cases:
    got = strip_sections(inp)
    ok = (got == exp)
    all_ok = all_ok and ok
    print(("PASS" if ok else "FAIL"), "| got=", repr(got), "| exp=", repr(exp))

print("\nALL:", "PASS" if all_ok else "FAIL")
