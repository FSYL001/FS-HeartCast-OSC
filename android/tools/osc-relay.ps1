# FS-HeartCast OSC 中继（PC 端）
#
# 为什么需要它：
#   VRChat 的 OSC 服务只监听本机回环地址 127.0.0.1:9000，不会监听局域网网卡，
#   所以手机把 OSC 直接发到「电脑的局域网 IP:9000」时，VRChat 收不到。
#   这个小脚本在电脑上监听局域网 UDP 端口（默认 9001），再原样转发给 127.0.0.1:9000。
#
# 用法（Windows PowerShell，不需要装任何东西）：
#   powershell -ExecutionPolicy Bypass -File .\osc-relay.ps1
#   powershell -ExecutionPolicy Bypass -File .\osc-relay.ps1 -ListenPort 9001 -ForwardPort 9000
#   powershell -ExecutionPolicy Bypass -File .\osc-relay.ps1 -AddFirewallRule   # 需要管理员
#
# 手机端把「电脑局域网 IP」填成下面打印出来的地址，端口填 -ListenPort（默认 9001）。

[CmdletBinding()]
param(
    [int]$ListenPort = 9001,
    [string]$ForwardHost = "127.0.0.1",
    [int]$ForwardPort = 9000,
    [string]$BindAddress = "0.0.0.0",
    [switch]$AddFirewallRule
)

$ErrorActionPreference = "Stop"

function Get-LanAddresses {
    $result = @()

    # 首选 .NET 枚举（不依赖 NetTCPIP 模块，权限受限时也能用）
    try {
        foreach ($nic in [System.Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()) {
            if ($nic.OperationalStatus -ne [System.Net.NetworkInformation.OperationalStatus]::Up) { continue }
            if ($nic.NetworkInterfaceType -eq [System.Net.NetworkInformation.NetworkInterfaceType]::Loopback) { continue }
            try {
                foreach ($ua in $nic.GetIPProperties().UnicastAddresses) {
                    $ip = $ua.Address
                    if ($ip.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork) { continue }
                    $text = $ip.ToString()
                    if ($text.StartsWith("169.254.") -or $text.StartsWith("127.")) { continue }
                    $result += $text
                }
            } catch {
                # 单个网卡读取失败时跳过
            }
        }
    } catch {
        # 忽略，继续用下面的回退方案
    }

    if ($result.Count -eq 0) {
        try {
            $result = @([System.Net.Dns]::GetHostAddresses([System.Net.Dns]::GetHostName()) |
                Where-Object { $_.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetwork } |
                ForEach-Object { $_.ToString() } |
                Where-Object { -not $_.StartsWith("127.") })
        } catch {
            $result = @()
        }
    }

    return @($result | Select-Object -Unique)
}

Write-Host ""
Write-Host "FS-HeartCast OSC 中继" -ForegroundColor Cyan
Write-Host "  监听 : $BindAddress`:$ListenPort (UDP)"
Write-Host "  转发 : $ForwardHost`:$ForwardPort (VRChat)"
$lan = Get-LanAddresses
if ($lan.Count -gt 0) {
    Write-Host "  手机里填的电脑 IP：" -NoNewline
    Write-Host ($lan -join "  或  ") -ForegroundColor Green
    Write-Host "  手机里填的端口：" -NoNewline
    Write-Host "$ListenPort" -ForegroundColor Green
} else {
    Write-Host "  没能自动识别局域网 IP，请用 ipconfig 查看。" -ForegroundColor Yellow
}
Write-Host ""

if ($AddFirewallRule) {
    $ruleName = "FS-HeartCast OSC Relay"
    try {
        & netsh advfirewall firewall delete rule name="$ruleName" | Out-Null
    } catch {
        # 没有旧规则时忽略
    }
    & netsh advfirewall firewall add rule name="$ruleName" dir=in action=allow protocol=UDP localport=$ListenPort | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "已添加入站防火墙规则 UDP $ListenPort。" -ForegroundColor Green
    } else {
        Write-Host "添加防火墙规则失败（需要以管理员身份运行）。" -ForegroundColor Yellow
    }
    Write-Host ""
}

$listener = New-Object System.Net.Sockets.UdpClient
$listener.Client.SetSocketOption(
    [System.Net.Sockets.SocketOptionLevel]::Socket,
    [System.Net.Sockets.SocketOptionName]::ReuseAddress, $true)
$listener.Client.Bind((New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Parse($BindAddress), $ListenPort)))
$listener.Client.ReceiveTimeout = 500

$target = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Parse($ForwardHost), $ForwardPort)
$sender = New-Object System.Net.Sockets.UdpClient
$sender.Client.SetSocketOption(
    [System.Net.Sockets.SocketOptionLevel]::Socket,
    [System.Net.Sockets.SocketOptionName]::ReuseAddress, $true)

$forwarded = 0
$probes = 0
$failed = 0
$lastSummary = [DateTime]::UtcNow
$lastFrom = "-"
$lastSize = 0
$quiet = $false

Write-Host "等待手机发来 OSC 数据…（Ctrl+C 退出）" -ForegroundColor DarkGray
Write-Host ""

try {
    while ($true) {
        $remote = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0)
        try {
            $data = $listener.Receive([ref]$remote)
        } catch [System.Net.Sockets.SocketException] {
            $code = $_.Exception.SocketErrorCode
            if ($code -eq [System.Net.Sockets.SocketError]::TimedOut) {
                $data = $null
            } elseif ($code -eq [System.Net.Sockets.SocketError]::ConnectionReset) {
                # Windows 在 ICMP 端口不可达后会返回 ConnectionReset，忽略继续
                $data = $null
            } else {
                throw
            }
        }

        if ($null -ne $data -and $data.Length -gt 0) {
            # App 的「自动检测」探测包：回一句自己的端口，不转发给 VRChat
            $text = [System.Text.Encoding]::ASCII.GetString($data)
            if ($text.StartsWith("FSHEARTCAST")) {
                try {
                    $reply = [System.Text.Encoding]::ASCII.GetBytes("FSHEARTCAST-RELAY $ListenPort")
                    [void]$sender.Send($reply, $reply.Length, $remote)
                    $probes++
                } catch {
                    # 回复失败忽略
                }
                continue
            }

            try {
                [void]$sender.Send($data, $data.Length, $target)
                $forwarded++
                $lastFrom = $remote.Address.ToString()
                $lastSize = $data.Length
            } catch {
                $failed++
            }
            if (-not $quiet) {
                Write-Host "  收到 $($data.Length) 字节（来自 $($remote.Address)）→ 已转发" -ForegroundColor DarkGray
                $quiet = $true
            }
        }

        $now = [DateTime]::UtcNow
        if (($now - $lastSummary).TotalSeconds -ge 5) {
            $lastSummary = $now
            $stamp = (Get-Date).ToString("HH:mm:ss")
            Write-Host "[$stamp] 已转发 $forwarded 个 UDP 包" -NoNewline
            if ($lastFrom -ne "-") {
                Write-Host " · 最近来源 $lastFrom · $lastSize 字节" -NoNewline
            }
            if ($failed -gt 0) {
                Write-Host " · 失败 $failed" -NoNewline
            }
            Write-Host ""
        }
    }
} finally {
    Write-Host ""
    Write-Host "中继已停止，共转发 $forwarded 个包。" -ForegroundColor Cyan
    try { $listener.Dispose() } catch { }
    try { $sender.Dispose() } catch { }
}
