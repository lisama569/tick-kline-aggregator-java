package com.github.lisama569.tickklineaggregatorjava.tests;

import com.github.lisama569.tickklineaggregatorjava.config.KlineProperties;
import com.github.lisama569.tickklineaggregatorjava.exception.TickRejectedException;
import com.github.lisama569.tickklineaggregatorjava.model.IngestResult;
import com.github.lisama569.tickklineaggregatorjava.model.IngestStatus;
import com.github.lisama569.tickklineaggregatorjava.model.Kline;
import com.github.lisama569.tickklineaggregatorjava.model.KlineRange;
import com.github.lisama569.tickklineaggregatorjava.model.RangeWindowState;
import com.github.lisama569.tickklineaggregatorjava.model.RejectReason;
import com.github.lisama569.tickklineaggregatorjava.model.TradeTick;
import com.github.lisama569.tickklineaggregatorjava.service.KlineAggregator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 窗口保留上限与自动回收测试：
 * 上限回收最旧窗口、迟到成交落入已回收窗口显式拒绝（WINDOW_EVICTED）、
 * 幂等判定不受回收影响、保留范围内结果不受影响、多周期独立回收。
 * 每个用例打印输入与判定依据。
 */
class WindowRetentionTests {

    private static final long WINDOW = 60_000L;
    private static final int CAP = 3;

    private KlineAggregator aggregator;
    private long base;

    @BeforeEach
    void setUp() {
        KlineProperties props = new KlineProperties();
        props.setWindowSizeMs(WINDOW);
        props.setMaxWindowsPerPeriod(CAP);
        aggregator = new KlineAggregator(props);
        long now = System.currentTimeMillis();
        base = Math.floorDiv(now, WINDOW) * WINDOW;
    }

    private TradeTick tick(String id, int windowIdx, double price) {
        return new TradeTick(id, "BTCUSDT", price, 1.0, base + (long) windowIdx * WINDOW);
    }

    @Test
    void evictsOldestWindowsBeyondCapAndKeepsRetainedResultsIntact() {
        // 输入：依次写入窗口 0..4 各一笔，上限 3
        for (int i = 0; i <= 4; i++) {
            aggregator.ingest(tick("w" + i, i, 100.0 + i));
        }
        List<Kline> kept = aggregator.klines("BTCUSDT");
        System.out.println("[回收] 输入: 窗口 0..4 各 1 笔, 上限=" + CAP);
        System.out.println("[回收] 判定依据: 超出上限回收最旧; 保留窗口为最近 " + CAP + " 个且结果不变");
        System.out.println("[回收] 保留窗口起点索引: "
                + kept.stream().map(k -> (k.windowStart() - base) / WINDOW).toList());

        assertEquals(CAP, kept.size(), "窗口数必须被限制在上限");
        for (int i = 0; i < CAP; i++) {
            int idx = 2 + i; // 保留 w2,w3,w4
            Kline k = kept.get(i);
            assertEquals(base + (long) idx * WINDOW, k.windowStart());
            assertEquals(100.0 + idx, k.open(), 1e-9, "保留窗口内容不得被回收影响");
            assertEquals(1, k.tradeCount());
        }
        assertTrue(aggregator.klineAt("BTCUSDT", base).isEmpty(), "w0 已回收, 单窗口查询为空(404)");
        assertTrue(aggregator.klineAt("BTCUSDT", base + WINDOW).isEmpty(), "w1 已回收");
        assertTrue(aggregator.klineAt("BTCUSDT", base + 2 * WINDOW).isPresent(), "w2 仍保留");

        // 范围查询中被回收的窗口必须显示 NOT_RETAINED，且 oldest 指向当前最旧保留窗口
        KlineRange range = aggregator.klinesRange("BTCUSDT", base, base + 4L * WINDOW + 1);
        assertEquals(RangeWindowState.NOT_RETAINED, range.windows().get(0).state());
        assertEquals(RangeWindowState.NOT_RETAINED, range.windows().get(1).state());
        assertEquals(RangeWindowState.HAS_DATA, range.windows().get(2).state());
        assertEquals(base + 2L * WINDOW, range.oldestWindowStart());
        System.out.println("[回收] 范围查询状态序列: "
                + range.windows().stream().map(w -> w.state().name()).toList());
    }

