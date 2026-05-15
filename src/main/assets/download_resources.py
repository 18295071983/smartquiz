#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AI模型和测试资源自动下载脚本

功能：
1. 下载TFLite嵌入模型（从HuggingFace或备用源）
2. 生成测试用图像
3. 验证文件完整性

使用方法：
    python download_resources.py --all                    # 下载所有资源
    python download_resources.py --model all-MiniLM-L6-v2 # 只下载指定模型
    python download_resources.py --images                 # 只下载测试图片
    python download_resources.py --check                  # 检查已下载的资源

依赖安装：
    pip install requests tqdm pillow numpy
"""

import argparse
import json
import os
import sys
import hashlib
import urllib.request
from pathlib import Path
from typing import Optional, Dict, List, Tuple

try:
    from tqdm import tqdm
except ImportError:
    def tqdm(iterable, **kwargs):
        return iterable

try:
    from PIL import Image, ImageDraw, ImageFont
    import numpy as np
    HAS_PIL = True
except ImportError:
    HAS_PIL = False
    print("⚠️ Pillow未安装，将无法生成测试图片。请运行: pip install pillow")

# ============================================================
# 配置常量
# ============================================================

BASE_DIR = Path(__file__).parent
MODELS_DIR = BASE_DIR / "models"
IMAGES_DIR = BASE_DIR / "images"

# ============================================================
# 国内镜像源配置（优先使用）
# ============================================================

MIRRORS = {
    "huggingface_cn": {
        "name": "HuggingFace 国内镜像",
        "base_url": "https://hf-mirror.com",
        "priority": 1,
        "description": "速度快，推荐使用"
    },
    "modelscope": {
        "name": "ModelScope (魔搭社区)",
        "base_url": "https://www.modelscope.cn",
        "priority": 2,
        "description": "阿里云托管，稳定可靠"
    },
    "huggingface_original": {
        "name": "HuggingFace 官方",
        "base_url": "https://huggingface.co",
        "priority": 3,
        "description": "官方源，可能较慢"
    }
}

# 默认使用的镜像（可修改）
DEFAULT_MIRROR = "huggingface_cn"

# 模型配置 - 包含多个下载源
MODELS_CONFIG = {
    "all-MiniLM-L6-v2": {
        "name": "all-MiniLM-L6-v2",
        "description": "轻量级句子嵌入模型",
        "files": [
            {
                "name": "all-MiniLM-L6-v2.tflite",
                # 多个下载源（按优先级排序）
                "urls": {
                    "huggingface_cn": "https://hf-mirror.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/model.tflite",
                    "modelscope": None,  # ModelScope暂无TFLite格式
                    "huggingface_original": "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/model.tflite"
                },
                "size_mb": 22,
                "expected_hash": None
            },
            {
                "name": "all-MiniLM-L6-v2_vocab.txt",
                "url": None,  # 使用本地生成的词汇表
                "size_mb": 0.5,
                "generate": True
            }
        ],
        "embedding_dim": 384,
        "max_length": 128
    },
    "paraphrase-multilingual-MiniLM-L12-v2": {
        "name": "paraphrase-multilingual-MiniLM-L12-v2",
        "description": "多语言句子嵌入模型",
        "files": [
            {
                "name": "multilingual-MiniLM-L12-v2.tflite",
                "urls": {
                    "huggingface_cn": "https://hf-mirror.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2/resolve/main/model.tflite",
                    "huggingface_original": "https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2/resolve/main/model.tflite"
                },
                "size_mb": 90,
                "expected_hash": None
            }
        ],
        "embedding_dim": 384,
        "max_length": 512
    }
}

# 测试图片配置
TEST_IMAGES_CONFIG = [
    {
        "name": "test_image.jpg",
        "type": "labeling",
        "description": "图像标签识别测试图",
        "width": 640,
        "height": 480,
        "content": "single_object"
    },
    {
        "name": "test_objects.jpg", 
        "type": "detection",
        "description": "目标检测测试图",
        "width": 1280,
        "height": 720,
        "content": "multiple_objects"
    },
    {
        "name": "test_text.png",
        "type": "ocr",
        "description": "OCR文字识别测试图",
        "width": 800,
        "height": 600,
        "content": "text_document"
    }
]


def print_banner():
    """打印欢迎横幅"""
    banner = """
