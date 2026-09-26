# -*- coding: utf-8 -*-
"""
Android 环境辅助模块
为 Agent Python 代码提供文件操作、数据处理、数据库访问等能力
Agent 执行 Python 代码时会自动导入此模块
"""

import os
import sys
import json
import csv
import sqlite3
import xml.etree.ElementTree as ET
import re
import io
from collections import Counter, OrderedDict

# ==================== 文件操作 ====================

def read_file(path, encoding='utf-8'):
    """读取文本文件内容"""
    with open(path, 'r', encoding=encoding) as f:
        return f.read()

def write_file(path, content, encoding='utf-8'):
    """写入文本文件"""
    os.makedirs(os.path.dirname(path) if os.path.dirname(path) else '.', exist_ok=True)
    with open(path, 'w', encoding=encoding) as f:
        f.write(content)
    return f"已写入 {path} ({len(content)} 字符)"

def list_files(path='.', recursive=False):
    """列出目录内容"""
    results = []
    if recursive:
        for root, dirs, files in os.walk(path):
            for f in files:
                full = os.path.join(root, f)
                results.append({'path': full, 'size': os.path.getsize(full), 'name': f})
            for d in dirs:
                results.append({'path': os.path.join(root, d), 'is_dir': True, 'name': d})
    else:
        for item in os.listdir(path):
            full = os.path.join(path, item)
            if os.path.isdir(full):
                results.append({'path': full, 'is_dir': True, 'name': item})
            else:
                results.append({'path': full, 'size': os.path.getsize(full), 'name': item})
    return results

def file_info(path):
    """获取文件信息"""
    stat = os.stat(path)
    return {
        'path': os.path.abspath(path),
        'name': os.path.basename(path),
        'size': stat.st_size,
        'modified': stat.st_mtime,
        'is_file': os.path.isfile(path),
        'is_dir': os.path.isdir(path),
        'extension': os.path.splitext(path)[1]
    }

# ==================== CSV 处理 ====================

def read_csv(path, delimiter=',', encoding='utf-8'):
    """读取CSV文件，返回字典列表"""
    rows = []
    with open(path, 'r', encoding=encoding, newline='') as f:
        reader = csv.DictReader(f, delimiter=delimiter)
        for row in reader:
            rows.append(dict(row))
    return rows

def write_csv(path, data, fieldnames=None, encoding='utf-8'):
    """写入CSV文件"""
    if not data:
        return "数据为空"
    if fieldnames is None:
        fieldnames = list(data[0].keys())
    os.makedirs(os.path.dirname(path) if os.path.dirname(path) else '.', exist_ok=True)
    with open(path, 'w', encoding=encoding, newline='') as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(data)
    return f"已写入 {len(data)} 行到 {path}"

def csv_summary(path, delimiter=','):
    """CSV文件摘要：行数、列名、前几行"""
    with open(path, 'r', encoding='utf-8', newline='') as f:
        reader = csv.reader(f, delimiter=delimiter)
        headers = next(reader, [])
        rows = []
        for i, row in enumerate(reader):
            if i < 5:
                rows.append(row)
            else:
                break
        # 统计总行数
        count = 5 + sum(1 for _ in reader)
    return {
        'headers': headers,
        'column_count': len(headers),
        'total_rows': count,
        'preview': rows
    }

# ==================== JSON 处理 ====================

def read_json(path, encoding='utf-8'):
    """读取JSON文件"""
    with open(path, 'r', encoding=encoding) as f:
        return json.load(f)

def write_json(path, data, indent=2, encoding='utf-8'):
    """写入JSON文件"""
    os.makedirs(os.path.dirname(path) if os.path.dirname(path) else '.', exist_ok=True)
    with open(path, 'w', encoding=encoding) as f:
        json.dump(data, f, ensure_ascii=False, indent=indent)
    return f"已写入 {path}"

# ==================== XML 处理 ====================

