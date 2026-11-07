# 从桌面版 AppIcon.png 生成 Android 图标资源（自适应图标前景 + 应用内标记）。
#
#   powershell -ExecutionPolicy Bypass -File .\tools\gen-icons.ps1
#   powershell -ExecutionPolicy Bypass -File .\tools\gen-icons.ps1 -ArtRatio 0.55
#
# 关于安全区：
#   Android 自适应图标画布 108dp，保证可见的是中间 66dp 的圆。
#   本脚本让元素（手表+信号弧+OSC 线）只占画布高度的 ArtRatio（默认 0.58），
#   并按元素包围盒居中，四周留白约 29% / 22%，
#   无论是圆形还是各家 ROM 的圆角矩形遮罩都不会裁到元素。

[CmdletBinding()]
param(
    [string]$SourcePng = (Join-Path $PSScriptRoot "..\..\HOSC\Assets\AppIcon.png"),
    [double]$ArtRatio = 0.58,
    [string]$ResourcesDir = (Join-Path $PSScriptRoot "..\app\src\main\res")
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

if (-not (Test-Path $SourcePng)) {
    throw "找不到桌面版图标：$SourcePng"
}

$logo = [System.Drawing.Bitmap]::FromFile((Resolve-Path $SourcePng))
$tile = [System.Drawing.Color]::FromArgb(255, 0x11, 0x13, 0x18)

# 圆角方块本体（裁掉透明外边与底部字标）
$srcRect = New-Object System.Drawing.Rectangle 90, 90, 842, 750
# 元素包围盒在裁切图中的中心比例（源坐标 304..848，相对 90 为 214..758）
$artCenterRatio = 486.0 / 842.0

function Save-Composition([int]$size, [double]$ratio, [string]$path, [bool]$roundedChip) {
    $bmp = New-Object System.Drawing.Bitmap $size, $size
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.Clear($tile)

    if ($roundedChip) {
        $clip = New-Object System.Drawing.Drawing2D.GraphicsPath
        $r = [int]($size * 0.22); $d = $r * 2
        $clip.AddArc(0, 0, $d, $d, 180, 90)
        $clip.AddArc($size - $d, 0, $d, $d, 270, 90)
        $clip.AddArc($size - $d, $size - $d, $d, $d, 0, 90)
        $clip.AddArc(0, $size - $d, $d, $d, 90, 90)
        $clip.CloseFigure()
        $g.SetClip($clip)
        $g.FillRectangle((New-Object System.Drawing.SolidBrush $tile), 0, 0, $size, $size)
        $g.ResetClip()
    }

    $h = [int]($size * $ratio)
    $w = [int]($h * 842.0 / 750.0)
    $x = [int]($size / 2.0 - $artCenterRatio * $w)
    $y = [int](($size - $h) / 2)
    $g.DrawImage($logo, (New-Object System.Drawing.Rectangle $x, $y, $w, $h), $srcRect, [System.Drawing.GraphicsUnit]::Pixel)

    $g.Dispose()
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
}

# 自适应图标前景（108dp × 密度）
$densities = @{ 'mdpi' = 1.0; 'hdpi' = 1.5; 'xhdpi' = 2.0; 'xxhdpi' = 3.0; 'xxxhdpi' = 4.0 }
foreach ($key in $densities.Keys) {
    $canvas = [int](108 * $densities[$key])
    $dir = Join-Path $ResourcesDir "mipmap-$key"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    Save-Composition $canvas $ArtRatio (Join-Path $dir "ic_launcher_foreground.png") $false
}

# 应用内标记 32dp / 44dp（@3x）
$drawable = Join-Path $ResourcesDir "drawable-xxhdpi"
New-Item -ItemType Directory -Force -Path $drawable | Out-Null
Save-Composition 96 0.74 (Join-Path $drawable "ic_logo.png") $true
Save-Composition 132 0.74 (Join-Path $drawable "ic_logo_about.png") $true

# API 26 以下才会用到的旧式图标，保持同一构图
$legacy = @{ 'mdpi' = 48; 'hdpi' = 72; 'xhdpi' = 96; 'xxhdpi' = 144; 'xxxhdpi' = 192 }
foreach ($key in $legacy.Keys) {
    $size = $legacy[$key]
    $dir = Join-Path $ResourcesDir "mipmap-$key"
    Save-Composition $size 0.70 (Join-Path $dir "ic_launcher.png") $false
    Save-Composition $size 0.70 (Join-Path $dir "ic_launcher_round.png") $false
}

$logo.Dispose()
Write-Host ("图标已生成（元素占比 {0:P0}）。" -f $ArtRatio)
