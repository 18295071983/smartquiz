Write-Host "========================================================" -ForegroundColor Green
Write-Host "  Android APK 构建脚本" -ForegroundColor Green
Write-Host "========================================================" -ForegroundColor Green
Write-Host

# 检查 Java 环境
try {
    java -version | Out-Null
    Write-Host "[INFO] Java 环境检查通过" -ForegroundColor Cyan
} catch {
    Write-Host "[ERROR] Java 未安装，请先安装 JDK 17+" -ForegroundColor Red
    Read-Host "按 Enter 键退出..."
    exit 1
}

# 检查 Gradle 包装器
if (-not (Test-Path "gradlew.bat")) {
    Write-Host "[ERROR] gradlew.bat 不存在，请确保在项目根目录运行" -ForegroundColor Red
    Read-Host "按 Enter 键退出..."
    exit 1
}

# 清理项目
Write-Host "[步骤 1/3] 清理项目..." -ForegroundColor Yellow
Write-Host
& .\gradlew.bat clean
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] 清理项目失败" -ForegroundColor Red
    Read-Host "按 Enter 键退出..."
    exit 1
}
Write-Host "[SUCCESS] 清理完成" -ForegroundColor Green

# 构建发布版本
Write-Host "[步骤 2/3] 构建发布版本 APK..." -ForegroundColor Yellow
Write-Host
& .\gradlew.bat assembleRelease
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] 构建失败" -ForegroundColor Red
    Read-Host "按 Enter 键退出..."
    exit 1
}
Write-Host "[SUCCESS] 构建完成" -ForegroundColor Green

# 查找生成的 APK 文件
Write-Host "[步骤 3/3] 查找 APK 文件..." -ForegroundColor Yellow
Write-Host
$apkPath = Get-ChildItem -Path "$PWD\build\outputs\apk\release" -Filter "*.apk" -Recurse | Select-Object -First 1

if ($apkPath) {
    $apkSize = (Get-Item $apkPath.FullName).Length / 1MB
    Write-Host "[SUCCESS] APK 构建成功！" -ForegroundColor Green
    Write-Host "[INFO] APK 路径: $($apkPath.FullName)" -ForegroundColor Cyan
    Write-Host "[INFO] APK 大小: $($apkSize.ToString('0.00')) MB" -ForegroundColor Cyan
} else {
    Write-Host "[ERROR] 未找到生成的 APK 文件" -ForegroundColor Red
    Read-Host "按 Enter 键退出..."
    exit 1
}

Write-Host
Write-Host "========================================================" -ForegroundColor Green
Write-Host "  构建完成！" -ForegroundColor Green
Write-Host "========================================================" -ForegroundColor Green
Write-Host
Write-Host "使用说明:" -ForegroundColor Cyan
Write-Host "  1. 生成的 APK 位于: $($apkPath.FullName)" -ForegroundColor Cyan
Write-Host "  2. 可以使用 adb 安装: adb install $($apkPath.FullName)" -ForegroundColor Cyan
Write-Host "  3. 或直接将 APK 文件复制到设备安装" -ForegroundColor Cyan
Write-Host
Read-Host "按 Enter 键退出..."