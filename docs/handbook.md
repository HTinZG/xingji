# 行迹 · 项目手册

> 最后更新：2026-11-16
> 这份文档写给「三个月后的自己」——那时你会忘记为什么某个版本号被钉死、为什么某个开关不能开。
> 每个坑都记了**现象 / 原因 / 解法**，照着查就能少走一遍弯路。

---

## 0. 这是什么

一个**纯本地**的 Android 出行轨迹记录 App。

- 包名 `com.llyytt.path`，应用名「行迹」
- 只给自己和家人用，不上架、没有服务器、没有账号
- 数据全部在手机本地，卸载即清除

设计原则：**能不联网就不联网，能不存就不存，存了就要能删干净。**

---

## 1. 现在能做什么

| 功能 | 状态 | 说明 |
|---|---|---|
| 手动开始 / 暂停 / 结束记录 | ✅ | 锁屏后靠前台服务继续记录 |
| 轨迹按速度或交通方式着色 | ✅ | 可在详情页切换 |
| 行程列表 | ✅ | 显示距离、时长、点数、断点数 |
| 行程详情 | ✅ | 地图 + 统计 + 滑块回放 |
| 自动分段（走/骑/车/停留） | ✅ | 按平滑速度和停顿判定 |
| 手动修正（改方式/拆分/合并） | ✅ | 每次操作一个事务，失败整体回滚 |
| 删除行程 | ✅ | 详情页底部，级联删除子表 |
| 离线地图 | ⚠️ 仅缓存 | 高德 SDK 自带缓存，不提供离线包 |
| AI 聊天 | ✅ | 用户自带 API Key，OpenAI 兼容 |
| AI 配置查看 / 清除 | ✅ | 设置页显示已存配置，可一键清除 |
| 导出 GPX 1.1 / KML 2.2 | ✅ | WGS84，可直接拖进 Google Earth |
| 分享图 | ✅ | 只含地图 + 统计带，不截屏 |
| 隐私说明页 | ✅ | 七节，写清楚数据去哪了 |

### 明确不做（不是忘了，是想清楚了不做）

- 账号体系 / 云端同步 / 社交
- 多轮工具调用、函数调用、流式输出
- AI 自动生成行程标题
- 离线地图包下载（高德这块是收费服务）
- 自定义 TileProvider
- 轨迹点级的手动编辑（只做段级）

---

## 2. 技术栈与版本（都验证过能一起工作）

| 组件 | 版本 | 为什么是这个版本 |
|---|---|---|
| Kotlin | 2.4.21 | 与 Compose 插件配套 |
| Android Gradle Plugin | 8.13.2 | 支持 compileSdk 36 的最高稳定线 |
| Gradle | 8.14.3 | AGP 8.13 要求 ≥ 8.13 |
| JDK | 21.0.2 | AGP 8.13 要求 17+，21 更稳 |
| compileSdk / targetSdk | 36 | 当前真机是 Android 16 |
| minSdk | 26 | 覆盖家里所有设备 |
| Compose BOM | 2025.06.01 | 再新的要求 AGP 9.1 + compileSdk 37 |
| Room | 2.8.5 | 配 kapt，见坑 #7 |
| OkHttp | 4.12.0 | 只用来发 AI 请求 |
| DataStore | 1.1.7 | 存 AI 配置（含加密 Key） |
| 高德 3D 地图 | 11.3.100 | jar + .so 形式，非 AAR |

> ⚠️ **升级时最重要的一条**：不要盲目升级 AndroidX / Compose BOM。
> 2026 年的最新版本要求 AGP 9.1 + compileSdk 37，会把整条工具链顶到未验证的组合上。
> 升级前先确认 AGP 版本要求。

---

## 3. 代码地图

