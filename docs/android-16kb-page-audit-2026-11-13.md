# Android 16 KB 页对齐审计（2026-11-13）

> 背景：Android 15 起，targetSdk ≥ 35 的应用在 **16 KB 页** 设备上要求所有 `.so` 的
> `PT_LOAD` 段 `p_align ≥ 0x4000`；不满足的库 `dlopen` 会失败（`has invalid ELF alignment`）。
> 本项目 `targetSdk = 36`、`ndkVersion = 28.2.13676358`、`abiFilters = arm64-v8a`。

## 1. 检查方法

两个脚本（可进 CI / 发版前跑）：

```powershell
python tools/check_elf_16k.py        # 扫源码树里的 .so，看 ELF LOAD 段对齐
python tools/check_apk_so_align.py   # 扫 APK 内 .so 的打包方式（STORE/DEFLATE）与偏移
```

判读：`LOAD_align >= 0x4000` 才兼容 16 KB；`0x1000` 为 4 KB 对齐（不兼容）。

## 2. 结果（2026-11-13，debug APK）

- 源码树：**OK 37 / BAD 34 / 跳过 5**（跳过的是非 ELF 的占位文件，如 `libcacert.pem.so`）
- APK 内 arm64 `.so` 共 **89 个**，其中 **30 个仍是 4 KB 对齐**
- 打包方式：**全部 DEFLATE**（`useLegacyPackaging = true`，安装时解压）
  → 打包偏移对齐不影响，问题只在 ELF 本身

### 2.1 兼容（示例）

`libllama-jni.so`、`libcrypto.so`、`libssl.so`、`libz.so`、`libc++_shared.so`、`libomp.so`、
busybox/curl/ssh 等工具链库 —— 均 `LOAD_align = 0x4000`（NDK 28 + `-Wl,-z,max-page-size=16384` 生效）。

### 2.2 不兼容（APK 内 30 个）

| 分组 | 库 | 来源 | 修复方式 |
|---|---|---|---|
| LibreOffice / NSS | `liblo-native-code`、`libnspr4`、`libnss3`、`libnssckbi`、`libnssdbm3`、`libnssutil3`、`libplc4`、`libplds4`、`libsmime3`、`libsoftokn3`、`libssl3`、`libfreebl3`、`libsqlite3` | `viewer/src/main/jniLibs`（预编译） | 用 NDK 28 + `-Wl,-z,max-page-size=16384` 重编 LibreOffice 侧；无源码则找 16 KB 版预编译 |
| PDFium | `libmodpdfium`、`libmodft2`、`libmodpng` | 预编译 | 升级到 16 KB 版 PDFium |
| ML Kit | `libmlkit_google_ocr_pipeline`、`libmlkitcommonpipeline`、`libimage_processing_util_jni` | 依赖 AAR | 升级 ML Kit（16.0.0 → 新版） |
| TensorFlow Lite | `libtensorflowlite_jni`（含 GPU 版 `libtensorflowlite_gpu_jni`） | 依赖 AAR 2.15.0 | 升级 TFLite ≥ 2.16（16 KB 支持） |
| ONNX Runtime | `libonnxruntime`、`libonnxruntime4j_jni` | 1.18.0 | 升级 ORT ≥ 1.19（官方 16 KB 支持） |
| OpenCV | `libopencv_java4` | rapidocr4j 传递 4.9.0 | 升级或改用 16 KB 版 OpenCV |
| RTMP | `librtmp-jni` | 预编译 | 重编或替换 |

### 2.3 免检 / 不随包

| 库 | 原因 |
|---|---|
| `libggml-htp-v7x/v8x.so`（5 个，arch=0xa4） | Hexagon DSP 库，由 Hexagon SDK 加载，不经 Android 动态链接器，不适用 16 KB 规则 |
| `src/main/cpp/opencl/stub/libOpenCL.so` | 编译期 stub，未打进 APK |
| `src/main/jniLibs/x86_64/*` | `abiFilters` 只有 arm64-v8a，不随包 |

## 3. 结论与建议

1. **我们自己编译的库全部达标**（`src/main/cpp` 的 CMake 已带 16 KB 链接参数，NDK 28）。
2. **风险集中在预编译第三方库**：LibreOffice/NSS、ML Kit、TFLite、ORT、OpenCV、PDFium、RTMP。
   在 16 KB 设备上，一旦加载这些库的页面（文档查看、OCR、离线模型）就会 `dlopen` 失败。
3. 优先修复顺序：
   - P0：LibreOffice/NSS（体量最大、最容易触发）→ 重编或换 16 KB 预编译
   - P1：TFLite / ONNX Runtime / OpenCV（OCR、语音）→ 升依赖版本
   - P2：ML Kit / PDFium / RTMP → 升依赖或替换
4. 发版门槛：`python tools/check_elf_16k.py` 必须 `BAD=0`（或用白名单排除 2.3 的免检项）。

## 4. 兼容模式（2026-11-13 追加）

按 [Android 16 行为变更](https://developer.android.com/about/versions/16/behavior-changes-all) 与
[16 KB 向后兼容选项（AOSP）](https://source.android.google.cn/docs/core/architecture/16kb-page-size/16kb-backcompat-option)：

- Android 16+ 设备检测到 4 KB LOAD 对齐的 `.so` 时，**会自动以 16 KB 兼容模式运行**，
  并在首次启动弹一次"应用不是 16 KB 兼容"的提示；
- 在 manifest 中声明 `android:pageSizeCompat="enabled"` 可**强制启用兼容模式并抑制该提示**
  （属性由 compileSdk 36 提供，取值 `enabled`/`disabled`，二进制 manifest 里是 `32`/`64`）；
- 该属性只影响**运行期**兼容模式，不改变 ELF 本身；若厂商安装器（如 MIUI/HyperOS 的
  "ELF 检查"）在安装阶段硬性拒绝，仍需 `adb install` 或移除 4 KB 库。

本项目已加：

```xml
<application android:pageSizeCompat="enabled" ... >
```

复核命令：

```powershell
aapt2 dump xmltree build\outputs\apk\debug\答题宝-debug-2.0.apk --file AndroidManifest.xml | Select-String pageSizeCompat
# A: ...:pageSizeCompat(0x010106ab)=32
```