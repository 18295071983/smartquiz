package com.oilquiz.app.ai.chat.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 思考段滚动器（全局可复用）。
 *
 * 从 AIChatActivity buildThinkingSegment 抽取：把思考内容按行切段，
 * 内容增长 → 跳到最新段（实时显示最新进展）；内容停顿 → 轮播各段。
 * 持有段列表与索引状态，可在任意状态条/角标复用同一套滚动逻辑。
 */
public class ThinkingSegmentRoller {

    private List<String> thinkingSegments = new ArrayList<>();
    private int thinkingSegmentIndex = 0;

    /** 取当前应显示的思考段（单行截断保留最新尾部 34 字符） */
    public String buildThinkingSegment(String think) {
        List<String> segs = new ArrayList<>();
        for (String line : think.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) segs.add(t);
        }
        if (segs.isEmpty()) {
            segs.add(think);
        }
        int oldSize = thinkingSegments.size();
        thinkingSegments = segs;
        if (segs.size() > oldSize) {
            thinkingSegmentIndex = segs.size() - 1;
        } else {
            thinkingSegmentIndex = (thinkingSegmentIndex + 1) % segs.size();
        }
        String seg = segs.get(thinkingSegmentIndex);
        if (seg.length() > 34) seg = seg.substring(seg.length() - 34);
        return seg;
    }

    public void reset() {
        thinkingSegments = new ArrayList<>();
        thinkingSegmentIndex = 0;
    }
}
