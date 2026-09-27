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

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 窗口保留与回收测试：超限自动回收最旧窗口、回收后迟到成交的可区分拒绝、
 * 回收不影响保留结果与幂等判定、多周期下不产生部分写入。
 * 每个用例打印输入与判定依据。
 */
class RetentionTests {

    private static final long M1 = 60_000L;
    private static final long M5 = 300_000L;
    private static final int MAX_WINDOWS = 3;
    private static final String SYMBOL = "BTCUSDT";

    private KlineAggregator aggregator;
    private long base;

    @BeforeEach
    void setUp() {
        KlineProperties props = new KlineProperties();
        props.setWindowSizeMs(M1);
        props.setMaxWindowsPerSymbol(MAX_WINDOWS);
        aggregator = new KlineAggregator(props);
        long now = System.currentTimeMillis();
        base = Math.floorDiv(now, M1) * M1;
    }

    private TradeTick tick(String id, double price, double qty, long ts) {
        return new TradeTick(id, SYMBOL, price, qty, ts);
    }

    /** 在窗口 i 中放一笔成交（id=E{i}, price=100+i）。 */
    private void fillWindow(int i) {
        aggregator.ingest(tick("E" + i, 100.0 + i, 1.0, base + i * M1 + 100));
    }

    @Test
    void oldestWindowsAreEvictedBeyondLimitAndRetainedOnesAreIntact() {
        for (int i = 0; i < 5; i++) {
            fillWindow(i); // 5 个窗口 > 上限 3 -> 最旧 2 个被回收
        }

        List<Kline> retained = aggregator.klines(SYMBOL);
        System.out.println("[回收] 输入: 上限=" + MAX_WINDOWS + ", 连续写入 5 个窗口的成交");
        System.out.println("[回收] 判定依据: 仅保留最新 3 个窗口(w2..w4), 最旧 2 个(w0,w1)被回收; 保留窗口数值不变");
        System.out.println("[回收] 保留窗口: " + retained);

        assertEquals(3, retained.size(), "超出上限后必须只保留最新 " + MAX_WINDOWS + " 个窗口");
        assertEquals(base + 2 * M1, retained.get(0).windowStart());
        assertEquals(base + 4 * M1, retained.get(2).windowStart());
        assertEquals(102.0, retained.get(0).open(), "保留窗口的聚合结果不得受回收影响");
        assertEquals(1, retained.get(0).tradeCount());

        // 新语义：已回收窗口的单点查询不再静默返回空，而是以 WINDOW_EVICTED 明确告知不可用
        TickRejectedException ev0 = assertThrows(TickRejectedException.class,
                () -> aggregator.klineAt(SYMBOL, base));
        assertEquals(RejectReason.WINDOW_EVICTED, ev0.getReason(), "已回收窗口单点查询必须明确告知不可用");
        TickRejectedException ev1 = assertThrows(TickRejectedException.class,
                () -> aggregator.klineAt(SYMBOL, base + M1));
        assertEquals(RejectReason.WINDOW_EVICTED, ev1.getReason());
        assertTrue(aggregator.klineAt(SYMBOL, base + 2 * M1).isPresent(), "保留窗口仍可查");
    }

