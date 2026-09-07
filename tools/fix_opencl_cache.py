# -*- coding: utf-8 -*-
# 补丁：在 setupGGMLBackendPath() 中设置 GGML_OPENCL_KERNEL_CACHE_DIR，
# 启用 OpenCL kernel 编译缓存，避免每次启动重新编译全部 FA kernels（~9s）。
import io, sys

path = r"D:\qzq\smartquiz\src\main\cpp\native-lib.cpp"
with io.open(path, "r", encoding="utf-8") as f:
    src = f.read()

anchor = '''        setenv("GGML_BACKEND_PATH", libDir.c_str(), 1);
'''
inject = '''        setenv("GGML_BACKEND_PATH", libDir.c_str(), 1);

        // ===== OpenCL kernel 编译缓存 =====
        // llama.cpp ggml-opencl 在 Android 上 default_cache_dir() 依赖 TMPDIR，
        // TMPDIR 未设置时缓存被禁用 -> 每次启动都重新编译全部 FA kernels（实测 ~9s）。
        // 显式指定 App 私有可写目录：首次编译后缓存 <sha256>.clbin（key 含设备/驱动/
        // 编译参数，驱动或参数变化自动失效重建），后续启动直接 HIT 加载，显著缩短
        // GPU 初始化耗时。
        const char* kOpenclCacheDir = "/data/user/0/com.oilquiz.app/cache/llama-cl";
        setenv("GGML_OPENCL_KERNEL_CACHE_DIR", kOpenclCacheDir, 1);
        LOGI("OpenCL kernel cache dir set to: %s", kOpenclCacheDir);
'''

count = src.count(anchor)
print("anchor count:", count)
if count != 1:
    print("ABORT: anchor not unique/found")
    sys.exit(1)

src = src.replace(anchor, inject, 1)
with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("PATCH OK")
