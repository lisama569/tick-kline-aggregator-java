package com.github.lisama569.tickklineaggregatorjava.tests;

import com.github.lisama569.tickklineaggregatorjava.config.KlineProperties;
import com.github.lisama569.tickklineaggregatorjava.exception.TickRejectedException;
import com.github.lisama569.tickklineaggregatorjava.model.IngestResult;
import com.github.lisama569.tickklineaggregatorjava.model.IngestStatus;
import com.github.lisama569.tickklineaggregatorjava.model.Kline;
import com.github.lisama569.tickklineaggregatorjava.model.KlineRangeEntry;
import com.github.lisama569.tickklineaggregatorjava.model.RejectReason;
import com.github.lisama569.tickklineaggregatorjava.model.TradeTick;
import com.github.lisama569.tickklineaggregatorjava.model.WindowStatus;
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
 * 带窗口上限的多周期聚合回收一致性测试：
 * 回收后保留的大周期窗口必须能由仍然保留的小周期窗口完整对上；
 * 凑不齐小周期数据的大周期窗口，单点查询/范围查询/窗口列表都必须明确不可用；
 * 回收后重复上报仍稳定判 DUPLICATE；并发上报与串行结论一致。
 * 每个用例打印输入与判定依据。
 */
class MultiPeriodRetentionTests {

    private static final long M1 = 60_000L;
    private static final long M5 = 300_000L;
    private static final int MAX_WINDOWS = 10;
    private static final int TOTAL_1M_WINDOWS = 22; // 连续上报 22 个 1m 窗口 > 上限 10
    private static final String SYMBOL = "BTCUSDT";

    private KlineAggregator aggregator;
    private long base; // 对齐到 5 分钟边界

    @BeforeEach
    void setUp() {
        KlineProperties props = new KlineProperties();
        props.setWindowSizeMs(M1);
        props.setAdditionalWindowSizesMs(List.of(M5));
        props.setMaxWindowsPerSymbol(MAX_WINDOWS);
        aggregator = new KlineAggregator(props);
        base = Math.floorDiv(System.currentTimeMillis(), M5) * M5;
    }

    private TradeTick tick(String id, int windowIndex) {
        // 每个 1m 窗口一笔成交，价格/数量由窗口序号确定
        return new TradeTick(id, SYMBOL, 100.0 + windowIndex, 1.0 + windowIndex % 3,
                base + windowIndex * M1 + 100);
    }

    /** 连续上报 TOTAL_1M_WINDOWS 个 1m 窗口的成交（每窗一笔，id=T{i}）。 */
    private void fillWindows(KlineAggregator agg) {
        for (int i = 0; i < TOTAL_1M_WINDOWS; i++) {
            assertEquals(IngestStatus.ACCEPTED, agg.ingest(tick("T" + i, i)).status());
        }
    }

    @Test
    void retainedLargePeriodWindowsAreFullyCoveredByRetainedSmallOnes() {
        fillWindows(aggregator);
        // 1m 保留 w12..w21（10 个），覆盖水位线 = base+12m
        long watermark = base + 12 * M1;

        List<Kline> small = aggregator.klines(SYMBOL, M1);
        List<Kline> big = aggregator.klines(SYMBOL, M5);
        System.out.println("[跨周期回收] 输入: 1m+5m 双周期, 每周期上限 " + MAX_WINDOWS
                + " 窗, 连续上报 " + TOTAL_1M_WINDOWS + " 个 1m 窗口的成交");
        System.out.println("[跨周期回收] 判定依据: 1m 仅保留 w12..w21; 5m 窗口起点 < base+12m 的"
                + "因小周期数据不齐不可用, 不得出现在窗口列表; 保留的 5m 窗口必须能由保留的 1m 窗口完整对上");
        System.out.println("[跨周期回收] 1m 保留窗口数=" + small.size()
                + " 首窗=" + small.get(0).windowStart() + "(水位线=" + watermark + ")");
        System.out.println("[跨周期回收] 5m 可用窗口=" + big);

        assertEquals(MAX_WINDOWS, small.size());
        assertEquals(watermark, small.get(0).windowStart(), "1m 最旧保留窗口必须正好在水位线上");

        // 5m 窗口 F0..F2(起点 base..base+10m) 覆盖不齐 -> 不可用; F3(base+15m), F4(base+20m) 可用
        assertEquals(2, big.size(), "只有能由保留 1m 窗口完整覆盖的 5m 窗口才允许出现");
        assertEquals(base + 15 * M1, big.get(0).windowStart());
        assertEquals(base + 20 * M1, big.get(1).windowStart());

        // 保留的 5m 窗口与保留的 1m 窗口严格对齐（逐字段合成核对）
        for (Kline k5 : big) {
            List<Kline> covered = small.stream()
                    .filter(k -> k.windowStart() >= k5.windowStart() && k.windowStart() < k5.windowEnd())
                    .toList();
            double expVol = covered.stream().mapToDouble(Kline::volume).sum();
            long expCount = covered.stream().mapToLong(Kline::tradeCount).sum();
            double expHigh = covered.stream().mapToDouble(Kline::high).max().orElseThrow();
            double expLow = covered.stream().mapToDouble(Kline::low).min().orElseThrow();
            System.out.println("[跨周期回收] 5m 窗口 " + k5.windowStart() + ": 覆盖 1m 窗口 "
                    + covered.size() + " 个, 期望 vol=" + expVol + " count=" + expCount
                    + " 实际 vol=" + k5.volume() + " count=" + k5.tradeCount());
            assertEquals(expVol, k5.volume(), 1e-9, "5m 成交量必须等于覆盖的保留 1m 窗口之和");
            assertEquals(expCount, k5.tradeCount(), "5m 笔数必须等于覆盖的保留 1m 窗口之和");
            assertEquals(expHigh, k5.high());
            assertEquals(expLow, k5.low());
        }
    }

