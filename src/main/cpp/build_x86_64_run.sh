#!/bin/bash
set -e

BUILD_DIR=/d/quzp/app/src/main/cpp/build/x86_64
CMAKE=/mingw64/bin/cmake
NINJA=/mingw64/bin/ninja

cd $BUILD_DIR

echo "=== Starting ninja build ==="
$NINJA -j8 2>&1 | tee /tmp/x86_build.log
EXIT_CODE=${PIPESTATUS[0]}

echo "=== NINJA EXIT CODE: $EXIT_CODE ===" | tee -a /tmp/x86_build.log

if [ $EXIT_CODE -eq 0 ]; then
    echo "=== Build SUCCESS ==="
    echo "=== Copying to jniLibs ==="
    cp $BUILD_DIR/libllama-jni.so /d/quzp/app/src/main/jniLibs/x86_64/
    ls -la /d/quzp/app/src/main/jniLibs/x86_64/libllama-jni.so
else
    echo "=== Build FAILED ==="
    echo "=== Last 50 lines of log ==="
    tail -50 /tmp/x86_build.log
fi
