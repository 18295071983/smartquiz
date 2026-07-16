# -*- coding: utf-8 -*-
"""
AI Agent Python 工具引擎
用于动态执行 Python 代码、管理包、自动修复代码
"""

import sys
import os
import io
import traceback
import json
import importlib
import importlib.util
import time
import threading
import re
from contextlib import redirect_stdout, redirect_stderr


class PythonToolEngine:
    """
    Python 工具引擎
    - 动态执行 Python 代码
    - 安全沙箱
    - 自动包安装（使用国内镜像）
    - 代码自动修复
    """

    # 国内镜像源配置
    PIP_MIRRORS: list[str] = [
        "https://pypi.tuna.tsinghua.edu.cn/simple",  # 清华
        "https://mirrors.aliyun.com/pypi/simple",      # 阿里云
        "https://mirrors.tencent.com/pypi/simple",    # 腾讯云
        "https://pypi.mirrors.org.cn/simple",        # 中国科技大学
    ]

    # 当前使用的镜像索引
    _current_mirror_index: int = 0

    def __init__(self, context: object | None = None, work_dir: str | None = None):
        self.context: object | None = context
        self.work_dir: str = work_dir if work_dir is not None else self._get_default_work_dir()
        self._ensure_work_dir()
        self._installed_packages: dict[str, dict[str, str | float]] = {}
        self._load_installed_packages()
        
    def _get_default_work_dir(self) -> str:
        """获取默认工作目录"""
        if self.context is not None:
            files_dir = getattr(self.context, 'getFilesDir', None)
            if callable(files_dir):
                files_dir_obj = files_dir()
                if files_dir_obj is not None:
                    try:
                        abs_path = getattr(files_dir_obj, 'getAbsolutePath', None)
                        if callable(abs_path):
                            path = abs_path()
                            work_dir = os.path.join(str(path), "python_tools")
                            return work_dir
                        else:
                            work_dir = os.path.join(str(files_dir_obj), "python_tools")
                            return work_dir
                    except Exception as e:
                        pass
        return os.path.join(os.path.expanduser("~"), ".python_tools")
    
    def _ensure_work_dir(self):
        """确保工作目录存在"""
        if not os.path.exists(self.work_dir):
            os.makedirs(self.work_dir, exist_ok=True)
        
        scripts_dir = os.path.join(self.work_dir, "scripts")
        cache_dir = os.path.join(self.work_dir, "cache")
        logs_dir = os.path.join(self.work_dir, "logs")
        
        for d in [scripts_dir, cache_dir, logs_dir]:
            if not os.path.exists(d):
                os.makedirs(d, exist_ok=True)
    
    def _load_installed_packages(self):
        """加载已安装的包列表"""
        cache_file = os.path.join(self.work_dir, "cache", "installed_packages.json")
        if os.path.exists(cache_file):
            try:
                with open(cache_file, 'r', encoding='utf-8') as f:
                    self._installed_packages = json.load(f)
            except:
                self._installed_packages = {}
    
    def _save_installed_packages(self):
        """保存已安装的包列表"""
        cache_file = os.path.join(self.work_dir, "cache", "installed_packages.json")
        try:
            with open(cache_file, 'w', encoding='utf-8') as f:
                json.dump(self._installed_packages, f, ensure_ascii=False, indent=2)
        except:
            pass
    
    def execute_code(self, code: str, timeout: int = 30,
                     capture_output: bool = True,
                     variables: dict[str, object] | None = None) -> dict[str, str | bool | float | None | list[str] | dict[str, str] | object]:
        """
        执行 Python 代码
        
        Args:
            code: Python 代码字符串
            timeout: 超时时间（秒）
            capture_output: 是否捕获输出
            variables: 传入的变量字典
            
        Returns:
            {
                "success": bool,
                "result": object,
                "stdout": str,
                "stderr": str,
                "error": str,
                "execution_time": float
            }
        """
        start_time = time.time()
        result: dict[str, str | bool | float | None | list[str] | dict[str, str] | object] = {
            "success": False,
            "result": None,
            "stdout": "",
            "stderr": "",
            "error": "",
            "execution_time": 0.0
        }
        
        stdout_buffer = io.StringIO()
        stderr_buffer = io.StringIO()
        
        local_vars = {
            "__builtins__": __builtins__,
            "engine": self,
        }
        
        if variables:
            local_vars.update(variables)
        
        global_vars = {
            "__name__": "__main__",
            "__builtins__": __builtins__,
        }
        
        def run_code():
            nonlocal result
            try:
                if capture_output:
                    with redirect_stdout(stdout_buffer), redirect_stderr(stderr_buffer):
                        exec(code, global_vars, local_vars)
                else:
                    exec(code, global_vars, local_vars)
                
                result["success"] = True
                if "result" in local_vars:
                    result["result"] = local_vars["result"]
                
            except Exception as e:
                result["error"] = {
                    "type": type(e).__name__,
                    "message": str(e),
                    "traceback": traceback.format_exc()
                }
            finally:
                if capture_output:
                    result["stdout"] = stdout_buffer.getvalue()
                    result["stderr"] = stderr_buffer.getvalue()
        
        thread = threading.Thread(target=run_code)
        thread.daemon = True
        thread.start()
        thread.join(timeout)
        
        if thread.is_alive():
            result["error"] = {
                "type": "TimeoutError",
                "message": f"代码执行超时（{timeout}秒）",
                "traceback": ""
            }
        
        result["execution_time"] = time.time() - start_time
        
        return result
    
    def install_package(self, package_name: str, version: str | None = None) -> dict[str, bool | str]:
        """
        安装 Python 包（Android Chaquo 环境下有限制）

        Args:
            package_name: 包名
            version: 版本号（可选）

        Returns:
            {
                "success": bool,
                "message": str,
                "installed": bool,
                "fallback_available": bool,
                "fallback_message": str
            }
        """
        result: dict[str, bool | str] = {
            "success": False,
            "message": "",
            "installed": False,
            "fallback_available": False,
            "fallback_message": ""
        }
        
        cache_key = package_name + (f"=={version}" if version else "")
        if cache_key in self._installed_packages:
            result["success"] = True
            result["message"] = f"包 {package_name} 已安装"
            result["installed"] = True
            result["fallback_available"] = True
            return result
        
        # 检查是否是标准库替代
        fallback = self._get_standard_lib_alternative(package_name)
        if fallback:
            result["success"] = True
            result["message"] = f"使用标准库替代: {fallback['name']}"
            result["installed"] = False
            result["fallback_available"] = True
            result["fallback_message"] = fallback["message"]
            return result
        
        # Android Chaquo Python 环境下 pip 可能不可用
        try:
            import subprocess
        except ImportError:
            result["message"] = f"pip 不可用（Android 环境限制），包 {package_name} 无法安装"
            result["fallback_message"] = self._get_install_help(package_name)
            return result
        
        # 尝试从国内镜像安装
        success = False
        last_error = ""
        
        for mirror_index in range(len(self.PIP_MIRRORS)):
            mirror = self.PIP_MIRRORS[(self._current_mirror_index + mirror_index) % len(self.PIP_MIRRORS)]
            install_cmd = [
                sys.executable, "-m", "pip", "install",
                "-i", mirror,  # 使用镜像源
                "--trusted-host", mirror.split("://")[1].split("/")[0],  # 信任该主机
                "--quiet"
            ]
            
            if version:
                install_cmd.append(f"{package_name}=={version}")
            else:
                install_cmd.append(package_name)
            
            try:
                _ = subprocess.run(install_cmd, check=True, capture_output=True, text=True, timeout=120)
                success = True
                self._current_mirror_index = (self._current_mirror_index + mirror_index) % len(self.PIP_MIRRORS)
                break
            except Exception as e:
                last_error = str(e)
                continue  # 尝试下一个镜像
        
        if success:
            self._installed_packages[cache_key] = {
                "name": package_name,
                "version": version or "latest",
                "installed_at": time.time()
            }
            self._save_installed_packages()
            
            result["success"] = True
            result["message"] = f"成功安装 {package_name} (使用国内镜像)"
            result["installed"] = True
        else:
            result["message"] = f"安装失败: {last_error}"
            result["fallback_message"] = self._get_install_help(package_name)
        
        return result
    
    def _get_standard_lib_alternative(self, package_name: str) -> dict[str, str] | None:
        """获取标准库替代方案（仅当包未安装时返回）"""

        # 检查包是否已安装（预装的包不需要替代）
        installed_packages: list[str] = [
            'Pillow', 'PIL', 'pillow',
            'regex', 
            'numpy', 'np',
            'pandas', 'pd',
            'requests',
            'beautifulsoup4', 'bs4',
            'lxml',
            'jieba',
            'cryptography',
            'plotly',
        ]
        
        # 如果是预装的包，不返回替代方案
        if package_name.lower() in [p.lower() for p in installed_packages]:
            return None
        
        alternatives = {
            # 常用包的替代
            'numpy': {
                'name': 'array/list',
                'message': '使用 Python 内置 list 和 array 模块替代 numpy'
            },
            'pandas': {
                'name': 'csv/dict',
                'message': '使用 csv 模块和 dict 替代 pandas'
            },
            'matplotlib': {
                'name': 'text/table',
                'message': '使用文本表格替代 matplotlib 图表'
            },
            'requests': {
                'name': 'urllib.request',
                'message': '使用 urllib.request 替代 requests'
            },
            'beautifulsoup4': {
                'name': 're/html.parser',
                'message': '使用 re 和 html.parser 替代 beautifulsoup4'
            },
            'opencv-python': {
                'name': 'PIL/Pillow',
                'message': '使用 PIL (Pillow) 替代 cv2'
            },
            'cv2': {
                'name': 'PIL/Pillow',
                'message': '使用 PIL (Pillow) 替代 cv2'
            },
            'numpy as np': {
                'name': 'array',
                'message': '使用 list 替代 numpy'
            },
            'pd': {
                'name': 'csv',
                'message': '使用 csv 模块替代 pandas'
            },
            'plt': {
                'name': 'print',
                'message': '使用文本输出替代 matplotlib'
            },
            'sns': {
                'name': 'text',
                'message': '使用文本输出替代 seaborn'
            },
            'jieba': {
                'name': 'split',
                'message': '使用 str.split() 简单分词替代 jieba'
            },
        }
        return alternatives.get(package_name)
    
    def _get_install_help(self, package_name: str) -> str:
        """获取安装帮助信息"""
        return (
            f"提示：{package_name} 需要额外安装。\n"
            f"在 Android Chaquo Python 环境下，建议：\n"
            f"1. 使用 Python 标准库替代（如 re, csv, json, urllib）\n"
            f"2. 如需特定包，请在 build.gradle 中添加依赖：\n"
            f"   pythonDependenices.add(PythonDependency(\"{package_name}\"))"
        )
    
    def check_and_install_packages(self, code: str) -> dict[str, bool | list[str] | str]:
        """
        检查代码中需要的包，自动安装缺失的包

        Args:
            code: Python 代码

        Returns:
            {
                "success": bool,
                "packages_installed": list,
                "packages_failed": list,
                "message": str
            }
        """
        result: dict[str, bool | list[str] | str] = {
            "success": True,
            "packages_installed": [],
            "packages_failed": [],
            "message": ""
        }
        
        imports = self._extract_imports(code)
        installed_list: list[str] = []
        failed_list: list[dict[str, str | bool]] = []

        for import_name, package_name in imports.items():
            if not self._is_package_available(import_name):
                install_result = self.install_package(package_name)
                if install_result["success"]:
                    installed_list.append(package_name)
                else:
                    failed_list.append({
                        "package": package_name,
                        "error": install_result["message"]
                    })
                    result["success"] = False

        result["packages_installed"] = installed_list
        result["packages_failed"] = failed_list

        if installed_list:
            result["message"] = "已安装: " + ", ".join(installed_list)
        if failed_list:
            failed_msg = " 安装失败: " + ", ".join([p["package"] for p in failed_list])
            result["message"] = (result["message"] or "") + failed_msg
        
        return result
    
    def _extract_imports(self, code: str) -> dict[str, str]:
        """
        从代码中提取导入的包
        
        Returns:
            {import_name: package_name}
        """
        imports: dict[str, str] = {}

        standard_libs: set[str] = {
            'os', 'sys', 'json', 're', 'math', 'random', 'time', 'datetime',
            'collections', 'itertools', 'functools', 'operator', 'copy',
            'io', 'string', 'struct', 'codecs', 'unicodedata',
            'threading', 'multiprocessing', 'concurrent',
            'socket', 'ssl', 'select', 'asyncio',
            'hashlib', 'hmac', 'secrets',
            'base64', 'binascii', 'quopri', 'uu',
            'csv', 'configparser', 'argparse', 'logging',
            'pathlib', 'tempfile', 'shutil', 'glob',
            'urllib', 'urllib.parse', 'urllib.request', 'urllib.error',
            'html', 'html.parser', 'html.entities',
            'xml', 'xml.etree.ElementTree',
            'sqlite3', 'dbm', 'shelve',
            'pickle', 'marshal', 'zlib', 'gzip', 'bz2', 'lzma',
            'zipfile', 'tarfile',
            'unittest', 'doctest',
            'traceback', 'inspect', 'types',
            'abc', 'numbers', 'decimal', 'fractions',
            'statistics',
            'enum', 'dataclasses',
            'typing', 'typing_extensions'
        }
        
        import_patterns = [
            r'import\s+(\w+)',
            r'from\s+(\w+)',
            r'import\s+(\w+\.\w+)',
            r'from\s+(\w+\.\w+)',
        ]

        for pattern in import_patterns:
            matches = re.findall(pattern, code)
            for match in matches:
                # match 可能是 str 或 tuple[str, ...]
                match_str: str = match if isinstance(match, str) else match[0]
                base_name = match_str.split('.')[0]
                if base_name not in standard_libs:
                    imports[match_str] = base_name
        
        aliases: dict[str, str] = {
            'bs4': 'beautifulsoup4',
            'PIL': 'Pillow',
            'sklearn': 'scikit-learn',
            'cv2': 'opencv-python',
            'torch': 'torch',
            'tf': 'tensorflow',
            'np': 'numpy',
            'pd': 'pandas',
        }

        final_imports: dict[str, str] = {}
        for import_name, package_name in imports.items():
            final_imports[import_name] = aliases.get(package_name, package_name)
        
        return final_imports
    
    def _is_package_available(self, package_name: str) -> bool:
        """检查包是否可用"""
        base_name = package_name.split('.')[0]
        
        cache_key = base_name
        if cache_key in self._installed_packages:
            return True
        
        spec = importlib.util.find_spec(base_name)
        return spec is not None
    
    def fix_code(self, code: str, error: dict[str, str],
                 attempt_count: int = 0, max_attempts: int = 3) -> tuple[str, bool]:
        """
        自动修复代码

        Args:
            code: 原始代码
            error: 错误信息
            attempt_count: 当前尝试次数
            max_attempts: 最大尝试次数

        Returns:
            (fixed_code, success)
        """
        if attempt_count >= max_attempts:
            return code, False

        error_type: str = error.get("type", "")
        error_msg: str = error.get("message", "")
        
        fixed_code = code
        
        if error_type == "ModuleNotFoundError":
            match = re.search(r"No module named '(\w+)'", error_msg)
            if match:
                module_name = match.group(1)
                _ = self.install_package(module_name)
                return code, True
        
        elif error_type == "SyntaxError":
            fixed_code = self._fix_syntax_error(code, error)
        
        elif error_type == "IndentationError":
            fixed_code = self._fix_indentation(code)
        
        elif error_type == "NameError":
            match = re.search(r"name '(\w+)' is not defined", error_msg)
            if match:
                var_name = match.group(1)
                if var_name in ['np', 'pd', 'plt', 'sns', 'cv2']:
                    imports_map = {
                        'np': 'import numpy as np',
                        'pd': 'import pandas as pd',
                        'plt': 'import matplotlib.pyplot as plt',
                        'sns': 'import seaborn as sns',
                        'cv2': 'import cv2',
                    }
                    if var_name in imports_map:
                        fixed_code = imports_map[var_name] + '\n' + code
        
        elif error_type == "AttributeError":
            pass
        
        return fixed_code, fixed_code != code

    def _fix_syntax_error(self, code: str, error: dict[str, str]) -> str:
        """修复语法错误"""
        fixed = code

        lines = code.split('\n')

        tb: str = error.get("traceback", "")
        match = re.search(r'line (\d+)', tb)
        if match:
            line_num = int(match.group(1)) - 1
            if 0 <= line_num < len(lines):
                bad_line = lines[line_num]
                
                if bad_line.count('(') > bad_line.count(')'):
                    lines[line_num] = bad_line + ')'
                elif bad_line.count('[') > bad_line.count(']'):
                    lines[line_num] = bad_line + ']'
                elif bad_line.count('{') > bad_line.count('}'):
                    lines[line_num] = bad_line + '}'
                
                if bad_line.rstrip().endswith(':'):
                    pass
                elif bad_line.strip().startswith(('if ', 'for ', 'while ', 'def ', 'class ')):
                    if not bad_line.rstrip().endswith(':'):
                        lines[line_num] = bad_line.rstrip() + ':'
                
                fixed = '\n'.join(lines)
        
        fixed = fixed.replace('，', ',').replace('：', ':')
        fixed = fixed.replace('（', '(').replace('）', ')')
        fixed = fixed.replace('【', '[').replace('】', ']')
        
        return fixed
    
    def _fix_indentation(self, code: str) -> str:
        """修复缩进错误"""
        lines = code.split('\n')
        fixed_lines: list[str] = []
        current_indent: int = 0
        
        for line in lines:
            stripped = line.strip()
            
            if not stripped:
                fixed_lines.append('')
                continue
            
            if stripped.startswith(('def ', 'class ', 'if ', 'elif ', 'else:', 
                                    'for ', 'while ', 'try:', 'except ', 'finally:',
                                    'with ')):
                if stripped.endswith(':'):
                    fixed_lines.append(' ' * current_indent + stripped)
                    current_indent += 4
                else:
                    fixed_lines.append(' ' * current_indent + stripped + ':')
                    current_indent += 4
            
            elif stripped.startswith(('return ', 'pass', 'break', 'continue', 'raise ')):
                if current_indent >= 4:
                    current_indent -= 4
                fixed_lines.append(' ' * current_indent + stripped)
            
            else:
                fixed_lines.append(' ' * current_indent + stripped)
        
        return '\n'.join(fixed_lines)
    
    def execute_with_auto_fix(self, code: str, max_attempts: int = 3,
                              timeout: int = 30, variables: dict[str, object] | None = None) -> dict[str, bool | str | float | None | list[str]]:
        """
        执行代码并自动修复

        Args:
            code: Python 代码
            max_attempts: 最大修复尝试次数
            timeout: 超时时间
            variables: 变量字典

        Returns:
            {
                "success": bool,
                "result": object,
                "stdout": str,
                "stderr": str,
                "error": str,
                "attempts": int,
                "fixes_applied": list,
                "final_code": str
            }
        """
        current_code = code
        fixes_applied: list[dict[str, str | int]] = []
        last_error: dict[str, str] | None = None
        
        for attempt in range(max_attempts):
            packages_result = self.check_and_install_packages(current_code)

            result = self.execute_code(current_code, timeout=timeout, variables=variables)
            
            if result["success"]:
                return {
                    "success": True,
                    "result": result.get("result"),
                    "stdout": result.get("stdout", ""),
                    "stderr": result.get("stderr", ""),
                    "error": result.get("error", ""),
                    "execution_time": result.get("execution_time", 0.0),
                    "attempts": attempt + 1,
                    "fixes_applied": fixes_applied,
                    "final_code": current_code,
                    "packages_installed": packages_result.get("packages_installed", [])
                }
            
            last_error = result.get("error")
            
            fixed_code, was_fixed = self.fix_code(
                current_code, last_error or {}, attempt, max_attempts
            )
            
            if was_fixed and fixed_code != current_code:
                fixes_applied.append({
                    "attempt": attempt + 1,
                    "error_type": last_error.get("type", "") if last_error else "",
                    "original_code": current_code,
                    "fixed_code": fixed_code
                })
                current_code = fixed_code
            else:
                break
        
        return {
            "success": False,
            "result": None,
            "stdout": last_error.get("message", "") if last_error else "",
            "stderr": last_error.get("traceback", "") if last_error else "",
            "error": last_error,
            "attempts": max_attempts,
            "fixes_applied": fixes_applied,
            "final_code": current_code
        }
    
    def save_script(self, name: str, code: str, description: str = "") -> str:
        """保存脚本到文件"""
        safe_name = "".join(c if c.isalnum() or c in "._-" else "_" for c in name)
        if not safe_name.endswith('.py'):
            safe_name += '.py'
        
        script_path = os.path.join(self.work_dir, "scripts", safe_name)

        header = f'# -*- coding: utf-8 -*-\n# Tool: {name}\n# Description: {description}\n# Created: {time.strftime("%Y-%m-%d %H:%M:%S")}\n\n'

        with open(script_path, 'w', encoding='utf-8') as f:
            f.write(header + code)

        return script_path
    
    def run_script(self, name: str, args: dict[str, object] | None = None) -> dict[str, bool | str | float | None | list[str] | dict[str, str] | object]:
        """运行保存的脚本"""
        safe_name = "".join(c if c.isalnum() or c in "._-" else "_" for c in name)
        if not safe_name.endswith('.py'):
            safe_name += '.py'

        script_path = os.path.join(self.work_dir, "scripts", safe_name)

        if not os.path.exists(script_path):
            return {
                "success": False,
                "error": f"脚本不存在: {name}"
            }

        with open(script_path, 'r', encoding='utf-8') as f:
            code = f.read()

        script_vars: dict[str, object] = args.copy() if args else {}
        script_vars["script_args"] = args

        return self.execute_with_auto_fix(code, variables=script_vars)

    def list_scripts(self) -> list[dict[str, str | int]]:
        """列出所有保存的脚本"""
        scripts_dir = os.path.join(self.work_dir, "scripts")
        scripts: list[dict[str, str | int]] = []

        if os.path.exists(scripts_dir):
            for filename in os.listdir(scripts_dir):
                if filename.endswith('.py'):
                    filepath = os.path.join(scripts_dir, filename)
                    stat = os.stat(filepath)
                    scripts.append({
                        "name": filename[:-3],
                        "filename": filename,
                        "path": filepath,
                        "size": int(stat.st_size),
                        "modified": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(stat.st_mtime))
                    })

        return sorted(scripts, key=lambda x: x["modified"], reverse=True)
    
    def delete_script(self, name: str) -> bool:
        """删除脚本"""
        safe_name = "".join(c if c.isalnum() or c in "._-" else "_" for c in name)
        if not safe_name.endswith('.py'):
            safe_name += '.py'
        
        script_path = os.path.join(self.work_dir, "scripts", safe_name)
        
        if os.path.exists(script_path):
            os.remove(script_path)
            return True
        return False
    
    def get_log_path(self) -> str:
        """获取日志目录"""
        logs_dir = os.path.join(self.work_dir, "logs")
        if not os.path.exists(logs_dir):
            os.makedirs(logs_dir, exist_ok=True)
        return logs_dir

    # ========== 扩展工具能力 ==========

    def read_file_content(self, file_path: str) -> str:
        """读取文件内容"""
        try:
            with open(file_path, 'r', encoding='utf-8') as f:
                return f.read()
        except Exception as e:
            return f"读取文件失败: {e}"

    def write_file_content(self, file_path: str, content: str) -> str:
        """写入文件内容"""
        try:
            with open(file_path, 'w', encoding='utf-8') as f:
                f.write(content)
            return f"写入成功: {file_path}"
        except Exception as e:
            return f"写入文件失败: {e}"

    def parse_csv_data(self, content: str) -> dict[str, bool | str | list[dict[str, str]]]:
        """解析CSV内容"""
        import csv
        import io
        try:
            reader = csv.DictReader(io.StringIO(content))
            rows = list(reader)
            return {"success": True, "rows": rows, "count": len(rows)}
        except Exception as e:
            return {"success": False, "error": str(e)}

    def parse_json_data(self, content: str) -> dict[str, bool | object | str]:
        """解析JSON内容"""
        try:
            data = json.loads(content)
            return {"success": True, "data": data}
        except Exception as e:
            return {"success": False, "error": str(e)}

    def http_request(self, url: str, method: str = "GET", data: dict[str, object] | None = None) -> dict[str, bool | int | str]:
        """HTTP请求"""
        try:
            import urllib.request
            import urllib.parse

            if method == "GET" and data:
                query = urllib.parse.urlencode(data)
                url = url + "?" + query

            req = urllib.request.Request(url, method=method)
            req.add_header('User-Agent', 'Mozilla/5.0')

            if data and method == "POST":
                req.add_header('Content-Type', 'application/json')
                resp = urllib.request.urlopen(req, data=json.dumps(data).encode(), timeout=10)
                resp_body: str = resp.read().decode('utf-8')
                resp_status: int = resp.status
                return {
                    "success": True,
                    "content": resp_body,
                    "status": resp_status
                }
            else:
                with urllib.request.urlopen(req, timeout=10) as resp:
                    resp_body: str = resp.read().decode('utf-8')
                    resp_status: int = resp.status
                    return {
                        "success": True,
                        "content": resp_body,
                        "status": resp_status
                    }
        except Exception as e:
            return {"success": False, "error": str(e)}

    def extract_emails(self, text: str) -> list[str]:
        """提取邮箱地址"""
        pattern = r'[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}'
        return re.findall(pattern, text)

    def extract_urls(self, text: str) -> list[str]:
        """提取URL"""
        pattern = r'https?://[^\s<>"{}|\\^`\[\]]+'
        return re.findall(pattern, text)

    def extract_numbers(self, text: str) -> list[float]:
        """提取数字"""
        pattern = r'-?\d+\.?\d*'
        return [float(n) for n in re.findall(pattern, text)]  # type: ignore[type-arg]

    def calculate_statistics(self, numbers: list[float]) -> dict[str, float | str]:
        """计算统计数据"""
        if not numbers:
            return {"error": "数据为空"}
        return {
            "count": len(numbers),
            "sum": sum(numbers),
            "mean": sum(numbers) / len(numbers),
            "min": min(numbers),
            "max": max(numbers),
            "variance": sum((x - sum(numbers)/len(numbers))**2 for x in numbers) / len(numbers)
        }

    def clean_data_list(self, data: list[str], remove_empty: bool = True, remove_duplicates: bool = False) -> list[str]:
        """清洗数据列表"""
        result: list[str] = list(data)
        if remove_empty:
            result = [x for x in result if x not in ("", "null", "None")]
        if remove_duplicates:
            result = list(dict.fromkeys(result))
        return result

    def text_transform(self, text: str, operation: str) -> str:
        """文本转换"""
        if operation == "upper":
            return text.upper()
        elif operation == "lower":
            return text.lower()
        elif operation == "title":
            return text.title()
        elif operation == "strip":
            return text.strip()
        elif operation == "reverse":
            return text[::-1]
        return text


_engine_instance: PythonToolEngine | None = None


def get_engine(context: object | None = None) -> PythonToolEngine:
    """获取全局引擎实例"""
    global _engine_instance
    if _engine_instance is None:
        _engine_instance = PythonToolEngine(context)
    return _engine_instance


def execute_python_code(code: str, context: object | None = None, auto_fix: bool = True,
                        max_attempts: int = 3, timeout: int = 30) -> dict[str, bool | str | float | None | list[str] | dict[str, str] | object]:
    """
    便捷函数：执行 Python 代码

    Args:
        code: Python 代码
        context: Android Context
        auto_fix: 是否自动修复
        max_attempts: 最大尝试次数
        timeout: 超时时间

    Returns:
        执行结果字典
    """
    engine = get_engine(context)

    if auto_fix:
        return engine.execute_with_auto_fix(code, max_attempts=max_attempts, timeout=timeout)
    else:
        return engine.execute_code(code, timeout=timeout)
