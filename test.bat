@echo off
REM PlaceIQ - run the backend test suite on Windows. Needs a JDK 11+ on PATH.
setlocal
cd /d "%~dp0"
if not exist bin mkdir bin
dir /s /b backend\src\*.java > "%TEMP%\placeiq-sources.txt"
javac -cp "lib\*" -d bin @"%TEMP%\placeiq-sources.txt"
if errorlevel 1 exit /b 1
java -cp "bin;lib\*" placeiq.Tests
