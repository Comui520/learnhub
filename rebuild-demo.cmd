@echo off
setlocal
cd /d "%~dp0"
echo [LearnHub] Rebuilding application images...
docker compose up -d --build
if errorlevel 1 (
  echo Rebuild failed. Run: docker compose logs backend
  pause
  exit /b 1
)
docker compose ps
pause
