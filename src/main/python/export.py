# -*- coding: utf-8 -*-
"""
导出模块 - 使用 Python 库处理各种格式的导出
支持: Excel (.xlsx), CSV, Markdown, JSON, PDF, 长图片
使用 openpyxl 处理 Excel
纯 Python 实现 CSV/Markdown/JSON（无需额外依赖）
"""

import json
import os
import datetime
import csv
import io
import traceback
import re


# ==================== Android UI 辅助函数 ====================

def _ui_return_success(message):
    """Android UI 操作返回成功"""
    return {'success': True, 'message': message}


def _ui_return_error(message):
    """Android UI 操作返回错误"""
    return {'success': False, 'message': message}


def safe_get_dict(data, key, default=None):
    """安全获取字典的值，处理 data 不是 dict 的情况"""
    if isinstance(data, dict):
        return data.get(key, default)
    return default


def is_valid_question(q):
    """检查是否为有效的题目对象"""
    if q is None:
        return False
    if not isinstance(q, dict):
        return False
    # 至少要有题型或题目文本之一
    has_type = q.get('questionType') or q.get('type')
    has_text = q.get('questionText') or q.get('text')
    return has_type or has_text


def safe_get_str(data, key, default=''):
    """安全获取字符串值"""
    if not isinstance(data, dict):
        return default
    value = data.get(key)
    if value is None:
        return default
    return str(value)


def clean_questions(raw_questions):
    """
    清洗题目数据，过滤无效题目
    """
    if raw_questions is None:
        return []
    if not isinstance(raw_questions, list):
        return []
    
    cleaned = []
    for q in raw_questions:
        if is_valid_question(q):
            cleaned.append(q)
    
    return cleaned


def safe_parse_config(config_raw):
    """
    安全解析配置参数
    """
    if config_raw is None or config_raw == '':
        return {}
    if isinstance(config_raw, str):
        try:
            config = json.loads(config_raw)
            if isinstance(config, dict):
                return config
            return {}
        except (json.JSONDecodeError, Exception):
            return {}
    if isinstance(config_raw, dict):
        return config_raw
    return {}


def safe_parse_questions(questions_raw):
    """
    安全解析题目数据（可能是 JSON 字符串或列表）
    """
    if questions_raw is None:
        return []
    if isinstance(questions_raw, str):
        try:
            questions = json.loads(questions_raw)
            if isinstance(questions, list):
                return clean_questions(questions)
            return []
        except (json.JSONDecodeError, Exception) as e:
            # JSON 解析失败，尝试直接作为列表处理
            return []
    if isinstance(questions_raw, list):
        return clean_questions(questions_raw)
    return []


def export_to_excel(filepath, questions, config=None):
    """
    导出为 Excel 格式 (.xlsx) - 现代简洁样式
    
    Args:
        filepath: 输出文件路径
        questions: 题目列表（Python list）或 JSON 字符串
        config: 配置字典或 JSON 字符串
    
    Returns:
        dict: {'success': True/False, 'filepath': str, 'error': str}
    """
    try:
        from openpyxl import Workbook
        from openpyxl.styles import Font, Alignment, PatternFill, Border, Side
        
        # 安全解析参数
        questions = safe_parse_questions(questions)
        config = safe_parse_config(config)
        
        if len(questions) == 0:
            return {'success': False, 'error': '没有可导出的题目数据'}
        
        fields = config.get('fields', [
            'id', 'questionType', 'questionText', 'optionA', 'optionB',
            'optionC', 'optionD', 'optionE', 'correctAnswer', 'explanation',
            'category', 'difficulty'
        ])
        
        wb = Workbook()
        ws = wb.active
        ws.title = "题目导出"
        
        # 定义样式 - 现代简洁无边框
        header_fill = PatternFill(start_color="3B82F6", end_color="3B82F6", fill_type="solid")
        header_font = Font(bold=True, color="FFFFFF", size=11)
        header_alignment = Alignment(horizontal="center", vertical="center", wrap_text=True)
        data_font = Font(size=10)
        data_alignment = Alignment(wrap_text=True, vertical="center")
        even_fill = PatternFill(start_color="F9FAFB", end_color="F9FAFB", fill_type="solid")
        
        # 表头
        for col_idx, field in enumerate(fields, 1):
            cell = ws.cell(row=1, column=col_idx, value=_field_display_name(field))
            cell.font = header_font
            cell.fill = header_fill
            cell.alignment = header_alignment
        ws.row_dimensions[1].height = 24
        
        # 冻结首行
        ws.freeze_panes = 'A2'
        
        # 数据行 - 带斑马纹
        for idx, question in enumerate(questions):
            row_idx = idx + 2
            for col_idx, field in enumerate(fields, 1):
                value = _get_field_value(question, field)
                cell = ws.cell(row=row_idx, column=col_idx, value=value)
                cell.font = data_font
                cell.alignment = data_alignment
                if idx % 2 == 0:
                    cell.fill = even_fill
            ws.row_dimensions[row_idx].height = 24
        
        # 设置列宽
        column_widths = {
            'id': 8, 'questionType': 12, 'questionText': 50,
            'optionA': 35, 'optionB': 35, 'optionC': 35, 'optionD': 35, 'optionE': 35,
            'correctAnswer': 12, 'explanation': 50, 'category': 20,
            'difficulty': 10, 'knowledgePoint': 20
        }
        for i, field in enumerate(fields, 1):
            if i <= 26:  # A-Z
                width = column_widths.get(field, 25)
                ws.column_dimensions[chr(64 + i)].width = width
        
        # 保存文件
        wb.save(filepath)
        
        return {'success': True, 'filepath': filepath}
    
    except ImportError as e:
        import traceback
        error_detail = f"Import error: {str(e)}\n{traceback.format_exc()}"
        return {'success': False, 'error': error_detail}
    except Exception as e:
        import traceback
        error_detail = f"Export error: {str(e)}\n{traceback.format_exc()}"
        return {'success': False, 'error': error_detail}


