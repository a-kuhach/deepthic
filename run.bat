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

rem ============================================================
rem  Update section.
rem  - If .git exists: git pull (dev checkout).
rem  - Else (ZIP install): compare pom.xml version with remote and, if
rem    different, download the fresh ZIP and replace A-side source files
rem    while preserving runtime artifacts (config.properties, project/,
rem    cache/, history/, util/, build/).
rem ============================================================
set "UPDATED=0"

if exist "%BUILD_DIR%\.git" goto :git_update

rem ---- ZIP-based update for dist installs ----
if not exist "%BUILD_DIR%\pom.xml" goto :updates_done
where curl.exe >nul 2>nul
if errorlevel 1 (
    echo [Free MAF Code] curl.exe not found; skipping auto-update.
    goto :updates_done
)

echo [Free MAF Code] Checking for updates from GitHub...
set "REMOTE_POM=%TEMP%\freemaf-remote-pom.xml"
del "%REMOTE_POM%" >nul 2>nul
curl.exe -s -L --max-time 20 -o "%REMOTE_POM%" "https://raw.githubusercontent.com/rezdoom90/free-maf-code/main/pom.xml"
if not exist "%REMOTE_POM%" goto :zip_update_skip

set "LOCAL_VER="
for /f "tokens=2 delims=<>" %%V in ('findstr /c:"<version>" "%BUILD_DIR%\pom.xml"') do (
    if not defined LOCAL_VER set "LOCAL_VER=%%V"
)
set "REMOTE_VER="
for /f "tokens=2 delims=<>" %%V in ('findstr /c:"<version>" "%REMOTE_POM%"') do (
    if not defined REMOTE_VER set "REMOTE_VER=%%V"
)
del "%REMOTE_POM%" >nul 2>nul

if not defined LOCAL_VER goto :zip_update_skip
if not defined REMOTE_VER goto :zip_update_skip

echo [Free MAF Code] Local version:  %LOCAL_VER%
echo [Free MAF Code] Remote version: %REMOTE_VER%

if "%LOCAL_VER%"=="%REMOTE_VER%" (
    echo [Free MAF Code] Already up to date.
    goto :updates_done
)

echo [Free MAF Code] Update available; downloading ZIP...
set "ZIP=%TEMP%\freemaf-update.zip"
set "EXTRACT_DIR=%TEMP%\freemaf-update"
del "%ZIP%" >nul 2>nul
if exist "%EXTRACT_DIR%" rmdir /S /Q "%EXTRACT_DIR%" >nul 2>nul

curl.exe -s -L --max-time 120 -o "%ZIP%" "https://github.com/rezdoom90/free-maf-code/archive/refs/heads/main.zip"
if not exist "%ZIP%" goto :zip_update_skip

echo [Free MAF Code] Extracting...
powershell.exe -NoProfile -Command "try { Expand-Archive -LiteralPath '%ZIP%' -DestinationPath '%EXTRACT_DIR%' -Force } catch { exit 1 }"
if errorlevel 1 (
    echo [Free MAF Code] Extraction failed; continuing with local code.
    del "%ZIP%" >nul 2>nul
    goto :updates_done
)

set "SRC=%EXTRACT_DIR%\free-maf-code-main"
if not exist "%SRC%\" (
    echo [Free MAF Code] Unexpected ZIP layout; continuing with local code.
    rmdir /S /Q "%EXTRACT_DIR%" >nul 2>nul
    del "%ZIP%" >nul 2>nul
    goto :updates_done
)

echo [Free MAF Code] Applying update (preserving config.properties, project/, cache/, history/, util/, build/)...
if exist "%BUILD_DIR%\src" rmdir /S /Q "%BUILD_DIR%\src"
xcopy "%SRC%\src" "%BUILD_DIR%\src" /E /I /Q /Y >nul
if exist "%BUILD_DIR%\rules" rmdir /S /Q "%BUILD_DIR%\rules"
xcopy "%SRC%\rules" "%BUILD_DIR%\rules" /E /I /Q /Y >nul
for %%F in (pom.xml ensure-prerequisites.ps1 README.md AGENT_INSTRUCTIONS.md LICENSE .gitignore .gitattributes winrect.ps1 code_mutation.ps1) do (
    if exist "%SRC%\%%F" copy /Y "%SRC%\%%F" "%BUILD_DIR%\%%F" >nul
)
if exist "%SRC%\run.bat" (
    copy /Y "%SRC%\run.bat" "%BUILD_DIR%\run.bat.new" >nul
    move /Y "%BUILD_DIR%\run.bat.new" "%BUILD_DIR%\run.bat" >nul
)

rmdir /S /Q "%EXTRACT_DIR%" >nul 2>nul
del "%ZIP%" >nul 2>nul

set "UPDATED=1"
echo [Free MAF Code] Update applied; rebuild required.
goto :updates_done

:zip_update_skip
echo [Free MAF Code] Update check skipped (offline or bad response); using local code.
goto :updates_done

:git_update
where git >nul 2>nul
if errorlevel 1 goto :updates_done

echo [Free MAF Code] Checking for updates from remote (git)...
pushd "%BUILD_DIR%"
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
goto :updates_done

:updates_done

if "%UPDATED%"=="1" goto :do_build_clean

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
