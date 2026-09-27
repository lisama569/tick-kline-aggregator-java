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
 * 带窗口上限的多周期聚合测试：
 * 回收后大周期窗口必须仍能由保留的小周期完整对上；凑不齐时单点查询以
 * WINDOW_UNVERIFIABLE 拒绝、范围查询标注 UNVERIFIABLE，绝不返回“看起来正常”的大周期结果；
 * 已回收窗口的成交重复上报在串行/并发下都稳定判 DUPLICATE 且不改变保留范围。
 * 每个用例打印输入与判定依据。
 */
class MultiPeriodRetentionTests {

    private static final long M1 = 60_000L;
    private static final long M5 = 300_000L;
    private static final int MAX_WINDOWS = 10;
    private static final String SYMBOL = "BTCUSDT";

    private long t0; // 对齐到 5m 边界

    /** 1m+5m、每周期最多保留 10 窗的聚合器。 */
    private KlineAggregator newAggregator() {
        KlineProperties props = new KlineProperties();
        props.setWindowSizeMs(M1);
        props.setAdditionalWindowSizesMs(List.of(M5));
        props.setMaxWindowsPerSymbol(MAX_WINDOWS);
        return new KlineAggregator(props);
    }

    @BeforeEach
    void setUp() {
        t0 = Math.floorDiv(System.currentTimeMillis(), M5) * M5;
    }

    private TradeTick tick(int i) {
        // 第 i 个 1m 窗口中的一笔：price/qty 由 i 确定，便于核对大小周期求和
        return new TradeTick("T" + i, SYMBOL, 100.0 + i, 1.0 + (i % 3), t0 + i * M1 + 100);
    }