    @Test
    void lateTickIntoEvictedWindowIsRejectedWithDistinctReason() {
        for (int i = 0; i <= 4; i++) {
            aggregator.ingest(tick("w" + i, i, 100.0 + i));
        }
        // 回收硬下界 = w4 - 2*WINDOW = w2；迟到到 w1 的新成交必须被显式拒绝
        TradeTick tooLate = new TradeTick("late-x", "BTCUSDT", 500.0, 9.0, base + WINDOW + 5_000);
        TickRejectedException ex = assertThrows(TickRejectedException.class,
                () -> aggregator.ingest(tooLate));

        System.out.println("[迟到拒绝] 输入: 窗口已推进到 w4, 新成交 late-x 落在 w1");
        System.out.println("[迟到拒绝] 判定依据: w1 早于回收硬下界 w2, 必须 WINDOW_EVICTED 显式拒绝");
        System.out.println("[迟到拒绝] 拒绝信息: " + ex.getMessage());

        assertEquals(RejectReason.WINDOW_EVICTED, ex.getReason());
        assertTrue(aggregator.klineAt("BTCUSDT", base + WINDOW).isEmpty(),
                "拒绝后不得生成残缺/孤立窗口");

        // 边界：落在硬下界 w2 的迟到成交仍可正常补入并修正 w2
        TradeTick atFloor = new TradeTick("late-floor", "BTCUSDT", 50.0, 1.0, base + 2L * WINDOW + 1);
        IngestResult r = aggregator.ingest(atFloor);
        assertEquals(IngestStatus.ACCEPTED, r.status());
        Kline w2 = aggregator.klineAt("BTCUSDT", base + 2L * WINDOW).orElseThrow();
        assertEquals(2, w2.tradeCount());
        assertEquals(50.0, w2.low(), 1e-9, "边界窗口的迟到成交必须能正常修正");
        System.out.println("[迟到拒绝] 落在硬下界 w2 的迟到成交被接受, w2 笔数=" + w2.tradeCount());
    }

    @Test
    void duplicateTickStaysDuplicateAfterItsWindowIsEvicted() {
        aggregator.ingest(tick("dup-1", 0, 100.0));
        // 推进到 w4，w0 被回收
        for (int i = 1; i <= 4; i++) {
            aggregator.ingest(tick("w" + i, i, 100.0 + i));
        }
        TradeTick replay = tick("dup-1", 0, 100.0); // 同一 tickId 重放
        IngestResult r = aggregator.ingest(replay);

        System.out.println("[回收后幂等] 输入: dup-1 首报于 w0, 窗口回收后原样重放");
        System.out.println("[回收后幂等] 判定依据: 去重表不随窗口回收, 必须返回 DUPLICATE 而非 WINDOW_EVICTED");
        System.out.println("[回收后幂等] 结果: " + r.status());

        assertEquals(IngestStatus.DUPLICATE, r.status(), "回收不得破坏幂等判定");
        assertTrue(aggregator.klineAt("BTCUSDT", base).isEmpty(), "重放不得重建已回收窗口");

        // 同一已回收窗口的“新”tickId：必须 WINDOW_EVICTED，与重复提交可明确区分
        TickRejectedException ex = assertThrows(TickRejectedException.class,
                () -> aggregator.ingest(tick("brand-new", 0, 100.0)));
        assertEquals(RejectReason.WINDOW_EVICTED, ex.getReason());
        System.out.println("[回收后幂等] 同窗口新 tickId brand-new 结果: " + ex.getReason());
    }

    @Test
    void sparseActivityWithinEvictionFloorIsAccepted() {
        // 稀疏行情：w0 一笔、w2 一笔（保留槽位远未打满），w1 的迟到成交必须正常接受
        aggregator.ingest(tick("s0", 0, 100.0));
        aggregator.ingest(tick("s2", 2, 120.0));
        TradeTick fillGap = tick("s1", 1, 110.0);
        IngestResult r = aggregator.ingest(fillGap);

        System.out.println("[稀疏] 输入: w0 成交 -> w2 成交 -> w1 迟到补单, 上限=" + CAP);
        System.out.println("[稀疏] 判定依据: 保留槽位未打满, 硬下界=w0, w1 不早于下界, 必须接受");
        System.out.println("[稀疏] 结果: " + r.status() + ", 保留窗口数=" + aggregator.klines("BTCUSDT").size());

        assertEquals(IngestStatus.ACCEPTED, r.status());
        assertEquals(3, aggregator.klines("BTCUSDT").size());
    }