```
app/src/main/java/com/llyytt/path/
├── MainActivity.kt            页面路由（sealed interface Screen）
├── XjApp.kt                   唯一组装点：数据库、仓库、地图引擎都在这
├── core/
│   ├── geo/                   坐标、距离、包围盒、折线抽稀与命中测试
│   ├── rules/                 纯算法，全部可单测
│   │   ├── Tuning.kt          ★ 所有阈值都在这，调参只改这一个文件
│   │   ├── Segmenter.kt       自动分段
│   │   ├── SpeedDisplay.kt    瞬时速度取样 + 平滑 + 静止判定
│   │   ├── TripInvariants.kt  分段/暂停的不变量校验
│   │   └── TripStats*.kt      统计计算
│   ├── export/                GPX/KML 生成、分享图合成
│   └── format/                Fmt（距离、时长、速度的格式化）
├── data/
│   ├── db/                    Room 实体、DAO、数据库（version 3）
│   ├── repo/                  录音、行程、分段、AI 聊天四个仓库
│   └── ai/                    Key 加密、设置存储、HTTP 客户端、上下文构造
├── map/                       MapEngine / MapController 抽象 + 高德实现
├── service/                   前台记录服务 + 状态持有者
└── feature/                   Compose 页面：record/trips/settings/ai/offline/privacy
```

### 架构上的五条硬规矩

| 编号 | 规矩 | 为什么 |
|---|---|---|
| D1 | **数据库只存 WGS84** | 所有地图引擎在渲染时自己转 GCJ-02。如果库里存了转换后的坐标，换引擎、导出 GPX 全都会偏 |
| D2 | MapEngine 只暴露跨引擎能力 | 避免被高德 API 绑死，将来换 MapLibre 不用重写业务 |
| D3 | LocationSource 独立于 MapEngine | 地图加载失败不能影响记录 |
| D4 | 高德实现在 `XjApp` 里唯一引用 | 换引擎只改一处 |
| D5 | Key 用 Keystore、导出用 SAF、分享用 FileProvider | 不申请存储权限，不把文件写到公共目录 |

---

## 4. 构建、签名、安装

### 4.1 环境准备

```powershell
$tc = "C:\Users\27603\Downloads\path\_toolchain"
$env:JAVA_HOME = "$tc\jdk\jdk-21.0.2"
$env:GRADLE_USER_HOME = "C:\Users\27603\Downloads\path\.gradle-home"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH
```

### 4.2 调试包

```powershell
& "$tc\gradle-8.14.3\bin\gradle.bat" -p "C:\Users\27603\Downloads\path\Xingji" `
    assembleDebug testDebugUnitTest --no-daemon --no-watch-fs
```

铁律：**每次构建都跑 `testDebugUnitTest`**。65 个单测能挡住绝大多数低级错误。

### 4.3 正式包

前提（一次性配置，已完成）：

1. `local.properties` 里有四行（**这个文件不进 git**）：
   ```properties
   xingji.release.storeFile=D:/AndroidKeys/xingji-release.jks
   xingji.release.keyAlias=xingji
   xingji.release.storePassword=<密码>
   xingji.release.keyPassword=<密码>
   ```
2. 用户级环境变量 `XINGJI_AMAP_KEY` 存高德 Key（**绝不写进命令、绝不进文件**）

```powershell
$env:AMAP_KEY = [Environment]::GetEnvironmentVariable('XINGJI_AMAP_KEY','User')
& "$tc\gradle-8.14.3\bin\gradle.bat" -p "...\Xingji" `
    :app:assembleRelease --no-daemon --no-watch-fs
```

### 4.4 验签（重要，别用错工具）

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\apksigner.bat" `
    verify --print-certs --verbose app\build\outputs\apk\release\app-release.apk