    @Test
    void uncoveredLargePeriodWindowsAreExplicitlyUnavailable() {
        fillWindows(aggregator);

        // 单点查询：覆盖不齐的 5m 窗口必须显式拒绝，而不是按正常数据返回
        System.out.println("[大周期不可用] 输入: 查询 5m 窗口 F0(base) 与 F2(base+10m), 其覆盖的 1m 窗口已部分回收");
        System.out.println("[大周期不可用] 判定依据: 单点查询必须以 WINDOW_EVICTED 显式告知不可用");
        for (long ws : new long[]{base, base + 5 * M1, base + 10 * M1}) {
            TickRejectedException ex = assertThrows(TickRejectedException.class,
                    () -> aggregator.klineAt(SYMBOL, M5, ws));
            System.out.println("[大周期不可用] 5m@" + ws + " -> " + ex.getReason());
            assertEquals(RejectReason.WINDOW_EVICTED, ex.getReason());
        }
        // 覆盖完整的 5m 窗口正常返回
        Kline f3 = aggregator.klineAt(SYMBOL, M5, base + 15 * M1).orElseThrow();
        assertEquals(5, f3.tradeCount());
        System.out.println("[大周期不可用] 5m@" + (base + 15 * M1) + "(覆盖完整) -> FILLED " + f3);

        // 范围查询：覆盖不齐的槽位必须标 UNAVAILABLE，覆盖完整的标 FILLED
        List<KlineRangeEntry> range = aggregator.klinesRange(SYMBOL, M5, base, base + 25 * M1);
        System.out.println("[大周期不可用] 5m 范围查询 [base, base+25m):");
        for (KlineRangeEntry e : range) {
            System.out.println("[大周期不可用]   slot [" + e.windowStart() + ") " + e.status());
        }
        assertEquals(5, range.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(WindowStatus.UNAVAILABLE, range.get(i).status(),
                    "覆盖不齐的 5m 槽位必须 UNAVAILABLE, 不得当作正常 FILLED 返回");
            assertNull(range.get(i).kline());
        }
        assertEquals(WindowStatus.FILLED, range.get(3).status());
        assertEquals(WindowStatus.FILLED, range.get(4).status());

        // 1m 侧范围查询语义不回归：已回收 UNAVAILABLE / 保留 FILLED / 无成交 EMPTY
        List<KlineRangeEntry> small = aggregator.klinesRange(SYMBOL, M1, base + 11 * M1, base + 23 * M1);
        assertEquals(WindowStatus.UNAVAILABLE, small.get(0).status(), "w11 已回收");
        for (int i = 1; i <= 10; i++) {
            assertEquals(WindowStatus.FILLED, small.get(i).status(), "w" + (11 + i) + " 保留且有成交");
        }
        assertEquals(WindowStatus.EMPTY, small.get(11).status(), "w22 保留范围内无成交");
    }

