# 使用 Ubuntu 22.04 作为基础镜像
FROM ubuntu:22.04

# 设置环境变量
ENV DEBIAN_FRONTEND=noninteractive
ENV PATH="/root/.local/bin:/usr/local/bin:$PATH"

# 安装依赖
RUN apt-get update && apt-get install -y \
    build-essential \
    cmake \
    ninja-build \
    git \
    clang \
    libopenmp-dev \
    libvulkan-dev \
    vulkan-tools \
    openjdk-17-jdk \
    && rm -rf /var/lib/apt/lists/*

# 设置工作目录
WORKDIR /app

# 复制项目文件
COPY . /app

# 创建构建目录
RUN mkdir -p src/main/cpp/build-docker

# 配置并构建
WORKDIR /app/src/main/cpp/build-docker
RUN cmake -G "Ninja" \
    -DCMAKE_C_COMPILER=clang \
    -DCMAKE_CXX_COMPILER=clang++ \
    -DGGML_VULKAN=OFF \
    -DGGML_OPENCL=OFF \
    -DGGML_CUDA=OFF \
    ..

# 构建
RUN ninja -j$(nproc)

# 创建输出目录并复制结果
RUN mkdir -p /app/output
RUN cp *.so /app/output 2>/dev/null || true
RUN cp *.dll /app/output 2>/dev/null || true
RUN cp build-llama/*.so /app/output 2>/dev/null || true
RUN cp build-llama/*.dll /app/output 2>/dev/null || true

# 设置默认命令
CMD ["bash"]
