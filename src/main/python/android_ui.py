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
5. 动态系统 UI 组件 API（组件"握手"：create → update → close → get_result）:
   - create_component(type, ...) -> {component_id}
   - update_component(component_id, ...)
   - close_component(component_id)
   - get_component_result(component_id, wait_seconds=0)
   组件类型: "dialog"（系统对话框）/ "progress"（水平进度条对话框）
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
        # 兼容两种回调形式：Java 对象方法 handle(action) / Python 可调用对象 (action)
        if hasattr(_ui_callback, 'handle'):
            result = _ui_callback.handle(action)
        else:
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


# ==================== 动态系统 UI 组件 API ====================
# 组件"握手"协议：create_component 返回 component_id，
# 之后用该 id 调用 update_component / close_component / get_component_result。
# 组件类型:
#   - "dialog": 系统对话框（info=确定 / confirm=确定+取消 / warning=知道了）
#   - "progress": 水平进度条对话框（update_component 推进进度，到 max 自动关闭）

def create_component(component_type, title="", message="", component_id=None,
                     dialog_type="info", max_value=100, options=None,
                     default_value="", input_hint="", props=None, action_label="",
                     items=None, url="", click_action="", auto_close=0):
    """
    创建动态系统 UI 组件。

    用法:
        # 系统原生组件:
        # 对话框（info/confirm/warning）
        dlg = create_component("dialog", "确认", "确定要删除吗？", dialog_type="confirm")
        cid = dlg["component_id"]
        result = get_component_result(cid, wait_seconds=30)  # 阻塞等待用户选择
        if result["result"] == "positive": ...
    
        # 进度条
        prog = create_component("progress", "正在导出", "准备中...", max_value=100)
        cid = prog["component_id"]
        update_component(cid, progress=30, message="处理第 3 题...")
        update_component(cid, progress=100)  # 完成后自动关闭
    
        # 文本输入（结果=用户输入文本）
        inp = create_component("input", "请输入文件名", input_hint="例如: report.xlsx",
                               default_value="report")
        cid = inp["component_id"]
        r = get_component_result(cid, wait_seconds=60)
        if r["result"] != "cancelled": name = r["result"]
    
        # 选项选择（结果=选中项文本）
        cho = create_component("choice", "选择导出格式",
                               options=["xlsx", "csv", "json"], default_value="xlsx")
        cid = cho["component_id"]
        r = get_component_result(cid, wait_seconds=60)
        if r["result"] != "cancelled": fmt = r["result"]

        # 内置 UI 组件（项目自带组件库，直接填参数渲染成卡片弹窗展示）:
        #   chart / info_card / table_card / image_grid / link_card / list_card /
        #   alert_card / metric_card / json_viewer / steps_card / note_card /
        #   file_list / grid_card / contact_card / todo_card / quiz_card /
        #   weather_card / file_card / code_card / progress_card / html
        # 用法：create_component("类型", props={...参数...})
        # 例如：
        #   create_component("info_card", props={"title":"用户信息","items":[{"label":"姓名","value":"张三"}]})
        #   create_component("table_card", props={"title":"成绩表","headers":["科目","分数"],"rows":[["语文","90"],["数学","95"]]})
        #   create_component("alert_card", props={"type":"success","title":"操作成功","content":"已保存"})
        #   create_component("chart", props={"chartType":"bar","title":"销量","categories":["1月","2月"],"series":[{"name":"销量","data":[120,200]}]})
        #   create_component("metric_card", props={"title":"今日数据","metrics":[{"label":"做题数","value":"120","color":"success"}]})
        #   create_component("steps_card", props={"title":"执行步骤","steps":[{"status":"done","title":"解析","description":"..."},{"status":"current","title":"导入"}]})
        #   create_component("list_card", props={"title":"清单","items":[{"icon":"📁","title":"报告","description":"说明"}]})
        #   create_component("progress_card", props={"title":"任务进度","progress":60,"description":"已完成60%"})
        #   create_component("html", props={"html":"<h3>标题</h3><p>富文本内容</p>","title":"可选标题"})
        #   create_component("todo_card", props={"title":"待办","items":[{"text":"完成报告","done":true},{"text":"回复邮件","done":false}]})
        #   create_component("quiz_card", props={"type":"single","question":"1+1=?","options":["A. 1","B. 2"],"answer":"B","analysis":"2"})
        #   create_component("weather_card", props={"city":"北京","temp":"26℃","text":"多云","icon":"⛅"})
        #   create_component("json_viewer", props={"title":"数据","data":{...}})
        #   create_component("note_card", props={"type":"tip","content":"提示内容"})
        #   create_component("grid_card", props={"title":"宫格","columns":3,"items":[{"icon":"📁","label":"标签"}]})
        #   create_component("link_card", props={"title":"文章","url":"https://..."})
        #   create_component("contact_card", props={"type":"phone","value":"13800138000","title":"联系人"})
        #   create_component("code_card", props={"language":"python","code":"print('hi')","title":"代码"})
        #   create_component("file_card", props={"name":"文件.txt","size":"1KB","path":"/sdcard/..."})
        #   create_component("file_list", props={"title":"目录","files":[{"name":"a.txt","size":"1KB","path":"/sdcard/a.txt"}]})
        #   create_component("image_grid", props={"images":["url1","url2"],"columns":2})
        #   create_component("custom_panel", props={...任意自定义字段...})  # 未注册类型自动通用卡片兜底
    
    参数:
        component_type: 组件类型（系统原生: dialog/progress/input/choice/multi_choice/date/time/image/snackbar；或内置 UI 组件类型）
        title: 标题
        message: 内容/进度说明
        component_id: 可选自定义ID（不传则自动生成）
        dialog_type: 仅 dialog 使用: "info" | "confirm" | "warning"
        max_value: 仅 progress 使用: 进度最大值（默认100）
        options: 仅 choice/multi_choice 使用: 选项列表
        default_value: input/choice/date/time/image 使用: 默认值（image 传本地路径或http(s) url）
        input_hint: 仅 input 使用: 输入框提示文字
        action_label: 仅 snackbar 使用: 操作按钮文字（不传则无按钮）
        props: 内置 UI 组件参数字典（component_type 为内置类型时使用）
        auto_close: 自动关闭秒数（>0 时创建 N 秒后自动关闭，默认 0 不自动关）
    
    返回:
        dict: {'success': bool, 'component_id': str, 'result': {'component_id': str, 'type': str}, 'message': str}
              组件ID两种取法（等价）:
                cid = r["component_id"]
                cid = r["result"]["component_id"]
    """
    action = {
        'type': 'create_component',
        'component_type': str(component_type),
        'title': str(title),
        'message': str(message),
        'dialog_type': str(dialog_type),
        'max': int(max_value) if max_value else 100,
        'default_value': str(default_value) if default_value else "",
        'input_hint': str(input_hint) if input_hint else "",
        'action_label': str(action_label) if action_label else "",
        'url': str(url) if url else "",
        'click_action': str(click_action) if click_action else "",
        'auto_close': int(auto_close) if auto_close else 0,
    }
    if options is not None:
        action['options'] = json.dumps(options, ensure_ascii=False)
    if props is not None:
        action['props'] = json.dumps(props, ensure_ascii=False)
    if items is not None:
        action['items'] = json.dumps(items, ensure_ascii=False)
    if component_id:
        action['component_id'] = str(component_id)
    return _do_ui_action(action)


