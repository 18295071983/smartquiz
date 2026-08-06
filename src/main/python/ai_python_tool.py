# -*- coding: utf-8 -*-
"""
AI 驱动的 Python 工具
让 AI 能够创建、执行和修复 Python 代码
"""

import sys
import os
import json
import re
import traceback
from typing import Dict, List, Any, Optional

from python_tool_engine import PythonToolEngine, get_engine


class AIPythonTool:
    """
    AI Python 工具
    - 接收任务描述
    - 生成 Python 代码
    - 执行并自动修复
    - 返回结果
    """
    
    def __init__(self, context=None):
        self.engine = get_engine(context)
        self.context = context
    
    def process_task(self, task: str, context_data: Dict[str, Any] = None) -> Dict[str, Any]:
        """
        处理任务
        
        Args:
            task: 任务描述
            context_data: 上下文数据
            
        Returns:
            {
                "success": bool,
                "code": str,
                "result": Any,
                "stdout": str,
                "stderr": str,
                "error": str,
                "attempts": int,
                "fixes": list
            }
        """
        result = {
            "success": False,
            "code": "",
            "result": None,
            "stdout": "",
            "stderr": "",
            "error": None,
            "attempts": 0,
            "fixes": []
        }
        
        code = self._generate_code_for_task(task, context_data)
        result["code"] = code
        
        exec_result = self.engine.execute_with_auto_fix(
            code,
            max_attempts=5,
            timeout=60
        )
        
        result.update({
            "success": exec_result.get("success", False),
            "result": exec_result.get("result"),
            "stdout": exec_result.get("stdout", ""),
            "stderr": exec_result.get("stderr", ""),
            "error": exec_result.get("error"),
            "attempts": exec_result.get("attempts", 0),
            "fixes": exec_result.get("fixes_applied", []),
            "code": exec_result.get("final_code", code)
        })
        
        if result["success"]:
            tool_name = self._sanitize_name(task[:30])
            self.engine.save_script(tool_name, result["code"], task)
        
        return result
    
    def _generate_code_for_task(self, task: str, context_data: Dict[str, Any] = None) -> str:
        """
        为任务生成 Python 代码
        
        这是一个基于模板的代码生成器
        实际生产环境可以结合 LLM 来生成更智能的代码
        """
        task_lower = task.lower()
        
        if any(keyword in task_lower for keyword in ['计算', '数学', 'math', 'calculate', 'compute']):
            return self._generate_math_code(task, context_data)
        
        elif any(keyword in task_lower for keyword in ['数据', '分析', '统计', 'data', 'analysis', 'statistics']):
            return self._generate_data_analysis_code(task, context_data)
        
        elif any(keyword in task_lower for keyword in ['网络请求', 'http', '请求', '网页', 'web', 'request']):
            return self._generate_network_code(task, context_data)
        
        elif any(keyword in task_lower for keyword in ['文本', '字符串', '正则', 'text', 'string', 'regex']):
            return self._generate_text_code(task, context_data)
        
        elif any(keyword in task_lower for keyword in ['时间', '日期', 'date', 'time', 'datetime']):
            return self._generate_datetime_code(task, context_data)
        
        elif any(keyword in task_lower for keyword in ['加密', '解密', '哈希', 'encrypt', 'decrypt', 'hash']):
            return self._generate_crypto_code(task, context_data)
        
        elif any(keyword in task_lower for keyword in ['图像', '图片', 'image', 'picture']):
            return self._generate_image_code(task, context_data)
        
        else:
            return self._generate_generic_code(task, context_data)
    
    def _sanitize_name(self, name: str) -> str:
        """清理文件名"""
        return "".join(c if c.isalnum() or c in "._-" else "_" for c in name)
    
    def _extract_expression(self, task: str) -> str:
        """从任务中提取数学表达式"""
        patterns = [
            r'计算\s*([\d+\-*/().\s^%]+)',
            r'(\d+\s*[+\-*/^%]\s*[\d+\-*/().\s^%]+)',
            r'等于多少\??\s*([\d+\-*/().\s^%]+)',
            r'结果是多少\??\s*([\d+\-*/().\s^%]+)',
        ]
        
        for pattern in patterns:
            match = re.search(pattern, task)
            if match:
                return match.group(1).strip()
        
        numbers = re.findall(r'\d+', task)
        operators = re.findall(r'[+\-*/^%]', task)
        if numbers and operators:
            expr = ""
            num_idx = 0
            op_idx = 0
            while num_idx < len(numbers):
                expr += numbers[num_idx]
                if op_idx < len(operators) and num_idx < len(numbers) - 1:
                    expr += operators[op_idx]
                    op_idx += 1
                num_idx += 1
            return expr
        
        return "0"
    
    def _generate_math_code(self, task: str, context_data: Dict[str, Any] = None) -> str:
        """生成数学计算代码"""
        expression = self._extract_expression(task)
        
        return f'''# -*- coding: utf-8 -*-
import math

task = {repr(task)}
expression = {repr(expression)}

print(f"任务: {{task}}")
print(f"表达式: {{expression}}")

try:
    result = eval(expression, {{
        '__builtins__': {{}},
        'math': math,
        'abs': abs,
        'pow': pow,
        'round': round,
        'int': int,
        'float': float,
    }})
    print(f"计算结果: {{result}}")
    result = result
except Exception as e:
    print(f"计算错误: {{e}}")
    result = str(e)
'''
    
    def _generate_data_analysis_code(self, task: str, context_data: Dict[str, Any] = None) -> str:
        """生成数据分析代码"""
        data = context_data.get("data", []) if context_data else []
        
        return f'''# -*- coding: utf-8 -*-
import json
import statistics
from collections import Counter

task = {repr(task)}
data = {repr(data)}

print(f"任务: {{task}}")

if data:
    print(f"数据量: {{len(data)}}")
    
    if all(isinstance(x, (int, float)) for x in data):
        nums = [float(x) for x in data]
        print(f"平均值: {{statistics.mean(nums)}}")
        print(f"中位数: {{statistics.median(nums)}}")
        print(f"最大值: {{max(nums)}}")
        print(f"最小值: {{min(nums)}}")
        print(f"总和: {{sum(nums)}}")
        if len(nums) > 1:
            print(f"标准差: {{statistics.stdev(nums)}}")
    
    result = {{
        "task": task,
        "data_count": len(data),
        "analysis": "completed"
    }}
else:
    print("无数据可分析")
    result = {{"task": task, "data_count": 0}}
'''
    
    def _generate_network_code(self, task: str, context_data: Dict[str, Any] = None) -> str:
        """生成网络请求代码"""
        url = context_data.get("url", "https://httpbin.org/get") if context_data else "https://httpbin.org/get"
        
        return f'''# -*- coding: utf-8 -*-
import requests
import json

task = {repr(task)}
url = {repr(url)}

print(f"任务: {{task}}")
print(f"请求 URL: {{url}}")

try:
    response = requests.get(url, timeout=10)
    print(f"状态码: {{response.status_code}}")
    
    if response.status_code == 200:
        try:
            data = response.json()
            print("响应数据:")
            print(json.dumps(data, ensure_ascii=False, indent=2)[:2000])
            result = data
        except:
            result = response.text[:2000]
    else:
        result = {{"error": f"HTTP {{response.status_code}}"}}
except Exception as e:
    print(f"请求错误: {{e}}")
    result = {{"error": str(e)}}
'''
    
    def _generate_text_code(self, task: str, context_data: Dict[str, Any] = None) -> str:
        """生成文本处理代码"""
        text = context_data.get("text", "Hello, World!") if context_data else "Hello, World!"
        
        return f'''# -*- coding: utf-8 -*-
import re

task = {repr(task)}
text = {repr(text)}

print(f"任务: {{task}}")
print(f"原始文本: {{text}}")

result = {{
    "length": len(text),
    "words": len(text.split()),
    "lines": len(text.splitlines()),
    "uppercase": text.upper(),
    "lowercase": text.lower(),
    "reversed": text[::-1],
}}

for key, value in result.items():
    print(f"{{key}}: {{value}}")
'''
    
    def _generate_datetime_code(self, task: str, context_data: Dict[str, Any] = None) -> str:
        """生成日期时间代码"""
        return '''# -*- coding: utf-8 -*-
from datetime import datetime, timedelta
import time

now = datetime.now()
print(f"当前时间: {now.strftime('%Y-%m-%d %H:%M:%S')}")
print(f"日期: {now.strftime('%Y-%m-%d')}")
print(f"时间: {now.strftime('%H:%M:%S')}")
print(f"星期: {['一', '二', '三', '四', '五', '六', '日'][now.weekday()]}")
print(f"时间戳: {time.time()}")

tomorrow = now + timedelta(days=1)
print(f"明天: {tomorrow.strftime('%Y-%m-%d')}")

yesterday = now - timedelta(days=1)
print(f"昨天: {yesterday.strftime('%Y-%m-%d')}")

result = {
    "now": now.strftime('%Y-%m-%d %H:%M:%S'),
    "date": now.strftime('%Y-%m-%d'),
    "time": now.strftime('%H:%M:%S'),
    "weekday": now.weekday(),
    "timestamp": time.time()
}
'''
    
    def _generate_crypto_code(self, task: str, context_data: Dict[str, Any] = None) -> str:
        """生成加密代码"""
        data = context_data.get("data", "Hello, World!") if context_data else "Hello, World!"
        
        return f'''# -*- coding: utf-8 -*-
import hashlib
import base64

data = {repr(data)}
print(f"原始数据: {{data}}")

md5_hash = hashlib.md5(data.encode()).hexdigest()
print(f"MD5: {{md5_hash}}")

sha1_hash = hashlib.sha1(data.encode()).hexdigest()
print(f"SHA-1: {{sha1_hash}}")

sha256_hash = hashlib.sha256(data.encode()).hexdigest()
print(f"SHA-256: {{sha256_hash}}")

encoded = base64.b64encode(data.encode()).decode()
print(f"Base64 编码: {{encoded}}")

decoded = base64.b64decode(encoded).decode()
print(f"Base64 解码: {{decoded}}")

result = {{
    "md5": md5_hash,
    "sha1": sha1_hash,
    "sha256": sha256_hash,
    "base64": encoded
}}
'''
    
    def _generate_image_code(self, task: str, context_data: Dict[str, Any] = None) -> str:
        """生成图像处理代码"""
        image_path = context_data.get("image_path", "") if context_data else ""
        
        return f'''# -*- coding: utf-8 -*-
from PIL import Image
import os

task = {repr(task)}
image_path = {repr(image_path)}

print(f"任务: {{task}}")

result = {{"task": task}}

if image_path and os.path.exists(image_path):
    try:
        img = Image.open(image_path)
        result.update({{
            "format": img.format,
            "size": img.size,
            "mode": img.mode,
            "width": img.width,
            "height": img.height
        }})
        for key, value in result.items():
            print(f"{{key}}: {{value}}")
    except Exception as e:
        result["error"] = str(e)
        print(f"错误: {{e}}")
else:
    print("未提供图片路径或文件不存在")
    result["message"] = "请提供图片路径"
'''
    
    def _generate_generic_code(self, task: str, context_data: Dict[str, Any] = None) -> str:
        """生成通用代码"""
        return f'''# -*- coding: utf-8 -*-
import json
import sys

task = {repr(task)}
context = {repr(context_data or {{}})}

print(f"任务: {{task}}")
print(f"Python 版本: {{sys.version}}")

result = {{
    "task": task,
    "context": context,
    "python_version": sys.version
}}

print(json.dumps(result, ensure_ascii=False, indent=2))
'''
    
    def list_tools(self) -> List[Dict[str, Any]]:
        """列出可用的工具脚本"""
        return self.engine.list_scripts()
    
    def delete_tool(self, name: str) -> bool:
        """删除工具脚本"""
        return self.engine.delete_script(name)
    
    def execute_script(self, name: str, args: Dict[str, Any] = None) -> Dict[str, Any]:
        """执行保存的脚本"""
        return self.engine.run_script(name, args)
    
    def run_code(self, code: str, timeout: int = 60) -> Dict[str, Any]:
        """
        直接执行 Python 代码（不解释为任务描述）
        
        Args:
            code: 要执行的 Python 代码
            timeout: 超时时间（秒）
            
        Returns:
            执行结果字典
        """
        result = {
            "success": False,
            "code": code,
            "result": None,
            "stdout": "",
            "stderr": "",
            "error": None,
            "attempts": 0,
            "fixes": []
        }
        
        exec_result = self.engine.execute_with_auto_fix(
            code,
            max_attempts=3,
            timeout=timeout
        )
        
        result.update({
            "success": exec_result.get("success", False),
            "result": exec_result.get("result"),
            "stdout": exec_result.get("stdout", ""),
            "stderr": exec_result.get("stderr", ""),
            "error": exec_result.get("error"),
            "attempts": exec_result.get("attempts", 0),
            "fixes": exec_result.get("fixes_applied", []),
            "code": exec_result.get("final_code", code)
        })
        
        return result


