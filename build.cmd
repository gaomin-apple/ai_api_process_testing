@echo off
setlocal

set ROOT=%~dp0
set JAVA_HOME=D:\labway\jdk\jdk-17.0.5

echo [1/3] Installing frontend dependencies...
cd /d "%ROOT%aft-web"
call npm install
if errorlevel 1 exit /b 1

echo [2/3] Building frontend...
call npm run build
if errorlevel 1 exit /b 1

echo [3/3] Building backend...
cd /d "%ROOT%"
call mvnw.cmd -Dmaven.repo.local="%ROOT%.m2-repository" clean package -DskipTests
if errorlevel 1 exit /b 1

echo.
echo Build complete: aft-server\target\aft-server-0.1.0-SNAPSHOT-exec.jar
