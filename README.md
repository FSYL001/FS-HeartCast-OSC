<p align="center">
  <img src="HOSC/Assets/AppIcon.png" width="96" alt="FS-HeartCast-OSC" />
</p>

<h1 align="center">FS-HeartCast-OSC</h1>

<p align="center">把真实心率通过 OSC 实时带入 VRChat 的 Windows 开源工具</p>

<p align="center">
  <a href="https://github.com/FSYL001/FS-HeartCast-OSC/releases">
    <img src="https://img.shields.io/badge/Release-v1.0.0-3CC4BB" alt="Release v1.0.0" />
  </a>
  <img src="https://img.shields.io/badge/.NET-6.0-512BD4" alt=".NET 6.0" />
  <img src="https://img.shields.io/badge/Platform-Windows%2010%2F11-0078D6" alt="Windows 10/11" />
  <img src="https://img.shields.io/badge/UI-4%20Languages-4CC38A" alt="4 Languages" />
</p>

## 简介

FS-HeartCast-OSC 是一款 Windows 10/11 桌面工具：监听低功耗蓝牙（BLE）心率广播，并把心率、心跳脉冲与连接状态通过 OSC 协议实时发送到 VRChat，让你的虚拟形象跟随真实心跳一起动。

所有数据仅在本机处理，不需要账号、不需要云服务，也不需要手表与 Windows 配对。

<img width="1920" height="1080" alt="VRChat_2026-08-02_19-41-57 789_1920x1080" src="https://github.com/user-attachments/assets/c3d655ee-cc1a-45db-a050-eee8edcef021" />



## 功能特性

- 免配对扫描：使用 `BluetoothLEAdvertisementWatcher` 主动扫描 BLE 广播，无需在 Windows 中配对或绑定手表
- 双通道读取：直接解析标准 Heart Rate Service（`0x180D`）广播；对仅通过 GATT 暴露心率的设备自动建立临时连接，订阅 `0x2A37` 心率通知
- 自动发现与状态维护：设备消失自动标记离线，重新出现自动恢复；12 秒无信号标记离线，90 秒后从列表移除
- 多协议兼容：同时输出 VRCOSC、HRtoVRChat、HeartOSC 与 Mi Band 风格 Prefab 常用参数名
- 内置 OSC 测试：一键发送 72 BPM 测试心率，并在 VRChat 聊天框显示测试提示
- 多语言界面：简体中文、繁体中文、English、日本語
- 外观自定义：深色 / 浅色 / 跟随系统，6 种强调色
- 实用设置：启动时自动扫描、开机自启、窗口置顶、OSC 地址与端口自定义
- 隐私优先：无遥测、无云服务、无账号，全部在本机处理

## 工作原理

```text
[心率设备 (BLE)]
      |
      | 1. 广播标准 Heart Rate Service（0x180D）
      |    部分手表直接把 BPM 放进广播数据
      v
[BluetoothLEAdvertisementWatcher]
      |
      | 2a. 直接解析广播中的 BPM
      | 2b. 若设备仅支持 GATT，临时连接并订阅 0x2A37 通知
      v
[FS-HeartCast-OSC]
      |
      | 3. 按配置的地址与端口封装 OSC 消息
      v
[VRChat OSC（默认 127.0.0.1:9000）]
```

## 设备兼容性

- 支持把 BPM 直接写入标准 `0x180D` 广播数据的设备：直接解析，无需建立连接
- 支持开启 "HR Data Broadcast" 的手表（如华为 GT 系列）：先在手表端打开广播，应用会扫描候选设备，再通过临时 BLE 连接读取心率
- 已与 Windows 配对、且名称符合心率设备特征的设备也会被列出，并自动过滤耳机、音箱等音频设备
- 临时 GATT 连接不会改变手表的配对状态，断开后自动释放

## 快速开始

1. 从 Releases 下载 `FS-HeartCast-OSC-v1.0.0-win-x64.zip`（仓库根目录也保留了该发布包），解压后运行 `FS-HeartCast-OSC.exe`
2. 在手表上开启心率广播（华为等手表：设置 -> HR Data Broadcasts）
3. 应用默认自动开始扫描，确认设备出现在列表并显示 BPM
4. 需要发送到 VRChat 时，先在 VRChat 中开启 OSC，再勾选 "导入 VRChat"（默认地址 `127.0.0.1`，端口 `9000`）
5. 点击 "OSC 测试"，确认 VRChat 收到参数

<img width="2026" height="1214" alt="屏幕截图 2026-08-03 130651" src="https://github.com/user-attachments/assets/0cda7e6c-eacf-4015-98e0-8f988c61f0eb" />

## VRChat 配置

1. 打开 VRChat 菜单 -> Options -> OSC，确认 OSC 已开启
2. 保持默认发送地址 `127.0.0.1:9000`，或在应用设置中修改
3. 在虚拟形象的动画控制器中声明并映射需要的参数（见下表）

## OSC 参数说明

应用会向配置的 OSC 地址发送以下参数，方便直接对接常见 Prefab：

