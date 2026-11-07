#pragma once

// ============================================================================
// PROBE-BRIDGE(2026-11-08)：native-lib.cpp（活体引擎所在）与 native-probe.cpp
// （JNI 壳）之间的窄接口。
//
// 为什么是"返回 POD 快照"而不是传裸指针：s_helperContext / g_chatContext 是
// native-lib.cpp 的文件级 static，跨编译单元只传指针等于把生命周期约定撕开——
// 装载线程 delete 掉旧 context 后，探测线程可能正拿着悬空指针调 llama API。
// 这里只让 native-lib.cpp 内部解引用句柄（它本来就知道模型何时换），外部拿到的
// 是拷贝出来的数字。并发口径与既有 nativeGetContextSize 一致：不加新锁
// （新锁若不覆盖 initModel 就是假安全），装载由 Java 侧 modelInitLock 串行化。
// ============================================================================

#include "sys_probe.h"

namespace probebridge {

// 当前活体引擎快照；未加载模型时 valid=false。
sysprobe::EngineInfo engineInfo();

// seq 0 的尾部截断 / 清空；无活体引擎时返回 false。
bool kvTrimTail(int keepTokens);
bool kvClear();

} // namespace probebridge
