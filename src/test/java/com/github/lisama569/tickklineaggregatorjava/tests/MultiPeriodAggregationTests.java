package com.github.lisama569.tickklineaggregatorjava.tests;

import com.github.lisama569.tickklineaggregatorjava.config.KlineProperties;
import com.github.lisama569.tickklineaggregatorjava.model.IngestResult;
import com.github.lisama569.tickklineaggregatorjava.model.IngestStatus;
import com.github.lisama569.tickklineaggregatorjava.model.Kline;
import com.github.lisama569.tickklineaggregatorjava.model.TradeTick;
import com.github.lisama569.tickklineaggregatorjava.service.KlineAggregator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多周期聚合测试：同一标的同时按 1m/5m/15m 聚合，
 * 验证大周期与所覆盖小周期严格对齐、边界归属唯一、乱序与并发下各周期结果与串行一致。
 * 每个用例打印输入与判定依据。
 */
class MultiPeriodAggregationTests {

    private static final long M1 = 60_000L;
    private static final long M5 = 300_000L;
    private static final long M15 = 900_000L;
    private static final String SYMBOL = "BTCUSDT";

    private KlineAggregator aggregator;
    private long base; // 对齐到 15 分钟边界

    @BeforeEach
    void setUp() {
        KlineProperties props = new KlineProperties();
        props.setWindowSizeMs(M1);
        props.setAdditionalWindowSizesMs(List.of(M5, M15));
        aggregator = new KlineAggregator(props);
        long now = System.currentTimeMillis();
        base = Math.floorDiv(now, M15) * M15;
    }

    private TradeTick tick(String id, double price, double qty, long ts) {
        return new TradeTick(id, SYMBOL, price, qty, ts);
    }

