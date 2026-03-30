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
set GIT_REPO_URL=https://github.com/hbsjz-swl/polar.git
set RAW_BASE_URL=https://raw.githubusercontent.com/hbsjz-swl/polar/main
set TEMP_DIR=%TEMP%\dlc-upgrade-%RANDOM%

REM 方式1: git clone
where git >nul 2>&1
if %errorlevel%==0 (
    echo   Downloading via git...
    git clone --depth 1 "%GIT_REPO_URL%" "%TEMP_DIR%" -q
    if exist "%TEMP_DIR%\dlc.jar" goto :install
    echo   Git clone failed, trying curl...
    if exist "%TEMP_DIR%" rmdir /s /q "%TEMP_DIR%"
)

REM 方式2: curl 直接下载 jar
where curl >nul 2>&1
if %errorlevel%==0 (
    mkdir "%TEMP_DIR%" 2>nul
    echo   Downloading via curl...
    curl -fsSL -o "%TEMP_DIR%\dlc.jar" "%RAW_BASE_URL%/dlc.jar"
    if exist "%TEMP_DIR%\dlc.jar" (
        curl -fsSL -o "%TEMP_DIR%\dlc.cmd" "%RAW_BASE_URL%/bin/dlc.cmd" 2>nul
        goto :install
    )
    echo   Curl download failed.
)

REM 方式3: PowerShell
echo   Downloading via PowerShell...
mkdir "%TEMP_DIR%" 2>nul
powershell -Command "try { Invoke-WebRequest -Uri '%RAW_BASE_URL%/dlc.jar' -OutFile '%TEMP_DIR%\dlc.jar' -UseBasicParsing } catch { exit 1 }"
if exist "%TEMP_DIR%\dlc.jar" (
    powershell -Command "try { Invoke-WebRequest -Uri '%RAW_BASE_URL%/bin/dlc.cmd' -OutFile '%TEMP_DIR%\dlc.cmd' -UseBasicParsing } catch {}" 2>nul
    goto :install
)

echo   Error: All download methods failed.
echo   Please check network connection to %GIT_REPO_URL%
if exist "%TEMP_DIR%" rmdir /s /q "%TEMP_DIR%"
exit /b 1

:install
copy /Y "%TEMP_DIR%\dlc.jar" "%DLC_JAR%" >nul
if exist "%TEMP_DIR%\dlc.cmd" copy /Y "%TEMP_DIR%\dlc.cmd" "%DLC_HOME%\bin\dlc.cmd" >nul
if exist "%TEMP_DIR%" rmdir /s /q "%TEMP_DIR%"
for %%A in ("%DLC_JAR%") do echo   Done. %%~zA bytes
exit /b 0