def _test_openpyxl():
    """
    测试 openpyxl 是否可用
    
    Returns:
        bool: True 如果可用，False 如果不可用
    """
    try:
        from openpyxl import Workbook
        wb = Workbook()
        ws = wb.active
        ws.cell(row=1, column=1, value="test")
        return True
    except Exception as e:
        import traceback
        error_detail = f"openpyxl test failed: {str(e)}\n{traceback.format_exc()}"
        print("openpyxl test failed:", error_detail)
        return False


def _test_reportlab():
    """
    测试 reportlab 是否可用

    Returns:
        bool: True 如果可用，False 如果不可用
    """
    try:
        import reportlab
        return True
    except ImportError as e:
        import traceback
        error_detail = f"reportlab test failed: {str(e)}\n{traceback.format_exc()}"
        print("reportlab test failed:", error_detail)
        return False


def export_to_csv(filepath, questions, config=None):
    """
    导出为 CSV 格式
    
    Args:
        filepath: 输出文件路径
        questions: 题目列表或 JSON 字符串
        config: 配置字典或 JSON 字符串
    
    Returns:
        dict: {'success': True/False, 'filepath': str, 'error': str}
    """
    try:
        # 安全解析参数
        questions = safe_parse_questions(questions)
        config = safe_parse_config(config)
        
        if len(questions) == 0:
            return {'success': False, 'error': '没有可导出的题目数据'}
        
        fields = config.get('fields', [
            'id', 'questionType', 'questionText', 'optionA', 'optionB',
            'optionC', 'optionD', 'correctAnswer', 'explanation'
        ])
        
        with open(filepath, 'w', newline='', encoding='utf-8-sig') as f:
            writer = csv.writer(f)
            # 写入表头（显示名称）
            writer.writerow([_field_display_name(f) for f in fields])
            # 写入数据
            for question in questions:
                row = [_get_field_value(question, field) for field in fields]
                writer.writerow(row)
        
        return {'success': True, 'filepath': filepath}
    
    except Exception as e:
        return {'success': False, 'error': str(e)}


def export_to_markdown(filepath, questions, config=None):
    """
    导出为 Markdown 格式 - 美化排版
    """
    try:
        # 安全解析参数
        questions = safe_parse_questions(questions)
        config = safe_parse_config(config)
        
        if len(questions) == 0:
            return {'success': False, 'error': '没有可导出的题目数据'}
        
        include_answers = config.get('includeAnswers', config.get('include_answers', True))
        include_explanations = config.get('includeExplanations', config.get('include_explanations', True))
        category = config.get('category', '')
        
        with open(filepath, 'w', encoding='utf-8') as f:
            # 写入 BOM
            f.write('\ufeff')
            f.write("# 📚 题目导出\n\n")
            f.write(f"> 导出时间: {datetime.datetime.now().strftime('%Y-%m-%d %H:%M')} | 共 {len(questions)} 道题目\n\n")
            
            # 统计信息
            types = {}
            for q in questions:
                qtype = q.get('questionType', '未分类') if isinstance(q, dict) else '未分类'
                types[qtype] = types.get(qtype, 0) + 1
            
            f.write("### 📊 题型分布\n\n")
            for qtype, count in sorted(types.items()):
                f.write(f"- **{qtype}**: {count} 题\n")
            f.write("\n---\n\n")
            
            # 按题型分组
            questions_by_type = {}
            for q in questions:
                qtype = q.get('questionType', '未分类') if isinstance(q, dict) else '未分类'
                if qtype not in questions_by_type:
                    questions_by_type[qtype] = []
                questions_by_type[qtype].append(q)
            
            for qtype, type_questions in sorted(questions_by_type.items()):
                f.write(f"## 📝 {qtype}\n\n")
                
                for idx, q in enumerate(type_questions, 1):
                    # 题目卡片
                    f.write(f"### 第{idx}题\n\n")
                    
                    # 标签
                    difficulty = q.get('difficulty', '')
                    category_val = q.get('category', '')
                    knowledge_point = q.get('knowledgePoint', '')
                    tags = []
                    if difficulty:
                        tags.append(f"难度: {'⭐' * difficulty}")
                    if category_val:
                        tags.append(f"分类: {category_val}")
                    if knowledge_point:
                        tags.append(f"知识点: {knowledge_point}")
                    
                    if tags:
                        f.write(f"> {' | '.join(tags)}\n\n")
                    
                    # 题目内容
                    f.write(f"**{q.get('questionText', '')}**\n\n")
                    
                    # 选项列表
                    f.write("| 选项 | 内容 |\n|------|------|\n")
                    for letter in ['A', 'B', 'C', 'D', 'E', 'F']:
                        option_key = f'option{letter}'
                        option_value = q.get(option_key, '')
                        if option_value:
                            f.write(f"| {letter} | {option_value} |\n")
                    f.write("\n")
                    
                    # 答案和解析
                    if include_answers or include_explanations:
                        f.write("---\n")
                        if include_answers:
                            correct = q.get('correctAnswer', '')
                            if correct:
                                f.write(f"**✅ 正确答案**: {correct}\n\n")
                        
                        if include_explanations:
                            explanation = q.get('explanation', '')
                            if explanation:
                                f.write(f"**💡 解析**: {explanation}\n\n")
                    
                    f.write("\n")
        
        return {'success': True, 'filepath': filepath}
    
    except Exception as e:
        return {'success': False, 'error': str(e)}


