@echo off
rem Follow Banjo-Tooie Recompiled on an Android device live over adb.
rem
rem 1. On the device: Settings > Developer options > USB debugging on; connect by USB
rem    (or "adb connect <ip>:<port>" for wireless debugging).
rem 2. In the game: Settings > Video > Live Debug Log: On.
rem 3. Run this file. Lines appear as they happen and are also saved to bt-live-<date>.log here.
rem
rem   bt-live           status line every second (BTLive) + the game's own log (BTRecomp)
rem   bt-live all       everything the app writes, including Android's own messages for it
rem   bt-live crash     also shows crashes (the system's crash reports)
rem
rem Needs adb (Android platform-tools): on PATH, next to this file, or in the Android SDK.

setlocal
set "ADB=adb"
where adb >nul 2>nul || (
  if exist "%~dp0adb.exe" (set "ADB=%~dp0adb.exe") else if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" (set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe") else (
    echo adb not found. Install Android platform-tools and put adb.exe on PATH or next to this file:
    echo https://developer.android.com/tools/releases/platform-tools
    exit /b 1
  )
)

"%ADB%" get-state >nul 2>nul || (
  echo No device. Turn on USB debugging, connect the device and accept the prompt on its screen.
  exit /b 1
)

for /f %%d in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set "STAMP=%%d"
set "OUT=%~dp0bt-live-%STAMP%.log"
echo Saving to %OUT%
echo Press Ctrl+C to stop.
echo.

if /i "%~1"=="all" (
  for /f %%p in ('"%ADB%" shell pidof io.github.bownz.btrecomp') do set "PID=%%p"
  if not defined PID (
    echo The game isn't running; start it and run this again.
    exit /b 1
  )
  powershell -NoProfile -Command "& '%ADB%' logcat -v time --pid=%PID% | Tee-Object -FilePath '%OUT%'"
) else if /i "%~1"=="crash" (
  powershell -NoProfile -Command "& '%ADB%' logcat -v time -s BTLive:I BTRecomp:I DEBUG:* AndroidRuntime:E libc:F | Tee-Object -FilePath '%OUT%'"
) else (
  powershell -NoProfile -Command "& '%ADB%' logcat -v time -s BTLive:I BTRecomp:I | Tee-Object -FilePath '%OUT%'"
)