def _extract_component_id(resp):
    """从 create_component 返回值中提取 component_id（兼容两种结构）。"""
    if not isinstance(resp, dict):
        return ""
    cid = resp.get("component_id")
    if cid:
        return str(cid)
    result = resp.get("result")
    if isinstance(result, dict):
        cid2 = result.get("component_id")
        if cid2:
            return str(cid2)
    return ""


def update_component(component_id, progress=-1, message=None, title=None, max_value=0):
    """
    更新动态组件。
    
    用法:
        update_component(cid, progress=50, message="完成一半")
        update_component(cid, progress=100)   # progress >= max 时自动关闭
    
    参数:
        component_id: create_component 返回的组件ID
        progress: 新进度值（-1 表示不更新）
        message: 新的进度/内容文字
        title: 新标题
        max_value: 新最大值（0 表示不更新）
    
    返回:
        dict: {'success': bool, 'component_id': str, 'message': str}
    """
    action = {
        'type': 'update_component',
        'component_id': str(component_id),
        'progress': int(progress) if progress is not None else -1,
        'max': int(max_value) if max_value else 0,
    }
    if message is not None:
        action['message'] = str(message)
    if title is not None:
        action['title'] = str(title)
    return _do_ui_action(action)


def close_component(component_id):
    """
    关闭并移除动态组件。
    
    用法:
        close_component(cid)
    
    返回:
        dict: {'success': bool, 'component_id': str, 'message': str}
    """
    action = {
        'type': 'close_component',
        'component_id': str(component_id),
    }
    return _do_ui_action(action)


