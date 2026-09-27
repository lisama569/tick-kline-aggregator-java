# tick-kline-aggregator-java

本地逐笔行情接入与 K 线聚合服务（Spring Boot 3 / Java 21）。支持：

- 同一标的**多个周期并行聚合**（如 1m / 5m / 15m），各周期独立查询、严格对齐；
- 按**时间范围批量拉取**连续 K 线，整批结果来自同一时刻的一致视图，空窗口与无数据可区分；
- 窗口**保留上限与自动回收**，长期运行内存有界，迟到成交落入已回收窗口时显式拒绝。

## 对外接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/market/ticks` | 上报一笔逐笔成交 |
| GET  | `/api/market/klines/{symbol}/at/{timestamp}` | 查询某时刻所属窗口的 K 线快照（无数据 404），可选 `periodMs` |
| GET  | `/api/market/klines/{symbol}` | 查询某标的全部保留 K 线（按窗口起点升序），可选 `periodMs` |
| GET  | `/api/market/klines/{symbol}/range?from=&to=&periodMs=` | **范围查询**连续窗口槽位（见下） |

成交上报请求体：

```json
{"tickId":"t-1","symbol":"BTCUSDT","price":100.5,"quantity":2,"timestamp":1790205660000}
```

上报响应：`{"status":"ACCEPTED"|"DUPLICATE","tickId":"...","symbol":"...","windowStart":...}`。
非法输入返回 HTTP 400，响应体含可机读的拒绝原因：

```json
{"reason":"INVALID_PRICE","message":"price must be a positive finite number, got -1.0"}
```

拒绝原因枚举：

- `INVALID_TICK_ID`、`INVALID_SYMBOL`、`INVALID_PRICE`、`INVALID_QUANTITY`、
  `INVALID_TIMESTAMP`（非正时间戳）、`TIMESTAMP_OUT_OF_RANGE`（超出允许的迟到/未来范围）；
- `MALFORMED_REQUEST`：请求体无法解析（如时间戳为非数字字符串）；
- `UNSUPPORTED_WINDOW_SIZE`：查询使用了未配置的聚合周期；
- `INVALID_RANGE`：范围查询起止颠倒或跨度超过 `kline.max-range-windows`；
- `WINDOW_EVICTED`：成交应落入的窗口已按保留上限回收；或查询的窗口已回收 /
  大周期窗口无法由保留的小周期窗口完整覆盖（详见“窗口保留与回收”）。

任何非法或不可处理的输入都会被显式拒绝并给出原因，绝不静默丢弃。

## 多周期聚合

- 通过 `kline.window-size-ms`（默认周期）与 `kline.additional-window-sizes-ms`
  （逗号分隔的其他周期）配置，例如：

  ```properties
  kline.window-size-ms=60000
  kline.additional-window-sizes-ms=300000,900000
  ```

- 每个标的为**每个已配置周期**各维护一份独立窗口表。同一笔成交在每个周期各计一次、
  且只计一次；幂等按 `symbol + tickId` 判定，跨周期共享一次去重。
- **周期组合必须满足整数倍对齐**：把所有周期升序排列后，每个周期都必须是前一个周期的
  整数倍（传递性保证每个周期都是所有更小周期的整数倍），窗口网格才能嵌套对齐。
  合法：`60000 + 300000`（1m/5m）、`60000 + 300000,900000`（1m/5m/15m）。
  非法：`60000 + 90000`（90s 不是 60s 的整数倍）、`60000 + 120000,300000`
  （300s 不是 120s 的整数倍）。非法组合在**启动时**即抛出
  `InvalidPeriodConfigException`（独立异常类型，与其他配置错误可区分），
  服务不会带着对不齐的配置运行。
- 查询时可用 `periodMs` 显式指定周期；缺省使用默认周期。未配置周期以
  `UNSUPPORTED_WINDOW_SIZE`（HTTP 400）拒绝。
- **周期间严格对齐**：各周期由同一成交流按相同的确定性规则独立聚合，因此大周期窗口的
  `open/high/low/close/volume/tradeCount` 与其覆盖的全部小周期窗口合起来必然一致：
  `open` = 最早非空小窗的 open，`close` = 最晚非空小窗的 close，
  `high/low` = 各小窗极值，`volume/tradeCount` = 求和。
- 乱序、迟到、并发重复上报下，每个周期的结果都与串行处理一致且可复现；
  恰好落在周期边界的成交只归属一个周期窗口（左闭右开）。

## 时间范围查询

`GET /api/market/klines/{symbol}/range?from={ms}&to={ms}&periodMs={ms}`

- 返回**窗口起点落在 `[from, to)`** 的全部窗口槽位（from 包含、to 不包含），
  槽位起点严格对齐窗口网格；from/to 无需对齐边界。
- 整批结果在「标的+周期」的同一把内部锁内生成，来自**同一时刻的一致视图**，
  不会出现部分窗口已计入新成交、部分仍是旧值。
