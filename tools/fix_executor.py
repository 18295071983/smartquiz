# -*- coding: utf-8 -*-
import io

p = r'D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ai\service\OnlineInferenceService.java'
with io.open(p, 'r', encoding='utf-8') as f:
    s = f.read()

old = '''        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "Online-Inference-Worker");
            t.setPriority(Thread.NORM_PRIORITY);
            t.setDaemon(true);
            return t;
        });'''

new = '''        // cached 线程池：chat/agent/摘要/翻译/题目生成/embedding 等全部在线请求共用，
        // 固定小池会被慢请求（HTTP read 最长 120s）占满导致后续请求无限排队（"卡死"）；
        // cached 下慢请求只占自己的线程，HTTP 超时后自动回收，互不阻塞。
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "Online-Inference-Worker");
            t.setPriority(Thread.NORM_PRIORITY);
            t.setDaemon(true);
            return t;
        });'''

if old in s:
    s = s.replace(old, new)
    with io.open(p, 'w', encoding='utf-8', newline='') as f:
        f.write(s)
    print('替换成功')
else:
    print('未找到目标')
