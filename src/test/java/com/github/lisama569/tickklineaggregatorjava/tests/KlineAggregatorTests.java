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
 * K 线聚合核心规则测试：窗口边界、乱序迟到修正、并发幂等、一致快照。
 * 每个用例打印输入与判定依据。
 */
class KlineAggregatorTests {

    private static final long WINDOW = 60_000L;

    private KlineAggregator aggregator;
    private long base; // 对齐到窗口起点的基准时间

    @BeforeEach
    void setUp() {
        KlineProperties props = new KlineProperties();
        props.setWindowSizeMs(WINDOW);
        aggregator = new KlineAggregator(props);
        long now = System.currentTimeMillis();
        base = Math.floorDiv(now, WINDOW) * WINDOW;
    }

    private TradeTick tick(String id, double price, double qty, long ts) {
        return new TradeTick(id, "BTCUSDT", price, qty, ts);
    }

    @Test
    void boundaryTickBelongsToExactlyOneWindow() {
        // 输入：恰好落在窗口起点的成交 + 窗口最后一毫秒的成交
        TradeTick atStart = tick("b1", 100.0, 1.0, base);
        TradeTick atEnd = tick("b2", 200.0, 2.0, base + WINDOW - 1);
        aggregator.ingest(atStart);
        aggregator.ingest(atEnd);

        Optional<Kline> current = aggregator.klineAt("BTCUSDT", base);
        Optional<Kline> previous = aggregator.klineAt("BTCUSDT", base - 1);
        Optional<Kline> next = aggregator.klineAt("BTCUSDT", base + WINDOW);

        System.out.println("[边界] 输入: ts=" + base + "(窗口起点), ts=" + (base + WINDOW - 1) + "(窗口末毫秒)");
        System.out.println("[边界] 判定依据: 窗口左闭右开 [" + base + ", " + (base + WINDOW)
                + ")，起点成交只计入本窗口");
        System.out.println("[边界] 结果: 当前窗口=" + current.orElse(null)
                + ", 前一窗口=" + previous.orElse(null) + ", 后一窗口=" + next.orElse(null));

        assertTrue(current.isPresent());
        assertEquals(2, current.get().tradeCount(), "两笔成交都必须归属当前窗口");
        assertEquals(3.0, current.get().volume());
        assertTrue(previous.isEmpty(), "窗口起点成交不得计入前一窗口");
        assertTrue(next.isEmpty(), "窗口末毫秒成交不得计入后一窗口");
    }

    @Test
    void outOfOrderAndLateTicksCorrectWindowDeterministically() {
        // 输入：乱序到达 —— 先到最后时刻的成交，再到窗口内最早成交（迟到）
        TradeTick lateArrivalEarlyTs = tick("L1", 90.0, 1.0, base + 1_000);
        TradeTick earlyArrivalLateTs = tick("L2", 130.0, 2.0, base + 50_000);
        TradeTick middle = tick("L3", 110.0, 3.0, base + 30_000);

        aggregator.ingest(earlyArrivalLateTs); // 先到，时间戳最大
        aggregator.ingest(middle);
        aggregator.ingest(lateArrivalEarlyTs); // 迟到，时间戳最小，必须修正 open

        Kline k = aggregator.klineAt("BTCUSDT", base + 10_000).orElseThrow();
        System.out.println("[乱序] 输入到达顺序: L2(ts=+50s,p=130) -> L3(ts=+30s,p=110) -> L1(ts=+1s,p=90,迟到)");
        System.out.println("[乱序] 判定依据: open=窗口内时间戳最小者的价格, close=时间戳最大者的价格, 与到达顺序无关");
        System.out.println("[乱序] 结果: " + k);

        assertEquals(90.0, k.open(), "迟到成交必须修正开盘价");
        assertEquals(130.0, k.close(), "收盘价取时间戳最大成交");
        assertEquals(130.0, k.high());
        assertEquals(90.0, k.low());
        assertEquals(6.0, k.volume());
        assertEquals(3, k.tradeCount());
    }

