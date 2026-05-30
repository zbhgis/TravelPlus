@echo off
rem TravelPlus frontend silent stop (kill process listening on port 19608)
set "FOUND="
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":19608 " ^| findstr "LISTENING"') do (
    set "FOUND=1"
    taskkill /F /T /PID %%a >nul 2>&1
    echo TravelPlus frontend process PID %%a on port 19608 stopped.
)
if not defined FOUND echo No TravelPlus frontend process listening on port 19608. Already stopped.
