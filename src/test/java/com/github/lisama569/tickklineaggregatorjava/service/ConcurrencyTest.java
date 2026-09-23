package com.github.lisama569.tickklineaggregatorjava.service;

import com.github.lisama569.tickklineaggregatorjava.domain.IngestResult;
import com.github.lisama569.tickklineaggregatorjava.domain.KlineBar;
import com.github.lisama569.tickklineaggregatorjava.domain.Trade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发测试：
 * 1) 同一笔成交被多线程重复提交，恰好一次计入；
 * 2) 多线程并发重复提交同一批成交，最终 K 线与串行处理预期一致且可复现；
 * 3) 聚合进行中的并发查询读不到半更新（违反 OHLCV 不变量）的 K 线。
 */
class ConcurrencyTest {

    private static final long W = 60_000L;

    private static Trade trade(int i) {
        // 确定性生成：跨 3 个窗口，价格/数量固定，便于独立计算串行预期
        long ts = (i * 1_000L) % (3 * W);
        BigDecimal price = BigDecimal.valueOf(10 + (i % 17)); // 10..26
        BigDecimal volume = BigDecimal.valueOf(1 + (i % 5));  // 1..5
        return new Trade("T" + i, "BTC", price, volume, ts);
    }

    @Test
    @DisplayName("并发重复提交同一笔成交：恰好一次计入")
    void concurrentDuplicates_countExactlyOnce() throws Exception {
        int threads = 16;
        KlineAggregator agg = new KlineAggregator(W);
        Trade t = new Trade("ONE", "BTC", new BigDecimal("100"), new BigDecimal("9"), 5_000L);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(threads);
        System.out.println("[输入] " + threads + " 个线程并发提交同一笔成交 ONE");

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    barrier.await();
                    IngestResult r = agg.ingest(t);
                    if (r.accepted()) {
                        accepted.incrementAndGet();
                    }
                    if (r.duplicate()) {
                        duplicates.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdownNow();

        System.out.println("[判定依据] accepted=" + accepted.get()
                + " duplicate=" + duplicates.get());
        assertEquals(1, accepted.get(), "并发下同一笔成交必须恰好计入一次");
        assertEquals(threads - 1, duplicates.get(), "其余提交全部应判为幂等重复");
        KlineBar bar = agg.getBar("BTC", 0L);
        assertNotNull(bar);
        assertEquals(new BigDecimal("9"), bar.volume());
        assertEquals(1L, bar.tradeCount());
    }

    @Test
    @DisplayName("并发重复提交一批成交：最终聚合与串行预期一致，且查询始终一致")
    void concurrentBatch_finalBarMatchesSerialExpectation() throws Exception {
        int distinct = 400;
        int writerThreads = 8;
        int readerThreads = 4;

        List<Trade> trades = new ArrayList<>(distinct);
        for (int i = 0; i < distinct; i++) {
            trades.add(trade(i));
        }

        KlineAggregator agg = new KlineAggregator(W);
        ExecutorService pool = Executors.newFixedThreadPool(writerThreads + readerThreads);
        CyclicBarrier start = new CyclicBarrier(writerThreads + readerThreads);
        CountDownLatch writersDone = new CountDownLatch(writerThreads);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ConcurrentLinkedQueue<String> inconsistentViews = new ConcurrentLinkedQueue<>();

        System.out.println("[输入] " + distinct + " 笔不同成交，由 "
                + writerThreads + " 个写线程各自重复提交整批，"
                + readerThreads + " 个读线程在聚合期间持续查询");

        // 写线程：每个线程都提交全部 400 笔（即每笔被重复提交 8 次）
        for (int w = 0; w < writerThreads; w++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (Trade t : trades) {
                        agg.ingest(t);
                    }
                } catch (Throwable ex) {
                    failure.compareAndSet(null, ex);
                } finally {
                    writersDone.countDown();
                }
            });
        }

        // 读线程：聚合进行中不断读取三个窗口，校验一致视图不变量
        for (int r = 0; r < readerThreads; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    while (writersDone.getCount() > 0) {
                        for (long at = 0L; at < 3 * W; at += W) {
                            KlineBar bar = agg.getBar("BTC", at);
                            if (bar != null) {
                                assertConsistent(bar, inconsistentViews);
                            }
                        }
                    }
                } catch (Throwable ex) {
                    failure.compareAndSet(null, ex);
                }
            });
        }

        assertTrue(writersDone.await(15, TimeUnit.SECONDS), "写线程未在时限内完成");
        pool.shutdownNow();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        if (failure.get() != null) {
            throw new AssertionError("并发执行出现异常", failure.get());
        }
        assertTrue(inconsistentViews.isEmpty(),
                "聚合进行中读到半更新 K 线: " + inconsistentViews.stream().findFirst().orElse(""));

        // 独立计算串行预期并逐窗口断言
        for (long windowStart = 0L; windowStart < 3 * W; windowStart += W) {
            BigDecimal expVol = BigDecimal.ZERO;
            BigDecimal expHigh = null;
            BigDecimal expLow = null;
            Trade expOpenTrade = null;
            Trade expCloseTrade = null;
            int expCount = 0;
            for (Trade t : trades) {
                if (agg.windowStartFor(t.timestampMillis()) != windowStart) {
                    continue;
                }
                expCount++;
                expVol = expVol.add(t.volume());
                expHigh = expHigh == null ? t.price() : expHigh.max(t.price());
                expLow = expLow == null ? t.price() : expLow.min(t.price());
                if (expOpenTrade == null
                        || t.timestampMillis() < expOpenTrade.timestampMillis()
                        || (t.timestampMillis() == expOpenTrade.timestampMillis()
                        && t.tradeId().compareTo(expOpenTrade.tradeId()) < 0)) {
                    expOpenTrade = t;
                }
                if (expCloseTrade == null
                        || t.timestampMillis() > expCloseTrade.timestampMillis()
                        || (t.timestampMillis() == expCloseTrade.timestampMillis()
                        && t.tradeId().compareTo(expCloseTrade.tradeId()) > 0)) {
                    expCloseTrade = t;
                }
            }

            KlineBar actual = agg.getBar("BTC", windowStart);
            System.out.println("[判定依据] 窗口[" + actual.windowStartMillis() + ","
                    + actual.windowEndMillis() + ") count=" + actual.tradeCount()
                    + "(预期" + expCount + ") volume=" + actual.volume()
                    + "(预期" + expVol + ") open=" + actual.open()
                    + " close=" + actual.close());

            assertEquals(expCount, actual.tradeCount());
            assertEquals(expVol, actual.volume());
            assertEquals(expHigh, actual.high());
            assertEquals(expLow, actual.low());
            assertEquals(expOpenTrade.price(), actual.open());
            assertEquals(expCloseTrade.price(), actual.close());
        }
    }

    /** 校验读到的 K 线自身不变量，任何违反都说明读到了字段级半更新视图。 */
    private static void assertConsistent(KlineBar bar, ConcurrentLinkedQueue<String> sink) {
        if (bar.high().compareTo(bar.low()) < 0) {
            sink.add("high<low: " + bar);
        }
        if (bar.open().compareTo(bar.high()) > 0 || bar.open().compareTo(bar.low()) < 0) {
            sink.add("open 越界: " + bar);
        }
        if (bar.close().compareTo(bar.high()) > 0 || bar.close().compareTo(bar.low()) < 0) {
            sink.add("close 越界: " + bar);
        }
        if (bar.volume().signum() <= 0 || bar.tradeCount() <= 0) {
            sink.add("volume/count 非法: " + bar);
        }
    }
}
