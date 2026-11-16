# 第 9 步执行日志：打包、签名、真机验收

## ① release 签名打包

### 签名配置（密码不进代码）

`app/build.gradle.kts` 里只读密码，不写密码：

```kotlin
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun secret(propKey: String, envKey: String): String? =
    (localProps.getProperty(propKey) ?: System.getenv(envKey))?.takeIf { it.isNotBlank() }

val releaseStoreFile     = secret("xingji.release.storeFile",     "XINGJI_STORE_FILE")
val releaseKeyAlias      = secret("xingji.release.keyAlias",      "XINGJI_KEY_ALIAS")
val releaseStorePassword = secret("xingji.release.storePassword", "XINGJI_STORE_PASSWORD")   // ← 你自己填
val releaseKeyPassword   = secret("xingji.release.keyPassword",   "XINGJI_KEY_PASSWORD")     // ← 你自己填

val hasReleaseSigning = listOf(...).all { !it.isNullOrBlank() }
```

```kotlin
signingConfigs {
    if (hasReleaseSigning) {
        create("release") {
            storeFile = file(releaseStoreFile!!)   // 路径
            storePassword = releaseStorePassword   // ← local.properties / 环境变量
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword       // ← local.properties / 环境变量
        }
    }
}

buildTypes {
    release {
        isMinifyEnabled = true
        isShrinkResources = true
        if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        // 没有密码时保持未签名，绝不退化成调试签名
    }
}
```

**密码脱敏说明**：上面代码块里 `storePassword` / `keyPassword` 两行指向的是
`local.properties` 或环境变量里的值，源码中不存在任何明文密码。
我全程没有接触你的密码，release 包也因此构建为**未签名**版。

### 你要做的两件事

1. 在 `local.properties`（与 `settings.gradle.kts` 同级，不进 git）里加：

```properties
xingji.release.storeFile=D\:\\AndroidKeys\\xingji-release.jks
xingji.release.keyAlias=<你的别名>
xingji.release.storePassword=<你自己填>
xingji.release.keyPassword=<你自己填>
```

2. 然后执行（命令里不带任何密码）：

```
gradlew.bat :app:assembleRelease -PAMAP_KEY=<高德Key>
```

产物：`app/build/outputs/apk/release/app-release.apk`（签名版）。

### .gitignore 已确认包含

```
local.properties
keystore/
*.jks
*.keystore
```

### 本次构建结果

由于没有密码，产出的是 `app-release-unsigned.apk`：

| 项 | 值 |
|---|---|
| 路径 | `app/build/outputs/apk/release/app-release-unsigned.apk` |
| 体积 | **44.29 MB** |
| R8 | 已开启（`isMinifyEnabled = true` + `isShrinkResources = true`） |
| abiFilters | 仅 `arm64-v8a` |

体积构成（解压后 47.5 MB）：

| 部分 | 大小 |
|---|---|
| `lib/`（native） | 33.7 MB |
| `assets/`（高德资源） | 8.5 MB |
| `classes*.dex` | 5.0 MB |
| 其它 | 0.3 MB |

单是高德的 `libAMapSDK_MAP_v11_3_100.so` 就 **32.9 MB**——
体积天花板由地图 SDK 决定，跟业务代码关系不大。
已经做了能做的：R8 开着、只留 arm64、资源压缩开着。

R8 过程中踩到一个坑：高德 jar 的 location 模块引用了未随包发布的
`com.amap.ams.gnss.GnssSoftLocator`，R8 会把它当**错误**而不是警告直接构建失败。
在 `proguard-rules.pro` 里加了 `-dontwarn com.amap.ams.**` 才通过。

## ② 高德 Key 的 release SHA1

### 获取命令（你自己执行）

```
keytool -list -v -keystore "D:\AndroidKeys\xingji-release.jks" -alias <你的别名>
```

不记得别名就先列全部证书：

```
keytool -list -v -keystore "D:\AndroidKeys\xingji-release.jks"
```

输出里找这一行：

```
证书指纹:
     SHA1: XX:XX:XX:...
```

把 SHA1 填进高德控制台 → 应用管理 → 你的 Key → 设置 → **发布版安全码 SHA1**。
PackageName 填 `com.llyytt.path`。

> 注意：调试版和发布版的 SHA1 是两个不同的值，都要填进同一个 Key 里，
> 否则调试能显示地图、发布包白屏。

### release 包地图白屏的排查顺序

按这个顺序查，基本三步内定位：

1. **先看是"白屏"还是"黑屏/闪退"**
   闪退多半是 `.so` 没打进包或 ABI 不匹配；白屏才是 Key 的问题。
   ```
   adb logcat | findstr /i "amap auth"
   ```
   看到 `auth fail` / `INVALID_USER_SCODE` 就是 Key 校验没过。

2. **核对 SHA1**
   ```
   keytool -list -v -keystore "D:\AndroidKeys\xingji-release.jks" -alias <别名>
   ```
   和第 ② 步填进控制台的值逐字符对比。常见错误：
   - 填成了调试版 SHA1
   - 少填了冒号
   - 控制台里 Key 的"发布版安全码"那一栏是空的

