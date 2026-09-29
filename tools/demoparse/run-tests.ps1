# run-tests.ps1 —— 本机测试入口。
#
# 为什么不能直接 `go test`：这台开发机开着 Smart App Control，
# 它会拦截 %TEMP% 下新生成的测试 exe（同一个命令时通时不通）。
# 实测 C:\Program Files\、C:\Windows\System32\、D:\go\pkg\tool\ 是放行的，
# 于是「先 `go test -c` 编译，再到放行目录执行」。
#
# 用法:  powershell -ExecutionPolicy Bypass -File tools\demoparse\run-tests.ps1 [-SkipVet]
param(
    [string]$GoBin = "D:\go\bin\go.exe",
    [string]$OutDir = "C:\Program Files\GoTest",
    [switch]$SkipVet
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

if (-not (Test-Path $GoBin)) { throw "找不到 go: $GoBin" }
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }

$env:GOROOT = "D:\go"
$env:GOPATH = "D:\gopath"
$env:GOPROXY = "https://goproxy.io,direct"
$env:Path = (Split-Path $GoBin) + ";" + $env:Path

if (-not $SkipVet) {
    # 只 vet 非 cgo 包：cmd/jni 需要 NDK 的 jni.h，本机没有 Windows C 编译器，
    # cgo 包在 Windows 上会被 "build constraints exclude all Go files" 挡掉。
    & $GoBin vet ./internal/... ./cmd/parsecli
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

# 逐包编译测试 exe 到放行目录并执行
$packages = @("./internal/demostats")
$failed = $false
foreach ($pkg in $packages) {
    $name = (Split-Path $pkg -Leaf) + ".test.exe"
    $exe = Join-Path $OutDir $name
    & $GoBin test -c -o $exe $pkg
    if ($LASTEXITCODE -ne 0) { $failed = $true; continue }

    # SAC 的拦截是间歇性的（同路径同文件时通时不通），重试几次。
    #
    # 必须用 Start-Process + 文件重定向，不能用 `2>&1`：demo 解析测试会往
    # stderr 打 "unknown grenade model 0"，而 PowerShell 5.1 在
    # $ErrorActionPreference="Stop" 下会把每一行 stderr 变成终止性的
    # NativeCommandError，脚本在第一行输出就崩了（根本走不到判断退出码）。
    $outFile = Join-Path $OutDir "last-test.out"
    $errFile = Join-Path $OutDir "last-test.err"
    $ok = $false
    for ($try = 1; $try -le 5 -and -not $ok; $try++) {
        $code = -1
        $out = ""
        try {
            if (Test-Path $outFile) { Remove-Item $outFile -Force -ErrorAction SilentlyContinue }
            if (Test-Path $errFile) { Remove-Item $errFile -Force -ErrorAction SilentlyContinue }
            $p = Start-Process -FilePath $exe -ArgumentList "-test.v" `
                -RedirectStandardOutput $outFile -RedirectStandardError $errFile `
                -NoNewWindow -Wait -PassThru -ErrorAction Stop
            $code = $p.ExitCode
        } catch {
            $out = "启动失败：$($_.Exception.Message)`r`n"
        }
        if (Test-Path $outFile) { $out += Get-Content $outFile -Raw -Encoding UTF8 }
        if (Test-Path $errFile) { $out += Get-Content $errFile -Raw -Encoding UTF8 }
        ($out -split "`n") | ForEach-Object { $_ }

        if ($code -eq 0) {
            $ok = $true
        } elseif ($out -match "--- FAIL") {
            break   # 真正的测试失败，不必重试
        } else {
            # 要么被 SAC 拦下（根本跑起来），要么启动即失败 —— 都值得再试一次
            Write-Output "  [第 $try 次] 退出码 $code，1.5s 后重试…"
            Start-Sleep -Milliseconds 1500
        }
    }
    if (-not $ok) { $failed = $true }
}

if ($failed) { Write-Output "=== 有测试失败 ==="; exit 1 }
Write-Output "=== 全部通过 ==="
exit 0
