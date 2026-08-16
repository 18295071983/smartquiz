# -*- coding: utf-8 -*-
"""
Android UI 操作工具模块
为 Agent Python 代码提供 Android UI 操作能力

工作机制:
- Agent 执行 Python 代码时，通过回调将 UI 操作请求返回给 Java 层
- Java 层接收 UI 操作请求后，执行 Toast/Dialog/进度更新
- 所有操作异步返回标准结果格式

功能:
1. Toast 提示 (show_toast)
2. 对话框 (show_dialog)  
3. 进度更新 (update_progress)
4. 通知 Java 回调 (notify_java)
"""

import json


# 全局回调存储（由 Java 层注入）
_ui_callback = None


def set_ui_callback(callback):
    """
    设置 UI 操作回调（由 Java 层调用）
    
    Args:
        callback: 回调函数，接收 ui_action dict，返回结果 dict
    """
    global _ui_callback
    _ui_callback = callback


def _do_ui_action(action):
    """
    执行 UI 操作（内部使用）
    
    Args:
        action: UI 操作 dict，如 {"type": "toast", "message": "hello"}
    
    Returns:
        dict: {'success': bool, 'message': str, ...}
    """
    global _ui_callback
    if _ui_callback is None:
        # 没有回调时，只返回结果不执行
        print(f"[UI ACTION NOT EXECUTED] {action}")
        return {'success': False, 'message': 'UI callback not set'}
    
    try:
        result = _ui_callback(action)
        return result if isinstance(result, dict) else {'success': True, 'result': result}
    except Exception as e:
        return {'success': False, 'message': str(e)}


def show_toast(message, duration="short"):
    """
    显示 Toast 提示
    
    用法:
        show_toast("导出完成")
        show_toast("处理中...", "long")
    
    参数:
        message: 提示文本
        duration: 显示时长，"short"（2秒）或 "long"（3.5秒）
    
    返回:
        dict: {'success': bool, 'message': str}
    """
    action = {
        'type': 'toast',
        'message': str(message),
        'duration': duration
    }
    return _do_ui_action(action)


def show_dialog(title, message, dialog_type="info", callback=None):
    """
    显示对话框
    
    用法:
        show_dialog("提示", "导出成功！", "info")
    
    参数:
        title: 对话框标题
        message: 对话框内容
        dialog_type: 对话框类型
            - "info": 信息提示（只有确定按钮）
            - "confirm": 确认框（确定/取消）
            - "warning": 警告框（红色警告）
        callback: 回调函数，接收用户选择结果
    
    返回:
        dict: {'success': bool, 'result': str, 'message': str}
              result: "positive"（确定）/ "negative"（取消）/ "cancelled"
    """
    action = {
        'type': 'dialog',
        'title': str(title),
        'message': str(message),
        'dialog_type': dialog_type
    }
    result = _do_ui_action(action)
    
    # 如果提供了回调，在 UI 线程执行
    if callback and 'result' in result:
        try:
            callback(result['result'])
        except Exception:
            pass
    
    return result


def update_progress(current, total, message=None):
    """
    更新导出进度
    
    用法:
        update_progress(10, 100, "正在处理第 10 题...")
    
    参数:
        current: 当前进度值
        total: 总进度值
        message: 可选的进度消息
    
    返回:
        dict: {'success': bool, 'progress': int, 'message': str}
    """
    progress_pct = int((current / total * 100) if total > 0 else 0)
    action = {
        'type': 'progress',
        'current': current,
        'total': total,
        'progress_pct': progress_pct,
        'message': str(message) if message else ""
    }
    return _do_ui_action(action)


def notify_java(event_type, data=None):
    """
    通知 Java 层事件
    
    用法:
        notify_java("export_complete", {"file_count": 5})
        notify_java("error", {"message": "导出失败"})
    
    参数:
        event_type: 事件类型
            - "export_start": 导出开始
            - "export_complete": 导出完成
            - "export_progress": 进度更新
            - "error": 错误
        data: 可选的附加数据（字典或 JSON 字符串）
    
    返回:
        dict: {'success': bool, 'message': str}
    """
    if isinstance(data, dict):
        data_str = json.dumps(data, ensure_ascii=False)
    else:
        data_str = str(data) if data else ""
    
    action = {
        'type': 'java_event',
        'event_type': str(event_type),
        'data': data_str
    }
    return _do_ui_action(action)
