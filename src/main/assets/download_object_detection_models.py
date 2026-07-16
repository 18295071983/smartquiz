
import os
import time
from pathlib import Path

try:
    import requests
    HAS_REQUESTS = True
except ImportError:
    HAS_REQUESTS = False
    print("⚠️ requests 库未安装，尝试使用 urllib")

try:
    from modelscope import snapshot_download
    HAS_MODELSCOPE = True
except ImportError:
    HAS_MODELSCOPE = False

MODELS_DIR = Path(__file__).parent / "models"
MODELS_DIR.mkdir(exist_ok=True)

MODELS = [
    {
        "name": "ssd_mobilenet_v2_coco_quant_postprocess.tflite",
        "description": "SSD MobileNet V2 (轻量级目标检测模型，300x300输入，90个类别)",
        "input_size": [300, 300],
        "num_classes": 90,
        "model_format": "ssd",
        "size_mb": 6.6,
        "sources": [
            {
                "type": "url",
                "url": "https://raw.githubusercontent.com/google-coral/test_data/master/ssd_mobilenet_v2_coco_quant_postprocess.tflite"
            },
            {
                "type": "modelscope",
                "model_id": "google/ssd_mobilenet_v2_coco_quant_postprocess_tflite",
                "file_name": "ssd_mobilenet_v2_coco_quant_postprocess.tflite"
            }
        ]
    },
    {
        "name": "efficientdet_lite0_320_ptq.tflite",
        "description": "EfficientDet-Lite0 (更准确的目标检测模型，320x320输入，90个类别)",
        "input_size": [320, 320],
        "num_classes": 90,
        "model_format": "efficientdet",
        "size_mb": 5.7,
        "sources": [
            {
                "type": "url",
                "url": "https://raw.githubusercontent.com/google-coral/test_data/master/efficientdet_lite0_320_ptq.tflite"
            },
            {
                "type": "modelscope",
                "model_id": "google/efficientdet_lite0_320_ptq_tflite",
                "file_name": "efficientdet_lite0_320_ptq.tflite"
            }
        ]
    }
]

def download_with_requests(url, output_path, timeout=300):
    """使用 requests 下载文件"""
    try:
        print(f"🔄 尝试使用 requests 下载...")
        
        headers = {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
        }
        
        response = requests.get(url, stream=True, timeout=timeout, headers=headers)
        response.raise_for_status()
        
        total_size = int(response.headers.get('content-length', 0))
        downloaded = 0
        chunk_size = 8192
        
        with open(output_path, 'wb') as f:
            for chunk in response.iter_content(chunk_size=chunk_size):
                if chunk:
                    f.write(chunk)
                    downloaded += len(chunk)
                    
                    if total_size > 0:
                        percent = min((downloaded / total_size) * 100, 100)
                        mb_downloaded = downloaded / (1024 * 1024)
                        mb_total = total_size / (1024 * 1024)
                        print(f"\r   下载中: {percent:.1f}% ({mb_downloaded:.2f}/{mb_total:.2f} MB)", end="")
        
        print()
        return True
        
    except Exception as e:
        print(f"\n   requests 下载失败: {e}")
        if output_path.exists():
            output_path.unlink()
        return False

def download_with_modelscope(model_id, file_name, output_path):
    """使用 ModelScope 下载文件"""
    try:
        print(f"🔄 尝试使用 ModelScope 下载...")
        print(f"   模型ID: {model_id}")
        
        model_dir = snapshot_download(model_id)
        print(f"   下载目录: {model_dir}")
        
        import shutil
        source_path = Path(model_dir) / file_name
        if source_path.exists():
            shutil.copy(source_path, output_path)
            return True
        else:
            print(f"   在模型目录中未找到文件: {file_name}")
            return False
            
    except Exception as e:
        print(f"\n   ModelScope 下载失败: {e}")
        return False

def download_model(model_info):
    """下载单个模型"""
    model_path = MODELS_DIR / model_info["name"]
    
    if model_path.exists():
        size_mb = model_path.stat().st_size / (1024 * 1024)
        print(f"⏭️ 已存在: {model_info['name']} ({size_mb:.2f} MB)")
        print(f"   描述: {model_info['description']}")
        return True
    
    print(f"\n📦 准备下载: {model_info['name']}")
    print(f"   描述: {model_info['description']}")
    print(f"   预计大小: {model_info['size_mb']} MB")
    
    for i, source in enumerate(model_info["sources"]):
        print(f"\n🔄 尝试来源 {i+1}/{len(model_info['sources'])}...")
        
        if source["type"] == "url":
            if HAS_REQUESTS:
                success = download_with_requests(source["url"], model_path)
            else:
                print("   ⚠️ 跳过 (requests 库未安装)")
                success = False
        elif source["type"] == "modelscope":
            if HAS_MODELSCOPE:
                success = download_with_modelscope(source["model_id"], source["file_name"], model_path)
            else:
                print("   ⚠️ 跳过 (modelscope 库未安装)")
                success = False
        else:
            success = False
        
        if success and model_path.exists():
            size_mb = model_path.stat().st_size / (1024 * 1024)
            print(f"✅ 下载完成: {model_info['name']} ({size_mb:.2f} MB)")
            return True
    
    print(f"❌ 所有来源都下载失败: {model_info['name']}")
    return False

def main():
    print("=" * 60)
    print("🤖 TensorFlow Lite 目标检测模型下载工具")
    print("=" * 60)
    print(f"\n📂 模型保存目录: {MODELS_DIR}")
    
    print()
    print("📋 可用的下载方式:")
    if HAS_REQUESTS:
        print("   ✅ requests 库已安装 (支持直接下载)")
    else:
        print("   ⚠️ requests 库未安装")
    if HAS_MODELSCOPE:
        print("   ✅ ModelScope 已安装 (国内镜像)")
    else:
        print("   ⚠️ ModelScope 未安装")
    print()
    
    success_count = 0
    total_count = len(MODELS)
    
    for model in MODELS:
        if download_model(model):
            success_count += 1
    
    print()
    print("=" * 60)
    print(f"✅ 下载完成: {success_count}/{total_count} 个模型")
    print("=" * 60)
    
    if success_count > 0:
        print()
        print("📋 可用的目标检测模型:")
        print()
        for model in MODELS:
            model_path = MODELS_DIR / model["name"]
            if model_path.exists():
                print(f"  📦 {model['name']}")
                print(f"     描述: {model['description']}")
                print(f"     输入尺寸: {model['input_size']}")
                print(f"     类别数: {model['num_classes']}")
                print(f"     模型格式: {model['model_format']}")
                print()

if __name__ == "__main__":
    main()

