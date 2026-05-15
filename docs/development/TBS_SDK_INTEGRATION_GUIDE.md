# TBS SDK 集成指南

## 概述
腾讯浏览服务（TBS）SDK 提供强大的文档预览功能，支持多种格式：
- Word: doc, docx, rtf
- Excel: xls, xlsx, xlsm, csv
- PowerPoint: ppt, pptx
- PDF: pdf
- 文本: txt, epub, chm

## 集成步骤

### 步骤1：下载 TBS SDK AAR 文件

1. 访问腾讯云官网：https://cloud.tencent.com/document/product/1645/83900
2. 下载 `TbsFileSdk_xxxx.aar` 文件
3. 将 AAR 文件复制到 `app/libs/` 目录下

### 步骤2：申请 LicenseKey

1. 访问腾讯云控制台：https://console.cloud.tencent.com/
2. 注册/登录腾讯云账号
3. 进入"腾讯浏览服务"产品页面
4. 创建应用，获取 LicenseKey
5. 将 LicenseKey 保存好，后续需要配置到代码中

### 步骤3：配置 AndroidManifest.xml

在 `AndroidManifest.xml` 中添加以下权限：

```xml
<!-- 网络权限 - 必选 -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- 存储权限 - 可选（如果文档存储在App私有目录则不需要） -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />

<!-- 剪切板权限 - 可选（用于复制粘贴功能） -->
<uses-permission android:name="android.permission.READ_CLIPBOARD" />
```

### 步骤4：配置混淆规则

在 `proguard-rules.pro` 中添加：

```proguard
-dontwarn com.tencent.tbs.reader.**
-keep class com.tencent.tbs.reader.** { *; }
```

### 步骤5：初始化 TBS SDK

在 `MainActivity` 或 `Application` 的 `onCreate` 中初始化：

```java
// 设置 LicenseKey（需要替换为您的实际 LicenseKey）
TbsFileInterfaceImpl.setLicenseKey("YOUR_LICENSE_KEY_HERE");

// 同步初始化
int ret = TbsFileInterfaceImpl.initEngine(context);
if (ret == 0) {
    Log.d("TBS", "TBS SDK 初始化成功");
} else {
    Log.e("TBS", "TBS SDK 初始化失败，错误码: " + ret);
}
```

### 步骤6：使用 TBS 预览文件

```java
// 检查文件格式是否支持
String fileExt = "pdf"; // 文件扩展名
if (TbsFileInterfaceImpl.canOpenFileExt(fileExt)) {
    // 准备参数
    Bundle param = new Bundle();
    param.putString("filePath", filePath);  // 本地文件路径
    param.putString("fileExt", fileExt);    // 文件扩展名
    param.putString("tempPath", getExternalFilesDir("temp").getAbsolutePath());
    
    // 可选参数
    param.putBoolean("file_reader_enable_long_press_menu", true); // 开启长按复制
    param.putBoolean("file_reader_goto_last_pos", true); // 记住上次阅读位置
    
    // 打开文件
    ITbsReaderCallback callback = new ITbsReaderCallback() {
        @Override
        public void onCallBackAction(Integer actionType, Object args, Object result) {
            // 处理回调事件
        }
    };
    
    int ret = TbsFileInterfaceImpl.getInstance().openFileReader(context, param, callback, null);
}
```

## 注意事项

1. **隐私政策**：在初始化 SDK 前，确保用户已同意隐私政策
2. **网络连接**：首次使用需要网络连接进行授权验证
3. **文件路径**：只支持本地文件路径，网络文件需要先下载
4. **临时目录**：建议指定在 App 私有目录下

## 常见问题

### Q: TBS SDK 是否收费？
A: 文档预览功能免费，但需要申请 LicenseKey。

### Q: 支持哪些文件格式？
A: doc, docx, xls, xlsx, ppt, pptx, pdf, txt, epub, chm 等。

### Q: 如何获取 LicenseKey？
A: 访问腾讯云控制台，创建应用后即可获取。

### Q: 初始化失败怎么办？
A: 检查以下几点：
- LicenseKey 是否正确
- 网络连接是否正常
- 是否同意了隐私政策
- AAR 文件是否正确导入

## 参考文档

- 官方文档：https://cloud.tencent.com/document/product/1645/83900
- SDK 下载：https://cloud.tencent.com/document/product/1645/83900
