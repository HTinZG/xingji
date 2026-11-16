# 第 2 步执行日志

> 执行者：AI（DSH）。项目最终落地路径：`D:\Xingji`。
> 本文只记录**可复现的事实与命令输出**。不含任何 release 密码、高德 Key 明文。
> （工程里的调试证书密码 `android` 是 Android 调试证书的公开约定，不是机密。）

---

## 0. 环境结论（先纠正一个前提）

原以为「没装 Android Studio」，实际勘察结果相反：

| 组件 | 实际状态 |
|---|---|
| Android Studio | **已安装**，`C:\Program Files\Android\Android Studio` |
| 自带 JBR | **OpenJDK 25.0.4**（太新，Gradle 8 不支持，另装了 JDK 21） |
| Android SDK | `C:\Users\27603\AppData\Local\Android\Sdk` |
| platforms | android-36、android-36.1、android-37.0 |
| build-tools | 34.0.0、36.0.0、36.1.0、37.0.0 |
| platform-tools | adb 37.0.0 |
| cmdline-tools | 未安装（本次不需要） |
| 已连接设备 | **无**（`adb devices` 为空） |

补齐的工具链（放在 `D:\Xingji\.toolchain` 旁的工作目录，不装进系统）：

| 工具 | 版本 | 来源 |
|---|---|---|
| JDK | OpenJDK 21.0.2 | 华为云镜像 |
| Gradle | 8.14.3 | 腾讯云镜像（wrapper 也指向它） |

---

## 1. 高德 SDK 获取方式（与 Step 2 文档不同，必须记录）

原计划「下载 11.3.100 的 AAR」**行不通**，事实是：

1. 官方下载页 `https://lbs.amap.com/api/android-sdk/download/` 只提供一个直链：
   `https://a.amap.com/lbs/static/zip/AMap_Android_SDK_All.zip`（115 MB）。
2. 解压后是三个内层 zip，其中 `AMap3DMap_AMapSearch_AMapLocation.zip`（35 MB）才是 3D 地图开发包。
3. 再解压，内容为 **1 个 jar + 2 个 .so**，没有任何 `.aar`：

```
AMap3DMap_11.3.100_AMapSearch_9.8.1_AMapLocation_11.3.101_20260924.jar   11.51 MB
arm64-v8a/libAMapSDK_MAP_v11_3_100.so                                    32.90 MB
arm64-v8a/libapssdk.so                                                    0.82 MB
```

4. Maven Central 上的 `com.amap.api:3dmap-location-search` 也只有 `.jar`，没有 `.aar`。

**所以工程改成官方推荐的分放方式**：

```
app/libs/amap-sdk-11.3.100.jar
app/src/main/jniLibs/arm64-v8a/libAMapSDK_MAP_v11_3_100.so
app/src/main/jniLibs/arm64-v8a/libapssdk.so
```

---

## 2. 16KB 页对齐验证（闸门）—— 已通过

### 2.1 直接验 .so（编译前）

```
检查目标：...\so-arm64
共 2 个 native 库

Abi Library                     Align Verdict
--- -------                     ----- -------
?   libAMapSDK_MAP_v11_3_100.so 16384 通过 16KB
?   libapssdk.so                16384 通过 16KB

结论：全部通过 16 KB 页对齐，可以在这类设备上运行。
RESULT: PASS (all PT_LOAD p_align >= 16384)
```

### 2.2 验最终 APK

```
检查目标：...\app-debug.apk
共 3 个 native 库

Abi       Library                      Align Verdict
---       -------                      ----- -------
arm64-v8a libAMapSDK_MAP_v11_3_100.so  16384 通过 16KB
arm64-v8a libandroidx.graphics.path.so 16384 通过 16KB
arm64-v8a libapssdk.so                 16384 通过 16KB

结论：全部通过 16 KB 页对齐，可以在这类设备上运行。
RESULT: PASS (all PT_LOAD p_align >= 16384)
```

**结论：11.3.100 通过 16KB 校验，不需要回退到 11.2.x / 11.1.x。**

附带收获：官方这个包只有 `libAMapSDK_MAP` 和 `libapssdk` 两个 .so，
定位/搜索的代码并进了同一个 `libAMapSDK_MAP`，所以不存在「回退到 Maven 合包会额外引入定位/搜索 .so」的问题。

---

## 3. 构建记录（失败 4 次，均已修）

