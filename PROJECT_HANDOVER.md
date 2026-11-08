# FS-HeartCast-OSC 项目交接文档

> 文档版本：1.1  
> 审查对象：`C:\Users\JKH\Desktop\GIT\0Amyhub\HOSC` 工作树（分支 `main`，代码 HEAD `4283397`；本次仅改本文件）  
> 首次审查：2026-11-07（Windows 桌面端，只读静态审查，当时 HEAD `5c19cb7`）  
> 本次更新：2026-11-08，新增 Android 端完整记录（第 23 节）与文档变更记录（附录 E）  
> 审查方式：桌面端部分为只读静态审查；Android 端为实际实现、编译、单测并通过 GitHub Releases 分发，
> 但没有在真机或模拟器上运行过（环境限制，见 23.8）。  
> 文件约束：首次审查只新增本文件、未改动任何原有文件；本次更新同样只改本文件。

---

## 0. 结论摘要

FS-HeartCast-OSC 是一个 Windows 10/11 上的 WPF 桌面程序：用 BLE 广播扫描和按需 GATT 连接读取智能手表的心率，再通过 UDP/OSC 发送给 VRChat，让 Avatar 参数跟随真实心率变化。

从代码看，主流程是完整的：

```text
BLE 广播 / GATT 通知
        ↓
BluetoothHeartRateScanner / GattHeartRateService
        ↓
HeartRateParser
        ↓
HeartRateDevice（UI 模型）
        ↓
OscService（OSC 1.0 编码 + UDP）
        ↓
VRChat OSC（默认 127.0.0.1:9000）
        ↓
Avatar 参数
```

本次审查的主要结论：

- 未发现“必然导致项目无法启动或必然丢失心率数据”的已确认 P0 问题。
- 有 1 项需要实测的 P0 候选：蓝牙关闭/驱动异常时，`BluetoothLEAdvertisementWatcher.Start()` 抛出的异常没有在应用层捕获，默认自动扫描可能让程序在启动时退出（见 P1-5）。
- 最需要优先处理的是 P1 级问题：GATT 连接/断开竞态、切换设备时未发送 `Connected=false`、GATT 连接后广播 BPM 被抑制、GATT 断开后状态未清理、扫描启动异常、VRCOSC 参数语义与 README 不一致。
- 现有 Markdown 文档实际上只有根目录的 `README.md`。`已废弃MD文档/` 目录为空；`HOSC/Assets/design/README.md` 和 `HOSC/Assets/legacy/README.md` 已在提交 `531ee02` 中删除，只能从 Git 历史读取。旧版 README 也只在 Git 历史中。
- 项目当前没有 `.sln`，`HOSC.Diagnostics` 是一个未跟踪的独立控制台项目，不在主程序构建中。
- 本次记录问题共 37 项：P0 确认 0 项、P0 候选 1 项（计入 P1-5）、P1 6 项、P2 16 项、P3 15 项。问题数量包含建议性优化，不代表全部都会在当前设备上复现。

**2026-11-08 补充**：仓库在 v1.0.0 之后新增了独立的 Android 端（`android/`，当前 v1.2.1）——
手机直接连手表读心率，再通过局域网 OSC 推给 VRChat，与桌面版共用同一套 Avatar 参数。
它不改变本文档对 Windows 端的任何结论；Android 端的实现、构建、发布与踩坑记录见第 23 节。

---

## 1. 项目简介和用途

### 1.1 项目是什么

- 产品名：`FS-HeartCast-OSC`
- 程序集名：`FS-HeartCast-OSC`（项目文件为 `HOSC/HOSC.csproj`，根命名空间为 `HOSC`）
- 类型：Windows 桌面 WPF 应用（`WinExe`）
- 目标框架：`net6.0-windows10.0.19041.0`
- 用途：监听低功耗蓝牙（BLE）心率设备，把心率、心跳脉冲和连接状态通过 OSC 发送给 VRChat。

### 1.2 它解决什么问题

VRChat 本身不直接读取智能手表的心率。这个程序在本机完成三件事：

1. 发现并读取 BLE 心率设备；
2. 把心率转换成 VRChat Avatar 可用的 OSC 参数；
3. 通过本机 UDP 把参数发送到 VRChat 的 OSC 端口。

它不需要账号、云服务或 VRChat SDK，也不需要把手表和 Windows 正式配对。数据默认只在本机回环地址上传输。

### 1.3 名称说明

- 根目录名 `HOSC` 是历史项目名/仓库名；
- 发布名、窗口标题、程序集名使用 `FS-HeartCast-OSC`；
- 代码命名空间仍是 `HOSC`，所以阅读源码时会同时看到两个名字，这是正常的，不是两套程序。

---

## 2. 当前项目功能清单

### 2.1 已实现且已接入主流程

