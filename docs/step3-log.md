# 第 3 步执行日志

> 分两批交付。
> 批次 1：Room + LocationSource + 隐私门禁。
> 批次 2：前台服务 + 权限链 + 保活 + 断点恢复 + 冷启动实测。

---

## 批次 1：已完成

### 1.1 隐私门禁（硬门槛）

| 约束 | 落地方式 |
|---|---|
| 同意前不能创建 MapView | `MapEngine` 改为**懒创建**：`XjApp.ensureMapEngine()` 里先 `check(settings.privacyAccepted)`，未同意直接抛异常 |
| 同意前不能初始化高德 SDK | `AMapEngine.initSdk()` 从 `Application.onCreate` 移出，只在 `ensureMapEngine()` 里调 |
| 首启必须看到说明页 | `MainActivity` 先判断 `settings.privacyAccepted`，未同意只渲染 `PrivacyGateScreen`，不构建地图页 |
| 拒绝的出口 | 「不同意，退出」→ `finish()`，不留后门 |

隐私页文案包含三类承诺（轨迹只存本机 / AI Key 加密 / 不上传服务器），
并按合规要求**单独披露高德 SDK 收集设备标识与位置信息**。

### 1.2 Room

四张表，版本 1，`exportSchema = true`，schema 已导出：

```
app/schemas/com.llyytt.path.data.db.AppDatabase/1.json    9362 bytes
```

| 表 | 关键字段 | 说明 |
|---|---|---|
| `trip` | startedAt / endedAt / status / distanceMeters / activeMillis | 行程。status 非 FINISHED 即「未结束」，断点恢复靠它 |
| `track_point` | tripId + seq（唯一索引）/ lat / lng / speedMps / accuracyM / startsNewSegment | 轨迹点，坐标一律 WGS84 |
| `trip_segment` | mode / startPointSeq / endPointSeq / manuallyEdited | 分段结果，原始点永不修改 |
| `stay_point` | lat / lng / radiusMeters / arriveAt / leaveAt | 停留点 |

`startsNewSegment` 是为「暂停 / 丢信号」准备的：画线时在它前面断开，
绝不拿直线把中断两端连起来伪造轨迹。

### 1.3 LocationSource

```
location/LocationSource.kt     接口 + LocationFix（坐标强制 WGS84）
location/GnssLocationSource.kt 系统 GNSS 实现，自适应采样
```

自适应间隔：≥5 m/s → 1s；1.5~5 m/s → 2s；静止 → 5s；首轮未知 → 2s。

### 1.4 几何工具抽成 core/geo

`core/geo/ScreenHitTest.kt`：纯 Kotlin、零 Android 依赖。

- `ScreenPoint` 值对象
- `distanceToSegment` / `distanceToPolyline` / `nearestPolyline`（阈值 48px）

`AMapController` 只负责把经纬度投影成屏幕点，几何判定全部走这个工具类。
第 4/5 步点选分段直接复用。

### 1.5 单测（8/8 通过）

```
BUILD SUCCESSFUL
tests=8 failures=0 errors=0
  - 点在边中点也能命中折线
  - V形折线开口外的点不应命中
  - 超出线段两端时退化为端点距离
  - 离所有折线都远时返回负一
  - 退化线段不会除零
  - 垂直距离等于线段到点的垂距
  - 在多条折线里选中最近的那条
  - 点落在端点上时距离为零
```

> 中间发现一次「测试用例写错」：我最初把点放在 V 形折线开口外，距离 69px 判不中是对的，
> 是断言写错了。已修正用例并补了一条反向用例。

### 1.6 .gitignore 与签名位置

新增 `.gitignore`，包含 `keystore/`、`_tmp/`、`local.properties`、`*.jks`、`*.keystore`、
`build/`、`.gradle/`、`.kotlin/`、`_dl/`、`_toolchain/`、`*.log`。

签名位置核查：

| 文件 | 路径 | 是否在项目内 |
|---|---|---|
| 调试证书 | `D:\Xingji\keystore\xingji-debug.jks` | 是（刻意，SHA1 要稳定） |
| 发布证书 | `D:\AndroidKeys\xingji-release.jks` | **否** ✅ |

