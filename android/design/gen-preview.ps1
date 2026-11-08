Add-Type -AssemblyName System.Drawing

$S = 2.0
function Col([string]$h) { [System.Drawing.ColorTranslator]::FromHtml($h) }
function Fx([double]$px, [string]$style, [string]$family = 'Segoe UI') {
    New-Object System.Drawing.Font($family, [single]$px, [System.Drawing.FontStyle]::$style, [System.Drawing.GraphicsUnit]::Pixel)
}
function Draw([System.Drawing.Graphics]$g, [string]$s, [double]$x, [double]$y, [double]$px, $color, [string]$style = 'Regular', [string]$family = 'Segoe UI') {
    $f = Fx $px $style $family
    $b = New-Object System.Drawing.SolidBrush $color
    $g.DrawString($s, $f, $b, [single]$x, [single]$y)
    $b.Dispose(); $f.Dispose()
}
function DrawCenter([System.Drawing.Graphics]$g, [string]$s, [double]$x, [double]$w, [double]$y, [double]$px, $color, [string]$style = 'Regular', [string]$family = 'Segoe UI') {
    $f = Fx $px $style $family
    $b = New-Object System.Drawing.SolidBrush $color
    $size = $g.MeasureString($s, $f)
    $g.DrawString($s, $f, $b, [single]($x + ($w - $size.Width) / 2), [single]$y)
    $b.Dispose(); $f.Dispose()
}
function RoundPath([double]$x, [double]$y, [double]$w, [double]$h, [double]$r) {
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $r * 2
    $p.AddArc([single]$x, [single]$y, [single]$d, [single]$d, 180, 90)
    $p.AddArc([single]($x + $w - $d), [single]$y, [single]$d, [single]$d, 270, 90)
    $p.AddArc([single]($x + $w - $d), [single]($y + $h - $d), [single]$d, [single]$d, 0, 90)
    $p.AddArc([single]$x, [single]($y + $h - $d), [single]$d, [single]$d, 90, 90)
    $p.CloseFigure()
    return $p
}
function Round([System.Drawing.Graphics]$g, $fill, $pen, [double]$x, [double]$y, [double]$w, [double]$h, [double]$r) {
    $p = RoundPath $x $y $w $h $r
    if ($fill) { $g.FillPath($fill, $p) }
    if ($pen) { $g.DrawPath($pen, $p) }
    $p.Dispose()
}