    /** 连续上报 20 个 1m 窗口各一笔（20 > 上限 10）。 */
    private List<TradeTick> feedTwentyWindows(KlineAggregator agg) {
        List<TradeTick> ticks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            TradeTick t = tick(i);
            ticks.add(t);
            IngestResult r = agg.ingest(t);
            assertEquals(IngestStatus.ACCEPTED, r.status());
        }
        return ticks;
    }

    @Test
    void evictedLargePeriodWindowsAreReportedUnverifiableNotAsNormalResults() {
        KlineAggregator agg = newAggregator();
        feedTwentyWindows(agg);

        System.out.println("[多周期回收] 输入: 1m+5m, 每周期上限 " + MAX_WINDOWS
                + " 窗, 连续上报 20 个 1m 窗口各一笔");
        System.out.println("[多周期回收] 判定依据: 1m 只保留 w10..w19(水位线=t0+10m), "
                + "5m 窗口 [0,5)/[5,10) 覆盖的 1m 已全部回收 -> 不可信; "
                + "[10,15)/[15,20) 仍能由保留的 1m 完整对上 -> 正常返回");

        // 1) 1m 侧：w0..w9 已回收，w10..w19 保留
        List<Kline> m1 = agg.klines(SYMBOL, M1);
        assertEquals(10, m1.size());
        assertEquals(t0 + 10 * M1, m1.get(0).windowStart());
        assertEquals(t0 + 19 * M1, m1.get(9).windowStart());

        // 2) 列表查询只返回能对上的 5m 窗口（最后两个）
        List<Kline> m5List = agg.klines(SYMBOL, M5);
        System.out.println("[多周期回收] 5m 列表查询返回: " + m5List.stream().map(Kline::windowStart).toList());
        assertEquals(2, m5List.size());
        assertEquals(t0 + 10 * M1, m5List.get(0).windowStart());
        assertEquals(t0 + 15 * M1, m5List.get(1).windowStart());

        // 3) 范围查询：前两个 5m 槽位 UNVERIFIABLE（有聚合值但凑不齐小周期），
        //    后两个 FILLED
        List<KlineRangeEntry> m5Range = agg.klinesRange(SYMBOL, M5, t0, t0 + 20 * M1);
        for (KlineRangeEntry e : m5Range) {
            System.out.println("[多周期回收] 5m 槽位 start=" + e.windowStart() + " status=" + e.status());
        }
        assertEquals(4, m5Range.size());
        assertEquals(WindowStatus.UNVERIFIABLE, m5Range.get(0).status());
        assertEquals(WindowStatus.UNVERIFIABLE, m5Range.get(1).status());
        assertEquals(WindowStatus.FILLED, m5Range.get(2).status());
        assertEquals(WindowStatus.FILLED, m5Range.get(3).status());
        assertNull(m5Range.get(0).kline(), "不可信槽位不得携带 K 线，防止被当成正常结果");

        // 4) 单点查询：不可信 5m 窗口以可区分原因明确拒绝
        TickRejectedException ex = assertThrows(TickRejectedException.class,
                () -> agg.klineAt(SYMBOL, M5, t0 + 50));
        System.out.println("[多周期回收] 不可信 5m 单点查询实际 reason=" + ex.getReason()
                + ", message=" + ex.getMessage());
        assertEquals(RejectReason.WINDOW_UNVERIFIABLE, ex.getReason());
    }

    @Test
    void retainedLargePeriodWindowsReconcileExactlyWithRetainedSmallPeriods() {
        KlineAggregator agg = newAggregator();
        feedTwentyWindows(agg);

        // 每个“仍可对上”的 5m 窗口：笔数/成交量必须等于覆盖的全部 1m 保留窗之和
        for (int big = 2; big < 4; big++) {
            long bigStart = t0 + big * 5 * M1;
            Kline k5 = agg.klineAt(SYMBOL, M5, bigStart).orElseThrow();
            double vol = 0;
            long count = 0;
            double high = Double.NEGATIVE_INFINITY;
            double low = Double.POSITIVE_INFINITY;
            for (int j = 0; j < 5; j++) {
                Kline k1 = agg.klineAt(SYMBOL, M1, bigStart + j * M1).orElseThrow();
                vol += k1.volume();
                count += k1.tradeCount();
                high = Math.max(high, k1.high());
                low = Math.min(low, k1.low());
            }
            System.out.println("[多周期对齐] 5m@" + bigStart + " 实际 count=" + k5.tradeCount()
                    + " vol=" + k5.volume() + " high=" + k5.high() + " low=" + k5.low()
                    + " ; 覆盖 1m 求和 count=" + count + " vol=" + vol + " high=" + high + " low=" + low);
            assertEquals(count, k5.tradeCount(), "大周期笔数必须等于覆盖的保留小周期笔数之和");
            assertEquals(vol, k5.volume(), 1e-9, "大周期成交量必须等于覆盖的保留小周期成交量之和");
            assertEquals(high, k5.high(), 1e-9);
            assertEquals(low, k5.low(), 1e-9);
        }

        // 临界点：起点恰好等于 1m 回收水位线的 5m 窗口仍可对上（水位线为左闭边界）
        Kline boundary = agg.klineAt(SYMBOL, M5, t0 + 10 * M1).orElseThrow();
        assertEquals(5, boundary.tradeCount());
        System.out.println("[多周期对齐] 临界判定: 5m 起点=t0+10m 恰为 1m 水位线, 仍可对上 count="
                + boundary.tradeCount());
    }

    @Test
    void duplicateResubmitAfterEvictionIsStableAndKeepsRetentionRange() {
        KlineAggregator agg = newAggregator();
        List<TradeTick> ticks = feedTwentyWindows(agg);

        // w0..w4 的成交所属 1m 窗口已回收；逐笔再次上报，必须全部判 DUPLICATE
        for (int i = 0; i < 5; i++) {
            IngestResult r = agg.ingest(ticks.get(i));
            System.out.println("[回收后重复] tickId=T" + i + " 再次上报 -> " + r.status());
            assertEquals(IngestStatus.DUPLICATE, r.status(),
                    "已接受过的成交无论窗口是否回收，重复上报稳定判 DUPLICATE");
        }

        // 重复上报不得让已回收窗口复生，也不得改变保留范围（5m 仍只有后两个窗口可查）
        assertTrue(agg.klineAt(SYMBOL, M1, t0).isEmpty(), "已回收 1m 窗口不得复生");
        assertEquals(2, agg.klines(SYMBOL, M5).size(), "5m 保留/可信范围不得被重复上报改变");
        List<KlineRangeEntry> m5Range = agg.klinesRange(SYMBOL, M5, t0, t0 + 20 * M1);
        assertEquals(WindowStatus.UNVERIFIABLE, m5Range.get(0).status());
        assertEquals(WindowStatus.FILLED, m5Range.get(3).status());
    }

    @Test
    void concurrentResubmitAfterEvictionMatchesSerialAndNeverChangesResult()
            throws InterruptedException {
        // 串行参照：先接受全部 20 笔（触发回收），再串行重复上报一遍 -> 全部 DUPLICATE，状态不变
        KlineAggregator serial = newAggregator();
        List<TradeTick> ticks = feedTwentyWindows(serial);
        List<Kline> serialM1 = serial.klines(SYMBOL, M1);
        List<Kline> serialM5 = serial.klines(SYMBOL, M5);
        for (TradeTick t : ticks) {
            assertEquals(IngestStatus.DUPLICATE, serial.ingest(t).status());
        }
        assertEquals(serialM1, serial.klines(SYMBOL, M1));
        assertEquals(serialM5, serial.klines(SYMBOL, M5));

        // 并发：4 个线程各自乱序重复提交同一批 20 笔
        KlineAggregator concurrent = newAggregator();
        feedTwentyWindows(concurrent);
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger duplicates = new AtomicInteger();
        AtomicInteger nonDuplicates = new AtomicInteger();
        for (int th = 0; th < threads; th++) {
            List<TradeTick> shuffled = new ArrayList<>(ticks);
            Collections.shuffle(shuffled, new java.util.Random(th));
            pool.submit(() -> {
                try {
                    start.await();
                    for (TradeTick t : shuffled) {
                        IngestResult r = concurrent.ingest(t);
                        if (r.status() == IngestStatus.DUPLICATE) {
                            duplicates.incrementAndGet();
                        } else {
                            nonDuplicates.incrementAndGet();
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

        System.out.println("[回收后并发重复] 输入: 20 笔(已触发回收) x " + threads
                + " 线程乱序重复提交, 共 " + (20 * threads) + " 次");
        System.out.println("[回收后并发重复] 判定依据: 全部必须判 DUPLICATE(不得出现 ACCEPTED/WINDOW_EVICTED), "
                + "且两个周期最终结果与串行完全一致, 已回收窗口不复生");
        System.out.println("[回收后并发重复] DUPLICATE=" + duplicates.get() + " 非DUPLICATE=" + nonDuplicates.get());

        assertEquals(20 * threads, duplicates.get(), "每一次重复上报都必须稳定判为 DUPLICATE");
        assertEquals(0, nonDuplicates.get(), "不得出现重复之外的任何结论");
        assertEquals(serialM1, concurrent.klines(SYMBOL, M1), "1m 结果必须与串行一致");
        assertEquals(serialM5, concurrent.klines(SYMBOL, M5), "5m 可信窗口必须与串行一致");
        assertTrue(concurrent.klineAt(SYMBOL, M1, t0).isEmpty(), "已回收窗口不得因并发重复上报复生");
    }
}
