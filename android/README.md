# FS-HeartCast OSC · Android 版

手机连手表读心率，把 BPM 通过 **OSC (UDP)** 推到同一局域网里的电脑，让 VRChat 的 Avatar 参数跟着心率变化。

这是桌面板 `FS-HeartCast-OSC`（Windows / WPF）的 Android 配套实现：

- **OSC 参数名和公式与桌面版一致**，同一个 Avatar 可以直接在两端切换；
- **界面沿用桌面版的信息层级与配色**（`HOSC/Themes/Dark.xaml`、`Light.xaml`），
  顶部 Logo + 标题 + 设置，左侧「实时心率 + 设备表」，右侧「VRChat OSC」面板；
- **设置页与桌面版一致**：通用 / 外观 / 关于；
- **4 种语言**：简体中文、繁體中文、English、日本語，文案直接复用桌面版 `.resx`；
- **图标就是桌面版完整 `AppIcon`**（圆角方块 + 手表 + 信号弧 + OSC 线 + 底部字标），
  按中心等比缩到画布 52%：完整内容（含字标）最远点距中心 32.7%，
  落在 108dp 画布的 72dp 可见圆（半径 33.3%）以内，圆形与圆角矩形遮罩都不会裁到任何元素；
  状态栏图标是同款白色心跳。

  图标三版都留了底，放在 `design/icon-archive/`：
  `v1` 最早的自绘青绿心跳（已废弃）、`v2` 68% 版（会被圆角遮罩裁切）、
  `v3` 当前 58% 版（APK 正在用）。`compare-masks.png` 是三者叠加安全圆的对比图。
  桌面版的 `HOSC/Assets/` 原图标未做任何修改。

```
手表 BLE ──► 手机（本 App） ──► 局域网 UDP/OSC ──► 电脑 ──► VRChat Avatar 参数
  0x180D / 0x2A37          电脑IP:9001(中继)     127.0.0.1:9000
```

---

## 1. 已经打包好的 APK

```
android\build\output\FS-HeartCast-OSC-1.1.1-debug.apk
android\dist\FS-HeartCast-OSC-1.1.1-debug.apk      ← 同一份
```

- 最低 Android 8.0（API 26），目标 API 34，已在 Android 14/15 的目标版本上编译；
- 约 264 KB，**不依赖任何第三方 / AndroidX 库**；
- `adb install -r FS-HeartCast-OSC-1.1.1-debug.apk`，或传到手机点击安装。

---

## 2. 使用步骤

