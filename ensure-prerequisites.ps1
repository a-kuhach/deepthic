# ensure-prerequisites.ps1
# Project A (Deepthic) dependency installer for a clean Windows.
# Runs from project A root. Installs JDK 21 (Temurin), Git, Maven if missing.
# Writes %TEMP%\freemaf-env.bat with PATH / JAVA_HOME / MAVEN_HOME so run.bat can pick them up.


$ErrorActionPreference = 'Continue'
# Always regenerate %TEMP%\freemaf-env.bat to avoid stale PATH/JAVA_HOME/MAVEN_HOME
$envFile = Join-Path $env:TEMP 'freemaf-env.bat'
if (Test-Path $envFile) { Remove-Item $envFile -Force -ErrorAction SilentlyContinue }
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Refresh-PathFromRegistry {
    $machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
    $userPath    = [Environment]::GetEnvironmentVariable('Path', 'User')
    $env:PATH = (($machinePath, $userPath) | Where-Object { $_ -and $_.Trim() }) -join ';'
}

function Test-Command([string]$name) { return [bool](Get-Command $name -ErrorAction SilentlyContinue) }

function Ensure-Winget {
    if (Test-Command winget) { return $true }
    Write-Host '[Deepthic] winget not found, attempting to install via PSGallery...'
    try {
        if (-not (Get-PackageProvider -Name NuGet -ErrorAction SilentlyContinue)) {
            Install-PackageProvider -Name NuGet -Force -Scope CurrentUser -ErrorAction Stop | Out-Null
        }
        Set-PSRepository -Name PSGallery -InstallationPolicy Trusted -ErrorAction SilentlyContinue
        Install-Module -Name Microsoft.WinGet.Client -Force -Scope CurrentUser -AllowClobber -ErrorAction Stop
        Import-Module Microsoft.WinGet.Client -ErrorAction Stop
        Repair-WinGetPackageManager -Force -Latest -ErrorAction Continue
        Refresh-PathFromRegistry
        if (Test-Command winget) { return $true }
    } catch {
        Write-Warning "winget installation failed: $($_.Exception.Message)"
    }
    return $false
}

function Ensure-Jdk {
    Refresh-PathFromRegistry
    $java = Get-Command java -ErrorAction SilentlyContinue
    if ($java) {
        $first = (& java -version 2>&1 | Select-Object -First 1)
        if ("$first" -match 'version "(\d+)') {
            $major = [int]$Matches[1]
            if ($major -ge 21) { Write-Host "[Deepthic] JDK $major found: $($java.Source)"; return $true }
        }
    }
    if (Ensure-Winget) {
        Write-Host '[Deepthic] Installing Temurin JDK 21 via winget...'
        & winget install --id EclipseAdoptium.Temurin.21.JDK --exact --silent --accept-package-agreements --accept-source-agreements
        Refresh-PathFromRegistry
        if (Test-Command java) { Write-Host '[Deepthic] JDK installed via winget'; return $true }
    }
    Write-Host '[Deepthic] Falling back to direct Temurin ZIP download...'
    try {
        $installRoot = Join-Path $env:LOCALAPPDATA 'Programs\Temurin-21'
        if (-not (Test-Path $installRoot)) { New-Item -ItemType Directory -Path $installRoot -Force | Out-Null }
        $zipPath = Join-Path $env:TEMP 'temurin-21.zip'
        $url = 'https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk'
        Invoke-WebRequest -Uri $url -OutFile $zipPath -UseBasicParsing
        $extractTmp = Join-Path $env:TEMP 'temurin-21-extract'
        if (Test-Path $extractTmp) { Remove-Item $extractTmp -Recurse -Force }
        Expand-Archive -Path $zipPath -DestinationPath $extractTmp -Force
        $inner = Get-ChildItem -Path $extractTmp -Directory | Select-Object -First 1
        Copy-Item -Path (Join-Path $inner.FullName '*') -Destination $installRoot -Recurse -Force
        Remove-Item $extractTmp -Recurse -Force -ErrorAction SilentlyContinue
        Remove-Item $zipPath -Force -ErrorAction SilentlyContinue
        $env:JAVA_HOME = $installRoot
        $env:PATH = "$installRoot\bin;$env:PATH"
        [Environment]::SetEnvironmentVariable('JAVA_HOME', $installRoot, 'User')
        $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
        if (-not $userPath -or $userPath -notmatch [regex]::Escape("$installRoot\bin")) {
            $newPath = if ($userPath) { "$userPath;$installRoot\bin" } else { "$installRoot\bin" }
            [Environment]::SetEnvironmentVariable('Path', $newPath, 'User')
        }
        Refresh-PathFromRegistry
        if (Test-Command java) { Write-Host '[Deepthic] JDK installed via ZIP'; return $true }
    } catch {
        Write-Warning "JDK ZIP fallback failed: $($_.Exception.Message)"
    }
    return $false
}

