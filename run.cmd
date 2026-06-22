@echo off
setlocal

set ROOT=%~dp0
set JAR=%ROOT%aft-server\target\aft-server-0.1.0-SNAPSHOT-exec.jar
set JAVA=D:\labway\jdk\jdk-17.0.5\bin\java.exe

if not exist "%JAR%" (
    echo JAR not found. Please run build.cmd first.
    exit /b 1
)

echo Starting AFT Studio at http://127.0.0.1:51780
"%JAVA%" -jar "%JAR%"