1. **手表开启心率广播**。华为/荣耀等：设置 → 心率数据广播；只允许官方 App 连接的手表必须走这个模式。
2. 手机和电脑连**同一个 Wi-Fi**。
3. 电脑上启动 VRChat 并开启 OSC（Options → OSC）。
4. （可选）只有直连不通时才需要在电脑上跑中继脚本，见第 3 节：
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\tools\osc-relay.ps1
   ```
   脚本会打印「手机里填的电脑 IP」。
5. 手机打开 App → 允许蓝牙权限 → 「VRChat OSC → 高级 → 自动检测」（会通过 mDNS 找到 VRChat，自动填 IP:9000）→
   点「开始扫描」→ 点手表所在行选中 → 点「连接手表」。
6. 心率出现后，点「OSC 测试」验证链路，然后在 VRChat 里看 Avatar 参数。

App 默认以前台服务运行，**锁屏/切后台继续读取和发送**（通知栏显示 BPM 与推送目标）。

---

## 3. 网络：能直连，不需要在电脑上装东西

VRChat 开启 OSC 后监听 **UDP 0.0.0.0:9000**（本机所有网卡），并对局域网广播两个 mDNS 服务：

| 服务 | 用途 |
| --- | --- |
| `_osc._udp` | 端口就是 OSC 端口（默认 9000），手机据此直连 |
| `_oscjson._tcp` | OSCQuery，HTTP 端口随机，只用于确认主机存在 |

所以：**手机 → 电脑 IP:9000 直接发就行**，电脑端不需要安装任何软件或脚本。

唯一需要放行的是 Windows 防火墙：第一次运行 VRChat 时如果弹过提示，选「允许（专用网络）」；
如果没弹或选错了，用管理员 PowerShell 补一条：

```powershell
netsh advfirewall firewall add rule name="VRChat OSC 9000" dir=in action=allow protocol=UDP localport=9000
```

手机端点「高级 → 自动检测」会通过 mDNS 直接找到 VRChat 并填好 IP 与 9000 端口。

> `tools/osc-relay.ps1` 仍然保留，但只是**备选**：只有在路由器禁止组播、或要不经过防火墙策略
> 才需要它（监听 9001 → 转发 127.0.0.1:9000，手机端口填 9001）。
## 4. 界面（全新重做 UI v3）

整体是**单屏双标签**的现代化手机架构，彻底重构了视觉层次、动效质感与信息密度：

```
┌ 顶栏  FS-HeartCast [VRChat Bridge]           [● 已连接] ┐
│                                                        │
│   ╭────────────────── 实时心率监测 ──────────────────╮  │
│   │   [♥] 实时心率监测                     BLE GATT   │  │
│   │                                                    │  │
│   │             ╭─ 60格精密刻度仪表盘 ─╮               │  │
│   │            (       72  BPM        ●) ← 发光端点     │  │
│   │             ╰─── 动态ECG波形 ────╯                │  │
│   │                                                    │  │
│   │   ╭────────────────────────────────────────────╮   │  │
│   │   │ (⌚) HUAWEI WATCH GT 4 │ ● 已连接 · 正在推送 │   │  │
│   │   ╰────────────────────────────────────────────╯   │  │
│   ╰────────────────────────────────────────────────────╯  │
│                                                        │
│   [● 开始扫描 (主)]                [断开连接 (次)]       │
│                                                        │
│   ╭ 心率设备 [2]                                清空 ╮  │
│   │ ╭────────────────────────────────────────────╮ │  │
│   │ │ (H) HUAWEI WATCH GT 4           72 BPM    │ │  │
│   │ │     F4:8C:EB:11:22:33           ● 已连接   │ │  │
│   │ ╰────────────────────────────────────────────╯ │  │
│   │ ╭────────────────────────────────────────────╮ │  │
│   │ │ (R) Redmi Watch 6               65 BPM    │ │  │
│   │ │     72:05:62:DA:E0:63           ● 广播中   │ │  │
│   │ ╰────────────────────────────────────────────╯ │  │
│   ╰────────────────────────────────────────────────╯  │
│                                                        │
│   ╭ VRChat OSC                                 [开] ╮  │
│   │ ╭────────────────────────────────────────────╮ │  │
│   │ │ 推送目标 · 192.168.1.100:9000              │ │  │
│   │ │ 已发送 128 个数据包 (节流 400ms)           │ │  │
│   │ ╰────────────────────────────────────────────╯ │  │
│   │ [OSC 测试]                         网络设置 ⌄   │  │
│   ╰────────────────────────────────────────────────╯  │
└  ╭── ♥ 心率 ──╮                           ⚙ 设置       ┘
```

| 区域 | 设计亮点 |
| --- | --- |
| 心率仪表盘 | 216dp 圆环：外圈 60 格精密刻度环（随心率动态点亮） + 智能心率区间动态渐变（静息/正常/燃脂/极限四色） + 弧端发光粒子（Glowing Bead） + 逐拍外扩脉冲 + 内置平滑动态 ECG 心电律动波形 |
| 英雄卡片底部 Dock | 告别旧版大片空白：在卡片底部集成悬浮设备状态条，左侧显示活动手表名称，右侧显示实时推送状态 |
| 状态胶囊 | 右上角药丸标签：待机 / 扫描中 / 已连接，带状态发光圆点，颜色动态响应 |
| 操作指令栏 | 明确主次层级：主按钮实心高光青绿 + 雷达扫描图标，次按钮采用柔和表面底色（Tonal Surface） |
| 设备卡片 | 解决旧版文字与 BPM 重叠 Bug：设备名称自适应截断、MAC 地址采用等宽字体（monospace）、BPM 专设大字号加粗并附带单位标签 |
| 底部悬浮标签栏 | 现代悬浮 Dock 风格，当前活跃标签带半透明圆角胶囊背景块（Pill Indicator），切换更灵动 |
| 设计预览图 | 见 `design/ui-v3-main.png`、`design/ui-v3-settings.png` 及对比图 `design/ui-comparison.png` |

## 5. 设置（同一屏内的第二个标签）

按手机习惯做成**分组行**，而不是桌面版的三栏页面：

| 分组 | 行 |
| --- | --- |
| 启动 | 启动时自动开始扫描（带说明）、开机自动启动 |
| 显示 | 保持屏幕常亮、后台持续运行（前台服务） |
| 外观 | 主题：跟随系统 / 浅色 / 深色（**带滑动块的 segmented control**）<br>强调色：6 个色点，选中态是本色的圆环<br>语言：点击弹出单选对话框（跟随系统 / 简 / 繁 / EN / 日） |
| 关于 | 标记 + 版本、社区主页四项（带图标，可跳转）、站长邮箱、隐私说明、© 署名 |

切换主题/强调色/语言会立即重建界面（保留当前标签页），深浅色两套都是独立调过的配色，
不是把深色反相：深色是近黑分层（`#0A0C0E` / `#12161A`），浅色是冷白分层（`#F6F8FA` / `#FFFFFF`）。

