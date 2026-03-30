@echo off
set SCRIPT_DIR=%~dp0
call "%SCRIPT_DIR%polar.cmd" %*
exit /b %errorlevel%
