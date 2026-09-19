@echo off
setlocal
cd /d "%~dp0"

echo [LearnHub] Checking Docker Desktop...
docker info >nul 2>&1
if errorlevel 1 (
  echo.
  echo Docker is not running. Please start Docker Desktop and try again.
  pause
  exit /b 1
)

if not exist ".env" (
  echo.
  echo Missing .env. Creating it from .env.example...
  copy /y ".env.example" ".env" >nul
  echo Please edit .env and replace all change-me / replace-with values, then run this file again.
  start "" notepad ".env"
  pause
  exit /b 1
)

docker image inspect learnhub-backend:local >nul 2>&1
if errorlevel 1 goto build
docker image inspect learnhub-frontend:local >nul 2>&1
if errorlevel 1 goto build

echo [LearnHub] Starting existing Docker images...
docker compose up -d
goto started

:build
echo [LearnHub] First run detected. Building images and starting services...
docker compose up -d --build

:started
if errorlevel 1 (
  echo.
  echo LearnHub failed to start. Run: docker compose logs backend
  pause
  exit /b 1
)

echo [LearnHub] Waiting for the web application...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$deadline=(Get-Date).AddMinutes(3); do { try { $r=Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 http://127.0.0.1:8088/actuator/health; if($r.StatusCode -eq 200){ exit 0 } } catch {}; Start-Sleep -Seconds 2 } while((Get-Date) -lt $deadline); exit 1"
if errorlevel 1 (
  echo.
  echo Containers started, but the application did not become healthy in time.
  docker compose ps
  echo Run this command for details: docker compose logs --tail=100 backend
  pause
  exit /b 1
)

echo.
echo [LearnHub] Ready: http://127.0.0.1:8088
start "" "http://127.0.0.1:8088"
docker compose ps
exit /b 0
