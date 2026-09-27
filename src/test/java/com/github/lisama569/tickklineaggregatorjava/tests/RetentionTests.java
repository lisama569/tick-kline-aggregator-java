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

        assertTrue(aggregator.klineAt(SYMBOL, base).isEmpty(), "已回收窗口单点查询返回空");
        assertTrue(aggregator.klineAt(SYMBOL, base + M1).isEmpty());
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

        // 不生成残缺窗口、不影响保留结果
        assertTrue(aggregator.klineAt(SYMBOL, base).isEmpty(), "被拒绝的迟到成交不得生成残缺窗口");
        assertEquals(before, aggregator.klines(SYMBOL), "保留窗口结果不得被迟到成交改变");
    }

    @Test
    void idempotencySurvivesEviction() {
        for (int i = 0; i < 5; i++) {
            fillWindow(i);
        }
        List<Kline> retainedBefore = aggregator.klines(SYMBOL);
        // 保留窗口内的重复提交：仍然 DUPLICATE，不重复计入
        TradeTick dup = tick("E4", 104.0, 1.0, base + 4 * M1 + 100);
        IngestResult r1 = aggregator.ingest(dup);
        System.out.println("[回收幂等] 输入1: 重复提交保留窗口 w4 中已接受的 tickId=E4");
        System.out.println("[回收幂等] 判定依据: 去重表不随回收删除, 保留窗口内重复提交仍判 DUPLICATE");
        System.out.println("[回收幂等] 实际1: " + r1);
        assertEquals(IngestStatus.DUPLICATE, r1.status());
        assertEquals(1, aggregator.klineAt(SYMBOL, base + 4 * M1).orElseThrow().tradeCount(),
                "重复提交不得重复计入");

        // 【新语义】已回收窗口中“曾经成功接受过”的成交再次上报：仍判 DUPLICATE。
        // 旧实现回收检查先于幂等检查，会把它误判为 WINDOW_EVICTED；现已修正为幂等判定先于
        // 回收判定。重复上报不得重复计入，也不得让已回收窗口复生或改变保留范围。
        TradeTick dupEvicted = tick("E0", 100.0, 1.0, base + 100);
        System.out.println("[回收幂等] 输入2: 重复提交已回收窗口 w0 中曾接受的 tickId=E0");
        System.out.println("[回收幂等] 判定依据(新语义): 曾经接受即永久幂等, 结论稳定为 DUPLICATE, "
                + "不是 WINDOW_EVICTED; 窗口不复生、保留范围不变");
        IngestResult r2 = aggregator.ingest(dupEvicted);
        System.out.println("[回收幂等] 实际2: " + r2);
        assertEquals(IngestStatus.DUPLICATE, r2.status(),
                "已接受成交即使窗口已回收，重复上报也必须稳定判为重复提交");
        assertTrue(aggregator.klineAt(SYMBOL, base).isEmpty(), "已回收窗口不得因重复提交而复生");
        assertEquals(retainedBefore, aggregator.klines(SYMBOL),
                "重复提交不得改变保留窗口范围与内容");

        // 对照：另一笔从未被接受过、落入已回收窗口的新成交，仍应明确判 WINDOW_EVICTED
        TradeTick neverAccepted = tick("NEVER-0", 200.0, 1.0, base + 200);
        TickRejectedException ex = assertThrows(TickRejectedException.class,
                () -> aggregator.ingest(neverAccepted));
        System.out.println("[回收幂等] 输入3(对照): 从未接受过的新成交落入已回收窗口 w0");
        System.out.println("[回收幂等] 判定依据(对照): 未占用幂等表, 必须判 WINDOW_EVICTED, 实际=" + ex.getReason());
        assertEquals(RejectReason.WINDOW_EVICTED, ex.getReason());
        // 且该迟到成交再次提交结论不变（不会因为第一次尝试而被记成重复）
        TickRejectedException exAgain = assertThrows(TickRejectedException.class,
                () -> aggregator.ingest(neverAccepted));
        assertEquals(RejectReason.WINDOW_EVICTED, exAgain.getReason(),
                "从未接受的迟到成交重复提交结论必须一致");
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
        // 多周期：1m + 5m，每周期上限 2 窗。写入同一 5m 窗口内的 4 个 1m 窗口后，
        // 1m 侧回收 w0,w1，于是这个 5m 窗口覆盖的小周期已凑不齐——按新语义它不再是
        // 可信结果（单点查询 WINDOW_UNVERIFIABLE、范围查询 UNVERIFIABLE）。
        // 本用例验证：此时再向已回收的 1m w0 补发一笔“从未接受”的成交，必须整体
        // WINDOW_EVICTED 拒绝，且拒绝前后所有周期的可观测状态完全一致（无部分写入）。
        KlineProperties props = new KlineProperties();
        props.setWindowSizeMs(M1);
        props.setAdditionalWindowSizesMs(List.of(M5));
        props.setMaxWindowsPerSymbol(2);
        KlineAggregator multi = new KlineAggregator(props);

        // 基准对齐到 5m 边界，保证四笔成交落在同一个 5m 窗口内
        long t0 = Math.floorDiv(System.currentTimeMillis(), M5) * M5;
        // 写入 w0..w3 四个 1m 窗口（同一 5m 窗口内），1m 侧回收 w0,w1
        for (int i = 0; i < 4; i++) {
            multi.ingest(new TradeTick("M" + i, SYMBOL, 100.0 + i, 1.0, t0 + i * M1 + 100));
        }

        // 【新语义】5m 窗口虽自身有 4 笔聚合值，但覆盖的 1m w0,w1 已回收，无法对上
        TickRejectedException unverifiable = assertThrows(TickRejectedException.class,
                () -> multi.klineAt(SYMBOL, M5, t0));
        System.out.println("[多周期回收] 前置: 1m 已回收 w0,w1; 5m 窗口单点查询 reason="
                + unverifiable.getReason());
        assertEquals(RejectReason.WINDOW_UNVERIFIABLE, unverifiable.getReason());

        // 拒绝前的可观测状态：保留的 1m 窗口、5m 范围槽位（应为 UNVERIFIABLE）
        List<Kline> m1Before = multi.klines(SYMBOL, M1);
        List<KlineRangeEntry> m5RangeBefore = multi.klinesRange(SYMBOL, M5, t0, t0 + M5);
        System.out.println("[多周期回收] 拒绝前 1m 保留窗=" + m1Before);
        System.out.println("[多周期回收] 拒绝前 5m 槽位状态=" + m5RangeBefore.get(0).status());
        assertEquals(2, m1Before.size());
        assertEquals(WindowStatus.UNVERIFIABLE, m5RangeBefore.get(0).status());

        TradeTick late = new TradeTick("LATE-X", SYMBOL, 555.0, 9.0, t0 + 50); // 落入已回收的 1m w0
        System.out.println("[多周期回收] 输入: 从未接受的迟到成交落入已回收的 1m w0, 其 5m 窗口仍不可信");
        System.out.println("[多周期回收] 判定依据: 任一周期已回收即整体 WINDOW_EVICTED 拒绝, "
                + "任何周期都不得出现部分写入, 拒绝前后状态完全一致");
        TickRejectedException ex = assertThrows(TickRejectedException.class, () -> multi.ingest(late));
        System.out.println("[多周期回收] 实际: reason=" + ex.getReason());
        assertEquals(RejectReason.WINDOW_EVICTED, ex.getReason());

        assertEquals(m1Before, multi.klines(SYMBOL, M1), "1m 侧不得计入被整体拒绝的迟到成交");
        List<KlineRangeEntry> m5RangeAfter = multi.klinesRange(SYMBOL, M5, t0, t0 + M5);
        assertEquals(m5RangeBefore, m5RangeAfter, "5m 侧槽位状态不得被被拒成交改变");
        // 该迟到成交再次提交仍是 WINDOW_EVICTED（未被记成重复，结论可复现）
        TickRejectedException exAgain = assertThrows(TickRejectedException.class,
                () -> multi.ingest(late));
        assertEquals(RejectReason.WINDOW_EVICTED, exAgain.getReason());
    }
}