    @Test
    void multiPeriodRetentionNestsAcrossPeriodsAndKeptBigWindowsStayComposable() {
        long small = WINDOW;
        long big = 5 * WINDOW;
        int cap = 10; // 每个周期各自最多保留 10 个窗口
        // 基准对齐到大周期起点，使 w0..w19 恰好落在 B0..B3
        long bigBase = Math.floorDiv(System.currentTimeMillis(), big) * big;
        KlineProperties props = new KlineProperties();
        props.setWindowSizeMs(small);
        props.setWindowSizesMs(List.of(small, big));
        props.setMaxWindowsPerPeriod(cap);
        aggregator = new KlineAggregator(props);

        // 小周期窗口 0..19（横跨 4 个大周期 0..3）
        for (int i = 0; i <= 19; i++) {
            aggregator.ingest(new TradeTick("p" + i, "ETHUSDT", 100.0 + i, 1.0,
                    bigBase + (long) i * small));
        }
        List<Kline> smallKept = aggregator.klines("ETHUSDT", small);
        List<Kline> bigKept = aggregator.klines("ETHUSDT", big);

        System.out.println("[多周期回收] 输入: 小窗口 0..19, 各周期上限=" + cap);
        System.out.println("[多周期回收] 判定依据: 小周期保留 w10..w19; 与已回收小窗重叠的 B0/B1 嵌套回收, "
                + "B2/B3 完整覆盖 w10..w19 必须保留且可由小窗严格合成");
        System.out.println("[多周期回收] 小周期保留索引(相对小窗): "
                + smallKept.stream().map(k -> (k.windowStart() - bigBase) / small).toList()
                + "; 大周期保留索引(相对大窗): "
                + bigKept.stream().map(k -> (k.windowStart() - bigBase) / big).toList());

        // 小周期自身上限：保留 w10..w19
        assertEquals(cap, smallKept.size());
        assertEquals(10L * small, smallKept.get(0).windowStart() - bigBase);
        // 大周期自身上限 10 远未打满（只有 4 个），但 B0/B1 与已回收小窗重叠，
        // 嵌套回收后只保留完整覆盖现存小窗的 B2(w10..w14)、B3(w15..w19)
        assertEquals(2, bigKept.size(), "与已回收小窗重叠的大窗口必须一并回收");
        assertEquals(2L * big, bigKept.get(0).windowStart() - bigBase, "保留 B2,B3");
        assertEquals(3L * big, bigKept.get(1).windowStart() - bigBase);

        // 迟到到已被回收的小窗口 w0（同时属于已回收大周期 B0）：显式拒绝
        TickRejectedException ex = assertThrows(TickRejectedException.class,
                () -> aggregator.ingest(new TradeTick("late", "ETHUSDT", 1.0, 1.0, bigBase + 1)));
        assertEquals(RejectReason.WINDOW_EVICTED, ex.getReason());
        System.out.println("[多周期回收] 迟到到 w0/B0 拒绝原因: " + ex.getReason() + " -> " + ex.getMessage());

        // 每个保留大周期都必须恰好等于其覆盖的现存小周期合成（回收后跨周期对齐仍严格成立）
        for (Kline bk : bigKept) {
            long bs = bk.windowStart();
            List<Kline> children = smallKept.stream()
                    .filter(k -> k.windowStart() >= bs && k.windowStart() < bs + big)
                    .toList();
            long childCount = children.stream().mapToLong(Kline::tradeCount).sum();
            double childVolume = children.stream().mapToDouble(Kline::volume).sum();
            double childHigh = children.stream().mapToDouble(Kline::high).max().orElseThrow();
            double childLow = children.stream().mapToDouble(Kline::low).min().orElseThrow();
            System.out.println("[多周期回收] 大窗@" + (bs - bigBase) / big
                    + " 子小窗数=" + children.size() + " 大窗笔数=" + bk.tradeCount()
                    + " 子窗合计=" + childCount);
            assertEquals(5, children.size(), "B2/B3 的 5 个子小窗必须全部仍在保留范围内");
            assertEquals(childCount, bk.tradeCount(), "大窗笔数必须等于子小窗笔数之和");
            assertEquals(childVolume, bk.volume(), 1e-9, "大窗成交量必须等于子小窗之和");
            assertEquals(childHigh, bk.high(), 1e-9, "大窗最高必须等于子小窗最高之最大");
            assertEquals(childLow, bk.low(), 1e-9, "大窗最低必须等于子小窗最低之最小");
            assertEquals(children.get(0).open(), bk.open(), 1e-9, "大窗开=首个子小窗开");
            assertEquals(children.get(4).close(), bk.close(), 1e-9, "大窗收=末个子小窗收");
        }
    }
}
