# tick-kline-aggregator-java

本地逐笔行情接入与 K 线聚合服务（Spring Boot 3 / Java 21）。

## 对外接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/market/ticks` | 上报一笔逐笔成交 |
| GET  | `/api/market/klines/{symbol}/at/{timestamp}` | 查询某时刻所属窗口的 K 线快照（无数据返回 404） |
| GET  | `/api/market/klines/{symbol}` | 查询某标的全部 K 线（按窗口起点升序） |

成交上报请求体：

```json
{"tickId":"t-1","symbol":"BTCUSDT","price":100.5,"quantity":2,"timestamp":1790205660000}
```

响应：`{"status":"ACCEPTED"|"DUPLICATE","tickId":"...","symbol":"...","windowStart":...}`。
非法输入返回 HTTP 400，响应体含可机读的拒绝原因：

```json
{"reason":"INVALID_PRICE","message":"price must be a positive finite number, got -1.0"}
```

拒绝原因枚举：`INVALID_TICK_ID`、`INVALID_SYMBOL`、`INVALID_PRICE`、`INVALID_QUANTITY`、
`INVALID_TIMESTAMP`（非正的时间戳）、`TIMESTAMP_OUT_OF_RANGE`（超出允许的迟到/未来范围）、
`MALFORMED_REQUEST`（请求体无法解析，如时间戳为非数字字符串）。任何非法输入都会被显式
拒绝并给出原因，绝不静默丢弃。

## 聚合与纠错规则

- **窗口归属**：窗口为左闭右开 `[windowStart, windowStart + windowSizeMs)`，
  `windowStart = floorDiv(timestamp, windowSizeMs) * windowSizeMs`。恰好落在窗口起点的
  成交归属该窗口，且只归属该窗口，不会同时计入相邻窗口。
- **K 线字段**：`open/high/low/close/volume/tradeCount`。
  - `open` = 窗口内**时间戳最小**成交的价格；`close` = **时间戳最大**成交的价格。
    因此乱序或迟到的成交会修正其所属窗口的开/收盘价，而不是按到达顺序覆盖。
  - 时间戳相同的平局按 `tickId` 字典序打破（open 取最小、close 取最大），
    保证修正结果**唯一确定、与到达顺序无关**。
  - `volume` 使用 `BigDecimal` 累加，与成交到达顺序无关，保证并发下结果可复现。
- **幂等**：同一 `symbol` 下相同 `tickId` 只计入一次。重复提交返回 `DUPLICATE`，
  不重复计入成交量与笔数。并发重复提交的最终聚合结果与串行处理完全一致。
- **时间范围**：`timestamp` 必须为正；不得早于 `now - kline.max-lateness-ms`，
  不得晚于 `now + kline.max-future-ms`，否则以 `TIMESTAMP_OUT_OF_RANGE` 拒绝。

## 一致性边界

每个窗口的聚合状态由独立的内部锁保护，所有字段在锁内原子更新；查询返回的是
不可变快照（`Kline` record）。聚合进行中的查询不会读到半更新的 K 线
（例如成交量已增加而最高价未更新）。测试 `snapshotIsConsistentWhileAggregating`
在 4 写线程聚合的同时用读线程持续校验快照内部一致性（`low <= open/close <= high`
且量与笔数自洽）。

## 配置

`application.properties`（前缀 `kline`）：

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `kline.window-size-ms` | `60000` | K 线窗口长度（毫秒） |
| `kline.max-lateness-ms` | `86400000` | 允许的最大迟到时间 |
| `kline.max-future-ms` | `300000` | 允许的最大未来偏移 |

## 本地验证

```bash
# 运行全部测试（含并发重复提交、乱序迟到、窗口边界、拒绝原因断言，
# 每个用例会打印输入与判定依据）
mvn test

# 启动服务
mvn spring-boot:run

# 上报成交
curl -X POST localhost:8080/api/market/ticks \
  -H 'Content-Type: application/json' \
  -d '{"tickId":"t-1","symbol":"BTCUSDT","price":100.5,"quantity":2,"timestamp":'"$(date +%s)000"'}'

# 查询 K 线
curl localhost:8080/api/market/klines/BTCUSDT
```