_ai_tool_instance: Optional[AIPythonTool] = None


def get_ai_tool(context=None) -> AIPythonTool:
    """获取全局 AI 工具实例"""
    global _ai_tool_instance
    if _ai_tool_instance is None:
        _ai_tool_instance = AIPythonTool(context)
    return _ai_tool_instance


def process_python_task(task: str, context=None, context_data: Dict[str, Any] = None) -> Dict[str, Any]:
    """
    便捷函数：处理 Python 任务
    
    Args:
        task: 任务描述
        context: Android Context
        context_data: 上下文数据
        
    Returns:
        执行结果
    """
    tool = get_ai_tool(context)
    return tool.process_task(task, context_data)


def generate_code_with_llm(task: str, llm_generate_func=None) -> str:
    """
    利用 LLM 生成 Python 代码
    
    Args:
        task: 任务描述
        llm_generate_func: LLM 生成函数，接受 prompt 返回代码
        
    Returns:
        生成的 Python 代码
    """
    if llm_generate_func is None:
        # 使用内置的轻量级生成器
        return _generate_simple_code(task)
    
    prompt = _build_code_prompt(task)
    try:
        code = llm_generate_func(prompt)
        # 清理 markdown 代码块标记
        code = _clean_code(code)
        return code
    except Exception as e:
        print(f"LLM 生成失败: {e}")
        return _generate_simple_code(task)


