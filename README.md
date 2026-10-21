# 荣耀 SystemUI 增强（HonorSystemUI）

一款针对荣耀 / Honor 系统界面（`com.android.systemui`）的 LSPosed 模块，只提供**状态栏三合一**：把 Wi-Fi、移动信号、电量合并为一个 Duo 风格字形。

> 本项目基于 [HonorHook](https://github.com/coderstory/HonorHook)（AGPL-3.0）裁剪重构，仅保留状态栏三合一功能。

---

## 功能一览

### 状态栏

| 开关 | 默认 | 说明 |
| --- | --- | --- |
| 状态栏三合一 | 开 | 将 Wi-Fi、移动信号、电量合并为一个 Duo 风格字形：外圈电量环、环内 Wi-Fi 弧 / 电量数字、底部信号点；原生三图标隐藏。 |
| 三合一：显示 Wi-Fi 弧 | 开 | 环内上部绘制 Wi-Fi 弧线，弧数跟随真实信号强度。 |
| 三合一：显示移动信号点 | 开 | 环内底部绘制 4 个信号点（单卡，取默认数据卡）。 |
| 三合一：显示电量数字 | 开 | 环心显示电量百分比；充电时固定显示闪电。 |
| 三合一：无 Wi-Fi 时显示 5G/4G | 开 | 没有 Wi-Fi 时环内上部改显示当前网络制式文本。 |
| 三合一：彩色强调 | 开 | 充电绿色、快充琥珀、低电量红色；关闭后所有元素跟随状态栏反色。 |

### 三合一图标大小

提供 18–26 dp 滑杆，按 1 dp 调节（默认 20 dp）。固定 dp 值，横屏与锁屏不会随宿主行高放大。

---

## 安装

### 前置条件

| 项目 | 要求 |
| --- | --- |
| 系统 | Honor / MagicOS（已在 SystemUI 17.0.0.1 上验证） |
| Android | 8.0 及以上（API 26+） |
| 框架 | 支持 libxposed **API 102** 的 LSPosed |

### 步骤

1. 安装本模块 APK。
2. 打开 **LSPosed 管理器** → 模块 → 启用 **HonorSystemUI**。
3. 作用域勾选 **系统界面（`com.android.systemui`）**。
4. 打开 App 调整开关 → 点「重启系统界面」（需要 root 授权），或手动重启手机。

重启系统界面也可通过命令：

```sh
adb shell "su -c 'killall com.android.systemui'"
```

> 开关改动在 SystemUI 进程重启后生效。

---

## 构建

本项目使用纯命令行工具构建，**无需 Gradle**。

### 依赖

- JDK 17+
- `kotlinc`（Kotlin 编译器）
- Android SDK build-tools 35+：`aapt2`、`d8`、`zipalign`、`apksigner`
- `android.jar`（API 34）

### 构建命令

```sh
# 默认从 ../tools 读取工具链
./build.sh

# 或通过环境变量指定
TOOLS=/path/to/tools ./build.sh
```

构建产物：`build/HonorSystemUI-Trio-1.0.1.apk`

---

## 目录结构

```
HonorSystemUI/
├── AndroidManifest.xml          # 清单文件
├── META-INF/xposed/             # LSPosed 模块描述（module.prop / scope.list）
├── res/                         # 资源（strings.xml）
├── src/io/github/voreulch/honorsystemui/
│   ├── FeatureCatalog.kt        # 功能开关目录（key 的唯一事实源）
│   ├── ui/
│   │   ├── App.kt               # Application
│   │   ├── MainActivity.kt      # 设置页 UI
│   │   ├── FeatureStore.kt      # 本地/远端配置读写
│   │   └── FrameworkState.kt    # XposedService 连接状态
│   └── xposed/
│       ├── HookEntry.kt         # 入口（registerHook）
│       ├── core/                # 通用 hook 框架（挂载、调度、解析）
│       ├── data/RemoteConfig.kt # hook 侧只读远端配置
│       └── target/              # SystemUI 功能 hook
│           ├── SystemUiHook.kt  # 目标入口
│           ├── TrioFeature.kt   # 状态栏三合一
│           └── TargetHook.kt
├── build.sh                     # 构建脚本
├── ks.jks                       # 签名密钥（构建时自动生成，不打包发布）
├── LICENSE                      # AGPL-3.0
└── NOTICE                       # 第三方代码声明
```

---

## 三合一字形绘制几何

字形在 120×120 设计空间内绘制，运行时按视图实际大小缩放。关键参数：

- **电量环**：圆心 `(59.5, 61.49)`，半径 `51.5`，描边 `14`，起始 `148.69°`，扫过 `242.62°`；顶部留 `0.325–0.675` 的缺口给 Wi-Fi / 数字，底部缺口给信号点，常态为左右两段圆弧。
- **Wi-Fi 弧**：沿用原项目 `TrioGeometry` 的圆心 / 半径 / 起止角，整体上移并缩放进顶部缺口。
- **信号点**：4 个圆点贴合环底排布，点亮数跟随真实信号强度。
- **电量数字**：环心居中，`40` 号字；充电时由闪电路径替换。
- **配色**：充电 `#34C759`、快充 `#FFB300`、低电量 `#F2B900`、危险 `#FF3B30`。

---

## 常见问题

**状态栏完全没有变化？**

依次确认：模块已在 LSPosed 中启用 → 作用域勾选「系统界面」→ 已重启系统界面。

**三合一图标在横屏 / 锁屏变大？**

已修复：图标使用固定 dp 尺寸（18–26 可选），不跟随宿主状态栏行高拉伸。

**充电样式不生效？**

充电判定使用电池 sticky 广播（`EXTRA_STATUS` / `EXTRA_PLUGGED`），不依赖可能滞后的 `BatteryManager.isCharging`；快充由 `CURRENT_NOW ≥ 1.5A` 判定。

**原生电池 / Wi-Fi 图标没隐藏？**

三合一总开关开启后，注入时会在 `HwSystemIcons` / `KeyguardStatusBar` 等容器中隐藏 `wifi` / `mobile` / `battery` 等子视图，并挂 `OnPreDrawListener` 每帧重申。如仍可见，请检查是否有其它模块同时改动同一区域。

---

## 许可

本项目基于 **GNU Affero General Public License v3.0** 发布，全文见 `LICENSE`。

- 上游项目：[HonorHook](https://github.com/coderstory/HonorHook)（AGPL-3.0，Copyright © 2026 coderstory）
- 三合一字形几何参数参考 [HyperDuo](https://github.com/yixing233/HyperDuo)（AGPL-3.0）

荣耀 / Honor / MagicOS / YOYO 等商标属其各自权利人所有，本项目与之无隶属或背书关系。
