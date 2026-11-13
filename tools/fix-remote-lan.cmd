@echo off
rem fix-remote-lan.cmd — 双击运行：自动修好"手机连不上电脑"的两个最常见设置
rem 会弹 UAC 提权，点"是"即可；跑完窗口会停住，按任意键关闭。
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "Start-Process powershell -Verb RunAs -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-NoExit','-File','\"%~dp0fix-remote-lan.ps1\"'"