def _build_code_prompt(task: str) -> str:
    """构建代码生成提示词"""
    return f"""请为以下任务生成 Python 代码。

要求：
1. 代码简洁、可直接执行（使用 exec() 执行）
2. 包含必要的错误处理
3. 输出结果用 print() 打印
4. 不要包含 markdown 代码块标记（不要 ```python 和 ```）
5. 只输出纯代码

任务：{task}

代码："""


def _clean_code(code: str) -> str:
    """清理代码中的 markdown 标记"""
    if not code:
        return code
    # 移除 ```python, ```, ```py 等标记
    import re
    code = re.sub(r'```python\s*', '', code)
    code = re.sub(r'```py\s*', '', code)
    code = re.sub(r'```\s*', '', code)
    return code.strip()


def _generate_simple_code(task: str) -> str:
    """简单的模板代码生成"""
    task_lower = task.lower()
    
    if any(k in task_lower for k in ['计算', '数学', 'math', '+', '-', '*', '/', 'calculate']):
        # 提取表达式
        import re
        expr_match = re.search(r'[\d\+\-*/(). ]+', task)
        if expr_match:
            expr = expr_match.group().strip()
            return f"print({expr})"
        return "# 无法解析表达式\nprint('请提供有效的数学表达式')"
    
    elif any(k in task_lower for k in ['阶乘', 'factorial']):
        return '''def factorial(n):
    if n < 0:
        return None
    if n <= 1:
        return 1
    return n * factorial(n - 1)

n = 5
print(f"{n}! = {{factorial(n)}}")'''
    
    elif any(k in task_lower for k in ['排序', 'sort']):
        return '''import random

# 生成随机数组
arr = [random.randint(1, 100) for _ in range(10)]
print(f"原数组: {{arr}}")

# 排序
arr_sorted = sorted(arr)
print(f"排序后: {{arr_sorted}}")'''
    
    elif any(k in task_lower for k in ['斐波那契', 'fibonacci', 'fib']):
        return '''def fibonacci(n):
    if n <= 0:
        return []
    if n == 1:
        return [0]
    
    fib = [0, 1]
    for i in range(2, n):
        fib.append(fib[i-1] + fib[i-2])
    return fib

n = 10
print(f"Fibonacci({{n}}): {{fibonacci(n)}}")'''
    
    elif any(k in task_lower for k in ['质数', 'prime']):
        return '''def is_prime(n):
    if n < 2:
        return False
    for i in range(2, int(n ** 0.5) + 1):
        if n % i == 0:
            return False
    return True

# 找出 1-100 的质数
primes = [i for i in range(1, 101) if is_prime(i)]
print(f"1-100 的质数: {primes}")'''

    elif any(k in task_lower for k in ['提取邮箱', 'email', 'e-mail']):
        return '''import re
text = "联系邮箱: test@example.com 或 admin@test.org"
pattern = r'[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}'
emails = re.findall(pattern, text)
print(f"提取到的邮箱: {emails}")'''

    elif any(k in task_lower for k in ['提取URL', 'url', '网址']):
        return '''import re
text = "访问 https://example.com 或 http://test.org 获取更多信息"
pattern = r'https?://[^\\s<>"{}|\\\\^`\\[\\]]+'
urls = re.findall(pattern, text)
print(f"提取到的URL: {urls}")'''

    elif any(k in task_lower for k in ['统计', '平均值', 'mean', 'average', '均 值']):
        return '''data = [10, 20, 30, 40, 50, 60, 70, 80, 90]
print(f"数据: {data}")
print(f"数量: {len(data)}")
print(f"总和: {sum(data)}")
print(f"平均值: {sum(data)/len(data)}")
print(f"最大值: {max(data)}")
print(f"最小值: {min(data)}")'''

    elif any(k in task_lower for k in ['去重', '删除重复', 'remove duplicate']):
        return '''data = [1, 2, 3, 2, 4, 3, 5, 1, 6]
print(f"原数据: {data}")
unique_data = list(dict.fromkeys(data))
print(f"去重后: {unique_data}")'''

    elif any(k in task_lower for k in ['反转', 'reverse']):
        return '''text = "Hello Python"
print(f"原文本: {text}")
print(f"反转后: {text[::-1]}")'''

    elif any(k in task_lower for k in ['转大写', 'uppercase', 'upper']):
        return '''text = "hello world"
print(f"原文本: {text}")
print(f"转大写: {text.upper()}")'''

    elif any(k in task_lower for k in ['转小写', 'lowercase', 'lower']):
        return '''text = "HELLO WORLD"
print(f"原文本: {text}")
print(f"转小写: {text.lower()}")'''

    elif any(k in task_lower for k in ['随机数', 'random']):
        return '''import random
# 生成10个1-100的随机整数
random_nums = [random.randint(1, 100) for _ in range(10)]
print(f"随机数: {random_nums}")
print(f"最大值: {max(random_nums)}")
print(f"最小值: {min(random_nums)}")
print(f"平均值: {sum(random_nums)/len(random_nums)}")'''

    elif any(k in task_lower for k in ['求和', 'sum', '总和']):
        return '''numbers = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
print(f"数字列表: {numbers}")
print(f"总和: {sum(numbers)}")
print(f"平均值: {sum(numbers)/len(numbers)}")'''

    elif any(k in task_lower for k in ['最大', 'max', '最小', 'min']):
        return '''numbers = [45, 23, 67, 89, 12, 56, 78, 34]
print(f"数字列表: {numbers}")
print(f"最大值: {max(numbers)}")
print(f"最小值: {min(numbers)}")
print(f"平均值: {sum(numbers)/len(numbers)}")'''

    elif any(k in task_lower for k in ['JSON', 'json', '解析json']):
        return '''import json
data = {"name": "张三", "age": 25, "city": "北京"}
json_str = json.dumps(data, ensure_ascii=False, indent=2)
print(f"JSON字符串: {json_str}")
parsed = json.loads(json_str)
print(f"解析后: {parsed}")'''

    elif any(k in task_lower for k in ['CSV', 'csv', '解析csv']):
        return '''import csv
import io
csv_data = "name,age,city\\n张三,25,北京\\n李四,30,上海\\n王五,28,广州"
reader = csv.DictReader(io.StringIO(csv_data))
rows = list(reader)
for row in rows:
    print(row)'''

    else:
        # 默认生成一个简单的问候
        return '''# 通用 Python 代码模板
print("Hello, World!")
print("这是自动生成的 Python 代码")'''