```

期望结果：

```
Verified using v2 scheme (APK Signature Scheme v2): true
Signer #1 certificate DN: CN=Xingji, OU=Personal, O=Personal, L=Beijing, ST=Beijing, C=CN
Signer #1 certificate SHA-1 digest: a441adbaa6dfdef1a5b144f4a362541db83191de
```

> 🚫 **不要用 `keytool -printcert -jarfile`** —— 它只认老式 JAR 签名，
> 而这包是 v2 签名，它会告诉你"未签名的 jar 文件"，白白吓自己一跳。

### 4.5 16KB 页对齐检查

```powershell
.\tools\Check-16Kb.ps1 .\app\build\outputs\apk\release\app-release.apk -All
```

Android 15+ 部分设备用 16KB 内存页，`.so` 必须按 16KB 对齐，否则一加载就崩，且无法在真机补救。

### 4.6 安装

```powershell
adb install -r app\build\outputs\apk\release\app-release.apk
```

⚠️ **MIUI 会在卸载重装后自动恢复应用数据**。想要真正的干净状态：

```powershell
adb shell pm clear com.llyytt.path
```

---

## 5. 高德 Key 与 SHA1（最容易出错的地方）

### 三样东西必须同时对上

| 项 | 值 |
|---|---|
| Key | 高德控制台生成的那串 |
| 发布版 SHA1 | `A4:41:AD:BA:A6:DF:DE:F1:A5:B1:44:F4:A3:62:54:1D:B8:31:91:DE` |
| 包名 | `com.llyytt.path` |

任何一项不对，日志里都会出现 `authErrLog`，地图可能白屏或功能异常。

### 怎么抓鉴权日志

```powershell
adb logcat -c
adb shell am start -n com.llyytt.path/.MainActivity
# 打开任意行程详情（地图会加载）
adb logcat -d | findstr /i "authErrLog"
```

**没有输出 = 鉴权通过。** 有输出就看 `json` 那一行的 `info` 字段：

| info | 含义 |
|---|---|
| `INVALID_USER_KEY` | Key 本身无效（被删、被吊销、复制时带了多余字符） |
| `INVALID_USER_SCODE` | SHA1 或包名不匹配 |
| `USERKEY_PLAT_NOMATCH` | 同一 Key 被用在别的包名下 |

### 调试版和发布版是两个 SHA1

调试证书：`92:BF:80:9F:37:A1:1F:D9:7B:59:90:0C:6B:FB:3F:88:B8:EF:2A:81`
发布证书：`A4:41:AD:...`（见上）

两个都要填进同一个 Key 里，否则会出现"调试能显示地图、正式包白屏"。

---

## 6. 升级发版的完整流程

以后每次改完代码，照这个顺序走：

```
① 改版本号
   app/build.gradle.kts:
     versionCode 1 → 2      （每次发布必须 +1，否则装不上）
     versionName "0.2.0" → "0.2.1"

② 跑单测
   gradlew assembleDebug testDebugUnitTest
   → 必须 0 失败

③ 真机自测
   adb install -r app-debug.apk
   跑一遍受影响的页面

④ 打正式包
   确认 local.properties 有密码、XINGJI_AMAP_KEY 环境变量存在
   gradlew :app:assembleRelease

⑤ 验签 + 16KB
   apksigner verify --print-certs
   .\tools\Check-16Kb.ps1 <apk> -All

⑥ 装到手机
   adb install -r app-release.apk

⑦ 抓一次鉴权日志
   adb logcat -c → 打开地图 → 确认无 authErrLog
```

---

## 7. 踩坑记录

按「现象 → 原因 → 解法」记。**这些坑我都踩过一遍，你以后升级可能再遇到。**

### 构建环境

**#1 构建报 `Failed to find Build Tools revision 35.0.0`**
原因：AGP 默认要 35.0.0，本机只装了 36.0.0。
解法：`buildToolsVersion = "36.0.0"` 显式钉死。

**#2 报 `Couldn't open current thread, error = 5`**
原因：Gradle 的 VFS 文件监听在受限目录上没权限。
解法：`org.gradle.vfs.watch=false` + 命令行加 `--no-watch-fs`。

**#3 升级 AndroidX 后要求 AGP 9.1 / compileSdk 37**
原因：2026 年的新库把门槛提高了。
解法：版本全部钉在 AGP 8.13 兼容线上，参考第 2 节的表。