    @Test
    void sameTimestampTieBreakIsDeterministic() {
        // 同一时间戳两笔成交，open/close 由 tickId 字典序唯一确定
        TradeTick a = tick("T2", 105.0, 1.0, base + 100);
        TradeTick b = tick("T1", 95.0, 1.0, base + 100);
        aggregator.ingest(a);
        aggregator.ingest(b);

        Kline k = aggregator.klineAt("BTCUSDT", base + 100).orElseThrow();
        System.out.println("[平局] 输入: 同时间戳两笔 T2(p=105), T1(p=95)");
        System.out.println("[平局] 判定依据: 时间戳相同，open 取 tickId 字典序最小者(T1), close 取最大者(T2)");
        System.out.println("[平局] 结果: open=" + k.open() + ", close=" + k.close());

        assertEquals(95.0, k.open());
        assertEquals(105.0, k.close());
    }

    @Test
    void concurrentDuplicateSubmissionsAreIdempotentAndMatchSerialExpectation() throws InterruptedException {
        int distinctTicks = 200;
        int threads = 8;
        // 构造 200 笔不同成交，全部落在同一窗口
        List<TradeTick> ticks = new ArrayList<>();
        for (int i = 0; i < distinctTicks; i++) {
            ticks.add(tick("C" + i, 100.0 + i, 1.0 + (i % 5), base + (i * 100L)));
        }

        // 串行期望：单线程按序摄入
        KlineAggregator serial = new KlineAggregator(new KlineProperties());
        for (TradeTick t : ticks) {
            serial.ingest(t);
        }
        Kline expected = serial.klineAt("BTCUSDT", base).orElseThrow();

        // 并发：每个线程都提交全部 200 笔（即每笔被重复提交 8 次）
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

        Kline actual = aggregator.klineAt("BTCUSDT", base).orElseThrow();
        System.out.println("[并发幂等] 输入: " + distinctTicks + " 笔不同成交 x " + threads
                + " 线程重复提交, 共 " + (distinctTicks * threads) + " 次上报");
        System.out.println("[并发幂等] 判定依据: 相同 tickId 只计入一次; 最终聚合必须与串行结果完全一致");
        System.out.println("[并发幂等] 接受=" + accepted.get() + ", 去重=" + duplicated.get());
        System.out.println("[并发幂等] 串行期望: " + expected);
        System.out.println("[并发幂等] 并发实际: " + actual);

        assertEquals(distinctTicks, accepted.get(), "每笔成交只能被接受一次");
        assertEquals(distinctTicks * (threads - 1), duplicated.get(), "其余提交必须判为重复");
        assertEquals(expected, actual, "并发聚合结果必须与串行期望一致且可复现");
    }

    @Test
    void snapshotIsConsistentWhileAggregating() throws InterruptedException {
        int threads = 4;
        int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger violations = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int offset = t * perThread;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        aggregator.ingest(tick("S" + (offset + i), 50.0 + (i % 100), 1.0, base + (i % 1000)));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        // 读线程：聚合进行中持续读快照，校验内部一致性（不得读到半更新）
        pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < 10_000; i++) {
                    Optional<Kline> snap = aggregator.klineAt("BTCUSDT", base);
                    if (snap.isPresent()) {
                        Kline k = snap.get();
                        boolean consistent = k.low() <= k.open() && k.open() <= k.high()
                                && k.low() <= k.close() && k.close() <= k.high()
                                && k.low() <= k.high()
                                && k.tradeCount() >= 1
                                && k.volume() >= k.tradeCount(); // 每笔 qty=1
                        if (!consistent) {
                            violations.incrementAndGet();
                            System.out.println("[一致性] 违规快照: " + k);
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

        Kline finalK = aggregator.klineAt("BTCUSDT", base).orElseThrow();
        System.out.println("[一致性] 输入: " + threads + " 写线程 x " + perThread + " 笔, 1 读线程并发读快照");
        System.out.println("[一致性] 判定依据: 任意快照必须满足 low<=open/close<=high 且 volume 与笔数自洽");
        System.out.println("[一致性] 半更新违规次数=" + violations.get() + ", 最终 K 线=" + finalK);

        assertEquals(0, violations.get(), "聚合进行中不得读到半更新的 K 线");
        assertEquals(threads * perThread, finalK.tradeCount());
        assertEquals(threads * perThread * 1.0, finalK.volume());
    }
}