### 动效（都是系统原生属性动画，无第三方库）

| 场景 | 效果 |
| --- | --- |
| 标签切换 | 旧页 120ms 淡出+下移，新页 220ms 淡入上浮；图标换色并 1.14× 弹一下 |
| 心率变化 | 数字 420ms 滚动到新值（DecelerateInterpolator），同时字号 1.06→1 |
| 每次心跳 | 圆环进度弧 560ms 扫到新比例 + 一圈外扩淡出的脉冲 |
| 按钮/色点 | 按压 0.97 回弹（90ms 下压、160ms 弹回），叠加 ripple |
| 设备列表 | 数量变化时子项按 50ms 交错淡入上浮 |
| 高级设置 | `TransitionManager` + `AutoTransition` 220ms 高度展开/收起，图标同步换向 |
| 主题/强调色 | 分段块滑动 200ms、色点放大后重建 |
## 6. OSC 参数（与桌面版一致）

每次心率变化（400ms 节流）发送 13 个参数，120ms 后把心跳地址复位为 0：

| 地址 | 类型 | 值 |
| --- | --- | --- |
| `/avatar/parameters/HR`（可改） | int | BPM |
| `/avatar/parameters/isHRBeat`（可改） | int | 1，120ms 后 0 |
| `/avatar/parameters/HeartBeatToggle` | bool | 每次心跳翻转 |
| `/avatar/parameters/VRCOSC/Heartrate/Value` | int | BPM |
| `/avatar/parameters/VRCOSC/Heartrate/Beat` | bool | 翻转值 |
| `/avatar/parameters/VRCOSC/Heartrate/Normalised` | float | `clamp(BPM/240, 0, 1)` |
| `/avatar/parameters/VRCOSC/Heartrate/Average` | int | 最近 20 秒平均 BPM |
| `/avatar/parameters/HeartRateInt` | int | BPM |
| `/avatar/parameters/HeartRate3` | int | BPM |
| `/avatar/parameters/Heartrate3` | int | BPM |
| `/avatar/parameters/HeartRateFloat01` | float | `clamp(BPM/240, 0, 1)` |
| `/avatar/parameters/HRPercent` | float | `clamp(BPM/255, 0, 1)` |
| `/avatar/parameters/HeartRate` | float | `clamp(BPM/127.5-1, -1, 1)` |

