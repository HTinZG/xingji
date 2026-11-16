# 第 5 步执行日志：自动分段 + 手动修正

分两批。批次 1 = 算法 + 校验 + 单测（不依赖真实 GPS）；批次 2 = 手动修正 UI + 事务。

---

## 批次 1：已完成

### 5.1 阈值统一（一套数，两处用）

`Tuning.Segment` 现在是唯一来源，分段分类和地图「按速度着色」共用：

```kotlin
WALK_MAX_MPS = 2.2        // 约 8 km/h
BIKE_MAX_MPS = 7.0        // 约 25 km/h
STOP_SPEED_MPS = 0.8
STOP_MIN_DURATION_MS = 20_000
GAP_SPLIT_MS = 120_000
MIN_SEGMENT_MS = 15_000
SMOOTH_WINDOW = 5
```

`TrackPainter.speedBucket` 里原来写死的 1.4 / 5.0 / 15.0 已删掉，改成读同一组常量。
以后调速度分档，两个地方一起变，不会出现「地图上是绿的、分段却算成走路」这种不一致。

### 5.2 算法（`core/rules/Segmenter.kt`）

流程：

1. 按 `startsNewSegment` 切「连续片」——断点两边不参与同一段。
2. 片内速度做中位数平滑（窗口 5），逐点判断是否低于 `STOP_SPEED_MPS`。
3. 连续低于阈值且时长 ≥ 20 秒 → 记为**停留**，这些点从分段里排除（成为空洞）。
4. 剩下的点在片内按速度分档，合并成 WALK / BIKE / CAR 段。
5. 短于 15 秒的段并入索引相邻的那一段（避免切得太碎）。
   **只在索引相邻时合并**——中间隔着停留的两段不能合，否则停留点会被包进 segment。

输出是草稿（`SegmentDraft` / `StayDraft`），落库留给批次 2。

### 5.3 停留与分段的职责划分

- `TransportMode` 里**没有 STAY**（早前已改）：只有 `UNKNOWN / WALK / BIKE / CAR`
- 停留全部进 `stay_point`（`StayDraft` 带中心点、半径、到达/离开时间）
- 详情页现在的「N 个停留」就是读 `stay_point`；完整的停留区块列表留给批次 2 的 UI

### 5.4 校验（`core/rules/TripInvariants.kt`）

分段五条：

| 代号 | 检查 |
|---|---|
| `SEQ_NOT_DENSE` | seq 必须 0..N-1 连续 |
| `SEGMENT_RANGE` | 起点 ≤ 终点、且在 0..maxSeq 内 |
| `SEGMENT_OVERLAP` | 相邻段严格递增、不重叠 |
| `SEGMENT_OVERLAPS_STAY` | 分段区间不能和停留区间相交 |
| `POINT_NOT_COVERED` | 每个点要么在分段里、要么在停留里 |

**明确允许**：segment 跨过 `startsNewSegment=true` 的点。那个标记只影响画线断开，
不强制分段切开。有一条单测专门钉这个行为。

暂停三条：

| 状态 | 要求 |
|---|---|
| RECORDING | 未闭合暂停数必须为 0 |
| PAUSED | 未闭合暂停数必须恰好为 1 |
| FINISHED | 未闭合数为 0 且 `endedAt` 非空 |

### 5.5 单测：52 个全过

```
core.geo.PolylineHitTestTest      8
core.geo.PolylineSimplifierTest   6
core.rules.DurationCalcTest       6
core.rules.SegmenterTest          5
core.rules.SpeedDisplayTest      17
core.rules.TripInvariantsTest    10
                                  --
                                  52  failures=0
```

分段 5 个用例：合成轨迹切三段一停留、合成轨迹通过校验、10 秒停顿不算停留、
断点处不合并、点数太少返回空。
校验 10 个用例：合法无违规、重叠、漏覆盖、停留覆盖、跨断点不报错、分段压停留、
seq 不连续，以及暂停三种状态的正反例。

### 5.6 合成轨迹的切段输出（单测真实打印）

构造：走 2 分钟（1.3 m/s）→ 停 30 秒 → 快走 1 分钟（2.0 m/s）→ 骑车 2 分钟（5.5 m/s），每 2 秒一个点。

```
=== 合成轨迹：走 2 分钟 → 停 30 秒 → 快走 1 分钟 → 骑车 2 分钟 ===
点位数=165  总时长=328.0s
停留 1 个：
  seq 62..76  28.0s  半径 7.2m
分段 3 段：
  [1] WALK  seq 0..61  122s  153m
  [2] WALK  seq 77..106  58s  130m
  [3] BIKE  seq 107..164  114s  626m
校验：通过（0 条违规）
```

读这张表能看出三件事：

1. **切段点正确**：走路段在停留前结束（0..61），停留之后重新开一段，骑车段从 107 开始。
2. **分类正确**：两段 WALK（第二段是快走，2.0 < 2.2 仍是走路），一段 BIKE。
3. **停留被正确剥离**：停留 62..76 不在任何 segment 里，半径 7.2m 符合「站着不动 + 平滑边界」。

### 5.7 两个已知偏差（可接受，记录在案）