3. **核对包名**
   必须是 `com.llyytt.path`，和你 `applicationId` 一致。
   如果改过 `applicationIdSuffix`，控制台也要跟着改。

4. **确认 Key 真的进了包**
   ```
   adb shell dumpsys package com.llyytt.path | findstr /i "amap"
   ```
   或者解包看 AndroidManifest 里的 `com.amap.api.v2.apikey` 是否有值。
   如果在 CI 里构建，注意 `-PAMAP_KEY` 有没有传——不传就是空串，地图必白。

5. **确认隐私合规接口调用顺序**
   高德 11.x 要求在 `MapView` 创建前调用 `MapsInitializer.updatePrivacyShow/updatePrivacyAgree`，
   顺序错了也会白屏。本项目在 `XjApp` 里已处理。

## ③ release 包 16KB 检查

命令：

```
.\tools\Check-16Kb.ps1 .\app\build\outputs\apk\release\app-release-unsigned.apk -All
```

输出原文：

```
检查目标：C:\Users\27603\Downloads\path\Xingji\app\build\outputs\apk\release\app-release-unsigned.apk
共 4 个 native 库


Abi       Library                        Align Verdict
---       -------                        ----- -------
arm64-v8a libAMapSDK_MAP_v11_3_100.so    16384 通过 16KB
arm64-v8a libandroidx.graphics.path.so   16384 通过 16KB
arm64-v8a libapssdk.so                   16384 通过 16KB
arm64-v8a libdatastore_shared_counter.so 16384 通过 16KB


结论：全部通过 16 KB 页对齐，可以在这类设备上运行。
RESULT: PASS (all PT_LOAD p_align >= 16384)
```

## ④ HyperOS 五个手动设置

见独立文档：`docs/hyperos-setup.md`。五条分别是自启动、省电策略无限制、
锁定后台卡片、通知常驻、电池优化白名单，每条都写了路径和"为什么需要"，
末尾附了"设置完怎么自检"和"还断的话按现象排查"。

## ⑤ 隐私说明与商用授权

### 隐私说明页

设置 → 隐私说明，共七节：

1. **轨迹只存本机** —— 本地 SQLite，无账号无云端，卸载即清除
2. **AI Key 是加密存的** —— Keystore AES-256-GCM，换机拷文件也解不开
3. **AI 请求发给你配置的服务商** —— 只发摘要不发原始点，不经过任何中转
4. **导出文件由你控制** —— SAF 选路径，文件里没有 Key/BaseURL/聊天记录
5. **不上传服务器** —— 没有自建后端，除地图和用户触发的 AI 外不主动联网
6. **用了哪些权限，为什么** —— 定位/前台服务/通知/存储逐条说明
7. **怎么删数据** —— 卸载即清除，Key 可在设置页清空（连带删 Keystore 密钥）

### 高德商用授权成本（重要，别忽略）

现状：**侧载自用不触发商用授权**。高德开放平台的免费额度面向个人开发者和
非商业场景，装在自己手机上、不对外分发不收费。

未来什么情况要重新评估：

| 场景 | 是否需要商用授权 |
|---|---|
| 只装自己/家人手机，不发布 | 不需要 |
| 上架应用商店（哪怕免费） | **需要评估**，属对外分发 |
| 收费、内购、广告变现 | 需要 |
| 公司内部使用 | 需要 |

费用参考：高德地图商用授权大约 **5 万元/年** 量级（具体以官方报价为准，
按调用量和服务等级有浮动）。上架前必须先确认，否则属于无授权商用。

替代方案（如果只是自己用）：换成开源且允许商用的底图
（MapLibre + 自建瓦片 / OpenStreetMap），但那样就没有高德的中文 POI 和路网精度了。
本项目当前的架构（`MapEngine` 接口隔离）已经为将来换引擎留好了位置。

## ⑥ 真机验收结果

| 项 | 结果 |
|---|---|
| release APK 构建 | ✅ 44.29 MB，R8 + 资源压缩 + arm64 only |
| 16KB 对齐（release 包） | ✅ 4 个 .so 全部 16384 |
| 回滚验证（阻塞项） | ✅ 见下 |
| 进程被杀恢复（阻塞项） | ✅ 见下 |
| 隐私说明页 | ✅ 七节全部渲染 |
| 户外 1 小时（阻塞项） | ⬜ 需要你本人出门 |

### 回滚验证细节

制造一个"拆分后必然违规"的状态：删掉 `stay_point` 里那条停留记录，
让 seq 62~76 这 15 个点既不在任何段里、也不在任何停留里。

执行第 2 段的拆分 → 弹窗：

> **操作没有生效**
> 分段校验失败：POINT_NOT_COVERED(点 seq=62 既不在分段也不在停留里), …

查库结果：