    /** 生成确定性成交流：15 分钟内 150 笔，每分钟 10 笔，价格/数量由序号确定。 */
    private List<TradeTick> deterministicTicks() {
        List<TradeTick> ticks = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            long ts = base + i * 6_000L; // 每 6 秒一笔，覆盖整个 15 分钟
            double price = 100.0 + (i * 37 % 50); // 确定性伪随机价格
            double qty = 1.0 + (i % 4);
            ticks.add(tick("MP" + i, price, qty, ts));
        }
        return ticks;
    }

    /** 用小周期窗口合成大周期窗口的期望字段。 */
    private static void assertAligned(String label, Kline big, List<Kline> smalls) {
        List<Kline> filled = smalls.stream().filter(k -> k.tradeCount() > 0).toList();
        assertFalse(filled.isEmpty(), label + ": 大周期有数据时小周期不可能全空");
        double expOpen = filled.get(0).open();                       // 最早非空小窗的 open
        double expClose = filled.get(filled.size() - 1).close();     // 最晚非空小窗的 close
        double expHigh = filled.stream().mapToDouble(Kline::high).max().orElseThrow();
        double expLow = filled.stream().mapToDouble(Kline::low).min().orElseThrow();
        double expVol = filled.stream().mapToDouble(Kline::volume).sum();
        long expCount = filled.stream().mapToLong(Kline::tradeCount).sum();

        System.out.println("[对齐] " + label + " 判定依据: 大周期 open=最早非空小窗open, close=最晚非空小窗close, "
                + "high/low=各小窗极值, volume/count=求和");
        System.out.println("[对齐] " + label + " 期望: open=" + expOpen + " close=" + expClose
                + " high=" + expHigh + " low=" + expLow + " vol=" + expVol + " count=" + expCount);
        System.out.println("[对齐] " + label + " 实际: open=" + big.open() + " close=" + big.close()
                + " high=" + big.high() + " low=" + big.low() + " vol=" + big.volume()
                + " count=" + big.tradeCount());

        assertEquals(expOpen, big.open(), label + " open 必须与小周期合成一致");
        assertEquals(expClose, big.close(), label + " close 必须与小周期合成一致");
        assertEquals(expHigh, big.high(), label + " high 必须与小周期合成一致");
        assertEquals(expLow, big.low(), label + " low 必须与小周期合成一致");
        assertEquals(expVol, big.volume(), 1e-9, label + " volume 必须与小周期求和一致");
        assertEquals(expCount, big.tradeCount(), label + " tradeCount 必须与小周期求和一致");
    }

    private List<Kline> smallWindows(long periodMs, long from, long to) {
        List<Kline> result = new ArrayList<>();
        for (long ws = from; ws < to; ws += periodMs) {
            aggregator.klineAt(SYMBOL, periodMs, ws).ifPresent(result::add);
        }
        return result;
    }

    @Test
    void largePeriodAlignsExactlyWithCoveredSmallPeriods() {
        List<TradeTick> ticks = deterministicTicks();
        List<TradeTick> shuffled = new ArrayList<>(ticks);
        Collections.shuffle(shuffled, new java.util.Random(42)); // 固定种子乱序到达
        shuffled.forEach(aggregator::ingest);

        System.out.println("[对齐] 输入: 150 笔确定性成交覆盖一个 15m 窗口, 固定种子乱序到达");

        Kline k15 = aggregator.klineAt(SYMBOL, M15, base).orElseThrow();
        assertAligned("15m vs 1m", k15, smallWindows(M1, base, base + M15));

        for (long m5Start = base; m5Start < base + M15; m5Start += M5) {
            Kline k5 = aggregator.klineAt(SYMBOL, M5, m5Start).orElseThrow();
            assertAligned("5m@" + m5Start + " vs 1m", k5, smallWindows(M1, m5Start, m5Start + M5));
        }
        assertAligned("15m vs 5m", k15, smallWindows(M5, base, base + M15));
    }

    @Test
    void boundaryTickCountsOnceInEveryPeriod() {
        // 恰好落在 5m（同时也是 15m）边界的成交
        TradeTick boundary = tick("B0", 500.0, 2.0, base);
        TradeTick before = tick("B1", 400.0, 1.0, base - 1); // 前一窗口最后一毫秒
        aggregator.ingest(boundary);
        aggregator.ingest(before);

        System.out.println("[多周期边界] 输入: ts=" + base + "(5m/15m 窗口起点), ts=" + (base - 1) + "(前一窗口末毫秒)");
        System.out.println("[多周期边界] 判定依据: 每个周期内窗口左闭右开, 边界成交在每个周期只计一次且只归本窗口");

        for (long period : new long[]{M1, M5, M15}) {
            Kline cur = aggregator.klineAt(SYMBOL, period, base).orElseThrow();
            Optional<Kline> prev = aggregator.klineAt(SYMBOL, period, base - 1);
            System.out.println("[多周期边界] period=" + period + " 当前窗=" + cur
                    + " 前一窗=" + prev.orElse(null));
            assertEquals(1, cur.tradeCount(), "边界成交在当前周期窗口只计一次");
            assertEquals(2.0, cur.volume());
            if (prev.isPresent()) {
                assertEquals(1, prev.get().tradeCount(), "前一窗口只含末毫秒那笔");
                assertEquals(1.0, prev.get().volume());
            }
        }
    }

    @Test
    void concurrentIngestMatchesSerialOnEveryPeriod() throws InterruptedException {
        List<TradeTick> ticks = deterministicTicks();

        // 串行期望
        KlineProperties props = new KlineProperties();
        props.setWindowSizeMs(M1);
        props.setAdditionalWindowSizesMs(List.of(M5, M15));
        KlineAggregator serial = new KlineAggregator(props);
        ticks.forEach(serial::ingest);

        // 并发：4 线程各自乱序重复提交全部成交
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            List<TradeTick> shuffled = new ArrayList<>(ticks);
            Collections.shuffle(shuffled, new java.util.Random(t));
            pool.submit(() -> {
                try {
                    start.await();
                    for (TradeTick tick : shuffled) {
                        if (aggregator.ingest(tick).status() == IngestStatus.ACCEPTED) {
                            accepted.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        System.out.println("[多周期并发] 输入: 150 笔 x " + threads + " 线程乱序重复提交");
        System.out.println("[多周期并发] 判定依据: 每笔在每个周期只计一次; 三个周期结果均与串行完全一致");
        assertEquals(ticks.size(), accepted.get(), "每笔成交只能被接受一次");

        for (long period : new long[]{M1, M5, M15}) {
            List<Kline> expected = serial.klines(SYMBOL, period);
            List<Kline> actual = aggregator.klines(SYMBOL, period);
            System.out.println("[多周期并发] period=" + period + " 串行=" + expected);
            System.out.println("[多周期并发] period=" + period + " 并发=" + actual);
            assertEquals(expected, actual, "period=" + period + " 并发结果必须与串行一致且可复现");
        }
    }

    @Test
    void unconfiguredPeriodIsRejectedDistinctly() {
        aggregator.ingest(tick("U0", 100.0, 1.0, base));
        System.out.println("[周期校验] 输入: 查询未配置周期 120000ms");
        System.out.println("[周期校验] 判定依据: 未配置周期必须抛出 UNSUPPORTED_WINDOW_SIZE, 可区分");
        var ex = assertThrows(com.github.lisama569.tickklineaggregatorjava.exception.TickRejectedException.class,
                () -> aggregator.klineAt(SYMBOL, 120_000L, base));
        System.out.println("[周期校验] 实际: reason=" + ex.getReason());
        assertEquals(com.github.lisama569.tickklineaggregatorjava.model.RejectReason.UNSUPPORTED_WINDOW_SIZE,
                ex.getReason());
    }
}