    @Test
    void duplicateAfterEvictionStaysDuplicateAndDoesNotResurrect() {
        fillWindows(aggregator);
        List<Kline> smallBefore = aggregator.klines(SYMBOL, M1);
        List<Kline> bigBefore = aggregator.klines(SYMBOL, M5);

        // T0 曾成功计入 w0（已回收）；T15 曾成功计入 w15（仍保留）
        System.out.println("[回收后重复] 输入: 重复上报已回收窗口的 T0 与保留窗口的 T15");
        System.out.println("[回收后重复] 判定依据: 无论窗口是否回收都稳定判 DUPLICATE; "
                + "已回收窗口不得复生, 保留范围与结果不得改变");
        IngestResult r0 = aggregator.ingest(tick("T0", 0));
        IngestResult r15 = aggregator.ingest(tick("T15", 15));
        System.out.println("[回收后重复] T0(已回收窗口) -> " + r0.status()
                + ", T15(保留窗口) -> " + r15.status());
        assertEquals(IngestStatus.DUPLICATE, r0.status());
        assertEquals(IngestStatus.DUPLICATE, r15.status());

        // 已回收窗口不得复生：单点查询仍然明确不可用
        TickRejectedException ex = assertThrows(TickRejectedException.class,
                () -> aggregator.klineAt(SYMBOL, M1, base));
        assertEquals(RejectReason.WINDOW_EVICTED, ex.getReason());
        // 保留范围与两个周期的结果都不变
        assertEquals(smallBefore, aggregator.klines(SYMBOL, M1));
        assertEquals(bigBefore, aggregator.klines(SYMBOL, M5));

        // 对照：从未出现过的成交落入已回收窗口，仍按 WINDOW_EVICTED 拒绝（与重复提交可区分）
        TradeTick lateNew = new TradeTick("NEVER-SEEN", SYMBOL, 1.0, 1.0, base + 50);
        TickRejectedException late = assertThrows(TickRejectedException.class,
                () -> aggregator.ingest(lateNew));
        System.out.println("[回收后重复] 对照: 新成交落入已回收窗口 -> " + late.getReason());
        assertEquals(RejectReason.WINDOW_EVICTED, late.getReason());
    }

    @Test
    void concurrentResubmissionAfterEvictionMatchesSerial() throws InterruptedException {
        fillWindows(aggregator);

        // 串行期望：全新实例重放同样 22 笔，再串行重复上报一遍
        KlineAggregator serial = new KlineAggregator(propsCopy());
        fillWindows(serial);
        for (int i = 0; i < TOTAL_1M_WINDOWS; i++) {
            assertEquals(IngestStatus.DUPLICATE, serial.ingest(tick("T" + i, i)).status());
        }

        // 并发：8 个线程各自乱序重复上报全部 22 笔（含已回收窗口的成交）
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger duplicated = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            List<TradeTick> shuffled = new ArrayList<>();
            for (int i = 0; i < TOTAL_1M_WINDOWS; i++) {
                shuffled.add(tick("T" + i, i));
            }
            Collections.shuffle(shuffled, new java.util.Random(t));
            pool.submit(() -> {
                try {
                    start.await();
                    for (TradeTick tick : shuffled) {
                        IngestStatus s = aggregator.ingest(tick).status();
                        if (s == IngestStatus.ACCEPTED) {
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

        System.out.println("[并发重复] 输入: 22 笔已接受成交(部分窗口已回收) x " + threads + " 线程乱序重复上报");
        System.out.println("[并发重复] 判定依据: 全部判 DUPLICATE, 无一 ACCEPTED; "
                + "两个周期结果与串行重放完全一致; 已回收窗口不复生");
        System.out.println("[并发重复] 实际: ACCEPTED=" + accepted.get() + " DUPLICATE=" + duplicated.get());
        assertEquals(0, accepted.get(), "回收后并发重复上报不得有任何一笔被再次计入");
        assertEquals(threads * TOTAL_1M_WINDOWS, duplicated.get());

        assertEquals(serial.klines(SYMBOL, M1), aggregator.klines(SYMBOL, M1),
                "1m 并发结果必须与串行一致");
        assertEquals(serial.klines(SYMBOL, M5), aggregator.klines(SYMBOL, M5),
                "5m 并发结果必须与串行一致");
        TickRejectedException ex = assertThrows(TickRejectedException.class,
                () -> aggregator.klineAt(SYMBOL, M1, base));
        assertEquals(RejectReason.WINDOW_EVICTED, ex.getReason(), "已回收窗口不得因并发重复上报而复生");
    }

    private static KlineProperties propsCopy() {
        KlineProperties props = new KlineProperties();
        props.setWindowSizeMs(M1);
        props.setAdditionalWindowSizesMs(List.of(M5));
        props.setMaxWindowsPerSymbol(MAX_WINDOWS);
        return props;
    }
}