| 参数地址 | 类型 | 说明 |
| --- | --- | --- |
| `/avatar/parameters/HR` | int | 心率 BPM（默认地址，可配置） |
| `/avatar/parameters/isHRBeat` | int | 心跳脉冲，发送 `1` 后约 120ms 复位为 `0`（默认地址，可配置） |
| `/avatar/parameters/HeartBeatToggle` | bool | 心跳开关，每次心跳翻转 |
| `/avatar/parameters/VRCOSC/Heartrate/Value` | int | VRCOSC 兼容 BPM |
| `/avatar/parameters/VRCOSC/Heartrate/Beat` | bool | VRCOSC 兼容心跳 |
| `/avatar/parameters/VRCOSC/Heartrate/Normalised` | float | 归一化心率，`BPM / 240` |
| `/avatar/parameters/VRCOSC/Heartrate/Average` | int | VRCOSC 兼容平均值 |
| `/avatar/parameters/VRCOSC/Heartrate/Connected` | bool | 设备连接状态 |
| `/avatar/parameters/VRCOSC/Heartrate/Enabled` | bool | OSC 启用状态 |
| `/avatar/parameters/isHRConnected` | bool | 连接状态别名 |
| `/avatar/parameters/isHRActive` | bool | 激活状态别名 |
| `/avatar/parameters/HeartRateInt` | int | HRtoVRChat 等兼容别名 |
| `/avatar/parameters/HeartRate3` | int | 兼容别名 |
| `/avatar/parameters/Heartrate3` | int | 兼容别名 |
| `/avatar/parameters/HeartRateFloat01` | float | 浮动值，`BPM / 240` |
| `/avatar/parameters/HRPercent` | float | 百分比，`BPM / 255` |
| `/avatar/parameters/HeartRate` | float | 归一化到 `-1..1`，`BPM / 127.5 - 1` |

## 从源码构建

要求：

- Windows 10（Build 19041）或更高版本
- .NET SDK 6.0
- Visual Studio 2022 或 PowerShell 命令行环境

还原并构建：

```powershell
dotnet restore HOSC\HOSC.csproj -r win-x64
dotnet build HOSC\HOSC.csproj -c Release
```

发布为单文件 EXE：

```powershell
dotnet publish HOSC\HOSC.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true
```

输出位于 `HOSC\bin\Release\net6.0-windows10.0.19041.0\win-x64\publish\FS-HeartCast-OSC.exe`。

## 项目结构

```text
HOSC/
├─ App.xaml / App.xaml.cs           # 应用入口：设置加载、主题与语言初始化
├─ MainWindow.xaml(.cs)             # 主界面：扫描、设备列表、OSC 发送
├─ SettingsWindow.xaml(.cs)         # 设置窗口：通用、外观、关于
├─ Models/HeartRateDevice.cs        # 设备模型与连接状态
├─ Services/
│  ├─ BluetoothHeartRateScanner.cs  # BLE 广播扫描与已配对设备刷新
│  ├─ HeartRateParser.cs            # 0x180D 广播与 0x2A37 数据解析
│  ├─ GattHeartRateService.cs       # 临时 GATT 连接与通知订阅
│  ├─ OscService.cs                 # OSC 消息编码与发送
│  ├─ AppSettings.cs                # 设置读写（%APPDATA%\HOSC\settings.json）
│  ├─ ThemeService.cs               # 深色/浅色/跟随系统与强调色
│  └─ StartupService.cs             # 开机自启注册
├─ Resources/                       # 多语言资源
├─ Themes/                          # 深色与浅色主题
└─ Assets/                          # 图标与社区 Logo
```

## 常见问题

**列表中找不到手表。**
确认手表已开启心率广播（华为等手表：设置 -> HR Data Broadcasts），并确认 Windows 蓝牙已开启。

**状态显示 "Bluetooth is off"。**
打开 Windows 蓝牙后再点击 "开始扫描"。

**VRChat 收不到心率。**
确认 VRChat 的 OSC 已开启、地址端口正确，并先点击 "OSC 测试"；若测试成功，请检查虚拟形象是否声明了对应参数。

**会不会影响手表与手机的连接？**
应用只在设备活跃时建立临时 BLE 连接，不进行配对或绑定，断开后自动释放。

## 隐私说明

心率数据、设备信息与配置均只在本机处理。应用不包含遥测、不上传任何数据，也不需要注册账号。配置文件保存在 `%APPDATA%\HOSC\settings.json`。

## 社区与支持

- GitHub：<https://github.com/FSYL001/FS-HeartCast-OSC>
- Bilibili：<https://space.bilibili.com/450000657>
- 抖音：<https://www.douyin.com/user/MS4wLjABAAAAzWfG071S0Ol0_wF3xT1i0oL5eSpPdiYZZY_gKib9S_M>
- 爱发电：<https://ifdian.net/a/fsyl001>
- 博客：<https://blog.fsyl001.sbs>
- 邮箱：`email@fsyl001.sbs` / `3320969905@qq.com`

如果这个项目对你有帮助，欢迎 Star，也欢迎提交 Issue 与 Pull Request。

## 贡献指南

- 提交 Issue 时请附上 Windows 版本、设备型号与复现步骤
- 欢迎补充设备兼容性报告与多语言翻译
- 提交 PR 前请确保 `dotnet build -c Release` 构建通过

## 许可证

当前仓库尚未附带 `LICENSE` 文件，具体许可证将在后续版本中明确。在此之前，代码仅供个人学习与测试使用。
