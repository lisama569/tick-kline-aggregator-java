package com.github.lisama569.tickklineaggregatorjava.service;

import com.github.lisama569.tickklineaggregatorjava.domain.IngestResult;
import com.github.lisama569.tickklineaggregatorjava.domain.KlineBar;
import com.github.lisama569.tickklineaggregatorjava.domain.Trade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 聚合规则单元测试：窗口边界、乱序/迟到修正、开盘收盘 tie-break、幂等。
 */
class KlineAggregatorTest {

    private static final long W = 60_000L;

    private static Trade trade(String id, String symbol, String price,
                               String volume, long ts) {
        return new Trade(id, symbol, new BigDecimal(price), new BigDecimal(volume), ts);
    }

    private static void printBar(String caseName, KlineBar bar) {
        System.out.println("[判定依据] " + caseName + " -> 窗口["
                + bar.windowStartMillis() + "," + bar.windowEndMillis()
                + ") open=" + bar.open() + " high=" + bar.high()
                + " low=" + bar.low() + " close=" + bar.close()
                + " volume=" + bar.volume() + " count=" + bar.tradeCount());
    }

    @Test
    @DisplayName("窗口起始时间：恰好落在边界的时间戳归属右侧窗口")
    void windowStart_floorAndBoundary() {
        KlineAggregator agg = new KlineAggregator(W);
        System.out.println("[输入] windowMillis=" + W);
        assertEquals(0L, agg.windowStartFor(0L), "0 属窗口 0");
        assertEquals(0L, agg.windowStartFor(59_999L), "59999 属窗口 0");
        assertEquals(60_000L, agg.windowStartFor(60_000L),
                "边界 60000 必须属右侧窗口 60000，不得计入窗口 0");
        assertEquals(60_000L, agg.windowStartFor(60_001L));
        assertEquals(120_000L, agg.windowStartFor(120_000L),
                "边界 120000 必须属窗口 120000");
        System.out.println("[判定依据] floorDiv 保证左闭右开，边界点唯一归属右侧窗口");
    }

    @Test
    @DisplayName("边界成交：59999 与 60000 分别进入相邻两个窗口，互不重复")
    void boundaryTicksBelongToExactlyOneWindow() {
        KlineAggregator agg = new KlineAggregator(W);
        Trade left = trade("L1", "BTC", "100", "1", 59_999L);
        Trade right = trade("R1", "BTC", "200", "2", 60_000L);
        System.out.println("[输入] L1 ts=59999 price=100 vol=1; R1 ts=60000 price=200 vol=2");

        agg.ingest(left);
        agg.ingest(right);

        KlineBar bar0 = agg.getBar("BTC", 0L);
        KlineBar bar1 = agg.getBar("BTC", 60_000L);
        printBar("窗口0 (查询 at=0)", bar0);
        printBar("窗口1 (查询 at=60000)", bar1);

        assertEquals(new BigDecimal("100"), bar0.open());
        assertEquals(new BigDecimal("1"), bar0.volume());
        assertEquals(1L, bar0.tradeCount());

        assertEquals(new BigDecimal("200"), bar1.open());
        assertEquals(new BigDecimal("2"), bar1.volume());
        assertEquals(1L, bar1.tradeCount());

        // 用边界时间戳查询，两个窗口各自只被计一次
        assertNull(agg.getBar("BTC", 120_000L), "窗口2 无成交");
    }

    @Test
    @DisplayName("乱序与迟到成交：最终 OHLCV 与按时间排序的串行结果一致")
    void outOfOrderAndLateTicksCorrectFinalBar() {
        KlineAggregator agg = new KlineAggregator(W);
        // 先到中间价，再到更早（迟到）的低价，最后到更高的高价
        agg.ingest(trade("T-mid", "ETH", "100", "5", 30_000L));
        agg.ingest(trade("T-early-late", "ETH", "90", "2", 10_000L));
        agg.ingest(trade("T-high", "ETH", "110", "3", 50_000L));
        System.out.println("[输入] (30000,p100,v5) 先到; (10000,p90,v2) 迟到; (50000,p110,v3)");

        KlineBar bar = agg.getBar("ETH", 0L);
        printBar("乱序/迟到修正后", bar);

        assertEquals(new BigDecimal("90"), bar.open(), "开盘应为最早成交 10000 的价格 90");
        assertEquals(new BigDecimal("110"), bar.high());
        assertEquals(new BigDecimal("90"), bar.low());
        assertEquals(new BigDecimal("110"), bar.close(), "收盘应为最晚成交 50000 的价格 110");
        assertEquals(new BigDecimal("10"), bar.volume(), "成交量 5+2+3=10");
        assertEquals(3L, bar.tradeCount());
    }

    @Test
    @DisplayName("同时间戳 tie-break：tradeId 决定开收盘，且与到达顺序无关、结果唯一")
    void sameTimestampTieBreakIsOrderIndependent() {
        KlineAggregator a = new KlineAggregator(W);
        KlineAggregator b = new KlineAggregator(W);

        // 同一时刻两笔成交，价格不同；两个聚合器以相反顺序接收
        Trade ta = trade("a", "X", "10", "1", 1_000L);
        Trade tb = trade("b", "X", "20", "2", 1_000L);
        System.out.println("[输入] 同 ts=1000: a(p10,v1), b(p20,v2)；聚合器A收 a→b，聚合器B收 b→a");

        a.ingest(ta);
        a.ingest(tb);
        b.ingest(tb);
        b.ingest(ta);

        KlineBar barA = a.getBar("X", 0L);
        KlineBar barB = b.getBar("X", 0L);
        printBar("顺序 a->b", barA);
        printBar("顺序 b->a", barB);

        assertEquals(new BigDecimal("10"), barA.open());
        assertEquals(new BigDecimal("20"), barA.close());
        assertEquals(barA, barB, "相反到达顺序必须得到完全相同的 K 线");
        assertEquals(new BigDecimal("3"), barA.volume());
        assertEquals(2L, barA.tradeCount());
    }

    @Test
    @DisplayName("串行重复提交：同一 tradeId 第二次为 DUPLICATE，聚合值不变")
    void duplicateSubmissionIsIdempotent() {
        KlineAggregator agg = new KlineAggregator(W);
        Trade t = trade("DUP1", "BTC", "100", "7", 5_000L);
        System.out.println("[输入] 同一成交 DUP1 连续提交两次");

        IngestResult first = agg.ingest(t);
        IngestResult second = agg.ingest(t);

        assertTrue(first.accepted());
        assertFalse(first.duplicate());
        assertFalse(second.accepted());
        assertTrue(second.duplicate(), "第二次必须标记为幂等重复");
        System.out.println("[判定依据] 第一次 accepted=" + first.accepted()
                + "，第二次 duplicate=" + second.duplicate());

        KlineBar bar = agg.getBar("BTC", 0L);
        printBar("重复提交后", bar);
        assertEquals(new BigDecimal("7"), bar.volume(), "成交量不得因重复提交翻倍");
        assertEquals(1L, bar.tradeCount(), "笔数不得因重复提交增加");
    }
}