function RenderMainScreen([System.Drawing.Graphics]$g, [double]$ox, [bool]$dark) {
    if ($dark) {
        $bg = Col '#0D0F12'; $surface = Col '#16191E'; $alt = Col '#1E232B'
        $hair = Col '#2B323C'; $txt = Col '#F0F4F8'; $sec = Col '#94A3B8'
        $mut = Col '#64748B'; $accent = Col '#3CC4BB'; $onAcc = Col '#04211F'
        $heart = Col '#FF6B7A'; $ok = Col '#10B981'
    } else {
        $bg = Col '#F4F6F8'; $surface = Col '#FFFFFF'; $alt = Col '#EEF2F6'
        $hair = Col '#E2E8F0'; $txt = Col '#0F172A'; $sec = Col '#475569'
        $mut = Col '#94A3B8'; $accent = Col '#0E7C74'; $onAcc = Col '#F5FFFE'
        $heart = Col '#E8505B'; $ok = Col '#059669'
    }

    $w = 360 * $S; $h = 800 * $S
    $g.FillRectangle((New-Object System.Drawing.SolidBrush $bg), [single]$ox, 0, [single]$w, [single]$h)

    $pad = 16 * $S
    $x0 = $ox + $pad
    $cw = $w - 2 * $pad

    # 1. 顶栏：品牌与状态胶囊
    Draw $g 'FS-HeartCast-OSC' $x0 (14 * $S) (20 * $S) $txt 'Bold'
    Draw $g 'VRChat OSC Bridge' $x0 (38 * $S) (10.5 * $S) $mut 'Regular'

    $chipW = 76 * $S; $chipH = 28 * $S
    $chipX = $ox + $w - $pad - $chipW
    $chipY = 16 * $S
    Round $g (New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(40, $accent.R, $accent.G, $accent.B))) $null $chipX $chipY $chipW $chipH (14 * $S)
    
    $dotOk = New-Object System.Drawing.SolidBrush $ok
    $g.FillEllipse($dotOk, [single]($chipX + 10 * $S), [single]($chipY + 11 * $S), [single](6 * $S), [single](6 * $S))
    $dotOk.Dispose()
    Draw $g '已连接' ($chipX + 22 * $S) ($chipY + 7 * $S) (11 * $S) $accent 'Bold'

    # 2. 心率主卡片 (保留经典圆环与排版样式)
    $cardY = 58 * $S
    $cardH = 360 * $S
    Round $g (New-Object System.Drawing.SolidBrush $surface) (New-Object System.Drawing.Pen($hair, 1)) $x0 $cardY $cw $cardH (20 * $S)

    DrawCenter $g '实时心率' $x0 $cw ($cardY + 16 * $S) (11.5 * $S) $mut 'Bold'

    # 经典圆环
    $rs = 204 * $S
    $rx = $ox + ($w - $rs) / 2
    $ry = $cardY + 40 * $S
    $cx = $rx + $rs / 2
    $cy = $ry + $rs / 2

    # 底环轨道 (原版 strokeWidth 11dp)
    $arcR = $rs - 22 * $S
    $arcX = $cx - $arcR / 2
    $arcY = $cy - $arcR / 2
    $penTrack = New-Object System.Drawing.Pen($hair, [single](11 * $S))
    $g.DrawEllipse($penTrack, [single]$arcX, [single]$arcY, [single]$arcR, [single]$arcR)
    $penTrack.Dispose()

    # 原版青绿->心跳红渐变进度弧
    $penArc = New-Object System.Drawing.Pen($accent, [single](11 * $S))
    $penArc.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $penArc.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $g.DrawArc($penArc, [single]$arcX, [single]$arcY, [single]$arcR, [single]$arcR, -90, 130)
    $penArc.Dispose()

    # 原版中心柔光
    $bCenterGlow = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(26, $accent.R, $accent.G, $accent.B))
    $g.FillEllipse($bCenterGlow, [single]($cx - 50 * $S), [single]($cy - 50 * $S), [single](100 * $S), [single](100 * $S))
    $bCenterGlow.Dispose()

    # 原版经典 56sp 心率数值排版
    $numFont = Fx (56 * $S) 'Regular' 'Segoe UI'
    $nb = New-Object System.Drawing.SolidBrush $heart
    $nSize = $g.MeasureString('72', $numFont)
    $g.DrawString('72', $numFont, $nb, [single]($ox + ($w - $nSize.Width) / 2), [single]($cy - 44 * $S))
    $nb.Dispose(); $numFont.Dispose()

    DrawCenter $g 'B P M' $ox $cw ($cy + 22 * $S) (10 * $S) $mut 'Bold'

    # 卡片底部集成状态信息条 (消除原版大片虚无)
    $dockY = $cardY + $cardH - 52 * $S
    $dockH = 38 * $S
    Round $g (New-Object System.Drawing.SolidBrush $alt) (New-Object System.Drawing.Pen($hair, 1)) ($x0 + 14 * $S) $dockY ($cw - 28 * $S) $dockH (12 * $S)
    
    Draw $g 'HUAWEI WATCH GT 4' ($x0 + 28 * $S) ($dockY + 11 * $S) (12 * $S) $txt 'Bold'
    
    $sepX = $x0 + $cw - 148 * $S
    $penSep = New-Object System.Drawing.Pen($hair, 1)
    $g.DrawLine($penSep, [single]$sepX, [single]($dockY + 8 * $S), [single]$sepX, [single]($dockY + $dockH - 8 * $S))
    $penSep.Dispose()

    $g.FillEllipse((New-Object System.Drawing.SolidBrush $ok), [single]($sepX + 12 * $S), [single]($dockY + 16 * $S), [single](6 * $S), [single](6 * $S))
    Draw $g '已连接 · 正在推送' ($sepX + 24 * $S) ($dockY + 11 * $S) (11 * $S) $sec 'Regular'

    # 3. 操作指令栏 (恢复经典纯胶囊状 Capsule 形状)
    $btnY = $cardY + $cardH + 14 * $S
    $btnH = 52 * $S
    $half = ($cw - 12 * $S) / 2
    $capsuleRadius = $btnH / 2

    # 主按钮：开始扫描 (胶囊状)
    Round $g (New-Object System.Drawing.SolidBrush $accent) $null $x0 $btnY $half $btnH $capsuleRadius
    DrawCenter $g '▶ 开始扫描' $x0 $half ($btnY + 17 * $S) (14 * $S) $onAcc 'Bold'

    # 次按钮：断开连接 (胶囊状)
    $bx = $x0 + $half + 12 * $S
    Round $g (New-Object System.Drawing.SolidBrush $surface) (New-Object System.Drawing.Pen($hair, 1)) $bx $btnY $half $btnH $capsuleRadius
    DrawCenter $g '断开连接' $bx $half ($btnY + 17 * $S) (14 * $S) $txt 'Bold'

    # 4. 附近心率设备卡片
    $dY = $btnY + $btnH + 14 * $S
    $dH = 176 * $S
    Round $g (New-Object System.Drawing.SolidBrush $surface) (New-Object System.Drawing.Pen($hair, 1)) $x0 $dY $cw $dH (20 * $S)

    Draw $g '设备' ($x0 + 16 * $S) ($dY + 14 * $S) (14 * $S) $txt 'Bold'
    
    Round $g (New-Object System.Drawing.SolidBrush $alt) (New-Object System.Drawing.Pen($hair, 1)) ($x0 + 58 * $S) ($dY + 14 * $S) (24 * $S) (18 * $S) (9 * $S)
    DrawCenter $g '2' ($x0 + 58 * $S) (24 * $S) ($dY + 15 * $S) (10 * $S) $accent 'Bold'
    
    Draw $g '清空' ($x0 + $cw - 46 * $S) ($dY + 14 * $S) (12 * $S) $sec 'Regular'

    # 设备条目 1 (选中)
    $r1Y = $dY + 44 * $S
    $rH = 56 * $S
    $rowW = $cw - 24 * $S
    Round $g (New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(28, $accent.R, $accent.G, $accent.B))) (New-Object System.Drawing.Pen($accent, 1)) ($x0 + 12 * $S) $r1Y $rowW $rH (14 * $S)
    
    Round $g (New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(44, $accent.R, $accent.G, $accent.B))) $null ($x0 + 22 * $S) ($r1Y + 10 * $S) (36 * $S) (36 * $S) (18 * $S)
    DrawCenter $g 'H' ($x0 + 22 * $S) (36 * $S) ($r1Y + 18 * $S) (14 * $S) $accent 'Bold'

    Draw $g 'HUAWEI WATCH GT 4' ($x0 + 66 * $S) ($r1Y + 10 * $S) (13 * $S) $txt 'Bold'
    Draw $g 'F4:8C:EB:11:22:33' ($x0 + 66 * $S) ($r1Y + 30 * $S) (10 * $S) $mut 'Regular'

    Draw $g '72' ($x0 + $cw - 92 * $S) ($r1Y + 8 * $S) (18 * $S) $accent 'Bold'
    Draw $g 'BPM' ($x0 + $cw - 64 * $S) ($r1Y + 13 * $S) (9.5 * $S) $mut 'Regular'
    
    $g.FillEllipse((New-Object System.Drawing.SolidBrush $ok), [single]($x0 + $cw - 72 * $S), [single]($r1Y + 34 * $S), [single](5 * $S), [single](5 * $S))
    Draw $g '已连接' ($x0 + $cw - 62 * $S) ($r1Y + 30 * $S) (10 * $S) $sec 'Regular'

    # 设备条目 2 (广播)
    $r2Y = $r1Y + $rH + 8 * $S
    Round $g (New-Object System.Drawing.SolidBrush $alt) (New-Object System.Drawing.Pen($hair, 1)) ($x0 + 12 * $S) $r2Y $rowW $rH (14 * $S)

    Round $g (New-Object System.Drawing.SolidBrush $hair) $null ($x0 + 22 * $S) ($r2Y + 10 * $S) (36 * $S) (36 * $S) (18 * $S)
    DrawCenter $g 'R' ($x0 + 22 * $S) (36 * $S) ($r2Y + 18 * $S) (14 * $S) $mut 'Bold'

    Draw $g 'Redmi Watch 6' ($x0 + 66 * $S) ($r2Y + 10 * $S) (13 * $S) $txt 'Bold'
    Draw $g '72:05:62:DA:E0:63' ($x0 + 66 * $S) ($r2Y + 30 * $S) (10 * $S) $mut 'Regular'

    Draw $g '65' ($x0 + $cw - 92 * $S) ($r2Y + 8 * $S) (18 * $S) $mut 'Bold'
    Draw $g 'BPM' ($x0 + $cw - 64 * $S) ($r2Y + 13 * $S) (9.5 * $S) $mut 'Regular'

    $g.FillEllipse((New-Object System.Drawing.SolidBrush $mut), [single]($x0 + $cw - 72 * $S), [single]($r2Y + 34 * $S), [single](5 * $S), [single](5 * $S))
    Draw $g '广播中' ($x0 + $cw - 62 * $S) ($r2Y + 30 * $S) (10 * $S) $mut 'Regular'

    # 5. 底部标签栏
    $tabY = $h - 64 * $S
    $g.FillRectangle((New-Object System.Drawing.SolidBrush $surface), [single]$ox, [single]$tabY, [single]$w, [single](64 * $S))
    $penTabBorder = New-Object System.Drawing.Pen($hair, 1)
    $g.DrawLine($penTabBorder, [single]$ox, [single]$tabY, [single]($ox + $w), [single]$tabY)
    $penTabBorder.Dispose()

    $tabW = ($w - 48 * $S) / 2
    $pill1X = $ox + 24 * $S
    Round $g (New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(36, $accent.R, $accent.G, $accent.B))) $null $pill1X ($tabY + 10 * $S) $tabW (44 * $S) (14 * $S)
    
    DrawCenter $g '♥  心率' $pill1X $tabW ($tabY + 23 * $S) (13 * $S) $accent 'Bold'
    
    $pill2X = $ox + 24 * $S + $tabW
    DrawCenter $g '⚙  设置' $pill2X $tabW ($tabY + 23 * $S) (13 * $S) $mut 'Regular'
}

