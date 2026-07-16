#!/bin/bash
set -e

BUILD_DIR=/d/quzp/app/src/main/cpp/build/arm64-v8a
NINJA=/mingw64/bin/ninja

cd $BUILD_DIR

echo "=== Starting ninja incremental build (ARM64) ==="
$NINJA -j4 2>&1 | tee /tmp/arm64_rebuild2.log
EXIT_CODE=${PIPESTATUS[0]}

echo "=== NINJA EXIT CODE: $EXIT_CODE ===" | tee -a /tmp/arm64_rebuild2.log

if [ $EXIT_CODE -eq 0 ]; then
    echo "=== Build SUCCESS ==="
    cp $BUILD_DIR/libllama-jni.so /d/quzp/app/src/main/jniLibs/arm64-v8a/
    ls -la /d/quzp/app/src/main/jniLibs/arm64-v8a/libllama-jni.so
else
    echo "=== Build FAILED ==="
    tail -50 /tmp/arm64_rebuild2.log
fi
