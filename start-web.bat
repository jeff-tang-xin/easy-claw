@echo off
chcp 65001 >nul
rem ============================================================
rem Easy-Claw web 服务启动脚本（带内存约束）
rem ------------------------------------------------------------
rem 目标：限制堆上限 + 空闲时把内存归还操作系统，避免 RSS 居高不下
rem   -Xmx768m            堆上限（如需更大改此值，建议 512m~1g）
rem   MinHeapFreeRatio=20 / MaxHeapFreeRatio=40
rem                       GC 后堆使用率低于阈值即收缩，把内存还给 OS
rem   G1PeriodicGCInterval=300000
rem                       应用空闲时每 5 分钟也触发一次 GC 机会（配合上行比例归还）
rem   ExitOnOutOfMemoryError  OOM 时快速退出（比僵死更好诊断）
rem ============================================================
java -Xms256m -Xmx768m ^
 -XX:+UseG1GC ^
 -XX:MinHeapFreeRatio=20 -XX:MaxHeapFreeRatio=40 ^
 -XX:G1PeriodicGCInterval=300000 ^
 -XX:+ExitOnOutOfMemoryError ^
 -Dfile.encoding=UTF-8 ^
 -jar web\api\target\easy-claw.jar %*