    @Test
    void lateTickIntoEvictedWindowIsRejectedDistinctlyAndReproducibly() {
        for (int i = 0; i < 5; i++) {
            fillWindow(i); // w0, w1 已回收
        }
        List<Kline> before = aggregator.klines(SYMBOL);

        TradeTick late = tick("LATE-1", 999.0, 7.0, base + 50); // 落入已回收的 w0
        System.out.println("[迟到拒绝] 输入: 5 窗口写入后, 迟到成交 ts=" + (base + 50) + " 落入已回收窗口 w0");
        System.out.println("[迟到拒绝] 判定依据: 必须以 WINDOW_EVICTED 显式拒绝, 不生成残缺窗口、不改变保留结果");

        TickRejectedException ex = assertThrows(TickRejectedException.class, () -> aggregator.ingest(late));
        System.out.println("[迟到拒绝] 实际: reason=" + ex.getReason() + ", message=" + ex.getMessage());
        assertEquals(RejectReason.WINDOW_EVICTED, ex.getReason());

        // 可复现：同样的迟到成交再次提交，结论相同
        TickRejectedException ex2 = assertThrows(TickRejectedException.class, () -> aggregator.ingest(late));
        assertEquals(RejectReason.WINDOW_EVICTED, ex2.getReason(), "同一迟到成交重复提交结论必须一致");

        // 不生成残缺窗口、不影响保留结果（新语义：已回收窗口单点查询抛 WINDOW_EVICTED）
        TickRejectedException ev = assertThrows(TickRejectedException.class,
                () -> aggregator.klineAt(SYMBOL, base));
        assertEquals(RejectReason.WINDOW_EVICTED, ev.getReason(),
                "被拒绝的迟到成交不得生成残缺窗口，已回收窗口查询必须明确不可用");
        assertEquals(before, aggregator.klines(SYMBOL), "保留窗口结果不得被迟到成交改变");
    }

    @Test
    void idempotencySurvivesEviction() {
        for (int i = 0; i < 5; i++) {
            fillWindow(i);
        }
        // 保留窗口内的重复提交：仍然 DUPLICATE，不重复计入
        TradeTick dup = tick("E4", 104.0, 1.0, base + 4 * M1 + 100);
        IngestResult r1 = aggregator.ingest(dup);
        System.out.println("[回收幂等] 输入1: 重复提交保留窗口 w4 中已接受的 tickId=E4");
        System.out.println("[回收幂等] 判定依据: 去重表不随回收删除, 保留窗口内重复提交仍判 DUPLICATE");
        System.out.println("[回收幂等] 实际1: " + r1);
        assertEquals(IngestStatus.DUPLICATE, r1.status());
        assertEquals(1, aggregator.klineAt(SYMBOL, base + 4 * M1).orElseThrow().tradeCount(),
                "重复提交不得重复计入");

        // 已回收窗口中的重复提交：幂等判定先于回收检查，仍稳定判 DUPLICATE；
        // 不得复生已回收窗口、不得改变保留范围（本次修正后的新语义，原为 WINDOW_EVICTED）
        TradeTick dupEvicted = tick("E0", 100.0, 1.0, base + 100);
        List<Kline> before = aggregator.klines(SYMBOL);
        System.out.println("[回收幂等] 输入2: 重复提交已回收窗口 w0 中曾接受的 tickId=E0");
        System.out.println("[回收幂等] 判定依据: 幂等判定先于回收检查, 已接受过的成交无论窗口是否回收都判 DUPLICATE,"
                + " 且不触碰窗口状态(不复生、不改变保留范围)");
        IngestResult r2 = aggregator.ingest(dupEvicted);
        System.out.println("[回收幂等] 实际2: " + r2);
        assertEquals(IngestStatus.DUPLICATE, r2.status(),
                "回收不得改变已成立的幂等判定：已接受过的成交再报必须仍判 DUPLICATE");
        TickRejectedException ev = assertThrows(TickRejectedException.class,
                () -> aggregator.klineAt(SYMBOL, base));
        assertEquals(RejectReason.WINDOW_EVICTED, ev.getReason(), "已回收窗口不得因重复提交而复生");
        assertEquals(before, aggregator.klines(SYMBOL), "重复提交不得改变保留范围与保留窗口结果");
    }