连接状态（连上 / 断开各一次）：`VRCOSC/Heartrate/Connected`、`VRCOSC/Heartrate/Enabled`、
`isHRConnected`、`isHRActive`。

与桌面版的两处**有意改进**：`Average` 是真正的 20 秒滑动平均；归一化参数都做了 clamp。

---

## 7. 技术实现

```
MainActivity / SettingsActivity      界面（主题 + 多语言）
        │
HeartRateCore（单例编排，主线程回调）
   ├─ BleScanner       BluetoothLeScanner（LOW_LATENCY）
   ├─ HeartRateGatt    connectGatt → discoverServices → CCCD → 0x2A37 通知
   ├─ OscUdpClient     DatagramSocket 发送，独立线程
   └─ HeartRateService 前台服务（connectedDevice），锁屏保活
```

- **主题**：`AppThemeBase_Dark/Light` + 6 种强调色共 12 个样式，色值来自桌面版两个 xaml；
  布局里用 `?attr/panelBg`、`?attr/accent` 等自定义属性，切换主题即 `recreate()`。
- **语言**：`Lang.wrap()` 在 `attachBaseContext` 里覆盖 Locale，资源目录
  `values/`（英文默认）、`values-zh-rCN`、`values-zh-rTW`、`values-ja`。
- **文案来源**：`tools/gen-strings.ps1` 读取桌面版 `HOSC/Resources/Strings*.resx`，
  按 snake_case 生成 96 个 key × 4 种语言，Windows 端改名后重跑即可同步。
- **图标**：`ic_launcher` 为自适应图标，前景取自桌面版 `AppIcon.png`（裁掉字标），
  底色 `#111318` 与桌面版一致；标题栏用小尺寸标记，与桌面版 `Assets/Logo.xaml` 的构图相同。
- 交接文档里列的 P1 问题在安卓端已规避：扫描异常捕获、GATT 操作串行化、断开清理、
  广播 BPM 兜底、通知超时重连、音频设备过滤。

### 目录

```
android/
├─ build-apk.ps1                 离线构建（不需要 Gradle / Android Studio）
├─ build.gradle / settings.gradle / gradle.properties
├─ app/src/main/
│  ├─ AndroidManifest.xml
│  ├─ java/com/fsheartcast/osc/
│  │  ├─ MainActivity.java  SettingsActivity.java  BootReceiver.java
│  │  ├─ HeartRateCore.java  HeartRateService.java
│  │  ├─ BleScanner.java    HeartRateGatt.java
│  │  ├─ HeartRateParser.java  OscUdpClient.java
│  │  ├─ OscMessage.java    OscParams.java
│  │  ├─ Appearance.java    Lang.java  Prefs.java  DeviceEntry.java
│  └─ res/（values ×4 语言、drawable、layout、mipmap 自适应图标）
├─ tests/                        JVM 单元测试（100 项断言）
└─ tools/
   ├─ gen-strings.ps1            从桌面版 resx 生成多语言资源
   └─ osc-relay.ps1              PC 端 UDP 中继
```

---

## 8. 自己构建

### 方式 A：离线脚本（无需 Gradle）

需要 JDK 17 + Android SDK build-tools 34 + platform android-34：

```powershell
powershell -ExecutionPolicy Bypass -File .\build-apk.ps1
powershell -ExecutionPolicy Bypass -File .\build-apk.ps1 -AndroidHome "D:\Android\Sdk" -JdkHome "C:\Program Files\Java\jdk-17"
```

产物：`build\output\FS-HeartCast-OSC-1.1.1-debug.apk`（`debug.keystore` 自签名，仅调试分发）。

### 方式 B：Android Studio

直接打开 `android/`（AGP 8.5.2 / Gradle 8.7 / JDK 17），`gradlew assembleDebug`。
项目没有任何第三方依赖。

