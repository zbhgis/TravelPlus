@echo off
rem TravelPlus backend silent stop (kill process listening on port 8081)
set "FOUND="
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8081 " ^| findstr "LISTENING"') do (
    set "FOUND=1"
    taskkill /F /T /PID %%a >nul 2>&1
    echo TravelPlus backend process PID %%a on port 8081 stopped.
)
if not defined FOUND echo No TravelPlus backend process listening on port 8081. Already stopped.
