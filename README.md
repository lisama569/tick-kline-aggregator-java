# tick-kline-aggregator-java

本地逐笔行情接入与 K 线聚合服务（Spring Boot 3 / Java 21）。
支持**多周期同时聚合**、**时间范围批量查询**与**窗口保留上限与自动回收**，
并在扩展的同时保持单周期接口与语义完全兼容。

## 对外接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/market/ticks` | 上报一笔逐笔成交（原子计入所有已配置周期） |
| GET  | `/api/market/klines/{symbol}/at/{timestamp}` | 查询某时刻所属窗口的 K 线快照（无数据 404） |
| GET  | `/api/market/klines/{symbol}` | 查询某标的全部保留 K 线（按窗口起点升序） |
| GET  | `/api/market/klines/{symbol}/range?from=&to=` | 按时间范围批量查询连续 K 线（整批一致视图） |

所有 GET 查询都支持可选查询参数 `windowSizeMs`（如 `&windowSizeMs=300000`），
缺省时使用**主周期**（`kline.window-sizes-ms` 中最小的周期，单周期配置时即
`kline.window-size-ms`），因此不带该参数的历史调用行为完全不变。

成交上报请求体：

```json
{"tickId":"t-1","symbol":"BTCUSDT","price":100.5,"quantity":2,"timestamp":1790205660000}
```

响应（新增 `windowStarts` 字段，回报该笔成交在每个周期归属的窗口起点；
`windowStart` 仍为主周期窗口起点，与旧版本兼容）：

```json
{
  "status": "ACCEPTED",
  "tickId": "t-1",
  "symbol": "BTCUSDT",
  "windowStart": 1790205660000,
  "windowStarts": {"60000": 1790205660000, "300000": 1790205600000}
}
```

非法输入返回 HTTP 400，响应体含可机读的拒绝原因：

```json
{"reason":"INVALID_PRICE","message":"price must be a positive finite number, got -1.0"}
```

拒绝原因枚举：

| reason | 触发场景 |
| --- | --- |
| `INVALID_TICK_ID` / `INVALID_SYMBOL` / `INVALID_PRICE` / `INVALID_QUANTITY` | 字段非法 |
| `INVALID_TIMESTAMP` | 时间戳非正，或路径时间戳不是数字 |
| `TIMESTAMP_OUT_OF_RANGE` | 超出允许的迟到/未来范围 |
| `MALFORMED_REQUEST` | 请求体无法解析（如时间戳为非数字字符串） |
| `WINDOW_EVICTED` | 迟到成交归属的窗口已超出保留上限被回收（新增） |
| `INVALID_RANGE` | 范围查询参数非法（`from`/`to` 非正、`from > to`、非数字、缺失，新增） |
| `UNKNOWN_PERIOD` | 查询指定的周期未在 `kline.window-sizes-ms` 中配置（新增） |

任何非法输入都会被显式拒绝并给出原因，绝不静默丢弃。

## 多周期聚合

- 由 `kline.window-sizes-ms` 配置同时聚合的周期（逗号分隔，毫秒）。留空或缺省时
  退化为仅 `kline.window-size-ms` 单周期，与历史行为完全一致。默认配置 `60000,300000`。
- 每个周期必须是**最小周期的整数倍**（启动时校验，不满足直接失败），
  保证一个大周期恰好由整数个连续小周期拼成，跨周期严格对齐：
  - 大周期 `open`/`close` = 时间意义上首个/末个小周期（跳过无成交空窗）的开/收；
  - 大周期 `high` = 子小窗最高之最大、`low` = 子小窗最低之最小；
  - 大周期 `volume` = 子小窗成交量之和；`tradeCount` = 子小窗笔数之和。
- 同一笔成交在标的级锁内**原子地计入每个周期恰好一次**：要么所有周期都计入，
  要么（如命中回收拒绝）一个都不计入，不会产生部分写入或残缺窗口。
