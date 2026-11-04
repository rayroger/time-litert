@echo off
rem Restarts the agent bridge if it ever exits. Set the token first in this window (never in this file):
rem   set GITHUB_TOKEN=github_pat_...
rem   run_bridge.bat
:loop
python agent_bridge.py --data "C:\Users\ilya\Downloads\RebexTinyFtpServer-Binaries-Latest\data"
echo bridge exited (%date% %time%), restarting in 5 seconds - press Ctrl+C twice to stop
timeout /t 5 /nobreak >nul
goto loop
