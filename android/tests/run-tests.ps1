# 纯 JVM 单元测试：OSC 编码 + 心率解析（不需要 Android SDK，只需要 JDK）。
#
#   powershell -ExecutionPolicy Bypass -File .\tests\run-tests.ps1

[CmdletBinding()]
param(
    [string]$JdkHome = $env:JAVA_HOME
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot

function Find-Jdk([string]$hint) {
    if ($hint -and (Test-Path (Join-Path $hint "bin\javac.exe"))) { return $hint }
    $tmp = Join-Path $env:TEMP "android-tc\jdk"
    if (Test-Path $tmp) {
        foreach ($d in (Get-ChildItem $tmp -Directory)) {
            if (Test-Path (Join-Path $d.FullName "bin\javac.exe")) { return $d.FullName }
        }
    }
    return $null
}

$jdk = Find-Jdk $JdkHome
if (-not $jdk) {
    Write-Host "找不到 JDK，请设置 JAVA_HOME 或用 -JdkHome 指定。" -ForegroundColor Red
    exit 1
}

$javac = Join-Path $jdk "bin\javac.exe"
$java = Join-Path $jdk "bin\java.exe"

$out = Join-Path $PSScriptRoot "out"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out | Out-Null

$sources = @(
    (Join-Path $root "app\src\main\java\com\fsheartcast\osc\HeartRateParser.java"),
    (Join-Path $root "app\src\main\java\com\fsheartcast\osc\OscMessage.java"),
    (Join-Path $root "app\src\main\java\com\fsheartcast\osc\OscParams.java"),
    (Join-Path $PSScriptRoot "src\com\fsheartcast\osc\TestMain.java")
)

foreach ($s in $sources) {
    if (-not (Test-Path $s)) {
        Write-Host "缺少源文件：$s" -ForegroundColor Red
        exit 1
    }
}

& $javac -encoding UTF-8 -d $out @sources
if ($LASTEXITCODE -ne 0) {
    Write-Host "编译测试代码失败。" -ForegroundColor Red
    exit 1
}

& $java -cp $out com.fsheartcast.osc.TestMain
exit $LASTEXITCODE
