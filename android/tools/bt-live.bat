@echo off
rem Follow Banjo-Tooie Recompiled live over adb, and hand Claude what it needs as it happens.
rem
rem 1. On the device: USB debugging on; connect by USB (or wireless debugging + adb connect).
rem 2. In the game: Settings > Video > Live Debug Log: On.
rem 3. Run this file and leave it open while you play.
rem
rem What it does:
rem   - shows the status line every second (BTLive), the game's own log (BTRecomp) and the
rem     probe (BTProbe), and saves them to live\session-<date>.log and live\current.log;
rem   - each time you tap Mark on the bottom screen, copies that RAM snapshot to
rem     live\<date>\mark-NN.bin.gz as soon as the game has written it, so Claude can read it
rem     straight away (with the Claude desktop app connected to this folder).
rem
rem   bt-live           as above
rem   bt-live all       everything the app writes, including Android's own messages for it
rem   bt-live crash     also shows crash reports
cd /d "%~dp0"
call _adb.bat || (pause & exit /b 1)
set "BT_ADB_EXE=%ADB_EXE%"
set "BT_SERIAL=%DEVICE_SERIAL%"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bt-live.ps1" %1
pause