⚠️ `.gitignore` 忽略了 `keystore/`，所以调试证书不会进版本库。
如果哪天换电脑 clone 一份新仓库，调试 SHA1 会变、高德 Key 会失效。
**请把 `D:\Xingji\keystore\` 和 `D:\AndroidKeys\` 一起备份。**

### 1.7 构建踩的坑

| 报错 | 根因 | 修法 |
|---|---|---|
| `Provided Metadata instance has version 2.4.0, while maximum supported version is 2.2.0` | Room 2.7.2 的 kotlin-metadata 太旧，读不了 Kotlin 2.4 的元数据 | Room 升 2.8.5 |
| 同上，但 `maximum supported version is 2.3.0` | Room 2.8.5 仍只认到 2.3 | 按报错提示加 `kapt("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.21")` |
| KSP 无法使用 | KSP 最新只到 2.3.14，与 Kotlin 2.4.21 不配对 | 改用 KAPT |

最终：`Kotlin 2.4.21 + KAPT + Room 2.8.5 + kotlin-metadata-jvm 2.4.21`，`BUILD SUCCESSFUL`。

---

## 批次 1：验收结果（真机实测，4/4 通过）

设备：Redmi K80 至尊版，13:08~13:09 实机操作，`adb shell pm clear` 模拟全新安装。

| # | 验收点 | 结果 | 证据 |
|---|---|---|---|
| 1 | 首启只出隐私页、零地图 | ✅ 整个页面没有任何 MapView，文案含三条承诺 + 高德 SDK 披露 | `screenshots/07-privacy-gate.png` |
| 2 | 同意后才出地图 | ✅ 点「同意并继续」后地图正常渲染，测试折线在 | `screenshots/08-after-agree-map.png` |
| 3 | 二次启动直进地图 | ✅ force-stop 后重启，不再弹隐私页 | `screenshots/09-relaunch-straight-to-map.png` |
| 4 | 拒绝即退出 | ✅ 点「不同意，退出」后 Activity 结束、回到系统桌面/设置；`shared_prefs` 里**没有**写入同意标记 | 见下方命令输出 |

同意标记落盘验证（第 2 条之后）：

```xml
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="privacy_accepted" value="true" />
</map>
```

拒绝之后：

```
cat: /data/data/com.llyytt.path/shared_prefs/xingji_settings.xml: No such file or directory
```

即：拒绝不会留下任何「已同意」痕迹。

**代码级保证**：`JApp.ensureMapEngine()` 里 `check(settings.privacyAccepted)`，
就算将来某个页面绕过了 UI 判断提前调 `mapEngine()`，会直接抛异常 —— 不会静默白屏，也不会悄悄同意。

---

## 批次 1：待验收入口（你可以自己复现）

1. `设置 → 应用 → 行迹 → 存储 → 清除数据`
2. 打开 App → 应只看到隐私说明页，**看不到地图**
3. 点「同意并继续」→ 地图出现
4. 杀掉 App 再打开 → 直接进地图，不再问
5. 再清一次数据 → 打开 → 点「不同意，退出」→ App 应直接关闭


---

# 第 3 步 · 批次 2

## 2.1 已落地的改动

| 项 | 实现 |
|---|---|
| 前台服务 | `RecordingService`，`foregroundServiceType="location"`，`stopWithTask=false` |
| 常驻通知 | 通道 `recording`（IMPORTANCE_LOW），实时显示 距离 · 时长 · 点数 |
| 单协程串行写 | 定位回调 → `Channel` → 单个消费者入库，保证 seq 严格递增 |
| 暂停/恢复/结束 | 事务内同时收敛 `trip_pause`，维护状态不变量 |
| 断点恢复 | `START_STICKY` + intent 为空时走 `recover()`，从 DB 接回未结束行程 |
| 防并行 | `startNewTrip()` 在事务里先查未结束行程，有就返回 `AlreadyRunning`，UI 只给「继续/结束」 |
| 距离 | 不缓存，`distanceMeters(tripId)` 从点实时算，断点处不加线段 |
| 时长 | `DurationCalc`，三种状态分别处理（见 2.2） |
| 缓存点过滤 | 点的 age > 10 秒视为缓存点直接丢（防跨城假距离） |
| 精度过滤 | accuracy > 100m 丢；speed > 60m/s 丢 |

## 2.2 activeMillis 三种状态

| 状态 | end 取谁 | 未闭合 pause 的 resumedAt 取谁 |
|---|---|---|
| RECORDING | now | 不允许存在未闭合 pause（事务保证） |
| PAUSED | now | now |
| FINISHED | endedAt | endedAt（脏数据兜底） |

单测 6 个用例：正常、扣暂停、暂停中、已结束、多次暂停、脏数据不为负。全部通过。

## 2.3 前台服务权限链（按 Android 版本）

| 版本 | 要求 | 本 App 的处理 |
|---|---|---|
| 8.0~12（26~32） | 前台服务 + 常驻通知 | `startForeground(id, notif)` |
| 13（33） | `POST_NOTIFICATIONS` 运行时权限，否则通知被静默丢弃 | 开始记录前一并申请 |
| 14（34） | 必须声明 `foregroundServiceType="location"` **且**持有 `FOREGROUND_SERVICE_LOCATION`；启动时必须在前台且有 while-in-use 定位权限 | Activity 内点按钮启动；权限先申请；Manifest 同时声明两个权限 |
| 15（35） | 禁止从 `BOOT_COMPLETED` 启动部分类型 FGS（dataSync/mediaProcessing/camera） | 本 App 不做开机自启，不受影响 |
| 16（36） | 沿用 14/15 的前台服务限制；JobScheduler 配额调整 | 不使用 JobScheduler / WorkManager |

关键结论：**只要前台服务是在可见 Activity 里由用户点击启动的，Android 14/15/16 的限制都不会触发。**
会踩坑的是"从后台或开机广播里偷偷启动"，我们没做。

## 2.4 澎湃 OS（HyperOS）必须手动开的项

adb 改不了，需要你手动点（朋友装完也要自己点一遍）：

| # | 路径 | 设为 | 不开会怎样 |
|---|---|---|---|
| 1 | 设置 → 应用设置 → 应用管理 → 行迹 → **自启动** | 打开 | 划掉后台后可能被限制唤醒 |
| 2 | 应用信息 → **省电策略** | **无限制** | 息屏一段时间后被冻结，轨迹断 |
| 3 | 应用信息 → 通知管理 → **允许通知** + 锁屏显示 | 打开 | 前台服务通知看不到，容易被误杀 |
| 4 | 最近任务卡片 → **下拉锁定** | 锁定 | 一键清理把记录一起清掉 |
| 5 | 手机管家 → 应用管理 → 权限 → 行迹 → 后台弹出界面 | 打开（可选） | 一般不影响记录 |

另外已通过 adb 打开「充电时不锁屏」：`svc power stayon true`（`stay_on_while_plugged_in=15`）。
插着 USB 时不会自动锁屏；拔线后恢复系统默认。

## 2.5 真机验证记录（室内，无卫星信号）

### 冷启动第 1 次：TIMEOUT（真实结果）

```
11-16 18:33:48.419 32360  7318 I XjRec : adopt trip=1 status=RECORDING seq=0 points=0 dist=0.0
11-16 18:34:18.427 32360 32360 W XjRec : location error: TIMEOUT
```

点「开始记录」后 **30 秒没有任何点入库**，服务按设计报 `TIMEOUT`。
这不是 bug：室内没有卫星信号。provider 是开着的（没报 `PROVIDER_DISABLED`），
权限也是给的（没报 `PERMISSION_DENIED`）—— 这正是加 `LocationError` 枚举的意义。

### 断点恢复：通过

强行 `am force-stop` 杀掉进程（服务一起没了）→ 重新打开 App：

截图显示弹窗「有一条没结束的行程 / 开始于 11-16 18:33，状态 记录中。要继续它，还是结束它？」
—— 进程被杀后能从数据库找回未结束行程，且只给「继续 / 结束它」两个出口。

点「继续」后服务重新以前台身份运行：

```
isForeground=true foregroundId=1001 types=0x00000008
```

`0x8` = `FOREGROUND_SERVICE_TYPE_LOCATION`。

### 暂停 / 结束 + 数据库不变量：通过

操作序列：继续 → 暂停 → 结束。拉库核对：

```
--- trip ---
{'id': 1, 'startedAt': 1794825228395, 'endedAt': 1794825378785, 'status': 'FINISHED'}

