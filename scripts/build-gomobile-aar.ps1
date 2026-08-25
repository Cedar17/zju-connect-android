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

$goRoot = Join-Path $ToolRoot "go"
$goExe = Join-Path $goRoot "bin\go.exe"
$gopath = Join-Path $ToolRoot "gopath"
$gomobileExe = Join-Path $gopath "bin\gomobile.exe"
$ndkRoot = Join-Path $AndroidSdkRoot "ndk\$($lock.android.ndkVersion)"
$aarPath = Join-Path $projectRoot "app\libs\zju-connect-core.aar"
$receiptPath = Join-Path $projectRoot "app\libs\zju-connect-core.build-info.json"

foreach ($requiredPath in @($goExe, $gomobileExe, $ndkRoot, (Join-Path $JavaHome "bin\javac.exe"))) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "Missing $requiredPath. Run scripts\bootstrap-gomobile-toolchain.ps1 first."
    }
}

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

$bridgeDir = Join-Path $projectRoot "go\bridge"
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $aarPath) | Out-Null

Push-Location $bridgeDir
try {
    # Migration-spike exception: let CI materialize the upstream-main module
    # graph so compilation can expose API blockers before this draft is made
    # merge-ready. Restore the normal `go mod tidy -diff` gate before merge.
    & $goExe mod tidy
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to resolve the Go module graph for the upstream migration spike."
    }
    & $goExe mod download
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to download pinned Go modules."
    }
    & $goExe mod verify
    if ($LASTEXITCODE -ne 0) {
        throw "Pinned Go module verification failed."
    }

    $bindArguments = @(
        "bind",
        "-target", "android/arm64,android/amd64",
        "-androidapi", $lock.android.androidApi,
        "-javapkg", "io.github.cedar17.zjuconnect.gocore",
        "-trimpath",
        "-o", $aarPath,
        "."
    )
    & $gomobileExe @bindArguments
    if ($LASTEXITCODE -ne 0) {
        throw "gomobile bind failed."
    }
} finally {
    Pop-Location
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($aarPath)
try {
    $entries = $archive.Entries.FullName
} finally {
    $archive.Dispose()
}
foreach ($entry in @("classes.jar", "jni/arm64-v8a/libgojni.so", "jni/x86_64/libgojni.so")) {
    if ($entries -notcontains $entry) {
        throw "Generated AAR is missing $entry."
    }
}

$receipt = [ordered]@{
    schemaVersion = $lock.schemaVersion
    aar = "app/libs/zju-connect-core.aar"
    aarSha256 = (Get-FileHash -LiteralPath $aarPath -Algorithm SHA256).Hash.ToLowerInvariant()
    goVersion = $lock.go.version
    gomobileVersion = $lock.gomobile.version
    gomobileCommit = $lock.gomobile.commit
    zjuConnectUpstreamVersion = $lock.zjuConnect.upstreamVersion
    zjuConnectUpstreamCommit = $lock.zjuConnect.upstreamCommit
    ndkVersion = $lock.android.ndkVersion
    androidApi = $lock.android.androidApi
    abis = $lock.android.abis
}
$receipt | ConvertTo-Json | Set-Content -LiteralPath $receiptPath -Encoding utf8

Write-Host "Generated $aarPath"
Write-Host "SHA-256: $($receipt.aarSha256)"