def read_xml(path, target_tag=None):
    """读取XML文件，可选按标签提取"""
    tree = ET.parse(path)
    root = tree.getroot()
    if target_tag:
        items = []
        for elem in root.iter(target_tag):
            items.append({
                'text': elem.text.strip() if elem.text else '',
                'tag': elem.tag,
                'attrib': dict(elem.attrib)
            })
        return {'matched': len(items), 'data': items}
    else:
        # 返回结构概览
        summary = {}
        for elem in root.iter():
            tag = elem.tag.split('}')[-1] if '}' in elem.tag else elem.tag
            summary[tag] = summary.get(tag, 0) + 1
        return {
            'root': root.tag,
            'total_elements': len(list(root.iter())),
            'tag_summary': summary
        }

def parse_xlsx_xml(xml_path, shared_strings_path=None):
    """解析从xlsx提取的XML工作表"""
    tree = ET.parse(xml_path)
    root = tree.getroot()
    ns = {'s': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
    
    # 加载共享字符串
    strings = []
    if shared_strings_path and os.path.exists(shared_strings_path):
        ss_tree = ET.parse(shared_strings_path)
        ss_root = ss_tree.getroot()
        for si in ss_root.findall('.//s:si', ns):
            t = si.find('s:t', ns)
            strings.append(t.text if t is not None and t.text else '')
    
    rows = []
    for row_elem in root.findall('.//s:row', ns):
        row_data = {}
        for cell in row_elem.findall('s:c', ns):
            ref = cell.get('r', '')
            v = cell.find('s:v', ns)
            t = cell.get('t', '')
            if v is not None and v.text:
                if t == 's' and strings:
                    idx = int(v.text)
                    row_data[ref] = strings[idx] if idx < len(strings) else ''
                else:
                    row_data[ref] = v.text
        if row_data:
            rows.append(row_data)
    return rows

# ==================== SQLite 数据库 ====================

def db_connect(db_path):
    """连接SQLite数据库"""
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    return conn

def _connect_db(db_path):
    """连接 SQLite 数据库（Room WAL 兼容：busy_timeout + 重试）"""
    import sqlite3 as _s
    conn = _s.connect(db_path, timeout=10)
    conn.row_factory = _s.Row
    try:
        conn.execute('PRAGMA busy_timeout = 10000')
        conn.execute('PRAGMA journal_mode = WAL')
    except Exception:
        pass
    return conn

def db_query(db_path, sql, params=None):
    """执行SQL查询，返回字典列表"""
    conn = _connect_db(db_path)
    try:
        cursor = conn.cursor()
        cursor.execute(sql, params or [])
        columns = [desc[0] for desc in cursor.description] if cursor.description else []
        rows = [dict(zip(columns, row)) for row in cursor.fetchall()]
        return {'columns': columns, 'rows': rows, 'count': len(rows)}
    finally:
        conn.close()

def db_execute(db_path, sql, params=None):
    """执行SQL写操作（WAL 下自动重试，避免与 Room 并发写锁冲突）"""
    conn = _connect_db(db_path)
    try:
        cursor = conn.cursor()
        for attempt in range(3):
            try:
                cursor.execute(sql, params or [])
                break
            except Exception as e:
                if 'locked' in str(e).lower() and attempt < 2:
                    import time as _t
                    _t.sleep(0.5)
                    continue
                raise
        conn.commit()
        return {'affected_rows': cursor.rowcount, 'last_id': cursor.lastrowid}
    finally:
        conn.close()

def db_tables(db_path):
    """列出数据库所有表"""
    return db_query(db_path, "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")

def db_schema(db_path, table_name=None):
    """获取表结构信息"""
    if table_name:
        return db_query(db_path, f"PRAGMA table_info({table_name})")
    else:
        tables = db_tables(db_path)
        result = []
        for t in tables['rows']:
            info = db_query(db_path, f"PRAGMA table_info({t['name']})")
            result.append({'table': t['name'], 'columns': info['rows']})
        return result

# ==================== 数据统计 ====================

def data_stats(data, key=None):
    """对数据进行统计描述"""
    if key:
        values = [d[key] for d in data if key in d]
    else:
        values = data
    
    numeric = []
    text = []
    for v in values:
        try:
            numeric.append(float(v))
        except (ValueError, TypeError):
            text.append(str(v))
    
    result = {'total': len(values)}
    if numeric:
        result['numeric_summary'] = {
            'count': len(numeric),
            'min': min(numeric),
            'max': max(numeric),
            'mean': sum(numeric) / len(numeric),
            'sum': sum(numeric)
        }
    if text:
        counter = Counter(text)
        result['text_summary'] = {
            'unique': len(set(text)),
            'top5': counter.most_common(5)
        }
    return result

# ==================== 应用数据目录 ====================

def get_app_dir():
    """获取应用数据目录（files/ 目录）"""
    try:
        # 常见Android应用数据路径
        for p in ['/data/user/0/com.oilquiz.app/files',
                  '/data/data/com.oilquiz.app/files']:
            if os.path.exists(p):
                return p
    except:
        pass
    return '.'

def get_db_path(db_name='smartquiz_database'):
    """获取数据库文件路径。

    Room 数据库实际位于 <app_data>/databases/<name>（不是 files/ 下），
    此前候选路径全部错误导致 Python 连不上真实库（新建了空库，导入无效）。
    """
    app_dir = get_app_dir()
    # 优先 databases/ 目录（Room 默认位置）
    candidates = [
        os.path.join(os.path.dirname(app_dir), 'databases', db_name),
        os.path.join(app_dir, 'databases', db_name),
        os.path.join(app_dir, db_name),
        os.path.join(app_dir, 'oilquiz', db_name),
        os.path.join('/data/data/com.oilquiz.app', 'databases', db_name),
        os.path.join('/data/user/0/com.oilquiz.app', 'databases', db_name),
    ]
    for c in candidates:
        if os.path.exists(c):
            return c
    # 找不到时返回最可能的路径（Room 默认），调用方会因文件不存在得到明确错误
    return os.path.join(os.path.dirname(app_dir), 'databases', db_name)

def bulk_import_questions(data, db_name='smartquiz_database'):
    """
    批量导入题目到数据库。
    
    参数:
        data: JSON文件路径 或 题目字典列表
            每个字典可包含: questionText, optionA-D, correctAnswer,
            category, difficulty, explanation, questionType, source 等
        db_name: 数据库名(默认smartquiz_database)
    
    返回:
        {'success': bool, 'imported': int, 'failed': int, 'errors': [...]}
    """
    import time as _time
    
    # 解析输入
    if isinstance(data, str):
        # 文件路径：读取JSON
        if not os.path.exists(data):
            return {'success': False, 'imported': 0, 'failed': 0, 'errors': ['文件不存在: ' + data]}
        with open(data, 'r', encoding='utf-8') as f:
            questions = json.load(f)
    elif isinstance(data, list):
        questions = data
    else:
        return {'success': False, 'imported': 0, 'failed': 0, 'errors': ['参数类型不支持']}
    
    if not questions or not isinstance(questions, list):
        return {'success': False, 'imported': 0, 'failed': 0, 'errors': ['没有题目数据']}
    
    db_path = get_db_path(db_name)
    
    # 允许的字段及其数据库列名
    allowed_fields = {
        'questionText', 'optionA', 'optionB', 'optionC', 'optionD',
        'optionE', 'optionF', 'optionG', 'optionH',
        'optionI', 'optionJ', 'optionK', 'optionL',
        'correctAnswer', 'category', 'difficulty', 'explanation',
        'relatedQuestion', 'questionType', 'favorite',
        'createdAt', 'updatedAt', 'source', 'tags',
        'points', 'timeLimit', 'hint', 'analysis',
        'knowledgePoint', 'subCategory',
        'usageCount', 'correctCount', 'incorrectCount', 'lastUsedAt',
        'status', 'isPublic', 'author', 'comment',
        'answerText', 'imageUri', 'audioUri', 'parentId', 'sortOrder'
    }
    
    # 整数类型字段
    int_fields = {
        'difficulty', 'points', 'timeLimit',
        'usageCount', 'correctCount', 'incorrectCount', 'lastUsedAt',
        'status', 'isPublic', 'createdAt', 'updatedAt',
        'parentId', 'sortOrder'
    }
    
    # 布尔类型字段（SQLite存为0/1）
    bool_fields = {'favorite'}
    
    # 使用timeout避免与Room的WAL模式锁定冲突
    conn = _connect_db(db_path)
    imported = 0
    failed = 0
    errors = []
    
    try:
        now_ms = int(_time.time() * 1000)
        cursor = conn.cursor()
        
        for i, q in enumerate(questions):
            if not isinstance(q, dict):
                failed += 1
                errors.append('第' + str(i+1) + '条: 不是字典格式')
                continue
            
            # 必须有题目内容
            qt = q.get('questionText') or q.get('question_text') or q.get('question') or ''
            if not qt.strip():
                failed += 1
                errors.append('第' + str(i+1) + '条: 题目内容为空')
                continue
            
            # 构建INSERT语句
            cols = ['questionText']
            vals = [qt.strip()]
            
            for field in allowed_fields:
                if field == 'questionText':
                    continue
                # 支持多种字段名格式
                val = q.get(field)
                if val is None:
                    # 尝试小写开头
                    snake = field[0].lower() + field[1:]
                    val = q.get(snake)
                if val is None:
                    # 尝试全蛇形命名 (answerText -> answer_text)
                    import re as _re
                    snake_full = _re.sub(r'(?<!^)(?=[A-Z])', '_', field).lower()
                    val = q.get(snake_full)
                if val is not None:
                    cols.append(field)
                    if field in bool_fields:
                        # 布尔转0/1
                        if isinstance(val, bool):
                            vals.append(1 if val else 0)
                        elif isinstance(val, (int, float)):
                            vals.append(1 if val else 0)
                        elif isinstance(val, str):
                            vals.append(1 if val.lower() in ('true', '1', 'yes') else 0)
                        else:
                            vals.append(0)
                    elif field in int_fields:
                        try:
                            vals.append(int(val))
                        except (ValueError, TypeError):
                            vals.append(0)
                    else:
                        vals.append(str(val))
            
            # 自动设置时间戳
            if 'createdAt' not in cols:
                cols.append('createdAt')
                vals.append(now_ms)
            if 'updatedAt' not in cols:
                cols.append('updatedAt')
                vals.append(now_ms)
            
            placeholders = ', '.join(['?'] * len(cols))
            col_names = ', '.join(cols)
            sql = 'INSERT INTO question (' + col_names + ') VALUES (' + placeholders + ')'
            
            try:
                cursor.execute(sql, vals)
                imported += 1
            except Exception as e:
                failed += 1
                errors.append('第' + str(i+1) + '条: ' + str(e))
        
        conn.commit()
        
        return {
            'success': imported > 0,
            'imported': imported,
            'failed': failed,
            'total': len(questions),
            'errors': errors[:10]  # 最多返回10条错误
        }
    except Exception as e:
        conn.rollback()
        return {'success': False, 'imported': 0, 'failed': len(questions), 'errors': [str(e)]}
    finally:
        conn.close()

# ==================== Python 文件执行 ====================

def run_python_file(file_path, args=None, timeout=60):
    """执行一个 Python 文件，返回其 stdout 输出。

    Chaquopy 环境下无法用 sys.executable 启动子进程（Android 无独立 python 可执行文件），
    改为在当前解释器内 exec 执行，并捕获 stdout/stderr。
    timeout: 超时秒数（默认 60），防止死循环脚本永久卡住（偶发故障根因之一）。
    """
    import io as _io
    from contextlib import redirect_stdout, redirect_stderr
    if not os.path.exists(file_path):
        return f"错误: 文件不存在 {file_path}"
    with open(file_path, 'r', encoding='utf-8') as f:
        code = f.read()

    namespace = {
        '__name__': '__main__',
        '__builtins__': __builtins__,
    }
    if args:
        namespace['sys_argv'] = args
        namespace['args'] = args

    stdout_buf = _io.StringIO()
    stderr_buf = _io.StringIO()
    result_holder = {}
    import threading as _threading

    def _run():
        try:
            with redirect_stdout(stdout_buf), redirect_stderr(stderr_buf):
                exec(compile(code, file_path, 'exec'), namespace, namespace)
            output = stdout_buf.getvalue()
            err = stderr_buf.getvalue()
            if err:
                output += '\n[STDERR] ' + err
            result_holder['output'] = output if output else '(无输出)'
        except Exception as e:
            import traceback as _tb
            result_holder['output'] = f'错误: {e}\n{_tb.format_exc()}'

    thread = _threading.Thread(target=_run, daemon=True)
    thread.start()
    thread.join(timeout)
    if thread.is_alive():
        return f"错误: 执行超时（{timeout}秒），脚本可能包含死循环"
    return result_holder.get('output', '(无输出)')

def create_python_file(name, code, directory=None):
    """创建一个 Python 文件并返回路径"""
    if directory is None:
        directory = os.path.join(get_app_dir(), 'python_tools', 'scripts')
    os.makedirs(directory, exist_ok=True)
    if not name.endswith('.py'):
        name += '.py'
    # 清理文件名
    safe_name = ''.join(c if c.isalnum() or c in '._-' else '_' for c in name)
    path = os.path.join(directory, safe_name)
    with open(path, 'w', encoding='utf-8') as f:
        f.write('# -*- coding: utf-8 -*-\n')
        f.write(code)
    return path

def create_and_run(name, code, args=None):
    """创建 Python 文件并立即执行"""
    path = create_python_file(name, code)
    result = run_python_file(path, args)
    return f"文件: {path}\n\n输出:\n{result}"

# ==================== matplotlib 中文字体 ====================

# Android 系统字体目录不在 matplotlib font_manager 的扫描范围内，
# 不注册就会画出一堆方框（"matplotlib 没有中文字体"）。
_CJK_FONT_CANDIDATES = [
    '/system/fonts/NotoSansCJK-Regular.ttc',
    '/system/fonts/NotoSansSC-Regular.otf',
    '/system/fonts/DroidSansFallbackFull.ttf',
    '/system/fonts/DroidSansFallback.ttf',
    '/system/fonts/SansSerif-Regular.ttf',
    '/system/fonts/NotoSerifCJK-Regular.ttc',
]

def cjk_font_path():
    """返回一个可用的中文字体文件路径（找不到返回 None）。"""
    cands = []
    try:
        base = os.path.dirname(os.path.abspath(__file__))
        cands += [os.path.join(base, 'simhei.ttf'), os.path.join(base, 'simkai.ttf')]
    except Exception:
        pass
    cands += _CJK_FONT_CANDIDATES
    for p in cands:
        try:
            if p and os.path.exists(p):
                return p
        except Exception:
            continue
    return None

def setup_matplotlib_cjk():
    """让 matplotlib 能正常显示中文，返回注册的字体名（失败返回 None）。

    用法（python_execute 里 android_helper 已 star-import，可直接调用）：
        setup_matplotlib_cjk()
        plt.title('中文标题')      # 不再显示方框
    """
    try:
        import matplotlib
        from matplotlib import font_manager
    except Exception:
        return None
    path = cjk_font_path()
    if not path:
        return None
    try:
        font_manager.fontManager.addfont(path)
        name = font_manager.FontProperties(fname=path).get_name()
        old = [f for f in matplotlib.rcParams.get('font.sans-serif', []) if f != name]
        matplotlib.rcParams['font.sans-serif'] = [name] + old
        matplotlib.rcParams['font.family'] = 'sans-serif'
        matplotlib.rcParams['axes.unicode_minus'] = False
        return name
    except Exception:
        return None
