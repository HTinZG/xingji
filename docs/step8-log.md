# 第 8 步执行日志：KML/GPX 导出 + 截图分享

## 8.1 三条约束的落实

| 约束 | 落实方式 |
|---|---|
| 导出只含轨迹点/时间/分段方式/停留点 | 生成器只读这四类字段，代码里没有任何配置/对话的入口 |
| 导出前扫一遍 | `TrackExporter.scanForSensitiveContent()`，命中就**中止导出**并弹错 |
| 分享图只含地图/轨迹/统计 | **不截屏**，只取地图控件的 `snapshot()` 再自绘统计带 |
| GPX 1.1 / KML 2.2 / WGS84 / ISO 8601 | 见下 |

## 8.2 为什么"不截屏"是更强的做法

整屏截图再裁剪，永远有裁漏的风险（状态栏、悬浮窗、下一个页面透出来）。
这里的做法是：地图控件 `snapshot()` 拿到纯地图位图 → 用 Canvas 在下方拼一条统计带 →
输出 PNG。整个过程 App 界面不参与，**物理上不可能带上设置页、AI 页或通知栏**。

## 8.3 坐标不偏移（核心验收项）

架构上 DB 只存 WGS84（第 2 步定的 D1），导出**原样输出，不做任何转换**。
如果这里偷偷转成 GCJ-02，Google Earth 上会整体偏移 300~600 米。

实测比对（165 点行程，其中 150 个点属于轨迹段，15 个点属于停留时段）：

```
导出点数: 150   期望点数: 150
最大坐标差: 0.000000493 度  (约 0.055 米)
```

0.055 米就是 6 位小数的舍入误差（1e-6 度 ≈ 0.11 米），**没有系统性偏移**。

把 GPX 拿到 WGS84 底图上渲染，轨迹正好落在**前门东大街**上——
和 App 里高德地图显示的是同一条街：

`screenshots/37-gpx-in-viewer.png`

## 8.4 导出文件样例（截断，完整文件见 docs/sample-export.gpx / .kml）

GPX 1.1：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Xingji" xmlns="http://www.topografix.com/GPX/1/1" ...>
  <metadata>
    <name>11-16 20:03</name>
    <time>2026-11-16T12:03:32Z</time>
  </metadata>
  <wpt lat="39.900000" lon="116.401836">
    <name>停留 1</name>
    <time>2026-11-16T12:05:38Z</time>
    <desc>停留 28 秒</desc>
  </wpt>
  <trk>
    <name>第 1 段 · 骑车</name>
    <type>cycling</type>
    <trkseg>
      <trkpt lat="39.900000" lon="116.400030">
        <time>2026-11-16T12:03:34Z</time>
      </trkpt>
      ...
```

KML 2.2：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <name>11-16 20:03</name>
    <description>导出于 2026-11-16T13:05:56Z，坐标 WGS84</description>
    <Style id="style_bike">...</Style>
    <Placemark>
      <name>第 1 段 · 骑车</name>
      <styleUrl>#style_bike</styleUrl>
      <LineString>
        <coordinates>116.400030,39.900000,0 ...</coordinates>
```

结构检查结果：

| 项 | GPX | KML |
|---|---|---|
| 版本/命名空间 | `version="1.1"` + topografix ns | `xmlns="http://www.opengis.net/kml/2.2"` |
| 分段 | 3 个 `<trk>`（cycling / walking / cycling） | 3 个 `<LineString>` + 各自 style |
| 停留 | 1 个 `<wpt>` | 1 个 `<Point>` |
| 点数 | 62 / 30 / 58 | 62 / 30 / 58 |
| 坐标顺序 | lat / lon | **lon,lat,0**（KML 特有） |
| 时间 | 全部匹配 `^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$` | 描述里带导出时间 |

GPX 用「每段一个 trk」而不是「一个 trk 多个 trkseg」——
因为 GPX 1.1 的 trkseg 里装不下"方式"，拆成多个 trk 才能把骑车/走路保住。

## 8.5 敏感信息扫描（导出文件）

对导出的 GPX 和 KML 各扫一遍：

| 关键词 | GPX | KML |
|---|---|---|
| `sk-` | clean | clean |
| `Authorization` | clean | clean |
| `Bearer` | clean | clean |
| `api_key` | clean | clean |
| `api.deepseek` | clean | clean |
| `http://` | 仅 xmlns 声明 | 无 |

App 内部还有一道闸：生成后、写文件前再扫一次，命中就中止并提示
「已中止：文件里发现可疑内容」。

## 8.6 分享图

`screenshots/38-share-image.png`（1280 × 3437）

内容只有两块：
1. 地图位图（含轨迹、停留点、高德 logo）
2. 自绘统计带：日期、`行迹 · 165 个点 · 3 段`、距离 / 时长 / 均速 / 最高

没有状态栏、没有返回按钮、没有滑块、没有导出按钮。

分享面板截图：`screenshots/39-share-sheet.png`

## 8.7 单测

新增 `TrackExporterTest` 12 个用例，全部通过：

- GPX 声明 1.1 + 命名空间
- 每段一个 trk，type 正确
- 坐标是 WGS84 原值 6 位小数
- 时间 ISO 8601 UTC
- 停留点带时长
- KML 是 2.2，经度在前（并断言纬度在前的写法**不存在**）
- XML 特殊字符转义
- 导出内容不含密钥类字样；扫描能抓到 `sk-` / `Authorization`
- 坐标格式化不受系统地区设置影响

总计：**64 个单测，0 失败**

## 8.8 一个实现细节

`pointAt(seq)` 用 `associateBy { it.seq }` 建索引，而不是 `list[seq]`。
虽然 seq 确实是从 0 开始的稠密序号（第 5 步的不变量校验保证了这点），
但导出是"拿去给别人用"的功能，不该依赖这种隐含约束。

## 8.9 已知限制

- 只做**单条行程**导出，没有"导出全部"
- 停留时段内的原始点不进 `<trkseg>`，只留一个 `<wpt>`——
  这是 GPX 的常规做法（轨迹线不画停顿），但也意味着这些点不会出现在轨迹线上
- 分享图是竖版，宽度跟随手机屏幕，没做横版/自定义比例