def export_to_json(filepath, questions, config=None):
    """
    导出为 JSON 格式
    """
    try:
        # 安全解析参数
        questions = safe_parse_questions(questions)
        
        if len(questions) == 0:
            return {'success': False, 'error': '没有可导出的题目数据'}
        
        output = {
            'exportTime': datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            'totalCount': len(questions),
            'questions': questions
        }
        
        with open(filepath, 'w', encoding='utf-8') as f:
            json.dump(output, f, ensure_ascii=False, indent=2)
        
        return {'success': True, 'filepath': filepath}
    
    except Exception as e:
        return {'success': False, 'error': str(e)}


def export_to_long_image(filepath, questions, config=None):
    """
    导出为长图片格式 (.png) - 使用 Pillow 渲染
    现代卡片式设计，支持中文，支持分批分页导出
    
    Args:
        filepath: 输出文件路径（如果是列表，则输出多张图片）
        questions: 题目列表或 JSON 字符串
        config: 配置字典或 JSON 字符串
    
    Returns:
        dict: {'success': True/False, 'filepath': str/list, 'error': str}
    """
    try:
        from PIL import Image, ImageDraw, ImageFont
        import os
        
        # 安全解析参数
        questions = safe_parse_questions(questions)
        config = safe_parse_config(config)
        
        if len(questions) == 0:
            return {'success': False, 'error': '没有可导出的题目数据'}
        
        # 每张图片的题目数量（默认 50 题/图）
        questions_per_image = config.get('questionsPerImage', config.get('questions_per_image', 50))
        # 输出目录（如果 filepath 是目录，则在此目录下生成多个图片）
        output_dir = None
        if os.path.isdir(filepath):
            output_dir = filepath
        elif isinstance(filepath, str) and os.path.isdir(os.path.dirname(filepath)):
            # 如果 filepath 是文件路径，提取目录
            output_dir = os.path.dirname(filepath)
        
        # 中文字体路径列表（按优先级排序）
        font_paths = [
            os.path.join(os.path.dirname(os.path.abspath(__file__)), 'simkai.ttf'),
            os.path.join(os.path.dirname(os.path.abspath(__file__)), 'simhei.ttf'),
            '/system/fonts/DroidSansFallbackFull.ttf',
            '/system/fonts/NotoSansCJK-Regular.ttc',
            '/system/fonts/DroidSansFallback.ttf',
            '/system/fonts/SansSerif-Regular.ttf',
        ]
        
        # 加载中文字体
        def load_font(size):
            """加载指定大小的字体"""
            for fpath in font_paths:
                if fpath and os.path.exists(fpath):
                    try:
                        return ImageFont.truetype(fpath, size)
                    except Exception:
                        continue
            try:
                return ImageFont.load_default()
            except Exception:
                return ImageFont.load_default()
        
        font = load_font(28)
        title_font = load_font(44)
        heading_font = load_font(36)
        
        # 颜色定义
        BG_COLOR = (248, 250, 252)      # 浅灰背景
        CARD_BG = (255, 255, 255)        # 白色卡片
        CARD_BORDER = (226, 232, 240)    # 浅灰边框
        TITLE_COLOR = (15, 23, 42)       # 深色标题
        TYPE_COLOR = (100, 116, 139)     # 灰色题型
        TEXT_COLOR = (30, 41, 59)        # 深灰文本
        ANSWER_COLOR = (6, 95, 70)       # 绿色答案
        EXPLANATION_COLOR = (21, 94, 117) # 青色解析
        
        # 布局参数
        width = 1080
        card_margin = 48
        card_padding = 56
        card_radius = 24
        card_spacing = 30
        content_width = width - 2 * card_margin
        
        # 计算行高
        line_height = int(36)
        heading_size = 32
        small_size = 22
        
        # 生成单个图片的辅助函数
        def generate_single_image(questions_batch, batch_index, total_batches):
            """生成单张图片"""
            total_height = 600  # 标题 + 信息
            
            # 计算总高度
            for q in questions_batch:
                if not isinstance(q, dict):
                    continue
                h = 100
                if q.get('questionText'):
                    lines = max(1, len(q.get('questionText', '')) * 28 // (content_width - 40))
                    h += lines * line_height + 20
                for opt_field in ['optionA', 'optionB', 'optionC', 'optionD', 'optionE', 'optionF']:
                    opt = q.get(opt_field) or ''
                    if opt:
                        lines = max(1, len(opt) * 28 // (content_width - 80))
                        h += lines * line_height + 8
                include_answers = config.get('includeAnswers', config.get('include_answers', False))
                include_explanations = config.get('includeExplanations', config.get('include_explanations', False))
                if include_answers:
                    if q.get('correctAnswer') or q.get('answerText'):
                        h += 60
                if include_explanations:
                    explanation = q.get('explanation') or q.get('analysis') or ''
                    if explanation:
                        lines = max(1, len(explanation) * 26 // (content_width - 40))
                        h += lines * 32 + 32
                total_height += h + card_spacing
            
            total_height += 100  # 底部页脚
            
            # 创建图片
            img = Image.new('RGB', (width, total_height), BG_COLOR)
            draw = ImageDraw.Draw(img)
            
            y = card_margin + 40
            
            # 绘制标题
            draw.text((width // 2, y), "题目导出", fill=TITLE_COLOR, anchor="mm", font=title_font)
            
            # 绘制导出信息
            export_time = datetime.datetime.now().strftime("%Y-%m-%d %H:%M")
            if total_batches > 1:
                info_text = f"第 {batch_index + 1}/{total_batches} 批 · 共 {len(questions_batch)} 道题目"
            else:
                info_text = f"共 {len(questions_batch)} 道题目 · {export_time}"
            y += 60
            draw.text((width // 2, y), info_text, fill=(148, 163, 184), anchor="mm", font=font)
            y += 60
            
            # 绘制题目卡片
            for i, q in enumerate(questions_batch):
                if not isinstance(q, dict):
                    continue
                
                card_height = 100
                card_bottom = y + card_height
                
                # 计算实际卡片高度
                include_answers = config.get('includeAnswers', config.get('include_answers', False))
                include_explanations = config.get('includeExplanations', config.get('include_explanations', False))
                if q.get('questionText'):
                    lines = max(1, len(q.get('questionText', '')) * 28 // (content_width - 40))
                    card_height += lines * line_height + 20
                for opt_field in ['optionA', 'optionB', 'optionC', 'optionD', 'optionE', 'optionF']:
                    opt = q.get(opt_field) or ''
                    if opt:
                        lines = max(1, len(opt) * 28 // (content_width - 80))
                        card_height += lines * line_height + 8
                if include_answers:
                    if q.get('correctAnswer') or q.get('answerText'):
                        card_height += 60
                if include_explanations:
                    explanation = q.get('explanation') or q.get('analysis') or ''
                    if explanation:
                        lines = max(1, len(explanation) * 26 // (content_width - 40))
                        card_height += lines * 32 + 32
                
                card_bottom = y + card_height
                
                # 绘制卡片背景
                draw.rounded_rectangle(
                    [card_margin, y, width - card_margin, card_bottom],
                    radius=card_radius, fill=CARD_BG, outline=CARD_BORDER, width=2
                )
                
                card_y = y + card_padding
                
                # 绘制题型标签
                type_label = f"第{i+1}题 · {q.get('questionType', '')}"
                draw.text((card_margin + card_padding, card_y), type_label, fill=TYPE_COLOR, font=font)
                card_y += heading_size + 15
                
                # 题目正文
                q_text = q.get('questionText', '')
                if q_text:
                    _draw_wrapped_text(draw, q_text, (card_margin + card_padding, card_y), 
                                       content_width - 2 * card_padding, font, TEXT_COLOR)
                    card_y += _count_lines(q_text, content_width - 2 * card_padding, font, 28) * line_height + 20
                
                # 选项
                for opt_field in ['optionA', 'optionB', 'optionC', 'optionD', 'optionE', 'optionF']:
                    opt_label = opt_field[6:].upper()
                    opt_text = q.get(opt_field) or ''
                    if opt_text:
                        label = f"{opt_label}. {opt_text}"
                        draw.text((card_margin + card_padding + 20, card_y), label, fill=(71, 85, 105), font=font)
                        card_y += line_height + 8
                
                # 分割线
                if q.get('correctAnswer') or config.get('includeAnswers', config.get('include_answers', False)):
                    line_y = card_y + 10
                    draw.line(
                        [(card_margin + card_padding, line_y), (width - 2 * card_margin, line_y)],
                        fill=CARD_BORDER, width=1
                    )
                    card_y += 20
                
                # 答案
                include_answers = config.get('includeAnswers', config.get('include_answers', False))
                if include_answers:
                    correct = q.get('correctAnswer') or q.get('answerText')
                    if correct:
                        draw.text((card_margin + card_padding, card_y), f"正确答案：{correct}", fill=ANSWER_COLOR, font=font)
                        card_y += 50
                
                # 解析
                include_explanations = config.get('includeExplanations', config.get('include_explanations', False))
                if include_explanations:
                    explanation = q.get('explanation') or q.get('analysis')
                    if explanation:
                        draw.text((card_margin + card_padding, card_y), f"解析：{explanation}", fill=EXPLANATION_COLOR, font=font)
                        card_y += 40
                
                y = card_bottom + card_spacing
            
            # 页脚
            draw.text((width // 2, total_height - 50), "导出完成", fill=(148, 163, 184), anchor="mm", font=font)
            
            return img
        
        # 分批处理题目
        all_files = []
        total_batches = max(1, len(questions) // questions_per_image + (1 if len(questions) % questions_per_image else 0))
        
        for batch_start in range(0, len(questions), questions_per_image):
            batch = questions[batch_start:batch_start + questions_per_image]
            batch_index = batch_start // questions_per_image
            
            # 生成单张图片
            img = generate_single_image(batch, batch_index, total_batches)
            
            # 保存文件
            if output_dir:
                # 多文件导出
                base_name = os.path.basename(filepath) if os.path.isfile(filepath) else "题目导出"
                if total_batches > 1:
                    file_name = f"{base_name.replace('.png', '')}_第{batch_index + 1}批.png"
                else:
                    file_name = f"{base_name.replace('.png', '')}.png"
                out_path = os.path.join(output_dir, file_name)
            else:
                # 单文件导出
                if total_batches > 1:
                    out_path = f"{os.path.splitext(filepath)[0]}_第{batch_index + 1}批.png"
                else:
                    out_path = filepath
            
            img.save(out_path, 'PNG')
            all_files.append(out_path)
        
        # 返回结果
        if len(all_files) == 1:
            return {'success': True, 'filepath': all_files[0]}
        else:
            return {'success': True, 'filepath': all_files, 'file_count': len(all_files)}
        # 返回结果
        if len(all_files) == 1:
            return {'success': True, 'filepath': all_files[0]}
        else:
            return {'success': True, 'filepath': all_files, 'file_count': len(all_files)}
    
    except ImportError as e:
        return {'success': False, 'error': f'缺少 Pillow 库: {str(e)}'}
    except Exception as e:
        import traceback
        return {'success': False, 'error': f'图片导出失败: {str(e)}\n{traceback.format_exc()}'}


def _draw_wrapped_text(draw, text, position, max_width, font, fill):
    """绘制自动换行的文本"""
    x, y = position
    words = text.split()
    line = ""
    line_y = y
    
    for word in words:
        test_line = line + word + " " if line else word
        bbox = draw.textbbox((0, 0), test_line, font=font)
        text_width = bbox[2] - bbox[0]
        
        if text_width <= max_width:
            line = test_line
        else:
            draw.text((x, line_y), line.strip(), fill=fill, font=font)
            line = word + " "
            line_y += int(font.size * 1.3)
    
    if line:
        draw.text((x, line_y), line.strip(), fill=fill, font=font)


def _count_lines(text, max_width, font, font_size):
    """计算文本需要多少行"""
    if not text:
        return 1
    return max(1, len(text) * font_size // max(max_width, 1))


def _field_display_name(field):
    """获取字段的显示名称"""
    names = {
        'id': '序号', 'questionType': '题型', 'questionText': '题目',
        'optionA': 'A选项', 'optionB': 'B选项', 'optionC': 'C选项', 'optionD': 'D选项',
        'optionE': 'E选项', 'optionF': 'F选项', 'correctAnswer': '正确答案',
        'answerText': '答案文本', 'explanation': '解析', 'analysis': '详细解析',
        'category': '分类', 'subCategory': '子分类', 'difficulty': '难度',
        'knowledgePoint': '知识点', 'tags': '标签', 'source': '来源',
        'author': '作者', 'points': '分值', 'hint': '提示',
        'comment': '备注', 'relatedQuestion': '相关题目'
    }
    return names.get(field, field)


def _get_field_value(question, field):
    """获取题目字段的值"""
    if not isinstance(question, dict):
        return ""
    value = question.get(field, "")
    if value is None:
        return ""
    return str(value)


def export_to_pdf(filepath, questions, config=None):
    """
    导出为 PDF 格式（支持中文）- 使用 reportlab
    现代简洁样式
    
    Args:
        filepath: 输出文件路径
        questions: 题目列表或 JSON 字符串
        config: 配置字典或 JSON 字符串
    
    Returns:
        dict: {'success': True/False, 'filepath': str, 'error': str}
    """
    try:
        # 安全解析参数
        questions = safe_parse_questions(questions)
        config = safe_parse_config(config)
        
        if len(questions) == 0:
            return {'success': False, 'error': '没有可导出的题目数据'}
        
        # 每页题目数量（默认 30 题/页）
        questions_per_page = config.get('questionsPerPage', config.get('questions_per_page', 30))
        
        from reportlab.lib.pagesizes import A4
        from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, PageBreak
        from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
        from reportlab.lib.units import cm
        from reportlab.lib.colors import HexColor
        from reportlab.pdfbase import pdfmetrics
        from reportlab.pdfbase.ttfonts import TTFont
        from reportlab.lib.utils import escape
        import os
        
        # 加载中文字体（支持 PDF 中文）
        font_name = None
        try:
            # 尝试从 assets 目录加载字体
            font_dir = os.path.dirname(os.path.abspath(__file__))
            font_files = ['simkai.ttf', 'simhei.ttf']
            for font_file in font_files:
                font_path = os.path.join(font_dir, font_file)
                if os.path.exists(font_path):
                    pdfmetrics.registerFont(TTFont('SimHei', font_path))
                    font_name = 'SimHei'
                    break
            
            # 如果 assets 中没有，尝试从 Android 字体目录加载
            if font_name is None:
                android_fonts = [
                    '/system/fonts/DroidSansFallbackFull.ttf',
                    '/system/fonts/NotoSansCJK-Regular.ttc',
                    '/system/fonts/DroidSansFallback.ttf',
                    '/system/fonts/SansSerif-Regular.ttf',
                ]
                for fpath in android_fonts:
                    if os.path.exists(fpath):
                        pdfmetrics.registerFont(TTFont('SimHei', fpath))
                        font_name = 'SimHei'
                        break
        except Exception as e:
            pass  # 字体加载失败会使用默认字体
        
        # 如果字体加载失败，尝试使用默认字体
        if font_name is None:
            font_name = 'Helvetica'
        
        # 创建 PDF 文档
        doc = SimpleDocTemplate(filepath, pagesize=A4,
                                leftMargin=2*cm, rightMargin=2*cm,
                                topMargin=2*cm, bottomMargin=2*cm)
        
        # 定义样式
        styles = getSampleStyleSheet()
        
        # 辅助函数：创建安全的 Paragraph（自动转义 HTML 特殊字符）
        def safe_paragraph(text, style):
            """创建安全的 Paragraph，自动转义特殊字符"""
            if text:
                return Paragraph(escape(str(text)), style)
            return None
        
        # 中文样式
        if font_name != 'Helvetica':
            title_style = ParagraphStyle('TitleCN', parent=styles['Normal'],
                                         fontname=font_name, fontsize=20, leading=28,
                                         alignment=1, spaceAfter=12)
            heading_style = ParagraphStyle('HeadingCN', parent=styles['Heading1'],
                                           fontname=font_name, fontsize=16, leading=22,
                                           spaceAfter=6, spaceBefore=12)
            body_style = ParagraphStyle('BodyCN', parent=styles['Normal'],
                                        fontname=font_name, fontsize=11, leading=17)
            small_style = ParagraphStyle('SmallCN', parent=styles['Normal'],
                                         fontname=font_name, fontsize=9, leading=13,
                                         textColor=HexColor('#888888'))
        else:
            title_style = styles['Title']
            heading_style = styles['Heading1']
            body_style = styles['Normal']
            small_style = styles['Normal']
        
        # 构建 PDF 内容
        story = []
        page_count = 0
        
        # 标题（仅第一页显示）
        if page_count == 0:
            story.append(Paragraph("导出题目", title_style))
            story.append(Spacer(1, 6))
            
            # 导出信息
            now = datetime.datetime.now().strftime("%Y-%m-%d %H:%M")
            story.append(Paragraph(escape(f"导出时间: {now}"), small_style))
            story.append(Paragraph(escape(f"导出题目总数: {len(questions)}"), small_style))
            story.append(Spacer(1, 12))
            page_count += 1
        
        # 按题型分组
        questions_by_type = {}
        for q in questions:
            if not isinstance(q, dict):
                continue
            type_name = q.get('questionType', '未分类') or '未分类'
            if type_name not in questions_by_type:
                questions_by_type[type_name] = []
            questions_by_type[type_name].append(q)
        
        # 排序题型
        sorted_types = sorted(questions_by_type.keys())
        
        type_index = 1
        for type_name in sorted_types:
            type_questions = questions_by_type[type_name]
            
            # 题型标题
            story.append(Paragraph(
                escape(f"{type_index}、{type_name} ({len(type_questions)}题)"),
                heading_style
            ))
            
            # 题目（分批处理）
            q_num = 1
            for batch_start in range(0, len(type_questions), questions_per_page):
                batch = type_questions[batch_start:batch_start + questions_per_page]
                
                for q in batch:
                    if not isinstance(q, dict):
                        q_num += 1
                        continue
                    
                    # 题目正文
                    q_text = q.get('questionText', '') or ''
                    if q_text:
                        story.append(Paragraph(escape(f"第{q_num}题: {q_text}"), body_style))
                    else:
                        q_num += 1
                        continue
                    
                    # 元信息
                    meta_parts = []
                    if q.get('questionType'):
                        meta_parts.append(f"题型: {q['questionType']}")
                    if q.get('difficulty'):
                        meta_parts.append(f"难度: {q['difficulty']}")
                    if q.get('category'):
                        meta_parts.append(f"分类: {q['category']}")
                    if q.get('knowledgePoint'):
                        meta_parts.append(f"知识点: {q['knowledgePoint']}")
                    if meta_parts:
                        story.append(Paragraph(escape(" | ".join(meta_parts)), small_style))
                    
                    # 选项
                    options = []
                    for label in ['optionA', 'optionB', 'optionC', 'optionD', 'optionE', 'optionF']:
                        opt_text = q.get(label) or ''
                        if opt_text:
                            options.append(f"{label[6:].upper()}. {opt_text}")
                    if options:
                        for opt in options:
                            story.append(Paragraph(escape(opt), body_style))
                    
                    # 答案
                    include_answers = config.get('includeAnswers', config.get('include_answers', False))
                    if include_answers:
                        correct = q.get('correctAnswer') or q.get('answerText')
                        if correct:
                            story.append(Paragraph(escape(f"正确答案: {correct}"), body_style))
                    
                    # 解析
                    include_explanations = config.get('includeExplanations', config.get('include_explanations', False))
                    if include_explanations:
                        explanation = q.get('explanation') or q.get('analysis')
                        if explanation:
                            story.append(Paragraph(escape(f"解析: {explanation}"), body_style))
                    
                    # 其他信息
                    extra_parts = []
                    if q.get('tags'):
                        extra_parts.append(f"标签: {q['tags']}")
                    if q.get('hint'):
                        extra_parts.append(f"提示: {q['hint']}")
                    if q.get('source'):
                        extra_parts.append(f"来源: {q['source']}")
                    if q.get('author'):
                        extra_parts.append(f"作者: {q['author']}")
                    if extra_parts:
                        story.append(Paragraph(escape(" ".join(extra_parts)), small_style))
                    
                    story.append(Spacer(1, 6))
                    q_num += 1
                
                # 如果不是最后一个批次，添加分页符
                if batch_start + questions_per_page < len(type_questions):
                    story.append(PageBreak())
            
            type_index += 1
        
        # 构建 PDF
        doc.build(story)
        
        return {'success': True, 'filepath': filepath}
    
    except ImportError as e:
        return {'success': False, 'error': f'缺少 reportlab 库: {str(e)}'}
    except Exception as e:
        import traceback
        return {'success': False, 'error': f'PDF 导出失败: {str(e)}\n{traceback.format_exc()}'}


def export_to_html(filepath, questions, config=None):
    """
    导出为 HTML 格式 - 学习查看版
    现代卡片式布局，答案和解析可折叠显示，适合查看、学习和背诵
    
    Args:
        filepath: 输出文件路径
        questions: 题目列表或 JSON 字符串
        config: 配置字典或 JSON 字符串
    
    Returns:
        dict: {'success': True/False, 'filepath': str, 'error': str}
    """
    try:
        # 安全解析参数
        questions = safe_parse_questions(questions)
        config = safe_parse_config(config)
        
        if len(questions) == 0:
            return {'success': False, 'error': '没有可导出的题目数据'}
        
        include_answers = config.get('includeAnswers', config.get('include_answers', True))
        include_explanations = config.get('includeExplanations', config.get('include_explanations', True))
        answers_collapsible = config.get('answersCollapsible', config.get('answers_collapsible', True))
        explanations_collapsible = config.get('explanationsCollapsible', config.get('explanations_collapsible', True))
        
        # 构建题目HTML
        questions_html = ""
        for idx, q in enumerate(questions, 1):
            if not isinstance(q, dict):
                continue
            
            # 题目信息
            question_type = q.get('questionType', '') or ''
            question_text = q.get('questionText', '') or ''
            correct_answer = q.get('correctAnswer', '') or ''
            explanation = q.get('explanation', '') or ''
            knowledge_point = q.get('knowledgePoint', '') or ''
            category = q.get('category', '') or ''
            difficulty = q.get('difficulty', '') or ''
            
            # 难度显示
            difficulty_str = ''
            if difficulty:
                try:
                    difficulty_num = int(difficulty)
                    difficulty_str = '⭐' * difficulty_num
                except:
                    difficulty_str = str(difficulty)
            
            # 构建标签
            tags_html = ''
            if difficulty:
                tags_html += f'<span class="tag difficulty">难度: {difficulty_str}</span>'
            if category:
                tags_html += f'<span class="tag category">{category}</span>'
            if question_type:
                tags_html += f'<span class="tag">{question_type}</span>'
            
            # 构建选项
            options_html = ''
            for letter in ['A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L']:
                option_value = q.get(f'option{letter}', '') or ''
                if option_value:
                    options_html += f'<div class="option"><span class="option-label">{letter}.</span> {option_value}</div>'
            
            # 构建题目卡片
            question_html = f'''<div class="question-card">
  <div class="question-header">
    <span class="question-number">第 {idx} 题</span>
    <div class="question-tags">{tags_html}</div>
  </div>
  <div class="question-text">{question_text}</div>
  {options_html}'''
            
            # 知识点
            if knowledge_point:
                question_html += f'<div class="knowledge-point">{knowledge_point}</div>'
            
            # 答案部分（可折叠）
            if include_answers and correct_answer:
                if answers_collapsible:
                    question_html += f'''
  <div class="answer-section">
    <div class="collapsible-header" onclick="toggleAnswer({idx})">
      <span class="collapsible-title">✅ 查看答案</span>
      <span class="collapsible-icon" id="answer-icon-{idx}">▼</span>
    </div>
    <div class="collapsible-content" id="answer-{idx}">
      <div class="answer-content">{correct_answer}</div>
    </div>
  </div>'''
                else:
                    question_html += f'''
  <div class="answer-section">
    <div class="answer-content"><strong>✅ 正确答案：</strong>{correct_answer}</div>
  </div>'''
            
            # 解析部分（可折叠）
            if include_explanations and explanation:
                if explanations_collapsible:
                    question_html += f'''
  <div class="explanation-section">
    <div class="collapsible-header" onclick="toggleExplanation({idx})">
      <span class="collapsible-title">💡 查看解析</span>
      <span class="collapsible-icon" id="explanation-icon-{idx}">▼</span>
    </div>
    <div class="collapsible-content" id="explanation-{idx}">
      <div class="explanation-content">{explanation}</div>
    </div>
  </div>'''
                else:
                    question_html += f'''
  <div class="explanation-section">
    <div class="explanation-content"><strong>💡 解析：</strong>{explanation}</div>
  </div>'''
            
            question_html += '\n</div>'
            questions_html += question_html
        
        # 导出时间
        export_time = datetime.datetime.now().strftime('%Y-%m-%d %H:%M')
        
        # 生成完整HTML
        html_content = f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>题目学习</title>
<style>
* {{
  box-sizing: border-box;
  margin: 0;
  padding: 0;
}}
body {{
  font-family: 'Microsoft YaHei', 'PingFang SC', Arial, sans-serif;
  line-height: 1.6;
  color: #1a202c;
  background-color: #f7fafc;
  padding: 20px;
}}
.container {{
  max-width: 900px;
  margin: 0 auto;
}}
h1 {{
  text-align: center;
  color: #2d3748;
  margin-bottom: 10px;
  font-size: 24px;
}}
.info {{
  text-align: center;
  color: #718096;
  margin-bottom: 30px;
  font-size: 14px;
}}
.question-card {{
  background: #fff;
  border-radius: 12px;
  padding: 24px;
  margin-bottom: 20px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.08);
  border-left: 4px solid #4299e1;
}}
.question-header {{
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}}
.question-number {{
  font-size: 14px;
  color: #4299e1;
  font-weight: 600;
}}
.question-tags {{
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}}
.tag {{
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 12px;
  background: #edf2f7;
  color: #4a5568;
}}
.tag.difficulty {{
  background: #fefcbf;
  color: #975a16;
}}
.tag.category {{
  background: #e6fffa;
  color: #234e52;
}}
.question-text {{
  font-size: 16px;
  line-height: 1.8;
  color: #2d3748;
  margin-bottom: 16px;
  font-weight: 500;
}}
.options {{
  margin: 16px 0;
}}
.option {{
  padding: 10px 12px;
  margin-bottom: 8px;
  border-radius: 8px;
  border: 1px solid #e2e8f0;
  font-size: 14px;
  line-height: 1.6;
  transition: all 0.2s;
}}
.option:hover {{
  background: #f7fafc;
  border-color: #4299e1;
}}
.option-label {{
  display: inline-block;
  min-width: 24px;
  font-weight: 600;
  color: #4299e1;
}}
.answer-section,
.explanation-section {{
  margin-top: 16px;
}}
.collapsible-header {{
  display: flex;
  align-items: center;
  justify-content: space-between;
  cursor: pointer;
  padding: 12px 16px;
  border-radius: 8px;
  user-select: none;
  transition: background 0.2s;
}}
.collapsible-header:hover {{
  background: #f7fafc;
}}
.collapsible-title {{
  font-weight: 600;
  font-size: 14px;
}}
.collapsible-icon {{
  font-size: 12px;
  transition: transform 0.3s;
}}
.collapsible-icon.open {{
  transform: rotate(180deg);
}}
.collapsible-content {{
  max-height: 0;
  overflow: hidden;
  transition: max-height 0.3s ease-out;
}}
.collapsible-content.open {{
  max-height: 2000px;
}}
.answer-content {{
  padding: 12px 16px;
  background: #f0fff4;
  border: 1px solid #c6f6d5;
  border-radius: 8px;
  color: #22543d;
  font-size: 14px;
}}
.explanation-content {{
  padding: 12px 16px;
  background: #ebf8ff;
  border: 1px solid #bee3f8;
  border-radius: 8px;
  color: #2c5282;
  font-size: 14px;
  line-height: 1.8;
}}
.knowledge-point {{
  margin-top: 8px;
  padding: 8px 12px;
  background: #faf5ff;
  border: 1px solid #d6bcfa;
  border-radius: 8px;
  color: #553c9a;
  font-size: 13px;
}}
.knowledge-point::before {{
  content: "📖 知识点：";
  font-weight: 600;
}}
.controls {{
  position: sticky;
  top: 0;
  z-index: 100;
  background: #fff;
  padding: 12px 20px;
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.1);
  margin-bottom: 20px;
  display: flex;
  gap: 10px;
  justify-content: center;
}}
.controls button {{
  padding: 8px 16px;
  border: 1px solid #4299e1;
  background: #4299e1;
  color: #fff;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  transition: all 0.2s;
}}
.controls button:hover {{
  background: #3182ce;
}}
.controls button.outline {{
  background: #fff;
  color: #4299e1;
}}
.controls button.outline:hover {{
  background: #ebf8ff;
}}
</style>
</head>
<body>
<div class="container">
<h1>📚 题目学习</h1>
<p class="info">共 {len(questions)} 道题目 | 导出时间: {export_time}</p>

<div class="controls">
<button onclick="toggleAll(true)">展开全部</button>
<button onclick="toggleAll(false)" class="outline">折叠全部</button>
</div>

{questions_html}
</div>

<script>
function toggleAnswer(index) {{
  const content = document.getElementById('answer-' + index);
  const icon = document.getElementById('answer-icon-' + index);
  content.classList.toggle('open');
  icon.classList.toggle('open');
}}

function toggleExplanation(index) {{
  const content = document.getElementById('explanation-' + index);
  const icon = document.getElementById('explanation-icon-' + index);
  content.classList.toggle('open');
  icon.classList.toggle('open');
}}

function toggleAll(show) {{
  document.querySelectorAll('.collapsible-content').forEach(el => {{
    if (show) el.classList.add('open');
    else el.classList.remove('open');
  }});
  document.querySelectorAll('.collapsible-icon').forEach(el => {{
    if (show) el.classList.add('open');
    else el.classList.remove('open');
  }});
}}
</script>
</body>
</html>"""
        
        # 写入文件（带BOM）
        with open(filepath, 'w', encoding='utf-8-sig') as f:
            f.write(html_content)
        
        return {'success': True, 'filepath': filepath}
    
    except Exception as e:
        import traceback
        return {'success': False, 'error': f'HTML 导出失败: {str(e)}\n{traceback.format_exc()}'}


