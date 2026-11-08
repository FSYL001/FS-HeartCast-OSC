# Build FS-HeartCast-OSC (Android) without Gradle / Android Studio.
#
# 只需要三样东西：
#   1. JDK 17
#   2. Android SDK build-tools（aapt2 / d8 / zipalign / apksigner）
#   3. Android SDK platform android-34（android.jar）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\build-apk.ps1
#   powershell -ExecutionPolicy Bypass -File .\build-apk.ps1 -AndroidHome "D:\Android\Sdk" -JdkHome "C:\Program Files\Java\jdk-17"
#
# 也可以用 Android Studio 直接打开本目录（Gradle 文件已附带）。

[CmdletBinding()]
param(
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$OutDir,
    [string]$VersionName = "1.2.1",
    [int]$VersionCode = 5,
    [int]$MinSdk = 26,
    [int]$TargetSdk = 34
)

$ErrorActionPreference = "Stop"

function Fail([string]$message) {
    Write-Host ""
    Write-Host "构建失败：$message" -ForegroundColor Red
    exit 1
}

function Invoke-Tool([string]$exe, [string[]]$arguments) {
    Write-Host "> $([System.IO.Path]::GetFileName($exe)) $($arguments -join ' ')" -ForegroundColor DarkGray
    & $exe @arguments
    if ($LASTEXITCODE -ne 0) {
        Fail("$([System.IO.Path]::GetFileName($exe)) 退出码 $LASTEXITCODE")
    }
}

function Find-First([string[]]$paths) {
    foreach ($p in $paths) {
        if ($p -and (Test-Path $p)) { return $p }
    }
    return $null
}

# ----------------------------------------------------------------- 定位工具链

$sdkRoots = @()
if ($AndroidHome) { $sdkRoots += $AndroidHome }
if ($env:ANDROID_SDK_ROOT) { $sdkRoots += $env:ANDROID_SDK_ROOT }
$sdkRoots += (Join-Path $env:TEMP "android-tc\sdk")
$sdkRoots += (Join-Path $env:TEMP "android-tc")

$aapt2 = $null
$d8 = $null
$zipalign = $null
$apksigner = $null
$androidJar = $null
$btDir = $null
$platformDir = $null

foreach ($root in $sdkRoots) {
    if (-not $root -or -not (Test-Path $root)) { continue }

    if (-not $btDir) {
        $btDir = Find-First @(
            (Join-Path $root "build-tools\34.0.0"),
            (Join-Path $root "bt\android-14"),
            (Join-Path $root "bt")
        )
    }
    if (-not $platformDir) {
        $platformDir = Find-First @(
            (Join-Path $root "platforms\android-34"),
            (Join-Path $root "plat\android-34")
        )
    }
    if ($btDir -and $platformDir) { break }
}

if ($btDir) {
    $aapt2 = Join-Path $btDir "aapt2.exe"
    $d8 = Join-Path $btDir "d8.bat"
    $zipalign = Join-Path $btDir "zipalign.exe"
    $apksigner = Join-Path $btDir "apksigner.bat"
}
if ($platformDir) {
    $androidJar = Join-Path $platformDir "android.jar"
}

if (-not (Test-Path $aapt2)) { Fail "找不到 aapt2.exe。请安装 Android SDK build-tools，或用 -AndroidHome 指定 SDK 目录。" }
if (-not (Test-Path $d8)) { Fail "找不到 d8.bat。" }
if (-not (Test-Path $zipalign)) { Fail "找不到 zipalign.exe。" }
if (-not (Test-Path $apksigner)) { Fail "找不到 apksigner.bat。" }
if (-not (Test-Path $androidJar)) { Fail "找不到 android.jar（platforms/android-34）。" }

$jdkRoot = $null
if ($JdkHome -and (Test-Path (Join-Path $JdkHome "bin\javac.exe"))) {
    $jdkRoot = $JdkHome
} else {
    $candidates = @()
    $tmpJdk = Join-Path $env:TEMP "android-tc\jdk"
    if (Test-Path $tmpJdk) {
        $candidates += (Get-ChildItem $tmpJdk -Directory | ForEach-Object { $_.FullName })
    }
    foreach ($c in $candidates) {
        if (Test-Path (Join-Path $c "bin\javac.exe")) { $jdkRoot = $c; break }
    }
}
if (-not $jdkRoot) { Fail "找不到 JDK 17。请设置 JAVA_HOME 或用 -JdkHome 指定。" }

$javac = Join-Path $jdkRoot "bin\javac.exe"
$keytool = Join-Path $jdkRoot "bin\keytool.exe"
$env:JAVA_HOME = $jdkRoot
$env:PATH = (Join-Path $jdkRoot "bin") + ";" + $env:PATH

Write-Host "JDK          : $jdkRoot"
Write-Host "build-tools  : $btDir"
Write-Host "android.jar  : $androidJar"
Write-Host ""

# ----------------------------------------------------------------- 准备目录