```
=== 回滚测试前 ===
   (0, 'BIKE', 0, 61)
   (1, 'WALK', 77, 106)
   (2, 'BIKE', 107, 164)
=== 回滚测试后 ===
   (0, 'BIKE', 0, 61)
   (1, 'WALK', 77, 106)
   (2, 'BIKE', 107, 164)
  段数: 3
  轨迹点: 165
  完全一致: True
```

没有插入新段、没有改 endPointSeq、没有半拆状态。

### 进程被杀恢复细节

记录中执行 `am force-stop` → 重新打开 App → 界面显示「已结束旧行程，可以重新开始」。

数据库确认：该行程 `FINISHED`、`endedAt` 已写入、`RECORDING` 计数为 0、没有重复行程。

当前策略是**结束**而不是**继续**：暂停后 GPS 有断档，直接续会产生一条
横穿城市的直线，比结束更难解释。这个取舍记在清单里。

## ⑦ 单测

**65 个，0 失败。** 其中第 9 步新增 1 个：KML 样式 id 在同方式两段时也必须唯一。

---

## ⑧ 签名版构建与真机验证（2026-11-16 21:52）

### 构建

高德 Key 从用户级环境变量 `XINGJI_AMAP_KEY` 读入当前进程，命令行里不出现 Key。

| 项 | 值 |
|---|---|
| 产物 | `app/build/outputs/apk/release/app-release.apk`（已签名） |
| 体积 | **44.30 MB** |
| 签名 | v2（v1 关闭，minSdk 26 默认） |

### 签名验证

`keytool -printcert -jarfile` 对 v2-only 的包会报"未签名"（它只认 JAR v1 签名），
正确工具是 `apksigner`：

```
Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Number of signers: 1
Signer #1 certificate DN: CN=Xingji, OU=Personal, O=Personal, L=Beijing, ST=Beijing, C=CN
Signer #1 certificate SHA-1 digest: a441adbaa6dfdef1a5b144f4a362541db83191de
Signer #1 key algorithm: RSA
Signer #1 key size (bits): 2048
```

CN = **Xingji**，SHA1 与 release keystore 一致。

### 安装

`adb uninstall com.llyytt.path` → `adb install app-release.apk` 均直接成功，手机无二次确认。

**注意**：MIUI 在卸载重装后会自动恢复应用数据（行程、隐私同意状态都回来了），
要真正清空得用 `adb shell pm clear com.llyytt.path`。

### 高德鉴权

第一次装完抓到：

```
authErrLog: |SHA1Package:A4:41:AD:...:com.llyytt.path|
authErrLog: |key:<dd37……（旧 Key，已吊销，完整值不记录）>|
authErrLog: |json:{"status":"0","info":"INVALID_USER_KEY","infocode":"10001"}|
```

原因：环境变量里传的是 `<旧Key>`，带尖括号（长度 34 而非 32），而且是已吊销的旧 Key。

修正后重新构建，APK 内嵌 Key 与环境变量指纹一致（均为 32 位），重装后再抓日志：

**无 `authErrLog`、无 `INVALID_USER_KEY`**，地图正常渲染。

这说明 Key、发布版 SHA1、包名三者都对上了——只要有一项不对，就会重新出现 authErrLog。

---

## ⑨ 追加：删行程 + AI 配置可见可清除

### 为什么加

用户反馈两点：
1. 行程没法删，测试数据留着碍事
2. 不知道 AI 里到底存了哪些配置（填过什么、还在不在）

### 删行程

- `TripDao.deleteById()` 一条 SQL，子表靠 CASCADE 连带删除
- `TripRepository.delete()` 外面包一层事务，顺带清掉挂在这条行程上的 AI 消息
- 入口在详情页底部：`导出 / 分享图 / 删除`，删除是红色
- 删除前弹确认框，明确写了"轨迹点、分段、停留会一起删掉，不能恢复"，
  并提示想留就先导出

### AI 配置面板

设置页 AI 区块顶部新增「当前已保存」卡片：

- BaseURL、模型名直接显示
- Key 只显示「已保存（加密存储，不显示原文）」——不显示前缀/后缀，避免截图泄露
- 没配置时显示「还没有配置。填下面的三项并保存即可。」
- 配了就出现红色「清除配置」按钮，点了会：
  1. 清空 DataStore 里的 BaseURL / 模型 / Key 密文 / 隐私同意标记
  2. **同时删掉 Keystore 里的密钥**（`KeyCrypto.deleteKey()`）
  3. 把输入框也清空

### 验证

| 项 | 结果 |
|---|---|
| 详情页出现红色「删除」 | ✅ |
| 删除确认对话框文案正确 | ✅ |
| 删除后回到列表，显示「还没有行程」 | ✅ |
| 设置页显示已保存的 BaseURL / 模型 | ✅ |
| Key 不显示原文 | ✅ |
| 「清除配置」按钮出现 | ✅（未点，因为那是用户的真实配置） |

截图：`screenshots/44-delete-trip-dialog.png`、`45-trip-list-empty.png`、`46-ai-config-panel.png`
