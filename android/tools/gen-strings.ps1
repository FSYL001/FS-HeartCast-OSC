# 从桌面版 HOSC\Resources\Strings*.resx 生成 Android 的多语言资源。
#
#   powershell -ExecutionPolicy Bypass -File .\tools\gen-strings.ps1
#
# 说明：
#   - key 名从 PascalCase 转成 Android 的 snake_case（DevicesHeader → devices_header）
#   - 桌面版已有的 69 个 key 里，只取本 App 实际用到的那些，避免资源冗余
#   - Android 端额外的文案（安卓特有：权限、前台服务、开机自启等）在 $extras 里维护
#   - 输出：values/（英文，默认）、values-zh-rCN、values-zh-rTW、values-ja

[CmdletBinding()]
param(
    [string]$DesktopResourcesDir = (Join-Path $PSScriptRoot "..\..\HOSC\Resources")
)

$ErrorActionPreference = "Stop"

$resDir = $PSScriptRoot
$appRes = Join-Path $resDir "..\app\src\main\res"
$utf8 = New-Object System.Text.UTF8Encoding($false)

# ---------------------------------------------------------------- 读取桌面版 resx

function Read-Resx([string]$path) {
    if (-not (Test-Path $path)) {
        throw "缺少桌面版资源文件：$path"
    }
    $text = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
    [xml]$xml = $text
    $map = @{}
    foreach ($d in $xml.root.data) {
        $map[$d.name] = [string]$d.value
    }
    return $map
}

$source = @{
    en = Read-Resx (Join-Path $DesktopResourcesDir "Strings.resx")
    zh = Read-Resx (Join-Path $DesktopResourcesDir "Strings.zh-Hans.resx")
    tw = Read-Resx (Join-Path $DesktopResourcesDir "Strings.zh-Hant.resx")
    ja = Read-Resx (Join-Path $DesktopResourcesDir "Strings.ja.resx")
}

# 桌面版 key（保持同名，转换成 snake_case）
$desktopKeys = @(
    "AppTitle",
    "LiveHeartRate", "DevicesHeader", "NoDevices",
    "DeviceName", "DeviceBpm", "DeviceSignal", "DeviceStatus", "DeviceAddress",
    "DeviceNoName", "DeviceBroadcasting", "DeviceOffline", "DeviceConnected",
    "DeviceConnecting", "DeviceConnectError",
    "ScanStart", "ScanStop", "Scanning", "ScanStopped", "BluetoothOff", "ClearList",
    "SettingsButton", "SettingsHeader", "SettingsCategoryGeneral",
    "SettingsCategoryAppearance", "SettingsCategoryAbout",
    "SettingsGeneralDescription", "SettingsAppearanceDescription", "SettingsAboutDescription",
    "VrchatHeader", "EnableVrchat", "ActiveDeviceLabel", "ActiveDeviceNone",
    "Connect", "Disconnect",
    "AddressLabel", "PortLabel", "HrAddressLabel", "BeatAddressLabel",
    "OscTest", "OscTestSent", "OscTestFailed",
    "LanguageLabel", "ThemeLabel", "ThemeSystem", "ThemeLight", "ThemeDark",
    "AccentLabel", "AccentTeal", "AccentBlue", "AccentPurple", "AccentGreen",
    "AccentOrange", "AccentPink",
    "StartupGroup", "WindowGroup",
    "AutoStartScanLabel", "StartWithWindowsLabel",
    "WebmasterEmailLabel", "CommunityGroup",
    "GitHubLabel", "BilibiliLabel", "DouyinLabel", "AifadianLabel",
    "VersionLabel", "Done", "LocalOnly"
)

# ---------------------------------------------------------------- 安卓端额外文案
# 键：{ en, zh, tw, ja }  —— 品牌名/语言自称不翻译

