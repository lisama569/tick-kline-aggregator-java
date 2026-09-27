package com.github.lisama569.tickklineaggregatorjava.tests;

import com.github.lisama569.tickklineaggregatorjava.config.KlineProperties;
import com.github.lisama569.tickklineaggregatorjava.exception.TickRejectedException;
import com.github.lisama569.tickklineaggregatorjava.model.KlineRangeEntry;
import com.github.lisama569.tickklineaggregatorjava.model.RejectReason;
import com.github.lisama569.tickklineaggregatorjava.model.TradeTick;
import com.github.lisama569.tickklineaggregatorjava.model.WindowStatus;
import com.github.lisama569.tickklineaggregatorjava.service.KlineAggregator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 范围查询测试：同一时刻一致视图、空窗口与无数据的区分、非法范围的可区分拒绝。
 * 每个用例打印输入与判定依据。
 */
class RangeQueryTests {

    private static final long M1 = 60_000L;
    private static final String SYMBOL = "ETHUSDT";

    private KlineAggregator aggregator;
    private long base;

    @BeforeEach
    void setUp() {
        KlineProperties props = new KlineProperties();
        props.setWindowSizeMs(M1);
        aggregator = new KlineAggregator(props);
        long now = System.currentTimeMillis();
        base = Math.floorDiv(now, M1) * M1;
    }

    private TradeTick tick(String id, double price, double qty, long ts) {
        return new TradeTick(id, SYMBOL, price, qty, ts);
    }

    @Test
    void rangeReturnsFilledAndEmptySlotsDeterministically() {
        // 在 5 个连续窗口中的第 0、2、4 个各放一笔，1、3 留空
        aggregator.ingest(tick("R0", 100.0, 1.0, base + 1_000));
        aggregator.ingest(tick("R2", 300.0, 3.0, base + 2 * M1 + 1_000));
        aggregator.ingest(tick("R4", 500.0, 5.0, base + 4 * M1 + 1_000));

        List<KlineRangeEntry> range = aggregator.klinesRange(SYMBOL, M1, base, base + 5 * M1);

        System.out.println("[范围] 输入: 窗口0/2/4 各一笔, 窗口1/3 无成交; 查询 [base, base+5m)");
        System.out.println("[范围] 判定依据: 有成交槽位 FILLED 且与单点查询一致; 保留范围内无成交槽位 EMPTY");
        for (KlineRangeEntry e : range) {
            System.out.println("[范围] slot [" + e.windowStart() + "," + e.windowEnd() + ") -> " + e.status()
                    + (e.kline() != null ? " " + e.kline() : ""));
        }

        assertEquals(5, range.size(), "范围必须覆盖 5 个槽位");
        assertEquals(WindowStatus.FILLED, range.get(0).status());
        assertEquals(WindowStatus.EMPTY, range.get(1).status());
        assertEquals(WindowStatus.FILLED, range.get(2).status());
        assertEquals(WindowStatus.EMPTY, range.get(3).status());
        assertEquals(WindowStatus.FILLED, range.get(4).status());

        assertNull(range.get(1).kline(), "EMPTY 槽位不得携带 K 线");
        assertEquals(300.0, range.get(2).kline().open(), "FILLED 槽位必须与单点查询一致");
        assertEquals(aggregator.klineAt(SYMBOL, base + 4 * M1).orElseThrow(), range.get(4).kline());
        // 槽位边界可预测：起点对齐窗口网格
        assertEquals(base, range.get(0).windowStart());
        assertEquals(base + M1, range.get(0).windowEnd());
    }

    @Test
    void unalignedRangeIsSnappedToWindowGrid() {
        aggregator.ingest(tick("G0", 100.0, 1.0, base + 1_000));
        aggregator.ingest(tick("G1", 200.0, 2.0, base + M1 + 1_000));
        // from/to 不对齐窗口边界：语义为“窗口起点落在 [from, to)”，槽位起点严格落在网格上
        List<KlineRangeEntry> range = aggregator.klinesRange(SYMBOL, M1, base + 500, base + 2 * M1 + 500);
        System.out.println("[网格] 输入: from=base+500, to=base+120s+500 (均不对齐)");
        System.out.println("[网格] 判定依据: 槽位=窗口起点落在 [from,to) 的窗口; "
                + "from 所在窗口(起点 base < from)被排除, 末槽起点 base+120s < to 被包含");
        for (KlineRangeEntry e : range) {
            System.out.println("[网格] slot [" + e.windowStart() + "," + e.windowEnd() + ") " + e.status());
        }
        assertEquals(2, range.size());
        assertEquals(base + M1, range.get(0).windowStart());
        assertEquals(base + 2 * M1, range.get(1).windowStart());
        assertEquals(WindowStatus.FILLED, range.get(0).status());
        assertEquals(WindowStatus.EMPTY, range.get(1).status());
    }