function RenderSettingsScreen([System.Drawing.Graphics]$g, [double]$ox, [bool]$dark) {
    if ($dark) {
        $bg = Col '#0D0F12'; $surface = Col '#16191E'; $alt = Col '#1E232B'
        $hair = Col '#2B323C'; $txt = Col '#F0F4F8'; $sec = Col '#94A3B8'
        $mut = Col '#64748B'; $accent = Col '#3CC4BB'; $onAcc = Col '#04211F'
        $ok = Col '#10B981'
    } else {
        $bg = Col '#F4F6F8'; $surface = Col '#FFFFFF'; $alt = Col '#EEF2F6'
        $hair = Col '#E2E8F0'; $txt = Col '#0F172A'; $sec = Col '#475569'
        $mut = Col '#94A3B8'; $accent = Col '#0E7C74'; $onAcc = Col '#F0FDFA'
        $ok = Col '#059669'
    }

    $w = 360 * $S; $h = 800 * $S
    $g.FillRectangle((New-Object System.Drawing.SolidBrush $bg), [single]$ox, 0, [single]$w, [single]$h)

    $pad = 16 * $S
    $x0 = $ox + $pad
    $cw = $w - 2 * $pad

    Draw $g '设置' $x0 (16 * $S) (24 * $S) $txt 'Bold'
    Draw $g '启动、行为与外观偏好' $x0 (46 * $S) (12 * $S) $mut 'Regular'

    # 分组 1：启动
    $g1Y = 74 * $S
    Draw $g '启动与常驻' $x0 $g1Y (11 * $S) $mut 'Bold'
    
    $c1Y = $g1Y + 20 * $S
    $c1H = 116 * $S
    Round $g (New-Object System.Drawing.SolidBrush $surface) (New-Object System.Drawing.Pen($hair, 1)) $x0 $c1Y $cw $c1H (20 * $S)

    # 行 1
    Draw $g '启动时自动开始扫描' ($x0 + 16 * $S) ($c1Y + 14 * $S) (13.5 * $S) $txt 'Bold'
    Draw $g '打开应用后即自动搜索手表心率广播' ($x0 + 16 * $S) ($c1Y + 34 * $S) (10.5 * $S) $mut 'Regular'
    # Switch 开关 (开)
    $swX = $x0 + $cw - 54 * $S; $swY = $c1Y + 16 * $S
    Round $g (New-Object System.Drawing.SolidBrush $accent) $null $swX $swY (40 * $S) (22 * $S) (11 * $S)
    $g.FillEllipse((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::White)), [single]($swX + 20 * $S), [single]($swY + 2 * $S), [single](18 * $S), [single](18 * $S))

    # 分割线
    $g.DrawLine((New-Object System.Drawing.Pen($hair, 1)), [single]($x0 + 16 * $S), [single]($c1Y + 58 * $S), [single]($x0 + $cw - 16 * $S), [single]($c1Y + 58 * $S))

    # 行 2
    Draw $g '开机自动启动' ($x0 + 16 * $S) ($c1Y + 74 * $S) (13.5 * $S) $txt 'Bold'
    # Switch (关)
    $sw2Y = $c1Y + 72 * $S
    Round $g (New-Object System.Drawing.SolidBrush $hair) $null $swX $sw2Y (40 * $S) (22 * $S) (11 * $S)
    $g.FillEllipse((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::White)), [single]($swX + 2 * $S), [single]($sw2Y + 2 * $S), [single](18 * $S), [single](18 * $S))

    # 分组 2：外观与个性化
    $g2Y = $c1Y + $c1H + 20 * $S
    Draw $g '外观与个性化' $x0 $g2Y (11 * $S) $mut 'Bold'

    $c2Y = $g2Y + 20 * $S
    $c2H = 196 * $S
    Round $g (New-Object System.Drawing.SolidBrush $surface) (New-Object System.Drawing.Pen($hair, 1)) $x0 $c2Y $cw $c2H (20 * $S)

    Draw $g '主题模式' ($x0 + 16 * $S) ($c2Y + 14 * $S) (13.5 * $S) $txt 'Bold'

    # 三段分段选择器
    $segY = $c2Y + 38 * $S
    $segH = 38 * $S
    $segW = $cw - 32 * $S
    Round $g (New-Object System.Drawing.SolidBrush $alt) (New-Object System.Drawing.Pen($hair, 1)) ($x0 + 16 * $S) $segY $segW $segH (12 * $S)

    # 选中滑块
    $tW = $segW / 3
    Round $g (New-Object System.Drawing.SolidBrush $surface) (New-Object System.Drawing.Pen($hair, 1)) ($x0 + 18 * $S + $tW * 2) ($segY + 2 * $S) ($tW - 4 * $S) ($segH - 4 * $S) (10 * $S)

    DrawCenter $g '跟随系统' ($x0 + 16 * $S) $tW ($segY + 11 * $S) (11.5 * $S) $sec 'Regular'
    DrawCenter $g '浅色' ($x0 + 16 * $S + $tW) $tW ($segY + 11 * $S) (11.5 * $S) $sec 'Regular'
    DrawCenter $g '深色' ($x0 + 16 * $S + $tW * 2) $tW ($segY + 11 * $S) (11.5 * $S) $txt 'Bold'

    Draw $g '强调色' ($x0 + 16 * $S) ($c2Y + 92 * $S) (13.5 * $S) $txt 'Bold'
    
    # 6 个色点
    $colors = @('#14B8A6', '#3B82F6', '#8B5CF6', '#10B981', '#F59E0B', '#EC4899')
    $cx0 = $x0 + 20 * $S
    $gap = ($cw - 40 * $S - 6 * 26 * $S) / 5
    for ($i = 0; $i -lt 6; $i++) {
        $dotX = $cx0 + $i * (26 * $S + $gap)
        $dotY = $c2Y + 116 * $S
        $cl = Col $colors[$i]
        
        if ($i -eq 0) {
            $penRing = New-Object System.Drawing.Pen($cl, [single](2 * $S))
            $g.DrawEllipse($penRing, [single]($dotX - 4 * $S), [single]($dotY - 4 * $S), [single](34 * $S), [single](34 * $S))
            $penRing.Dispose()
        }
        $g.FillEllipse((New-Object System.Drawing.SolidBrush $cl), [single]$dotX, [single]$dotY, [single](26 * $S), [single](26 * $S))
    }

    # 分割线
    $g.DrawLine((New-Object System.Drawing.Pen($hair, 1)), [single]($x0 + 16 * $S), [single]($c2Y + 154 * $S), [single]($x0 + $cw - 16 * $S), [single]($c2Y + 154 * $S))

    # 语言行
    Draw $g '应用语言' ($x0 + 16 * $S) ($c2Y + 168 * $S) (13.5 * $S) $txt 'Bold'
    Draw $g '跟随系统  >' ($x0 + $cw - 92 * $S) ($c2Y + 168 * $S) (12 * $S) $mut 'Regular'

    # 分组 3：关于
    $g3Y = $c2Y + $c2H + 20 * $S
    Draw $g '关于' $x0 $g3Y (11 * $S) $mut 'Bold'

    $c3Y = $g3Y + 20 * $S
    $c3H = 100 * $S
    Round $g (New-Object System.Drawing.SolidBrush $surface) (New-Object System.Drawing.Pen($hair, 1)) $x0 $c3Y $cw $c3H (20 * $S)

    # Logo 占位方块
    Round $g (New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(40, $accent.R, $accent.G, $accent.B))) $null ($x0 + 16 * $S) ($c3Y + 14 * $S) (40 * $S) (40 * $S) (12 * $S)
    DrawCenter $g '♥' ($x0 + 16 * $S) (40 * $S) ($c3Y + 22 * $S) (18 * $S) $accent 'Bold'

    Draw $g 'FS-HeartCast-OSC' ($x0 + 66 * $S) ($c3Y + 16 * $S) (14 * $S) $txt 'Bold'
    Draw $g '版本 1.2.1 (Build 5) · Android' ($x0 + 66 * $S) ($c3Y + 36 * $S) (11 * $S) $mut 'Regular'

    # 检查更新
    $g.DrawLine((New-Object System.Drawing.Pen($hair, 1)), [single]($x0 + 16 * $S), [single]($c3Y + 64 * $S), [single]($x0 + $cw - 16 * $S), [single]($c3Y + 64 * $S))
    Draw $g '检查更新' ($x0 + 16 * $S) ($c3Y + 74 * $S) (13 * $S) $txt 'Bold'
    Draw $g '已是最新版本  >' ($x0 + $cw - 110 * $S) ($c3Y + 74 * $S) (11.5 * $S) $ok 'Regular'

    # 底部标签栏
    $tabY = $h - 64 * $S
    $g.FillRectangle((New-Object System.Drawing.SolidBrush $surface), [single]$ox, [single]$tabY, [single]$w, [single](64 * $S))
    $penTabBorder = New-Object System.Drawing.Pen($hair, 1)
    $g.DrawLine($penTabBorder, [single]$ox, [single]$tabY, [single]($ox + $w), [single]$tabY)
    $penTabBorder.Dispose()

    $tabW = ($w - 48 * $S) / 2
    $pill1X = $ox + 24 * $S
    DrawCenter $g '♥  心率' $pill1X $tabW ($tabY + 23 * $S) (13 * $S) $mut 'Regular'

    $pill2X = $ox + 24 * $S + $tabW
    Round $g (New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(36, $accent.R, $accent.G, $accent.B))) $null $pill2X ($tabY + 10 * $S) $tabW (44 * $S) (14 * $S)
    DrawCenter $g '⚙  设置' $pill2X $tabW ($tabY + 23 * $S) (13 * $S) $accent 'Bold'
}

