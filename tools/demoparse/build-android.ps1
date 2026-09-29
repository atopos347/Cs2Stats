# build-android.ps1 —— 把 Go 解析器交叉编译成安卓原生库 libcs2demo.so。
#
# 前置条件：
#   * Go 1.24+（D:\go）
#   * Android NDK r27c（D:\android-sdk\ndk\27.2.12479018），里面有 android/arm64 的 clang
#
# 产出直接写进工程的 jniLibs 目录，Gradle 打包时自动带上，**不需要 CMake / externalNativeBuild**。
param(
    [string]$GoBin = "D:\go\bin\go.exe",
    [string]$Ndk  = "D:\android-sdk\ndk\27.2.12479018",
    [string]$Out  = (Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) "app\src\main\jniLibs\arm64-v8a\libcs2demo.so")
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$clang = Join-Path $Ndk "toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android26-clang.cmd"
if (-not (Test-Path $clang)) { throw "找不到 NDK clang: $clang" }
if (-not (Test-Path $GoBin)) { throw "找不到 go: $GoBin" }

# 保存并替换受影响的环境变量
$save = @{}
foreach ($k in @('GOOS', 'GOARCH', 'CGO_ENABLED', 'CC', 'GOROOT', 'GOPATH', 'GOPROXY')) {
    $save[$k] = [Environment]::GetEnvironmentVariable($k, 'Process')
}
try {
    $env:GOROOT = "D:\go"
    $env:GOPATH = "D:\gopath"
    $env:GOPROXY = "https://goproxy.io,direct"
    $env:GOOS = "android"
    $env:GOARCH = "arm64"
    $env:CGO_ENABLED = "1"
    $env:CC = $clang

    $outDir = Split-Path $Out -Parent
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null

    & $GoBin build -v -buildmode=c-shared -o $Out ./cmd/jni
    if ($LASTEXITCODE -ne 0) { throw "交叉编译失败（exit=$LASTEXITCODE）" }
} finally {
    foreach ($k in $save.Keys) {
        if ($null -eq $save[$k]) { [Environment]::SetEnvironmentVariable($k, $null, 'Process') }
        else { [Environment]::SetEnvironmentVariable($k, $save[$k], 'Process') }
    }
}

$f = Get-Item $Out
Write-Output ("OK  {0}  ({1:N0} 字节)" -f $f.FullName, $f.Length)

# 顺手确认导出的 JNI 符号都在
$nm = Join-Path $Ndk "toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-nm.exe"
if (Test-Path $nm) {
    $syms = & $nm -D --defined-only $Out 2>&1 | Out-String
    foreach ($want in @('parseJson', 'progress', 'cancel')) {
        if ($syms -match "Java_com_cs2stats_app_data_demo_DemoNative_$want") { "  符号 OK: $want" }
        else { throw "缺少 JNI 导出符号: $want" }
    }
}
exit 0