$extras = [ordered]@{
    "app_name" = @{ en = "FS-HeartCast-OSC"; zh = "FS-HeartCast-OSC"; tw = "FS-HeartCast-OSC"; ja = "FS-HeartCast-OSC" }
    "osc_target_hint" = @{ en = "PC IP address"; zh = "电脑 IP 地址"; tw = "電腦 IP 位址"; ja = "PC の IP アドレス" }
    "apply" = @{ en = "Apply"; zh = "应用"; tw = "套用"; ja = "適用" }
    "applied" = @{ en = "Applied"; zh = "已应用"; tw = "已套用"; ja = "適用しました" }
    "saved" = @{ en = "Saved"; zh = "已保存"; tw = "已儲存"; ja = "保存しました" }
    "language_system" = @{ en = "Follow system"; zh = "跟随系统"; tw = "跟隨系統"; ja = "システムに従う" }
    "keep_screen_on" = @{ en = "Keep screen on"; zh = "保持屏幕常亮"; tw = "保持螢幕恆亮"; ja = "画面をオンのままにする" }
    "background_run" = @{ en = "Keep running in background (foreground service)"; zh = "后台持续运行（前台服务）"; tw = "背景持續執行（前景服務）"; ja = "バックグラウンドで動作（フォアグラウンドサービス）" }
    "boot_start" = @{ en = "Start with the system"; zh = "开机自动启动"; tw = "開機自動啟動"; ja = "システム起動時に自動起動" }
    "status_idle" = @{ en = "Idle"; zh = "待机"; tw = "待機"; ja = "待機" }
    "status_connected" = @{ en = "Connected, pushing"; zh = "已连接 · 正在推送"; tw = "已連線 · 正在推送"; ja = "接続中 · 送信中" }
    "status_connecting" = @{ en = "Connecting…"; zh = "连接中…"; tw = "連線中…"; ja = "接続中…" }
    "status_scanning" = @{ en = "Scanning, not connected"; zh = "扫描中 · 未连接"; tw = "掃描中 · 未連線"; ja = "スキャン中 · 未接続" }
    "status_packets" = @{ en = "%1$d packets sent"; zh = "已发 %1$d 包"; tw = "已送 %1$d 包"; ja = "%1$d パケット送信" }
    "select_device_first" = @{ en = "Select a device first"; zh = "请先在列表中选择一个设备"; tw = "請先在清單中選擇裝置"; ja = "先にデバイスを選択してください" }
    "ip_invalid" = @{ en = "Invalid PC IP address"; zh = "电脑 IP 不合法"; tw = "電腦 IP 不合法"; ja = "PC の IP が不正です" }
    "port_invalid" = @{ en = "Port must be 1-65535"; zh = "端口必须是 1-65535 的数字"; tw = "連接埠必須是 1-65535 的數字"; ja = "ポートは 1-65535 の数値で指定してください" }
    "address_invalid" = @{ en = "Address must start with /"; zh = "参数地址必须以 / 开头"; tw = "參數位址必須以 / 開頭"; ja = "アドレスは / で始めてください" }
    "bt_permission_needed" = @{ en = "Bluetooth permission is required to scan"; zh = "没有蓝牙权限将无法扫描手表"; tw = "沒有藍牙權限將無法掃描手錶"; ja = "Bluetooth 権限がないとスキャンできません" }
    "bt_permission_granted" = @{ en = "Permission granted"; zh = "权限已授予"; tw = "權限已授予"; ja = "権限が許可されました" }
    "bt_enable_prompt" = @{ en = "Turn on Bluetooth to scan"; zh = "请先开启系统蓝牙"; tw = "請先開啟系統藍牙"; ja = "Bluetooth をオンにしてください" }
    "scan_failed" = @{ en = "Unable to start scanning"; zh = "无法开始扫描"; tw = "無法開始掃描"; ja = "スキャンを開始できません" }
    "bt_unsupported" = @{ en = "This device has no Bluetooth"; zh = "该设备不支持蓝牙"; tw = "此裝置不支援藍牙"; ja = "この端末は Bluetooth に対応していません" }
    "boot_start_failed" = @{ en = "The system blocked auto start"; zh = "系统拒绝了开机自启"; tw = "系統拒絕了開機自啟"; ja = "システムが自動起動を拒否しました" }
    "service_channel" = @{ en = "Heart rate push"; zh = "心率推送"; tw = "心率推送"; ja = "心拍数送信" }
    "notif_waiting" = @{ en = "Waiting for heart rate"; zh = "等待心率数据"; tw = "等待心率資料"; ja = "心拍数データを待機中" }
    "notif_bpm" = @{ en = "%1$d BPM"; zh = "%1$d BPM"; tw = "%1$d BPM"; ja = "%1$d BPM" }
    "notif_osc_off" = @{ en = "OSC off"; zh = "OSC 已关闭"; tw = "OSC 已關閉"; ja = "OSC オフ" }
    "discovery_label" = @{ en = "PC address"; zh = "电脑地址"; tw = "電腦位址"; ja = "PC のアドレス" }
    "discovery_button" = @{ en = "Auto detect"; zh = "自动检测"; tw = "自動偵測"; ja = "自動検出" }
    "discovery_scanning" = @{ en = "Scanning LAN…"; zh = "正在扫描局域网…"; tw = "正在掃描區域網路…"; ja = "LAN をスキャン中…" }
    "discovery_found" = @{ en = 'Found %1$s:%2$d'; zh = '已找到 %1$s:%2$d'; tw = '已找到 %1$s:%2$d'; ja = '%1$s:%2$d を検出' }
    "discovery_not_found" = @{ en = "Not found. Make sure osc-relay.ps1 is running on the PC."; zh = "没找到。请确认电脑上已运行 osc-relay.ps1"; tw = "找不到。請確認電腦上已執行 osc-relay.ps1"; ja = "見つかりません。PC 側で osc-relay.ps1 を実行してください。" }
    "check_update" = @{ en = "Check for updates"; zh = "检查更新"; tw = "檢查更新"; ja = "アップデートを確認" }
    "update_checking" = @{ en = "Checking…"; zh = "正在检查…"; tw = "正在檢查…"; ja = "確認中…" }
    "update_latest" = @{ en = "You are up to date"; zh = "已是最新版本"; tw = "已是最新版本"; ja = "最新版です" }
    "update_failed" = @{ en = "Check failed. Check your network."; zh = "检查失败，请检查网络"; tw = "檢查失敗，請檢查網路"; ja = "確認に失敗しました。ネットワークを確認してください。" }
    "update_found_title" = @{ en = "Version %1$s available"; zh = "发现新版本 %1$s"; tw = "發現新版本 %1$s"; ja = "新しいバージョン %1$s" }
    "update_now" = @{ en = "Update"; zh = "立即更新"; tw = "立即更新"; ja = "今すぐ更新" }
    "update_later" = @{ en = "Later"; zh = "稍后"; tw = "稍後"; ja = "後で" }
    "update_ignore" = @{ en = "Skip this version"; zh = "忽略此版本"; tw = "忽略此版本"; ja = "このバージョンをスキップ" }
    "update_notes_empty" = @{ en = "No release notes."; zh = "（该版本没有发布说明）"; tw = "（該版本沒有發佈說明）"; ja = "（リリースノートはありません）" }
    "no_device" = @{ en = "No device selected"; zh = "未选择设备"; tw = "未選擇裝置"; ja = "デバイス未選択" }
    "tab_live" = @{ en = "Heart rate"; zh = "心率"; tw = "心率"; ja = "心拍数" }
    "tab_settings" = @{ en = "Settings"; zh = "设置"; tw = "設定"; ja = "設定" }
    "section_devices" = @{ en = "Devices"; zh = "设备"; tw = "裝置"; ja = "デバイス" }
    "advanced" = @{ en = "Advanced"; zh = "高级"; tw = "進階"; ja = "詳細" }
    "settings_subtitle" = @{ en = "Startup, behavior, appearance"; zh = "启动、行为与外观"; tw = "啟動、行為與外觀"; ja = "起動・動作・外観" }
    "target_label" = @{ en = "Target"; zh = "推送目标"; tw = "推送目標"; ja = "送信先" }
    "language" = @{ en = "Language"; zh = "语言"; tw = "語言"; ja = "言語" }
    "theme" = @{ en = "Theme"; zh = "主题"; tw = "主題"; ja = "テーマ" }
    "accent" = @{ en = "Accent color"; zh = "强调色"; tw = "強調色"; ja = "アクセントカラー" }
    "about" = @{ en = "About"; zh = "关于"; tw = "關於"; ja = "このアプリについて" }
    "appearance_group" = @{ en = "Appearance"; zh = "外观"; tw = "外觀"; ja = "外観" }
    "behavior_group" = @{ en = "Display"; zh = "显示"; tw = "顯示"; ja = "表示" }
    "links_group" = @{ en = "Community"; zh = "社区主页"; tw = "社群主頁"; ja = "コミュニティ" }
    "no_devices_hint" = @{ en = "No heart rate broadcast found"; zh = "未发现心率广播"; tw = "未發現心率廣播"; ja = "心拍数のブロードキャストが見つかりません" }
    "connected_chip" = @{ en = "Connected"; zh = "已连接"; tw = "已連線"; ja = "接続中" }
    "idle_chip" = @{ en = "Idle"; zh = "待机"; tw = "待機"; ja = "待機" }
    "scanning_chip" = @{ en = "Scanning"; zh = "扫描中"; tw = "掃描中"; ja = "スキャン中" }
    "connect_device" = @{ en = "Connect watch"; zh = "连接手表"; tw = "連接手錶"; ja = "ウォッチに接続" }
    "about_android" = @{
        en = "This is the Android build: the phone reads heart rate from a BLE watch and forwards it to VRChat over OSC on the local network. VRChat only listens on 127.0.0.1:9000, so when the phone cannot reach it directly, run the bundled osc-relay.ps1 on the PC first."
        zh = "这是 Android 版：手机读取 BLE 手表心率，再通过局域网 OSC 转给 VRChat。VRChat 只监听 127.0.0.1:9000，手机直连收不到时，请先在电脑上运行随附的 osc-relay.ps1。"
        tw = "這是 Android 版：手機讀取 BLE 手錶心率，再透過區域網路 OSC 轉給 VRChat。VRChat 只監聽 127.0.0.1:9000，手機直連收不到時，請先在電腦上執行隨附的 osc-relay.ps1。"
        ja = "Android 版です。スマートフォンが BLE ウォッチの心拍数を読み取り、LAN 上の OSC で VRChat に転送します。VRChat は 127.0.0.1:9000 のみ待ち受けるため、直接届かない場合は付属の osc-relay.ps1 を PC 側で実行してください。"
    }
}

