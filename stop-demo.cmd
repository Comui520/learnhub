@echo off
setlocal
cd /d "%~dp0"
echo [LearnHub] Stopping containers and keeping all data...
docker compose down
if errorlevel 1 (
  echo Failed to stop LearnHub.
  pause
  exit /b 1
)
echo [LearnHub] Stopped. MySQL, MinIO, Qdrant and other data volumes are preserved.
pause