$W = [int](2 * 360 * $S + 40)
$H = [int](800 * $S)

# 1. Main UI
$bmpMain = New-Object System.Drawing.Bitmap $W, $H
$gMain = [System.Drawing.Graphics]::FromImage($bmpMain)
$gMain.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$gMain.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::ClearTypeGridFit
$gMain.Clear([System.Drawing.ColorTranslator]::FromHtml('#000000'))
RenderMainScreen $gMain 0 $true
RenderMainScreen $gMain (360 * $S + 40) $false
$gMain.Dispose()
$outMain = "C:\Users\JKH\Desktop\GIT\0Amyhub\HOSC\android\design\ui-v3-main.png"
$bmpMain.Save($outMain, [System.Drawing.Imaging.ImageFormat]::Png)
$bmpMain.Dispose()
Write-Host "Generated: $outMain"

# 2. Settings UI
$bmpSettings = New-Object System.Drawing.Bitmap $W, $H
$gSettings = [System.Drawing.Graphics]::FromImage($bmpSettings)
$gSettings.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$gSettings.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::ClearTypeGridFit
$gSettings.Clear([System.Drawing.ColorTranslator]::FromHtml('#000000'))
RenderSettingsScreen $gSettings 0 $true
RenderSettingsScreen $gSettings (360 * $S + 40) $false
$gSettings.Dispose()
$outSettings = "C:\Users\JKH\Desktop\GIT\0Amyhub\HOSC\android\design\ui-v3-settings.png"
$bmpSettings.Save($outSettings, [System.Drawing.Imaging.ImageFormat]::Png)
$bmpSettings.Dispose()
Write-Host "Generated: $outSettings"