# ---------------------------------------------------------------- 生成

function ConvertTo-SnakeCase([string]$name) {
    $sb = New-Object System.Text.StringBuilder
    for ($i = 0; $i -lt $name.Length; $i++) {
        $c = $name[$i]
        if ([char]::IsUpper($c)) {
            if ($i -gt 0) { [void]$sb.Append('_') }
            [void]$sb.Append([char]::ToLowerInvariant($c))
        } else {
            [void]$sb.Append($c)
        }
    }
    return $sb.ToString()
}

function Escape-AndroidString([string]$value) {
    $v = $value -replace '&', '&amp;'
    $v = $v -replace '<', '&lt;'
    $v = $v -replace '>', '&gt;'
    $v = $v -replace "'", "\'"
    return $v
}

$entries = [ordered]@{}

foreach ($key in $desktopKeys) {
    $snake = ConvertTo-SnakeCase $key
    $entries[$snake] = @{
        en = $source.en[$key]
        zh = $source.zh[$key]
        tw = $source.tw[$key]
        ja = $source.ja[$key]
    }
}

foreach ($key in $extras.Keys) {
    $entries[$key] = $extras[$key]
}

$targets = @(
    @{ lang = "en"; dir = "values" },
    @{ lang = "zh"; dir = "values-zh-rCN" },
    @{ lang = "tw"; dir = "values-zh-rTW" },
    @{ lang = "ja"; dir = "values-ja" }
)

foreach ($t in $targets) {
    $dir = Join-Path $appRes $t.dir
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.AppendLine('<?xml version="1.0" encoding="utf-8"?>')
    [void]$sb.AppendLine("<resources>")
    foreach ($name in $entries.Keys) {
        $row = $entries[$name]
        $value = $row[$t.lang]
        if ($null -eq $value -or $value -eq "") {
            throw ("{0} 缺少 {1} 的翻译" -f $name, $t.lang)
        }
        $escaped = Escape-AndroidString $value
        [void]$sb.AppendLine(("    <string name=""{0}"">{1}</string>" -f $name, $escaped))
    }
    [void]$sb.AppendLine("</resources>")

    $path = Join-Path $dir "strings.xml"
    [System.IO.File]::WriteAllText($path, $sb.ToString(), $utf8)
    Write-Host ("写入 {0} ({1} 条)" -f $path, $entries.Count)
}

Write-Host ""
Write-Host ("共 {0} 个字符串 key，4 种语言。" -f $entries.Count)