function Ensure-Git {
    Refresh-PathFromRegistry
    if (Test-Command git) { Write-Host '[Deepthic] git found'; return $true }
    if (-not (Ensure-Winget)) { return $false }
    Write-Host '[Deepthic] Installing Git via winget...'
    & winget install --id Git.Git --exact --silent --accept-package-agreements --accept-source-agreements
    Refresh-PathFromRegistry
    if (Test-Command git) { Write-Host '[Deepthic] git installed'; return $true }
    return $false
}

function Ensure-Maven {
    Refresh-PathFromRegistry
    if (Test-Command mvn) { Write-Host '[Deepthic] Maven found'; return $true }
    Write-Host '[Deepthic] Installing Maven 3.9.9 via ZIP...'
    try {
        $mavenVersion = '3.9.9'
        $installRoot = Join-Path $env:LOCALAPPDATA 'Programs\Maven'
        $mavenDir = Join-Path $installRoot ("apache-maven-$mavenVersion")
        if (-not (Test-Path (Join-Path $mavenDir 'bin\mvn.cmd'))) {
            New-Item -ItemType Directory -Path $installRoot -Force | Out-Null
            $url = "https://archive.apache.org/dist/maven/maven-3/$mavenVersion/binaries/apache-maven-$mavenVersion-bin.zip"
            $zipPath = Join-Path $env:TEMP "apache-maven-$mavenVersion-bin.zip"
            Invoke-WebRequest -Uri $url -OutFile $zipPath -UseBasicParsing
            Expand-Archive -Path $zipPath -DestinationPath $installRoot -Force
            Remove-Item $zipPath -Force -ErrorAction SilentlyContinue
        }
        $env:MAVEN_HOME = $mavenDir
        $env:PATH = "$mavenDir\bin;$env:PATH"
        [Environment]::SetEnvironmentVariable('MAVEN_HOME', $mavenDir, 'User')
        $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
        if (-not $userPath -or $userPath -notmatch [regex]::Escape("$mavenDir\bin")) {
            $newPath = if ($userPath) { "$userPath;$mavenDir\bin" } else { "$mavenDir\bin" }
            [Environment]::SetEnvironmentVariable('Path', $newPath, 'User')
        }
        Refresh-PathFromRegistry
        if (Test-Command mvn) { Write-Host '[Deepthic] Maven installed'; return $true }
    } catch {
        Write-Warning "Maven ZIP install failed: $($_.Exception.Message)"
    }
    return $false
}


# ---- Run ----

Write-Host '[Deepthic] Checking prerequisites...'

$jdkOk   = Ensure-Jdk

$gitOk   = Ensure-Git

$mavenOk = Ensure-Maven



if (-not $jdkOk)   { Write-Host '[Deepthic] JDK is not available. Manual install: https://adoptium.net/temurin/releases/?version=21'; exit 1 }

if (-not $mavenOk) { Write-Host '[Deepthic] Maven is not available.'; exit 1 }

if (-not $gitOk)   { Write-Host '[Deepthic] Git is not available (recommended but not required).' }



Refresh-PathFromRegistry



$javaCmd = (Get-Command java -ErrorAction SilentlyContinue).Source

$mvnCmd  = (Get-Command mvn  -ErrorAction SilentlyContinue).Source

$javaHome  = if ($javaCmd) { Split-Path (Split-Path $javaCmd -Parent) -Parent } else { '' }

$mavenHome = if ($mvnCmd)  { Split-Path (Split-Path $mvnCmd  -Parent) -Parent } else { '' }



function SanitizeForBat([string]$v) {

    if ($null -eq $v) { return '' }

    $s = [string]$v

    $s = $s -replace '"',''

    $s = $s -replace "`r",''

    $s = $s -replace "`n",''

    return $s

}



$pathSan  = SanitizeForBat $env:PATH

$javaSan  = SanitizeForBat $javaHome

$mavenSan = SanitizeForBat $mavenHome



Write-Host ("[Deepthic] PATH length after sanitize: " + $pathSan.Length)

Write-Host ("[Deepthic] JAVA_HOME = " + $javaSan)

Write-Host ("[Deepthic] MAVEN_HOME = " + $mavenSan)



$envFile = Join-Path $env:TEMP 'freemaf-env.bat'

$envLines = @(

    '@echo off',

    ('set "PATH=' + $pathSan + '"'),

    ('set "JAVA_HOME=' + $javaSan + '"'),

    ('set "MAVEN_HOME=' + $mavenSan + '"')

)



# Use Default (ANSI) encoding so cmd.exe reads the batch correctly on any locale.

[System.IO.File]::WriteAllLines($envFile, $envLines, [System.Text.Encoding]::Default)



Write-Host "[Deepthic] Prerequisites OK. Env written to $envFile"

exit 0