| # | 报错 | 根因 | 修法 |
|---|---|---|---|
| 1 | `Failed to find Build Tools revision 35.0.0` | AGP 8.13.2 默认找 35.0.0，本机只有 34/36/37 | `buildToolsVersion = "36.0.0"` |
| 1 | `Couldn't open current thread, error = 5` | 沙箱禁止 Gradle 文件监听 | `org.gradle.vfs.watch=false` + `--no-watch-fs` |
| 2 | 一批 `requires Android Gradle plugin 9.1.0` / `compile against 37` | 用了 2026 最新 AndroidX，要求 AGP 9.1 + compileSdk 37 | 依赖回退到 AGP 8.x 那一代 |
| 3 | `AMapEngine.kt:244 Return type mismatch: expected 'LatLng', actual 'CoordinateConverter!'` | 高德 `coord()` 返回转换器本身，取结果要再调 `.convert()` | 链式补 `.convert()` |
| 3 | `AccessDeniedException: ...\kotlin\daemon\...tmp` | Kotlin 守护进程要写 `%LOCALAPPDATA%`，沙箱拒绝 | `kotlin.compiler.execution.strategy=in-process` |
| 4 | wrapper 任务 `Test of distribution url ... failed` | `services.gradle.org` 从 Java 侧不可达 | wrapper 指向腾讯云镜像 |
| 5 | `AccessDeniedException: ...\Temp\kotlin-compiler-in-ingji-*.alive` | Kotlin 进程内编译仍往系统 Temp 写临时文件，被 ACL 拒绝 | `org.gradle.jvmargs` 里把 `java.io.tmpdir` 指到 `path\_tmp` |

最终依赖版本（刻意停在 AGP 8.x 代，避免顶到未验证的 AGP 9）：

```
AGP 8.13.2 / Gradle 8.14.3 / Kotlin 2.4.21 / compileSdk 36 / targetSdk 36 / minSdk 26
androidx.core:core-ktx:1.16.0
androidx.lifecycle:lifecycle-runtime-ktx:2.9.1
androidx.activity:activity-compose:1.10.1
androidx.compose:compose-bom:2025.06.01
```

最终构建：

```
> Task :app:assembleDebug
BUILD SUCCESSFUL in 15s
```

### 3.1 真机上发现的两个问题（都已修）

**问题 A：高德 11.x 的折线点击监听不触发。**
`AMap.setOnPolylineClickListener` 注册成功、编译通过、`PolylineOptions` 却没有任何 `clickable` 参数
（用 `javap` 反查确认：`Polyline` 只有 `remove/setPoints/setColor/...`，无 `setClickable`）。
手指点在线上没有任何回调。

**修法**：改成自己做命中判定 —— 注册 `setOnMapClickListener`，把点击点和折线顶点都投影成屏幕像素，
计算**点到线段**的最短距离，小于 48px（520dpi ≈ 14dp）即判定选中。

**问题 B（第一次修法错了）：只比较顶点距离。**
第一版命中判定只算「点击点到每个顶点」的距离。点在**边的中间**时，离最近顶点超过 48px，
判定失败（日志实证：`mapClick at (39.9112,116.4043) -> null (lines=1)`，但该点其实压在线段上）。
**修法**：改算点到线段的垂距。

这两个坑第 4/5 步点选分段会反复用到，所以现在就把它做对了。

---

## 4. 调试签名已固定（重要，直接影响高德 Key 是否有效）

**发现的隐患**：本机原本没有 `C:\Users\27603\.android\debug.keystore`。
构建时因为我重定向了 `ANDROID_USER_HOME`，调试证书生成在了临时目录里。
后果是：你之后用 Android Studio 打开工程、重新编译，AS 会**另生成一份**调试证书，
SHA1 随之改变 → 高德 Key 校验失败 → 地图白屏，而且很难排查。

**处理方式**：

1. 在工程内生成固定调试证书：`D:\Xingji\keystore\xingji-debug.jks`
2. `app/build.gradle.kts` 里显式绑定：
   ```kotlin
   signingConfigs {
       getByName("debug") {
           storeFile = rootProject.file("keystore/xingji-debug.jks")
           storePassword = "android"
           keyAlias = "androiddebugkey"
           keyPassword = "android"
       }
   }
   ```
3. 同时复制一份到 `C:\Users\27603\.android\debug.keystore`，让 Android Studio 默认路径也用同一份。

**调试版 SHA1（填高德「调试版安全码」）：**

```
92:BF:80:9F:37:A1:1F:D9:7B:59:90:0C:6B:FB:3F:88:B8:EF:2A:81
```

`apksigner` 对最终 APK 的验证结果（小写、无冒号，与上同）：

```
Signer #1 certificate DN: CN=Android Debug, O=Android, C=US
Signer #1 certificate SHA-1 digest: 92bf809f37a11fd97b59900c6bfb3f88b8ef2a81
```

> 高德后台两个输入框的格式习惯：**40 位十六进制，冒号可有可无**。
> 建议去掉冒号粘贴，粘贴后回头看一眼位数对不对。

---

## 5. APK 产物