    @Test
    void unknownSymbolReportsUnavailableNotEmpty() {
        List<KlineRangeEntry> range = aggregator.klinesRange("NOSUCH", M1, base, base + 3 * M1);
        System.out.println("[无数据] 输入: 系统中从未出现的标的 NOSUCH");
        System.out.println("[无数据] 判定依据: 每个槽位必须标注 UNAVAILABLE, 与 EMPTY(有周期表但无成交) 可区分");
        for (KlineRangeEntry e : range) {
            System.out.println("[无数据] slot [" + e.windowStart() + "," + e.windowEnd() + ") " + e.status());
            assertEquals(WindowStatus.UNAVAILABLE, e.status());
            assertNull(e.kline());
        }
        assertEquals(3, range.size());
    }

    @Test
    void rangeIsConsistentViewUnderConcurrentWrites() throws InterruptedException {
        int windows = 50;
        // 写线程：按窗口顺序逐个窗口 ingest（每窗一笔），任何一致视图必须满足前缀性质：
        // 若第 j 个槽位 FILLED，则所有 i<j 的槽位也 FILLED。撕裂视图会违反该性质。
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger violations = new AtomicInteger();

        pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < windows; i++) {
                    aggregator.ingest(tick("W" + i, 100.0 + i, 1.0, base + i * M1 + 100));
                    Thread.sleep(1); // 留出读写交错窗口
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        pool.submit(() -> {
            try {
                start.await();
                for (int round = 0; round < 2_000; round++) {
                    List<KlineRangeEntry> range =
                            aggregator.klinesRange(SYMBOL, M1, base, base + (long) windows * M1);
                    boolean seenFilledAfter = false;
                    for (int i = range.size() - 1; i >= 0; i--) {
                        if (range.get(i).status() == WindowStatus.FILLED) {
                            seenFilledAfter = true;
                        } else if (seenFilledAfter && range.get(i).status() == WindowStatus.EMPTY) {
                            violations.incrementAndGet();
                            System.out.println("[一致视图] 撕裂: 槽位 " + i + " 为 EMPTY 但更晚槽位已 FILLED");
                            break;
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        List<KlineRangeEntry> finalRange = aggregator.klinesRange(SYMBOL, M1, base, base + (long) windows * M1);
        long filled = finalRange.stream().filter(e -> e.status() == WindowStatus.FILLED).count();
        System.out.println("[一致视图] 输入: 写线程按序写 " + windows + " 窗口, 读线程并发 2000 次范围查询");
        System.out.println("[一致视图] 判定依据: 任一批次必须满足前缀性质(无空洞), 即来自同一时刻视图");
        System.out.println("[一致视图] 撕裂次数=" + violations.get() + ", 最终 FILLED 槽位=" + filled);

        assertEquals(0, violations.get(), "范围查询不得返回部分窗口已更新、部分未更新的撕裂视图");
        assertEquals(windows, filled);
    }

    @Test
    void invalidRangeIsRejectedDistinctly() {
        aggregator.ingest(tick("X0", 100.0, 1.0, base + 100));

        System.out.println("[范围校验] 输入1: from >= to");
        var ex1 = assertThrows(TickRejectedException.class,
                () -> aggregator.klinesRange(SYMBOL, M1, base + M1, base));
        System.out.println("[范围校验] 实际1: reason=" + ex1.getReason());
        assertEquals(RejectReason.INVALID_RANGE, ex1.getReason());

        KlineProperties tiny = new KlineProperties();
        tiny.setWindowSizeMs(M1);
        tiny.setMaxRangeWindows(3);
        KlineAggregator limited = new KlineAggregator(tiny);
        System.out.println("[范围校验] 输入2: 跨度 10 窗口 > 上限 3");
        var ex2 = assertThrows(TickRejectedException.class,
                () -> limited.klinesRange(SYMBOL, M1, base, base + 10 * M1));
        System.out.println("[范围校验] 实际2: reason=" + ex2.getReason());
        assertEquals(RejectReason.INVALID_RANGE, ex2.getReason());

        System.out.println("[范围校验] 输入3: 未配置周期 7000ms");
        var ex3 = assertThrows(TickRejectedException.class,
                () -> aggregator.klinesRange(SYMBOL, 7_000L, base, base + M1));
        System.out.println("[范围校验] 实际3: reason=" + ex3.getReason());
        assertEquals(RejectReason.UNSUPPORTED_WINDOW_SIZE, ex3.getReason());
    }
}
