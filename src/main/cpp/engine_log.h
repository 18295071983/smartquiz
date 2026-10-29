#ifndef ENGINE_LOG_H
#define ENGINE_LOG_H

#include <android/log.h>

/**
 * 推理引擎日志环（2026-10-30）：
 * native 的 LOGx / AGENT_KV_LOGx 除了写 logcat，也会追加到这个进程内环形缓冲，
 * 由 Java 侧 drain 到「日志中心」的 AI 服务 Tab（无需 READ_LOGS／logcat 权限）。
 * 只保留最近若干行，drain 即清空；不影响 logcat 原有输出。
 */
void engineLogAppend(int prio, const char* tag, const char* fmt, ...);

/** 指定引擎日志落盘文件（空串关闭）。由 Java 在启动时设置，追加写入、超过上限自动截断。 */
void engineLogSetFile(const char* path);

#endif // ENGINE_LOG_H