**#4 Kotlin 编译报 `AccessDeniedException ... kotlin-daemon...tmp`**
原因：Kotlin 守护进程往受限制的临时目录写文件。
解法：`kotlin.compiler.execution.strategy=in-process` + `java.io.tmpdir` 指向可写目录。

**#5 Gradle Wrapper 报 `Test of distribution url failed`**
原因：官方下载地址本机不可达。
解法：wrapper 指向腾讯镜像。

**#6 KAPT 报 `Metadata version 2.4.0, max supported 2.2.0`**
原因：Room 的注解处理器自带的 metadata 读取库太旧。
解法：`kapt("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.21")` 顶到同版本。
（KSP 暂不可用——2.4.21 没有对应的 KSP 发布。）

### 高德地图

**#7 `CoordinateConverter` 返回类型不匹配**
原因：`coord()` 只是设置参数，不执行转换。
解法：链式末尾必须 `.convert()`。

**#8 折线点击监听器不触发**
原因：高德的 `PolylineOptions` 没有 `clickable` 选项。
解法：自己做命中测试——把坐标投影到屏幕像素，算点到线段的距离，48px 容差。

**#9 R8 报 `Missing class com.amap.ams.gnss.GnssSoftLocator`**
原因：高德 jar 的定位模块引用了没随包发布的类，R8 当**错误**处理（不是警告）。
解法：`-dontwarn com.amap.ams.**` + `-dontwarn com.amap.location.**`。

**#10 地图白屏**
排查顺序：① 是白屏还是闪退（闪退多半 ABI 不对）→ ② 抓 `authErrLog` 看 info → ③ 核对 SHA1 → ④ 核对包名 → ⑤ 确认 Key 真的进了包（`aapt2 dump xmltree`）。

### 数据与业务

**#11 分段操作后数据处于半拆状态**
原因：拆分的三步（改前段、顺移 seq、插后段）如果中途失败，库就烂了。
解法：全部包在 `db.withTransaction {}` 里，最后跑不变量校验，失败抛异常让事务回滚。
**验证方式**：人为制造一个必然违规的状态，执行操作，确认库里数据与操作前逐字段一致。

**#12 分段校验静默失败**
原因：早期版本只 `Log.w` 不抛异常。
解法：`validateOrThrow()` 直接抛 `IllegalStateException`，UI 弹"操作没有生效"。

**#13 自动分段把整条 5 个点的短行程判成一个停留**
原因：点太少，平滑后速度全部低于阈值，连续时长又超过 20 秒。
不是 bug，是样本问题。真实轨迹（几百点、速度有变化）才有意义。

**#14 手动改的方式被"自动分段"覆盖了**
原因：`autoSegment` 会删掉全部旧段重建，`manuallyEdited` 标记存了但没用起来。
现状：已知限制，记在技术债里。

**#15 拆分后 `seq` 顺移被唯一索引挡住**
原因：早期 `trip_segment` 上建的是 `(tripId, seq)` 唯一索引。
解法：改成 `(tripId, startPointSeq)` 唯一 + `(tripId, seq)` 普通索引。

**#16 速度显示在静止时来回跳**
原因：中位数平滑会让"进入静止"和"离开静止"都延迟。
解法：非对称判定——低于阈值持续 5 秒才进静止，任一样本高于阈值立刻退出。

### 安全与隐私

**#17 DataStore 文件损坏导致 App 直接崩**
原因：`InvalidProtocolBufferException` 没人接，进程挂掉。
解法：`ReplaceFileCorruptionHandler { emptyPreferences() }`，损坏时回退空配置。

**#18 明文 HTTP 请求被系统拦截**
现象：`CLEARTEXT communication not permitted by network security policy`。
原因：targetSdk 28+ 默认禁止明文。
解法：`network_security_config.xml` 放开（因为 BaseURL 由用户填，且保存 http 时强制二次确认）。