    @Test
    void evictedSlotsAreDistinguishableInRangeQuery() {
        for (int i = 0; i < 5; i++) {
            fillWindow(i);
        }
        List<KlineRangeEntry> range = aggregator.klinesRange(SYMBOL, M1, base, base + 6 * M1);
        System.out.println("[回收范围] 输入: 回收 w0,w1 后查询 6 个槽位 [w0, w6)");
        System.out.println("[回收范围] 判定依据: w0/w1=UNAVAILABLE(已回收), w2..w4=FILLED, w5=EMPTY(保留范围内无成交)");
        for (KlineRangeEntry e : range) {
            System.out.println("[回收范围] slot [" + e.windowStart() + ") " + e.status());
        }
        assertEquals(WindowStatus.UNAVAILABLE, range.get(0).status());
        assertEquals(WindowStatus.UNAVAILABLE, range.get(1).status());
        assertEquals(WindowStatus.FILLED, range.get(2).status());
        assertEquals(WindowStatus.FILLED, range.get(3).status());
        assertEquals(WindowStatus.FILLED, range.get(4).status());
        assertEquals(WindowStatus.EMPTY, range.get(5).status());
    }

    @Test
    void evictionRejectionIsAtomicAcrossPeriods() {
        // 多周期：1m/5m，上限各 2 窗。写入横跨两个 5m 窗口的 6 个 1m 窗口后：
        // 1m 侧只保留 w4,w5；5m 窗口 F0(覆盖 w0..w4) 因小周期数据不齐变为不可用，
        // 5m 窗口 F1(覆盖 w5..w9) 仍由保留的 1m 窗口完整覆盖而可用。
        // 一笔落入已回收 1m 窗口的迟到成交必须整体拒绝，任何周期都不得写入。
        KlineProperties props = new KlineProperties();
        props.setWindowSizeMs(M1);
        props.setAdditionalWindowSizesMs(List.of(M5));
        props.setMaxWindowsPerSymbol(2);
        KlineAggregator multi = new KlineAggregator(props);

        // 基准对齐到 5m 边界；w0..w4 属于 F0，w5 属于 F1
        long t0 = Math.floorDiv(System.currentTimeMillis(), M5) * M5;
        for (int i = 0; i < 6; i++) {
            multi.ingest(new TradeTick("M" + i, SYMBOL, 100.0 + i, 1.0, t0 + i * M1 + 100));
        }
        System.out.println("[多周期回收] 前置: 写入 6 个 1m 窗口(上限2), 1m 仅保留 w4,w5; "
                + "5m 窗口 F0 因 1m 覆盖不齐不可用, F1 可用=" + multi.klines(SYMBOL, M5));

        // 新语义：F0 已不可用，单点查询必须显式拒绝而不是返回对不上的数据
        TickRejectedException k5ev = assertThrows(TickRejectedException.class,
                () -> multi.klineAt(SYMBOL, M5, t0));
        assertEquals(RejectReason.WINDOW_EVICTED, k5ev.getReason(),
                "小周期数据凑不齐的 5m 窗口必须明确告知不可用");
        Kline k5F1Before = multi.klineAt(SYMBOL, M5, t0 + 5 * M1).orElseThrow();
        assertEquals(1, k5F1Before.tradeCount(), "F1 仍由保留的 1m 窗口完整覆盖，必须保留");

        TradeTick late = new TradeTick("LATE-X", SYMBOL, 555.0, 9.0, t0 + 50); // 落入已回收的 1m w0
        System.out.println("[多周期回收] 输入: 迟到成交落入已回收的 1m w0 (其 5m 窗口 F0 也已回收)");
        System.out.println("[多周期回收] 判定依据: 任一周期已回收即整体 WINDOW_EVICTED 拒绝, 任何周期不得出现部分写入");
        TickRejectedException ex = assertThrows(TickRejectedException.class, () -> multi.ingest(late));
        System.out.println("[多周期回收] 实际: reason=" + ex.getReason());
        assertEquals(RejectReason.WINDOW_EVICTED, ex.getReason());

        Kline k5F1After = multi.klineAt(SYMBOL, M5, t0 + 5 * M1).orElseThrow();
        System.out.println("[多周期回收] 拒绝后保留的 5m 窗口 F1: " + k5F1After);
        assertEquals(k5F1Before, k5F1After, "保留的 5m 窗口不得计入被整体拒绝的迟到成交");
        assertEquals(1, multi.klines(SYMBOL, M5).size(), "可用窗口集合不得因迟到成交而改变");
    }
}
