@echo off
setlocal EnableExtensions DisableDelayedExpansion

rem Medical Agent - one-click local startup script for Windows.
rem This script uses the local installation paths documented by this project.

set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"
set "RUNTIME=%ROOT%\artifacts\runtime"

set "PG_HOME=D:\develop\postgresql18"
set "PG_CTL=%PG_HOME%\bin\pg_ctl.exe"
set "PG_DATA=%PG_HOME%\data"
set "PG_LOG=%RUNTIME%\postgresql.out.log"

set "MINIO_HOME=D:\develop\minio"
set "MINIO_EXE=%MINIO_HOME%\minio.exe"
set "MINIO_DATA=%MINIO_HOME%\data"
set "MINIO_CREDENTIAL=%MINIO_HOME%\.medical-agent-credential.xml"
set "MINIO_LOG=%RUNTIME%\minio.out.log"
set "MINIO_ERR=%RUNTIME%\minio.err.log"

set "JDK_HOME=D:\develop\jdk21"
set "JAVA_EXE=%JDK_HOME%\bin\java.exe"
set "MAVEN_CMD=%MAVEN_HOME%\bin\mvn.cmd"
if not exist "%MAVEN_CMD%" set "MAVEN_CMD=D:\develop\environment\apache-maven-3.9.11\bin\mvn.cmd"

set "BACKEND_JAR=%ROOT%\backend\target\medical-agent-0.1.0-SNAPSHOT.jar"

cd /d "%ROOT%"
if not exist "%RUNTIME%" mkdir "%RUNTIME%"

echo.
echo ================================================
echo   Medical Agent - One Click Startup
echo ================================================
echo.

if not exist "%PG_CTL%" (
    echo [ERROR] PostgreSQL was not found: %PG_CTL%
    goto :fail
)
if not exist "%PG_DATA%" (
    echo [ERROR] PostgreSQL data directory was not found: %PG_DATA%
    goto :fail
)
if not exist "%MINIO_EXE%" (
    echo [ERROR] MinIO was not found: %MINIO_EXE%
    goto :fail
)
if not exist "%MINIO_DATA%" (
    echo [ERROR] MinIO data directory was not found: %MINIO_DATA%
    goto :fail
)
if not exist "%MINIO_CREDENTIAL%" (
    echo [ERROR] MinIO DPAPI credential file was not found: %MINIO_CREDENTIAL%
    goto :fail
)
if not exist "%JAVA_EXE%" (
    echo [ERROR] JDK 21 was not found: %JAVA_EXE%
    goto :fail
)
set "JAVA_HOME=%JDK_HOME%"
set "PATH=%JDK_HOME%\bin;%PATH%"
if not exist "%MAVEN_CMD%" (
    where mvn.cmd >nul 2>nul
    if errorlevel 1 (
        echo [ERROR] Maven was not found. Install Maven 3.9+ or set MAVEN_HOME.
        goto :fail
    )
    set "MAVEN_CMD=mvn.cmd"
)
where node.exe >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Node.js was not found in PATH.
    goto :fail
)
where npm.cmd >nul 2>nul
if errorlevel 1 (
    echo [ERROR] npm was not found in PATH.
    goto :fail
)

echo [1/5] Starting PostgreSQL...
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -Command ^
    "$listener = Get-NetTCPConnection -LocalPort 5432 -State Listen -ErrorAction SilentlyContinue; if (-not $listener) { & '%PG_CTL%' -D '%PG_DATA%' -l '%PG_LOG%' start | Out-Null; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE } }"
if errorlevel 1 (
    echo [ERROR] PostgreSQL failed to start. Check %PG_LOG%
    goto :fail
)
call :wait_port 5432 30
if errorlevel 1 (
    echo [ERROR] PostgreSQL did not become ready on port 5432.
    goto :fail
)

echo [2/5] Starting MinIO...
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -Command ^
    "$listener = Get-NetTCPConnection -LocalPort 9000 -State Listen -ErrorAction SilentlyContinue; if (-not $listener) { try { $credential = Import-Clixml '%MINIO_CREDENTIAL%'; $env:MINIO_ROOT_USER = $credential.UserName; $env:MINIO_ROOT_PASSWORD = [System.Net.NetworkCredential]::new('', $credential.Password).Password; Start-Process -FilePath '%MINIO_EXE%' -ArgumentList @('server', '%MINIO_DATA%', '--address', '127.0.0.1:9000', '--console-address', '127.0.0.1:9001') -WorkingDirectory '%MINIO_HOME%' -WindowStyle Hidden -RedirectStandardOutput '%MINIO_LOG%' -RedirectStandardError '%MINIO_ERR%' | Out-Null } catch { Write-Error $_; exit 1 } }"