- 周期边界成交仍只归一个窗口（每个周期各自按左闭右开判定，见下）。
- 乱序、迟到、并发上报下，每个周期的结果都与串行处理一致、唯一且可复现
  （open/close 的时间戳+tickId 平局规则与单周期版本相同）。

### 时间范围查询

`GET /api/market/klines/{symbol}/range?from={ms}&to={ms}&windowSizeMs={可选}`

- `from`/`to` 均为 epoch 毫秒且**两端都含**，返回所有与 `[from, to]` 相交的完整窗口，
  按窗口起点升序。整批窗口在同一把标的锁内生成，来自**同一时刻的一致视图**，
  不会出现部分窗口已计入新成交、部分仍是旧值的撕裂批次。
- 即使全无数据也返回 200，每个窗口以 `state` 明确区分三种情况：

| state | 含义 |
| --- | --- |
| `HAS_DATA` | 窗口内有成交，`kline` 字段为完整 K 线 |
| `EMPTY` | 窗口落在该周期已观察到的时间跨度（最旧~最新有数据窗口）之内但无成交。空窗仅在查询时确定性生成，不落库、不占保留名额、不参与回收 |
| `NOT_RETAINED` | 窗口早于回收下限（已被回收）、晚于最新窗口，或该标的/周期根本没有数据 |

  响应还带 `oldestWindowStart`/`newestWindowStart`（无任何数据时为 `null`），
  调用方据此可把“确定无成交的空窗”与“系统里没有这段数据”可靠区分。
- `from`/`to` 非法返回 400 + `INVALID_RANGE`；未配置周期返回 400 + `UNKNOWN_PERIOD`。

## 聚合与纠错规则

- **窗口归属**：窗口为左闭右开 `[windowStart, windowStart + windowSizeMs)`，
  `windowStart = floorDiv(timestamp, windowSizeMs) * windowSizeMs`。恰好落在窗口起点的
  成交归属该窗口，且只归属该窗口，不会同时计入相邻窗口。
- **K 线字段**：`open/high/low/close/volume/tradeCount`。
  - `open` = 窗口内**时间戳最小**成交的价格；`close` = **时间戳最大**成交的价格。
    乱序或迟到的成交会修正其所属窗口的开/收盘价，而不是按到达顺序覆盖。
  - 时间戳相同的平局按 `tickId` 字典序打破（open 取最小、close 取最大），
    保证修正结果**唯一确定、与到达顺序无关**。
  - `volume` 使用 `BigDecimal` 累加，与成交到达顺序无关，保证并发下结果可复现。
- **幂等**：同一 `symbol` 下相同 `tickId` 只计入一次。重复提交返回 `DUPLICATE`，
  不重复计入成交量与笔数。**去重表不随窗口回收清理**：即使成交所属窗口已被回收，
  同一 tickId 重放仍稳定返回 `DUPLICATE`；而同窗口的“新”tickId 会被 `WINDOW_EVICTED`
  拒绝，两者可明确区分。并发重复提交的最终聚合结果与串行处理完全一致。
- **时间范围**：`timestamp` 必须为正；不得早于 `now - kline.max-lateness-ms`，
  不得晚于 `now + kline.max-future-ms`，否则以 `TIMESTAMP_OUT_OF_RANGE` 拒绝。

## 窗口保留与自动回收

服务设计为长时间运行，每个标的的内存占用有界：

- `kline.max-windows-per-period`（默认 **10000**，1 分钟周期约覆盖最近 7 天）
  限制**每个标的、每个周期**保留的**有数据窗口**数量；空窗不占名额。
- 每个周期维护一个**只进不退的高水位**（曾接受过的最大窗口起点）。
  回收硬下界 = `高水位 - (上限-1) × 周期长度`：
  - 当保留槽位打满、成交目标窗口早于硬下界时，以 `WINDOW_EVICTED` **显式拒绝**
    （HTTP 400，错误信息含目标窗口、下界、高水位、上限，结论确定可复现）；
  - 高水位只进不退，迟到成交不会让下界后退，**已回收窗口不会被重建**，
    保留窗口数永远不超过上限，也不会因迟到补单产生残缺窗口。