- 每个槽位带状态，调用方可以据此区分“窗口无成交”和“系统中没有这段数据”：

  | status | kline | 含义 |
  | --- | --- | --- |
  | `FILLED` | 非空，不可变快照 | 窗口内有成交 |
  | `EMPTY` | 空 | 窗口处于保留范围内但无成交（确定可预测的空窗口） |
  | `UNAVAILABLE` | 空 | 窗口已按保留上限回收、大周期窗口无法由保留的小周期窗口完整覆盖，或该标的在系统中根本没有数据 |

  响应示例：

  ```json
  [
    {"windowStart":1790205600000,"windowEnd":1790205660000,"status":"EMPTY","kline":null},
    {"windowStart":1790205660000,"windowEnd":1790205720000,"status":"FILLED",
     "kline":{"symbol":"BTCUSDT","windowStart":1790205660000,"windowEnd":1790205720000,
              "open":100.5,"high":101.0,"low":99.0,"close":100.0,"volume":5.0,"tradeCount":3}}
  ]
  ```

- `from >= to` 或槽位数超过 `kline.max-range-windows`（默认 5000）时，
  返回 400 + `INVALID_RANGE`。

## 聚合与纠错规则（单周期语义，所有周期相同）

- **窗口归属**：左闭右开 `[windowStart, windowStart + windowSizeMs)`，
  `windowStart = floorDiv(timestamp, windowSizeMs) * windowSizeMs`。恰好落在窗口起点的
  成交归属该窗口，且只归属该窗口，不会同时计入相邻窗口。
- **K 线字段**：`open/high/low/close/volume/tradeCount`。
  - `open` = 窗口内**时间戳最小**成交的价格；`close` = **时间戳最大**成交的价格。
    乱序或迟到成交会修正其所属窗口的开/收盘价，而不是按到达顺序覆盖。
  - 时间戳相同的平局按 `tickId` 字典序打破（open 取最小、close 取最大），
    保证修正结果**唯一确定、与到达顺序无关**。
  - `volume` 使用 `BigDecimal` 累加，与成交到达顺序无关，保证并发下结果可复现。
- **幂等**：同一 `symbol` 下相同 `tickId` 只计入一次。重复提交返回 `DUPLICATE`，
  不重复计入成交量与笔数。去重表不随窗口回收删除，回收不影响既有幂等判定。
- **时间范围**：`timestamp` 必须为正；不得早于 `now - kline.max-lateness-ms`，
  不得晚于 `now + kline.max-future-ms`，否则以 `TIMESTAMP_OUT_OF_RANGE` 拒绝。

## 窗口保留与回收

- 每个标的、每个周期最多保留 `kline.max-windows-per-symbol` 个窗口（默认 **10000**，
  可配置）。当一次接受成交后窗口数超过上限时，按窗口起点升序回收最旧窗口，
  并推进回收水位线。回收只由**已接受的不同成交**驱动，与墙钟无关，行为可复现。
- 回收不影响仍在保留范围内的任何窗口结果，也不改变幂等去重表。
- **跨周期一致性（覆盖水位线）**：相同窗口数上限下，最小周期覆盖的时间跨度最短、
  回收水位线最“紧”。大周期窗口只有当其覆盖的全部最小周期窗口**仍在保留范围内**时
  才可用；凑不齐小周期数据的大周期窗口一律视为不可用，绝不会当作正常结果返回：
  - 单点查询（`/at/{timestamp}`）：返回 400 + `WINDOW_EVICTED`，明确告知该段
    大周期数据不可用（与保留范围内无成交的 404 可区分）；
  - 范围查询（`/range`）：对应槽位标注 `UNAVAILABLE`；
  - 窗口列表（`/klines/{symbol}`）：不可用的大周期窗口不出现在结果中，
    即返回的每个大周期窗口都能由仍然保留的小周期窗口完整对上。
- 迟到成交若落入**任一周期**已回收的窗口：在任何状态变更之前即以 `WINDOW_EVICTED`
  （HTTP 400）整体拒绝——
  - 不静默丢弃：拒绝原因与水位线随响应/日志给出；
  - 不生成残缺窗口：被回收窗口不会因这笔成交而复生；
  - 不重复计入：其他周期也不会写入该成交，保证多周期结果始终对得上；
  - 可复现：同一迟到成交无论提交多少次，结论一致。
  注意回收水位线对最小周期最“紧”（相同窗口数上限下，小周期覆盖时间最短），因此最小周期
  是回收判定的约束边界。
- 范围查询中，已回收槽位返回 `UNAVAILABLE`，与保留范围内无成交的 `EMPTY` 可区分。
- **回收不改变幂等判定**：已成功接受过的成交，无论其所属窗口是否已被回收，
  再次上报都稳定返回 `DUPLICATE`（幂等判定先于回收检查，去重表不随回收删除）；
  重复上报不触碰任何窗口状态——已回收窗口不会复生、保留范围不变、不重复计入；
  并发重复上报同一批成交时结论与串行完全一致。与之对照：从未接受过的成交落入
  已回收窗口，仍按 `WINDOW_EVICTED` 拒绝，两种结论可区分。

