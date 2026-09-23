# 本地逐笔行情接入与 K 线聚合（Spring Boot）

在本地内存中接收逐笔成交（tick），按固定时间窗口聚合为 K 线（OHLCV + 成交笔数），
对外提供「成交上报」与「行情查询」两个 HTTP 接口，并对异常输入与并发访问给出稳定、
可复现、可区分的结论。

- Java 21 · Spring Boot 3.4.x · 无外部中间件（状态全部在内存，进程重启清空）

---

## 1. HTTP 接口

### 1.1 上报逐笔成交（幂等）

`POST /api/v1/trades`

请求体（`price`/`volume`/`timestamp` 刻意使用字符串，以便区分“无法解析”与“值非法”）：

```json
{
  "tradeId": "t-1001",
  "symbol": "BTC",
  "price": "100.25",
  "volume": "3.5",
  "timestamp": "1700000001000"
}
```

| 场景 | HTTP 状态 | `status` / `reasonCode` |
| --- | --- | --- |
| 首次计入 | `201 Created` | `status=ACCEPTED`，并返回所属 `windowStartMillis` |
| 同一 `tradeId` 重复提交 | `200 OK` | `status=DUPLICATE`，聚合结果保持不变（幂等） |
| 字段非法 / 时间戳超范围 | `422 Unprocessable Entity` | `reasonCode` 见第 5 节 |
| 请求体不是合法 JSON | `400 Bad Request` | `reasonCode=MALFORMED_JSON` |

成功响应示例：

```json
{
  "status": "ACCEPTED",
  "tradeId": "t-1001",
  "symbol": "BTC",
  "windowStartMillis": 1699999980000,
  "rejectReason": null,
  "reasonDetail": "成交已计入窗口 1699999980000"
}
```

错误响应示例（**不会静默丢弃**，一定带可区分原因码与可读说明）：

```json
{
  "error": "TRADE_REJECTED",
  "reasonCode": "PRICE_NOT_POSITIVE",
  "message": "price 必须为正数，实际为: 0"
}
```

### 1.2 查询某一时刻所属窗口的 K 线

`GET /api/v1/symbols/{symbol}/klines?at={epochMillis}`

- `at` 落在哪个窗口就返回哪个窗口的 K 线。
- 该窗口尚无成交：`404 Not Found`，`reasonCode=WINDOW_NOT_FOUND`。
- `at` 缺失或不是整数：`400 Bad Request`，`reasonCode=TIMESTAMP_NOT_A_NUMBER`。

响应：

```json
{
  "symbol": "BTC",
  "windowStartMillis": 1699999980000,
  "windowEndMillis": 1700000040000,
  "open": 90, "high": 100, "low": 90, "close": 100,
  "volume": 5, "tradeCount": 2
}
```

---

## 2. 时间窗口与边界归属

- 窗口大小由 `aggregator.window-millis` 配置，默认 **60000ms（1 分钟）**。
- 成交 `t` 的窗口起点：

  ```
  windowStart = floor(timestamp / windowMillis) * windowMillis
  ```

  使用 `Math.floorDiv` 对负时间戳也有确定定义。
- 每个窗口是**左闭右开**区间 `[start, start + windowMillis)`。
- **恰好落在边界** `start + k*windowMillis` 的成交，唯一归属**右侧**窗口，
  不会同时计入相邻两个窗口（见测试 `boundaryTicksBelongToExactlyOneWindow`）。

---

## 3. 聚合与乱序/迟到纠错规则

窗口内每个字段的定义与到达顺序无关，同一组成交无论以何种顺序提交，最终 K 线唯一：

| 字段 | 规则 |
| --- | --- |
| open（开盘价） | 排序键 `(timestamp, tradeId)` **最小**的成交价 |
| close（收盘价） | 排序键 `(timestamp, tradeId)` **最大**的成交价 |
| high / low | 窗口内成交价的最大 / 最小值 |
| volume | 窗口内所有**不同**成交量之和（`BigDecimal` 精确累加） |
| tradeCount | 窗口内**不同**成交笔数 |

- 排序键先比时间戳；时间戳相同时用 `tradeId` 字典序兜底，
  保证同毫秒多笔成交的开/收盘也是确定的（见测试 `sameTimestampTieBreakIsOrderIndependent`）。
- 乱序或迟到的成交，按其自身时间戳定位窗口；若它更早/更晚，
  会即时修正该窗口的 open/close（以及 high/low/volume/count），
  修正结果与“全部按时间排序后再聚合”的串行预期完全一致
  （见测试 `outOfOrderAndLateTicksCorrectFinalBar`）。

### 幂等

以全局唯一 `tradeId` 作为幂等键。同一笔成交重复提交（无论串行还是并发）
都返回 `DUPLICATE`，且**不**改变成交量与笔数。并发下同一笔成交保证“恰好计入一次”
（见测试 `concurrentDuplicates_countExactlyOnce`）。

---

## 4. 一致性边界（并发模型）

- 所有状态变更（去重判定 → 窗口定位 → 开高低收量笔数更新）在同一把锁内
  **一次性原子完成**。
- 每个窗口的聚合状态是**不可变对象**，新成交产生全新实例后整体替换 Map 引用。
  因此查询要么看到更新前的完整 K 线，要么看到更新后的完整 K 线，
  **绝不会读到“成交量已增加而最高价尚未更新”这类半更新视图**。
