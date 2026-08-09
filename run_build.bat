@echo off
cd /d d:\qzq\smartquiz
call gradlew.bat assembleDebug > d:\qzq\smartquiz\build_stdout.log 2>&1
echo EXIT_CODE=%ERRORLEVEL% >> d:\qzq\smartquiz\build_stdout.log