**#19 `keytool -printcert -jarfile` 说包没签名**
原因：它只认 v1（JAR）签名，而 minSdk 26 的包默认只打 v2。
解法：用 `apksigner verify --print-certs`。

**#20 PKCS12 keystore 报"密码不正确"**
原因：PKCS12 只有一个密码，`storePassword` 和 `keyPassword` 填得不一样就打不开私钥。
解法：两个字段填同一个值（代码里已做兜底：`keyPassword` 没填就复用 `storePassword`）。

**#21 高德报 `INVALID_USER_KEY` 但 Key 看起来是对的**
原因：环境变量里是 `<Key>`——**带尖括号**，长度 34 而不是 32。
解法：环境变量只放 Key 原文，不要尖括号、不要引号。

### 界面

**#22 状态栏和内容重叠**
原因：edge-to-edge 下内容会画到状态栏底下。
解法：页面根布局加 `statusBarsPadding()`。

**#23 键盘弹出后"保存"按钮被挡住**
原因：设置页没处理 IME inset。
解法：滚动容器加 `imePadding()`。

**#24 分享图会带上状态栏 / 别的页面**
原因：整屏截图再裁剪，永远有裁漏的风险。
解法：**不截屏**——只取地图控件的 `snapshot()`，再用 Canvas 在下方拼统计带。

### 自动化测试（我用 adb 操作手机时踩的）

**#25 中文输入法把 `:` 变成全角 `：`、把 `http` 自动补成 `https`**
解法：`adb shell ime disable <输入法id>`，禁用后 `input text` 走按键注入，不会触发联想。

**#26 `input text 'How many km'` 只输入了 `How`**
原因：`input text` 把空格当参数分隔。
解法：空格写成 `%s`。

**#27 命令行坐标点不准，点击总落空**
解法：`uiautomator dump` 拿到控件的精确 `bounds`，取中心点再点，不要靠肉眼估。

---

## 8. 测试清单状态

> 完整版见 `docs/step9-test-checklist.md`

### 阻塞项

| 项 | 状态 |
|---|---|
| 澎湃 OS 五设置后户外 1 小时不中断 | ⬜ **待你出门实测** |
| 进程被杀后恢复 | ✅ 已验证（自动结束旧行程并提示） |
| 拆分中途校验失败全回滚 | ✅ 已验证（3 段数据逐字段一致） |

### 已验证的关键指标

| 项 | 结果 |
|---|---|
| 单测 | 65 个，0 失败 |
| 16KB 页对齐 | 4 个 .so 全部 16384 |
| 导出坐标偏移 | 0.055 米（纯 6 位小数舍入误差） |
| APK 体积 | 44.30 MB（其中高德 .so 占 32.9 MB） |
| 冷启动首次定位 | 8398 ms |
| 签名 | CN=Xingji，v2 scheme |

---

## 9. 已知限制与技术债

| 项 | 说明 | 优先级 |
|---|---|---|
| 户外长时记录未验证 | 唯一没绿的阻塞项 | 高 |
| `autoSegment` 覆盖手动修正 | 应跳过 `manuallyEdited=true` 的段 | 中 |
| 列表页 N+1 查询 | 每条行程单独查点、查段计数，50+ 行程会慢 | 中 |
| KAPT 拖慢构建 | 等 KSP 版本对齐后切换 | 低 |
| 停留时段内的点不进轨迹线 | 只留一个 `<wpt>`，符合 GPX 惯例但信息有损 | 低 |
| 分享图只有竖版 | 跟随屏幕宽度 | 低 |
| 无"导出全部行程" | 只能一条一条导 | 低 |

---

## 10. 商业化与合规（别忘）

**现状**：侧载自用，不触发高德商用授权。

**以下情况必须重新评估**：

| 场景 | 是否需要授权 |
|---|---|
| 只装自己和家人手机 | 不需要 |
| 上架应用商店（哪怕免费） | 需要 |
| 收费 / 内购 / 广告 | 需要 |
| 公司内部使用 | 需要 |

