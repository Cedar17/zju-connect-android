param(
    [string]$OhosSdkRoot,
    [string]$BootstrapGoRoot
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$RepositoryRoot = Split-Path -Parent $ProjectRoot

$candidates = [System.Collections.Generic.List[string]]::new()
function Add-Candidate([string]$Path) {
    if ([string]::IsNullOrWhiteSpace($Path)) { return }
    $full = [System.IO.Path]::GetFullPath($Path)
    if (-not $candidates.Contains($full)) { $candidates.Add($full) }
}

if (-not [string]::IsNullOrWhiteSpace($OhosSdkRoot)) {
    Add-Candidate $OhosSdkRoot
}
Add-Candidate $env:OHOS_SDK_ROOT
if (-not [string]::IsNullOrWhiteSpace($env:DEVECO_SDK_HOME)) {
    Add-Candidate $env:DEVECO_SDK_HOME
    Add-Candidate (Join-Path $env:DEVECO_SDK_HOME "default\openharmony")
    Add-Candidate (Join-Path $env:DEVECO_SDK_HOME "sdk\default\openharmony")
}
Add-Candidate (Join-Path $RepositoryRoot "command-line-tools\sdk\default\openharmony")
Add-Candidate (Join-Path $RepositoryRoot "command-line-tools\command-line-tools\sdk\default\openharmony")
Add-Candidate (Join-Path $env:USERPROFILE "Downloads\command-line-tools\sdk\default\openharmony")
Add-Candidate (Join-Path $env:USERPROFILE "Downloads\command-line-tools\command-line-tools\sdk\default\openharmony")

$localSdk = Join-Path $env:LOCALAPPDATA "Huawei\Sdk\openharmony"
if (Test-Path -LiteralPath $localSdk) {
    Add-Candidate $localSdk
    Get-ChildItem -LiteralPath $localSdk -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { Add-Candidate $_.FullName }
}

$sdkRoot = $null
$clangPath = $null
foreach ($candidate in $candidates) {
    $bin = Join-Path $candidate "native\llvm\bin"
    $clangCandidates = @(
        (Join-Path $bin "aarch64-unknown-linux-ohos-clang.exe"),
        (Join-Path $bin "clang.exe")
    )
    $clang = $clangCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if ((Test-Path -LiteralPath (Join-Path $candidate "native\sysroot")) -and $null -ne $clang) {
        $sdkRoot = $candidate
        $clangPath = $clang
        break
    }
}

Write-Host "=== ZJU Connect HarmonyOS environment ==="
if ($null -eq $sdkRoot) {
    Write-Host "[missing] HarmonyOS SDK native toolchain"
    Write-Host "Extract DevEco/Command Line Tools and install the OpenHarmony native SDK. Pass -OhosSdkRoot with the directory containing native\sysroot and native\llvm."
} else {
    Write-Host "[ok] OHOS SDK root: $sdkRoot"
    Write-Host "[ok] clang: $clangPath"
    Write-Host "[ok] sysroot: $(Join-Path $sdkRoot 'native\sysroot')"
    $readElf = Join-Path $sdkRoot "native\llvm\bin\llvm-readelf.exe"
    $nm = Join-Path $sdkRoot "native\llvm\bin\llvm-nm.exe"
    if (Test-Path -LiteralPath $readElf) { Write-Host "[ok] llvm-readelf.exe" } else { Write-Host "[warn] llvm-readelf.exe not found" }
    if (Test-Path -LiteralPath $nm) { Write-Host "[ok] llvm-nm.exe" } else { Write-Host "[warn] llvm-nm.exe not found" }
    Write-Host ""
    Write-Host "Set for this PowerShell session:"
    Write-Host ('$env:OHOS_SDK_ROOT = "' + $sdkRoot + '"')
}

if ([string]::IsNullOrWhiteSpace($BootstrapGoRoot)) {
    $BootstrapGoRoot = Join-Path $RepositoryRoot ".gomobile-toolchain\go"
}
$bootstrapGo = Join-Path $BootstrapGoRoot "bin\go.exe"
if (Test-Path -LiteralPath $bootstrapGo) {
    $version = (& $bootstrapGo version | Out-String).Trim()
    Write-Host "[ok] bootstrap Go: $version"
} else {
    Write-Host "[missing] bootstrap Go at $bootstrapGo"
}

$ohosGo = Join-Path $ProjectRoot ".toolchain\ohos-go\bin\go.exe"
if (Test-Path -LiteralPath $ohosGo) {
    $version = (& $ohosGo version | Out-String).Trim()
    $targets = @(& $ohosGo tool dist list)
    if ($targets -contains "openharmony/arm64") {
        Write-Host "[ok] OpenHarmony Go: $version (openharmony/arm64)"
    } else {
        Write-Host "[warn] Go exists but openharmony/arm64 is not in dist list"
    }
} else {
    Write-Host "[pending] OpenHarmony Go toolchain has not been built yet"
}

if (Get-Command git.exe -ErrorAction SilentlyContinue) {
    Write-Host "[ok] $(git --version)"
} else {
    Write-Host "[missing] git.exe"
}

if ($null -eq $sdkRoot) { exit 1 }
