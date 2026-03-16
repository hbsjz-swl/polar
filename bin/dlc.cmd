@echo off
chcp 65001 >nul 2>&1
REM DLC - Local AI Coding Agent

if "%DLC_HOME%"=="" set DLC_HOME=%USERPROFILE%\.dlc
set DLC_JAR=%DLC_HOME%\dlc.jar

if /i "%~1"=="upgrade" goto :upgrade

if not exist "%DLC_JAR%" (
    echo Error: dlc.jar not found at %DLC_JAR%
    echo Run the install script first.
    exit /b 1
)

if "%DLC_WORKSPACE%"=="" set DLC_WORKSPACE=%cd%
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "%DLC_JAR%" %*
exit /b 0

:upgrade
echo   Upgrading DLC...
set TEMP_DIR=%TEMP%\dlc-upgrade-%RANDOM%
git clone --depth 1 "https://git.dlchm.cn/sunweilin/coding-agent.git" "%TEMP_DIR%" -q 2>nul
if not exist "%TEMP_DIR%\dlc.jar" (
    echo   Error: Failed to download.
    if exist "%TEMP_DIR%" rmdir /s /q "%TEMP_DIR%"
    exit /b 1
)
copy /Y "%TEMP_DIR%\dlc.jar" "%DLC_JAR%" >nul
if exist "%TEMP_DIR%\bin\dlc.cmd" copy /Y "%TEMP_DIR%\bin\dlc.cmd" "%DLC_HOME%\bin\dlc.cmd" >nul
if exist "%TEMP_DIR%" rmdir /s /q "%TEMP_DIR%"
echo   Done.
exit /b 0
