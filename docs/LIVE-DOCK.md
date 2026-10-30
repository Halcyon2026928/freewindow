# 坞窗与实时画面 实现说明

## 一句话

坞窗搬到了 **SystemUI** 里显示；应用在 ColorOS 的**幻影窗口**上后台保活；卡片画面是**镜像任务图层**得来的，静态应用则直接显示一张截图。

## 谁干什么

| 进程 | 职责 |
|---|---|
| `system_server` | 拦截悬浮球点击；跨进程发指令；后台保活（开/关幻影显示器）；把任务图层镜像/拆镜像；给应用单独控帧；恢复小窗 |
| `com.android.systemui` | 画坞窗窗口（`TYPE_PHONE`）、卡片、滚动与点击；把卡片的 `SurfaceControl` 递给 system_server |
| `com.coloros.smartsidebar` | 解除编辑模式 20 个应用的数量限制（独立入口 `SidebarHook`） |

## 数据流

```
点悬浮球
  ├─ system_server：读取悬浮球应用列表
  ├─ 每个应用：有没有幻影显示器？→ 有就给显示 id
  ├─ 每个应用：用户勾选过吗？→ 动态 / 静态
  ├─ 静态：从任务快照缓存压一张 JPEG 一起发过去
  └─ 广播 SHOW_DOCK → SystemUI

SystemUI 建窗口、建卡片
  ├─ 静态卡：显示截图（SurfaceView 隐藏）
  └─ 动态卡：把卡片 SurfaceView 的 SurfaceControl 发回 system_server
        └─ system_server：mirrorSurface(任务图层) → reparent 到卡片图层
                         + 按比例缩放/裁剪 + 圆角 + 60 帧

关坞窗 / 点卡片
  ├─ SystemUI 先撤窗口（卡片与坞窗同帧消失）
  └─ 再发 CLOSED → system_server 释放镜像、交还帧率、恢复小窗或清后台
```

## 几个关键决定

**为什么镜像而不是搬任务**
把任务搬到自建虚拟显示器虽然能出画面，但会让 ColorOS 丢掉这个任务的 `FloatHandleInfo`，悬浮球从此回不去。镜像是只读的，球的状态完全不受影响。

**为什么静态应用只给截图**
静态应用被收进球里后本来就不再重绘，硬挂镜像只会黑一会儿；截图打开即有，也省电。

**为什么判定只认用户勾选**
早先用过 `AudioManager.isMusicActive()` 这类全局信号，结果一个应用放视频会把所有卡片都判成动态（计算器跟着黑屏）。现在只认设置里勾选的应用，且判定结果按任务缓存。

**为什么点卡片要回调官方方法**
我们自己拼参数调 `startActivityByFloatInfo` 恢复出来的是通用浮窗（没有小窗标题栏）。官方点球走的是 `FloatHandleView.startFlexibleFreeformFromHandleView`，它会自己算好 `launchBounds/scale/ratio`，所以恢复时直接回调它，并用 `sRestoring` 标志放行一次。

## 踩过的坑

| 现象 | 原因 | 处理 |
|---|---|---|
| system_server 软重启 | 广播接收器在 main 线程调了 `hide/showFloatHandleView`，内部弹簧动画绑定调用线程，导致在 main 上 `requestLayout` | 所有悬浮球操作 `post` 回它自己的线程（`sHandleHandler`），拿不到线程就放弃动作 |
| 幻影显示器越开越多、应用被反复拉起 | ColorOS 一次最小化会多次调 `startMinimize`，每次都会新建显示器 | 按任务去重 + `releaseVirtualDisplayDevice` 按 id 释放 |
| 卡片黑一会儿 / 偶尔挖空透桌面 | 镜像出帧前 `SurfaceView` 挖的洞是空的 | 已接受（偶现），静态卡则直接隐藏 SurfaceView |
| 静态卡有灰直角 | `SurfaceView` 自带直角背景层 + 截图没做 outline 裁剪 | 静态卡隐藏 SurfaceView；截图层单独 `clipToOutline` |
| 卡片之间互相影响 | 判定用了全局音频状态 | 改为按应用独立判定并缓存 |
| 列表项没有间距 | `ListView` 忽略 item 根布局的 margin | 用外层 padding 做间距 |
| 设置页进得慢 | `onCreate` 同步枚举全部应用 + 解码图标 | 后台线程枚举 + 图标懒加载 + 转圈等待 |

## 已知限制

- 加载瞬间偶尔能看到「挖空透桌面」（`SurfaceView` 特性，静态卡不受影响）；
- 动态卡是镜像，源应用的圆角/阴影不会带进卡片，统一由图层圆角处理；
- 实时预览的数量受 `MAX_LIVE = 4` 限制，超出的卡片保持截图。

## 日志

```
adb logcat -s freewindow-hook freewindow-dock freewindow-mirror freewindow-hangup freewindow-sidebar
```

| 关键字 | 含义 |
|---|---|
| `dynamic? taskId=… dynamic=true/false` | 这张卡走了实时还是截图 |
| `mirror attached key=… scale=…` | 镜像挂上了，带源/目标尺寸与缩放比例 |
| `frame rate 60.0 pkg=…` | 给该应用顶到 60 帧 |
| `released mirage displayId=…` | 释放了后台保活的虚拟显示器 |
| `restore via official handle path` | 走官方路径恢复成自由小窗 |
| `[freewindow-sidebar] … hooked` | 侧边栏数量限制已解除 |
