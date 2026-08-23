[CmdletBinding()]
param(
    [string]$AndroidSdkRoot = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$ToolRoot
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$lockPath = Join-Path $projectRoot "tools\gomobile.lock.json"
$lock = Get-Content -LiteralPath $lockPath -Raw | ConvertFrom-Json

if ([string]::IsNullOrWhiteSpace($ToolRoot)) {
    $ToolRoot = Join-Path $projectRoot ".gomobile-toolchain"
}
if ([string]::IsNullOrWhiteSpace($AndroidSdkRoot)) {
    $AndroidSdkRoot = Join-Path $env:LOCALAPPDATA "Android\Sdk"
}
if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $JavaHome = Join-Path $env:ProgramFiles "Android\Android Studio\jbr"
}

function Assert-Hash {
    param(
        [string]$Path,
        [string]$Expected,
        [string]$Algorithm
    )

    $actual = (Get-FileHash -LiteralPath $Path -Algorithm $Algorithm).Hash.ToLowerInvariant()
    if ($actual -ne $Expected.ToLowerInvariant()) {
        throw "Checksum mismatch for $Path. Expected $Expected, got $actual."
    }
}

function Get-PinnedArchive {
    param(
        [string]$Url,
        [string]$Destination,
        [string]$ExpectedHash,
        [string]$HashAlgorithm
    )

    if (-not (Test-Path -LiteralPath $Destination -PathType Leaf)) {
        Invoke-WebRequest -Uri $Url -OutFile $Destination
    }
    Assert-Hash -Path $Destination -Expected $ExpectedHash -Algorithm $HashAlgorithm
}

$platformName = if ($null -eq $lock.android.compileSdkMinor) {
    "android-$($lock.android.compileSdk)"
} else {
    "android-$($lock.android.compileSdk).$($lock.android.compileSdkMinor)"
}
$requiredPlatform = Join-Path $AndroidSdkRoot "platforms\$platformName"
$requiredBuildTools = Join-Path $AndroidSdkRoot "build-tools\$($lock.android.buildTools)"
if (-not (Test-Path -LiteralPath $requiredPlatform -PathType Container)) {
    throw "Android SDK Platform $platformName is missing from $AndroidSdkRoot. Install it with Android Studio, then rerun."
}
if (-not (Test-Path -LiteralPath $requiredBuildTools -PathType Container)) {
    throw "Android Build Tools $($lock.android.buildTools) is missing from $AndroidSdkRoot. Install it with Android Studio, then rerun."
}
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome "bin\javac.exe") -PathType Leaf)) {
    throw "A JDK with javac.exe is required. Set -JavaHome or JAVA_HOME to Android Studio's JBR."
}

$downloadRoot = Join-Path $ToolRoot "downloads"
New-Item -ItemType Directory -Force -Path $downloadRoot | Out-Null

$goRoot = Join-Path $ToolRoot "go"
$goExe = Join-Path $goRoot "bin\go.exe"
if (-not (Test-Path -LiteralPath $goExe -PathType Leaf)) {
    $goArchive = Join-Path $downloadRoot "go$($lock.go.version).windows-amd64.zip"
    Get-PinnedArchive -Url $lock.go.archiveUrl -Destination $goArchive -ExpectedHash $lock.go.sha256 -HashAlgorithm "SHA256"
    Expand-Archive -LiteralPath $goArchive -DestinationPath $ToolRoot -Force
}
if (-not (Test-Path -LiteralPath $goExe -PathType Leaf)) {
    throw "Pinned Go archive did not produce $goExe."
}

$ndkRoot = Join-Path $AndroidSdkRoot "ndk\$($lock.android.ndkVersion)"
if (-not (Test-Path -LiteralPath $ndkRoot -PathType Container)) {
    $ndkArchive = Join-Path $downloadRoot "android-ndk-r29-windows.zip"
    Get-PinnedArchive -Url $lock.android.ndkArchiveUrl -Destination $ndkArchive -ExpectedHash $lock.android.ndkSha1 -HashAlgorithm "SHA1"

    $ndkExtractRoot = Join-Path $ToolRoot "extract"
    $expandedNdkRoot = Join-Path $ndkExtractRoot "android-ndk-r29"
    if (-not (Test-Path -LiteralPath $expandedNdkRoot -PathType Container)) {
        New-Item -ItemType Directory -Force -Path $ndkExtractRoot | Out-Null
        Expand-Archive -LiteralPath $ndkArchive -DestinationPath $ndkExtractRoot -Force
    }
    if (-not (Test-Path -LiteralPath $expandedNdkRoot -PathType Container)) {
        throw "Pinned NDK archive did not produce $expandedNdkRoot."
    }

    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ndkRoot) | Out-Null
    Move-Item -LiteralPath $expandedNdkRoot -Destination $ndkRoot
}
if (-not (Test-Path -LiteralPath $ndkRoot -PathType Container)) {
    throw "Pinned NDK was not installed at $ndkRoot."
}

$gopath = Join-Path $ToolRoot "gopath"
New-Item -ItemType Directory -Force -Path $gopath | Out-Null
$env:GOROOT = $goRoot
$env:GOPATH = $gopath
$env:GOTOOLCHAIN = "local"
$env:ANDROID_HOME = $AndroidSdkRoot
$env:ANDROID_NDK_HOME = $ndkRoot
$env:ANDROID_NDK_ROOT = $ndkRoot
$env:JAVA_HOME = $JavaHome
$env:PATH = "$(Join-Path $goRoot "bin");$(Join-Path $gopath "bin");$(Join-Path $JavaHome "bin");$env:PATH"

$goVersion = & $goExe version
if ($LASTEXITCODE -ne 0 -or $goVersion -notmatch "go$($lock.go.version)") {
    throw "Expected Go $($lock.go.version), got: $goVersion"
}

& $goExe install "$($lock.gomobile.module)@$($lock.gomobile.version)"
if ($LASTEXITCODE -ne 0) {
    throw "Unable to install pinned gomobile $($lock.gomobile.version)."
}

$gobindModule = $lock.gomobile.module -replace 'cmd/gomobile$', 'cmd/gobind'
& $goExe install "$gobindModule@$($lock.gomobile.version)"
if ($LASTEXITCODE -ne 0) {
    throw "Unable to install pinned gobind $($lock.gomobile.version)."
}

Write-Host "Pinned gomobile toolchain is ready."
Write-Host "Go: $goVersion"
Write-Host "NDK: $($lock.android.ndkVersion)"
Write-Host "gomobile: $(& (Join-Path $gopath "bin\gomobile.exe") version)"
