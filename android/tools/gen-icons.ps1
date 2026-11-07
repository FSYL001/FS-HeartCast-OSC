# 从桌面版 AppIcon.png 生成 Android 图标资源。
#
#   powershell -ExecutionPolicy Bypass -File .\tools\gen-icons.ps1
#   powershell -ExecutionPolicy Bypass -File .\tools\gen-icons.ps1 -LogoScale 0.50
#
# 用的是**完整原始图标**（圆角方块 + 手表 + 信号弧 + OSC 线 + 底部 "FS- Heart Cast -OSC" 字标），
# 按中心等比缩小放进自适应图标画布，不做任何裁剪。
#
# 安全区计算：
#   自适应图标画布 108dp，可见区域近似中间 72dp 的圆（半径 33.3% 画布）。
#   完整图标内容（含字标）相对圆角方块中心的最远点距离为 531px / 842px = 0.631 倍方块边长，
#   所以 0.631 × LogoScale ≤ 0.333 → LogoScale ≈ 0.528；
#   默认取 0.52，保证圆形 / 圆角矩形（squircle）遮罩都裁不到任何元素。

[CmdletBinding()]
param(
    [string]$SourcePng = (Join-Path $PSScriptRoot "..\..\HOSC\Assets\AppIcon.png"),
    [double]$LogoScale = 0.52,
    [double]$InAppScale = 0.88,
    [string]$ResourcesDir = (Join-Path $PSScriptRoot "..\app\src\main\res")
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

if (-not (Test-Path $SourcePng)) {
    throw "找不到桌面版图标：$SourcePng"
}

$logo = [System.Drawing.Bitmap]::FromFile((Resolve-Path $SourcePng))
$tile = [System.Drawing.Color]::FromArgb(255, 0x11, 0x13, 0x18)

# 圆角方块本体（90..932），包含底部字标；四周透明外边不要
$srcRect = New-Object System.Drawing.Rectangle 90, 90, 842, 842

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

    # 以画布中心为原点等比缩放
    $side = [int]($size * $ratio)
    $x = [int](($size - $side) / 2)
    $y = [int](($size - $side) / 2)
    $g.DrawImage($logo, (New-Object System.Drawing.Rectangle $x, $y, $side, $side), $srcRect, [System.Drawing.GraphicsUnit]::Pixel)

    $g.Dispose()
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
}

$drawable = Join-Path $ResourcesDir "drawable-xxhdpi"
New-Item -ItemType Directory -Force -Path $drawable | Out-Null

# 自适应图标前景（108dp × 密度）：完整图标，含字标
$densities = @{ 'mdpi' = 1.0; 'hdpi' = 1.5; 'xhdpi' = 2.0; 'xxhdpi' = 3.0; 'xxxhdpi' = 4.0 }
foreach ($key in $densities.Keys) {
    $canvas = [int](108 * $densities[$key])
    $dir = Join-Path $ResourcesDir "mipmap-$key"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    Save-Composition $canvas $LogoScale (Join-Path $dir "ic_launcher_foreground.png") $false
}

# API 26 以下才会用到的旧式图标（同一构图）
$legacy = @{ 'mdpi' = 48; 'hdpi' = 72; 'xhdpi' = 96; 'xxhdpi' = 144; 'xxxhdpi' = 192 }
foreach ($key in $legacy.Keys) {
    $size = $legacy[$key]
    $dir = Join-Path $ResourcesDir "mipmap-$key"
    Save-Composition $size $LogoScale (Join-Path $dir "ic_launcher.png") $false
    Save-Composition $size $LogoScale (Join-Path $dir "ic_launcher_round.png") $false
}

# 应用内标记（标题栏 32dp / 关于页 44dp @3x），同样保留字标
Save-Composition 96 $InAppScale (Join-Path $drawable "ic_logo.png") $true
Save-Composition 132 $InAppScale (Join-Path $drawable "ic_logo_about.png") $true

$logo.Dispose()
Write-Host ("图标已生成：自适应前景 {0:P0}，应用内标记 {1:P0}（均为完整图标，含字标）" -f $LogoScale, $InAppScale)