- **跨周期嵌套回收**：以最小周期的高水位推算全局保留时间下界并向下对齐到大周期边界。
  任何大窗口只要其覆盖范围内含有已落在保留时间范围之外的小窗口（无法再由现存小窗
  完整合成），就会被一并回收；因此回收之后，**任一仍保留的大周期都恰好等于其覆盖的
  现存小周期合成**，多周期对齐关系不因回收而被破坏。
- 回收只删除窗口聚合状态，不影响：幂等判定（去重表常驻）、保留范围内窗口的结果、
  以及后续更新窗口的正常摄入。
- 幂等去重表按 `(symbol, tickId)` 常驻，是“同一笔成交绝不重复计入”这一语义的代价；
  tickId 集合本身有界（每笔成交一个 id）。

## 一致性边界

每个标的的全部周期共享一把内部锁，摄入在锁内跨周期原子完成，所有查询
（单窗口、整表、时间范围）都在该锁内取同一时刻的快照；查询返回不可变快照
（`Kline` / `KlineRange` record）。聚合进行中的查询不会读到半更新的 K 线
（例如成交量已增加而最高价未更新），范围查询不会读到新旧混杂的批次。
测试 `snapshotIsConsistentWhileAggregating` 与
`rangeViewSnapshotsAreInternallyConsistentUnderConcurrentIngest`
在多个写线程聚合的同时用读线程持续校验快照内部一致性。

## 配置

`application.properties`（前缀 `kline`）：

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `kline.window-size-ms` | `60000` | 主窗口长度（毫秒）；未配置 `window-sizes-ms` 时即唯一周期，兼容历史键 |
| `kline.window-sizes-ms` | `60000,300000` | 同时聚合的周期列表（逗号分隔，毫秒）；留空退化为单周期。每项必须是最小项的整数倍 |
| `kline.max-lateness-ms` | `86400000` | 允许的最大迟到时间 |
| `kline.max-future-ms` | `300000` | 允许的最大未来偏移 |
| `kline.max-windows-per-period` | `10000` | 每标的、每周期保留的有数据窗口数上限，超出自动回收最旧部分（新增） |

### 兼容范围

- 单周期部署：不设置 `kline.window-sizes-ms` 即恢复为单一 `kline.window-size-ms`，
  全部旧接口、旧响应字段与旧语义保持不变。
- 响应仅新增字段（`windowStarts`），不改变既有字段含义。
- 查询接口仅新增可选参数 `windowSizeMs`，缺省行为与旧版本一致。
- 新拒绝原因（`WINDOW_EVICTED`/`INVALID_RANGE`/`UNKNOWN_PERIOD`）只在对应的新增
  场景出现，旧有的非法输入仍返回原来的原因。

## 本地验证

```bash
# 运行全部测试（28 个用例，含多周期对齐、范围查询一致性、回收与迟到处理、
# 回收后重复提交、跨周期嵌套回收、并发幂等、拒绝原因断言；每个用例打印输入与判定依据）
mvn test

# 启动服务
mvn spring-boot:run

# 上报成交（默认同时聚合 1 分钟与 5 分钟周期）
curl -X POST localhost:8080/api/market/ticks \
  -H 'Content-Type: application/json' \
  -d '{"tickId":"t-1","symbol":"BTCUSDT","price":100.5,"quantity":2,"timestamp":'"$(date +%s)000"'}'

# 单窗口查询（默认 1 分钟周期；加 &windowSizeMs=300000 查 5 分钟周期）
curl 'localhost:8080/api/market/klines/BTCUSDT/at/'"$(date +%s)000"

# 时间范围批量查询（返回 HAS_DATA / EMPTY / NOT_RETAINED 可区分的连续窗口）
curl 'localhost:8080/api/market/klines/BTCUSDT/range?from='"$(date -d '-10 min' +%s)000"'&to='"$(date +%s)000"
```
