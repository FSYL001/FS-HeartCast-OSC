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
4. 电脑上运行中继脚本（见第 3 节，VRChat 只监听本机回环地址）：
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\tools\osc-relay.ps1
   ```
   脚本会打印「手机里填的电脑 IP」。
5. 手机打开 App → 允许蓝牙权限 → 在「VRChat OSC」卡片点「高级」填电脑 IP / 端口（用中继时填 `9001`）→
   点「开始扫描」→ 点手表所在行选中 → 点「连接手表」。
6. 心率出现后，点「OSC 测试」验证链路，然后在 VRChat 里看 Avatar 参数。

App 默认以前台服务运行，**锁屏/切后台继续读取和发送**（通知栏显示 BPM 与推送目标）。

---

## 3. 为什么直接发到「电脑 IP:9000」收不到

VRChat 的 OSC 只监听本机回环 `127.0.0.1:9000`，不监听局域网网卡。
所以需要 PC 端做一次转发：`tools/osc-relay.ps1` 监听 `0.0.0.0:9001` → 原样转发到 `127.0.0.1:9000`，
手机端端口填 **9001**。

```powershell
# 默认 9001 → 9000
powershell -ExecutionPolicy Bypass -File .\tools\osc-relay.ps1

# 顺手加一条入站防火墙规则（需要管理员）
powershell -ExecutionPolicy Bypass -File .\tools\osc-relay.ps1 -AddFirewallRule

# 自定义端口
powershell -ExecutionPolicy Bypass -File .\tools\osc-relay.ps1 -ListenPort 9100 -ForwardPort 9000
```

已实测：中继把 16 字节 / 8 字节的 OSC 测试包逐字节转发到 `127.0.0.1:9000`。

---

## 4. 界面（手机原生，不照搬 PC）

整体是**单屏双标签**的手机结构，不是把桌面窗口缩小：

```
┌ 顶栏  FS-HeartCast-OSC            [已连接] ┐
│                                            │
│   ╭─────────── 心率卡片 ───────────╮        │
│   │      实时心率                 │        │
│   │      ◜◝  72  ◟◞   ← 渐变心率环 │        │
│   │         BPM                   │        │
│   │   HUAWEI WATCH GT 4           │        │
│   │   ● 已连接 · 正在推送          │        │
│   ╰──────────────────────────────╯        │
│   [▶ 开始扫描]      [连接手表]              │
│   ╭ 设备 2        清空 ╮                    │
│   │ (H) HUAWEI WATCH GT 4   72  ● 已连接 │  │
│   │ (R) Redmi Watch 6       65  ● 广播中 │  │
│   ╰──────────────────╯                    │
│   ╭ VRChat OSC   [开] ╮                     │
│   │ 推送目标 · 192.168.1.23:9001           │
│   │ [OSC 测试]            高级 ⌄           │
│   ╰──────────────────╯                    │
└  ♥ 心率                              ⚙ 设置 ┘
```

| 区域 | 设计 |
| --- | --- |
| 心率环 | 232dp 卡片内 204dp 圆环：底环 + 青绿→心跳红的渐变进度弧 + 中心柔光；数值 56sp 大字号，`--` 表示无数据 |
| 状态胶囊 | 右上角药丸标签：待机 / 扫描中 / 已连接，颜色随状态变（灰 / 青绿 / 绿 / 红） |
| 设备行 | 36dp 首字母头像 + 名称/地址 + 右侧 BPM 与状态点；选中态是 12% 强调色底 + 强调色描边 |
| 主按钮 | 52dp 药丸：实心青绿主操作 + 描边次操作，图标内联 |
| OSC 卡片 | 开关 + 推送目标 + 测试按钮；「高级」展开 IP/端口/HR 地址/Beat 地址 |
| 底部标签栏 | 心率 / 设置两个标签，图标+文字同步换色、选中图标轻微放大 |
| 「高级」面板 | 电脑 IP / 端口 / HR 地址 / Beat 地址 + **「自动检测」按钮**：在局域网里找电脑上运行的中继并自动填入地址 |
| 关于卡片 | 版本 + **「检查更新」**（手动）；启动时也会每天静默检查一次 |

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

### 发版流程（让自动更新生效）

1. 改版本号：`app/build.gradle` 的 `versionCode` / `versionName`，以及 `build-apk.ps1` 的同名参数；
2. 构建：`powershell -File .\build-apk.ps1`；
3. 在 GitHub 上创建 Release，**tag 用 `v1.1.0` 这种格式**，并把
   `build\output\FS-HeartCast-OSC-1.1.1-debug.apk` 作为 asset 上传；
4. 手机端下次检查就会提示更新。

Release 里最好写上更新说明（会显示在弹窗正文里，超过 600 字会截断）。