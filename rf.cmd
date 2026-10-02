@echo off
setlocal enabledelayedexpansion

set "DIR=%~dp0"
set "PORT_FILE=%DIR%.remote-flow.port"
set "EXIT_FILE=%DIR%.remote-flow.exitcode"

if not exist "%PORT_FILE%" (
    echo [Remote Flow] Error: IntelliJ IDEA Remote Flow bridge is not running.
    echo Please ensure IntelliJ IDEA is open with Remote Flow active.
    exit /b 1
)

set /p RF_PORT=<"%PORT_FILE%"
set "RF_PORT=!RF_PORT: =!"

set "ACTION=%~1"
if "%ACTION%"=="" set "ACTION=status"

if exist "%EXIT_FILE%" del /f /q "%EXIT_FILE%" >nul 2>&1

if /i "%ACTION%"=="test" (
    curl.exe -s -N -X POST "http://127.0.0.1:%RF_PORT%/api/test"
    goto :finish
)

if /i "%ACTION%"=="build" (
    curl.exe -s -N -X POST "http://127.0.0.1:%RF_PORT%/api/build"
    goto :finish
)

if /i "%ACTION%"=="sync" (
    curl.exe -s -N -X POST "http://127.0.0.1:%RF_PORT%/api/sync"
    goto :finish
)

if /i "%ACTION%"=="status" (
    curl.exe -s "http://127.0.0.1:%RF_PORT%/api/status"
    echo.
    goto :finish
)

if /i "%ACTION%"=="exec" (
    shift
    set "REM_ARGS="
    :loop
    if "%~1"=="" goto :endloop
    if defined REM_ARGS (set "REM_ARGS=!REM_ARGS! %~1") else (set "REM_ARGS=%~1")
    shift
    goto :loop
    :endloop
    curl.exe -s -N -X POST "http://127.0.0.1:%RF_PORT%/api/exec" --data-binary "!REM_ARGS!"
    goto :finish
)

echo [Remote Flow] Unknown command: %ACTION%
echo Usage: rf ^<test ^| build ^| sync ^| exec ^<command^> ^| status^>
exit /b 1

:finish
if exist "%EXIT_FILE%" (
    set /p CODE=<"%EXIT_FILE%"
    exit /b !CODE!
)
exit /b 0
