
@echo off
powershell -NoProfile -Command "$f='%~f0'; $c=[IO.File]::ReadAllText($f); $nl=[string][char]13+[string][char]10; $c2=$c.Replace($nl,[string][char]10).Replace([string][char]10,$nl); if ($c -ne $c2) { [IO.File]::WriteAllText($f,$c2,[Text.Encoding]::Default); Write-Host '[Deepthic] Fixed line endings for run.bat. Please run it again.'; Read-Host 'Press Enter to exit'; exit 1 }"
if errorlevel 1 exit /b 0
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
    echo [Deepthic] pom.xml not found near run.bat.
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
    echo [Deepthic] java not found in PATH.
    pause
    exit /b 1
)
where mvn >nul 2>nul
if errorlevel 1 (
    echo [Deepthic] Maven not found in PATH.
    pause
    exit /b 1
)
goto :prereq_ok

:prereq_failed
echo [Deepthic] Prerequisites are not satisfied.
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
rem Preflight: force update if any required rule file is missing or empty.
set "RULES_MISSING=0"
for %%F in (MINIMAL_FORMAT.md EXECUTOR.md JUDGE.md CODE_REVIEWER.md) do (
    if not exist "%BUILD_DIR%\rules\%%F" set "RULES_MISSING=1"
)
if "%RULES_MISSING%"=="1" (
    echo [Deepthic] Required rule files missing; forcing update.
    set "FORCE_UPDATE=1"
)

set "UPDATED=0"

if exist "%BUILD_DIR%\.git" goto :git_update

rem ---- ZIP-based update for dist installs ----
if not exist "%BUILD_DIR%\pom.xml" goto :updates_done
where curl.exe >nul 2>nul
if errorlevel 1 (
    echo [Deepthic] curl.exe not found; skipping auto-update.
    goto :updates_done
)

echo [Deepthic] Checking for updates from GitHub...
set "REMOTE_POM=%TEMP%\freemaf-remote-pom.xml"
del "%REMOTE_POM%" >nul 2>nul
curl.exe -s -L -H "Cache-Control: no-cache" -H "Pragma: no-cache" --max-time 20 -o "%REMOTE_POM%" "https://raw.githubusercontent.com/a-kuhach/deepthic/main/pom.xml?t=%RANDOM%"
if not exist "%REMOTE_POM%" goto :zip_update_skip

set "LOCAL_VER="
for /f "tokens=3 delims=<>" %%V in ('findstr /c:"<version>" "%BUILD_DIR%\pom.xml"') do (
    if not defined LOCAL_VER set "LOCAL_VER=%%V"
)
set "REMOTE_VER="
for /f "tokens=3 delims=<>" %%V in ('findstr /c:"<version>" "%REMOTE_POM%"') do (
    if not defined REMOTE_VER set "REMOTE_VER=%%V"
)
del "%REMOTE_POM%" >nul 2>nul

if not defined LOCAL_VER goto :zip_update_skip
if not defined REMOTE_VER (
    echo [Deepthic] Failed to parse remote version. Remote POM contents:
    type "%REMOTE_POM%"
    goto :zip_update_skip
)

echo [Deepthic] Local version:  %LOCAL_VER%
echo [Deepthic] Remote version: %REMOTE_VER%

if "%LOCAL_VER%"=="%REMOTE_VER%" (
    if not defined FORCE_UPDATE (
        echo [Deepthic] Already up to date.
        goto :updates_done
    )
    echo [Deepthic] Forcing update to restore missing rules.
)

echo [Deepthic] Update available; downloading ZIP...
set "ZIP=%TEMP%\freemaf-update.zip"
set "EXTRACT_DIR=%TEMP%\freemaf-update"
del "%ZIP%" >nul 2>nul
if exist "%EXTRACT_DIR%" rmdir /S /Q "%EXTRACT_DIR%" >nul 2>nul

curl.exe -s -L --max-time 120 -o "%ZIP%" "https://github.com/a-kuhach/deepthic/archive/refs/heads/main.zip"
if not exist "%ZIP%" goto :zip_update_skip

echo [Deepthic] Extracting...
powershell.exe -NoProfile -Command "try { Expand-Archive -LiteralPath '%ZIP%' -DestinationPath '%EXTRACT_DIR%' -Force } catch { exit 1 }"
if errorlevel 1 (
    echo [Deepthic] Extraction failed; continuing with local code.
    del "%ZIP%" >nul 2>nul
    goto :updates_done
)

set "SRC=%EXTRACT_DIR%\deepthic-main"
if not exist "%SRC%\" (
    echo [Deepthic] Unexpected ZIP layout; continuing with local code.
    rmdir /S /Q "%EXTRACT_DIR%" >nul 2>nul
    del "%ZIP%" >nul 2>nul
    goto :updates_done
)

echo [Deepthic] Applying update (preserving config.properties, project/, cache/, history/, util/, build/)...
if exist "%BUILD_DIR%\src" rmdir /S /Q "%BUILD_DIR%\src"
xcopy "%SRC%\src" "%BUILD_DIR%\src" /E /I /Q /Y >nul
if exist "%BUILD_DIR%\rules" rmdir /S /Q "%BUILD_DIR%\rules"
xcopy "%SRC%\rules" "%BUILD_DIR%\rules" /E /I /Q /Y >nul

