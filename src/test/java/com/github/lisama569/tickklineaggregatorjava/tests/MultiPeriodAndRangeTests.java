package com.github.lisama569.tickklineaggregatorjava.tests;

import com.github.lisama569.tickklineaggregatorjava.config.KlineProperties;
import com.github.lisama569.tickklineaggregatorjava.exception.TickRejectedException;
import com.github.lisama569.tickklineaggregatorjava.model.IngestResult;
import com.github.lisama569.tickklineaggregatorjava.model.IngestStatus;
import com.github.lisama569.tickklineaggregatorjava.model.Kline;
import com.github.lisama569.tickklineaggregatorjava.model.KlineRange;
import com.github.lisama569.tickklineaggregatorjava.model.RangeKline;
import com.github.lisama569.tickklineaggregatorjava.model.RangeWindowState;
import com.github.lisama569.tickklineaggregatorjava.model.RejectReason;
import com.github.lisama569.tickklineaggregatorjava.model.TradeTick;
import com.github.lisama569.tickklineaggregatorjava.service.KlineAggregator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多周期聚合与时间范围查询测试：
 * 跨周期对齐、每笔每周期只计一次、边界唯一归属、并发与串行一致；
 * 范围查询的 HAS_DATA/EMPTY/NOT_RETAINED 可区分判定与一致视图。
 * 每个用例打印输入与判定依据。
 */
class MultiPeriodAndRangeTests {

    private static final long SMALL = 60_000L;
    private static final long BIG = 300_000L;

    private KlineAggregator aggregator;
    private long base; // 同时对齐大、小周期起点

    @BeforeEach
    void setUp() {
        KlineProperties props = new KlineProperties();
        props.setWindowSizeMs(SMALL);
        props.setWindowSizesMs(List.of(SMALL, BIG));
        aggregator = new KlineAggregator(props);
        long now = System.currentTimeMillis();
        base = Math.floorDiv(now, BIG) * BIG;
    }

    private TradeTick tick(String id, double price, double qty, long ts) {
        return new TradeTick(id, "BTCUSDT", price, qty, ts);
    }

    @Test
    void periodsAreIndependentAndBigWindowExactlyComposesSmallWindows() {
        // 输入：一个大周期(5分钟)内的 6 笔成交，小周期分布为 w0(2笔) w1(1) w2(1) w3(空) w4(2)
        List<TradeTick> ticks = List.of(
                tick("t0", 100.0, 1.0, base),
                tick("t1", 110.0, 2.0, base + SMALL - 1),
                tick("t2", 90.0, 3.0, base + SMALL),
                tick("t3", 120.0, 4.0, base + 2 * SMALL),
                tick("t4", 95.0, 5.0, base + 4 * SMALL),
                tick("t5", 130.0, 6.0, base + BIG - 1));
        ticks.forEach(aggregator::ingest);

        List<Kline> smalls = aggregator.klines("BTCUSDT", SMALL);
        Kline big = aggregator.klineAt("BTCUSDT", base, BIG).orElseThrow();

        System.out.println("[多周期对齐] 输入: " + ticks.size() + " 笔成交, 周期 " + SMALL + "ms/" + BIG + "ms");
        System.out.println("[多周期对齐] 判定依据: 每笔在每个周期只计一次; 大周期 OHLCV/笔数 == 覆盖小周期合成");
        System.out.println("[多周期对齐] 小周期窗口数=" + smalls.size() + " (中间空窗不落库), 大周期=" + big);

        // 小周期：只应有 4 个有数据窗口（空窗不存储）
        assertEquals(4, smalls.size());
        long smallCountSum = smalls.stream().mapToLong(Kline::tradeCount).sum();
        double smallVolumeSum = smalls.stream().mapToDouble(Kline::volume).sum();
        assertEquals(6, smallCountSum, "6 笔成交在小周期合计只能计 6 次");
        assertEquals(21.0, smallVolumeSum, 1e-9);

        // 大周期与“逐笔期望”一致（open 最早时间戳、close 最晚时间戳）
        assertAll(
                () -> assertEquals(6, big.tradeCount(), "6 笔在大周期也只能计 6 次"),
                () -> assertEquals(21.0, big.volume(), 1e-9),
                () -> assertEquals(90.0, big.low(), 1e-9),
                () -> assertEquals(130.0, big.high(), 1e-9),
                () -> assertEquals(100.0, big.open(), 1e-9, "open=最早时间戳成交 t0"),
                () -> assertEquals(130.0, big.close(), 1e-9, "close=最晚时间戳成交 t5"),
                () -> assertEquals(base, big.windowStart()),
                () -> assertEquals(base + BIG, big.windowEnd()));

        // 大周期必须与覆盖的小周期严格合成
        Kline firstSmall = smalls.get(0);
        Kline lastSmall = smalls.get(smalls.size() - 1);
        assertAll(
                () -> assertEquals(smalls.stream().mapToDouble(Kline::high).max().orElseThrow(),
                        big.high(), 1e-9, "大周期最高=小周期最高之最大"),
                () -> assertEquals(smalls.stream().mapToDouble(Kline::low).min().orElseThrow(),
                        big.low(), 1e-9, "大周期最低=小周期最低之最小"),
                () -> assertEquals(smallVolumeSum, big.volume(), 1e-9, "大周期量=小周期量之和"),
                () -> assertEquals(smallCountSum, big.tradeCount(), "大周期笔数=小周期笔数之和"),
                () -> assertEquals(firstSmall.open(), big.open(), 1e-9, "大周期开=首个非空小周期开"),
                () -> assertEquals(lastSmall.close(), big.close(), 1e-9, "大周期收=末个非空小周期收"));

        // IngestResult 同时回报两个周期各自的窗口起点，同一笔在每个周期只落一个窗口
        IngestResult extra = aggregator.ingest(tick("t6", 101.0, 1.0, base + 3 * SMALL + 1));
        assertEquals(2, extra.windowStarts().size());
        assertEquals(base + 3 * SMALL, extra.windowStarts().get(SMALL));
        assertEquals(base, extra.windowStarts().get(BIG));
        System.out.println("[多周期对齐] t6 回报窗口: " + extra.windowStarts());
    }