if errorlevel 1 (
    echo [ERROR] MinIO failed to start. Check %MINIO_LOG% and %MINIO_ERR%
    goto :fail
)
call :wait_port 9000 30
if errorlevel 1 (
    echo [ERROR] MinIO did not become ready on port 9000.
    goto :fail
)
call :wait_port 9001 30
if errorlevel 1 (
    echo [ERROR] MinIO console did not become ready on port 9001.
    goto :fail
)

echo [3/5] Starting ClamAV...
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -Command ^
    "& '%ROOT%\tools\start-local-clamav.ps1'"
if errorlevel 1 (
    echo [ERROR] ClamAV failed to start. Check %RUNTIME%\clamd.err.log
    goto :fail
)
call :wait_port 3310 60
if errorlevel 1 (
    echo [ERROR] ClamAV did not become ready on port 3310.
    goto :fail
)

echo [4/5] Preparing backend...
if not exist "%BACKEND_JAR%" (
    echo Backend JAR was not found. Building it now...
    pushd "%ROOT%\backend"
    call "%MAVEN_CMD%" -DskipTests package
    if errorlevel 1 (
        popd
        echo [ERROR] Backend build failed.
        goto :fail
    )
    popd
)

start "" /b powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%ROOT%\tools\start-local-backend.ps1"
call :wait_port 8080 75
if errorlevel 1 (
    echo [ERROR] Backend did not become ready on port 8080.
    echo Check %RUNTIME%\backend.out.log and %RUNTIME%\backend.err.log
    goto :fail
)

echo [5/5] Starting frontend...
if not exist "%ROOT%\frontend\node_modules" (
    echo Frontend dependencies were not found. Running npm ci...
    pushd "%ROOT%\frontend"
    call npm ci
    if errorlevel 1 (
        popd
        echo [ERROR] Frontend dependency installation failed.
        goto :fail
    )
    popd
)

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -Command ^
    "$listener = Get-NetTCPConnection -LocalPort 5173 -State Listen -ErrorAction SilentlyContinue; if (-not $listener) { $workDir = '%ROOT%\frontend'; $out = '%RUNTIME%\frontend.out.log'; $err = '%RUNTIME%\frontend.err.log'; Start-Process -FilePath 'cmd.exe' -ArgumentList @('/d', '/c', 'npm run dev') -WorkingDirectory $workDir -WindowStyle Hidden -RedirectStandardOutput $out -RedirectStandardError $err | Out-Null }"
if errorlevel 1 (
    echo [ERROR] Frontend failed to launch.
    goto :fail
)
call :wait_port 5173 45
if errorlevel 1 (
    echo [ERROR] Frontend did not become ready on port 5173.
    echo Check %RUNTIME%\frontend.out.log and %RUNTIME%\frontend.err.log
    goto :fail
)

echo.
echo All services are ready:
echo   Frontend:   http://127.0.0.1:5173
echo   Backend:    http://127.0.0.1:8080
echo   PostgreSQL: 127.0.0.1:5432
echo   MinIO:      http://127.0.0.1:9000  (console: http://127.0.0.1:9001)
echo   ClamAV:     127.0.0.1:3310
echo.
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%ROOT%\tools\status-local.ps1" -NoFail
start "" "http://127.0.0.1:5173"
echo.
echo Logs: %RUNTIME%
pause
exit /b 0

:wait_port
set "WAIT_PORT=%~1"
set "WAIT_TRIES=%~2"
for /l %%N in (1,1,%WAIT_TRIES%) do (
    powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -Command "$p = Get-NetTCPConnection -LocalPort %WAIT_PORT% -State Listen -ErrorAction SilentlyContinue; if ($p) { exit 0 } else { exit 1 }" >nul 2>nul
    if not errorlevel 1 exit /b 0
    timeout /t 1 /nobreak >nul
)
exit /b 1

:fail
echo.
echo Startup failed. Existing services were not stopped.
echo Review logs under: %RUNTIME%
pause
exit /b 1
