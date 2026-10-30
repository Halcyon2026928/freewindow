# 灵动坞OS

LSPosed 模块：给 ColorOS / realme UI 的自由小窗加上一个带**实时画面**的悬浮球坞窗，顺手把两件小事也做了——隐藏底部白条和三点菜单栏。

> 原项目名 freewindow，从 3.2 起更名「灵动坞OS」。

## 功能

### 坞窗

点悬浮球，从侧边滑出一列卡片，每个悬浮球里的应用一张：

- **静态应用显示截图**：打开就有画面，不黑屏、不耗电；
- **动态应用显示实时画面**：在设置里勾选的应用（视频、短视频、游戏等）卡片会实时刷新；
- 点卡片 → 应用按系统原样恢复成带标题栏的自由小窗；
- 点坞窗外或「关闭」→ 收起坞窗，悬浮球照常显示；
- 「清除」→ 一键清掉坞窗里所有后台应用。

### 后台保活

应用收进悬浮球后仍在后台继续运行（走 ColorOS 的幻影窗口），声音和画面都不中断。可在设置里关闭。

### 隐藏装饰

- 隐藏自由小窗底部的手势白条
- 隐藏顶部三点菜单栏（视觉、点击、触摸穿透都处理）
- 不影响自由小窗本身的手势操作

### 智能侧边栏

解除编辑模式下「最多添加 20 个应用/工具」的限制（原 sidebar-hook 模块已并入，不用再单独装）。

## 设置

| 开关 | 作用 |
|---|---|
| 隐藏窗口白条 | 隐藏底部手势横条 |
| 隐藏三点菜单栏 | 移除顶部三点按钮 |
| 启用坞窗 | 点悬浮球弹坞窗；关掉后按系统默认恢复小窗 |
| 实时画面 | 坞窗卡片的画面来源总开关 |
| 后台保活 | 收进球里继续运行 |
| 动态应用（实时预览） | 勾选哪些应用走实时画面，其余只显示截图 |

改完开关不需要重启手机，**重新打开一次坞窗**就生效；只有首次装新版本需要重启一次。

## 使用要求

- ColorOS 16 / 17（OPPO、一加、realme），真我 GT5 / GT8 Pro 上实测
- Root + LSPosed
- 作用域需勾选：**「系统框架」**（android）、**「系统界面」**（com.android.systemui）
- 想用侧边栏功能再勾 **「智能侧边栏」**（com.coloros.smartsidebar）
- 勾选后重启手机一次

## 构建

```bash
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/freewindow-release.apk
```

实现细节、日志排查与已知问题见 [docs/LIVE-DOCK.md](docs/LIVE-DOCK.md)；更新记录见 [CHANGELOG.md](CHANGELOG.md)。

## 致谢

本项目的实现参考了以下项目的思路与公开资料，在此一并感谢：

- **ColorOS / realme UI 系统代码**：坞窗、幻影窗口、侧边栏相关接口均以系统自身实现为依据（反编译研究，不随本项目分发）；
- **[FlymeFreeform（Flyme 小窗）](https://github.com/mangi/flymefreeform)**（GPL-3.0）：悬浮球显隐、后台保活开关的对齐参考；
- **[LuckyTool](https://github.com/luckyzyx/LuckyTool)**（GPL-3.0）：ColorOS 17 幻影窗口 / 后台挂机路径的参考；
- **[NeXtep 格窗工作台](https://github.com/yishisanren/NeXtep)**（Apache-2.0，fork 自 [lujinxin/NeXtep](https://github.com/lujinxin/NeXtep)）：虚拟显示器与实时预览方案的参考；
- **[libxposed API](https://github.com/libxposed/api)**：Xposed 框架接口。

## 免责声明

**本项目完全免费，无需任何付费即可获取使用。**

禁止任何形式的二次开发收费行为，包括但不限于：转售、付费订阅、植入广告获取收益等商业牟利手段。

本项目仅供学习与研究用途，请勿用于违反设备使用协议或当地法律法规的场景。