    @Test
    void boundaryTickBelongsToExactlyOneWindowInEveryPeriod() {
        TradeTick atBigStart = tick("bd1", 100.0, 1.0, base);          // 同时是大、小周期起点
        TradeTick justBefore = tick("bd2", 200.0, 2.0, base - 1);     // 归前一大周期与前一小周期
        aggregator.ingest(atBigStart);
        aggregator.ingest(justBefore);

        Kline bigCur = aggregator.klineAt("BTCUSDT", base, BIG).orElseThrow();
        Kline bigPrev = aggregator.klineAt("BTCUSDT", base - 1, BIG).orElseThrow();
        Kline smallCur = aggregator.klineAt("BTCUSDT", base, SMALL).orElseThrow();
        Kline smallPrev = aggregator.klineAt("BTCUSDT", base - 1, SMALL).orElseThrow();

        System.out.println("[多周期边界] 输入: ts=" + base + "(大周期起点), ts=" + (base - 1) + "(前一毫秒)");
        System.out.println("[多周期边界] 判定依据: 左闭右开, 起点成交在大/小周期都只归本窗口");
        System.out.println("[多周期边界] 大周期当前笔数=" + bigCur.tradeCount() + ", 前一窗口笔数=" + bigPrev.tradeCount()
                + "; 小周期当前笔数=" + smallCur.tradeCount() + ", 前一窗口笔数=" + smallPrev.tradeCount());

        assertEquals(1, bigCur.tradeCount());
        assertEquals(1, bigPrev.tradeCount());
        assertEquals(1, smallCur.tradeCount());
        assertEquals(1, smallPrev.tradeCount());
    }

