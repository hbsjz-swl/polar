@echo off
chcp 65001 >nul 2>&1
REM DLC Installer for Windows
REM 蒂爱嘉(北京)有限公司
REM
REM Usage: git clone https://github.com/hbsjz-swl/dlc-cli.git && cd dlc-cli && install.bat

setlocal

set DLC_HOME=%USERPROFILE%\.dlc

echo.
echo   Installing DLC - Local AI Coding Agent ...
echo   Install dir: %DLC_HOME%
echo.

if not exist "%DLC_HOME%\bin" mkdir "%DLC_HOME%\bin"

REM Copy jar and launcher from local clone
if exist "dlc.jar" (
    echo   Copying files from local directory...
    copy /Y "dlc.jar" "%DLC_HOME%\dlc.jar" >nul
) else (
    echo   Error: dlc.jar not found in current directory.
    echo   Please run this from the cloned repository.
    pause
    exit /b 1
)

REM Copy launcher script from bin/
if exist "bin\dlc.cmd" (
    copy /Y "bin\dlc.cmd" "%DLC_HOME%\bin\dlc.cmd" >nul
) else (
    echo   Error: bin\dlc.cmd not found.
    pause
    exit /b 1
)

echo   Created launcher: %DLC_HOME%\bin\dlc.cmd

REM Add to user PATH
echo %PATH% | findstr /i ".dlc\bin" >nul 2>&1
if errorlevel 1 (
    echo   Adding to PATH...
    powershell -Command "[Environment]::SetEnvironmentVariable('PATH', [Environment]::GetEnvironmentVariable('PATH', 'User') + ';%DLC_HOME%\bin', 'User')"
    echo   Added %DLC_HOME%\bin to user PATH
)

echo.
echo   DLC installed successfully!
echo.
echo   Close and reopen your terminal, then run:
echo     cd \your\project
echo     dlc
echo.
echo   Update to latest version:
echo     dlc upgrade
echo.
pause
