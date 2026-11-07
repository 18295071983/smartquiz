package com.oilquiz.app.ai.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * CONTEXT-RESIDENT(2026-11-08)：KV 预算 → 上下文 token 数的纯算术。
 *
 * <p>这段算式原来在 CONTEXT-DYNAMIC 与 LAYERS-FIRST 各写一遍（含 512 对齐），
 * 两处口径漂过。抽成 {@link AIService#maxContextByKvBudget(long, long)} 后，
 * 这里锁住三件事：</p>
 * <ol>
 *   <li>单位：预算按 MB，代价按 B/token，结果必须落在 512 的整数倍（llama.cpp 的
 *       n_ctx 粒度），且**向下取整**——宁可少一个对齐块，不超预算；</li>
 *   <li>未知/无预算返回 0，由调用方决定退回哪条路，绝不返回"看起来能跑"的假值；</li>
 *   <li>本机真值：Qwen3.5-2B 的 12288 B/token 下，144MB KV 正好装 12288 tokens，
 *       而 11GB 设备的 25% 常驻上限（≈2770MB）折合 23 万 tokens——手动 32768
 *       根本不会被这道物理护栏夹小（这正是"窗口被压到 4096"要排除的误伤）。</li>
 * </ol>
 */
public class AIServiceContextBudgetTest {

    @Test
    public void convertsBudgetAt512AlignmentRoundingDown() {
        // 144 MiB / 12288 B = 12288 tokens（本机 Qwen3.5-2B 的真实数量级）
        assertEquals(12288, AIService.maxContextByKvBudget(144, 12288));

        // 1 MiB / 1000 B = 1048.576 -> 向下对齐到 1024
        assertEquals(1024, AIService.maxContextByKvBudget(1, 1000));

        // 结果永远是 512 的整数倍（1MB 对 12288 B/token 不足一个对齐块，结果应为 0）
        assertEquals("不足一个对齐块时返回 0", 0, AIService.maxContextByKvBudget(1, 12288));
        for (long budgetMb : new long[]{7, 64, 144, 598, 2048, 2770}) {
            int ctx = AIService.maxContextByKvBudget(budgetMb, 12288);
            assertTrue("budget=" + budgetMb + "MB -> " + ctx + " 不是 512 的整数倍",
                    ctx > 0 && ctx % 512 == 0);
        }
    }

    @Test
    public void unknownOrZeroBudgetYieldsZero() {
        assertEquals("无预算", 0, AIService.maxContextByKvBudget(0, 12288));
        assertEquals("负预算", 0, AIService.maxContextByKvBudget(-100, 12288));
        assertEquals("KV 代价未知", 0, AIService.maxContextByKvBudget(1024, 0));
        assertEquals("负 KV 代价", 0, AIService.maxContextByKvBudget(1024, -1));
    }

    @Test
    public void manual32kIsNotClippedByTheQuarterMemoryCeiling() {
        // 11GB 设备：totalMemoryMB≈11083，物理上限 = /4 ≈ 2770MB
        int ceiling = AIService.maxContextByKvBudget(11083L / 4, 12288);
        assertTrue("11GB 设备 + 12KB/token 的物理上限应远高于 32768，实际=" + ceiling,
                ceiling > 32768);

        // 老模型 90KB/token 才可能撞到上限：2770MB / 92160 ≈ 31528 tokens
        int legacy = AIService.maxContextByKvBudget(11083L / 4, 92160);
        assertTrue("90KB/token 的老模型应被物理上限压到 3 万出头，实际=" + legacy,
                legacy > 30000 && legacy < 32768);
    }

    @Test
    public void residentReleaseBaselineRestoresTheObservedWindow() {
        // 真机 11-07 的账：瞬时 avail=1399MB + 旧权重 1158MB = 2557MB 基线；
        // 80% = 2045MB，扣权重 1158MB + 缓冲 289MB => KV 预算 598MB。
        // 12288 B/token 下可容纳 ~5.1 万 tokens，静态档位 12288 不再是瓶颈
        // （旧实现直接用 1399MB 做减法，kvBudget=0，ctx 落到 floor 4096）。
        int ctx = AIService.maxContextByKvBudget(598, 12288);
        assertTrue("修复后的基线应能撑住 12288 档位，实际=" + ctx, ctx >= 12288);
    }
}