def get_component_result(component_id, wait_seconds=0):
    """
    获取组件结果（对话框用户选择 / 进度完成状态）。
    
    用法:
        # 不等待（立即返回当前状态）
        result = get_component_result(cid)
        # 阻塞等待最多 30 秒（对话框场景常用）
        result = get_component_result(cid, wait_seconds=30)
    
    参数:
        component_id: create_component 返回的组件ID
        wait_seconds: 阻塞等待秒数（0=不等待立即返回）
    
    返回:
        dict: {'success': bool, 'component_id': str, 'result': str}
              result: dialog: "pending"/"positive"/"negative"/"cancelled"
                      progress: "pending"/"completed"/"cancelled"
    """
    action = {
        'type': 'get_component_result',
        'component_id': str(component_id),
        'wait_seconds': int(wait_seconds) if wait_seconds else 0,
    }
    return _do_ui_action(action)


def show_progress(title, message="", max_value=100):
    """
    便捷函数：显示进度条对话框并返回组件ID（配合 update_progress_component 使用）。
    
    用法:
        cid = show_progress("正在导出", "准备中...")
        update_progress_component(cid, 50, "处理中")
        close_component(cid)
    
    返回:
        str: component_id
    """
    result = create_component("progress", title, message, max_value=max_value)
    return _extract_component_id(result) if result.get("success") else ""


def update_progress_component(component_id, current, total, message=None):
    """
    便捷函数：按 current/total 更新进度条组件（进度到顶自动关闭）。
    
    用法:
        update_progress_component(cid, 3, 10, "第 3 项")
    
    返回:
        dict: 更新结果
    """
    pct = int((current / total * 100)) if total > 0 else 100
    return update_component(component_id, progress=pct, message=message)


def _get_result_value(resp):
    """从 get_component_result 返回值中提取结果值（兼容 result 为字符串或 dict）。"""
    if not isinstance(resp, dict):
        return "cancelled"
    result = resp.get("result")
    if isinstance(result, dict):
        # 兼容 result 嵌套结构（如 result={'result': 'positive'})
        return result.get("result", "cancelled")
    return result if result is not None else "cancelled"


def ask_input(title, message="", default_value="", input_hint="", wait_seconds=60):
    """
    便捷函数：系统文本输入框，阻塞等待用户输入。
    
    用法:
        name = ask_input("请输入文件名", input_hint="例如: report.xlsx")
        if name != "cancelled": ...
    
    返回:
        str: 用户输入文本；用户取消/超时返回 "cancelled"
    """
    r = create_component("input", title, message, default_value=default_value,
                         input_hint=input_hint)
    if not r.get("success"):
        return "cancelled"
    cid = _extract_component_id(r)
    if not cid:
        return "cancelled"
    res = get_component_result(cid, wait_seconds=wait_seconds)
    return _get_result_value(res)


def ask_choice(title, options, message="", default_value="", wait_seconds=60):
    """
    便捷函数：系统选项选择框，阻塞等待用户选择。
    
    用法:
        fmt = ask_choice("选择导出格式", ["xlsx", "csv", "json"])
        if fmt != "cancelled": ...
    
    参数:
        options: 选项列表（字符串列表 或 [{"label":..,"value":..}]）
    
    返回:
        str: 选中项文本；用户取消/超时返回 "cancelled"
    """
    r = create_component("choice", title, message, options=options,
                         default_value=default_value)
    if not r.get("success"):
        return "cancelled"
    cid = _extract_component_id(r)
    if not cid:
        return "cancelled"
    res = get_component_result(cid, wait_seconds=wait_seconds)
    return _get_result_value(res)

