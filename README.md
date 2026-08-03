# FS-HeartCast-OSC

FS-HeartCast-OSC 是一个 Windows 10/11 本地工具：监听 BLE 心率广播，并把心率通过 OSC 实时发送到 VRChat。

## 功能

- 使用 `BluetoothLEAdvertisementWatcher` 扫描 BLE 广播，不需要在 Windows 中配对/绑定手表
- 解析标准 Heart Rate Service（`0x180D`）广播数据并显示 BPM
- 对华为等只通过 GATT 暴露心率的设备，自动临时连接读取 `0x2A37`，不需要手动配对
- 自动发现、持续监听；设备消失后标记离线，重新出现后自动恢复
- 支持 HR 与 isHRBeat 两个 OSC 参数，地址和端口可配置
- 内置 OSC 测试
- 简体中文、繁体中文、English、日本語
- 深色 / 浅色 / 跟随系统
- 所有数据仅在本机处理

## 使用

1. 在手表端开启「心率广播」。
2. 启动 FS-HeartCast-OSC，确认设备出现在列表中并显示 BPM。
3. 需要发送到 VRChat 时，打开「导入 VRChat」，确认 OSC 地址与端口。
4. 点击「OSC 测试」检查 VRChat 是否收到参数。

华为 GT4 等手表的「HR Data Broadcast」会把标准 Heart Rate Service（`0x180D`）暴露出来，FS-HeartCast-OSC 会先扫描所有候选 BLE 设备，再通过 GATT 读取 `0x2A37` 心率通知。这是临时 BLE 连接，不是配对/绑定；手表上仍需先在「设置 → HR Data Broadcasts」开启广播。

部分手表把 BPM 直接放进标准 `0x180D` 广播数据时，FS-HeartCast-OSC 也会直接解析，不需要建立连接。

## 构建

```powershell
dotnet restore HOSC\HOSC.csproj -r win-x64
dotnet build HOSC\HOSC.csproj -c Release
```

发布为单文件 EXE：

```powershell
dotnet publish HOSC\HOSC.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true
```

输出位于 `HOSC\bin\Release\net6.0-windows10.0.19041.0\win-x64\publish\FS-HeartCast-OSC.exe`。
