#!/bin/bash
set -e

SRC_DIR=/d/quzp/app/src/main/cpp
BUILD_DIR=$SRC_DIR/build/arm64-v8a
CMAKE=/mingw64/bin/cmake
NINJA=/mingw64/bin/ninja

cd $BUILD_DIR

echo "=== Starting ninja build (ARM64) ==="
$NINJA -j4 2>&1 | tee /tmp/arm64_rebuild.log
EXIT_CODE=${PIPESTATUS[0]}

echo "=== NINJA EXIT CODE: $EXIT_CODE ===" | tee -a /tmp/arm64_rebuild.log

if [ $EXIT_CODE -eq 0 ]; then
    echo "=== Build SUCCESS ==="
    echo "=== Copying to jniLibs ==="
    cp $BUILD_DIR/libllama-jni.so /d/quzp/app/src/main/jniLibs/arm64-v8a/
    ls -la /d/quzp/app/src/main/jniLibs/arm64-v8a/libllama-jni.so
else
    echo "=== Build FAILED ==="
    tail -50 /tmp/arm64_rebuild.log
fi
