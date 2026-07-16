# -*- coding: utf-8 -*-
"""
AI 驱动的工具自动创建系统
让 AI 能够自动发现、创建、测试和注册新工具
"""

import sys
import os
import re
import json
import time
import hashlib
from typing import Dict, List, Any, Optional, Tuple
from dataclasses import dataclass, asdict
from python_tool_engine import PythonToolEngine, get_engine


@dataclass
class ToolSpecification:
    """工具规范"""
    name: str
    description: str
    parameters: Dict[str, str]
    code: str
    examples: List[str]
    category: str = "general"
    version: str = "1.0.0"
    
    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


@dataclass
class ToolCreationResult:
    """工具创建结果"""
    success: bool
    tool_name: Optional[str] = None
    spec: Optional[ToolSpecification] = None
    error: Optional[str] = None
    attempts: int = 0
    test_results: Optional[Dict[str, Any]] = None
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            "success": self.success,
            "tool_name": self.tool_name,
            "spec": self.spec.to_dict() if self.spec else None,
            "error": self.error,
            "attempts": self.attempts,
            "test_results": self.test_results
        }


class AIToolCreator:
    """
    AI 工具创建器
    - 分析任务需求
    - 自动生成工具代码
    - 自动测试和修复
    - 自动保存和注册
    """
    
    def __init__(self, context=None):
        self.engine = get_engine(context)
        self.context = context
        self.tools_dir = os.path.join(self.engine.work_dir, "ai_tools")
        self.tools_index = os.path.join(self.tools_dir, "index.json")
        self._ensure_dirs()
        self._load_index()
    
    def _ensure_dirs(self):
        """确保目录存在"""
        if not os.path.exists(self.tools_dir):
            os.makedirs(self.tools_dir, exist_ok=True)
    
    def _load_index(self):
        """加载工具索引"""
        self.index = {}
        if os.path.exists(self.tools_index):
            try:
                with open(self.tools_index, 'r', encoding='utf-8') as f:
                    self.index = json.load(f)
            except:
                self.index = {}
    
    def _save_index(self):
        """保存工具索引"""
        try:
            with open(self.tools_index, 'w', encoding='utf-8') as f:
                json.dump(self.index, f, ensure_ascii=False, indent=2)
        except Exception as e:
            print(f"Error saving index: {e}")
    
    def analyze_task_for_tool(self, task: str, existing_tools: List[str] = None) -> Dict[str, Any]:
        """
        分析任务，判断是否需要新工具
        
        Args:
            task: 任务描述
            existing_tools: 现有工具列表
            
        Returns:
            {
                "needs_new_tool": bool,
                "tool_name": str,
                "tool_description": str,
                "parameters": dict,
                "reason": str
            }
        """
        result = {
            "needs_new_tool": False,
            "tool_name": None,
            "tool_description": None,
            "parameters": {},
            "reason": ""
        }
        
        task_lower = task.lower()
        existing = existing_tools or []
        existing_lower = [t.lower() for t in existing]
        
        patterns = {
            "unit_converter": {
                "keywords": ["换算", "转换", "单位", "convert", "unit"],
                "description": "单位转换工具，支持长度、重量、温度、货币等转换",
                "parameters": {
                    "value": "要转换的数值",
                    "from_unit": "源单位",
                    "to_unit": "目标单位",
                    "category": "单位类型（length, weight, temperature, currency）"
                }
            },
            "json_formatter": {
                "keywords": ["json", "格式化", "美化", "format", "beautify"],
                "description": "JSON 格式化和验证工具",
                "parameters": {
                    "json_str": "JSON 字符串",
                    "indent": "缩进空格数（可选，默认 2）"
                }
            },
            "regex_test": {
                "keywords": ["正则", "regex", "匹配", "提取", "match", "extract"],
                "description": "正则表达式测试和文本提取工具",
                "parameters": {
                    "pattern": "正则表达式",
                    "text": "测试文本",
                    "flags": "正则标志（可选）"
                }
            },
            "base64_tool": {
                "keywords": ["base64", "编码", "解码", "encode", "decode"],
                "description": "Base64 编码和解码工具",
                "parameters": {
                    "action": "操作类型：encode 或 decode",
                    "data": "要编码或解码的数据"
                }
            },
            "hash_tool": {
                "keywords": ["哈希", "hash", "md5", "sha1", "sha256", "加密"],
                "description": "哈希计算工具（MD5, SHA1, SHA256 等）",
                "parameters": {
                    "algorithm": "哈希算法：md5, sha1, sha256, sha512",
                    "data": "要计算哈希的数据"
                }
            },
            "timestamp_tool": {
                "keywords": ["时间戳", "timestamp", "日期", "时间", "date", "time"],
                "description": "时间戳和日期时间转换工具",
                "parameters": {
                    "action": "操作类型：to_timestamp, to_datetime, format",
                    "value": "时间戳或日期时间字符串",
                    "format": "日期格式（可选）"
                }
            },
            "csv_tool": {
                "keywords": ["csv", "表格", "excel", "parse"],
                "description": "CSV 数据解析和处理工具",
                "parameters": {
                    "csv_str": "CSV 字符串",
                    "action": "操作类型：parse, to_json, to_html",
                    "delimiter": "分隔符（可选，默认逗号）"
                }
            },
            "url_tool": {
                "keywords": ["url", "网址", "解析", "编码", "parse", "encode"],
                "description": "URL 解析、编码和解码工具",
                "parameters": {
                    "action": "操作类型：parse, encode, decode, join",
                    "url": "URL 字符串",
                    "params": "查询参数（JSON）"
                }
            },
            "random_tool": {
                "keywords": ["随机", "random", "抽奖", "生成", "generate"],
                "description": "随机数生成和随机选择工具",
                "parameters": {
                    "action": "操作类型：int, float, choice, shuffle, uuid",
                    "min": "最小值（可选）",
                    "max": "最大值（可选）",
                    "choices": "选项列表（JSON 数组）"
                }
            },
            "string_tool": {
                "keywords": ["字符串", "string", "处理", "统计", "count"],
                "description": "字符串处理工具（统计、查找、替换等）",
                "parameters": {
                    "action": "操作类型：count, find, replace, split, join, reverse",
                    "text": "目标文本",
                    "old": "要替换的内容（可选）",
                    "new": "新内容（可选）"
                }
            }
        }
        
        for tool_name, config in patterns.items():
            if tool_name in existing_lower:
                continue
                
            for keyword in config["keywords"]:
                if keyword in task_lower:
                    result["needs_new_tool"] = True
                    result["tool_name"] = tool_name
                    result["tool_description"] = config["description"]
                    result["parameters"] = config["parameters"]
                    result["reason"] = f"检测到关键词: {keyword}"
                    return result
        
        complex_patterns = [
            (r"计算\s*([\d+\-*/().^%]+)", "需要数学计算能力"),
            (r"从\s*([^\s]+)\s*提取", "需要文本提取能力"),
            (r"转换\s*(\d+)\s*(\w+)\s*到\s*(\w+)", "需要单位转换能力"),
            (r"验证\s*[\"\']([^\"\']+)[\"\']", "需要验证能力"),
        ]
        
        for pattern, reason in complex_patterns:
            if re.search(pattern, task):
                result["reason"] = reason
                break
        
        return result
    
    def generate_tool_code(self, spec: ToolSpecification) -> str:
        """
        生成工具代码（基于模板）
        
        注意：这是基于模板的代码生成器
        生产环境可以结合 LLM 生成更智能的代码
        """
        templates = {
            "unit_converter": self._generate_unit_converter_code,
            "json_formatter": self._generate_json_formatter_code,
            "regex_test": self._generate_regex_test_code,
            "base64_tool": self._generate_base64_code,
            "hash_tool": self._generate_hash_code,
            "timestamp_tool": self._generate_timestamp_code,
            "csv_tool": self._generate_csv_code,
            "url_tool": self._generate_url_code,
            "random_tool": self._generate_random_code,
            "string_tool": self._generate_string_code,
        }
        
        generator = templates.get(spec.name)
        if generator:
            return generator(spec)
        
        return self._generate_generic_code(spec)
    
    def _generate_unit_converter_code(self, spec: ToolSpecification) -> str:
        return '''# -*- coding: utf-8 -*-
import json

task = "单位转换"

value = float(script_args.get('value', 0))
from_unit = script_args.get('from_unit', '').lower()
to_unit = script_args.get('to_unit', '').lower()
category = script_args.get('category', 'length').lower()

print(f"转换: {value} {from_unit} -> {to_unit}")
print(f"类型: {category}")

length_units = {
    'm': 1.0,
    'meter': 1.0,
    '米': 1.0,
    'km': 1000.0,
    'kilometer': 1000.0,
    '千米': 1000.0,
    'cm': 0.01,
    'centimeter': 0.01,
    '厘米': 0.01,
    'mm': 0.001,
    'millimeter': 0.001,
    '毫米': 0.001,
    'mi': 1609.34,
    'mile': 1609.34,
    '英里': 1609.34,
    'ft': 0.3048,
    'foot': 0.3048,
    '英尺': 0.3048,
    'in': 0.0254,
    'inch': 0.0254,
    '英寸': 0.0254,
}

weight_units = {
    'kg': 1.0,
    'kilogram': 1.0,
    '千克': 1.0,
    'g': 0.001,
    'gram': 0.001,
    '克': 0.001,
    'mg': 0.000001,
    'milligram': 0.000001,
    '毫克': 0.000001,
    'lb': 0.453592,
    'pound': 0.453592,
    '磅': 0.453592,
    'oz': 0.0283495,
    'ounce': 0.0283495,
    '盎司': 0.0283495,
}

units_map = {
    'length': length_units,
    'weight': weight_units,
}

units = units_map.get(category, length_units)

if from_unit not in units:
    print(f"错误: 不支持的源单位 {from_unit}")
    print(f"支持的单位: {list(units.keys())}")
    result = {"error": "unsupported_unit", "from_unit": from_unit}
elif to_unit not in units:
    print(f"错误: 不支持的目标单位 {to_unit}")
    print(f"支持的单位: {list(units.keys())}")
    result = {"error": "unsupported_unit", "to_unit": to_unit}
else:
    meters = value * units[from_unit]
    result_value = meters / units[to_unit]
    print(f"结果: {result_value} {to_unit}")
    
    result = {
        "value": value,
        "from_unit": from_unit,
        "to_unit": to_unit,
        "result": result_value,
        "category": category
    }
'''
    
    def _generate_json_formatter_code(self, spec: ToolSpecification) -> str:
        return '''# -*- coding: utf-8 -*-
import json

json_str = script_args.get('json_str', '')
indent = int(script_args.get('indent', 2))

print(f"输入长度: {len(json_str)} 字符")

try:
    data = json.loads(json_str)
    formatted = json.dumps(data, ensure_ascii=False, indent=indent)
    print(f"格式化成功!")
    print("=== 格式化结果 ===")
    print(formatted)
    
    result = {
        "valid": True,
        "formatted": formatted,
        "type": type(data).__name__
    }
except json.JSONDecodeError as e:
    print(f"JSON 解析错误: {e}")
    print(f"错误位置: 第 {e.lineno} 行, 第 {e.colno} 列")
    
    result = {
        "valid": False,
        "error": str(e),
        "line": e.lineno,
        "column": e.colno
    }
'''
    
    def _generate_regex_test_code(self, spec: ToolSpecification) -> str:
        return '''# -*- coding: utf-8 -*-
import re

pattern = script_args.get('pattern', '')
text = script_args.get('text', '')
flags_str = script_args.get('flags', '')

print(f"正则表达式: {pattern}")
print(f"测试文本: {text[:100]}...")

flags = 0
if 'i' in flags_str:
    flags |= re.IGNORECASE
if 'm' in flags_str:
    flags |= re.MULTILINE
if 's' in flags_str:
    flags |= re.DOTALL

try:
    regex = re.compile(pattern, flags)
    
    matches = list(regex.finditer(text))
    print(f"找到 {len(matches)} 个匹配")
    
    results = []
    for i, match in enumerate(matches[:10]):
        results.append({
            "index": i,
            "start": match.start(),
            "end": match.end(),
            "match": match.group(),
            "groups": list(match.groups())
        })
        print(f"{i+1}. 位置 {match.start()}-{match.end()}: {match.group()[:50]}")
    
    if len(matches) > 10:
        print(f"... 还有 {len(matches) - 10} 个匹配")
    
    result = {
        "success": True,
        "match_count": len(matches),
        "matches": results
    }
    
except re.error as e:
    print(f"正则表达式错误: {e}")
    result = {
        "success": False,
        "error": str(e)
    }
'''
    
    def _generate_base64_code(self, spec: ToolSpecification) -> str:
        return '''# -*- coding: utf-8 -*-
import base64

action = script_args.get('action', 'encode')
data = script_args.get('data', '')

if action == 'encode':
    print(f"编码: {data[:50]}...")
    encoded = base64.b64encode(data.encode('utf-8')).decode('utf-8')
    print(f"结果: {encoded}")
    result = {
        "action": "encode",
        "original": data,
        "encoded": encoded
    }
elif action == 'decode':
    print(f"解码: {data[:50]}...")
    try:
        decoded = base64.b64decode(data).decode('utf-8')
        print(f"结果: {decoded}")
        result = {
            "action": "decode",
            "encoded": data,
            "decoded": decoded
        }
    except Exception as e:
        print(f"解码错误: {e}")
        result = {
            "action": "decode",
            "error": str(e)
        }
else:
    print(f"未知操作: {action}")
    result = {"error": "unknown_action", "action": action}
'''
    
    def _generate_hash_code(self, spec: ToolSpecification) -> str:
        return '''# -*- coding: utf-8 -*-
import hashlib

algorithm = script_args.get('algorithm', 'sha256').lower()
data = script_args.get('data', '')

print(f"算法: {algorithm}")
print(f"数据: {data[:50]}...")

hash_functions = {
    'md5': hashlib.md5,
    'sha1': hashlib.sha1,
    'sha256': hashlib.sha256,
    'sha512': hashlib.sha512,
}

if algorithm in hash_functions:
    h = hash_functions[algorithm](data.encode('utf-8'))
    hash_hex = h.hexdigest()
    print(f"结果: {hash_hex}")
    
    result = {
        "algorithm": algorithm,
        "data_length": len(data),
        "hash": hash_hex
    }
else:
    print(f"不支持的算法: {algorithm}")
    print(f"支持的算法: {list(hash_functions.keys())}")
    result = {
        "error": "unsupported_algorithm",
        "algorithm": algorithm,
        "supported": list(hash_functions.keys())
    }
'''
    
    def _generate_timestamp_code(self, spec: ToolSpecification) -> str:
        return '''# -*- coding: utf-8 -*-
import time
from datetime import datetime

action = script_args.get('action', 'to_timestamp')
value = script_args.get('value', None)
fmt = script_args.get('format', '%Y-%m-%d %H:%M:%S')

print(f"操作: {action}")

if action == 'to_timestamp':
    if value:
        try:
            dt = datetime.strptime(str(value), fmt)
            ts = dt.timestamp()
            print(f"时间戳: {ts}")
            result = {
                "datetime": value,
                "timestamp": ts,
                "format": fmt
            }
        except Exception as e:
            print(f"解析错误: {e}")
            result = {"error": str(e)}
    else:
        ts = time.time()
        print(f"当前时间戳: {ts}")
        result = {"timestamp": ts}

elif action == 'to_datetime':
    if value:
        try:
            ts = float(value)
            dt = datetime.fromtimestamp(ts)
            formatted = dt.strftime(fmt)
            print(f"日期时间: {formatted}")
            result = {
                "timestamp": ts,
                "datetime": formatted,
                "format": fmt
            }
        except Exception as e:
            print(f"转换错误: {e}")
            result = {"error": str(e)}
    else:
        now = datetime.now()
        formatted = now.strftime(fmt)
        print(f"当前时间: {formatted}")
        result = {"datetime": formatted}

else:
    print(f"未知操作: {action}")
    result = {"error": "unknown_action", "action": action}
'''
    
    def _generate_csv_code(self, spec: ToolSpecification) -> str:
        return '''# -*- coding: utf-8 -*-
import csv
import io
import json

csv_str = script_args.get('csv_str', '')
action = script_args.get('action', 'parse')
delimiter = script_args.get('delimiter', ',')

print(f"操作: {action}")
print(f"CSV 长度: {len(csv_str)} 字符")

if action == 'parse' or action == 'to_json':
    try:
        reader = csv.DictReader(io.StringIO(csv_str), delimiter=delimiter)
        rows = list(reader)
        print(f"解析成功: {len(rows)} 行数据")
        
        if rows:
            print(f"列名: {list(rows[0].keys())}")
            print("=== 前 5 行数据 ===")
            for i, row in enumerate(rows[:5]):
                print(f"{i+1}. {row}")
        
        result = {
            "row_count": len(rows),
            "columns": list(rows[0].keys()) if rows else [],
            "data": rows
        }
    except Exception as e:
        print(f"解析错误: {e}")
        result = {"error": str(e)}

elif action == 'to_html':
    try:
        reader = csv.DictReader(io.StringIO(csv_str), delimiter=delimiter)
        rows = list(reader)
        
        html = "<table border='1'>\\n"
        if rows:
            html += "<tr>"
            for col in rows[0].keys():
                html += f"<th>{col}</th>"
            html += "</tr>\\n"
            
            for row in rows:
                html += "<tr>"
                for value in row.values():
                    html += f"<td>{value}</td>"
                html += "</tr>\\n"
        html += "</table>"
        
        print(f"生成 HTML 表格: {len(rows)} 行")
        result = {
            "row_count": len(rows),
            "html": html
        }
    except Exception as e:
        print(f"转换错误: {e}")
        result = {"error": str(e)}

else:
    result = {"error": "unknown_action", "action": action}
'''
    
    def _generate_url_code(self, spec: ToolSpecification) -> str:
        return '''# -*- coding: utf-8 -*-
from urllib.parse import urlparse, urlencode, parse_qs, quote, unquote, urljoin
import json

action = script_args.get('action', 'parse')
url = script_args.get('url', '')
params_str = script_args.get('params', '{}')

print(f"操作: {action}")
print(f"URL: {url}")

if action == 'parse':
    parsed = urlparse(url)
    query_params = parse_qs(parsed.query)
    
    result = {
        "scheme": parsed.scheme,
        "netloc": parsed.netloc,
        "path": parsed.path,
        "params": parsed.params,
        "query": parsed.query,
        "fragment": parsed.fragment,
        "query_params": {k: v[0] if len(v) == 1 else v for k, v in query_params.items()}
    }
    
    print("=== 解析结果 ===")
    for key, value in result.items():
        print(f"{key}: {value}")

elif action == 'encode':
    encoded = quote(url)
    print(f"编码后: {encoded}")
    result = {"original": url, "encoded": encoded}

elif action == 'decode':
    decoded = unquote(url)
    print(f"解码后: {decoded}")
    result = {"encoded": url, "decoded": decoded}

elif action == 'join':
    try:
        params = json.loads(params_str)
        query_string = urlencode(params)
        if '?' in url:
            full_url = f"{url}&{query_string}"
        else:
            full_url = f"{url}?{query_string}"
        print(f"完整 URL: {full_url}")
        result = {"base_url": url, "params": params, "full_url": full_url}
    except Exception as e:
        result = {"error": str(e)}

else:
    result = {"error": "unknown_action", "action": action}
'''
    
    def _generate_random_code(self, spec: ToolSpecification) -> str:
        return '''# -*- coding: utf-8 -*-
import random
import uuid
import json

action = script_args.get('action', 'int')
min_val = int(script_args.get('min', 0))
max_val = int(script_args.get('max', 100))
choices_str = script_args.get('choices', '[]')

print(f"操作: {action}")

if action == 'int':
    result_val = random.randint(min_val, max_val)
    print(f"随机整数 ({min_val}-{max_val}): {result_val}")
    result = {"type": "int", "min": min_val, "max": max_val, "value": result_val}

elif action == 'float':
    result_val = random.uniform(float(min_val), float(max_val))
    print(f"随机浮点数 ({min_val}-{max_val}): {result_val}")
    result = {"type": "float", "min": min_val, "max": max_val, "value": result_val}

elif action == 'choice':
    try:
        choices = json.loads(choices_str)
        if choices:
            choice = random.choice(choices)
            print(f"随机选择: {choice}")
            result = {"type": "choice", "choices": choices, "value": choice}
        else:
            result = {"error": "no_choices"}
    except Exception as e:
        result = {"error": str(e)}

elif action == 'shuffle':
    try:
        choices = json.loads(choices_str)
        random.shuffle(choices)
        print(f"打乱后: {choices}")
        result = {"type": "shuffle", "value": choices}
    except Exception as e:
        result = {"error": str(e)}

elif action == 'uuid':
    result_val = str(uuid.uuid4())
    print(f"UUID: {result_val}")
    result = {"type": "uuid", "value": result_val}

else:
    result = {"error": "unknown_action", "action": action}
'''
    
    def _generate_string_code(self, spec: ToolSpecification) -> str:
        return '''# -*- coding: utf-8 -*-
import json

action = script_args.get('action', 'count')
text = script_args.get('text', '')
old_str = script_args.get('old', '')
new_str = script_args.get('new', '')

print(f"操作: {action}")
print(f"文本长度: {len(text)} 字符")

if action == 'count':
    char_count = len(text)
    word_count = len(text.split())
    line_count = len(text.splitlines())
    
    print(f"字符数: {char_count}")
    print(f"词数: {word_count}")
    print(f"行数: {line_count}")
    
    result = {
        "char_count": char_count,
        "word_count": word_count,
        "line_count": line_count
    }

elif action == 'find':
    if old_str:
        count = text.count(old_str)
        positions = [i for i in range(len(text)) if text.startswith(old_str, i)]
        print(f"找到 {count} 次")
        print(f"位置: {positions[:10]}")
        
        result = {
            "find": old_str,
            "count": count,
            "positions": positions[:10]
        }
    else:
        result = {"error": "missing_old"}

elif action == 'replace':
    if old_str:
        result_text = text.replace(old_str, new_str)
        count = text.count(old_str)
        print(f"替换 {count} 处")
        print(f"结果: {result_text[:100]}...")
        
        result = {
            "old": old_str,
            "new": new_str,
            "replace_count": count,
            "result": result_text
        }
    else:
        result = {"error": "missing_old"}

elif action == 'reverse':
    result_text = text[::-1]
    print(f"反转后: {result_text}")
    result = {"original": text, "reversed": result_text}

else:
    result = {"error": "unknown_action", "action": action}
'''
    
    def _generate_generic_code(self, spec: ToolSpecification) -> str:
        param_list = list(spec.parameters.keys())
        param_assignments = "\n".join([
            f"{p} = script_args.get('{p}', '')" for p in param_list
        ])
        param_prints = "\n".join([
            f"print(f'{p}: {{{p}}}')" for p in param_list
        ])
        
        return f'''# -*- coding: utf-8 -*-
import json

task = {repr(spec.description)}
print(f"任务: {{task}}")

{param_assignments}

{param_prints}

result = {{
    "task": task,
    "parameters": {{p: eval(p) for p in {repr(param_list)}}}
}}
'''
    
    def test_tool(self, spec: ToolSpecification) -> Dict[str, Any]:
        """
        测试工具
        
        Returns:
            {
                "success": bool,
                "test_cases": list,
                "error": str
            }
        """
        test_cases = []
        
        if spec.examples:
            for example in spec.examples[:3]:
                try:
                    params = json.loads(example) if isinstance(example, str) else example
                    test_result = self.engine.execute_code(
                        spec.code,
                        variables={"script_args": params}
                    )
                    test_cases.append({
                        "params": params,
                        "success": test_result.get("success", False),
                        "stdout": test_result.get("stdout", ""),
                        "error": test_result.get("error")
                    })
                except Exception as e:
                    test_cases.append({
                        "error": str(e),
                        "success": False
                    })
        
        all_passed = all(tc.get("success", False) for tc in test_cases) if test_cases else True
        
        return {
            "success": all_passed,
            "test_cases": test_cases,
            "error": None if all_passed else "部分测试失败"
        }
    
    def create_tool(self, name: str, description: str, 
                    parameters: Dict[str, str] = None,
                    examples: List[str] = None,
                    code: str = None,
                    category: str = "general",
                    max_attempts: int = 3) -> ToolCreationResult:
        """
        创建新工具
        
        Args:
            name: 工具名称
            description: 工具描述
            parameters: 参数定义
            examples: 示例参数
            code: 预定义代码（可选）
            category: 分类
            max_attempts: 最大尝试次数
            
        Returns:
            ToolCreationResult
        """
        spec = ToolSpecification(
            name=name,
            description=description,
            parameters=parameters or {},
            code=code or "",
            examples=examples or [],
            category=category
        )
        
        if not code:
            spec.code = self.generate_tool_code(spec)
        
        for attempt in range(max_attempts):
            test_result = self.test_tool(spec)
            
            if test_result["success"]:
                self._save_tool(spec)
                return ToolCreationResult(
                    success=True,
                    tool_name=name,
                    spec=spec,
                    attempts=attempt + 1,
                    test_results=test_result
                )
            
            if attempt < max_attempts - 1:
                error = test_result["error"] or "测试失败"
                fixed_code = self.engine._fix_code(
                    spec.code,
                    {"type": "TestError", "message": error, "traceback": ""}
                )
                if fixed_code != spec.code:
                    spec.code = fixed_code
                else:
                    break
        
        return ToolCreationResult(
            success=False,
            tool_name=name,
            spec=spec,
            error="工具创建失败，测试未通过",
            attempts=max_attempts,
            test_results=test_result
        )
    
    def _save_tool(self, spec: ToolSpecification):
        """保存工具"""
        tool_file = os.path.join(self.tools_dir, f"{spec.name}.py")
        tool_meta = os.path.join(self.tools_dir, f"{spec.name}.json")
        
        with open(tool_file, 'w', encoding='utf-8') as f:
            f.write(spec.code)
        
        meta = {
            "name": spec.name,
            "description": spec.description,
            "parameters": spec.parameters,
            "category": spec.category,
            "version": spec.version,
            "created_at": time.time(),
            "file": tool_file
        }
        
        with open(tool_meta, 'w', encoding='utf-8') as f:
            json.dump(meta, f, ensure_ascii=False, indent=2)
        
        self.index[spec.name] = meta
        self._save_index()
    
    def get_tool(self, name: str) -> Optional[Dict[str, Any]]:
        """获取工具信息"""
        if name not in self.index:
            return None
        
        meta = self.index[name].copy()
        tool_file = meta.get("file")
        
        if tool_file and os.path.exists(tool_file):
            with open(tool_file, 'r', encoding='utf-8') as f:
                meta["code"] = f.read()
        
        return meta
    
    def list_tools(self) -> List[Dict[str, Any]]:
        """列出所有工具"""
        return [
            {
                "name": meta.get("name"),
                "description": meta.get("description"),
                "category": meta.get("category"),
                "parameters": meta.get("parameters", {}),
                "version": meta.get("version"),
                "created_at": meta.get("created_at")
            }
            for meta in self.index.values()
        ]
    
    def execute_tool(self, name: str, parameters: Dict[str, Any] = None) -> Dict[str, Any]:
        """执行保存的工具"""
        meta = self.get_tool(name)
        if not meta or "code" not in meta:
            return {"success": False, "error": "工具不存在"}
        
        return self.engine.execute_with_auto_fix(
            meta["code"],
            variables={"script_args": parameters or {}}
        )
    
    def delete_tool(self, name: str) -> bool:
        """删除工具"""
        if name not in self.index:
            return False
        
        meta = self.index.pop(name)
        tool_file = meta.get("file")
        tool_meta = os.path.join(self.tools_dir, f"{name}.json")
        
        if tool_file and os.path.exists(tool_file):
            try:
                os.remove(tool_file)
            except:
                pass
        
        if os.path.exists(tool_meta):
            try:
                os.remove(tool_meta)
            except:
                pass
        
        self._save_index()
        return True


_creator_instance: Optional[AIToolCreator] = None


def get_creator(context=None) -> AIToolCreator:
    """获取全局工具创建器实例"""
    global _creator_instance
    if _creator_instance is None:
        _creator_instance = AIToolCreator(context)
    return _creator_instance


def create_ai_tool(name: str, description: str, context=None,
                   parameters: Dict[str, str] = None,
                   examples: List[str] = None,
                   code: str = None) -> Dict[str, Any]:
    """
    便捷函数：创建 AI 工具
    """
    creator = get_creator(context)
    result = creator.create_tool(
        name=name,
        description=description,
        parameters=parameters,
        examples=examples,
        code=code
    )
    return result.to_dict()