if exist "%SRC%\pom.xml"                  copy /Y "%SRC%\pom.xml"                  "%BUILD_DIR%\pom.xml"                  >nul
if exist "%SRC%\ensure-prerequisites.ps1" copy /Y "%SRC%\ensure-prerequisites.ps1" "%BUILD_DIR%\ensure-prerequisites.ps1" >nul
if exist "%SRC%\README.md"                copy /Y "%SRC%\README.md"                "%BUILD_DIR%\README.md"                >nul
if exist "%SRC%\LICENSE"                  copy /Y "%SRC%\LICENSE"                  "%BUILD_DIR%\LICENSE"                  >nul
if exist "%SRC%\.gitignore"               copy /Y "%SRC%\.gitignore"               "%BUILD_DIR%\.gitignore"               >nul
if exist "%SRC%\.gitattributes"           copy /Y "%SRC%\.gitattributes"           "%BUILD_DIR%\.gitattributes"           >nul
if exist "%SRC%\winrect.ps1"              copy /Y "%SRC%\winrect.ps1"              "%BUILD_DIR%\winrect.ps1"              >nul
if exist "%SRC%\code_mutation.ps1"        copy /Y "%SRC%\code_mutation.ps1"        "%BUILD_DIR%\code_mutation.ps1"        >nul

rmdir /S /Q "%EXTRACT_DIR%" >nul 2>nul
del "%ZIP%" >nul 2>nul

set "UPDATED=1"
echo [Deepthic] Update applied; rebuild required.
goto :updates_done

:zip_update_skip
echo [Deepthic] Update check skipped (offline or bad response); using local code.
goto :updates_done

:git_update
where git >nul 2>nul
if errorlevel 1 goto :updates_done

echo [Deepthic] Checking for updates from remote (git)...
pushd "%BUILD_DIR%"
for /f "delims=" %%B in ('git rev-parse --abbrev-ref HEAD 2^>nul') do set "BRANCH=%%B"
if not "%BRANCH%"=="main" (
    echo [Deepthic] Not on branch 'main' ^(%BRANCH%^); skipping update.
    popd
    goto :updates_done
)
git fetch --quiet origin
if errorlevel 1 (
    echo [Deepthic] git fetch failed; continuing with local code.
    popd
    goto :updates_done
)
set "BEHIND="
for /f "delims=" %%N in ('git rev-list --count HEAD..origin/main 2^>nul') do set "BEHIND=%%N"
if not defined BEHIND (
    echo [Deepthic] Cannot determine remote main; skipping update.
    popd
    goto :updates_done
)
if "%BEHIND%"=="0" (
    echo [Deepthic] Already up to date.
    popd
    goto :updates_done
)
echo [Deepthic] %BEHIND% new commit^(s^) in origin/main; pulling...
git pull --ff-only origin main
if errorlevel 1 (
    echo [Deepthic] git pull failed; continuing with local code.
    popd
    goto :updates_done
)
set "UPDATED=1"
echo [Deepthic] Update applied; rebuild required.
popd
goto :updates_done

:updates_done

if "%UPDATED%"=="1" goto :do_build_clean

if not exist "%JAR%" goto :do_build
powershell.exe -NoProfile -Command "try { Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null; $z=[System.IO.Compression.ZipFile]::OpenRead('%JAR%'); $ok=$z.Entries | Where-Object { $_.FullName -eq 'com/freemaf/agent/Main.class' }; $z.Dispose(); if ($ok) { exit 0 } else { exit 1 } } catch { exit 1 }"
if not errorlevel 1 goto :run
echo [Deepthic] Existing JAR is stale or invalid; rebuilding...
goto :do_build

:do_build
echo [Deepthic] Building framework, this may take a few minutes...
pushd "%BUILD_DIR%"
call mvn -q -DskipTests package
set "MVN_RC=%ERRORLEVEL%"
popd
goto :build_done

:do_build_clean
echo [Deepthic] Rebuilding after update...
pushd "%BUILD_DIR%"
call mvn -q -DskipTests clean package
set "MVN_RC=%ERRORLEVEL%"
popd

:build_done
if not "%MVN_RC%"=="0" (
    echo [Deepthic] Build failed with exit code %MVN_RC%.
    pause
    exit /b 1
)
if not exist "%JAR%" (
    echo [Deepthic] Build finished but %JAR% is missing.
    pause
    exit /b 1
)

:run
cd /d "%PROJECT_ROOT%"

rem Start window hunter (closes only framework-created Chrome windows)
if exist "%AGENT_DIR%\util\window_hunter.ps1" (
    echo [Deepthic] Starting window hunter...
    start "WindowHunter" /B powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%AGENT_DIR%\util\window_hunter.ps1"
) else (
    echo [Deepthic] window_hunter.ps1 not found; skipping.
)

echo [Deepthic] Starting...
java -jar "%JAR%"
set "JAVA_RC=%ERRORLEVEL%"

rem Kill window hunter
taskkill /F /T /FI "WINDOWTITLE eq WindowHunter" >nul 2>nul

if not "%JAVA_RC%"=="0" (
    echo [Deepthic] Application exited with code %JAVA_RC%.
    pause
)
exit /b %JAVA_RC%