    @Test
    void concurrentMultiPeriodIngestMatchesSerialAndCountsOnce() throws InterruptedException {
        int n = 200;
        int threads = 8;
        List<TradeTick> ticks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            // 分散在一个大周期内的 5 个小周期；同小周期内用 i 保证时间戳互不相同
            long ts = base + (i % 5) * SMALL + (i / 5) * 200L + 1;
            assertTrue(ts < base + BIG, "测试数据必须落在同一个大周期内");
            ticks.add(tick("M" + i, 100.0 + i, 1.0 + (i % 5), ts));
        }
        KlineProperties props = new KlineProperties();
        props.setWindowSizeMs(SMALL);
        props.setWindowSizesMs(List.of(SMALL, BIG));
        KlineAggregator serial = new KlineAggregator(props);
        ticks.forEach(serial::ingest);
        List<Kline> expectedSmall = serial.klines("BTCUSDT", SMALL);
        Kline expectedBig = serial.klineAt("BTCUSDT", base, BIG).orElseThrow();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger duplicated = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            List<TradeTick> shuffled = new ArrayList<>(ticks);
            Collections.shuffle(shuffled);
            pool.submit(() -> {
                try {
                    start.await();
                    for (TradeTick tick : shuffled) {
                        IngestResult r = aggregator.ingest(tick);
                        if (r.status() == IngestStatus.ACCEPTED) {
                            accepted.incrementAndGet();
                        } else {
                            duplicated.incrementAndGet();
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

        List<Kline> actualSmall = aggregator.klines("BTCUSDT", SMALL);
        Kline actualBig = aggregator.klineAt("BTCUSDT", base, BIG).orElseThrow();
        System.out.println("[多周期并发] 输入: " + n + " 笔 x " + threads + " 线程重复提交");
        System.out.println("[多周期并发] 判定依据: 每笔每周期只计一次, 两个周期的最终结果都须与串行一致");
        System.out.println("[多周期并发] 接受=" + accepted.get() + ", 去重=" + duplicated.get()
                + ", 小周期窗口数=" + actualSmall.size() + ", 大周期笔数=" + actualBig.tradeCount());

        assertEquals(n, accepted.get());
        assertEquals(n * (threads - 1), duplicated.get());
        assertEquals(expectedSmall, actualSmall, "小周期并发结果必须与串行一致");
        assertEquals(expectedBig, actualBig, "大周期并发结果必须与串行一致");
        long smallCountSum = actualSmall.stream().mapToLong(Kline::tradeCount).sum();
        assertEquals(n, smallCountSum, "小周期合计笔数必须等于成交笔数");
        assertEquals(n, actualBig.tradeCount(), "大周期笔数必须等于成交笔数");
    }

    @Test
    void rangeQueryDistinguishesDataGapAndEvictedAndReportsConsistentBatch() {
        // 输入：两个有数据窗口，中间空一个窗口
        aggregator.ingest(tick("r1", 100.0, 1.0, base));
        aggregator.ingest(tick("r2", 120.0, 2.0, base + 2 * SMALL));

        KlineRange range = aggregator.klinesRange("BTCUSDT", SMALL, base, base + 3 * SMALL - 1);
        List<RangeKline> windows = range.windows();
        System.out.println("[范围] 输入: 成交@" + base + ", @" + (base + 2 * SMALL)
                + "; 查询 [" + base + ", " + (base + 3 * SMALL - 1) + "]");
        System.out.println("[范围] 判定依据: 有成交=HAS_DATA; 已观察跨度内无成交=EMPTY; 跨度外=NOT_RETAINED");
        System.out.println("[范围] 状态序列: "
                + windows.stream().map(w -> w.state().name()).toList()
                + ", oldest=" + range.oldestWindowStart() + ", newest=" + range.newestWindowStart());

        assertEquals(3, windows.size());
        assertEquals(RangeWindowState.HAS_DATA, windows.get(0).state());
        assertEquals(RangeWindowState.EMPTY, windows.get(1).state(), "中间无成交窗口必须是确定性 EMPTY");
        assertEquals(RangeWindowState.HAS_DATA, windows.get(2).state());
        assertNull(windows.get(1).kline(), "空窗不携带 K 线数据");
        assertEquals(base, windows.get(0).windowStart());
        assertEquals(base + SMALL, windows.get(0).windowEnd(), "左闭右开");
        assertEquals(100.0, windows.get(0).kline().open());
        assertEquals(120.0, windows.get(2).kline().open());
        assertEquals(base, range.oldestWindowStart());
        assertEquals(base + 2 * SMALL, range.newestWindowStart());

        // 早于保留跨度：NOT_RETAINED，且能与 EMPTY 区分
        KlineRange before = aggregator.klinesRange("BTCUSDT", SMALL, base - 2 * SMALL, base - 1);
        assertEquals(2, before.windows().size());
        assertTrue(before.windows().stream().allMatch(w -> w.state() == RangeWindowState.NOT_RETAINED));
        assertEquals(base, before.oldestWindowStart(), "头部仍如实回报当前保留边界");
        System.out.println("[范围] 更早的两窗状态: "
                + before.windows().stream().map(w -> w.state().name()).toList());

        // 晚于最新窗口：NOT_RETAINED（不谎称空窗）
        KlineRange after = aggregator.klinesRange("BTCUSDT", SMALL, base + 3 * SMALL, base + 4 * SMALL - 1);
        assertEquals(RangeWindowState.NOT_RETAINED, after.windows().get(0).state());

        // 完全未知标的：全部 NOT_RETAINED，保留边界为 null（与“有跨度但空窗”区分）
        KlineRange unknown = aggregator.klinesRange("NOSUCH", SMALL, base, base + SMALL - 1);
        assertEquals(RangeWindowState.NOT_RETAINED, unknown.windows().get(0).state());
        assertNull(unknown.oldestWindowStart());
        assertNull(unknown.newestWindowStart());
    }

    @Test
    void rangeViewSnapshotsAreInternallyConsistentUnderConcurrentIngest() throws InterruptedException {
        int writers = 4;
        int perWriter = 300;
        ExecutorService pool = Executors.newFixedThreadPool(writers + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger violations = new AtomicInteger();
        for (int w = 0; w < writers; w++) {
            final int offset = w * perWriter;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perWriter; i++) {
                        aggregator.ingest(tick("V" + (offset + i), 50.0 + (i % 100),
                                1.0, base + (i % 10) * SMALL + (i * 100L) % 1000));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < 2_000; i++) {
                    KlineRange r = aggregator.klinesRange("BTCUSDT", SMALL, base, base + BIG - 1);
                    long prevEnd = Long.MIN_VALUE;
                    for (RangeKline rw : r.windows()) {
                        if (rw.windowStart() < prevEnd) {
                            violations.incrementAndGet(); // 窗口必须连续升序、不重叠
                        }
                        prevEnd = rw.windowEnd();
                        if (rw.state() == RangeWindowState.HAS_DATA) {
                            Kline k = rw.kline();
                            boolean ok = k.low() <= k.open() && k.open() <= k.high()
                                    && k.low() <= k.close() && k.close() <= k.high()
                                    && k.tradeCount() >= 1 && k.volume() >= k.tradeCount();
                            if (!ok) {
                                violations.incrementAndGet();
                                System.out.println("[范围并发] 半更新违规: " + k);
                            }
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        System.out.println("[范围并发] 判定依据: 写入中反复范围查询, 每个 HAS_DATA 快照必须内部自洽、窗口连续不重叠");
        System.out.println("[范围并发] 违规次数=" + violations.get());
        assertEquals(0, violations.get(), "范围查询不得读到半更新或撕裂的批次视图");
    }

    @Test
    void rejectsInvalidRangeAndUnknownPeriodWithDistinctReasons() {
        TickRejectedException badRange = assertThrows(TickRejectedException.class,
                () -> aggregator.klinesRange("BTCUSDT", SMALL, base + 10, base));
        TickRejectedException nonPositive = assertThrows(TickRejectedException.class,
                () -> aggregator.klinesRange("BTCUSDT", SMALL, 0, base));
        TickRejectedException unknownPeriod = assertThrows(TickRejectedException.class,
                () -> aggregator.klinesRange("BTCUSDT", 12_345L, base, base + SMALL - 1));
        TickRejectedException unknownPeriodAt = assertThrows(TickRejectedException.class,
                () -> aggregator.klineAt("BTCUSDT", base, 12_345L));

        System.out.println("[范围拒绝] 输入: from>to / from=0 / 未配置周期 12345ms");
        System.out.println("[范围拒绝] 判定依据: 分别期望 INVALID_RANGE / INVALID_RANGE / UNKNOWN_PERIOD");

        assertEquals(RejectReason.INVALID_RANGE, badRange.getReason());
        assertEquals(RejectReason.INVALID_RANGE, nonPositive.getReason());
        assertEquals(RejectReason.UNKNOWN_PERIOD, unknownPeriod.getReason());
        assertEquals(RejectReason.UNKNOWN_PERIOD, unknownPeriodAt.getReason());
    }

    @Test
    void rejectsNonAlignedPeriodConfiguration() {
        KlineProperties bad = new KlineProperties();
        bad.setWindowSizesMs(List.of(60_000L, 90_000L)); // 90000 不是 60000 的整数倍
        System.out.println("[配置] 输入: periods=60000,90000 -> 大周期无法由整数个小周期拼成, 必须启动即拒绝");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new KlineAggregator(bad));
        System.out.println("[配置] 拒绝信息: " + ex.getMessage());
    }
}