费用参考：**约 5 万元/年**（以高德官方报价为准）。

替代方案：换 MapLibre + 自建瓦片 / OSM。架构上已经留好位置（`MapEngine` 接口），
但会失去高德的中文 POI 和路网精度。

---

## 11. 给你的三条经验

### ① 改阈值只改一个文件

所有可调参数都在 `core/rules/Tuning.kt`：采样间隔、精度过滤、分段速度阈值、
停留判定、间隙切分、平滑窗口。调参时不要散着改。

### ② 每次改动都要跑单测

`core/rules` 和 `core/export` 是纯 Kotlin，改完立刻能验证。
这一段写错了，UI 上很难看出来，但导出的 GPX 会偏、分段会错。

### ③ 先备份两样东西

- `D:\AndroidKeys\xingji-release.jks`（丢了就永远无法给已装用户升级）
- `local.properties` 里的两个密码

**keystore 丢了 = 这个 App 的死期。** 换新 keystore 意味着 SHA1 变了、签名变了，
所有已装设备必须卸载重装，数据全丢。

---

## 12. 关键文件速查

| 我想改… | 去这个文件 |
|---|---|
| 分段/停留的阈值 | `core/rules/Tuning.kt` |
| 界面上显示的速度 | `core/rules/SpeedDisplay.kt` |
| 导出的格式 | `core/export/TrackExporter.kt` |
| 分享图长什么样 | `core/export/ShareComposer.kt` |
| AI 的提示词 | `data/ai/TripContextBuilder.kt` |
| AI 请求怎么发 | `data/ai/AiClient.kt` |
| 数据库表结构 | `data/db/Entities.kt` |
| 页面跳转 | `MainActivity.kt` |
| 依赖版本 | `app/build.gradle.kts` |
| 混淆规则 | `app/proguard-rules.pro` |

---

*本文档随项目一起放在 `D:\Xingji\docs\handbook.md`，与源码同步更新。*

---

## 13. GitHub 仓库

**地址**：https://github.com/HTinZG/xingji （私有）

### 这台机器上的特殊配置

推送时踩过一次 Recv failure: Connection was reset——**git 不会自动使用系统代理**。
本仓库已把代理写进本地配置：

```powershell
cd D:\Xingji
git config http.proxy  http://127.0.0.1:7897
git config https.proxy http://127.0.0.1:7897
```

如果哪天换了代理软件或端口变了，改这两行即可（git config --unset 可去掉）。

凭据用 Git Credential Manager 存在 Windows 凭据管理器里，登录过一次就不用再登。
helper 的路径带空格，必须用 !"..." 包起来，否则 git 会把它当成 `git-credential-<名字>` 去找：

```powershell
git config credential.helper '!"/c/Program Files/Git/mingw64/bin/git-credential-manager.exe"'
```

### 以后怎么更新

```powershell
cd D:\Xingji
git add -A
git commit -m "这次改了什么"
git push
```

### 仓库里有什么

137 个文件、约 43 MB：

| 目录 | 内容 |
|---|---|
| `app/` | 源码 67 个（含高德 SDK 的 jar 与 .so） |
| `docs/` | 文档 60 个（含截图、导出样例） |
| `tools/` | 16KB 检查脚本 |
| 根目录 | Gradle 配置、README、.gitignore |

### 不会进仓库的东西

`local.properties`（含签名密码）、`keystore/`、`*.jks`、`artifacts/`（APK）、
`build/`、`.gradle/`。提交前用 `git ls-files` 核对过，也全文扫过密钥。

### 备份提醒

GitHub 只备份了**代码和文档**。还有两样东西不在里面，必须单独备份：

1. `D:\AndroidKeys\xingji-release.jks`（签名证书，丢了就无法给已装用户升级）
2. 证书密码（记在离线的地方）

> GitHub 是公开托管的私有仓库，不要把它当成唯一备份。
> 建议 keystore 另外存一份到 U 盘或加密网盘。
