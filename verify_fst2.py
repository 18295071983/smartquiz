# -*- coding: utf-8 -*-
# 模拟"补壳版"filterStreamToken：漏闭合时 JSON 配平即结束，不吞后续正常回复

def is_complete_json(s):
    start = s.find('{')
    if start < 0:
        return False
    depth = 0
    in_str = False
    esc = False
    for c in s[start:]:
        if in_str:
            if esc:
                esc = False
            elif c == '\\':
                esc = True
            elif c == '"':
                in_str = False
        else:
            if c == '"':
                in_str = True
            elif c == '{':
                depth += 1
            elif c == '}':
                depth -= 1
                if depth == 0:
                    return True
    return False

class StreamFilter:
    def __init__(self):
        self.buf = []
        self.swallowing = False

    def filter(self, token):
        if self.swallowing:
            self.buf.append(token)
            acc = ''.join(self.buf)
            close = acc.find('</tool_call')
            if close >= 0:
                self.swallowing = False
                tail = acc[close + len('</tool_call'):]
                self.buf = []
                gt = tail.find('>')
                if gt >= 0:
                    tail = tail[gt + 1:]
                if tail:
                    return self.filter(tail)
                return ''
            if is_complete_json(acc):
                self.swallowing = False
                self.buf = []
                return ''
            if len(acc) > 8192:
                self.swallowing = False
                self.buf = []
                return ''
            return ''
        self.buf.append(token)
        probe = ''.join(self.buf)
        open_ = probe.find('<tool_call')
        if open_ >= 0:
            before = probe[:open_]
            self.buf = [probe[open_:]]
            after = ''.join(self.buf)
            if '</tool_call' in after:
                self.buf = []
                c = after.find('</tool_call')
                tail = after[c + len('</tool_call'):]
                gt = tail.find('>')
                if gt >= 0:
                    tail = tail[gt + 1:]
                if tail:
                    return before + self.filter(tail)
                return before
            if is_complete_json(after):
                self.buf = []
                self.swallowing = False
                return before
            self.swallowing = True
            return before
        lastLt = probe.rfind('<')
        if lastLt >= 0:
            suffix = probe[lastLt:]
            if '<tool_call'.startswith(suffix) and len(suffix) < len('<tool_call'):
                head = probe[:lastLt]
                self.buf = [suffix]
                return head
            if suffix == '<tool_call':
                self.buf = [suffix]
                self.swallowing = True
                return probe[:lastLt]
        self.buf = []
        return probe

def run(tokens):
    sf = StreamFilter()
    emitted = []
    for t in tokens:
        e = sf.filter(t)
        if e:
            emitted.append(e)
    return ''.join(emitted)

tests = [
    ('A 完整闭合(跨token)', ['好的', '我来查', '<tool', '_call>', '{"name"', ':"ai_we', 'ather","ar', 'guments":{"city":"银川"}}', '</tool_c', 'all>', '今天银川晴天']),
    ('B 漏闭合-后续正文保留', ['好的', '<tool_call>{"name":"ai_weather","arguments":{"city":"银川"}}', '今天天气不错，适合出门']),
    ('C 漏闭合-跨token JSON', ['好的', '<tool', '_call>', '{"name":"ai_', 'weather","ar', 'guments":{"city":"银川"}}', '明天有雨']),
    ('D 漏闭合-同token内容', ['好的<tool_call>{"name":"ai_weather","arguments":{"a":1}}', '接下来给你报告']),
    ('E 正常正文+<b>', ['结果', ' ', '<b>加粗</b>', ' 结束']),
    ('F 完整闭合-无前文', ['<tool_call>{"name":"network_search"}</tool_call>', '已为你搜索完成']),
    ('G 字符串内花括号', ['<tool_call>{"name":"a","arguments":{"q":"}这是}字符串{"}}', '查询结果如下']),
    ('H 复数<tool_calls>完整闭合', ['<tool_calls>{"name":"a"}</tool_calls>', '结果好了']),
    ('I 超长未闭合-强制结束', ['<tool_call>{"name":"a","args":"' + 'x' * 9000 + '",', '后续正文应保留']),
]
for name, tk in tests:
    print(name, '=>', repr(run(tk)))