$src = Join-Path $PSScriptRoot "app\src\main"
$build = Join-Path $PSScriptRoot "build"
if (-not $OutDir) { $OutDir = Join-Path $build "output" }

if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Force -Path $build | Out-Null
$genDir = Join-Path $build "gen"
$classDir = Join-Path $build "classes"
$dexDir = Join-Path $build "dex"
New-Item -ItemType Directory -Force -Path $genDir, $classDir, $dexDir, $OutDir | Out-Null

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

# aapt2 需要 manifest 里有 package 属性（Gradle 走 namespace，这里手动补上）
$manifestPath = Join-Path $build "AndroidManifest.xml"
$manifest = [System.IO.File]::ReadAllText((Join-Path $src "AndroidManifest.xml"))
if ($manifest -notmatch 'package\s*=') {
    $manifest = [regex]::Replace($manifest, '<manifest\s', '<manifest package="com.fsheartcast.osc" ', 1)
}
[System.IO.File]::WriteAllText($manifestPath, $manifest, $utf8NoBom)

# ----------------------------------------------------------------- 资源 + R.java

$resZip = Join-Path $build "res.zip"
Invoke-Tool $aapt2 @("compile", "--dir", (Join-Path $src "res"), "-o", $resZip)

$baseApk = Join-Path $build "base.apk"
Invoke-Tool $aapt2 @(
    "link", "-o", $baseApk,
    "-I", $androidJar,
    "--manifest", $manifestPath,
    "-R", $resZip,
    "--java", $genDir,
    "--auto-add-overlay",
    "--min-sdk-version", $MinSdk.ToString(),
    "--target-sdk-version", $TargetSdk.ToString(),
    "--version-code", $VersionCode.ToString(),
    "--version-name", $VersionName
)

# ----------------------------------------------------------------- 编译 Java

$sources = @()
$sources += (Get-ChildItem (Join-Path $src "java") -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$sources += (Get-ChildItem $genDir -Recurse -Filter *.java | ForEach-Object { $_.FullName })
if ($sources.Count -eq 0) { Fail "没有找到 Java 源文件。" }

Invoke-Tool $javac (@(
    "-encoding", "UTF-8",
    "-source", "11", "-target", "11",
    "-classpath", $androidJar,
    "-d", $classDir
) + $sources)

# ----------------------------------------------------------------- dex
# 先把 class 打包成 jar 再交给 d8：类多时直接传路径会超过 Windows 命令行长度上限

$jarTool = Join-Path $jdkRoot "bin\jar.exe"
$classesJar = Join-Path $build "classes.jar"
if (Test-Path $classesJar) { Remove-Item $classesJar -Force }
Invoke-Tool $jarTool @("cf", $classesJar, "-C", $classDir, ".")

Invoke-Tool $d8 (@("--lib", $androidJar, "--min-api", $MinSdk.ToString(), "--output", $dexDir, $classesJar))

$dexFile = Join-Path $dexDir "classes.dex"
if (-not (Test-Path $dexFile)) { Fail "d8 没有产出 classes.dex。" }

# 把 classes.dex 塞进 APK
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($baseApk, "Update")
try {
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, $dexFile, "classes.dex", [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
} finally {
    $zip.Dispose()
}

# ----------------------------------------------------------------- 对齐 + 签名

$alignedApk = Join-Path $build "aligned.apk"
Invoke-Tool $zipalign @("-f", "-p", "4", $baseApk, $alignedApk)

$keystore = Join-Path $PSScriptRoot "debug.keystore"
if (-not (Test-Path $keystore)) {
    Write-Host "生成调试签名密钥…"
    Invoke-Tool $keytool @(
        "-genkeypair",
        "-keystore", $keystore,
        "-alias", "androiddebugkey",
        "-storepass", "android",
        "-keypass", "android",
        "-keyalg", "RSA",
        "-keysize", "2048",
        "-validity", "10000",
        "-dname", "CN=FS-HeartCast-OSC,O=HOSC,C=CN"
    )
}

$apkName = "FS-HeartCast-OSC-$VersionName-debug.apk"
$apkPath = Join-Path $OutDir $apkName

Invoke-Tool $apksigner @(
    "sign",
    "--ks", $keystore,
    "--ks-pass", "pass:android",
    "--key-pass", "pass:android",
    "--v1-signing-enabled", "true",
    "--v2-signing-enabled", "true",
    "--out", $apkPath,
    $alignedApk
)

Invoke-Tool $apksigner @("verify", "--verbose", $apkPath)

$size = (Get-Item $apkPath).Length
$hash = (Get-FileHash $apkPath -Algorithm SHA256).Hash

Write-Host ""
Write-Host "构建完成" -ForegroundColor Green
Write-Host "  APK  : $apkPath"
Write-Host "  大小 : $([math]::Round($size / 1KB, 1)) KB"
Write-Host "  SHA256: $hash"
Write-Host ""
Write-Host "安装：adb install -r `"$apkPath`"（或把 APK 传到手机上点击安装）"
