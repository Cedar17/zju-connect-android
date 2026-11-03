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

Add-Candidate $OhosSdkRoot
Add-Candidate $env:OHOS_SDK_ROOT
Add-Candidate 'C:\Program Files\Huawei\DevEco Studio\sdk\default\openharmony'
if (-not [string]::IsNullOrWhiteSpace($env:DEVECO_CLI_STUDIO_PATH)) {
    Add-Candidate (Join-Path $env:DEVECO_CLI_STUDIO_PATH "sdk\default\openharmony")
}
if (-not [string]::IsNullOrWhiteSpace($env:DEVECO_SDK_HOME)) {
    Add-Candidate (Join-Path $env:DEVECO_SDK_HOME "default\openharmony")
    Add-Candidate (Join-Path $env:DEVECO_SDK_HOME "sdk\default\openharmony")
}
Add-Candidate (Join-Path $RepositoryRoot "command-line-tools\sdk\default\openharmony")
Add-Candidate (Join-Path $env:LOCALAPPDATA "Huawei\Sdk\openharmony")

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
    Write-Host "Pass -OhosSdkRoot pointing to a directory with native\sysroot and native\llvm."
} else {
    Write-Host "[ok] OHOS SDK root: $sdkRoot"
    Write-Host "[ok] clang: $clangPath"
    Write-Host "[ok] sysroot: $(Join-Path $sdkRoot 'native\sysroot')"
    foreach ($tool in @('llvm-readelf.exe','llvm-nm.exe')) {
        $p = Join-Path $sdkRoot "native\llvm\bin\$tool"
        if (Test-Path -LiteralPath $p) {
            Write-Host "[ok] $tool"
        } else {
            Write-Host "[warn] $tool not found"
        }
    }
    Write-Host ""
    Write-Host ('$env:OHOS_SDK_ROOT = "' + $sdkRoot + '"')
}

if ([string]::IsNullOrWhiteSpace($BootstrapGoRoot)) {
    $BootstrapGoRoot = Join-Path $RepositoryRoot ".gomobile-toolchain\go"
}
$bootstrapGo = Join-Path $BootstrapGoRoot "bin\go.exe"
if (Test-Path -LiteralPath $bootstrapGo) {
    Write-Host "[ok] bootstrap Go: $((& $bootstrapGo version | Out-String).Trim())"
} else {
    $pathGo = Get-Command go.exe -ErrorAction SilentlyContinue
    if ($null -ne $pathGo) {
        Write-Host "[ok] Go on PATH: $((& $pathGo.Source version | Out-String).Trim())"
    } else {
        Write-Host "[missing] bootstrap Go (provide a Go installation >= 1.24.6)"
    }
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
if (Get-Command devecocli -ErrorAction SilentlyContinue) {
    Write-Host "[ok] devecocli: $(devecocli --version)"
} else {
    Write-Host "[warn] devecocli not on PATH"
}

if ($null -eq $sdkRoot) { exit 1 }
