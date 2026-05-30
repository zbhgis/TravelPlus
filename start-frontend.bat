@echo off
rem TravelPlus frontend silent start (static http server, port 19608)
set "APP=%~dp0"
set "APP=%APP:~0,-1%"
if not exist "%APP%\logs" mkdir "%APP%\logs"
powershell -NoProfile -WindowStyle Hidden -Command "Start-Process -FilePath 'python' -ArgumentList '-m','http.server','19608' -WorkingDirectory '%APP%' -RedirectStandardOutput '%APP%\logs\frontend.log' -RedirectStandardError '%APP%\logs\frontend-err.log' -WindowStyle Hidden"
echo TravelPlus frontend starting on http://localhost:19608 ...
echo Log: %APP%\logs\frontend.log
