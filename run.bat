@echo off
setlocal EnableExtensions

set "RUN_DIR=%~dp0"
if "%RUN_DIR:~-1%"=="\" set "RUN_DIR=%RUN_DIR:~0,-1%"
set "LOG=%TEMP%\freemaf-run.log"

echo ==== run.bat start %DATE% %TIME% ====>>"%LOG%"
echo RUN_DIR=%RUN_DIR%>>"%LOG%"

rem Locate pom.xml (the Maven project directory).
set "BUILD_DIR="
if exist "%RUN_DIR%\pom.xml" set "BUILD_DIR=%RUN_DIR%"
if not defined BUILD_DIR (
    pushd "%RUN_DIR%\.."
    if exist "pom.xml" set "BUILD_DIR=%CD%"
    popd
)
if not defined BUILD_DIR (
    echo [Free MAF Code] pom.xml not found near run.bat.
    echo Checked: %RUN_DIR% and its parent.
    echo pom.xml not found>>"%LOG%"
    pause
    exit /b 1
)
echo BUILD_DIR=%BUILD_DIR%>>"%LOG%"

rem Detect layout: dev (agent subfolder has rules) vs dist (BUILD_DIR itself is the agent folder).
if exist "%BUILD_DIR%\agent\rules" goto :dev_layout
set "AGENT_DIR=%BUILD_DIR%"
pushd "%BUILD_DIR%\.."
set "PROJECT_ROOT=%CD%"
popd
goto :layout_ready

:dev_layout
set "AGENT_DIR=%BUILD_DIR%\agent"
set "PROJECT_ROOT=%BUILD_DIR%"

:layout_ready
set "JAR=%BUILD_DIR%\target\app.jar"
echo AGENT_DIR=%AGENT_DIR%>>"%LOG%"
echo PROJECT_ROOT=%PROJECT_ROOT%>>"%LOG%"
echo JAR=%JAR%>>"%LOG%"

rem Prerequisites.
if not exist "%RUN_DIR%\ensure-prerequisites.ps1" goto :legacy_prereq
if exist "%TEMP%\freemaf-env.bat" del "%TEMP%\freemaf-env.bat" >nul 2>nul
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%RUN_DIR%\ensure-prerequisites.ps1"
if errorlevel 1 goto :prereq_failed
if exist "%TEMP%\freemaf-env.bat" call "%TEMP%\freemaf-env.bat"
goto :prereq_ok

:legacy_prereq
where java >nul 2>nul
if errorlevel 1 (
    echo [Free MAF Code] java not found in PATH.
    pause
    exit /b 1
)
where mvn >nul 2>nul
if errorlevel 1 (
    echo [Free MAF Code] Maven not found in PATH.
    pause
    exit /b 1
)
goto :prereq_ok

:prereq_failed
echo [Free MAF Code] Prerequisites are not satisfied.
pause
exit /b 1

:prereq_ok

rem ---- Update from origin/main (skipped if .git or git is unavailable) ----
set "UPDATED=0"
if not exist "%BUILD_DIR%\.git" goto :updates_done
where git >nul 2>nul
if errorlevel 1 goto :updates_done

echo [Free MAF Code] Checking for updates from remote...
pushd "%BUILD_DIR%"

rem Only update when on the main branch; skip otherwise to avoid surprises.
for /f "delims=" %%B in ('git rev-parse --abbrev-ref HEAD 2^>nul') do set "BRANCH=%%B"
if not "%BRANCH%"=="main" (
    echo [Free MAF Code] Not on branch 'main' ^(%BRANCH%^); skipping update.
    popd
    goto :updates_done
)

git fetch --quiet origin
if errorlevel 1 (
    echo [Free MAF Code] git fetch failed; continuing with local code.
    popd
    goto :updates_done
)

set "BEHIND="
for /f "delims=" %%N in ('git rev-list --count HEAD..origin/main 2^>nul') do set "BEHIND=%%N"
if not defined BEHIND (
    echo [Free MAF Code] Cannot determine remote main; skipping update.
    popd
    goto :updates_done
)
if "%BEHIND%"=="0" (
    echo [Free MAF Code] Already up to date.
    popd
    goto :updates_done
)

echo [Free MAF Code] %BEHIND% new commit^(s^) in origin/main; pulling...
git pull --ff-only origin main
if errorlevel 1 (
    echo [Free MAF Code] git pull failed; continuing with local code.
    popd
    goto :updates_done
)
set "UPDATED=1"
echo [Free MAF Code] Update applied; rebuild required.
popd

:updates_done

rem Force rebuild if we just pulled an update.
if "%UPDATED%"=="1" goto :do_build_clean

rem Verify existing JAR contains the main class; otherwise rebuild.
if not exist "%JAR%" goto :do_build
powershell.exe -NoProfile -Command "try { Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null; $z=[System.IO.Compression.ZipFile]::OpenRead('%JAR%'); $ok=$z.Entries | Where-Object { $_.FullName -eq 'com/freemaf/agent/Main.class' }; $z.Dispose(); if ($ok) { exit 0 } else { exit 1 } } catch { exit 1 }"
if not errorlevel 1 goto :run
echo [Free MAF Code] Existing JAR is stale or invalid; rebuilding...
goto :do_build

:do_build
echo [Free MAF Code] Building framework, this may take a few minutes...
pushd "%BUILD_DIR%"
call mvn -q -DskipTests package
set "MVN_RC=%ERRORLEVEL%"
popd
goto :build_done

:do_build_clean
echo [Free MAF Code] Rebuilding after update...
pushd "%BUILD_DIR%"
call mvn -q -DskipTests clean package
set "MVN_RC=%ERRORLEVEL%"
popd

:build_done
if not "%MVN_RC%"=="0" (
    echo [Free MAF Code] Build failed with exit code %MVN_RC%.
    pause
    exit /b 1
)
if not exist "%JAR%" (
    echo [Free MAF Code] Build finished but %JAR% is missing.
    pause
    exit /b 1
)

:run
cd /d "%PROJECT_ROOT%"
echo [Free MAF Code] Starting...
java -jar "%JAR%"
set "JAVA_RC=%ERRORLEVEL%"
if not "%JAVA_RC%"=="0" (
    echo [Free MAF Code] Application exited with code %JAVA_RC%.
    pause
)
exit /b %JAVA_RC%