### 测试与文案

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\run-tests.ps1   # 100 项断言
powershell -ExecutionPolicy Bypass -File .\tools\gen-strings.ps1  # 从桌面版 resx 重新生成文案
```

---

## 9. 排错

| 现象 | 处理 |
| --- | --- |
| 扫描列表空的 | 手表要处于心率广播状态；Android 11 及以下还需「位置信息」权限且系统定位开关打开 |
| 能看到手表但连接失败 | 手表被官方 App 占用（先断开华为/小米运动健康）；或改用广播模式 |
| 已连接但 BPM 一直 `--` | 手表没在广播心率；15 秒无通知会自动重连 |
| OSC 测试 VRChat 没反应 | 端口是否填了中继端口 9001；中继是否在跑；防火墙放行 UDP；VRChat OSC 是否开启 |
| 参数到了但 Avatar 不动 | Animator 参数名/类型要和第 6 节一致（int/float/bool 不能混） |
| 锁屏后停了 | 检查通知是否还在；国产 ROM 需允许「后台运行/自启动」 |
| 开机没自动启动 | 部分系统限制后台启动前台服务，这是系统行为，手动打开即可 |
| 语言/主题没变 | 设置页改动会重建界面；若从系统设置里改了语言，App 内「跟随系统」会跟着变 |

---

## 10. 已知边界

- 一次只用**一个**活动设备（与桌面版一致）；
- 只解析标准心率服务 `0x180D` / `0x2A37`，不含厂商私有协议；
- 不做医学校准/滤波，只转发设备上报的数值；
- OSC 无认证/加密（协议如此），只在可信局域网使用；
- 中继与 VRChat 的 OSC 输入端口共用 `127.0.0.1:9000`，不会影响 VRChat 自身输出。

---

## 11. 自动检测电脑 & 自动更新

### 自动检测（不用手敲 IP）

「VRChat OSC → 高级」里的 **自动检测** 会：

1. 枚举手机当前的 IPv4 网段；
2. 向本网段 254 台主机 + 广播地址的 **9001 / 9000** 端口发 UDP 探测包 `FSHEARTCAST/1`；
3. 电脑上的 `osc-relay.ps1` 收到后回 `FSHEARTCAST-RELAY <端口>`，于是自动把 IP 和端口填进 OSC 主机。

VRChat 本身不回包，所以「检测到」= 电脑上的中继在跑 —— 这也正是手机能发进 VRChat 的前提。
检测不到时会提示「请确认电脑上已运行 osc-relay.ps1」。

### 自动更新

- 每次启动后静默检查一次（间隔 24 小时），发现新版本弹窗：「立即更新 / 稍后 / 忽略此版本」；
- 「设置 → 关于 → 检查更新」可手动检查；
- 数据来自 GitHub Releases API（`FSYL001/FS-HeartCast-OSC` 的 `releases/latest`），
  比较 `tag_name` 与安装包的 `versionName`（忽略 `v` 前缀，按数字段逐段比较）；
- 点「立即更新」会打开该 Release 里的 `.apk` 资源（没有资源就打开 Release 页面）；
- 「忽略此版本」会记住这个 tag，之后不再提示该版本。

### 闪退排查

App 会把未捕获异常的堆栈写到应用私有目录，并在「关于」里显示「上次崩溃日志」一行（有记录时才出现），
点一下即可复制到剪贴板，方便贴给开发者。检查更新失败时会退化为直接打开下载页，不会卡住。
### 发版流程（让自动更新生效）

1. 改版本号：`app/build.gradle` 的 `versionCode` / `versionName`，以及 `build-apk.ps1` 的同名参数；
2. 构建：`powershell -File .\build-apk.ps1`；
3. 在 GitHub 上创建 Release，**tag 用 `v1.1.0` 这种格式**，并把
   `build\output\FS-HeartCast-OSC-1.1.1-debug.apk` 作为 asset 上传；
4. 手机端下次检查就会提示更新。

Release 里最好写上更新说明（会显示在弹窗正文里，超过 600 字会截断）。