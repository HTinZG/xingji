# 行迹

一个**纯本地**的 Android 出行轨迹记录 App。记录走路、骑车、坐车的轨迹，
自动分段、手动修正，能导出 GPX/KML，也能用自己配置的 AI 服务问关于行程的问题。

> 没有账号、没有云端、没有服务器。数据全在手机本地，卸载即清除。

---

## 功能

- **记录**：手动开始 / 暂停 / 结束；锁屏后由前台服务继续记录
- **地图**：轨迹按速度或交通方式着色；滑块回放
- **自动分段**：按平滑速度与停顿自动切成 走路 / 骑车 / 坐车 / 停留
- **手动修正**：改方式、拆分、合并——每次操作一个事务，校验失败整体回滚
- **导出**：GPX 1.1 / KML 2.2，WGS84 坐标，直接拖进 Google Earth
- **分享图**：只取地图渲染结果 + 自绘统计带，不截屏
- **AI 聊天**：用户自填 OpenAI 兼容接口；Key 用 Android Keystore AES-256-GCM 加密存储
- **隐私说明**：内置说明页，写清数据去哪、怎么删

### 明确不做

账号体系、云同步、离线地图包下载、多轮工具调用、流式输出、AI 生成标题。

---

## 技术栈

| 组件 | 版本 |
|---|---|
| Kotlin | 2.4.21 |
| AGP | 8.13.2 |
| Gradle | 8.14.3 |
| minSdk / targetSdk / compileSdk | 26 / 36 / 36 |
| Jetpack Compose | BOM 2025.06.01 |
| Room | 2.8.5（KAPT） |
| OkHttp | 4.12.0 |
| DataStore | 1.1.7 |
| 高德 3D 地图 SDK | 11.3.100 |

> ⚠️ 不要盲目升级 AndroidX / Compose BOM。2026 年的最新版本要求 AGP 9.1 + compileSdk 37，
> 会把整条工具链顶到未验证的组合上。

---

## 构建

### 1. 准备高德 Key

高德 Key **不进仓库**。通过环境变量传入：

```powershell
$env:AMAP_KEY = "你的高德Key"
```

调试版和发布版的 SHA1 都要填进高德控制台。

### 2. 调试包

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest
```

### 3. 正式包

先在 `local.properties`（不进 git）里配置签名：

```properties
xingji.release.storeFile=D:/path/to/your-release.jks
xingji.release.keyAlias=你的别名
xingji.release.storePassword=你的密码
xingji.release.keyPassword=你的密码      # PKCS12 下与上一行相同
```

然后：

```powershell
.\gradlew.bat :app:assembleRelease
```

> 没有配置密码时会产出 `app-release-unsigned.apk`，**不会**退化成调试签名。

### 4. 验签与 16KB 检查

```powershell
# 注意：不要用 keytool -printcert -jarfile，它只认 JAR v1 签名
apksigner verify --print-certs --verbose app\build\outputs\apk\release\app-release.apk

.\tools\Check-16Kb.ps1 .\app\build\outputs\apk\release\app-release.apk -All
```

---

## 项目结构

```
app/src/main/java/com/llyytt/path/
├── MainActivity.kt            页面路由
├── XjApp.kt                   唯一组装点（数据库 / 仓库 / 地图引擎）
├── core/
│   ├── geo/                   坐标、距离、折线抽稀、命中测试
│   ├── rules/                 纯算法：分段、速度、不变量、统计（全部可单测）
│   ├── export/                GPX / KML 生成、分享图合成
│   └── format/                展示格式化
├── data/
│   ├── db/                    Room 实体与 DAO（version 3）
│   ├── repo/                  录音 / 行程 / 分段 / AI 聊天仓库
│   └── ai/                    Key 加密、设置存储、HTTP 客户端、上下文构造
├── map/                       MapEngine 抽象 + 高德实现
├── service/                   前台记录服务
└── feature/                   Compose 页面
```

### 五条架构规矩

| 规矩 | 原因 |
|---|---|
| 数据库只存 **WGS84** | 各引擎渲染时自己转换；库里存转换后的坐标会导致换引擎、导出全偏 |
| `MapEngine` 只暴露跨引擎能力 | 不被高德 API 绑死 |
| `LocationSource` 独立于 `MapEngine` | 地图加载失败不能影响记录 |
| 高德实现只在 `XjApp` 引用一次 | 换引擎改一处 |
| Key 用 Keystore、导出用 SAF、分享用 FileProvider | 不申请存储权限 |

---

## 测试

```powershell
.\gradlew.bat testDebugUnitTest
```

65 个纯 JVM 单测，覆盖分段算法、速度平滑、不变量校验、GPX/KML 生成。

真机验证清单见 [docs/step9-test-checklist.md](docs/step9-test-checklist.md)。

---

## 文档

| 文件 | 内容 |
|---|---|
| [docs/handbook.md](docs/handbook.md) | **项目手册**：技术决策、踩坑记录、升级流程 |
| [docs/hyperos-setup.md](docs/hyperos-setup.md) | 澎湃 OS 必须打开的五个设置 |
| [docs/step2-log.md](docs/step2-log.md) … | 各阶段实施日志 |
| [docs/step9-test-checklist.md](docs/step9-test-checklist.md) | 真机验收清单 |
| [docs/sample-export.gpx](docs/sample-export.gpx) | 导出样例 |

---

## 许可与第三方

- 高德地图 SDK 版权归高德所有，**仅供个人非商业使用**。商用需另行购买授权（约 5 万元/年）。
- 本仓库为私有项目，不含任何密钥或签名文件。

---

## 隐私

- 轨迹只存本机 SQLite
- AI Key 用 Android Keystore 加密，明文不出设备
- AI 请求直接发往用户自己配置的服务商，App 不做中转
- 导出文件由用户通过系统文件选择器决定位置
- 无任何自建后端