- 查询返回的是不可变 `KlineBar` 快照，后续聚合不影响已返回的结果。
- 并发批量重复提交的最终聚合值，与独立计算的串行预期逐字段相等，可复现
  （见测试 `concurrentBatch_finalBarMatchesSerialExpectation`，其中还包含聚合进行中的并发读校验）。

> 一致性范围：单进程内、`KlineAggregator` 单次方法调用的原子可见性。
> 不提供跨进程/跨重启持久化，也不提供跨多个窗口的事务视图。

---

## 5. 输入校验与拒绝原因码

校验顺序固定，保证一次拒绝只对应一个确定原因：
`tradeId → symbol → price（先可解析、再为正）→ volume → timestamp（先可解析、再范围）`。

| reasonCode | 含义 |
| --- | --- |
| `MISSING_TRADE_ID` | `tradeId` 缺失或为空白 |
| `MISSING_SYMBOL` | `symbol` 缺失或为空白 |
| `PRICE_NOT_A_NUMBER` | `price` 缺失或无法解析为十进制数 |
| `PRICE_NOT_POSITIVE` | `price` 可解析但 ≤ 0 |
| `VOLUME_NOT_A_NUMBER` | `volume` 缺失或无法解析 |
| `VOLUME_NOT_POSITIVE` | `volume` 可解析但 ≤ 0 |
| `TIMESTAMP_NOT_A_NUMBER` | `timestamp` 缺失或不是 64 位整数（查询参数非法也用此码） |
| `TIMESTAMP_OUT_OF_RANGE` | 时间戳早于下限，或晚于“服务器当前时间 + 容忍偏移” |
| `MALFORMED_JSON` | 请求体不是合法 JSON |
| `WINDOW_NOT_FOUND` | 查询的窗口尚无成交（404） |

价格、数量使用 `BigDecimal`；仅接受严格正数。

---

## 6. 配置项（`src/main/resources/application.properties`）

| 属性 | 默认值 | 说明 |
| --- | --- | --- |
| `aggregator.window-millis` | `60000` | K 线窗口大小（毫秒） |
| `aggregator.min-timestamp-millis` | `946684800000` (2000-01-01Z) | 允许的最早成交时间戳 |
| `aggregator.max-future-skew-millis` | `5000` | 允许超过服务器时钟的最大毫秒数（容忍时钟漂移） |

---

## 7. 本地验证

### 运行全部测试

```bash
mvn test
```

包含 13 个新增用例（外加既有的 Spring 上下文加载测试），覆盖：

- `KlineAggregatorTest`：窗口 floor/边界归属、边界成交只进一个窗口、
  乱序与迟到修正、同时间戳 tie-break 与顺序无关、串行幂等。
- `TradeValidatorTest`：每一类非法输入对应唯一可区分的拒绝原因码、合法输入解析。
- `ConcurrencyTest`：并发重复提交恰好一次计入；400 笔 × 8 写线程并发重复提交，
  最终三窗口 OHLCV/笔数与串行预期一致；4 个读线程在聚合期间验证读不到半更新视图。
- `TradeControllerIntegrationTest`：端到端上报/乱序修正/查询、HTTP 幂等、
  422/400/404 与原因码。

测试通过控制台打印 **`[输入]` 与 `[判定依据]`**，记录输入数据、期望值与结论。
也可查看 `target/surefire-reports/`。

### 启动服务并手工验证

```bash
mvn spring-boot:run
```

```bash
# 上报两笔（第二笔时间更早，模拟迟到，会修正开盘价）
curl -s -X POST localhost:8080/api/v1/trades -H 'Content-Type: application/json' \
  -d '{"tradeId":"t1","symbol":"BTC","price":"100","volume":"4","timestamp":"1700000003000"}'
curl -s -X POST localhost:8080/api/v1/trades -H 'Content-Type: application/json' \
  -d '{"tradeId":"t0","symbol":"BTC","price":"90","volume":"1","timestamp":"1700000001000"}'

# 重复提交（幂等）
curl -i -X POST localhost:8080/api/v1/trades -H 'Content-Type: application/json' \
  -d '{"tradeId":"t0","symbol":"BTC","price":"90","volume":"1","timestamp":"1700000001000"}'

# 查询 K 线（注意：真实运行时时间戳需落在“当前时间 ± 允许范围”内）
curl -s 'localhost:8080/api/v1/symbols/BTC/klines?at=1700000000000'

# 非法输入 -> 422 + reasonCode
curl -i -X POST localhost:8080/api/v1/trades -H 'Content-Type: application/json' \
  -d '{"tradeId":"bad","symbol":"BTC","price":"0","volume":"1","timestamp":"1700000001000"}'
```

> 时间戳校验依赖服务器时钟：手工 curl 时请把 `timestamp` 换成当前 epoch 毫秒
> （且与服务器时钟偏差不超过 `max-future-skew-millis`）。集成测试通过注入固定时钟
> 保证用例可重复、不依赖真实时间。

---

## 8. 代码结构

```
domain/      值对象：Trade / WindowKey / KlineBar / IngestResult / RejectReason
dto/         对外请求与响应记录
error/       TradeRejectedException / WindowNotFoundException
config/      ClockConfig（可替换时钟，便于确定性测试）
service/     AggregatorProperties（配置）、TradeValidator（解析校验）、
             KlineAggregator（线程安全聚合状态）、TradeService（应用服务）
web/         TradeController（REST）、GlobalExceptionHandler（错误码映射）
```
