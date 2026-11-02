param(
    [string]$OhosSdkRoot = $env:OHOS_SDK_ROOT,
    [string]$ToolchainRoot
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($ToolchainRoot)) {
    $ToolchainRoot = Join-Path $ProjectRoot ".toolchain\ohos-go"
}
if ([string]::IsNullOrWhiteSpace($OhosSdkRoot)) {
    throw "Set OHOS_SDK_ROOT to the OpenHarmony SDK root containing native\sysroot and native\llvm."
}

$GoExe = Join-Path $ToolchainRoot "bin\go.exe"
$NativeRoot = Join-Path $OhosSdkRoot "native"
$LlvmBin = Join-Path $NativeRoot "llvm\bin"
$Clang = Join-Path $LlvmBin "aarch64-unknown-linux-ohos-clang.exe"
$ClangXX = Join-Path $LlvmBin "aarch64-unknown-linux-ohos-clang++.exe"
if (-not (Test-Path -LiteralPath $Clang)) { $Clang = Join-Path $LlvmBin "clang.exe" }
if (-not (Test-Path -LiteralPath $ClangXX)) { $ClangXX = Join-Path $LlvmBin "clang++.exe" }
$ReadElf = Join-Path $LlvmBin "llvm-readelf.exe"
$Nm = Join-Path $LlvmBin "llvm-nm.exe"
$Sysroot = Join-Path $NativeRoot "sysroot"
$ProbeRoot = Join-Path $ProjectRoot "native\go-probe"
$BuildDirectory = Join-Path $ProjectRoot ".toolchain\out\arm64-v8a"
$OutputPath = Join-Path $BuildDirectory "libzju_go_probe.so"
$PackageDirectory = Join-Path $ProjectRoot "app\entry\libs\arm64-v8a"
$PackagePath = Join-Path $PackageDirectory "libzju_go_probe.so"

foreach ($required in @($GoExe, $Clang, $ClangXX, $ReadElf, $Nm, $Sysroot)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "Required path not found: $required"
    }
}

$goVersion = (& $GoExe version | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $goVersion -notmatch 'go1\.26\.5') {
    throw "Expected the pinned Go 1.26.5 OpenHarmony toolchain, got: $goVersion"
}
$targets = @(& $GoExe tool dist list)
if ($LASTEXITCODE -ne 0 -or $targets -notcontains "openharmony/arm64") {
    throw "The selected Go toolchain does not support openharmony/arm64."
}

# cgo parses CC as a command line. Convert SDK paths to 8.3 paths when possible
# so the default Windows installation path does not split at spaces.
if (-not ("ZjuShortPath" -as [type])) {
    Add-Type -TypeDefinition @"
using System.Runtime.InteropServices;
using System.Text;
public static class ZjuShortPath {
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    public static extern uint GetShortPathName(string longPath, StringBuilder shortPath, uint bufferLength);
}
"@
}
function Get-CompilerPath([string]$Path) {
    $buffer = New-Object System.Text.StringBuilder 4096
    $length = [ZjuShortPath]::GetShortPathName($Path, $buffer, [uint32]$buffer.Capacity)
    if ($length -gt 0 -and $length -lt $buffer.Capacity) {
        return $buffer.ToString()
    }
    return $Path
}

$Clang = Get-CompilerPath $Clang
$ClangXX = Get-CompilerPath $ClangXX
$Sysroot = Get-CompilerPath $Sysroot
if ($Clang.Contains(" ") -or $ClangXX.Contains(" ") -or $Sysroot.Contains(" ")) {
    throw "The DevEco SDK path contains spaces and Windows short paths are unavailable. Move the SDK to a path without spaces or enable 8.3 short names."
}

New-Item -ItemType Directory -Force -Path $BuildDirectory, $PackageDirectory | Out-Null

$oldEnvironment = @{}
foreach ($name in @("GOROOT", "GOOS", "GOARCH", "CGO_ENABLED", "CC", "CXX", "CGO_CFLAGS", "CGO_LDFLAGS", "GOTOOLCHAIN", "GOWORK", "PATH")) {
    $oldEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}

try {
    $env:GOROOT = $ToolchainRoot
    $env:PATH = "$(Join-Path $ToolchainRoot 'bin');$env:PATH"
    $env:GOOS = "openharmony"
    $env:GOARCH = "arm64"
    $env:CGO_ENABLED = "1"
    $env:CC = $Clang
    $env:CXX = $ClangXX
    $env:CGO_CFLAGS = "--target=aarch64-unknown-linux-ohos --sysroot=$Sysroot -D__MUSL__ -ftls-model=global-dynamic"
    $env:CGO_LDFLAGS = "--target=aarch64-unknown-linux-ohos --sysroot=$Sysroot -fuse-ld=lld -Wl,-z,lazy"
    $env:GOTOOLCHAIN = "local"
    $env:GOWORK = "off"

    Push-Location $ProbeRoot
    try {
        Write-Host "[build] $goVersion"
        Write-Host "[build] GOOS=$env:GOOS GOARCH=$env:GOARCH CGO_ENABLED=$env:CGO_ENABLED"
        & $GoExe build -buildmode=c-shared -trimpath -ldflags "-linkmode=external -extldflags=-Wl,-z,lazy" -o $OutputPath .
        if ($LASTEXITCODE -ne 0) {
            throw "Go c-shared build failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }

    $programHeaders = (& $ReadElf -l $OutputPath | Out-String)
    if ($LASTEXITCODE -ne 0 -or $programHeaders -notmatch '\bTLS\b') {
        throw "The generated library has no PT_TLS segment."
    }

    $relocations = (& $ReadElf -r $OutputPath | Out-String)
    if ($LASTEXITCODE -ne 0 -or $relocations -notmatch 'R_AARCH64_TLSDESC') {
        throw "The generated library has no R_AARCH64_TLSDESC relocation."
    }

    $symbols = (& $Nm -D $OutputPath | Out-String)
    foreach ($symbol in @("ZJUProbeVersion", "ZJUProbeAdd", "ZJUProbeFree")) {
        if ($symbols -notmatch "\b$symbol$") {
            throw "Required Go export is missing: $symbol"
        }
    }

    Copy-Item -LiteralPath $OutputPath -Destination $PackagePath -Force
    $hash = (Get-FileHash -LiteralPath $PackagePath -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Host "[verify] PT_TLS present"
    Write-Host "[verify] R_AARCH64_TLSDESC present"
    Write-Host "[verify] all three ZJUProbe exports present"
    Write-Host "[package] $PackagePath"
    Write-Host "[sha256] $hash"
}
finally {
    foreach ($name in $oldEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $oldEnvironment[$name], "Process")
    }
}