## 一致性边界

同一「标的+周期」的所有读写在同一把 store 锁内完成：单窗口字段原子更新，范围查询整批
原子生成。聚合进行中的查询不会读到半更新（例如量已加而最高价未更新）或撕裂的跨窗口
视图。查询返回的是不可变快照（`Kline` record）。

## 配置

`application.properties`（前缀 `kline`）：

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `kline.window-size-ms` | `60000` | 默认（基础）窗口长度（毫秒） |
| `kline.additional-window-sizes-ms` | 空 | 额外同时聚合的周期（毫秒，逗号分隔） |
| `kline.max-windows-per-symbol` | `10000` | 每个标的、每个周期的窗口保留数上限 |
| `kline.max-range-windows` | `5000` | 单次范围查询最多返回的窗口槽位数 |
| `kline.max-lateness-ms` | `86400000` | 允许的最大迟到时间 |
| `kline.max-future-ms` | `300000` | 允许的最大未来偏移 |

## 兼容范围

- 原有接口路径、请求/响应字段与单周期语义保持不变：不传 `periodMs` 时行为与旧版本一致。
- `IngestStatus`（`ACCEPTED`/`DUPLICATE`）与单点查询 `Kline` 结构未变；
  新增的 `WINDOW_EVICTED` / `UNSUPPORTED_WINDOW_SIZE` / `INVALID_RANGE` 是
  `RejectReason` 的追加枚举值，范围查询为新端点、`KlineRangeEntry` 为新响应类型。
- 默认配置下系统仍为单周期（60s）聚合；只有显式配置
  `kline.additional-window-sizes-ms` 才启用多周期。
- 本次修正明确的两处语义（旧行为属于缺陷，测试已按新语义调整并注明）：
  1. 已回收窗口的单点查询由“静默返回空（404）”改为返回 400 + `WINDOW_EVICTED`，
     与“保留范围内无成交（404）”可区分；
  2. 已成功接受过的成交在其窗口被回收后再次上报，由 `WINDOW_EVICTED` 改为稳定返回
     `DUPLICATE`——回收不改变已成立的幂等判定。

## 本地验证

```bash
# 运行全部测试（39 个用例，含多周期对齐、带窗口上限的跨周期回收一致性、
# 非法周期组合启动失败、回收后重复提交判定、并发与串行一致、范围一致视图、
# 回收迟到处理、并发幂等、乱序迟到、窗口边界、拒绝原因；每个用例打印输入与判定依据）
mvn test

# 只跑本次修正相关的测试类
mvn test -Dtest=MultiPeriodRetentionTests,PeriodAlignmentTests,RetentionTests

# 启动服务
mvn spring-boot:run

# 上报成交
curl -X POST localhost:8080/api/market/ticks \
  -H 'Content-Type: application/json' \
  -d '{"tickId":"t-1","symbol":"BTCUSDT","price":100.5,"quantity":2,"timestamp":'"$(date +%s)000"'}'

# 查默认周期单点
curl 'localhost:8080/api/market/klines/BTCUSDT/at/'"$(date +%s)000"

# 范围查询最近 5 分钟（5 个 1 分钟槽位），观察 FILLED / EMPTY 状态
NOW=$(($(date +%s%3N))); curl "localhost:8080/api/market/klines/BTCUSDT/range?from=$((NOW-300000))&to=$NOW"

# 显式指定周期（需在 additional-window-sizes-ms 中配置 300000）
curl 'localhost:8080/api/market/klines/BTCUSDT?periodMs=300000'
```

多周期本地验证建议：在 `application.properties` 配置
`kline.additional-window-sizes-ms=300000,900000`，对同一批成交分别用
`periodMs=60000/300000/900000` 查询，核对大周期 OHLC、成交量与笔数与覆盖的小周期完全一致。
回收验证可将 `kline.max-windows-per-symbol` 调小（如 `3`），写入超过上限的窗口后
再向最旧窗口补发一笔成交，观察 `WINDOW_EVICTED` 拒绝。
跨周期回收一致性验证：配置 `60000 + 300000` 且 `kline.max-windows-per-symbol=10`，
连续上报 20 个以上 1 分钟窗口的成交后，查询早期的 5 分钟窗口——单点查询返回
400 + `WINDOW_EVICTED`，范围查询对应槽位为 `UNAVAILABLE`，`/klines/{symbol}?periodMs=300000`
只返回能由保留的 1 分钟窗口完整覆盖的 5 分钟窗口。周期对齐验证：配置
`kline.additional-window-sizes-ms=90000`（非 60s 整数倍），服务启动即失败并抛出
`InvalidPeriodConfigException`，日志中可见具体的违规周期对。