--- trip_pause ---
{'id': 1, 'tripId': 1, 'pausedAt': 1794825366814, 'resumedAt': 1794825378785}

--- counts ---
track_point: 0   trip_segment: 0   stay_point: 0

状态不变量: status=FINISHED | endedAt 非空 | open pauses=0  → PASS
```

结束服务后：

```
no service record (已停止)
```

### 防并行行程：通过

存在未结束行程时，点「开始记录」不会新建，而是再弹一次恢复对话框
（`startNewTrip()` 在事务里返回 `AlreadyRunning`）。
数据库里始终只有一条 `status != 'FINISHED'` 的记录。

## 2.6 尚未验证（需要室外）

| # | 项 | 为什么必须室外 |
|---|---|---|
| 1 | 冷启动首次定位耗时 | 室内拿不到卫星 |
| 2 | 锁屏后继续记录 | 需要真实移动产生点 |
| 3 | 点的写入 / 距离计算 | 同上 |

## 2.7 冷启动实测（室外，第一次有效）

设备重置流程：`am force-stop` → `cmd location set-location-enabled false` → 等 3 秒 → `true` → 打开 App → 点「开始记录」。

原文（`adb logcat -s XjRec:I`）：

```
11-16 19:05:32.023  6092 12166 I XjRec : adopt trip=3 status=RECORDING seq=0 points=0 dist=0.0
11-16 19:05:40.418  6092 12167 I XjRec : POINT_STORED seq=0 age=14ms acc=9.1
11-16 19:05:40.419  6092 12167 I XjRec : FIRST_FIX_STORED: 8398ms since start request
11-16 19:05:45.010  6092 12167 I XjRec : POINT_STORED seq=1 age=3ms acc=6.5
11-16 19:05:51.018  6092 12167 I XjRec : POINT_STORED seq=2 age=16ms acc=5.8
11-16 19:05:54.037  6092 12167 I XjRec : POINT_STORED seq=3 age=35ms acc=5.8
11-16 19:06:03.004  6092 12167 I XjRec : POINT_STORED seq=4 age=3ms acc=39.0
```

**结论：首次定位 8398ms（8.4 秒），低于 20 秒阈值，不需要补 `AmapLocationSource`。**
`age` 是定位点时间与当前时间的差，14ms / 3ms 说明拿到的是**实时点**，不是缓存点。

### 点与距离核对

```
seq=0 NEW lat=36.660403 lng=116.897778 acc=9.1  +0.0m
seq=1     lat=36.660442 lng=116.897875 acc=6.5  +9.6m
seq=2     lat=36.660485 lng=116.898275 acc=5.8  +36.0m
seq=3     lat=36.660542 lng=116.898435 acc=5.8  +15.6m
seq=4     lat=36.660777 lng=116.898828 acc=39.0 +43.7m
TOTAL distance = 105.0 m, duration = 22.6s
```

- `seq` 0..4 连续，`startsNewSegment` 只在第一个点为 true —— 符合设计。
- 距离 105m / 22.6s ≈ 4.6 m/s，偏高。原因很清楚：`seq=3 → seq=4` 跨度 43.7m，
  而该点 `acc=39m`。**GPS 漂移把距离吹大了**。当前阈值 `MAX_ACCURACY_M = 100` 太松，
  第 5 步做分段时会一并处理（按精度加权或对漂移点降权），本步先记录现象。

## 2.8 一个必须查清的异常：19 分钟零记录

数据库里的完整时间线：

| trip | 开始 | 结束 | 点数 |
|---|---|---|---|
| 1 | 18:33:48 | 18:36:18 | 0（室内测试，TIMEOUT） |
| 2 | **18:46:14** | **19:05:31** | **0** |
| 3 | 19:05:32 | 19:06:08 | 5 |

`logcat` 里 18:46:14 确实有前台服务启动记录：

```
18:46:14.860 I/ActivityManager: Background started FGS: Allowed [... act=com.llyytt.path.action.START ...]
```

但 18:46 ~ 19:05 之间**没有任何一条 `XjRec` 日志**，连 `adopt trip=2` 都没有。
进程 PID 6092 从头到尾没变（19:05 的 `adopt trip=3` 也是 6092）。

这说明当时 `onStartCommand` 里 `scope.launch { loadAndStart(...) }` 的协程**没有执行**，
不是"室外没信号"（没信号会打 `POINT_STORED` 之外的 `TIMEOUT`，而这里连定时器日志都没有）。

**怀疑：进程被澎湃 OS 冻结**（前台服务通知还在，系统侧 `AlarmScheduler-TIMEOUT` 每秒钟还在为
通知 id=1001 排队，说明"服务"这个概念在系统侧还活着，但应用侧协程没跑）。

验证办法：加心跳日志（每 10 秒一条），锁屏 3 分钟看心跳是否中断。见 2.9。

## 2.9 锁屏记录实测：通过

两组对照实验。

**A 组：App 退到后台、屏幕常亮 60 秒**

```
19:13:16.738 service created pid=18248
19:13:16.739 onStartCommand action=...START tripId=4 startId=1
19:13:16.748 adopt trip=4 status=RECORDING seq=0 points=0 dist=0.0
19:13:16.759 heartbeat points=0 dist=0 status=RECORDING
19:13:26.842 heartbeat ...
19:13:36.935 heartbeat ...
19:13:46.753 location error: TIMEOUT          ← 室内无星，30 秒按设计报错
19:13:47.026 heartbeat ...
... 直到 19:14:17 连续
```

**B 组：手动息屏、锁屏 180 秒**

```
19:14:27.467 heartbeat points=0 dist=0 status=RECORDING
19:14:57.706 heartbeat ...
19:15:07.808 heartbeat ...
19:15:08.636 POINT_STORED seq=0 age=46ms acc=6.9     ← 锁屏状态下拿到卫星
19:15:08.637 FIRST_FIX_STORED: 111894ms since start request
19:15:15.635 POINT_STORED seq=1 age=44ms acc=7.2
19:15:20.605 POINT_STORED seq=2 age=14ms acc=7.6
19:15:25.634 POINT_STORED seq=3 age=43ms acc=8.1
19:15:30.597 POINT_STORED seq=4 age=6ms acc=8.6
19:15:38 → 19:17:29 heartbeat ...（每 10 秒一条，一次没断）
```

**结论：锁屏不会冻结进程，GNSS 在息屏状态下照常出点、照常入库。**

⚠️ 注意 `FIRST_FIX_STORED: 111894ms` 是**室内**从开始到拿星的耗时，**不能算冷启动数据**。
室外冷启动是 8398ms（见 2.7）。两个数要分开看，别混。

## 2.10 对 2.8「19 分钟零记录」的修正

2.8 里我怀疑是「澎湃 OS 冻结进程」。**这个假设被 2.9 推翻了**——锁屏 3 分钟心跳一次没断。

那 19 分钟最可能是**服务启动路径没有走完**（例如 START intent 被投递到一个正在销毁的
service 实例上，`scope.launch` 落在一个已取消的作用域里，协程体不会执行，也不会报错）。

这个失败模式很隐蔽，所以我没有去编一个说法，而是加了三道防御，让它下次必然留痕：

| 防御 | 代码位置 | 作用 |
|---|---|---|
| `onStartCommand` 整体 try/catch | `RecordingService.onStartCommand` | 任何异常打 `onStartCommand 失败` ERROR |
| `loadAndStart` try/catch | `RecordingService.loadAndStart` | 加载失败打 `loadAndStart 失败` 并 stopSelf |
| 看门狗 | `onCreate` 里 5 秒后检查 | 没 adopt 就打死 `WATCHDOG: 5000ms 内没有 adopt` |

下次再出现，日志里一定有一条 ERROR，不用再靠推理。

### 顺带修的一个噪音问题

`updateNotification` 原本每秒都 `notify()`，导致系统 `AlarmScheduler-TIMEOUT` 每秒为
通知 id=1001 排一个 alarm（18:51 那段时间日志刷屏）。现在加了「文本没变就不重发」。

### 当前设备上的真实恢复场景

安装新包时安装流程会杀掉进程，所以 trip 4 现在是：

```
{'id': 4, 'startedAt': 1794827596731, 'endedAt': None, 'status': 'RECORDING'}
track_point: tripId=4, 5 个点
```

这正好是「进程被杀」的真实现场，下次打开 App 会弹恢复对话框。