**① 边界被平滑推迟约 2 个点。**
快走转骑车时，中位数窗口里还留着 2 个低速样本，所以 seq 105~106 被判成 WALK，
骑车段从 107 才开始。代价是 4~5 秒的边界误差，换来的是不会因为单点跳变把段切碎。
要更灵敏就把 `SMOOTH_WINDOW` 调小，但误判会变多。

**② 停留半径 7.2m 偏大。**
合成数据是「原地不动」，理论上半径应接近 0。7.2m 来自停留边界那几个点——
它们实际还在移动，只是平滑后速度低于阈值被算进了停留。真实数据里 GPS 抖动本来就有几米，
这个量级正常。

### 5.8 单测里修正过一次自己的预期

「断点处不合并」这条最初把断点放在 seq 100，结果切出来的后半段只有 5 个点（10 秒），
被 `MIN_SEGMENT_MS` 合法地合并回前一段，断言失败。
**这不是代码错，是测试用例设计错了。** 把断点移到骑车段正中（seq 130），两半都够长，断言通过。

### 5.9 批次 1 没做的事（留给批次 2）

- 分段结果落库（`trip_segment` / `stay_point` 的写入）
- 行程结束时自动跑分段并持久化
- 详情页的停留区块列表（现在只有「N 个停留」一个数字）
- 手动修正：改方式 / 拆分 / 合并

这些都属于批次 2 的「UI + 事务」范围，按你的要求不提前做。

---

---

## 批次 2：手动修正 UI + 事务（已完成）

### 5.10 索引已确认

`trip_segment` 的索引早前已改成：

```kotlin
indices = [
    Index(value = ["tripId", "startPointSeq"], unique = true),
    Index(value = ["tripId", "seq"]),          // 普通索引，拆分时 seq 顺移不会被挡
]
```

`track_point` 的 `(tripId, seq)` 唯一索引保持不变。

### 5.11 三个操作（`data/repo/SegmentRepository.kt`）

每个操作一个事务，原始 `track_point` 一行都不碰。

| 操作 | 事务里做了什么 | 校验 |
|---|---|---|
| 自动分段 | 删旧段/旧停留 → 写入新段/新停留 | 跑 |
| 改方式 | 只 update `mode` + `manuallyEdited` | **不跑**（按你的要求） |
| 拆分 | 前段缩短 → 后续段 seq 顺移 → 插入后段 | 跑 |
| 合并 | 前段延长 → 删后段 → 后续段 seq 前移 | 跑 |

`validateOrThrow()` 失败直接抛 `IllegalStateException`，Room 回滚整个事务——
不会留半拆状态。

### 5.12 一个实现取舍

`stay_point` 表里没有存 seq 区间（只有中心点、半径、到达/离开时间）。
校验时需要停留的 seq 范围来算「覆盖」，我选择**用时间戳反查点的下标**：

```kotlin
val start = points.indexOfFirst { it.recordedAt >= stay.arriveAt }
val end = points.indexOfLast { it.recordedAt <= leaveAt }
```

因为 `arriveAt/leaveAt` 本来就取自点的 `recordedAt`，能精确对上。
这样避免为了「校验」再加两个字段和一次 schema 迁移。

### 5.13 真机验证（合成轨迹：走 2min → 停 30s → 快走 1min → 骑车 2min）

| # | 操作 | 结果 |
|---|---|---|
| 0 | 自动分段 | 3 段：走路 0..61(153m)、走路 77..106(130m)、骑车 107..164(626m)，1 个停留 |
| 1 | [1] 改方式 走路→骑车 | `seq 0..61` 与 153 米**完全没变**，只有模式变了 |
| 2 | [2] 拆分（slider 拖到中点） | 4 段：`77..91`(56m) + `92..106`(70m) |
| 3 | [2]+[3] 合并 | 回到 3 段，`77..106`(130m) |
| 4 | 把 [3] 尾部切成 4 秒，再点合并 | 弹窗：**「合并后时长 00:04，短于建议的 15 秒。」「手动操作优先于规则，你确认就执行。」** |
| 5 | 回详情页 | 距离 932 米、活动时长 05:28 不变；显示「3 段 · 编辑」；切到「按方式」后轨迹变成 橙→绿→橙 |

截图：`screenshots/22-seg-change-mode.png`、`23-seg-split.png`、`24-seg-merge.png`、
`25-seg-merge-warning.png`、`26-detail-after-edits.png`

### 5.14 原始点未被修改（硬证据）

三次操作前后各导出一次数据库，对 `tripId=7` 的全部 165 个点逐字段比对：

```
before rows 165   after rows 165
identical: True
first seq 0 -> 0   last seq 164 -> 164
hash before 3eee90d79b4ea18b   after 3eee90d79b4ea18b
```

### 5.15 顺带发现

用 5 个点的真实行程跑自动分段时，**整条行程被判成了一个停留**——因为 5 个点的平滑速度
全部低于 0.8 m/s，连续时长又超过 20 秒。这不是 bug，是样本太少的必然结果，
也说明分段算法对「短行程 + 慢速」确实敏感。真实轨迹（几百个点、速度有变化）才是有意义的输入。
