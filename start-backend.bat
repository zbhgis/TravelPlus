@echo off
rem TravelPlus backend silent start (Spring Boot, port 8081)
set "APP=E:\AAAproject\travelplus"
if not exist "%APP%\logs" mkdir "%APP%\logs"
powershell -NoProfile -WindowStyle Hidden -Command "Start-Process -FilePath 'C:\Users\lenovo2026\tools\apache-maven-3.9.11\bin\mvn.cmd' -ArgumentList 'spring-boot:run','-q' -WorkingDirectory '%APP%' -RedirectStandardOutput '%APP%\logs\backend.log' -RedirectStandardError '%APP%\logs\backend-err.log' -WindowStyle Hidden"
echo TravelPlus backend starting on http://localhost:8081 ...
echo Log: %APP%\logs\backend.log
