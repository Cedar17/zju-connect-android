param(
    [string]$BootstrapGoRoot
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$RepositoryRoot = Split-Path -Parent $ProjectRoot
$Lock = Get-Content -LiteralPath (Join-Path $ProjectRoot "toolchain.lock.json") -Raw | ConvertFrom-Json
$ToolchainRoot = Join-Path $ProjectRoot ".toolchain\ohos-go"
$GoExe = Join-Path $ToolchainRoot "bin\go.exe"

if (Test-Path -LiteralPath $GoExe) {
    $existingVersion = (& $GoExe version 2>$null | Out-String).Trim()
    $targets = @(& $GoExe tool dist list 2>$null)
    if ($LASTEXITCODE -eq 0 -and $existingVersion -match "go$([regex]::Escape($Lock.go.version))" -and $targets -contains "openharmony/arm64") {
        Write-Host "[toolchain] already ready: $existingVersion"
        exit 0
    }
}

if ([string]::IsNullOrWhiteSpace($BootstrapGoRoot)) {
    $candidate = Join-Path $RepositoryRoot ".gomobile-toolchain\go"
    if (Test-Path -LiteralPath (Join-Path $candidate "bin\go.exe")) {
        $BootstrapGoRoot = $candidate
    }
}

if ([string]::IsNullOrWhiteSpace($BootstrapGoRoot)) {
    $goCommand = Get-Command go.exe -ErrorAction SilentlyContinue
    if ($null -ne $goCommand) {
        $BootstrapGoRoot = (& $goCommand.Source env GOROOT).Trim()
    }
}

$BootstrapGoExe = if ([string]::IsNullOrWhiteSpace($BootstrapGoRoot)) { $null } else { Join-Path $BootstrapGoRoot "bin\go.exe" }
if ([string]::IsNullOrWhiteSpace($BootstrapGoExe) -or -not (Test-Path -LiteralPath $BootstrapGoExe)) {
    throw "Go bootstrap not found. Pass -BootstrapGoRoot or provision Go >= $($Lock.go.bootstrapMinimum). The repository's .gomobile-toolchain\go is the expected default."
}

$bootstrapVersionText = (& $BootstrapGoExe version | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $bootstrapVersionText -notmatch 'go version go(\d+)\.(\d+)\.(\d+)') {
    throw "Unable to read bootstrap Go version: $bootstrapVersionText"
}
$bootstrapVersion = [version]("$($Matches[1]).$($Matches[2]).$($Matches[3])")
$minimumVersion = [version]$Lock.go.bootstrapMinimum
if ($bootstrapVersion -lt $minimumVersion) {
    throw "Bootstrap Go $bootstrapVersionText is older than $minimumVersion."
}

$git = Get-Command git.exe -ErrorAction SilentlyContinue
if ($null -eq $git) {
    throw "git.exe is required to fetch the pinned OpenHarmony Go source."
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ToolchainRoot) | Out-Null
if (-not (Test-Path -LiteralPath (Join-Path $ToolchainRoot ".git"))) {
    if (Test-Path -LiteralPath $ToolchainRoot) {
        Remove-Item -LiteralPath $ToolchainRoot -Recurse -Force
    }
    & $git.Source clone --filter=blob:none --no-checkout $Lock.openHarmonyGo.repository $ToolchainRoot
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to clone the pinned OpenHarmony Go source."
    }
}

$head = (& $git.Source -C $ToolchainRoot rev-parse HEAD 2>$null | Out-String).Trim()
if ($head -ne $Lock.openHarmonyGo.commit) {
    & $git.Source -C $ToolchainRoot fetch --depth 1 origin $Lock.openHarmonyGo.commit
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to fetch OpenHarmony Go commit $($Lock.openHarmonyGo.commit)."
    }
    & $git.Source -C $ToolchainRoot checkout --detach FETCH_HEAD
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to check out the pinned OpenHarmony Go commit."
    }
}

$actualCommit = (& $git.Source -C $ToolchainRoot rev-parse HEAD | Out-String).Trim()
if ($actualCommit -ne $Lock.openHarmonyGo.commit) {
    throw "OpenHarmony Go source commit mismatch: expected $($Lock.openHarmonyGo.commit), got $actualCommit."
}

$sourceVersion = (Get-Content -LiteralPath (Join-Path $ToolchainRoot "VERSION") -Raw).Trim()
if ($sourceVersion -ne "go$($Lock.go.version)") {
    throw "Pinned source version mismatch: expected go$($Lock.go.version), got $sourceVersion."
}

$oldBootstrap = $env:GOROOT_BOOTSTRAP
$env:GOROOT_BOOTSTRAP = $BootstrapGoRoot
try {
    Push-Location (Join-Path $ToolchainRoot "src")
    try {
        Write-Host "[toolchain] building $sourceVersion from $actualCommit"
        & cmd.exe /d /c make.bat
        if ($LASTEXITCODE -ne 0) {
            throw "OpenHarmony Go make.bat failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }
}
finally {
    if ($null -eq $oldBootstrap) {
        Remove-Item Env:GOROOT_BOOTSTRAP -ErrorAction SilentlyContinue
    } else {
        $env:GOROOT_BOOTSTRAP = $oldBootstrap
    }
}

$builtVersion = (& $GoExe version | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $builtVersion -notmatch "go$([regex]::Escape($Lock.go.version))") {
    throw "Built toolchain version mismatch: $builtVersion"
}
$targets = @(& $GoExe tool dist list)
if ($LASTEXITCODE -ne 0 -or $targets -notcontains "openharmony/arm64") {
    throw "Built Go toolchain does not expose openharmony/arm64."
}

Write-Host "[toolchain] ready: $builtVersion"
Write-Host "[toolchain] root: $ToolchainRoot"
