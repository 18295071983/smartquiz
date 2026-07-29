package com.oilquiz.app.ai.agent;

/**
 * 执行引擎事件监听器
 *
 * UI层实现此接口来接收引擎事件
 * 引擎通过此接口通知UI，而不是直接操作Activity
 */
public interface ExecutionEventListener {
    void onExecutionEvent(ExecutionEvent event);
}