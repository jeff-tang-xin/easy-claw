# Easy Claw - Windows build & package script
# Usage:
#   .\scripts\build-package.ps1                    # app-image (bundled JRE) + zip, no Java needed on target
#   .\scripts\build-package.ps1 -ExeInstaller      # exe installer (needs WiX Toolset)
#   .\scripts\build-package.ps1 -FatJarOnly        # fat jar only (target needs JDK 21+)
#   .\scripts\build-package.ps1 -Version 1.2.0     # override version
#   .\scripts\build-package.ps1 -SkipFrontend      # skip frontend build

param(
    [switch]$ExeInstaller,
    [switch]$FatJarOnly,
    [string]$Version = "",
    [switch]$SkipFrontend
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$TargetDir   = Join-Path $ProjectRoot "target"
$DistDir     = Join-Path $TargetDir "dist"
$AppName     = "Easy-Claw"

# Default mode: app-image (bundled JRE, no Java install needed on target)
$Mode = if ($ExeInstaller) { "exe" } elseif ($FatJarOnly) { "fatjar" } else { "app-image" }

Write-Host "=============================================" -ForegroundColor Cyan
Write-Host "  $AppName - Windows Build Script" -ForegroundColor Cyan
Write-Host "=============================================" -ForegroundColor Cyan
Write-Host "  Mode: $Mode" -ForegroundColor Cyan
Write-Host ""

# ---------- read version from pom.xml ----------
if (-not $Version) {
    $pomFile = Join-Path $ProjectRoot "pom.xml"
    $pomContent = Get-Content $pomFile -Raw
    $noParent = [regex]::Replace($pomContent, '<parent>.*?</parent>', '', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    if ($noParent -match '<version>([^<]+)</version>') {
        $Version = $Matches[1] -replace '-SNAPSHOT', ''
    } else {
        $Version = "1.0.0"
    }
}
Write-Host "[1/5] Version : $Version" -ForegroundColor Yellow

# ---------- find JDK 21+ ----------
$requiredMajor = 21
$candidates = @()
if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
$candidates += "$env:USERPROFILE\.jdks\ms-21*"
$candidates += "$env:USERPROFILE\.jdks\corretto-21*"
$candidates += "$env:USERPROFILE\.jdks\*21*"
$candidates += "C:\Program Files\Java\jdk-21*"
$candidates += "C:\Program Files\Eclipse Adoptium\jdk-21*"
$candidates += "C:\Program Files\Microsoft\jdk-21*"
$candidates += "C:\Program Files\Amazon Corretto\jdk21*"

$foundJdk = $null
$prevEAP = $ErrorActionPreference
$ErrorActionPreference = "Continue"
try {
    foreach ($cand in $candidates) {
        $resolved = Get-ChildItem $cand -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($resolved) {
            $javaExe = Join-Path $resolved.FullName "bin\java.exe"
            if (Test-Path $javaExe) {
                $verLine = & $javaExe -version 2>&1 | Select-Object -First 1
                if ($verLine -match '"(\d+)\.') {
                    $major = [int]$Matches[1]
                    if ($major -ge $requiredMajor) {
                        $foundJdk = $resolved.FullName
                        Write-Host "      Found JDK $major at $foundJdk" -ForegroundColor Green
                        break
                    }
                }
            }
        }
    }
} finally { $ErrorActionPreference = $prevEAP }

if (-not $foundJdk) {
    $prevEAP = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $javaCmd = Get-Command java -ErrorAction SilentlyContinue
        if ($javaCmd) {
            $verLine = & java -version 2>&1 | Select-Object -First 1
            if ($verLine -match '"(\d+)\.') {
                $major = [int]$Matches[1]
                if ($major -ge $requiredMajor) {
                    $foundJdk = Split-Path (Split-Path $javaCmd.Source -Parent) -Parent
                } else {
                    Write-Host "WARN: java in PATH is $major, need JDK $requiredMajor+" -ForegroundColor DarkYellow
                }
            }
        }
    } finally { $ErrorActionPreference = $prevEAP }
}

if (-not $foundJdk) {
    Write-Host "ERROR: JDK $requiredMajor+ not found. Set JAVA_HOME or install from https://adoptium.net/" -ForegroundColor Red
    exit 1
}

$env:JAVA_HOME = $foundJdk
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path
$prevEAP = $ErrorActionPreference
$ErrorActionPreference = "Continue"
try { $javaVersion = & java -version 2>&1 | Select-Object -First 1 }
finally { $ErrorActionPreference = $prevEAP }
Write-Host "      JAVA_HOME: $env:JAVA_HOME" -ForegroundColor Gray
Write-Host "      java     : $javaVersion" -ForegroundColor Gray

# ---------- Maven build ----------
Write-Host ""
Write-Host "[2/5] Maven build" -ForegroundColor Yellow

$mvnArgs = @("clean", "package")
if ($SkipFrontend) { $mvnArgs += "-Pskip-frontend" }

Push-Location $ProjectRoot
try {
    & mvn @mvnArgs
    if ($LASTEXITCODE -ne 0) { Write-Host "ERROR: Maven build failed" -ForegroundColor Red; exit 1 }
} finally { Pop-Location }

$jarFile = Join-Path $TargetDir "easy-claw.jar"
if (-not (Test-Path $jarFile)) {
    $alt = Get-ChildItem $TargetDir -Filter "*.jar" | Where-Object { $_.Name -notlike "*-sources*" -and $_.Name -notlike "*-javadoc*" } | Select-Object -First 1
    if ($alt) { $jarFile = $alt.FullName }
    else { Write-Host "ERROR: built jar not found in $TargetDir" -ForegroundColor Red; exit 1 }
}
Write-Host "      JAR: $jarFile" -ForegroundColor Gray

# ---------- stage dist ----------
Write-Host ""
Write-Host "[3/5] Stage dist artifacts" -ForegroundColor Yellow
if (Test-Path $DistDir) { Remove-Item $DistDir -Recurse -Force }
New-Item -ItemType Directory -Path $DistDir | Out-Null

Copy-Item $jarFile (Join-Path $DistDir "easy-claw.jar") -Force

$runBat = @"
@echo off
chcp 65001 >nul 2>&1
cd /d "%~dp0"
java -Xmx2g -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -jar easy-claw.jar
pause
"@
$runBat | Out-File -FilePath (Join-Path $DistDir "run.bat") -Encoding ASCII

$readme = @"
Easy Claw - AI Work Assistant
=============================

Quick Start (app-image, no Java install needed):
  1. Extract the zip
  2. Double-click Easy-Claw\Easy-Claw.exe
  3. Open http://localhost:18080

Quick Start (fat jar, requires JDK 21+):
  1. Double-click run.bat (or: java -jar easy-claw.jar)
  2. Open http://localhost:18080

Data directory: %USERPROFILE%\.easyClaw\
"@
$readme | Out-File -FilePath (Join-Path $DistDir "README.txt") -Encoding UTF8

Write-Host "      dist dir: $DistDir" -ForegroundColor Green

# ---------- jpackage: app-image (default) or exe ----------
$pkgInput = Join-Path $TargetDir "pkg-input"
$pkgReady = $false

if ($Mode -ne "fatjar") {
    Write-Host ""
    Write-Host "[4/5] jpackage -> $Mode" -ForegroundColor Yellow

    $jp = Get-Command jpackage -ErrorAction SilentlyContinue
    if (-not $jp) {
        Write-Host "WARN: jpackage not found (part of JDK). Skipping native image, keeping fat jar." -ForegroundColor DarkYellow
        $Mode = "fatjar"
    }
}

if ($Mode -ne "fatjar") {
    if (Test-Path $pkgInput) { Remove-Item $pkgInput -Recurse -Force }
    New-Item -ItemType Directory -Path $pkgInput | Out-Null
    Copy-Item $jarFile (Join-Path $pkgInput "easy-claw.jar")

    # Spring Boot fat jar entrypoint is JarLauncher (reads MANIFEST's Start-Class)
    $mainClass = "org.springframework.boot.loader.launch.JarLauncher"
    $javaOpts = @(
        "-Xmx2g",
        "-Dfile.encoding=UTF-8",
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8",
        "-Dsun.stdout.encoding=UTF-8",
        "-Dsun.stderr.encoding=UTF-8",
        "-Dsun.jnu.encoding=UTF-8",
        "-Dspring.main.banner-mode=console"
    )

    $jpArgs = @(
        "--type", $Mode,
        "--name", $AppName,
        "--app-version", $Version,
        "--input", $pkgInput,
        "--main-jar", "easy-claw.jar",
        "--main-class", $mainClass,
        "--dest", $DistDir,
        "--description", "AgentScope 2.0 based AI work assistant",
        "--vendor", "Easy Claw"
    )
    foreach ($opt in $javaOpts) { $jpArgs += "--java-options"; $jpArgs += $opt }

    if ($Mode -eq "exe") {
        $jpArgs += "--win-menu"; $jpArgs += "--win-shortcut"; $jpArgs += "--win-dir-chooser"
    } else {
        # app-image: console mode so users see real output (not crypted "Failed to launch JVM")
        $jpArgs += "--win-console"
    }

    & jpackage @jpArgs
    if ($LASTEXITCODE -eq 0) {
        $pkgReady = $true
        if ($Mode -eq "exe") {
            Write-Host "      exe ready: $DistDir\$AppName-$Version.exe" -ForegroundColor Green
        } else {
            Write-Host "      app-image: $DistDir\$AppName\" -ForegroundColor Green
        }
    } else {
        Write-Host "WARN: jpackage failed (missing WiX?). fat jar kept." -ForegroundColor DarkYellow
        $Mode = "fatjar"
    }
}

# ---------- post-process: inject wrapper scripts & cfg tweaks ----------
if ($pkgReady -and $Mode -eq "app-image") {
    $appDir = Join-Path $DistDir $AppName

    # 1) Easy-Claw.bat wrapper: chcp 65001 for Chinese + log file + no-daemon
    #    Browser auto-open is handled by the JVM (BrowserLauncher.java), no hardcoded port here.
    $batContent = @"
@echo off
REM === Easy Claw Launcher ===
REM This wrapper:
REM   1. Switches console to UTF-8 (fixes Chinese garbled text)
REM   2. Creates log directory under %USERPROFILE%\.easyClaw\logs
REM   3. Runs Easy-Claw.exe in foreground (never as daemon)
REM   4. Browser opens automatically when server is ready (BrowserLauncher.java)
REM   5. Pauses so you can see startup errors
chcp 65001 >nul 2>&1
cd /d "%~dp0"

if not exist "%USERPROFILE%\.easyClaw\logs" mkdir "%USERPROFILE%\.easyClaw\logs"

echo Starting Easy-Claw...
echo   Log file : %USERPROFILE%\.easyClaw\logs\app.log
echo   Data dir : %USERPROFILE%\.easyClaw\
echo   Press Ctrl+C to stop.
echo.

Easy-Claw.exe >> "%USERPROFILE%\.easyClaw\logs\app.log" 2>&1
if errorlevel 1 (
    echo.
    echo Easy-Claw exited with error code %errorlevel%
    echo Check log: %USERPROFILE%\.easyClaw\logs\app.log
    pause
)
"@
    $batContent | Out-File -FilePath (Join-Path $appDir "Easy-Claw.bat") -Encoding ASCII

    # 2) Patch Easy-Claw.cfg: ensure all java-options propagate; add console encoding hint
    $cfgFile = Join-Path $appDir "app\Easy-Claw.cfg"
    if (Test-Path $cfgFile) {
        $cfg = Get-Content $cfgFile -Raw
        # log directory in cfg too (for exe direct-launch case)
        if ($cfg -notmatch "logging.file.path") {
            $cfg = $cfg -replace '\[JavaOptions\]', "[JavaOptions]`njava-options=-Dlogging.file.path=`$APPDIR\\..\\..\\logs"
            Set-Content -Path $cfgFile -Value $cfg -Encoding ASCII
        }
    }

    Write-Host "      + Easy-Claw.bat (UTF-8 + log + no-daemon)" -ForegroundColor Green
}

# ---------- zip app-image ----------
Write-Host ""
Write-Host "[5/5] Package for distribution" -ForegroundColor Yellow

if ($pkgReady -and $Mode -eq "app-image") {
    $zipPath = Join-Path $DistDir "$AppName-$Version-windows-x64.zip"
    if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
    $appDir = Join-Path $DistDir $AppName
    Compress-Archive -Path (Join-Path $appDir "*") -DestinationPath $zipPath -CompressionLevel Optimal
    Write-Host "      zip: $zipPath" -ForegroundColor Green
    $zipSize = [math]::Round((Get-Item $zipPath).Length / 1MB, 1)
    Write-Host "      size: ${zipSize} MB" -ForegroundColor Gray
} else {
    Write-Host "      (skipped, no app-image to zip)" -ForegroundColor DarkGray
}

if (Test-Path $pkgInput) { Remove-Item $pkgInput -Recurse -Force -ErrorAction SilentlyContinue }

# ---------- done ----------
Write-Host ""
Write-Host "=============================================" -ForegroundColor Green
Write-Host "  Build complete" -ForegroundColor Green
Write-Host "  Output: $DistDir" -ForegroundColor Green
Write-Host "=============================================" -ForegroundColor Green