╔═══════════════════════════════════════════════════════════╗
║     🤖 AI 模型和测试资源下载工具                           ║
║     AI Model & Test Resources Downloader                   ║
╚═══════════════════════════════════════════════════════════╝
"""
    print(banner)


def ensure_directories():
    """确保必要的目录存在"""
    MODELS_DIR.mkdir(parents=True, exist_ok=True)
    IMAGES_DIR.mkdir(parents=True, exist_ok=True)
    print(f"✅ 目录已准备就绪:")
    print(f"   📁 模型目录: {MODELS_DIR}")
    print(f"   📁 图片目录: {IMAGES_DIR}")
    print()


def download_file(url: str, destination: Path, description: str = "", 
                 mirror_name: str = "") -> bool:
    """
    下载文件并显示进度条
    
    Args:
        url: 下载链接
        destination: 保存路径
        description: 描述信息
        mirror_name: 镜像源名称
    
    Returns:
        bool: 是否成功
    """
    try:
        print(f"\n📥 正在下载: {description or destination.name}")
        if mirror_name:
            print(f"   🌐 镜像源: {MIRRORS.get(mirror_name, {}).get('name', mirror_name)}")
        print(f"   URL: {url[:80]}..." if len(url) > 80 else f"   URL: {url}")
        
        urllib.request.urlretrieve(url, destination)
        
        size_mb = destination.stat().st_size / (1024 * 1024)
        print(f"   ✅ 下载完成! 大小: {size_mb:.2f} MB")
        return True
        
    except Exception as e:
        print(f"   ❌ 下载失败: {str(e)}")
        if destination.exists():
            destination.unlink()
        return False


def download_file_with_fallback(urls_dict: dict, destination: Path, 
                                description: str = "") -> Tuple[bool, str]:
    """
    使用多个镜像源下载文件（自动切换）
    
    Args:
        urls_dict: 镜像源名称到URL的映射
        destination: 保存路径
        description: 描述信息
    
    Returns:
        Tuple[bool, str]: (是否成功, 使用的镜像名称)
    """
    if not urls_dict or all(v is None for v in urls_dict.values()):
        print(f"\n⚠️ 无可用的下载源: {description or destination.name}")
        return False, ""
    
    # 按优先级排序尝试
    sorted_mirrors = sorted(
        [(name, url) for name, url in urls_dict.items() if url],
        key=lambda x: MIRRORS.get(x[0], {}).get("priority", 99)
    )
    
    for mirror_name, url in sorted_mirrors:
        print(f"\n🔄 尝试使用镜像: {MIRRORS.get(mirror_name, {}).get('name', mirror_name)}")
        
        if download_file(url, destination, description, mirror_name):
            return True, mirror_name
        
        print(f"   ⏭️ 切换到下一个镜像...")
    
    print(f"\n❌ 所有镜像源均失败: {description or destination.name}")
    return False, ""


def generate_test_image(config: dict) -> bool:
    """
    生成测试图片
    
    Args:
        config: 图片配置字典
    
    Returns:
        bool: 是否成功
    """
    if not HAS_PIL:
        print("❌ 无法生成图片：Pillow库未安装")
        print("   请运行: pip install pillow numpy")
        return False
    
    name = config["name"]
    img_type = config["type"]
    width = config["width"]
    height = config["height"]
    content = config["content"]
    
    output_path = IMAGES_DIR / name
    
    print(f"\n🎨 正在生成测试图片: {name}")
    print(f"   类型: {img_type}")
    print(f"   尺寸: {width}x{height}")
    
    try:
        if content == "single_object":
            img = generate_single_object_image(width, height)
        elif content == "multiple_objects":
            img = generate_multiple_objects_image(width, height)
        elif content == "text_document":
            img = generate_text_document_image(width, height)
        else:
            img = generate_placeholder_image(width, height, f"Test: {img_type}")
        
        # 保存图片
        if name.endswith('.png'):
            img.save(output_path, 'PNG')
        else:
            img.save(output_path, 'JPEG', quality=90)
        
        size_kb = output_path.stat().st_size / 1024
        print(f"   ✅ 图片生成成功! 大小: {size_kb:.1f} KB")
        return True
        
    except Exception as e:
        print(f"   ❌ 图片生成失败: {str(e)}")
        return False


def generate_single_object_image(width: int, height: int) -> Image.Image:
    """生成单个物体测试图片（模拟猫的轮廓）"""
    img = Image.new('RGB', (width, height), color=(240, 248, 255))  # 浅蓝背景
    draw = ImageDraw.Draw(img)
    
    # 绘制简单的形状代表物体
    center_x, center_y = width // 2, height // 2
    
    # 绘制圆形（代表头部）
    head_radius = min(width, height) // 4
    draw.ellipse(
        [center_x - head_radius, center_y - head_radius,
         center_x + head_radius, center_y + head_radius],
        fill=(255, 182, 193),  # 粉色
        outline=(255, 105, 180),
        width=3
    )
    
    # 绘制耳朵
    ear_size = head_radius // 2
    draw.polygon([
        (center_x - head_radius + 20, center_y - head_radius + 20),
        (center_x - head_radius + 10, center_y - head_radius - ear_size + 20),
        (center_x - head_radius + 40, center_y - head_radius + 20)
    ], fill=(255, 182, 193))
    
    draw.polygon([
        (center_x + head_radius - 20, center_y - head_radius + 20),
        (center_x + head_radius - 10, center_y - head_radius - ear_size + 20),
        (center_x + head_radius - 40, center_y - head_radius + 20)
    ], fill=(255, 182, 193))
    
    # 绘制眼睛
    eye_radius = head_radius // 6
    draw.ellipse(
        [center_x - head_radius//2 - eye_radius, center_y - eye_radius,
         center_x - head_radius//2 + eye_radius, center_y + eye_radius],
        fill=(0, 0, 0)
    )
    draw.ellipse(
        [center_x + head_radius//2 - eye_radius, center_y - eye_radius,
         center_x + head_radius//2 + eye_radius, center_y + eye_radius],
        fill=(0, 0, 0)
    )
    
    # 添加文字标签
    try:
        font = ImageFont.truetype("arial.ttf", 24)
    except:
        font = ImageFont.load_default()
    
    text = "Cat Test Image"
    bbox = draw.textbbox((0, 0), text, font=font)
    text_width = bbox[2] - bbox[0]
    draw.text(((width - text_width) // 2, height - 50), text, 
              fill=(70, 130, 180), font=font)
    
    return img


def generate_multiple_objects_image(width: int, height: int) -> Image.Image:
    """生成多物体测试图片"""
    img = Image.new('RGB', (width, height), color=(245, 245, 220))  # 米色背景
    draw = ImageDraw.Draw(img)
    
    # 绘制多个不同颜色的矩形（代表不同的物体）
    objects = [
        (100, 100, 250, 200, (255, 99, 71)),    # 红色矩形
        (300, 150, 450, 300, (60, 179, 113)),   # 绿色矩形
        (500, 80, 700, 250, (65, 105, 225)),    # 蓝色矩形
        (200, 350, 400, 500, (255, 215, 0)),    # 黄色矩形
        (600, 320, 800, 480, (238, 130, 238)),  # 紫色矩形
        (900, 150, 1100, 350, (255, 140, 0)),   # 橙色矩形
    ]
    
    for x1, y1, x2, y2, color in objects:
        draw.rectangle([x1, y1, x2, y2], fill=color, outline=(0, 0, 0), width=2)
    
    # 绘制圆形物体
    circles = [
        (150, 400, 80, (255, 105, 180)),
        (750, 500, 100, (0, 206, 209)),
        (1050, 450, 70, (154, 205, 50)),
    ]
    
    for cx, cy, r, color in circles:
        draw.ellipse([cx-r, cy-r, cx+r, cy+r], fill=color, outline=(0, 0, 0), width=2)
    
    # 添加标签
    try:
        font = ImageFont.truetype("arial.ttf", 28)
    except:
        font = ImageFont.load_default()
    
    text = "Multiple Objects Detection Test"
    bbox = draw.textbbox((0, 0), text, font=font)
    text_width = bbox[2] - bbox[0]
    draw.text(((width - text_width) // 2, 20), text, 
              fill=(139, 69, 19), font=font)
    
    return img


def generate_text_document_image(width: int, height: int) -> Image.Image:
    """生成文本文档测试图片（用于OCR）"""
    img = Image.new('RGB', (width, height), color=(255, 255, 255))  # 白色背景
    draw = ImageDraw.Draw(img)
    
    # 尝试加载字体
    try:
        title_font = ImageFont.truetype("arial.ttf", 32)
        body_font = ImageFont.truetype("arial.ttf", 24)
        chinese_font = ImageFont.truetype("simsun.ttc", 24) if os.path.exists("C:/Windows/Fonts/simsun.ttc") else body_font
    except:
        title_font = ImageFont.load_default()
        body_font = title_font
        chinese_font = title_font
    
    y_position = 40
    
    # 标题
    title_en = "Artificial Intelligence Overview"
    draw.text((50, y_position), title_en, fill=(0, 0, 0), font=title_font)
    y_position += 60
    
    # 英文段落
    en_text = """Machine Learning is a subset of artificial intelligence that provides systems the ability to automatically learn and improve from experience without being explicitly programmed. It focuses on developing computer programs that can access data and use it to learn for themselves."""
    
    lines = []
    words = en_text.split()
    current_line = ""
    
    for word in words:
        test_line = current_line + word + " "
        bbox = draw.textbbox((0, 0), test_line, font=body_font)
        if bbox[2] - bbox[0] < width - 100:
            current_line = test_line
        else:
            lines.append(current_line.strip())
            current_line = word + " "
    lines.append(current_line.strip())
    
    for line in lines:
        draw.text((50, y_position), line, fill=(30, 30, 30), font=body_font)
        y_position += 35
    
    y_position += 20
    
    # 中文段落
    zh_title = "人工智能概述"
    draw.text((50, y_position), zh_title, fill=(0, 0, 0), font=title_font)
    y_position += 60
    
    zh_text = """人工智能（AI）是计算机科学的一个分支，致力于创造能够执行通常需要人类智能任务的系统。这些任务包括学习、推理、问题解决、感知和语言理解等。机器学习是实现AI的主要方法之一。"""
    
    # 简单换行处理中文
    chars_per_line = 35
    for i in range(0, len(zh_text), chars_per_line):
        line = zh_text[i:i+chars_per_line]
        draw.text((50, y_position), line, fill=(30, 30, 30), font=chinese_font)
        y_position += 40
    
    y_position += 20
    
    # 技术术语列表
    terms = ["- Deep Learning 深度学习", "- Neural Networks 神经网络",
             "- Natural Language Processing 自然语言处理",
             "- Computer Vision 计算机视觉"]
    
    for term in terms:
        draw.text((70, y_position), term, fill=(0, 100, 0), font=body_font)
        y_position += 35
    
    # 边框
    draw.rectangle([10, 10, width-10, height-10], outline=(200, 200, 200), width=2)
    
    return img


def generate_placeholder_image(width: int, height: int, text: str) -> Image.Image:
    """生成占位符图片"""
    img = Image.new('RGB', (width, height), color=(220, 220, 220))
    draw = ImageDraw.Draw(img)
    
    try:
        font = ImageFont.truetype("arial.ttf", 36)
    except:
        font = ImageFont.load_default()
    
    bbox = draw.textbbox((0, 0), text, font=font)
    text_width = bbox[2] - bbox[0]
    text_height = bbox[3] - bbox[1]
    
    x = (width - text_width) // 2
    y = (height - text_height) // 2
    
    draw.text((x, y), text, fill=(100, 100, 100), font=font)
    
    return img


def check_model_files(model_name: str) -> Tuple[bool, List[str]]:
    """
    检查模型文件是否完整
    
    Returns:
        Tuple[bool, List[str]]: (是否完整, 缺失的文件列表)
    """
    if model_name not in MODELS_CONFIG:
        return False, [f"未知模型: {model_name}"]
    
    config = MODELS_CONFIG[model_name]
    missing_files = []
    
    for file_config in config["files"]:
        file_path = MODELS_DIR / file_config["name"]
        if not file_path.exists():
            missing_files.append(file_config["name"])
        elif file_path.stat().st_size < 1024:  # 小于1KB可能是不完整的
            missing_files.append(f"{file_config['name']} (文件可能损坏)")
    
    return len(missing_files) == 0, missing_files


def check_images() -> Tuple[bool, List[str]]:
    """
    检查测试图片是否存在
    
    Returns:
        Tuple[bool, List[str]]: (是否完整, 缺失的文件列表)
    """
    missing_files = []
    
    for img_config in TEST_IMAGES_CONFIG:
        img_path = IMAGES_DIR / img_config["name"]
        if not img_path.exists():
            missing_files.append(img_config["name"])
    
    return len(missing_files) == 0, missing_files


def download_model(model_name: str) -> bool:
    """
    下载指定模型（自动使用国内镜像）
    
    Args:
        model_name: 模型名称
    
    Returns:
        bool: 是否成功
    """
    if model_name not in MODELS_CONFIG:
        print(f"❌ 未知模型: {model_name}")
        print(f"   可用模型: {', '.join(MODELS_CONFIG.keys())}")
        return False
    
    config = MODELS_CONFIG[model_name]
    print(f"\n{'='*60}")
    print(f"📦 准备下载模型: {config['name']}")
    print(f"   描述: {config['description']}")
    print(f"   嵌入维度: {config['embedding_dim']}")
    print(f"   🌐 默认镜像: {MIRRORS[DEFAULT_MIRROR]['name']}")
    print(f"{'='*60}")
    
    success_count = 0
    total_files = len(config["files"])
    
    for file_config in config["files"]:
        file_path = MODELS_DIR / file_config["name"]
        
        # 如果文件已存在且大小合理，跳过
        if file_path.exists() and file_path.stat().st_size > 1024:
            print(f"\n⏭️ 文件已存在，跳过: {file_config['name']}")
            success_count += 1
            continue
        
        # 检查是否需要生成本地文件
        if file_config.get("generate", False):
            print(f"\n📝 生成本地文件: {file_config['name']}")
            if file_path.exists():
                success_count += 1
            continue
        
        # 下载文件 - 支持多源切换
        if "urls" in file_config and file_config["urls"]:
            # 使用多源自动切换
            success, used_mirror = download_file_with_fallback(
                file_config["urls"], 
                file_path, 
                file_config["name"]
            )
            
            if success:
                success_count += 1
                if used_mirror:
                    mirror_info = MIRRORS.get(used_mirror, {})
                    print(f"   ✅ 成功使用镜像: {mirror_info.get('name', used_mirror)}")
                    
        elif file_config.get("url"):
            # 兼容旧格式（单URL）
            if download_file(file_config["url"], file_path, file_config["name"]):
                success_count += 1
        else:
            print(f"\n⚠️ 无下载URL: {file_config['name']}（请手动提供）")
    
    print(f"\n{'='*60}")
    if success_count == total_files:
        print(f"✅ 模型 '{model_name}' 下载完成！ ({success_count}/{total_files})")
        return True
    else:
        print(f"⚠️ 模型 '{model_name}' 部分完成 ({success_count}/{total_files})")
        return False


def download_all_models() -> bool:
    """下载所有模型"""
    print("\n🔄 开始下载所有模型...\n")
    
    all_success = True
    for model_name in MODELS_CONFIG.keys():
        if not download_model(model_name):
            all_success = False
    
    return all_success


def download_images() -> bool:
    """生成/下载所有测试图片"""
    print("\n🖼️ 开始生成测试图片...\n")
    
    success_count = 0
    total_images = len(TEST_IMAGES_CONFIG)
    
    for img_config in TEST_IMAGES_CONFIG:
        if generate_test_image(img_config):
            success_count += 1
    
    print(f"\n{'='*60}")
    if success_count == total_images:
        print(f"✅ 所有测试图片生成完成！ ({success_count}/{total_images})")
        return True
    else:
        print(f"⚠️ 部分图片生成完成 ({success_count}/{total_images})")
        return False


def show_status():
    """显示当前资源状态"""
    print("\n" + "="*60)
    print("📊 当前资源状态")
    print("="*60 + "\n")
    
    # 检查模型
    print("📦 模型文件:")
    for model_name, config in MODELS_CONFIG.items():
        is_complete, missing = check_model_files(model_name)
        status_icon = "✅" if is_complete else "❌"
        print(f"   {status_icon} {model_name}")
        if missing:
            for m in missing:
                print(f"      ⚠️ 缺失: {m}")
    
    print("\n🖼️ 测试图片:")
    is_complete, missing = check_images()
    status_icon = "✅" if is_complete else "❌"
    print(f"   {status_icon} 图片资源")
    if missing:
        for m in missing:
            print(f"      ⚠️ 缺失: {m}")
    
    print()


def main():
    """主函数"""
    global DEFAULT_MIRROR
    
    parser = argparse.ArgumentParser(
        description="AI模型和测试资源下载工具（支持国内镜像）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例用法:
  %(prog)s --all                     下载所有资源（默认使用国内镜像）
  %(prog)s --model all-MiniLM-L6-v2  下载特定模型
  %(prog)s --images                  生成测试图片
  %(prog)s --check                   检查资源状态
  %(prog)s --mirrors                 列出可用的镜像源
  %(prog)s --mirror modelscope       使用指定镜像下载

国内镜像源:
  - huggingface_cn: HuggingFace国内镜像 (推荐，速度快)
  - modelscope: 魔搭社区 (阿里云，稳定)
  - huggingface_original: HuggingFace官方 (可能较慢)
        """
    )
    
    parser.add_argument("--all", action="store_true", 
                       help="下载所有模型和测试图片")
    parser.add_argument("--model", type=str, metavar="MODEL_NAME",
                       help="下载指定的模型名称")
    parser.add_argument("--images", action="store_true",
                       help="生成测试图片")
    parser.add_argument("--check", action="store_true",
                       help="检查已下载资源的完整性")
    parser.add_argument("--list", action="store_true",
                       help="列出可用的模型")
    parser.add_argument("--mirrors", action="store_true",
                       help="列出可用的国内镜像源")
    parser.add_argument("--mirror", type=str, metavar="MIRROR_NAME",
                       default=DEFAULT_MIRROR,
                       help=f"指定使用的镜像源 (默认: {DEFAULT_MIRROR})")
    
    args = parser.parse_args()
    
    # 更新全局默认镜像
    if args.mirror in MIRRORS:
        DEFAULT_MIRROR = args.mirror
    
    print_banner()
    
    # 创建目录
    ensure_directories()
    
    # 列出可用镜像
    if args.mirrors:
        print("\n🌐 可用的国内镜像源:\n")
        for mirror_id, mirror_info in MIRRORS.items():
            is_default = "⭐ 默认" if mirror_id == DEFAULT_MIRROR else ""
            print(f"  {mirror_id}:")
            print(f"    名称: {mirror_info['name']}")
            print(f"    地址: {mirror_info['base_url']}")
            print(f"    描述: {mirror_info['description']} {is_default}")
            print(f"    优先级: {mirror_info['priority']}")
            print()
        
        print(f"\n使用方法:")
        print(f"  python download_resources.py --model all-MiniLM-L6-v2 --mirror huggingface_cn")
        return 0
    
    # 列出可用模型
    if args.list:
        print("\n📋 可用的模型:\n")
        for name, config in MODELS_CONFIG.items():
            print(f"  • {name}")
            print(f"    描述: {config['description']}")
            print(f"    维度: {config['embedding_dim']}")
            print(f"    文件数: {len(config['files'])}")
            print()
        return 0
    
    # 检查状态
    if args.check:
        show_status()
        return 0
    
    # 执行下载任务
    success = True
    
    if args.all:
        success = download_all_models() and download_images()
    
    if args.model:
        success = download_model(args.model)
    
    if args.images:
        success = download_images()
    
    if not any([args.all, args.model, args.images, args.check, args.list]):
        parser.print_help()
        return 1
    
    # 显示最终状态
    print("\n" + "="*60)
    if success:
        print("🎉 所有操作完成成功！")
    else:
        print("⚠️ 部分操作未完成，请检查上面的输出。")
    print("="*60 + "\n")
    
    # 提示下一步
    print("💡 下一步操作:")
    print("   1. 运行 GPUTestActivity 进行GPU测试")
    print("   2. 运行 MLKitVisionTester 进行视觉功能测试")
    print("   3. 查看 assets/models/README.md 了解更多配置选项")
    print()
    
    return 0 if success else 1


if __name__ == "__main__":
    sys.exit(main())
