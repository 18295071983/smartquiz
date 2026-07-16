# 测试图片资源

本目录包含 ML Kit 视觉功能测试所需的图像文件。

## 📸 需要的测试图像

### 1. **test_image.jpg** - 图像标签测试
- **用途**: 测试图像分类/标签识别功能
- **建议内容**: 包含明显物体的照片（如动物、食物、风景等）
- **推荐尺寸**: 640x480 或更高
- **格式**: JPEG
- **大小**: < 2MB
- **示例**:
  - 一只猫或狗的照片
  - 水果/蔬菜特写
  - 建筑物或自然景观
  - 交通工具（汽车、飞机等）

### 2. **test_objects.jpg** - 目标检测测试
- **用途**: 测试物体检测功能
- **建议内容**: 包含多个不同物体的场景
- **推荐尺寸**: 1280x720 或更高
- **格式**: JPEG
- **大小**: < 5MB
- **示例**:
  - 街道场景（车辆、行人、建筑）
  - 室内场景（家具、电器、人物）
  - 超市货架（多种商品）
  - 办公桌面（电脑、文件、杯子）

### 3. **test_text.png** - OCR文字识别测试
- **用途**: 测试OCR文字识别功能（支持中英文）
- **建议内容**: 包含清晰文字的图像
- **推荐尺寸**: 根据实际文字调整
- **格式**: PNG（保持清晰度）或JPEG
- **大小**: < 3MB
- **示例**:
  - 书页/文档的照片
  - 印刷体文字（中文：标题、段落；英文：文章、标签）
  - 手写文字（可选，用于测试手写识别）
  - 标志牌、菜单、名片
  - 代码截图

## 📥 获取测试图片的方法

### 方法一：使用公开数据集

#### ImageNet 示例图片
```python
# 使用 torchvision 下载示例
from torchvision import datasets
import urllib.request

# 下载一张猫的图片
url = "https://upload.wikimedia.org/wikipedia/commons/thumb/3/3a/Cat03.jpg/1200px-Cat03.jpg"
urllib.request.urlretrieve(url, "test_image.jpg")
```

#### COCO 数据集样本
```python
# 下载COCO目标检测示例
# 访问: https://cocodataset.org/#explore
# 选择包含多个物体的图片
```

### 方法二：自己拍摄
使用手机拍摄以下场景：
1. **标签测试**: 拍摄一个清晰的物体（如咖啡杯、植物、宠物）
2. **目标检测**: 拍摄一个复杂场景（如书桌、厨房台面）
3. **OCR测试**: 拍摄一段清晰的文字（如书本页面、产品包装）

### 方法三：在线下载
推荐来源：
- **Unsplash**: https://unsplash.com/ (免费高质量图片)
- **Pexels**: https://www.pexels.com/ (免费 stock photos)
- **Pixabay**: https://pixabay.com/ (免费图片)
- **Wikipedia Commons**: https://commons.wikimedia.org/

搜索关键词：
- "object detection test image"
- "multiple objects scene"
- "document text clear"
- "printed text sample"

## 🔧 图片要求

### 图像标签测试 (test_image.jpg)
✅ **最佳实践**:
- 光线充足
- 主体清晰对焦
- 背景简洁
- 物体占据画面主要部分

❌ **避免**:
- 模糊或失焦图像
- 过暗或过曝
- 复杂混乱的背景
- 过小的主体

### 目标检测测试 (test_objects.jpg)
✅ **最佳实践**:
- 包含3-10个不同物体
- 物体之间有一定间距
- 多样化的物体类别
- 合理的光照条件

❌ **避免**:
- 物体重叠过多
- 极端角度或透视
- 单调重复的场景

### OCR测试 (test_text.png)
✅ **最佳实践**:
- 文字清晰锐利
- 对比度高（深色文字/浅色背景）
- 正常视角拍摄
- 分辨率足够（建议 > 300 DPI等效）

❌ **避免**:
- 模糊或运动模糊
- 文字过小
- 反光或阴影遮挡
- 倾斜严重

## 🧪 测试用例参考

### 中文OCR测试文本示例：
```
人工智能（Artificial Intelligence，简称AI）是计算机科学的一个分支，
致力于创建能够执行通常需要人类智能任务的系统。这些任务包括学习、
推理、问题解决、感知和语言理解等。
```

### 英文OCR测试文本示例：
```
Machine Learning is a subset of artificial intelligence that provides systems 
the ability to automatically learn and improve from experience without being 
explicitly programmed.
```

### 混合语言测试：
```
Hello! 你好！
这是一个中英文混合测试。
This is a mixed language test.
机器学习 Machine Learning
深度学习 Deep Learning
```

## 📋 文件清单

必需文件：
- `test_image.jpg` - 用于图像标签识别测试
- `test_objects.jpg` - 用于目标检测测试  
- `test_text.png` - 用于OCR文字识别测试

可选文件：
- `test_face.jpg` - 人脸检测测试（如果启用了人脸检测）
- `test_barcode.jpg` - 条形码/二维码扫描测试
- `test_document.pdf` - 文档扫描测试

## ⚙️ 图片处理建议

如果需要批量处理图片：

```bash
# 使用ImageMagick调整尺寸
convert input.jpg -resize 640x480 -quality 85 output.jpg

# 批量转换格式
for f in *.png; do convert "$f" "${f%.png}.jpg"; done

# 压缩图片（减少APK大小）
jpegoptim --max=85 *.jpg
optipng -o7 *.png
```

## 🎯 测试验证

添加图片后，运行以下验证：

```java
// 在 GPUTestActivity 或单独的测试Activity中
Bitmap testImage = BitmapFactory.decodeStream(
    context.getAssets().open("images/test_image.jpg")
);

if (testImage != null) {
    Log.i("Test", "图片加载成功: " + testImage.getWidth() + "x" + testImage.getHeight());
    
    // 运行ML Kit测试
    MLKitVisionTester tester = new MLKitVisionTester(context);
    tester.runFullVisionTest().thenAccept(results -> {
        Log.i("Test", "视觉测试完成: " + results);
    });
} else {
    Log.e("Test", "图片加载失败！请检查文件路径");
}
```

## 📊 预期结果

成功配置后，你应该看到：

### 图像标签测试输出示例：
```
检测结果：
🏷️ cat (95.2%)
🏷️ pet (89.7%)
🏷️ animal (87.3%)
🏷️ mammal (82.1%)
```

### 目标检测测试输出示例：
```
检测结果：
🎯 person (92%, [123,45,256,400])
🎯 laptop (88%, [300,200,500,350])
🎯 cup (85%, [520,280,580,380])
🎯 book (79%, [150,320,280,450])
```

### OCR测试输出示例：
```
识别结果：
📝 "人工智能（Artificial Intelligence，简称AI）是计算机科学的一个分支..."
置信度: 96.8%
识别时间: 125ms
```

---
**最后更新**: 2026-05-10