| 功能 | 实现位置 | 说明 |
| --- | --- | --- |
| BLE 广播扫描 | [`HOSC/Services/BluetoothHeartRateScanner.cs`](HOSC/Services/BluetoothHeartRateScanner.cs#L21-L36) | 使用 `BluetoothLEAdvertisementWatcher`，`ScanningMode.Active` |
| 广播中的心率解析 | [`HOSC/Services/HeartRateParser.cs`](HOSC/Services/HeartRateParser.cs#L16-L52) | 解析 `0x16` Service Data 中的 `0x180D` 心率服务数据 |
| GATT 心率读取 | [`HOSC/Services/GattHeartRateService.cs`](HOSC/Services/GattHeartRateService.cs#L19-L73) | 连接设备，查找 `0x180D` 服务与 `0x2A37` 特征，订阅 Notify |
| 设备列表 | [`HOSC/MainWindow.xaml`](HOSC/MainWindow.xaml#L130-L229) | 显示名称、BPM、信号、状态、地址 |
| 设备在线/离线维护 | [`HOSC/MainWindow.xaml.cs`](HOSC/MainWindow.xaml.cs#L294-L340) | 12 秒无信号标记离线，90 秒后移除 |
| 自动选择设备 | [`HOSC/MainWindow.xaml.cs`](HOSC/MainWindow.xaml.cs#L142-L149) | 当前没有活动设备或活动设备离线时，选择第一个在线设备 |
| 自动连接活动设备 | [`HOSC/MainWindow.xaml.cs`](HOSC/MainWindow.xaml.cs#L154-L158) | 活动设备有广播且尚未连接时，异步建立 GATT 连接 |
| 手动连接/断开 | [`HOSC/MainWindow.xaml.cs`](HOSC/MainWindow.xaml.cs#L518-L533) | 按钮触发 `EnsureConnectedAsync` / `DisconnectActiveAsync` |
| 清空列表 | [`HOSC/MainWindow.xaml.cs`](HOSC/MainWindow.xaml.cs#L470-L486) | 断开当前连接并清空 `_devices` |
| OSC 发送 | [`HOSC/Services/OscService.cs`](HOSC/Services/OscService.cs#L121-L137) | 自建 OSC 1.0 消息，`UdpClient.Send` |
| 多套 VRChat 参数名 | [`HOSC/Services/OscService.cs`](HOSC/Services/OscService.cs#L38-L56) | 同时发送 VRCOSC、HRtoVRChat、HeartOSC、Mi Band 风格别名 |
| 心跳脉冲 | [`HOSC/Services/OscService.cs`](HOSC/Services/OscService.cs#L152-L173) | 自定义 beat 地址发送 1 后约 120ms 发送 0 |
| OSC 测试 | [`HOSC/Services/OscService.cs`](HOSC/Services/OscService.cs#L60-L74) | 发送 72 BPM，并在 VRChat 聊天框显示测试文字 |
| 连接状态参数 | [`HOSC/Services/OscService.cs`](HOSC/Services/OscService.cs#L76-L88) | `Connected`、`Enabled`、`isHRConnected`、`isHRActive` |
| 设置持久化 | [`HOSC/Services/AppSettings.cs`](HOSC/Services/AppSettings.cs#L44-L118) | `%APPDATA%\HOSC\settings.json` |
| 开机自启 | [`HOSC/Services/StartupService.cs`](HOSC/Services/StartupService.cs#L5-L45) | 写入 `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` |
| 主题/强调色 | [`HOSC/Services/ThemeService.cs`](HOSC/Services/ThemeService.cs#L22-L77) | 深色/浅色/跟随系统，6 种强调色 |
| 多语言 | [`HOSC/Localization/AppResources.cs`](HOSC/Localization/AppResources.cs#L7-L29) | 简体中文、繁体中文、English、日本語 |
| 窗口置顶 | [`HOSC/MainWindow.xaml.cs`](HOSC/MainWindow.xaml.cs#L38-L38) | 读取 `AlwaysOnTop` 设置 |
| 设置窗口 | [`HOSC/SettingsWindow.xaml.cs`](HOSC/SettingsWindow.xaml.cs#L23-L155) | 通用、外观、关于三页 |

### 2.2 代码存在但未接入主流程 / 未完成

| 项目 | 位置 | 当前状态 |
| --- | --- | --- |
| 已配对设备刷新 | [`BluetoothHeartRateScanner.cs`](HOSC/Services/BluetoothHeartRateScanner.cs#L59-L123) | `RefreshPairedDevicesAsync()` 和 `PairedDevicesRefreshed` 事件存在，但主窗口没有调用/订阅，属于未接入功能 |
| 已配对设备音频过滤 | [`BluetoothHeartRateScanner.cs`](HOSC/Services/BluetoothHeartRateScanner.cs#L87-L87) | `IsAudioDevice()` 只在未接入的已配对设备路径中使用 |
| 平均心率 | [`OscService.cs`](HOSC/Services/OscService.cs#L46-L46) | `VRCOSC/Heartrate/Average` 实际发送瞬时 BPM，没有滑动平均计算 |
| 独立的 OSC 启用状态 | [`OscService.cs`](HOSC/Services/OscService.cs#L76-L88) | `VRCOSC/Heartrate/Enabled` 被写成连接状态，不是“OSC 是否启用” |
| Redmi Watch 6 厂商协议 | `HOSC.Diagnostics/Program.cs` | 只有独立诊断程序，主程序没有接入厂商协议 |
| 单实例运行 | `HOSC/App.xaml.cs` | 没有互斥量/单实例保护 |
| 日志/诊断面板 | 全局 | 没有文件日志、全局异常处理或诊断导出 |
| 自动化测试 | 全局 | 仓库中没有测试项目 |

### 2.3 明确不存在或不应假设存在的功能

- 没有 VRChat SDK/Mod 集成，只使用 VRChat 原生 OSC。
- 没有云同步、账号系统、遥测或远程配置。
- 没有数据库；设置只保存在 JSON 文件。
- 没有安装程序、自动更新或 CI 配置；本次未验证 EXE 是否签名。
- 没有 `LICENSE` 文件；README 也明确说明许可证尚未确定。
- 没有 `.sln`；`HOSC.Diagnostics` 不是主程序的一部分。
- 没有对心率数据做医学意义上的校准、滤波或异常诊断。

---

## 3. 技术栈和运行环境

### 3.1 技术栈

| 类别 | 内容 |
| --- | --- |
| 语言 | C#（SDK 6 默认 C# 10；使用文件作用域命名空间、record、模式匹配） |
| UI | WPF / XAML |
| 运行时 | .NET 6，`net6.0-windows10.0.19041.0` |
| BLE | WinRT：`Windows.Devices.Bluetooth`、`Windows.Devices.Bluetooth.Advertisement`、`Windows.Devices.Bluetooth.GenericAttributeProfile` |
| 缓冲区 | `Windows.Storage.Streams.IBuffer` / `DataReader` |
| 网络 | `System.Net.Sockets.UdpClient`，OSC 1.0 消息 |
| 序列化 | `System.Text.Json` |
| 注册表 | `Microsoft.Win32.Registry` |
| 本地化 | `.resx` + `ResourceManager` |
| 主题 | WPF `ResourceDictionary` + `DynamicResource` |
| 发布 | 自包含 win-x64，可选单文件 EXE |
| 第三方 NuGet | 无；仅使用 .NET/Windows SDK 自带库 |

已发布产物的依赖清单显示：`Microsoft.NETCore.App.Runtime.win-x64 6.0.18`、`Microsoft.WindowsDesktop.App.Runtime.win-x64 6.0.18`、`Microsoft.Windows.SDK.NET.Ref 10.0.19041.28`。

### 3.2 运行环境

- 操作系统：Windows 10 Build 19041 或更高版本 / Windows 11。
- 架构：x64（发布包为 win-x64）。
- 蓝牙：需要 Windows 蓝牙已开启；BLE 广播扫描需要蓝牙适配器支持。
- VRChat：需要在 VRChat 中开启 OSC（默认端口 9000）。
- 权限：`app.manifest` 使用 `asInvoker`，不需要管理员权限。
- 网络：默认只向 `127.0.0.1:9000` 发送 UDP；地址可配置。

### 3.3 本次审查环境

- 审查机：Windows `10.0.26200`，x64。
- .NET SDK：`6.0.410`。
- 已安装 .NET 6/8/9/10 运行时；项目目标仍是 .NET 6。
- 已存在发布产物：`HOSC/bin/Release/net6.0-windows10.0.19041.0/win-x64/publish/FS-HeartCast-OSC.exe`，文件版本 `1.0.0.0`，产品版本 `1.0.0`，约 175.7 MB，时间戳 2026-11-04 22:38。
- 本次没有重新构建，也没有运行该 EXE。

### 3.4 构建/发布命令（来自 README，未在本次执行）

```powershell
dotnet restore HOSC\HOSC.csproj -r win-x64
dotnet build HOSC\HOSC.csproj -c Release
```

单文件自包含发布：

```powershell
dotnet publish HOSC\HOSC.csproj -c Release -r win-x64 --self-contained true `
  -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true
```

输出目录：

```text
HOSC\bin\Release\net6.0-windows10.0.19041.0\win-x64\publish\
```

---

## 4. 项目目录结构说明

### 4.1 顶层结构

```text
HOSC/                                 仓库根目录
├─ .git/                               Git 元数据（不参与运行）
├─ .gitignore                         忽略 bin/obj、.vs、*.user、settings.json
├─ README.md                          当前唯一的项目说明文档
├─ FS-HeartCast-OSC-v1.0.0-win-x64.zip 已构建的发布包（约 69 MB，已被 Git 跟踪）
├─ HOSC/                              主 WPF 项目
│  ├─ HOSC.csproj
│  ├─ app.manifest
│  ├─ App.xaml / App.xaml.cs
│  ├─ MainWindow.xaml / MainWindow.xaml.cs
│  ├─ SettingsWindow.xaml / SettingsWindow.xaml.cs
│  ├─ Models/
│  │  └─ HeartRateDevice.cs
│  ├─ Services/
│  │  ├─ AppSettings.cs
│  │  ├─ BluetoothHeartRateScanner.cs
│  │  ├─ GattHeartRateService.cs
│  │  ├─ HeartRateParser.cs
│  │  ├─ OscService.cs
│  │  ├─ StartupService.cs
│  │  └─ ThemeService.cs
│  ├─ Localization/
│  │  └─ AppResources.cs
│  ├─ Resources/
│  │  ├─ Strings.resx
│  │  ├─ Strings.zh-Hans.resx
│  │  ├─ Strings.zh-Hant.resx
│  │  └─ Strings.ja.resx
│  ├─ Themes/
│  │  ├─ Dark.xaml
│  │  └─ Light.xaml
│  ├─ Assets/
│  │  ├─ Logo.xaml
│  │  ├─ AppIcon.ico / AppIcon.png / AppIcon.svg
│  │  ├─ logo.svg
│  │  ├─ GitHubLogo.png / BilibiliLogo.png / DouyinLogo.png / AifadianLogo.png
│  │  └─ legacy/                       换标前的旧 Logo（保留备查）
│  ├─ bin/                             构建输出（被 .gitignore 忽略）
│  └─ obj/                             中间输出（被 .gitignore 忽略）
├─ HOSC.Diagnostics/                   独立控制台诊断项目（当前未被 Git 跟踪）
│  ├─ HOSC.Diagnostics.csproj
│  ├─ Program.cs
│  ├─ bin/                             构建输出（被忽略）
│  └─ obj/                             中间输出（被忽略）
├─ logo备份/                           未跟踪的 Logo 设计备份
│  ├─ newlogo-v2.html
│  └─ newlogo-v2.png
└─ 已废弃MD文档/                       当前为空目录（没有文件）
```

### 4.2 目录/文件用途

| 路径 | 用途 | 是否参与运行 |
| --- | --- | --- |
| `HOSC/App.xaml`、`App.xaml.cs` | 应用入口、全局样式、启动初始化 | 是 |
| `HOSC/MainWindow.xaml`、`.cs` | 主界面与核心编排：扫描、设备列表、连接、OSC 发送 | 是 |
| `HOSC/SettingsWindow.xaml`、`.cs` | 设置窗口：启动、外观、关于 | 是 |
| `HOSC/Models/HeartRateDevice.cs` | 设备 UI 模型、连接状态、BPM/RSSI/地址文本 | 是 |
| `HOSC/Services/BluetoothHeartRateScanner.cs` | BLE 广播扫描、已配对设备刷新（后者未接入） | 是 |
| `HOSC/Services/HeartRateParser.cs` | 心率广播/GATT 负载解析 | 是 |
| `HOSC/Services/GattHeartRateService.cs` | GATT 连接、服务/特征发现、Notify 订阅 | 是 |
| `HOSC/Services/OscService.cs` | OSC 编码、UDP 发送、心跳复位、聊天框测试 | 是 |
| `HOSC/Services/AppSettings.cs` | 设置模型、加载/保存/清洗 | 是 |
| `HOSC/Services/ThemeService.cs` | 深色/浅色/跟随系统、强调色 | 是 |
| `HOSC/Services/StartupService.cs` | 开机自启注册表读写 | 是 |
| `HOSC/Localization/AppResources.cs` | 多语言资源访问 | 是 |
| `HOSC/Resources/Strings*.resx` | 4 种语言的字符串资源 | 是 |
| `HOSC/Themes/Dark.xaml`、`Light.xaml` | 主题画刷 | 是 |
| `HOSC/Assets/Logo.xaml` | 应用内矢量 Logo（`AppLogo`） | 是 |
| `HOSC/Assets/legacy/` | 旧 Logo 备份 | 否（仅存档） |
| `HOSC.Diagnostics/` | 独立 BLE/GATT 诊断工具，疑似用于厂商协议探测 | 否（不在主项目构建中） |
| `logo备份/` | 新 Logo 设计源/预览备份 | 否 |
| `FS-HeartCast-OSC-v1.0.0-win-x64.zip` | 已构建发布包 | 否（分发用） |
| `bin/`、`obj/` | 构建产物 | 否（可再生成） |

### 4.3 关于 `HOSC.Diagnostics`

`HOSC.Diagnostics` 是一个独立的 `Exe` 项目，目标框架与主程序相同，但没有被 `HOSC.csproj` 引用，也没有 `.sln` 把它纳入构建。`Program.cs` 的行为包括：

- 默认连接硬编码地址 `0x720562DAE063`；
- 枚举全部 GATT 服务/特征；
- 订阅所有 Notify/Indicate 特征；
- 向所有可写特征发送疑似小米/Redmi 手表的 protobuf 认证和读取设备信息命令；
- 等待 60 秒并打印通知。

从代码行为看，它更像一个用于 Redmi Watch 6 连接性/厂商协议逆向的诊断工具，而不是主程序的一部分。该目录当前在 `git status` 中显示为未跟踪（`?? HOSC.Diagnostics/`）。本文件只记录，不评价其安全性，也不建议在未知设备上运行。

---

## 5. 核心模块说明

### 5.1 应用入口：`App.xaml` / `App.xaml.cs`

入口在 [`HOSC/App.xaml.cs`](HOSC/App.xaml.cs#L8-L25)：

1. `SettingsService.Load()` 读取 `%APPDATA%\HOSC\settings.json`；
2. `ThemeService.Apply(settings.Theme)` 加载深色/浅色资源字典；
3. `ThemeService.StartSystemThemeWatcher()` 启动系统主题轮询；
4. `AppResources.Instance.SetLanguage(settings.Language)` 设置语言；
5. 创建并显示 `MainWindow`；
6. `OnExit` 时再次保存设置。

`App.xaml` 定义了全局 Button/TextBox/ComboBox/ComboBoxItem/CheckBox 样式，使用 `DynamicResource` 引用主题画刷。它没有设置 `StartupUri`，窗口由代码创建。

注意：

- 没有全局异常处理；
- 没有单实例检查；
- 如果 `MainWindow` 构造或初始化失败，应用会直接退出。

### 5.2 主窗口：`MainWindow.xaml` / `MainWindow.xaml.cs`

主窗口是应用的“编排层”，承担了大部分业务逻辑（代码后置，不是严格 MVVM）。

关键字段（[`MainWindow.xaml.cs`](HOSC/MainWindow.xaml.cs#L16-L25)）：

- `_devices`：`ObservableCollection<HeartRateDevice>`，绑定 DataGrid；
- `_scanner`：BLE 广播扫描器；
- `_gatt`：当前 GATT 连接服务（全局只有一个）；
- `_osc`：OSC 发送服务；
- `_maintenanceTimer`：2 秒一次的 UI 定时器；
- `_activeDevice`：当前 UI 选中的设备；
- `_connectedDevice`：当前 GATT 连接对应的设备；
- `_isScanning`、`_scanFailed`：扫描状态；
- `_initializingUi`：防止初始化时触发保存。

关键方法：

| 方法 | 位置 | 作用 |
| --- | --- | --- |
| 构造函数 | [`#L27-L58`](HOSC/MainWindow.xaml.cs#L27-L58) | 初始化 UI、事件、定时器、设置、自动扫描 |
| `OnAdvertisementReceived` | [`#L118-L169`](HOSC/MainWindow.xaml.cs#L118-L169) | 处理广播：建/更新设备、选中、自动连接、发送广播 BPM |
| `EnsureConnectedAsync` | [`#L186-L230`](HOSC/MainWindow.xaml.cs#L186-L230) | 建立 GATT 连接，更新设备状态，发送连接状态 |
| `DisconnectActiveAsync` | [`#L232-L245`](HOSC/MainWindow.xaml.cs#L232-L245) | 断开当前连接并发送 `Connected=false` |
| `OnGattHeartRate` | [`#L247-L270`](HOSC/MainWindow.xaml.cs#L247-L270) | 处理 GATT 心率通知，更新 BPM，必要时发 OSC |
| `OnGattDisconnected` | [`#L272-L292`](HOSC/MainWindow.xaml.cs#L272-L292) | 处理底层断开，清空 `_connectedDevice` |
| `MaintenanceTick` | [`#L294-L340`](HOSC/MainWindow.xaml.cs#L294-L340) | 离线/移除/断开维护 |
| `SendHeartRateToOsc` | [`#L379-L398`](HOSC/MainWindow.xaml.cs#L379-L398) | 校验端点并调用 `OscService.SendHeartRate` |
| `SendConnectedStateToOsc` | [`#L400-L412`](HOSC/MainWindow.xaml.cs#L400-L412) | 发送连接状态参数 |
| `SaveSettingsFromUi` | [`#L434-L447`](HOSC/MainWindow.xaml.cs#L434-L447) | 从 UI 收集 OSC 设置并保存 |
| `DevicesGrid_SelectionChanged` | [`#L488-L516`](HOSC/MainWindow.xaml.cs#L488-L516) | 切换活动设备，断开旧连接，连接新设备 |
| `Window_Closing` | [`#L564-L572`](HOSC/MainWindow.xaml.cs#L564-L572) | 停止定时器/扫描、断开、保存、释放 OSC |

UI 结构：

- 顶部：Logo、标题、扫描状态、开始/停止扫描、清空、设置；
- 左侧：实时 BPM 大数字 + 设备 DataGrid；
- 右侧：VRChat OSC 开关、活动设备、连接按钮、OSC 地址/端口/HR 地址/Beat 地址、OSC 测试、状态文字。

### 5.3 设备模型：`HeartRateDevice`

[`HOSC/Models/HeartRateDevice.cs`](HOSC/Models/HeartRateDevice.cs#L15-L181) 是 `INotifyPropertyChanged` 模型，保存：

- `Address`（ulong，BLE 地址）；
- `DisplayName`（空名称时显示本地化的“未知设备”）；
- `Bpm` / `BpmText`；
- `SignalText`（`RSSI dBm`）；
- `StatusText`（离线/广播中/连接中/已连接/连接失败）；
- `LastSeenText`、`AddressText`；
- `IsOnline`、`HasHeartRateService`、`ConnectionState`。

重要方法：

- `MarkSeen(name, advertisedBpm, rssi, timestamp)`：更新名称/BPM/RSSI/最后时间，置为在线；
- `SetConnectedBpm(bpm)`：GATT 通知更新 BPM，置为在线；
- `SetConnectionState(state)`：更新连接状态；
- `MarkOffline()`：置为离线；
- `RefreshLocalizedTexts()`：语言切换后刷新显示名称/状态。

注意：`MarkSeen` 在广播没有 BPM 时会保留旧 BPM；`MarkOffline` 不会清空 BPM；`SetConnectedBpm` 在离线转在线时只通知 `IsOnline`，没有通知 `StatusText`（见 P2-4、P2-5）。

### 5.4 BLE 广播扫描：`BluetoothHeartRateScanner`

[`HOSC/Services/BluetoothHeartRateScanner.cs`](HOSC/Services/BluetoothHeartRateScanner.cs#L7-L194)

职责：

- 创建 `BluetoothLEAdvertisementWatcher`，使用 Active 模式；
- 订阅 `Received` 和 `Stopped`；
- 对每个广播做名称/服务数据过滤；
- 通过 `HeartRateParser.TryParse` 解析广播中的 BPM；
- 通过 `BluetoothAdvertisementResult` 把地址、RSSI、名称、时间戳、BPM、是否含心率服务传给 UI。

关键逻辑：

- `Start()`：如果已有 watcher 直接返回；否则创建并启动；
- `Stop()`：取消订阅并停止；
- `OnReceived()`：过滤空名称/无心率服务/无 BPM 且名称不像心率设备的广播；
- `LooksLikeHeartRateDevice()`：名称包含 HUAWEI/HONOR/WATCH/BAND/FIT/HEART/XIAOMI/MI BAND/MIBAND/AMAZFIT/GT；
- `IsAudioDevice()`：名称包含 FREEBUDS/HEADSET/HEADPHONE/SPEAKER/AUDIO；
- `RefreshPairedDevicesAsync()`：枚举已配对设备，过滤名称/音频设备，触发 `PairedDevicesRefreshed`。

当前问题：

- `RefreshPairedDevicesAsync` 和 `PairedDevicesRefreshed` 没有被 `MainWindow` 使用；
- `IsAudioDevice` 只在已配对路径使用，广播路径没有使用；
- `OnStopped` 把 `_watcher` 置空但没有取消事件订阅/释放；
- `Start()` 没有 try/catch。

### 5.5 心率解析：`HeartRateParser`

[`HOSC/Services/HeartRateParser.cs`](HOSC/Services/HeartRateParser.cs#L6-L112)

常量：

- `HeartRateServiceUuid16 = 0x180D`
- `HeartRateServiceUuid = 0000180d-0000-1000-8000-00805f9b34fb`
- `HeartRateMeasurementUuid = 00002a37-0000-1000-8000-00805f9b34fb`

`TryParse(BluetoothLEAdvertisement)`：

1. 遍历 `DataSections`；
2. 只处理 `DataType == 0x16`（16-bit Service Data）；
3. 读取前两个字节作为 16-bit Service UUID；
4. 如果是 `0x180D`，把剩余字节交给 `ParseHeartRatePayload`。

`ParseHeartRatePayload(byte[])`：

- 1 字节：当作无 flags 的 BPM；
- 2 字节以上：第一个字节是 flags；
- flags bit0 = 1：BPM 为 16 位小端（需要 3 字节）；
- flags bit0 = 0：BPM 为 8 位；
- 拒绝 `<=0` 或 `>255` 的 BPM；
- 返回 `HeartRateReading(Bpm, HasContactSupport, ContactDetected, HasRrInterval)`。

注意：

- 只支持 `0x16` Service Data，不支持 32-bit Service Data、Manufacturer Data 或 GATT Read 回退；
- `HeartRateReading` 的 contact/RR 标志目前没有被业务使用；
- `payload.Length == 1` 的“裸 BPM”回退在 Service Data 场景下可能把 flags 当成 BPM（边缘情况）；
- `payload.Length < 2` 检查在 `==1` 返回之后不可达；
- 16 位 BPM 被限制到 255，虽然标准允许更大值，但心率实际很少超过 255。

### 5.6 GATT 心率服务：`GattHeartRateService`

[`HOSC/Services/GattHeartRateService.cs`](HOSC/Services/GattHeartRateService.cs#L7-L138)

职责：

- `ConnectAsync(address)`：
  1. 先 `DisconnectAsync()` 清理旧连接；
  2. `BluetoothLEDevice.FromBluetoothAddressAsync(address)`；
  3. 订阅 `ConnectionStatusChanged`；
  4. `GetGattServicesForUuidAsync(0x180D, Uncached)`；
  5. `GetCharacteristicsForUuidAsync(0x2A37, Uncached)`；
  6. 写 CCCD 为 `Notify`；
  7. 订阅 `ValueChanged`；
  8. 返回是否成功。
- `DisconnectAsync()`：取消 `ValueChanged`，写 CCCD `None`，释放 service/device。
- `OnHeartRateValueChanged()`：读取 `IBuffer`，调用 `HeartRateParser.ParseHeartRatePayload`，触发 `HeartRateChanged`。
- `OnConnectionStatusChanged()`：底层断开时触发 `Disconnected`。

`IsConnected` 的当前实现是：

```csharp
public bool IsConnected => _device != null && _characteristic != null;
```

这意味着底层已经断开时，只要字段还没清空，`IsConnected` 仍可能为 `true`。

### 5.7 OSC 服务：`OscService`

[`HOSC/Services/OscService.cs`](HOSC/Services/OscService.cs#L7-L248)

职责：

- 持有一个 `UdpClient`；
- `SendHeartRate`：发送 BPM、心跳、VRCOSC 兼容参数、常见别名；
- `SendConnectedState`：发送连接状态四参数；
- `SendTest`：发送 72 BPM + 聊天框测试；
- `SendChatboxTest`：发送 `/chatbox/input`，2.5 秒后清空；
- `Send`：构建 OSC 消息并发送；
- `BuildMessage`：手写 OSC 1.0 编码（地址、类型标签、参数、4 字节对齐）；
- `ScheduleBeatReset`：120ms 后把自定义 beat 地址复位为 0。

重要行为：

- 心率发送有 400ms 节流：`now - _lastBeatTimestamp < 400` 时直接返回 `true`，不再发送任何参数；
- 心跳开关 `_heartBeatToggle` 每次发送翻转；
- `VRCOSC/Heartrate/Beat` 发送的是翻转值，不是 1→0 脉冲；
- `VRCOSC/Heartrate/Enabled` 在 `SendConnectedState` 中与 `connected` 相同；
- `VRCOSC/Heartrate/Average` 发送瞬时 BPM；
- `HeartRateFloat01` 没有 clamp；
- 一次心跳发送 14 个 UDP 包（不含连接状态包），没有使用 OSC bundle。

### 5.8 设置：`AppSettings` / `SettingsService`

[`HOSC/Services/AppSettings.cs`](HOSC/Services/AppSettings.cs#L7-L118)

设置模型字段：

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `Language` | 根据系统 UI 文化自动选择 | `en` / `zh-Hans` / `zh-Hant` / `ja` |
| `Theme` | `System` | `System` / `Light` / `Dark` |
| `AccentColor` | `Teal` | `Teal` / `Blue` / `Purple` / `Green` / `Orange` / `Pink` |
| `AutoStartScan` | `true` | 启动时自动扫描 |
| `AlwaysOnTop` | `false` | 窗口置顶 |
| `OscEnabled` | `false` | 是否向 VRChat 发送 |
| `OscAddress` | `127.0.0.1` | OSC 目标主机 |
| `OscPort` | `9000` | OSC 目标端口 |
| `OscHrAddress` | `/avatar/parameters/HR` | 心率参数地址 |
| `OscBeatAddress` | `/avatar/parameters/isHRBeat` | 心跳参数地址 |

`SettingsService`：

- `SettingsPath`：`%APPDATA%\HOSC\settings.json`；
- `Load()`：文件存在则反序列化，异常时回退默认值；然后 `Sanitize()`；
- `Save()`：`Directory.CreateDirectory` + `File.WriteAllText`；异常被吞掉；
- `Sanitize()`：修正端口、地址、语言、主题、强调色；不校验 `OscHrAddress`/`OscBeatAddress` 是否以 `/` 开头。

### 5.9 主题：`ThemeService`

[`HOSC/Services/ThemeService.cs`](HOSC/Services/ThemeService.cs#L8-L102)

- `Apply(mode)`：根据 `System`/`Light`/`Dark` 选择 `Themes/Light.xaml` 或 `Themes/Dark.xaml`，清空 `Application.Current.Resources.MergedDictionaries` 后加入主题字典，再应用强调色；
- `ApplyAccent(accent)`：从 6 种颜色中取 `AccentBrush`、`AccentTextBrush`、`RowSelectedBrush`；
- `StartSystemThemeWatcher()`：3 秒一次的 `DispatcherTimer`，当 `Theme == "System"` 时重新 `Apply("System")`；
- `IsSystemLight()`：读 `HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize` 的 `AppsUseLightTheme`/`SystemUsesLightTheme`。

注意：`Apply` 会清空所有应用级合并字典，不只是主题字典；系统主题轮询即使主题没变也会重复应用。

### 5.10 开机自启：`StartupService`

[`HOSC/Services/StartupService.cs`](HOSC/Services/StartupService.cs#L5-L45)

- 注册表路径：`HKCU\Software\Microsoft\Windows\CurrentVersion\Run`
- 值名：`FS-HeartCast-OSC`
- 值内容：`"<Environment.ProcessPath>"`
- `IsEnabled()` 只检查值存在且非空，不检查路径是否仍指向当前 EXE。

### 5.11 本地化：`AppResources` + `.resx`

[`HOSC/Localization/AppResources.cs`](HOSC/Localization/AppResources.cs#L7-L29)

- 单例 `AppResources.Instance`；
- `ResourceManager("HOSC.Resources.Strings", assembly)`；
- 索引器 `this[key]` 返回当前语言字符串，找不到时返回 key；
- `SetLanguage(code)` 创建 `CultureInfo` 并触发 `PropertyChanged(string.Empty)`。

资源文件：

- `Strings.resx`（英语/中性）
- `Strings.zh-Hans.resx`
- `Strings.zh-Hant.resx`
- `Strings.ja.resx`

四个文件都包含 69 个同名 key，未发现缺失 key。注意 `DeviceLastSeen` 等 key 当前没有被 UI 使用。

### 5.12 设置窗口：`SettingsWindow`

[`HOSC/SettingsWindow.xaml`](HOSC/SettingsWindow.xaml#L1-L355) / [`HOSC/SettingsWindow.xaml.cs`](HOSC/SettingsWindow.xaml.cs#L10-L156)

三个页面：

- General：自动扫描、开机自启、窗口置顶；
- Appearance：语言、主题、强调色；
- About：版本、邮箱、社区链接、隐私说明。

事件处理：

- `CategoryList_SelectionChanged` 切换页面可见性；
- `AutoStartScan_Changed` 保存设置；
- `StartWithWindows_Changed` 调用 `StartupService.SetEnabled` 并回读；
- `AlwaysOnTop_Changed` 保存并更新 Owner.Topmost；
- `Language_SelectionChanged` 更新 `AppResources`、保存、刷新版本文字；
- `Theme_SelectionChanged` 调用 `ThemeService.Apply`；
- `Accent_SelectionChanged` 调用 `ThemeService.ApplyAccent`；
- `Hyperlink_RequestNavigate` 用 `Process.Start` 打开外部链接。

### 5.13 诊断项目：`HOSC.Diagnostics`

[`HOSC.Diagnostics/Program.cs`](HOSC.Diagnostics/Program.cs#L1-L326)

这是一个独立控制台程序，不是主程序的一部分。它：

- 默认连接 `0x720562DAE063`（可用命令行参数覆盖为十六进制地址）；
- 枚举全部 GATT 服务/特征；
- 读取可读特征；
- 订阅所有 Notify/Indicate 特征；
- 发送两段疑似小米/Redmi 手表的 protobuf 命令（认证用户 ID、读取设备信息）；
- 以 18 字节分块写入，等待 ack；
- 60 秒后取消并清理。

它的用途、目标设备和安全性没有文档说明，属于“历史记录/待验证”。

---

## 6. 完整数据流和运行原理

### 6.1 启动生命周期

```text
进程启动
  → App.OnStartup
    → SettingsService.Load()                 读取 %APPDATA%\HOSC\settings.json
    → ThemeService.Apply(theme)              加载 Dark/Light 资源字典
    → ThemeService.StartSystemThemeWatcher() 每 3 秒检查系统主题
    → AppResources.SetLanguage(language)     设置 UI 语言
    → new MainWindow()
      → InitializeComponent()
      → 绑定设备集合、订阅 scanner/gatt 事件
      → Topmost = settings.AlwaysOnTop
      → LoadSettingsIntoUi()
      → 启动 2 秒维护定时器
      → UpdateNoDevices() / RefreshUiTexts()
      → 如果 AutoStartScan：StartScanning()
    → MainWindow.Show()
```

### 6.2 BLE 广播路径（主路径）

```text
手表/心率带
  → BLE 广播（可能包含 0x180D Service Data，也可能只有名称/服务 UUID）
  → BluetoothLEAdvertisementWatcher.Received
  → BluetoothHeartRateScanner.OnReceived
       - 读取 LocalName
       - HeartRateParser.TryParse(advertisement)
       - 检查 ServiceUuids 是否含 0x180D
       - 过滤空名称/无心率信息/名称不像心率设备的广播
  → AdvertisementReceived 事件
  → MainWindow.OnAdvertisementReceived（Dispatcher.Invoke 到 UI 线程）
       - 按 Address 查找/创建设备
       - device.MarkSeen(name, advertisedBpm, rssi, timestamp)
       - 如果含 0x180D，device.SetHasHeartRateService()
       - 如果当前没有活动设备或活动设备离线，选中第一个在线设备
       - 如果活动设备尚未连接，EnsureConnectedAsync(device)
       - 如果活动设备尚未连接、OSC 开启、有 BPM，SendHeartRateToOsc(device.Bpm)
  → HeartRateDevice 属性变化
  → DataGrid / 实时 BPM 文本更新
```

关键点：

- 整体链路与“手表 → 采集 → 处理 → 传输 → VRChat → Avatar 参数”一致；实际差异是采集层有“广播”和“GATT”两条路径，VRChat 接收/Avatar 映射不在本仓库，由 VRChat 自身完成。
- 广播路径不要求配对；
- 广播中是否直接带 BPM 取决于设备；很多设备只广播服务 UUID，需要 GATT；
- 代码会对“活动设备”自动发起 GATT 连接，即使广播里已经有 BPM（见 P1-3）；
- 广播没有 BPM 时，`MarkSeen` 保留旧 BPM，可能导致旧值继续显示/发送（见 P2-4）。
- 数据处理实际很薄：解析 flags/BPM、拒绝 `<=0`/`>255`、GATT 只取 BPM（忽略 contact/RR 标志）、OSC 发送前做公式转换和 400ms 节流；没有滑动平均、低通/卡尔曼滤波、异常值剔除或丢包补偿。

### 6.3 GATT 路径（按需连接）

```text
MainWindow.EnsureConnectedAsync(device)
  → device.SetConnectionState(Connecting)
  → GattHeartRateService.ConnectAsync(address)
       → BluetoothLEDevice.FromBluetoothAddressAsync
       → 订阅 ConnectionStatusChanged
       → GetGattServicesForUuidAsync(0x180D, Uncached)
       → GetCharacteristicsForUuidAsync(0x2A37, Uncached)
       → WriteClientCharacteristicConfigurationDescriptorAsync(Notify)
       → 订阅 ValueChanged
  → 成功：device.SetConnectionState(Connected)
          SendConnectedStateToOsc(true)
  → 失败：device.SetConnectionState(Error)
          _connectedDevice = null

GATT 通知
  → GattHeartRateService.OnHeartRateValueChanged
       → DataReader 读取 byte[]
       → HeartRateParser.ParseHeartRatePayload
       → HeartRateChanged(bpm)
  → MainWindow.OnGattHeartRate（Dispatcher.Invoke）
       → device.SetConnectedBpm(bpm)
       → UpdateActiveDeviceText()
       → 如果 OSC 开启且该设备是活动设备：SendHeartRateToOsc(bpm)
```

注意：

- GATT 连接是“临时连接”，不改变手表的配对状态；
- 代码在连接前会先 `DisconnectAsync()` 清理旧连接；
- 同一个 `_gatt` 实例被多个异步路径共享，存在竞态（见 P1-1）；
- 底层断开时 `GattHeartRateService` 只触发 `Disconnected`，不主动清理字段（见 P1-4）。

### 6.4 OSC 编码与 VRChat 接收

`OscService.SendHeartRate` 的发送顺序（以一次心跳为例）：

1. 自定义 HR 地址（默认 `/avatar/parameters/HR`）：int BPM；
2. 自定义 Beat 地址（默认 `/avatar/parameters/isHRBeat`）：int `1`；
3. `/avatar/parameters/HeartBeatToggle`：bool 翻转值；
4. `/avatar/parameters/VRCOSC/Heartrate/Value`：int BPM；
5. `/avatar/parameters/VRCOSC/Heartrate/Beat`：bool 翻转值；
6. `/avatar/parameters/VRCOSC/Heartrate/Normalised`：float `clamp(BPM/240, 0, 1)`；
7. `/avatar/parameters/VRCOSC/Heartrate/Average`：int BPM（实际是瞬时值）；
8. `/avatar/parameters/HeartRateInt`：int BPM；
9. `/avatar/parameters/HeartRate3`：int BPM；
10. `/avatar/parameters/Heartrate3`：int BPM；
11. `/avatar/parameters/HeartRateFloat01`：float `BPM/240`；
12. `/avatar/parameters/HRPercent`：float `BPM/255`；
13. `/avatar/parameters/HeartRate`：float `BPM/127.5 - 1`；
14. 120ms 后，自定义 Beat 地址发送 int `0`。

`SendConnectedState` 发送：

- `/avatar/parameters/VRCOSC/Heartrate/Connected`：bool；
- `/avatar/parameters/VRCOSC/Heartrate/Enabled`：bool（当前等于 `connected`）；
- `/avatar/parameters/isHRConnected`：bool；
- `/avatar/parameters/isHRActive`：bool。

OSC 编码细节：

- 地址和类型标签都是 null 结尾、4 字节对齐的字符串；
- int 为大端 4 字节；
- float 为大端 IEEE-754 4 字节；
- bool 使用类型标签 `T`/`F`，不写额外负载；
- string 使用 UTF-8 + null + 4 字节对齐；
- 每个参数是独立 UDP 包，没有 bundle。

VRChat 侧：

- 需要在 VRChat 中开启 OSC；
- 默认监听 `127.0.0.1:9000`；
- Avatar 的 Animator 参数名需要与发送地址一致；
- 本项目不包含 Avatar/Prefab 工程，也不直接修改 VRChat 配置。

### 6.5 设备状态维护

`MaintenanceTick` 每 2 秒执行：

```text
for each device in _devices.ToList():
    isConnected = (_connectedDevice == device) && _gatt.IsConnected

    if device.IsOnline && !isConnected && now - LastSeenUtc > 12s:
        if 活动设备 && OSC 开启 && 端点有效:
            发送 Beat 地址 = 0
        device.MarkOffline()

    if now - LastSeenUtc > 90s:
        if _connectedDevice == device:
            _connectedDevice = null
            device.SetConnectionState(None)
            SendConnectedStateToOsc(false)
            _ = _gatt.DisconnectAsync()
        _devices.Remove(device)

if _activeDevice 已不在 _devices:
    _activeDevice = null
    DevicesGrid.SelectedItem = null
```

关键阈值：

- 2 秒：维护定时器间隔；
- 12 秒：无信号标记离线；
- 90 秒：从列表移除；
- 120ms：心跳脉冲复位；
- 400ms：OSC 心率发送节流；
- 2500ms：聊天框测试清空延迟。

### 6.6 关闭流程

`MainWindow.Window_Closing`：

1. 停止维护定时器；
2. 停止 BLE 扫描；
3. `_ = _gatt.DisconnectAsync()`（未 await）；
4. 发送 `Connected=false`；
5. 保存设置；
6. `_osc.Dispose()`。

`App.OnExit` 再次保存设置。

注意：GATT 清理是 fire-and-forget，进程可能在 CCCD 取消订阅完成前退出（见 P2-10）。

### 6.7 实际实现与 README 描述的差异

| README 描述 | 实际代码 | 结论 |
| --- | --- | --- |
| “对仅通过 GATT 暴露心率的设备自动建立临时连接” | 对所有活动设备都会自动连接，即使广播里已有 BPM | 部分不符；见 P1-3 |
| “已与 Windows 配对、名称符合特征的设备也会被列出” | `RefreshPairedDevicesAsync` 存在但主窗口没有调用/订阅 | 未实现/未接入；见 P2-2 |
| “自动过滤耳机、音箱等音频设备” | `IsAudioDevice` 只在未接入的已配对路径使用；广播路径没有过滤 | 广播路径不符；见 P2-3 |
| `VRCOSC/Heartrate/Average` 为“平均值” | 发送瞬时 BPM | 不符；见 P1-6 |
| `VRCOSC/Heartrate/Enabled` 为“OSC 启用状态” | 发送连接状态 | 不符；见 P1-6 |
| `VRCOSC/Heartrate/Beat` 为“VRCOSC 兼容心跳” | 发送翻转 bool，不复位 | 语义需按目标 Prefab 验证；见 P1-6 |
| “所有数据仅在本机处理” | 默认地址是本机，但 OSC 地址可改为远程主机 | 默认成立，但不是强制本机；见 P3-11 |

---

## 7. 关键代码文件及入口位置

| 入口/功能 | 文件 | 关键位置 |
| --- | --- | --- |
| 应用启动 | [`HOSC/App.xaml.cs`](HOSC/App.xaml.cs) | `OnStartup` L8-L19，`OnExit` L21-L25 |
| 全局样式 | [`HOSC/App.xaml`](HOSC/App.xaml) | L4-L168 |
| 主窗口构造 | [`HOSC/MainWindow.xaml.cs`](HOSC/MainWindow.xaml.cs) | L27-L58 |
| 广播处理 | [`HOSC/MainWindow.xaml.cs`](HOSC/MainWindow.xaml.cs) | L118-L169 |
| GATT 连接 | [`HOSC/MainWindow.xaml.cs`](HOSC/MainWindow.xaml.cs) | L186-L230 |
| GATT 通知 | [`HOSC/MainWindow.xaml.cs`](HOSC/MainWindow.xaml.cs) | L247-L270 |
| 状态维护 | [`HOSC/MainWindow.xaml.cs`](HOSC/MainWindow.xaml.cs) | L294-L340 |
| 设备切换 | [`HOSC/MainWindow.xaml.cs`](HOSC/MainWindow.xaml.cs) | L488-L516 |
| 关闭清理 | [`HOSC/MainWindow.xaml.cs`](HOSC/MainWindow.xaml.cs) | L564-L572 |
| 主界面 XAML | [`HOSC/MainWindow.xaml`](HOSC/MainWindow.xaml) | L1-L293 |
| 设备模型 | [`HOSC/Models/HeartRateDevice.cs`](HOSC/Models/HeartRateDevice.cs) | L15-L181 |
| 广播扫描 | [`HOSC/Services/BluetoothHeartRateScanner.cs`](HOSC/Services/BluetoothHeartRateScanner.cs) | `Start` L21-L36，`OnReceived` L125-L150，`RefreshPairedDevicesAsync` L59-L123 |
| 心率解析 | [`HOSC/Services/HeartRateParser.cs`](HOSC/Services/HeartRateParser.cs) | `TryParse` L16-L52，`ParseHeartRatePayload` L54-L98 |
| GATT 服务 | [`HOSC/Services/GattHeartRateService.cs`](HOSC/Services/GattHeartRateService.cs) | `ConnectAsync` L19-L73，`DisconnectAsync` L75-L105，`OnHeartRateValueChanged` L112-L129 |
| OSC 发送 | [`HOSC/Services/OscService.cs`](HOSC/Services/OscService.cs) | `SendHeartRate` L17-L58，`SendConnectedState` L76-L88，`BuildMessage` L175-L247 |
| 设置模型/存储 | [`HOSC/Services/AppSettings.cs`](HOSC/Services/AppSettings.cs) | `AppSettings` L7-L42，`SettingsService` L44-L118 |
| 主题 | [`HOSC/Services/ThemeService.cs`](HOSC/Services/ThemeService.cs) | `Apply` L22-L38，`StartSystemThemeWatcher` L61-L77 |
| 开机自启 | [`HOSC/Services/StartupService.cs`](HOSC/Services/StartupService.cs) | L5-L45 |
| 本地化 | [`HOSC/Localization/AppResources.cs`](HOSC/Localization/AppResources.cs) | L7-L29 |
| 设置窗口 | [`HOSC/SettingsWindow.xaml.cs`](HOSC/SettingsWindow.xaml.cs) | L10-L156 |
| 诊断工具 | [`HOSC.Diagnostics/Program.cs`](HOSC.Diagnostics/Program.cs) | L1-L326 |
| 项目文件 | [`HOSC/HOSC.csproj`](HOSC/HOSC.csproj) | L1-L21 |
| 诊断项目文件 | [`HOSC.Diagnostics/HOSC.Diagnostics.csproj`](HOSC.Diagnostics/HOSC.Diagnostics.csproj) | L1-L10 |

---

## 8. 模块之间的依赖关系

### 8.1 依赖图

```text
App
 ├─ SettingsService ── AppSettings
 ├─ ThemeService ───── WPF Resources + Registry
 ├─ AppResources ───── .resx / ResourceManager
 └─ MainWindow
     ├─ BluetoothHeartRateScanner ── HeartRateParser + WinRT BLE
     ├─ GattHeartRateService ─────── HeartRateParser + WinRT GATT
     ├─ OscService ───────────────── UdpClient
     ├─ HeartRateDevice
     ├─ AppResources
     └─ SettingsService

SettingsWindow
 ├─ SettingsService
 ├─ StartupService ─── Registry
 ├─ ThemeService
 └─ AppResources

HOSC.Diagnostics（独立）
 └─ WinRT BLE/GATT
```

### 8.2 调用/事件关系

| 调用方 | 被调用方 | 方式 |
| --- | --- | --- |
| `App` | `SettingsService` | 静态方法 |
| `App` | `ThemeService` | 静态方法 |
| `App` | `AppResources` | 单例 |
| `App` | `MainWindow` | 构造/显示 |
| `MainWindow` | `BluetoothHeartRateScanner` | 字段 + 事件 |
| `MainWindow` | `GattHeartRateService` | 字段 + 事件 |
| `MainWindow` | `OscService` | 字段 |
| `MainWindow` | `HeartRateDevice` | 集合/属性 |
| `MainWindow` | `SettingsService` | 静态方法 |
| `BluetoothHeartRateScanner` | `HeartRateParser` | 静态方法 |
| `GattHeartRateService` | `HeartRateParser` | 静态方法 |
| `SettingsWindow` | `StartupService` | 静态方法 |
| `SettingsWindow` | `ThemeService` | 静态方法 |
| `SettingsWindow` | `SettingsService` | 静态方法 |

### 8.3 依赖特点

- 没有依赖注入容器；服务在主窗口中直接 `new`。
- 事件把底层（扫描/GATT）与 UI 解耦，但事件处理仍集中在 `MainWindow`。
- `HOSC.Diagnostics` 与主项目没有项目引用/命名空间依赖。
- 没有第三方包依赖，部署简单。
- 主要耦合点：`MainWindow` 同时管理 UI、设备状态、连接生命周期和 OSC 发送，职责偏重。

---

## 9. 配置说明和启动方式

### 9.1 配置文件

路径：

```text
%APPDATA%\HOSC\settings.json
```

示例（字段含义见 5.8）：

```json
{
  "Language": "zh-Hans",
  "Theme": "System",
  "AccentColor": "Teal",
  "AutoStartScan": true,
  "AlwaysOnTop": false,
  "OscEnabled": true,
  "OscAddress": "127.0.0.1",
  "OscPort": 9000,
  "OscHrAddress": "/avatar/parameters/HR",
  "OscBeatAddress": "/avatar/parameters/isHRBeat"
}
```

说明：

- 文件不存在时使用默认值；
- JSON 解析失败时回退默认值，原文件不会被自动备份；
- `OscPort` 非法时回退 9000；
- `OscAddress` 空白时回退 `127.0.0.1`；
- `Language`、`Theme`、`AccentColor` 非法时回退默认值；
- `OscHrAddress`/`OscBeatAddress` 没有强制校验，但 UI 空白时会用默认地址。

### 9.2 开机自启

注册表：

```text
HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Run
值名：FS-HeartCast-OSC
值：  "<当前 EXE 路径>"
```

注意：如果以框架依赖方式运行（`dotnet HOSC.dll`），`Environment.ProcessPath` 可能是 `dotnet.exe`，注册表项不会带 DLL 参数，开机自启会失败。发布版是自包含 EXE，通常没有这个问题。

### 9.3 启动方式

开发/直接运行：

```powershell
dotnet run --project HOSC\HOSC.csproj
```

或先构建后运行：

```powershell
dotnet build HOSC\HOSC.csproj -c Release
HOSC\bin\Release\net6.0-windows10.0.19041.0\HOSC.exe
```

发布版：

```text
解压 FS-HeartCast-OSC-v1.0.0-win-x64.zip
运行 FS-HeartCast-OSC.exe
```

运行前确认：

1. Windows 蓝牙已开启；
2. 手表已开启心率广播（华为等手表：设置 → HR Data Broadcasts）；
3. 如需发送 VRChat：VRChat 已开启 OSC，地址/端口正确；
4. Avatar 已声明对应参数。

### 9.4 VRChat 配置

1. VRChat 菜单 → Options → OSC，确认 OSC 已开启；
2. 默认发送到 `127.0.0.1:9000`；
3. 在 Avatar 的 Animator 中声明参数；
4. 先用应用内“OSC 测试”确认 VRChat 收到参数；
5. 如果测试成功但 Avatar 不动，检查参数名/类型是否匹配。

---

## 10. 代码质量检查结果

### 10.1 重复代码和重复功能

- `HeartRateParser` 同时服务广播和 GATT 两条路径，这是合理的复用。
- `OscService` 中多次调用 `Send(...)` 发送不同参数名，属于“多协议兼容”的有意重复，不是坏味道；但可以用表驱动减少重复。
- `HeartRateDevice` 与 `MainWindow` 都维护“在线/连接状态”的一部分，状态来源不唯一，容易出现 UI 与底层不一致。
- `BluetoothHeartRateScanner` 中的 `RefreshPairedDevicesAsync` 与广播路径有部分重复的设备过滤逻辑，但未接入。
- `HOSC.Diagnostics` 与主程序都实现了 GATT 枚举/订阅逻辑，属于独立工具，不必强行合并。

### 10.2 未使用的函数、变量、类及参数

经静态搜索确认（未发现反射/动态调用）：

- `HeartRateReading.HasContactSupport`、`ContactDetected`、`HasRrInterval`：解析后没有被业务使用；
- `BluetoothHeartRateScanner.PairedDevicesRefreshed`、`RefreshPairedDevicesAsync`：主窗口没有订阅/调用；
- `BluetoothHeartRateScanner.IsScanning`：主窗口使用自己的 `_isScanning`，该属性未被外部读取；
- `HeartRateDevice.HasHeartRateService`：只在扫描器里设置，UI 没有列/绑定；
- `HeartRateDevice.LastSeenText`：属性存在，DataGrid 没有“最后接收”列；
- `AppResources.LanguageCode`：未被使用；
- `DeviceLastSeen` 资源 key：四个 resx 都有，但 UI 未使用；
- `GattHeartRateService.DisposeAsync`、`BluetoothHeartRateScanner.Dispose`：没有被调用（`Stop` 被调用）；
- `BluetoothAdvertisementResult.HasHeartRateService`：只用于设置设备标志，没有进一步 UI/逻辑用途。

注意：这些“未使用”是静态可见的；在删除前仍应确认没有外部调用、XAML 绑定、反射或未来计划。

### 10.3 不必要的复杂逻辑

- `MainWindow` 同时承担 UI 编排、设备状态机、连接生命周期、OSC 发送、设置保存，职责过多。
- `EnsureConnectedAsync`、`DisconnectActiveAsync`、`DevicesGrid_SelectionChanged`、`MaintenanceTick`、`OnGattDisconnected` 都能改变 `_connectedDevice`/`_gatt`，没有统一状态机。
- `OscService` 手写 OSC 编码，逻辑简单但缺少单元测试；对 bool/float/string 的边界处理依赖调用方。
- `HeartRateParser.TryParse` 的 1 字节回退与标准 flags 语义有歧义。
- `ThemeService.Apply` 清空所有合并字典，逻辑上比“只替换主题字典”更粗暴。

### 10.4 潜在内存泄漏和资源未释放

- `GattHeartRateService` 在底层断开后不清理 `_device`/`_service`/`_characteristic`；要等下一次 `ConnectAsync`/`DisconnectAsync` 才释放。
- `MainWindow.Window_Closing` 的 `_gatt.DisconnectAsync()` 是 fire-and-forget；`DisposeAsync` 未调用。
- `BluetoothHeartRateScanner.OnStopped` 把 `_watcher` 置空但没有取消 `Received`/`Stopped` 订阅；watcher 可能被事件源持有。
- `DataReader.FromBuffer(...)` 没有 `using`；每次通知/广播创建一个 reader。
- `OscService.Dispose` 取消了 CTS，但没有 `Dispose()` 它们。
- `HOSC.Diagnostics` 的 `DataWriter` 没有释放；`Channel<byte[]>` 是无界的。
- 这些大多是小对象/短生命周期，是否构成实际泄漏取决于运行频率；目前没有看到会无限增长的大对象集合。

### 10.5 线程、异步任务及并发安全问题

- 扫描器/GATT 事件来自 WinRT 线程，`MainWindow` 用 `Dispatcher.Invoke` 切回 UI 线程；同步 Invoke 会阻塞事件线程。
- `_gatt` 是单例式共享对象，多个异步路径可同时调用 `ConnectAsync`/`DisconnectAsync`，没有锁/取消/代际号。
- `_connectedDevice` 在多个异步方法中被读写，`OnGattDisconnected` 不区分是哪一个设备的断开事件。
- `GattHeartRateService` 的 `_device`/`_service`/`_characteristic` 没有并发保护。
- `BluetoothHeartRateScanner._pairedRefreshRunning` 是普通 bool，不是线程安全标志（当前未接入）。
- `HOSC.Diagnostics` 的 `notificationCount++` 在通知回调中执行，非原子。
- `OscService._heartBeatToggle` 目前只在 UI 线程调用，暂未发现竞态；如果未来从后台线程调用需要加锁。

### 10.6 网络通信和数据延迟问题

- 每个心跳发送 14 个独立 UDP 包，没有 bundle；包丢失会导致部分参数不同步。
- 400ms 节流意味着 BPM 更新最多 2.5 次/秒；高心率时心跳脉冲会落后于真实心跳。
- `UdpClient.Send(string host, ...)` 可能每次做 DNS 解析；默认 IP 没有这个问题，自定义主机名时可能阻塞 UI。
- 广播/GATT 事件到 UI 再到 OSC 的链路包含 `Dispatcher.Invoke`，UI 忙时会增加延迟。
- 没有发送队列/优先级；OSC 测试和心率发送共享同一个 UDP socket。

### 10.7 异常处理不足

- `BluetoothLEAdvertisementWatcher.Start()` 没有 try/catch。
- `EnsureConnectedAsync` 没有 try/catch；`GattHeartRateService.ConnectAsync` 的 `DisconnectAsync` 前置调用不在 try 内。
- 大量 `catch { }` / `catch { return false; }` 没有日志。
- 没有 `DispatcherUnhandledException`/`AppDomain.UnhandledException`。
- `SettingsService.Save` 失败时用户无感知。
- `HOSC.Diagnostics` 的异常大多只打印到控制台。

### 10.8 可能影响心率实时性的性能瓶颈

- 每个广播包都同步 `Dispatcher.Invoke` 并更新多个 UI 元素。
- 每个 GATT 通知都分配 `byte[]` 并 `Dispatcher.Invoke`。
- 设置每次按键都同步写 JSON。
- 系统主题每 3 秒重建资源字典。
- 一次心跳 14 个 UDP 包。
- `MainWindow` 的线性设备查找在设备多时是 O(n)，但设备数量通常很小，不是主要瓶颈。

### 10.9 潜在安全问题

- OSC 地址/端口由用户配置；如果把地址改成远程主机，心率数据会离开本机。README 的“仅本机”应加限定。
- OSC 没有认证/加密，这是 VRChat OSC 的协议特性；不要暴露到不可信网络。
- 开机自启写入 HKCU，权限范围合理。
- `HOSC.Diagnostics` 会向所有可写特征发送厂商命令；只应在已知设备上运行。
- 没有代码签名/安装器；从网络下载 EXE 时需自行校验来源。
- 没有发现硬编码密钥、账号或远程上报。

### 10.10 文档与代码不一致

- README 的已配对设备、音频过滤、Average、Enabled、GATT-only 自动连接等描述与代码有差异（详见 6.7 和 P1/P2）。
- README 的 OSC 参数表基本覆盖了代码发送的参数，但语义说明需要修正。
- 旧设计/旧 Logo 文档引用了已删除的 `Assets/design/` 和 `Assets/legacy/README.md`。

---

## 11. 已知问题和潜在风险（P0-P3）

### 11.1 P0：严重问题

**P0 确认项：0 项。**

静态审查没有发现“必然导致项目无法启动、必然无法读取心率、必然丢失心率数据”的已确认问题。

**P0 候选：蓝牙关闭/驱动异常时的扫描启动异常（P1-5）。**

- 位置：[`HOSC/MainWindow.xaml.cs`](HOSC/MainWindow.xaml.cs#L103-L109)、[`HOSC/Services/BluetoothHeartRateScanner.cs`](HOSC/Services/BluetoothHeartRateScanner.cs#L21-L36)。
- 当前实现：默认 `AutoStartScan=true`，构造函数直接 `StartScanning()`；`_scanner.Start()` 没有 try/catch。
- 为什么可能是 P0：如果目标 Windows/蓝牙驱动在蓝牙关闭时让 `BluetoothLEAdvertisementWatcher.Start()` 抛异常，应用会在启动阶段退出，用户看不到主界面。
- 待验证：需要在实际蓝牙关闭/驱动异常/无适配器环境测试该 API 的行为。若确认抛异常，升级为 P0。
- 推荐：在 `StartScanning`/`Start` 外层捕获异常，设置 `_scanFailed`，显示“Bluetooth is off/无法启动扫描”，并记录日志。
- 修改风险：低。

### 11.2 P1：重要问题（影响核心功能、稳定性或实时性）

#### P1-1 GATT 连接/断开竞态

- **问题位置**：[`HOSC/MainWindow.xaml.cs`](HOSC/MainWindow.xaml.cs#L186-L245)、[`HOSC/MainWindow.xaml.cs`](HOSC/MainWindow.xaml.cs#L488-L516)、[`HOSC/MainWindow.xaml.cs`](HOSC/MainWindow.xaml.cs#L272-L292)、[`HOSC/Services/GattHeartRateService.cs`](HOSC/Services/GattHeartRateService.cs#L19-L105)。
- **当前实现**：`_gatt` 是单个共享对象；`EnsureConnectedAsync` 在 `await` 前就把 `_connectedDevice` 指向目标设备；`DisconnectActiveAsync`、设备切换、维护定时器、`OnGattDisconnected` 都可能启动重叠的异步操作。`GattHeartRateService.ConnectAsync` 开头 `await DisconnectAsync()`，没有锁、取消令牌或连接代际号。
- **潜在影响**：旧连接可能在新连接之后完成，导致 `_gatt` 持有旧设备而 `_connectedDevice` 指向新设备；旧设备的通知可能被记到新设备上；设备状态可能卡在 Connecting/Connected；旧连接可能没有释放。
- **推荐解决方案**：把 GATT 操作收敛到一个连接管理器，使用 `SemaphoreSlim(1,1)` 串行化 Connect/Disconnect；为每次连接分配递增 `generation`/`CancellationTokenSource`，在每个 `await` 后校验代际；让 `GattHeartRateService` 暴露 `CurrentAddress`，事件携带地址；断开时统一清理。
- **修改风险**：中。会改变连接生命周期，需要多设备/快速切换/断线重连回归测试。

#### P1-2 切换设备/取消选择时未发送 `Connected=false`

- **问题位置**：[`HOSC/MainWindow.xaml.cs`](HOSC/MainWindow.xaml.cs#L488-L516)；对照 [`#L232-L245`](HOSC/MainWindow.xaml.cs#L232-L245)、[`#L470-L486`](HOSC/MainWindow.xaml.cs#L470-L486)、[`#L294-L340`](HOSC/MainWindow.xaml.cs#L294-L340)。
- **当前实现**：`DevicesGrid_SelectionChanged` 在切换设备时把旧设备状态设为 `None` 并 fire-and-forget 断开，但没有调用 `SendConnectedStateToOsc(false)`；`DisconnectActiveAsync`、`ClearList`、维护移除、`OnGattDisconnected` 和窗口关闭会发送 false。
- **潜在影响**：VRChat 的 `Connected`/`isHRConnected`/`isHRActive` 可能保持 true；如果新设备连接失败，Avatar 仍显示“已连接”；切换设备时状态短暂/长期不一致。
- **推荐解决方案**：把“连接状态变化”集中到一个方法：断开旧设备前/后发送 false，连接成功发送 true，连接失败发送 false；确保同一时刻只有一个 true；处理 `SelectedItem=null`。
- **修改风险**：低。注意不要重复发送导致状态抖动。

#### P1-3 GATT 连接抑制广播 BPM，可能导致 BPM 冻结

- **问题位置**：[`HOSC/MainWindow.xaml.cs`](HOSC/MainWindow.xaml.cs#L154-L167)、[`#L186-L230`](HOSC/MainWindow.xaml.cs#L186-L230)；[`HOSC/Models/HeartRateDevice.cs`](HOSC/Models/HeartRateDevice.cs#L82-L87)。
- **当前实现**：活动设备只要收到广播就会调用 `EnsureConnectedAsync`，不管广播里是否已有 BPM；广播 BPM 只在 `!_connectedDevice == device` 时发送。一旦 GATT 连接成功，广播路径不再发送 BPM；如果 GATT 通知没有到达/订阅失败，BPM 会停在最后一个值。
- **潜在影响**：本来可以通过广播稳定获取 BPM 的设备，在 GATT 连接后反而停止更新；用户看到“已连接”但 BPM 不动；核心实时性受损。
- **推荐解决方案**：只有广播没有 BPM 或明确需要 GATT 时才自动连接；保留广播 BPM 作为 fallback；增加“GATT 通知超时”看门狗（例如 5-10 秒无通知则回退广播或断开）；只在数据新鲜时发送。
- **修改风险**：中。需要按设备类型测试“广播带 BPM”“仅 GATT”“两者都有”三种情况。

#### P1-4 GATT 断开后状态未清理，`IsConnected` 可能过期

- **问题位置**：[`HOSC/Services/GattHeartRateService.cs`](HOSC/Services/GattHeartRateService.cs#L17-L17)、[`#L131-L137`](HOSC/Services/GattHeartRateService.cs#L131-L137)；[`HOSC/MainWindow.xaml.cs`](HOSC/MainWindow.xaml.cs#L272-L292)。
- **当前实现**：`IsConnected => _device != null && _characteristic != null`；底层 `ConnectionStatusChanged` 断开时只触发 `Disconnected`，不清空字段；`MainWindow.OnGattDisconnected` 清空 `_connectedDevice`，但不调用 `_gatt.DisconnectAsync()`/`DisposeAsync()`。事件也不携带设备地址，无法判断是不是当前连接。
- **潜在影响**：`_gatt.IsConnected` 仍为 true；UI/逻辑可能误判已连接；旧 service/device 对象滞留；旧设备的断开事件可能清掉新连接的 `_connectedDevice`。
- **推荐解决方案**：在 `GattHeartRateService` 的断开处理里取消订阅、清空 `_characteristic`/`_service`/`_device`；`IsConnected` 改为检查 `ConnectionStatus == Connected`；事件携带地址/连接 ID；`MainWindow` 只处理匹配当前设备的断开。
- **修改风险**：低/中。需要测试手动断开、手表关机、走出范围、重新连接。

#### P1-5 扫描启动异常未捕获（P0 候选）

- **问题位置**：[`HOSC/MainWindow.xaml.cs`](HOSC/MainWindow.xaml.cs#L103-L109)、[`#L48-L51`](HOSC/MainWindow.xaml.cs#L48-L51)；[`HOSC/Services/BluetoothHeartRateScanner.cs`](HOSC/Services/BluetoothHeartRateScanner.cs#L21-L36)。
- **当前实现**：默认自动扫描；`StartScanning()` 直接调用 `_scanner.Start()`，没有 try/catch；`Start()` 里 `new BluetoothLEAdvertisementWatcher`/`Start()` 也没有保护。
- **潜在影响**：蓝牙关闭、驱动异常、权限/策略限制时，`Start()` 可能抛异常，导致启动崩溃或点击“开始扫描”崩溃；`Stopped` 事件只能覆盖异步停止错误。
- **推荐解决方案**：在 `StartScanning`/`Start` 外层捕获 `Exception`（至少 `UnauthorizedAccessException`、`InvalidOperationException`、COMException），设置 `_scanFailed`，刷新 UI，记录日志；可选先检查 `BluetoothAdapter.GetDefaultAsync()`/radio 状态。
- **修改风险**：低。需要实测 API 在目标环境的行为；如果确认会抛异常，应升级 P0。

#### P1-6 VRCOSC 参数语义与 README 不一致

- **问题位置**：[`HOSC/Services/OscService.cs`](HOSC/Services/OscService.cs#L38-L56)、[`#L76-L88`](HOSC/Services/OscService.cs#L76-L88)；[`README.md`](README.md#L85-L105)。
- **当前实现**：
  - `VRCOSC/Heartrate/Beat` 发送 `_heartBeatToggle`，每次心跳翻转，且不会复位；
  - `isHRBeat` 发送 1 后 120ms 复位 0；
  - `VRCOSC/Heartrate/Enabled` 与 `Connected` 相同，都是连接状态；
  - `VRCOSC/Heartrate/Average` 发送瞬时 BPM；
  - `HeartRateFloat01` 没有 clamp。
- **潜在影响**：使用 VRCOSC 兼容 Prefab 的用户可能看到 Beat 半时间常亮、Enabled 在 OSC 开启但未连接时为 false、Average 不是平均；README 会误导集成者。
- **推荐解决方案**：按目标 Prefab 的实际语义决定：Beat 若需要脉冲则 1→0 复位；Enabled 单独表示 OSC 开关；Average 实现滑动平均或改文档；所有归一化值 clamp；用 OSC 监视器 + 实际 Avatar 测试。
- **修改风险**：低/中。协议兼容性变更，需要通知现有用户/更新 README。

### 11.3 P2：一般问题（影响核心功能、稳定性、实时性或维护）

| ID | 位置 | 当前实现与潜在影响 | 推荐解决方案 | 修改风险 |
| --- | --- | --- | --- | --- |
| P2-1 | [`MainWindow.xaml.cs`](HOSC/MainWindow.xaml.cs#L203-L206) | 连接失败后设备进入 `Error`，`EnsureConnectedAsync` 在非 `force` 时直接返回；没有重试/退避。瞬态失败需要用户手动点 Connect。 | 增加指数退避重试（如 2s/5s/10s，上限 3 次），或在 N 秒后允许自动重试；显示重试状态。 | 低；避免重连风暴。 |
| P2-2 | [`BluetoothHeartRateScanner.cs`](HOSC/Services/BluetoothHeartRateScanner.cs#L13-L13)、[`#L59-L123`](HOSC/Services/BluetoothHeartRateScanner.cs#L59-L123) | `RefreshPairedDevicesAsync`/`PairedDevicesRefreshed` 存在，但主窗口没有调用/订阅；README 却声称已配对设备会列出。 | 接入 UI（扫描开始时或“刷新已配对”按钮），合并去重；或删除代码并修正 README。 | 低/中；需处理广播/配对重复设备。 |
| P2-3 | [`BluetoothHeartRateScanner.cs`](HOSC/Services/BluetoothHeartRateScanner.cs#L125-L150)、[`#L177-L185`](HOSC/Services/BluetoothHeartRateScanner.cs#L177-L185) | 广播路径没有调用 `IsAudioDevice`；名称含 `HUAWEI` 的耳机（如 FreeBuds）可能被当作心率设备。 | 在广播路径也应用音频过滤，除非有 0x180D/有效 BPM；收紧名称规则；允许用户隐藏设备。 | 低；注意不要误过滤合法设备。 |
| P2-4 | [`HeartRateDevice.cs`](HOSC/Models/HeartRateDevice.cs#L82-L87)、[`#L148-L158`](HOSC/Models/HeartRateDevice.cs#L148-L158)；[`MainWindow.xaml.cs`](HOSC/MainWindow.xaml.cs#L160-L167)、[`#L355-L368`](HOSC/MainWindow.xaml.cs#L355-L368) | 广播没有 BPM 时保留旧 BPM；离线不清空；活动设备离线仍显示旧 BPM；未连接时可能重复发送旧 BPM。 | 记录 BPM 时间戳，超时清空/标记 stale；只发送当前广播/通知的新值；离线显示 `--`。 | 低；行为变化需测试。 |
| P2-5 | [`HeartRateDevice.cs`](HOSC/Models/HeartRateDevice.cs#L117-L134) | `SetConnectedBpm` 在离线转在线时只通知 `IsOnline`，没有通知 `StatusText`；状态列可能仍显示 Offline。 | 增加 `MarkOnline` 或同时通知 `StatusText`；统一在线状态变更。 | 低。 |
| P2-6 | [`MainWindow.xaml.cs`](HOSC/MainWindow.xaml.cs#L434-L447)、[`#L556-L562`](HOSC/MainWindow.xaml.cs#L556-L562)；[`AppSettings.cs`](HOSC/Services/AppSettings.cs#L74-L86) | 每次 `TextChanged` 都同步写 JSON；没有临时文件/原子替换；异常被吞。UI 可能卡顿，崩溃/断电可能损坏设置。 | 防抖 300-500ms 或失焦/关闭时保存；写临时文件后 `File.Replace`/`Move`；保留备份；记录错误。 | 低；确保关闭时最终保存。 |
| P2-7 | [`MainWindow.xaml.cs`](HOSC/MainWindow.xaml.cs#L118-L169) | 每个广播包同步 `Dispatcher.Invoke`，线性查找、更新多个控件；广播频繁时 UI 线程压力大。 | 按设备合并更新（100-250ms），用 `BeginInvoke`/队列；只在值变化时刷新；批量更新。 | 中；需测响应性。 |
| P2-8 | `App.xaml.cs`；[`OscService.cs`](HOSC/Services/OscService.cs#L121-L137)；[`AppSettings.cs`](HOSC/Services/AppSettings.cs#L56-L86) 等 | 大量 `catch {}`/`return false`，没有日志/全局异常处理；问题难定位。 | 增加轻量文件日志（滚动）、全局异常处理、诊断导出；避免记录敏感数据。 | 低；注意隐私/磁盘。 |
| P2-9 | [`ThemeService.cs`](HOSC/Services/ThemeService.cs#L61-L77) | 每 3 秒检查系统主题，即使没变也 `Apply`，清空并重建资源字典。 | 缓存最后生效的主题/强调色，只在变化时应用；用系统事件或更长间隔；退出时停止。 | 低。 |
| P2-10 | [`MainWindow.xaml.cs`](HOSC/MainWindow.xaml.cs#L564-L572)；[`GattHeartRateService.cs`](HOSC/Services/GattHeartRateService.cs#L107-L110) | 关闭时 `_ = _gatt.DisconnectAsync()` 不等待，`DisposeAsync` 未调用；进程可能先退出。 | 异步关闭流程，带超时等待断开/释放；或至少 `await` 并处理异常。 | 低/中；WPF 关闭时序。 |
| P2-11 | `App.xaml.cs` | 没有单实例保护；两个实例会重复扫描/连接/发 OSC。 | 命名 Mutex；第二实例激活已有窗口或提示退出；可选多实例开关。 | 低。 |
| P2-12 | [`OscService.cs`](HOSC/Services/OscService.cs#L121-L137) | `UdpClient.Send(string host, ...)` 可能每次解析 DNS；自定义主机名时阻塞 UI。 | 设置变化时解析一次并缓存 `IPEndPoint`；用 `Send(byte[], int, IPEndPoint)`。 | 低。 |
| P2-13 | [`HeartRateParser.cs`](HOSC/Services/HeartRateParser.cs#L23-L49) | 只解析 `0x16` Service Data；不支持 32-bit Service Data、Manufacturer Data、GATT Read 回退。 | 按目标设备补充解析器；文档列出支持范围；保留 GATT fallback。 | 中；协议差异。 |
| P2-14 | [`BluetoothHeartRateScanner.cs`](HOSC/Services/BluetoothHeartRateScanner.cs#L161-L185) | 名称规则过宽（`GT`、`FIT` 等），广播路径又无音频过滤；可能列出非心率设备。 | 优先用 0x180D/BPM；收紧名称规则；维护 allow/deny 列表；允许用户隐藏。 | 低。 |
| P2-15 | [`HOSC.Diagnostics/Program.cs`](HOSC.Diagnostics/Program.cs#L6-L7)、[`#L145-L158`](HOSC.Diagnostics/Program.cs#L145-L158)、[`#L192-L256`](HOSC.Diagnostics/Program.cs#L192-L256) | 未跟踪的独立项目；硬编码设备地址；向所有可写特征发送厂商命令；无 README/确认步骤。 | 决定是否保留为工具；加 README/CLI 参数/确认提示；移入 `tools/`；如保留则纳入版本控制；不要在未知设备上运行。 | 中；可能影响设备。 |
| P2-16 | [`OscService.cs`](HOSC/Services/OscService.cs#L29-L33)、[`#L60-L74`](HOSC/Services/OscService.cs#L60-L74) | `SendHeartRate` 在 400ms 节流命中时直接返回 `true` 但不发送；`SendTest` 用这个返回值判断成功，并继续发聊天框。测试可能显示“已发送”，实际只有聊天框到达，没有 72 BPM。 | 测试路径绕过节流或返回“已节流”状态；先重置节流时间戳；测试结果区分“HR 已发/仅聊天框已发”。 | 低。 |

### 11.4 P3：建议优化（不影响当前正常使用）

| ID | 位置 | 当前实现与潜在影响 | 推荐解决方案 | 修改风险 |
| --- | --- | --- | --- | --- |
| P3-1 | [`HeartRateParser.cs`](HOSC/Services/HeartRateParser.cs#L108-L112)；[`BluetoothHeartRateScanner.cs`](HOSC/Services/BluetoothHeartRateScanner.cs#L13-L13)；[`HeartRateDevice.cs`](HOSC/Models/HeartRateDevice.cs#L64-L70) | `HeartRateReading` 的 contact/RR 标志、`HasHeartRateService`、`LastSeenText`、`LanguageCode` 等未被 UI/业务使用；`DeviceLastSeen` 资源未使用。 | 确认无外部/反射使用后，在专门清理提交中删除或接入 UI；先标记注释/`[Obsolete]`。 | 低。 |
| P3-2 | [`HeartRateParser.cs`](HOSC/Services/HeartRateParser.cs#L37-L38)、[`#L61-L70`](HOSC/Services/HeartRateParser.cs#L61-L70) | `serviceUuid == 0x180D` 与 `(bytes[0]==0x18 && bytes[1]==0x0D)` 重复；`payload.Length == 1` 返回后 `payload.Length < 2` 不可达。 | 简化条件；明确 1 字节回退的适用场景；补单元测试。 | 低。 |
| P3-3 | [`GattHeartRateService.cs`](HOSC/Services/GattHeartRateService.cs#L121-L124)；[`HeartRateParser.cs`](HOSC/Services/HeartRateParser.cs#L100-L104) | `DataReader.FromBuffer` 未 `using`；每次通知/广播分配 `byte[]`。 | `using var reader`； profiling 后如有必要再池化；不要过早优化。 | 低。 |
| P3-4 | [`OscService.cs`](HOSC/Services/OscService.cs#L90-L119)、[`#L139-L150`](HOSC/Services/OscService.cs#L139-L150) | `Dispose` 未 Dispose CTS；聊天框测试文字硬编码英文；2.5 秒后清空可能覆盖用户聊天框内容。 | Dispose CTS；本地化或配置测试文字；清空前判断是否仍是测试内容。 | 低。 |
| P3-5 | [`OscService.cs`](HOSC/Services/OscService.cs#L45-L54) | `HeartRateFloat01` 无 clamp；`Average` 瞬时；`HRPercent` 最大 1；数值边界未文档化。 | clamp 到目标范围；实现平均或改名；在 README 写清公式。 | 低。 |
| P3-6 | [`StartupService.cs`](HOSC/Services/StartupService.cs#L10-L44) | `IsEnabled` 只检查值存在，不校验路径；框架依赖运行时可能注册 `dotnet.exe` 而非 DLL 参数。 | 比较当前命令/路径；清理陈旧值；文档说明自包含发布。 | 低。 |
| P3-7 | [`ThemeService.cs`](HOSC/Services/ThemeService.cs#L35-L37) | `MergedDictionaries.Clear()` 会清掉所有应用级合并字典，不只是主题。 | 只替换主题字典；给主题字典一个固定 URI/键。 | 低。 |
| P3-8 | [`MainWindow.xaml`](HOSC/MainWindow.xaml#L149-L189)；`Strings*.resx` | `DeviceLastSeen` 资源存在、`LastSeenText` 维护，但没有 DataGrid 列。 | 增加“最后接收”列，或删除未用资源；保持 4 语言 key 一致。 | 低。 |
| P3-9 | [`HOSC.Diagnostics/Program.cs`](HOSC.Diagnostics/Program.cs#L100-L158)、[`#L192-L256`](HOSC.Diagnostics/Program.cs#L192-L256) | `notificationCount++` 非原子；`Channel` 无界；向所有可写特征发送命令；`DataWriter` 未释放。 | `Interlocked.Increment`；有界/排空 channel；只写目标特征；释放 writer；加使用说明。 | 低/中。 |
| P3-10 | 仓库根目录 | `FS-HeartCast-OSC-v1.0.0-win-x64.zip` 约 69 MB 被跟踪；`logo备份/` 未跟踪；`已废弃MD文档/` 为空；`HOSC.Diagnostics/` 未跟踪。 | 决定发布包策略（Releases/LFS）；给未跟踪目录加 README；不要把构建产物当源码。 | 低。 |
| P3-11 | [`README.md`](README.md#L165-L167)、[`#L186-L188`](README.md#L186-L188) | “所有数据仅在本机处理”未说明地址可改为远程；没有 LICENSE；没有测试。 | 改为“默认仅本机；修改 OSC 地址后数据会发往该地址”；补 LICENSE；补测试。 | 低。 |
| P3-12 | [`OscService.cs`](HOSC/Services/OscService.cs#L175-L247)；[`MainWindow.xaml.cs`](HOSC/MainWindow.xaml.cs#L414-L426) | OSC 地址只做空白回退，不校验是否以 `/` 开头；非法地址会被 VRChat 忽略；解析器拒绝 16 位 BPM >255。 | UI 校验 `/` 开头/无空格；显示错误；文档说明范围；必要时支持更大 BPM。 | 低。 |
| P3-13 | [`AppSettings.cs`](HOSC/Services/AppSettings.cs#L44-L118) | JSON 无 schema 版本；解析失败回退默认；没有备份/迁移。 | 加 `SchemaVersion`、迁移、备份；保留未知字段；记录错误。 | 低。 |
| P3-14 | [`MainWindow.xaml.cs`](HOSC/MainWindow.xaml.cs#L355-L377)、[`#L428-L432`](HOSC/MainWindow.xaml.cs#L428-L432) | `OscStatusText` 只在测试/发送失败时更新；离线设备仍可点 Connect；语言切换后状态文字可能不刷新。 | 统一 UI 状态刷新；离线禁用 Connect；语言切换时刷新所有代码设置文本。 | 低。 |
| P3-15 | [`AppResources.cs`](HOSC/Localization/AppResources.cs#L25-L29) | 通过 `PropertyChanged(string.Empty)` 刷新所有绑定；WPF 对索引器绑定的刷新行为需实测；主窗口代码设置的文字要等设置窗口关闭才刷新。 | 显式触发索引器/属性通知；语言切换后调用 `RefreshUiTexts`；加 UI 测试。 | 低。 |

### 11.5 问题统计与建议修复顺序

| 级别 | 数量 | 说明 |
| --- | --- | --- |
| P0（确认） | 0 | 未发现必然无法运行/丢失核心数据的问题 |
| P0（候选） | 1 | P1-5，需实测蓝牙关闭时 `Start()` 是否抛异常 |
| P1 | 6 | 连接竞态、连接状态、BPM 冻结、GATT 清理、扫描异常、VRCOSC 语义 |
| P2 | 16 | 重试、已配对设备、音频过滤、陈旧 BPM、设置写盘、UI 线程、日志、主题轮询、测试节流等 |
| P3 | 15 | 死代码、资源释放、数值边界、仓库卫生、文档等 |

建议顺序：

1. 先修 P1-5（扫描启动异常），确认是否存在 P0。
2. 再修 P1-1/P1-4（连接生命周期/状态清理），这是后续修复的基础。
3. 再修 P1-2/P1-3（连接状态发送、广播 fallback），直接改善 VRChat 体验。
4. 再处理 P1-6（VRCOSC 语义），同时更新 README。
5. P2 按用户可见程度排序：P2-4/P2-5（BPM 显示）、P2-6（设置写盘）、P2-2/P2-3（设备发现）、P2-8（日志）。
6. P3 在专门清理提交中处理，避免和功能修复混在一起。

---

## 12. 尚未完成的功能

以下“未完成”是代码中可见的缺口，不代表作者承诺过这些功能：

1. **已配对设备列表/刷新**：`RefreshPairedDevicesAsync` 已实现但未接入；README 已宣称。
2. **广播路径音频设备过滤**：`IsAudioDevice` 只用于未接入的已配对路径。
3. **平均心率**：`Average` 参数没有滑动平均。
4. **独立 OSC Enabled 状态**：`Enabled` 目前等于连接状态。
5. **VRCOSC Beat 脉冲语义**：当前是翻转 bool，不是 1→0 脉冲。
6. **GATT 自动重连/退避**：失败后需要手动操作。
7. **GATT 通知超时 fallback**：连接后没有“无通知”检测。
8. **单实例运行**：多开没有保护。
9. **日志/诊断导出**：没有文件日志或诊断面板。
10. **自动化测试**：没有单元/集成测试。
11. **Redmi Watch 6 厂商协议**：只在 `HOSC.Diagnostics` 中探测，主程序未接入。
12. **安装器/自动更新/CI**：没有；EXE 签名情况本次未验证。
13. **LICENSE**：没有。
14. **聊天框测试文字本地化**：硬编码英文。
15. **设备兼容性数据库/用户覆盖规则**：没有。

---

## 13. 后续优化建议

### 13.1 短期（稳定性优先）

- 给扫描启动/停止加异常处理（P1-5）。
- 把 GATT 连接/断开串行化，增加连接代际/取消（P1-1）。
- GATT 断开时清理字段，事件携带设备地址（P1-4）。
- 切换设备/连接失败时发送 `Connected=false`（P1-2）。
- 保留广播 BPM fallback，增加通知超时看门狗（P1-3）。
- 修正 VRCOSC 参数语义或更新 README（P1-6）。
- 加最小日志（P2-8）。

### 13.2 中期（体验/维护）

- 接入已配对设备刷新，或删除相关代码与 README 声明（P2-2）。
- 广播路径应用音频过滤（P2-3）。
- 设置保存防抖 + 原子写（P2-6）。
- 合并 UI 更新，降低 Dispatcher 压力（P2-7）。
- 系统主题只在变化时应用（P2-9）。
- 单实例保护（P2-11）。
- 设置 schema 版本/备份（P3-13）。

### 13.3 长期（架构/质量）

- 将 `MainWindow` 拆成 `HeartRateSource`、`ConnectionManager`、`OscOutput`、`SettingsStore` 等职责更清晰的模块。
- 为 `HeartRateParser`、`OscService.BuildMessage`、`SettingsService` 增加单元测试。
- 建立设备兼容性测试矩阵（广播带 BPM、仅 GATT、华为/小米/Redmi、耳机误识别）。
- 用 OSC 监视器做端到端测试（参数名/类型/频率）。
- 考虑 OSC bundle 或合并发送，减少 UDP 包数量。
- 评估是否支持 32-bit Service Data/Manufacturer Data。
- 如果继续做 Redmi Watch 6，给 `HOSC.Diagnostics` 加文档/安全确认，并决定是否纳入主项目。

### 13.4 测试建议

| 测试 | 目的 |
| --- | --- |
| 广播带 BPM 的设备 | 验证不连接 GATT 也能更新 |
| 仅 GATT 的设备 | 验证连接/订阅/通知/断开 |
| 同时支持广播+GATT 的设备 | 验证 fallback 和 BPM 不冻结 |
| 快速切换设备 | 验证 P1-1/P1-2 |
| 手表关机/走出范围 | 验证 12s/90s 状态和 `Connected=false` |
| 蓝牙关闭时启动 | 验证 P1-5/P0 候选 |
| VRChat OSC 监视器 | 验证参数名/类型/频率/复位 |
| 修改设置时强制结束进程 | 验证设置文件完整性 |
| 多实例启动 | 验证是否重复发送 |
| 4 种语言切换 | 验证所有绑定/代码设置文字刷新 |

---

## 14. 下一位开发者接手时需要注意的事项

1. **先读本文件，再读 README**：README 是用户向文档，部分描述与代码不一致；本文件的“实际实现”优先。
2. **不要随意删除未跟踪文件**：`HOSC.Diagnostics/`、`logo备份/` 当前未被 Git 跟踪；`已废弃MD文档/` 为空目录。删除前先确认作者意图。
3. **不要随意升级目标框架**：项目是 .NET 6 + Windows 10 19041；升级到 .NET 8/9 需要重新验证 WPF、WinRT、单文件发布和旧 Windows 兼容性。
4. **蓝牙/GATT 代码必须用真实设备测试**：模拟器无法覆盖广播、配对、通知、断开、厂商差异。
5. **VRChat 参数是外部契约**：修改 `OscService` 的参数名/类型/频率会影响现有 Prefab；先看 README 的参数表，再用 OSC 监视器验证。
6. **注意 UI 线程**：扫描/GATT 事件来自后台线程，现有代码用 `Dispatcher.Invoke`；新增逻辑不要直接操作 WPF 控件。
7. **注意异步竞态**：`_gatt`/`_connectedDevice` 是共享状态；新增连接逻辑前先看 P1-1。
8. **设置文件在 `%APPDATA%`**：不要提交 `settings.json`；测试时注意不要覆盖用户配置。
9. **发布包很大**：根目录 zip 约 69 MB，publish EXE 约 175 MB；不要无意中把 `bin/obj` 或大文件提交进 Git。
10. **本地化要同步 4 个 resx**：新增 key 时同时更新 `Strings.resx`、`zh-Hans`、`zh-Hant`、`ja`，否则回退到 key 或英文。
11. **主题资源**：`Assets/Logo.xaml` 是当前应用内 Logo；`Assets/legacy/` 是旧版，不要误用。
12. **HOSC.Diagnostics 有设备侧副作用**：它会向所有可写 GATT 特征发送厂商命令；只在已知设备上运行。
13. **没有测试/CI**：任何修改都要手工回归：扫描、连接、BPM、OSC 测试、设置、语言、主题、关闭。
14. **保留历史**：旧 Logo/旧文档/发布包是作者有意保留的；清理前先询问。
15. **隐私**：默认只发本机，但 OSC 地址可改；不要把它宣传成“绝对不出本机”。

---

## 15. 历史开发背景

以下信息来自 Git 历史，属于“历史记录”，不是当前代码行为。

### 15.1 提交时间线

| 提交 | 日期 | 内容 |
| --- | --- | --- |
| `aa6ed0a` | 2026-08-03 | Initial commit；初版 README（41 行），描述 BLE 广播、GATT、OSC、4 语言、主题 |
| `6b23641` | 2026-08-03 | Update about page GitHub link |
| `e4d348a` | 2026-08-03 | Prepare v1.0.0 release；README 扩展为完整功能/参数/构建文档；打标签 `FHS`；分支 `codex/redmi-watch6-connectivity` 指向此提交 |
| `027846a` | 2026-09-09 | Update README.md |
| `f73bcd2` | 2026-10-20 | 使用新 heart-watch Logo，保留旧 Logo 到 `Assets/legacy`，增加 `Assets/design` 设计源 |
| `531ee02` | 2026-10-20 | Revert 上述换标提交；恢复旧 Logo，删除 `Assets/design`/`Assets/legacy` 及其 README |
| `b20e8ac` | 2026-10-20 | 再次替换为新 heart-watch Logo；重新加入 `Assets/legacy`（但没有 README） |
| `571eb90` | 2026-10-20 | 在 AppIcon.svg/png/ico 中加入 `FS-HeartCast-OSC` 字标 |
| `5c19cb7` | 2026-11-04 | Rebuild win-x64 release package with the new logo；当前 HEAD |

### 15.2 代码与资产的变化

- `e4d348a` 到 `5c19cb7` 之间，**C#/XAML 源码没有变化**；变化集中在 Logo 资产、README 和发布 zip。
- 当前 `Assets/legacy/` 是 `b20e8ac` 重新加入的旧 Logo 备份；`Assets/legacy/README.md` 在 `531ee02` 被删除后没有恢复。
- 当前 `Assets/design/` 不存在；设计源的一部分在未跟踪的 `logo备份/` 中（`newlogo-v2.html`、`newlogo-v2.png`）。
- `HOSC.Diagnostics/` 没有出现在任何提交中，是当前工作树的未跟踪目录；从代码看是 Redmi Watch 6/小米 GATT 协议诊断工具，但没有文档。

### 15.3 分支与标签

- `main`：当前主分支，HEAD `5c19cb7`。
- `codex/redmi-watch6-connectivity`：指向 `e4d348a`（与标签 `FHS` 同一提交）；与 `main` 的 diff 为空（它是 `main` 的祖先），没有额外提交。分支名暗示曾计划做 Redmi Watch 6 连接性工作，但 Git 历史中没有对应代码变更。
- `tag FHS`：v1.0.0 发布点。

### 15.4 历史结论

- 项目在 v1.0.0 后主要做品牌/发布整理，没有功能迭代记录。
- “Redmi Watch 6” 相关工作目前只看到分支名和未跟踪的诊断工具，不能当成已完成功能。
- 旧 Logo 的多次替换/回滚是历史事实，不代表当前 Logo 还有争议；以当前 `Assets/Logo.xaml` 为准。

---

## 16. 已有技术方案与设计决策

| 决策 | 证据 | 当前有效性 |
| --- | --- | --- |
| 不做 Windows 配对，直接监听 BLE 广播 | `BluetoothLEAdvertisementWatcher`，README | 有效 |
| 广播不够时用临时 GATT 连接读取 `0x2A37` | `GattHeartRateService` | 有效，但对所有活动设备都会尝试连接（见 P1-3） |
| 使用标准 Heart Rate Service UUID `0x180D`/`0x2A37` | `HeartRateParser` | 有效 |
| 手写 OSC 1.0 编码，不引入第三方 OSC 库 | `OscService.BuildMessage` | 有效；简单但缺少测试 |
| 通过多参数别名兼容不同 Prefab | `SendHeartRate` 发送 14 个参数 | 有效；但部分语义与 README 不一致 |
| 设置保存在 `%APPDATA%\HOSC\settings.json` | `SettingsService` | 有效 |
| 开机自启用 HKCU Run 键 | `StartupService` | 有效；框架依赖模式有坑 |
| 主题用 WPF 资源字典 + DynamicResource | `ThemeService`、`Themes/*.xaml` | 有效；每 3 秒轮询有优化空间 |
| 多语言用 resx/ResourceManager | `Resources/Strings*.resx` | 有效；4 个文件 key 一致 |
| 单文件自包含发布 | README、发布产物 | 有效；EXE 很大 |
| 旧 Logo 保留在 `Assets/legacy` | Git 历史、目录 | 有效（存档） |
| 诊断工具独立于主程序 | `HOSC.Diagnostics` 未被引用 | 有效；但未跟踪/无文档 |

---

## 17. 历史问题及解决记录

Git 历史中没有明确的 bug fix 提交信息；能确认的“历史事件”主要是资产/文档：

1. **Logo 换标与回滚**：`f73bcd2` 引入新 Logo/设计源/旧 Logo 备份；`531ee02` 回滚；`b20e8ac` 再次引入新 Logo；`571eb90` 加字标；`5c19cb7` 重打发布包。
2. **README 扩展**：`e4d348a` 把初版 README 扩展为功能、原理、兼容性、参数表、构建、FAQ、隐私等完整文档；`027846a` 后续更新。
3. **旧文档删除**：`531ee02` 删除了 `HOSC/Assets/design/README.md`、`HOSC/Assets/legacy/README.md` 及设计源；`b20e8ac` 只恢复了 legacy 资产，没有恢复 README。
4. **Redmi Watch 6 分支**：`codex/redmi-watch6-connectivity` 指向 v1.0.0，没有额外提交；没有可确认的功能实现。
5. **诊断工具**：`HOSC.Diagnostics` 未跟踪；没有提交历史/README；用途只能从代码推断。

没有找到“某次提交修复了某个 BLE/OSC bug”的记录；因此本文件中的问题都是当前代码静态审查发现，不是历史遗留 bug 的复述。

---

## 18. 当前仍然有效的技术信息

以下内容已由当前代码确认，可作为接手依据：

### 18.1 UUID/协议

- Heart Rate Service：`0000180d-0000-1000-8000-00805f9b34fb`（16-bit `0x180D`）
- Heart Rate Measurement：`00002a37-0000-1000-8000-00805f9b34fb`（16-bit `0x2A37`）
- 广播解析：AD Type `0x16`（16-bit Service Data）
- OSC：UDP，OSC 1.0 消息，默认 `127.0.0.1:9000`

### 18.2 默认值

- OSC 地址：`127.0.0.1`
- OSC 端口：`9000`
- HR 参数：`/avatar/parameters/HR`
- Beat 参数：`/avatar/parameters/isHRBeat`
- 自动扫描：`true`
- 置顶：`false`
- OSC 发送：`false`
- 主题：`System`
- 强调色：`Teal`
- 语言：按系统 UI 文化

### 18.3 时间/阈值

- 维护定时器：2 秒
- 离线阈值：12 秒
- 移除阈值：90 秒
- 心跳复位：120ms
- 心率发送节流：400ms
- 聊天框清空：2500ms
- 系统主题轮询：3 秒

### 18.4 文件/路径

- 设置：`%APPDATA%\HOSC\settings.json`
- 自启：`HKCU\Software\Microsoft\Windows\CurrentVersion\Run\FS-HeartCast-OSC`
- 主项目：`HOSC/HOSC.csproj`
- 目标框架：`net6.0-windows10.0.19041.0`
- 发布：`HOSC/bin/Release/net6.0-windows10.0.19041.0/win-x64/publish/FS-HeartCast-OSC.exe`

### 18.5 参数公式

| 参数 | 公式/值 |
| --- | --- |
| `VRCOSC/Heartrate/Value` | BPM |
| `VRCOSC/Heartrate/Normalised` | `clamp(BPM / 240, 0, 1)` |
| `VRCOSC/Heartrate/Average` | BPM（瞬时，不是平均） |
| `HeartRateFloat01` | `BPM / 240`（未 clamp） |
| `HRPercent` | `BPM / 255` |
| `HeartRate` | `BPM / 127.5 - 1` |

---

## 19. 已过时或存在冲突的旧文档内容

### 19.1 旧 README（Git 历史）

- `aa6ed0a:README.md`（初版，41 行）：功能描述较少，构建命令与现在基本一致；已被后续 README 取代。**状态：已过时。**
- `e4d348a:README.md`（v1.0.0 完整版）：当前 README 的基础；它本身不含“品牌资源”章节，后续 `f73bcd2` 增加了该章节，`531ee02` 又回滚。**状态：大部分仍有效，但被当前 README 取代。**
- `027846a` 的 README 更新：历史版本；当前 README 已包含其内容。**状态：已过时。**

### 19.2 设计文档 `HOSC/Assets/design/README.md`

- 存在过，提交 `f73bcd2` 添加，`531ee02` 删除；当前工作树没有该目录。
- 内容描述：`AppIcon.svg`、`logo.svg`、`AppIcon.png`、`AppIcon.ico`、`Logo.xaml` 的用途，以及 `logo-v1.html`、`logo-v2.html`、`preview-v2.png`。
- 当前：`Assets/design/` 不存在；`logo-v1.html`/`logo-v2.html`/`preview-v2.png` 不在当前树；`logo备份/newlogo-v2.html` 和 `newlogo-v2.png` 可能是相关设计备份，但文件名/位置不同。
- **状态：已过时/历史记录。** 不能把其中的路径当成当前事实。

### 19.3 旧 Logo 文档 `HOSC/Assets/legacy/README.md`

- 存在过，`f73bcd2` 添加，`531ee02` 删除；当前 `Assets/legacy/` 文件存在，但 README 不在。
- 内容描述：旧 Logo 文件的原路径、用途、回滚方式。
- 当前：`Assets/legacy/` 有 `AppIcon.ico`、`AppIcon.png`、`AppIcon.svg`、`logo.svg`、`Logo.xaml`；没有 README。
- **状态：部分有效（资产仍在），README 已过时/缺失。**

### 19.4 当前 README 中与代码冲突的内容

| README 内容 | 冲突点 | 处理建议 |
| --- | --- | --- |
| 已配对设备会列出 | 未接入 `RefreshPairedDevicesAsync` | 接入或删除声明（P2-2） |
| 自动过滤耳机/音箱 | 广播路径未调用 `IsAudioDevice` | 补过滤（P2-3） |
| 仅 GATT 设备才自动连接 | 所有活动设备都会自动连接 | 调整逻辑或文档（P1-3） |
| `Average` 是平均值 | 实际瞬时值 | 实现平均或改文档（P1-6） |
| `Enabled` 是 OSC 启用状态 | 实际等于连接状态 | 分离语义（P1-6） |
| `VRCOSC/Heartrate/Beat` 兼容心跳 | 实际是翻转 bool | 按 Prefab 验证/修正（P1-6） |
| 所有数据仅本机 | OSC 地址可改远程 | 加限定（P3-11） |

### 19.5 发布包内的 README

`FS-HeartCast-OSC-v1.0.0-win-x64.zip` 中的文档条目只有一个 `README.md`（9099 字节），与当前根目录 README 大小一致；它是发布时打包的副本。**状态：发布时有效；如果 README 后续修改，zip 内副本可能过时。**

### 19.6 空目录 `已废弃MD文档/`

- 当前没有任何文件；无法读取内容。
- 如果这里曾经放过旧交接/设计/TODO 文档，它们不在工作树中；Git 历史也没有对应路径。
- **状态：未能读取/空目录。**

---

## 20. 尚待验证的信息

以下信息无法仅靠静态代码确认，接手时建议实测：

1. **蓝牙关闭时 `BluetoothLEAdvertisementWatcher.Start()` 是否抛异常**（P0 候选/P1-5）。
2. **VRCOSC/HRtoVRChat/HeartOSC/Mi Band Prefab 对 `Beat`、`Enabled`、`Average` 的实际语义**（P1-6）。
3. **目标手表在 GATT 连接后是否继续广播 BPM**；如果继续，P1-3 的冻结风险是否真实发生。
4. **`BluetoothLEDevice.FromBluetoothAddressAsync`/GATT 在未配对手表上的兼容性**（华为/小米/Redmi/Amazfit 等）。
5. **WPF 索引器绑定在 `PropertyChanged(string.Empty)` 下是否在所有目标 Windows 版本刷新**（P3-15）。
6. **`Environment.ProcessPath` 在单文件/框架依赖/不同启动方式下的值**（P3-6）。
7. **`HOSC.Diagnostics` 的厂商命令是否只适用于特定 Redmi 型号**，以及是否有设备侧副作用。
8. **`logo备份/` 是否是当前 Logo 的正式设计源**；没有 README。
9. **`codex/redmi-watch6-connectivity` 分支是否已放弃**；没有额外提交。
10. **发布 zip/EXE 是否与当前源码完全一致**；源码自 v1.0.0 未变，但资产/README 有变化，建议重新构建对比。
11. **Windows 11 24H2/25H2 的 BLE 行为**是否与 Windows 10 19041 有差异；本次只在 10.0.26200 上审查，未运行。
12. **多实例/多设备/快速切换的竞态是否在实际使用中触发**；代码上存在，需压力测试。

---

## 21. 原有文档索引

本次实际读取/核对的 Markdown 文档如下。除当前 `README.md` 外，其余来自 Git 历史或发布包；没有修改任何原文件。

| 文档 | 位置/获取方式 | 主要内容 | 有效性判断 |
| --- | --- | --- | --- |
| `README.md` | 工作树根目录 [`README.md`](README.md) | 项目简介、功能、原理、兼容性、快速开始、VRChat 配置、OSC 参数表、构建、项目结构、FAQ、隐私、社区、许可证 | **当前有效，但需修正 6 处与代码不一致的描述**（见 19.4） |
| `HOSC/Assets/design/README.md` | `git show 531ee02^:HOSC/Assets/design/README.md` | 新 Logo 设计源/预览文件说明 | **已过时/历史记录**；目录已删除 |
| `HOSC/Assets/legacy/README.md` | `git show 531ee02^:HOSC/Assets/legacy/README.md` | 旧 Logo 文件清单/回滚说明 | **部分有效**；资产仍在，README 已删除 |
| 初版 `README.md` | `git show aa6ed0a:README.md` | 初版功能/构建说明 | **已过时**；被后续 README 取代 |
| v1.0.0 `README.md` | `git show e4d348a:README.md` | 完整功能/参数/构建/FAQ | **大部分有效但已被当前 README 取代**；不含后来加入又回滚的“品牌资源”章节 |
| 发布包内 `README.md` | `FS-HeartCast-OSC-v1.0.0-win-x64.zip` 内 `README.md` | 与根目录 README 同大小的发布副本 | **发布时有效**；可能随根 README 更新而过时 |
| `已废弃MD文档/` | 工作树目录 | 空目录，无文件 | **未能读取**；没有内容可继承 |
| `PROJECT_HANDOVER.md` | 本次新建 | 本交接文档 | **新增**；不是原有文档 |

补充说明：

- 没有找到其他 AI 生成的交接、设计、开发记录、TODO 或技术方案 Markdown。
- 没有找到 `LICENSE`、`CHANGELOG`、`CONTRIBUTING`、`docs/` 等文档。
- 旧文档中可继承的有效信息已整理到第 15-18 节；无法验证的内容标记为“历史记录/待验证”。

---

## 22. 本次审查范围、未能检查的内容与验收

### 22.1 实际阅读的目录和文件

**根目录**

- `.gitignore`
- `README.md`
- `HOSC/`、`HOSC.Diagnostics/`、`logo备份/`、`已废弃MD文档/` 的目录结构
- `FS-HeartCast-OSC-v1.0.0-win-x64.zip` 的条目列表（只读；确认其中只有一个 README.md）
- `logo备份/newlogo-v2.html` 的前 40 行（确认是 SVG 设计源）

**主项目 `HOSC/`**

- `HOSC.csproj`
- `app.manifest`
- `App.xaml`、`App.xaml.cs`
- `MainWindow.xaml`、`MainWindow.xaml.cs`
- `SettingsWindow.xaml`、`SettingsWindow.xaml.cs`
- `Models/HeartRateDevice.cs`
- `Services/AppSettings.cs`
- `Services/BluetoothHeartRateScanner.cs`
- `Services/GattHeartRateService.cs`
- `Services/HeartRateParser.cs`
- `Services/OscService.cs`
- `Services/StartupService.cs`
- `Services/ThemeService.cs`
- `Localization/AppResources.cs`
- `Resources/Strings.resx`
- `Resources/Strings.zh-Hans.resx`
- `Resources/Strings.zh-Hant.resx`
- `Resources/Strings.ja.resx`
- `Themes/Dark.xaml`
- `Themes/Light.xaml`
- `Assets/Logo.xaml`
- `Assets/legacy/Logo.xaml` 的前 20 行（确认旧版资源）
- `Assets/` 与 `Assets/legacy/` 的文件清单

**诊断项目**

- `HOSC.Diagnostics/HOSC.Diagnostics.csproj`
- `HOSC.Diagnostics/Program.cs`（完整）

**Git 历史**

- `git log --all -- '*.md'`
- `git show 531ee02^:HOSC/Assets/design/README.md`
- `git show 531ee02^:HOSC/Assets/legacy/README.md`
- `git show aa6ed0a:README.md`
- `git show e4d348a:README.md`
- 提交图/分支/标签/差异统计

### 22.2 因权限、体积或其他原因未能检查的内容

- `HOSC/bin/`、`HOSC/obj/`：构建产物，未逐文件审查；只查看了发布 EXE 元数据和 `deps.json`/`runtimeconfig.json`。
- `HOSC.Diagnostics/bin/`、`obj/`：构建产物，未审查。
- `FS-HeartCast-OSC-v1.0.0-win-x64.zip`：只列出了条目，没有解压/逐文件比对；确认其中只有 README.md 文档。
- 二进制图片/图标（`AppIcon.png`、`AppIcon.ico`、`logo.svg` 等）：未做图像内容审查；只确认文件存在/大小。
- `logo备份/newlogo-v2.png`：二进制，未做图像内容审查。
- `.git/` 内部对象：未逐对象检查；使用 Git 命令读取历史。
- 实际蓝牙硬件/手表/VRChat：未连接、未运行，因此所有运行时行为（广播、GATT、OSC 接收、Avatar 参数）都未实测。
- 未执行 `dotnet build`/`dotnet publish`，因此“可编译性”是静态判断，不是构建验证。
- 未检查代码签名、安装器、CI、发布流程（仓库中没有这些文件）。

### 22.3 对项目架构的理解

- 这是一个**单进程、单窗口、代码后置**的 WPF 应用。
- 核心是“BLE 输入 → 解析 → UI 模型 → OSC 输出”的管道；没有数据库/服务端。
- `MainWindow` 是事实上的应用服务层，`Services/` 是工具层，`Models/` 是 UI 模型。
- `HOSC.Diagnostics` 是旁路工具，不属于主程序。
- 架构简单、部署容易，但连接生命周期和 UI 更新逻辑集中，导致竞态和职责过重。
- 主要扩展点：`HeartRateParser`（协议）、`OscService`（输出参数）、`BluetoothHeartRateScanner`/`GattHeartRateService`（设备来源）、`SettingsService`（配置）。

### 22.4 发现的问题数量及分类

| 分类 | 数量 |
| --- | --- |
| P0 确认 | 0 |
| P0 候选 | 1（P1-5，需实测） |
| P1 | 6 |
| P2 | 16 |
| P3 | 15 |
| 合计记录 | 37（不含 P0 候选重复计数） |

说明：P1-5 同时作为 P0 候选列出，但在统计中只计入 P1，避免重复。

### 22.5 生成的交接文档路径

```text
C:\Users\JKH\Desktop\GIT\0Amyhub\HOSC\PROJECT_HANDOVER.md
```

### 22.6 是否修改了原有代码

**没有。**

- 本次只新增 `PROJECT_HANDOVER.md`。
- 没有修改、删除、移动、重命名任何原有文件。
- 没有修改依赖、配置、协议、数据格式或运行流程。
- 没有执行构建/运行/蓝牙/VRChat 操作。
- 原有 Markdown 文档全部只读；旧文档内容只从 Git 历史读取。

---

## 23. Android 端（2026-11 新增，当前 v1.2.1）

### 23.1 定位与状态

- 包名 `com.fsheartcast.osc`；单 Activity（`MainActivity`）+ 两个页面（心率 / 设置），设置不再是独立 Activity
- Android 8.0+（minSdk 26），compileSdk / targetSdk 34；当前 `versionCode 5` / `versionName 1.2.1`
- **没有任何第三方或 AndroidX 依赖**，只用框架 API；APK 约 260 KB，调试签名
- 通过 GitHub Releases 分发，并在 App 内做版本检查（见 23.9）
- 与桌面版的关系：共用同一套心率来源与 OSC 参数契约，可以互为替代——手机当采集端时，电脑端只需要开着 VRChat

### 23.2 目录结构

```text
android/
├─ build-apk.ps1              离线构建：aapt2 + javac + d8 + zipalign + apksigner
├─ build.gradle / settings.gradle / gradle.properties / app/build.gradle
├─ app/src/main/
│  ├─ AndroidManifest.xml
│  ├─ java/com/fsheartcast/osc/          业务与界面（Java 11，无 Kotlin）
│  └─ res/                               4 语言 strings、drawable、layout、自适应图标
├─ tests/                                JVM 单元测试（run-tests.ps1 + TestMain.java）
├─ tools/
│  ├─ gen-strings.ps1                    桌面版 resx → Android 多语言资源
│  ├─ gen-icons.ps1                      桌面版 AppIcon → Android 图标
│  └─ osc-relay.ps1                      PC 端 UDP 中继（可选）
├─ design/                               UI 静态预览 + 图标归档（icon-archive/）
└─ dist/                                 APK 分发目录
```

### 23.3 数据流

```text
手表 BLE
  ├─ BleScanner      广播 0x180D Service Data（GATT 未就绪时的兜底）
  └─ HeartRateGatt   connectGatt → discoverServices → 写 CCCD → 0x2A37 通知
        ↓
HeartRateParser     8/16 位 BPM、contact、RR 标志
        ↓
HeartRateCore       单例编排：设备表、活动设备、自动连接、离线/重连、平均心率
        ↓
OscUdpClient        手写 OSC 1.0，单线程发送 + 定时复位
        ↓
电脑 UDP:9000       VRChat OSC → Avatar 参数
```

### 23.4 与桌面版的 OSC 契约

13 个心率参数与桌面版附录 A 完全一致（`HR`、`isHRBeat`、`HeartBeatToggle`、`VRCOSC/Heartrate/*`、
`HeartRateInt` 等别名），连接状态四参数也一致。两处**有意改进**：

- `VRCOSC/Heartrate/Average` 改为最近 20 秒滑动平均（桌面版发的是瞬时值，见 P1-6）；
- 所有归一化参数都 clamp 到目标区间（桌面版 `HeartRateFloat01` 没有上限保护）。

### 23.5 关键实现

| 类 | 要点 |
| --- | --- |
| `BleScanner` | `SCAN_MODE_LOW_LATENCY`；失败回调；不用 `ScanFilter`，靠名称/服务数据自行筛选 |
| `HeartRateGatt` | 所有 GATT 调用跑在独立 `HandlerThread`；CCCD 写失败重试 2 次；`disconnectReported` 保证一次连接只上报一次断开；断开即 `close()` 并清空引用（对应桌面版 P1-4） |
| `HeartRateCore` | 单例 + 主线程回调；12s 无信号离线、12s 心率过期、8s 重连间隔、15s 通知看门狗；广播 BPM 在 GATT 未就绪时兜底（对应 P1-3） |
| `OscUdpClient` | 400ms 节流、120ms 心跳复位、发送与定时分两个线程、发送失败自动重开 socket |
| `HeartRateService` | `connectedDevice` 类型前台服务，锁屏继续工作；通知显示 BPM 与推送目标；`PARTIAL_WAKE_LOCK` 60s 续期 |
| `BootReceiver` | `BOOT_COMPLETED` 拉起前台服务，系统限制时静默失败（不崩） |
| `Appearance` / `Lang` | 12 个主题样式（深浅 × 6 强调色）+ 4 语言，`attachBaseContext` 覆盖 Locale |
| `ui/HeartRateRingView` | 自绘心率环：SweepGradient 渐变弧、中心柔光、逐拍外扩脉冲 |
| `UpdateChecker` / `VrchatDiscovery` / `LanDiscovery` / `CrashHandler` | 见 23.9 / 23.10 |

动效全部用系统原生 API（`ViewPropertyAnimator`、`ValueAnimator`、`LayoutAnimationController`、
`TransitionManager`）：切页淡入上浮 + 图标弹一下、数值 420ms 滚动、圆环 560ms 扫弧 + 脉冲、
按钮 0.97 按压回弹、设备列表 50ms 交错淡入、高级面板 220ms 高度展开。

### 23.6 构建方式

两条路都可用：

1. **离线脚本**（本次实际使用，不需要 Gradle/Android Studio）

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\build-apk.ps1
   ```

   需要 JDK 17 + Android SDK `build-tools;34.0.0` + `platforms;android-34`；
   依次执行 aapt2 compile/link、javac、**先打 jar 再交给 d8**（类多时直接传路径会超 Windows 命令行长度）、
   zipalign、apksigner。

2. **Android Studio**：直接打开 `android/`（AGP 8.5.2 / Gradle 8.7 / JDK 17），项目零依赖，同步很快。

版本号要改两处：`app/build.gradle` 的 `versionCode/versionName`，以及 `build-apk.ps1` 的同名参数。

### 23.7 资源生成

- `tools/gen-strings.ps1`：读取桌面版 `HOSC/Resources/Strings*.resx`，按 snake_case 生成
  `values/`、`values-zh-rCN`、`values-zh-rTW`、`values-ja` 四套，共 135 个 key
  （桌面版已有 69 个 + Android 专属追加）。桌面版改文案后重跑即可同步。
- `tools/gen-icons.ps1`：用桌面版**完整** `AppIcon.png`（圆角方块 + 手表 + 心跳 + 信号弧 + OSC 线 + 字标），
  按中心缩到画布的 52%：内容最远点距中心 **32.7%**，落在 108dp 画布 72dp 可见圆（半径 33.3%）以内，
  圆形与圆角矩形遮罩都不会裁到元素。`-LogoScale` 可调，`design/icon-archive/` 保留了三版历史图标。

### 23.8 测试与验证现状

- `tests/run-tests.ps1`：**100 项断言全过**，覆盖 OSC 逐字节编码（地址/标签/对齐/大端）、
  13 个参数地址与公式、clamp、8/16 位心率解析、contact 位、0x180D 广播服务数据、非法数据拒绝。
- 版本比较逻辑另外用 7 组用例验证过（`v1.1.0 > 1.0.0`、`1.10.0 > 1.9.0`、`1.1.0-beta` 等），
  目前是临时脚本，**尚未入库**（后续可并入 tests/）。
- 中继探测做过端到端验证：中继回 `FSHEARTCAST-RELAY <端口>`，测试包逐字节到达。
- **没有做真机/模拟器自动化**：审查环境没有 Android 设备，也没有可用的模拟器加速，
  因此 UI 度量、BLE 实际连接、VRChat 实际收发都需要人工回归。

### 23.9 发布与自动更新

- 发布：GitHub Releases，tag 形如 `v1.2.1`，APK 作为 asset 上传；已发布 v1.1.0 / v1.1.1 / v1.2.0 / v1.2.1。
- 检查：`api.github.com/repos/FSYL001/FS-HeartCast-OSC/releases/latest`，比较 `tag_name` 与安装包
  `versionName`（忽略 `v`，按数字段逐段比较）。
- 时机：启动后 1.5s 静默检查，**只有检查成功才记录时间**（旧实现先记时间再请求，网络失败会导致 24 小时不再重试）；
  「设置 → 关于 → 检查更新」可手动检查。
- 交互：弹窗「立即更新 / 稍后 / 忽略此版本」，忽略会记住 tag；点更新打开 Release 里的 `.apk` 资源。
- 限制：Android 不允许应用静默自装，最终仍需用户在系统安装器里确认一次。
- 发布工具链：本机 `gh` 已登录 `FSYL001`（repo 权限）；`git push` 在本机需要用
  `git -c http.sslBackend=openssl push`（默认 schannel 会握手失败）。

### 23.10 局域网发现，以及一条需要更正的事实

**更正**：本文档 v1.0 与旧 README 曾写「VRChat 只监听 127.0.0.1:9000，手机必须用中继」——**这是错的**。
在该机器实测（VRChat 运行时）：

- `netstat -ano -p UDP` 显示 `0.0.0.0:9000`，即监听所有网卡；
- VRChat 日志：`Advertising Service VRChat-Client-xxxx of type OSC on 9000`；
- 局域网 mDNS 查询 `_osc._udp.local` / `_oscjson._tcp.local` 都有应答。

因此手机**可以直连** `电脑IP:9000`，唯一需要的是 Windows 防火墙放行 UDP 9000：

```powershell
netsh advfirewall firewall add rule name="VRChat OSC 9000" dir=in action=allow protocol=UDP localport=9000
```

「自动检测」的实现随之改成三级：

1. `VrchatDiscovery`：用 Android `NsdManager`（mDNS）浏览 `_osc._udp`（端口即 OSC 端口）与
   `_oscjson._tcp`（确认主机），持有 `MulticastLock`；
2. `LanDiscovery`：如果没找到 VRChat，再发探测包找电脑上的中继（9001）；
3. 都失败：提示检查「VRChat 是否开启 OSC / 是否同一 Wi-Fi / 防火墙 UDP 9000」。

`tools/osc-relay.ps1` 保留为**可选**方案（监听 9001 → 转发 127.0.0.1:9000，并应答探测包），
仅在路由器禁止组播或防火墙策略不放开时使用。

### 23.11 已知限制

- 未在真机验证过；UI 预览图是按同一套色值/间距绘制的静态图，不是截图；
- 启动图标在部分桌面有缓存，更新后可能仍显示旧图标，需要卸载重装或重启桌面；
- 使用调试签名，正式分发前应换正式 keystore；
- 不能静默安装更新（Android 平台限制）；
- 只解析标准心率服务 `0x180D/0x2A37`，不含厂商私有协议；部分手表只允许官方 App 连接，需改用广播模式；
- 一次只用一个活动设备（与桌面版一致）。

### 23.12 桌面版可以借鉴的点

下面这些问题在 Android 端已经处理过，桌面版仍存在（编号对应第 11 节）：

| 问题 | Android 的做法 |
| --- | --- |
| P1-5 扫描启动异常未捕获 | `BleScanner.start()` 全 try/catch，失败只报状态不抛 |
| P1-1 GATT 竞态 | 所有 GATT 操作在单一 HandlerThread 串行；用标志位保证一次连接只上报一次断开 |
| P1-4 断开后字段未清理 | 断开即 `close()` 并清空 device/service/characteristic 引用 |
| P1-3 连上 GATT 后 BPM 冻结 | 广播 BPM 在 GATT 未就绪时继续作为数据源；15s 无通知自动重连 |
| P1-6 Average/归一化 | 真正做 20 秒滑动平均；所有归一化值 clamp |
| P2-6 设置写盘 | SharedPreferences + 500ms 防抖，失焦/暂停时补写 |
## 附录 A. OSC 参数实际发送表

### A.1 每次心率发送（`SendHeartRate`）

| 顺序 | 参数地址 | 类型 | 值 | 备注 |
| --- | --- | --- | --- | --- |
| 1 | 自定义 `HrAddress`，默认 `/avatar/parameters/HR` | int | BPM | 可配置 |
| 2 | 自定义 `BeatAddress`，默认 `/avatar/parameters/isHRBeat` | int | 1 | 120ms 后同一地址发 0 |
| 3 | `/avatar/parameters/HeartBeatToggle` | bool | 翻转值 | 每次心跳翻转 |
| 4 | `/avatar/parameters/VRCOSC/Heartrate/Value` | int | BPM | VRCOSC 兼容 |
| 5 | `/avatar/parameters/VRCOSC/Heartrate/Beat` | bool | 翻转值 | 不复位；语义待验证 |
| 6 | `/avatar/parameters/VRCOSC/Heartrate/Normalised` | float | `clamp(BPM/240,0,1)` | VRCOSC 兼容 |
| 7 | `/avatar/parameters/VRCOSC/Heartrate/Average` | int | BPM | 实际瞬时值 |
| 8 | `/avatar/parameters/HeartRateInt` | int | BPM | 兼容别名 |
| 9 | `/avatar/parameters/HeartRate3` | int | BPM | 兼容别名 |
| 10 | `/avatar/parameters/Heartrate3` | int | BPM | 兼容别名 |
| 11 | `/avatar/parameters/HeartRateFloat01` | float | `BPM/240` | 未 clamp |
| 12 | `/avatar/parameters/HRPercent` | float | `BPM/255` | 兼容别名 |
| 13 | `/avatar/parameters/HeartRate` | float | `BPM/127.5 - 1` | 归一化到 -1..1 |
| 14 | 自定义 `BeatAddress` | int | 0 | 120ms 后复位 |

注意：400ms 内重复调用会直接返回，不发送任何参数；因此上表不是每个广播/GATT 通知都会执行。

### A.2 连接状态发送（`SendConnectedState`）

| 参数地址 | 类型 | 值 |
| --- | --- | --- |
| `/avatar/parameters/VRCOSC/Heartrate/Connected` | bool | `connected` |
| `/avatar/parameters/VRCOSC/Heartrate/Enabled` | bool | `connected`（当前语义） |
| `/avatar/parameters/isHRConnected` | bool | `connected` |
| `/avatar/parameters/isHRActive` | bool | `connected` |

仅在 `OscEnabledCheckBox.IsChecked == true` 时发送。

### A.3 OSC 测试（`SendTest`）

- 调用 `SendHeartRate(..., 72)` 发送 72 BPM；
- 发送 `/chatbox/input`，内容 `FS-HeartCast-OSC OSC Test 72 BPM`，立即显示；
- 2.5 秒后发送空字符串清空聊天框；
- 如果 400ms 节流命中，72 BPM 可能不会真正发送，但聊天框仍会发送（见 P1-6/P2）。

---

## 附录 B. 关键常量/阈值

| 名称 | 值 | 位置 |
| --- | --- | --- |
| Heart Rate Service UUID | `0000180d-0000-1000-8000-00805f9b34fb` | `HeartRateParser` |
| Heart Rate Measurement UUID | `00002a37-0000-1000-8000-00805f9b34fb` | `HeartRateParser` |
| 广播 AD Type | `0x16` | `HeartRateParser.TryParse` |
| 默认 OSC 地址 | `127.0.0.1` | `AppSettings` |
| 默认 OSC 端口 | `9000` | `AppSettings` |
| 默认 HR 地址 | `/avatar/parameters/HR` | `AppSettings` |
| 默认 Beat 地址 | `/avatar/parameters/isHRBeat` | `AppSettings` |
| 维护间隔 | 2 秒 | `MainWindow` |
| 离线阈值 | 12 秒 | `MainWindow.MaintenanceTick` |
| 移除阈值 | 90 秒 | `MainWindow.MaintenanceTick` |
| 心跳复位 | 120ms | `OscService.ScheduleBeatReset` |
| 心率发送节流 | 400ms | `OscService.SendHeartRate` |
| 聊天框清空 | 2500ms | `OscService.SendChatboxTest` |
| 系统主题轮询 | 3 秒 | `ThemeService.StartSystemThemeWatcher` |
| 目标框架 | `net6.0-windows10.0.19041.0` | `HOSC.csproj` |
| 程序集版本 | `1.0.0.0` / `1.0.0` | `HOSC.csproj`/EXE 元数据 |

---

## 附录 C. 术语表

| 术语 | 含义 |
| --- | --- |
| BLE | Bluetooth Low Energy，低功耗蓝牙 |
| GATT | Generic Attribute Profile，BLE 服务/特征访问协议 |
| CCCD | Client Characteristic Configuration Descriptor，控制 Notify/Indicate 的配置描述符 |
| Notify | GATT 特征主动推送通知 |
| RSSI | 接收信号强度指示（dBm） |
| OSC | Open Sound Control，VRChat 使用的参数传输协议 |
| VRCOSC | VRChat 的 OSC 插件/预制体生态 |
| BPM | Beats Per Minute，每分钟心跳次数 |
| Prefab | VRChat Avatar 的可复用组件/参数集合 |
| Avatar 参数 | VRChat Animator 中声明的参数，OSC 地址映射到它 |

---

## 附录 D. 快速交接清单

- [ ] 用 .NET SDK 6.0 打开 `HOSC/HOSC.csproj`，确认能构建。
- [ ] 在蓝牙开启的 Windows 上运行，确认默认自动扫描。
- [ ] 用真实手表验证广播/GATT 两条路径。
- [ ] 用 OSC 监视器确认 14 个参数和连接状态。
- [ ] 在 VRChat 中验证 Avatar 参数。
- [ ] 测试切换设备、断开、关机、走出范围。
- [ ] 测试 4 种语言、3 种主题、6 种强调色。
- [ ] 检查 `%APPDATA%\HOSC\settings.json` 的读写。
- [ ] 阅读 P1/P2 问题，决定修复顺序。
- [ ] 不要删除 `HOSC.Diagnostics`/`logo备份`/发布 zip；先确认作者意图。
**Android 端（v1.2.1）**

- [ ] 用 `build-apk.ps1`（或 Android Studio）跑通构建，确认产物与 `dist/` 里的一致。
- [ ] 真机验证：扫描/连接手表、OSC 直连 `电脑IP:9000`、自动检测（mDNS）、检查更新弹窗。
- [ ] 确认 Windows 防火墙已放行 UDP 9000，否则手机发不进 VRChat（见 23.10）。
- [ ] 按 README 第 11 节发一个 `vX.Y.Z` 的 Release（tag + APK asset），验证更新提示。
- [ ] 修改桌面端文案后重跑 `tools/gen-strings.ps1`，保持 4 语言一致。

---

## 附录 E. 文档变更记录

| 版本 | 日期 | 变更 |
| --- | --- | --- |
| 1.0 | 2026-11-07 | 首次审查：Windows 桌面端只读静态审查，记录 37 项问题（P0 候选 1、P1 6、P2 16、P3 15），新增本文件 |
| 1.1 | 2026-11-08 | 新增第 23 节（Android 端 v1.2.1 完整记录：架构、契约、构建、资源生成、测试、发布与更新、局域网发现、限制）；文档头改为本次 HEAD `4283397`；§0 摘要补充 Android 说明；附录 D 增加 Android 交接清单；新增本附录 |

说明：

- **第 1–22 节的技术结论没有改动**，仍以 2026-11-07 那次只读审查的代码（`5c19cb7`）为准。
  如果桌面端在这之后有代码改动，需要重新审查那部分，而不是套用本文档的结论。
- 第 23.10 节修正了一条事实：VRChat 的 OSC 监听的是 `0.0.0.0:9000`（实测），
  不是早前文档/README 里写的「只监听 127.0.0.1」。桌面版默认发 `127.0.0.1:9000` 不受影响。
- 本次更新只修改本文件；`HOSC/`（桌面端源码与资产）、`README.md` 均未改动。
