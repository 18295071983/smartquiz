#!/bin/bash

BUILD_DIR=/d/quzp/app/src/main/cpp/build/x86_64
NINJA=/mingw64/bin/ninja

cd $BUILD_DIR

echo "=== Resuming ninja build with -j4 ==="
$NINJA -j4 2>&1 | tee /tmp/x86_build2.log
EXIT_CODE=${PIPESTATUS[0]}

echo "=== NINJA EXIT CODE: $EXIT_CODE ===" | tee -a /tmp/x86_build2.log

if [ $EXIT_CODE -eq 0 ]; then
    echo "=== Build SUCCESS ==="
    echo "=== Copying to jniLibs ==="
    cp $BUILD_DIR/libllama-jni.so /d/quzp/app/src/main/jniLibs/x86_64/
    ls -la /d/quzp/app/src/main/jniLibs/x86_64/libllama-jni.so
else
    echo "=== Build FAILED ==="
    echo "=== Last 80 lines of log ==="
    tail -80 /tmp/x86_build2.log
fi
