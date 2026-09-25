@echo off
setlocal enabledelayedexpansion

echo ==============================================
echo Building AltsMod for all Minecraft versions...
echo ==============================================

if not exist "dist" mkdir dist

echo.
echo [1/5] Building 1.16.5...
cd v1_16_5
call gradlew.bat build
if %errorlevel% neq 0 (
    echo Error building 1.16.5
    exit /b %errorlevel%
)
copy /y build\libs\AltsMod-1.16.5-1.0.0.jar ..\dist\AltsMod-1.16.5-1.0.0.jar
copy /y build\libs\AltsMod-1.16.5-1.0.0.jar ..\dist\AltsMod-1.16.5.jar
cd ..

echo.
echo [2/5] Building 1.21.4...
cd v1_21_4
call gradlew.bat build
if %errorlevel% neq 0 (
    echo Error building 1.21.4
    exit /b %errorlevel%
)
copy /y build\libs\AltsMod-1.21.4-1.0.0.jar ..\dist\AltsMod-1.21.4-1.0.0.jar
copy /y build\libs\AltsMod-1.21.4-1.0.0.jar ..\dist\AltsMod-1.21.4.jar
cd ..

echo.
echo [3/5] Building 1.21.8...
cd v1_21_8
call gradlew.bat build
if %errorlevel% neq 0 (
    echo Error building 1.21.8
    exit /b %errorlevel%
)
copy /y build\libs\AltsMod-1.21.8-1.0.0.jar ..\dist\AltsMod-1.21.8-1.0.0.jar
copy /y build\libs\AltsMod-1.21.8-1.0.0.jar ..\dist\AltsMod-1.21.8.jar
cd ..

echo.
echo [4/5] Building 1.21.11...
cd v1_21_11
call gradlew.bat build
if %errorlevel% neq 0 (
    echo Error building 1.21.11
    exit /b %errorlevel%
)
copy /y build\libs\AltsMod-1.21.11-1.0.0.jar ..\dist\AltsMod-1.21.11-1.0.0.jar
copy /y build\libs\AltsMod-1.21.11-1.0.0.jar ..\dist\AltsMod-1.21.11.jar
cd ..

echo.
echo [5/5] Building 26.2...
cd v26_2
call gradlew.bat build
if %errorlevel% neq 0 (
    echo Error building 26.2
    exit /b %errorlevel%
)
copy /y build\libs\AltsMod-26.2-1.0.0.jar ..\dist\AltsMod-26.2-1.0.0.jar
copy /y build\libs\AltsMod-26.2-1.0.0.jar ..\dist\AltsMod-26.2.jar
cd ..

echo.
echo ==============================================
echo ALL BUILDS SUCCESSFUL!
echo Jars located in: dist\
echo ==============================================
dir dist\*.jar
pause
