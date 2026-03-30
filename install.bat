@echo off
chcp 65001 >nul 2>&1
REM Polar Installer for Windows
REM 蒂爱嘉(北京)有限公司
REM
REM Usage: git clone https://github.com/hbsjz-swl/dlc-cli.git && cd dlc-cli && install.bat

setlocal

set APP_HOME=%USERPROFILE%\.dlc

echo.
echo   Installing Polar - Local AI Coding Agent ...
echo   Install dir: %APP_HOME%
echo.

if not exist "%APP_HOME%\bin" mkdir "%APP_HOME%\bin"

REM Copy jar and launcher from local clone
if exist "dlc.jar" (
    echo   Copying files from local directory...
    copy /Y "dlc.jar" "%APP_HOME%\dlc.jar" >nul
) else (
    echo   Error: dlc.jar not found in current directory.
    echo   Please run this from the cloned repository.
    pause
    exit /b 1
)

REM Copy launcher scripts from bin/
if exist "bin\polar.cmd" (
    copy /Y "bin\polar.cmd" "%APP_HOME%\bin\polar.cmd" >nul
) else (
    echo   Error: bin\polar.cmd not found.
    pause
    exit /b 1
)
if exist "bin\dlc.cmd" copy /Y "bin\dlc.cmd" "%APP_HOME%\bin\dlc.cmd" >nul

echo   Created launcher: %APP_HOME%\bin\polar.cmd

REM Add to user PATH
echo %PATH% | findstr /i ".dlc\bin" >nul 2>&1
if errorlevel 1 (
    echo   Adding to PATH...
    powershell -Command "[Environment]::SetEnvironmentVariable('PATH', [Environment]::GetEnvironmentVariable('PATH', 'User') + ';%APP_HOME%\bin', 'User')"
    echo   Added %APP_HOME%\bin to user PATH
)

echo.
echo   Polar installed successfully!
echo.
echo   Close and reopen your terminal, then run:
echo     cd \your\project
echo     polar
echo.
echo   Update to latest version:
echo     polar upgrade
echo.
pause
