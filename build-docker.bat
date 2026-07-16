@echo off
setlocal enabledelayedexpansion

echo === Building Native Library with Docker ===

echo Building Docker image...
docker build -t quiz-app-builder .

if %errorlevel% neq 0 (
    echo Docker build failed!
    pause
    exit /b 1
)

echo Running Docker container...
docker run --rm -v "%cd%/output:/app/output" quiz-app-builder bash -c "cp /app/src/main/cpp/build-docker/*.so /app/output 2>/dev/null; cp /app/src/main/cpp/build-docker/*.dll /app/output 2>/dev/null; ls -la /app/output"

if %errorlevel% neq 0 (
    echo Docker run failed!
    pause
    exit /b 1
)

echo Build completed successfully!
echo Output files:
dir "output"

pause