| 项 | 值 |
|---|---|
| 构建路径 | `app/build/outputs/apk/debug/app-debug.apk` |
| 交付副本 | `D:\Xingji\artifacts\app-debug.apk` |
| 大小 | 52.31 MB |
| package | `com.llyytt.path` |
| versionName / versionCode | 0.2.0 / 1 |
| minSdk / targetSdk | 26 / 36 |
| 应用名 | 行迹 |
| native-code | `arm64-v8a`（唯一 ABI） |
| 启动 Activity | `com.llyytt.path.MainActivity` |
| 签名 | 调试证书（见第 4 节） |

---

## 6. 第 8 节 9 条自查状态（真机全部实测）

设备：Redmi K80 至尊版（`25060RK16C`，Android 16 / 澎湃 OS），`adb` 驱动，1280×2772 @520dpi。

| # | 自查项 | 结果 | 证据 |
|---|---|---|---|
| 1 | 底图显示（不白屏） | ✅ | `screenshots/01-map-ok.png`，logcat 无 `INVALID_USER_SCODE` |
| 2 | 拖动/缩放正常 | ✅ 拖动 ✅ 双击放大（双指缩放 adb 无法模拟，未自动化） | `screenshots/04-double-tap-zoom.png` |
| 3 | 墨绿测试折线 | ✅ | `01-map-ok.png` 中的闭合墨绿折线 |
| 4 | 点折线弹 Toast | ✅ 「点到折线：demo」 | `screenshots/02-polyline-click-toast.png` |
| 5 | 「定位到我」 | ✅ 地图飞到济南，状态显示 `36.66055, 116.89748` | `screenshots/03-locate-me-jinan.png` |
| 6 | 「截图」 | ✅ 「截图成功：1280 × 2772」 | `screenshots/06-screenshot-button.png` |
| 7 | 离线能力显示 CACHE_ONLY | ✅ 「仅 SDK 底图缓存（弱网可看部分区域，不保证完整）」 | `01-map-ok.png` |
| 8 | 16KB 脚本退出码 0 | ✅ `RESULT: PASS` | 见第 2 节 |
| 9 | 切后台回来地图不白 | ✅ HOME 4 秒后返回，相机位置、折线都在，无崩溃 | `screenshots/05-back-after-background.png` |

`logcat` 全程无 `FATAL EXCEPTION`，无 `AndroidRuntime` 崩溃记录。

---

## 7. 本步收尾状态

- 高德 Key 已通过 `-PAMAP_KEY=...` 构建参数注入，**没有写进任何文件**（`AndroidManifest.xml` 里是 `${amapKey}` 占位符）。
- 发布版 SHA1 已拿到，但 release keystore 密码由你保管，我没有经手。
- 真机已装最新包，包名 `com.llyytt.path`。

**第 3 步开工前，需要你先确认三件事**（见下条消息）。


---

## 7. 卡在这里：需要你操作

### 7.1 高德 Key（必须你本人做，要实名）

1. `https://lbs.amap.com` 注册 → 控制台 → 账号管理 → **实名认证（个人）**
2. 应用管理 → 我的应用 → 创建新应用
   - 名称：`行迹`，类型：`出行`
3. 添加 Key，服务平台选 **Android 平台**，四个框：

| 字段 | 填什么 |
|---|---|
| Key 名称 | `xingji-android` |
| 发布版安全码 SHA1 | 见 7.2（还没生成就先留空） |
| 调试版安全码 SHA1 | `92BF809F37A11FD97B59900C6BFB3F88B8EF2A81` |
| PackageName | `com.llyytt.path` |

4. 提交后得到 **32 位十六进制 Key**，发我（我只把它当构建参数用，不写进任何文件）。

### 7.2 发布版证书（你设密码，我不经手）

你说过 release keystore 密码由你设定、我不记录。请在**你自己的** PowerShell 里跑这一条：

```powershell
& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v -keystore "D:\AndroidKeys\xingji-release.jks" -alias xingji -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=Xingji, OU=Personal, O=Personal, L=Beijing, ST=Beijing, C=CN"
```

它会交互式问你两遍密码（库口令 + 密钥口令，可设成一样）。
跑完再执行下面这条拿 SHA1，把 SHA1 发我，**密码不要发我**：

```powershell
& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -list -v -keystore "D:\AndroidKeys\xingji-release.jks" -alias xingji
```

> 目录 `D:\AndroidKeys` 若不存在，先 `New-Item -ItemType Directory D:\AndroidKeys`。
> 生成后立刻把 `.jks` 复制一份到 U 盘/网盘，密码写进密码管理器。

### 7.3 手机

1. K80 插 USB，通知栏选「传输文件 / MTP」
2. 开发者选项 → USB 调试 打开
3. 手机弹「允许 USB 调试」，勾「一律允许」并确定

完成后 `adb devices` 能看到设备，我继续装 APK、抓 logcat、跑剩下 8 条自查。
