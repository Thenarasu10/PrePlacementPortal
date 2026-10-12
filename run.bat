@echo off
REM PlaceIQ - build and run on Windows. Needs a JDK 11+ on PATH (javac + java).
setlocal
cd /d "%~dp0"
echo Compiling Java backend...
if not exist bin mkdir bin
dir /s /b backend\src\*.java > "%TEMP%\placeiq-sources.txt"
javac -cp "lib\*" -d bin @"%TEMP%\placeiq-sources.txt"
if errorlevel 1 (
  echo.
  echo Compile failed. Is a JDK 11+ installed and on PATH? Check with: javac -version
  exit /b 1
)
echo Starting PlaceIQ on http://localhost:8080
java -cp "bin;lib\*" placeiq.Main %*