# 3. Side-by-side comparison with old version
$oldBmp = [System.Drawing.Image]::FromFile("C:\Users\JKH\Desktop\GIT\0Amyhub\HOSC\android\design\ui-v2-main.png")
$newBmp = [System.Drawing.Image]::FromFile($outMain)

$halfW = [int]($oldBmp.Width / 2)
$H_old = $oldBmp.Height

$cmpW = $halfW * 2 + 40
$cmpBmp = New-Object System.Drawing.Bitmap $cmpW, ($H_old + 80)
$gCmp = [System.Drawing.Graphics]::FromImage($cmpBmp)
$gCmp.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$gCmp.Clear([System.Drawing.ColorTranslator]::FromHtml('#0A0C0E'))

$fTitle = New-Object System.Drawing.Font('Segoe UI', 18, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
$fSub = New-Object System.Drawing.Font('Segoe UI', 12, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
$bRed = New-Object System.Drawing.SolidBrush ([System.Drawing.ColorTranslator]::FromHtml('#EF4444'))
$bGreen = New-Object System.Drawing.SolidBrush ([System.Drawing.ColorTranslator]::FromHtml('#10B981'))
$bMuted = New-Object System.Drawing.SolidBrush ([System.Drawing.ColorTranslator]::FromHtml('#94A3B8'))

$gCmp.DrawString("原来 DeepSeek 版本 (旧版)", $fTitle, $bRed, [single]30, [single]15)
$gCmp.DrawString("文字重叠 · 空白过大 · 按钮无序 · 界面扁平", $fSub, $bMuted, [single]30, [single]42)

$gCmp.DrawString("按要求调整后的 UI (保留原心率环与胶囊按钮)", $fTitle, $bGreen, [single]($halfW + 50), [single]15)
$gCmp.DrawString("经典心率圆圈 + 原版排版 + 纯胶囊按钮 + 设备栏无重叠", $fSub, $bMuted, [single]($halfW + 50), [single]42)

$rectOldSrc = New-Object System.Drawing.Rectangle 0, 0, $halfW, $H_old
$rectOldDest = New-Object System.Drawing.Rectangle 20, 70, $halfW, $H_old
$gCmp.DrawImage($oldBmp, $rectOldDest, $rectOldSrc, [System.Drawing.GraphicsUnit]::Pixel)

$rectNewSrc = New-Object System.Drawing.Rectangle 0, 0, $halfW, $H_old
$rectNewDest = New-Object System.Drawing.Rectangle ($halfW + 40), 70, $halfW, $H_old
$gCmp.DrawImage($newBmp, $rectNewDest, $rectNewSrc, [System.Drawing.GraphicsUnit]::Pixel)

$gCmp.Dispose()
$oldBmp.Dispose()
$newBmp.Dispose()

$outCmp = "C:\Users\JKH\Desktop\GIT\0Amyhub\HOSC\android\design\ui-comparison.png"
$cmpBmp.Save($outCmp, [System.Drawing.Imaging.ImageFormat]::Png)
$cmpBmp.Dispose()
Write-Host "Generated: $outCmp"
