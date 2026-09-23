package com.github.lisama569.tickklineaggregatorjava.service;

import com.github.lisama569.tickklineaggregatorjava.config.KlineProperties;
import com.github.lisama569.tickklineaggregatorjava.model.IngestResult;
import com.github.lisama569.tickklineaggregatorjava.model.Kline;
import com.github.lisama569.tickklineaggregatorjava.model.TradeTick;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * K 线聚合核心。
 *
 * <p>规则：
 * <ul>
 *   <li>窗口为左闭右开 [windowStart, windowStart + windowSizeMs)，
 *       windowStart = floorDiv(timestamp, windowSizeMs) * windowSizeMs，
 *       恰好落在窗口起点的成交归属该窗口，不会计入相邻窗口。</li>
 *   <li>幂等：同一 symbol 下相同 tickId 只计入一次，重复提交返回 DUPLICATE。</li>
 *   <li>乱序/迟到：open 取窗口内时间戳最小成交的价格，close 取时间戳最大成交的价格；
 *       时间戳相同按 tickId 字典序打破平局，保证修正结果与到达顺序无关、唯一确定。</li>
 *   <li>一致性：每个窗口的聚合状态在内部锁内原子更新，读取返回不可变快照，
 *       不会读到半更新（如量已加而最高价未更新）的 K 线。</li>
 *   <li>成交量用 BigDecimal 累加，与成交到达顺序无关，保证并发下结果可复现。</li>
 * </ul>
 */
@Component
public class KlineAggregator {

    /** 窗口键：标的 + 窗口起点 */
    private record WindowKey(String symbol, long windowStart) {
    }

    /**
     * 单个窗口的可变聚合状态。所有读写均在实例锁内完成，
     * 快照不可变，保证读写一致性。
     */
    private static final class KlineState {
        private double high = Double.NEGATIVE_INFINITY;
        private double low = Double.POSITIVE_INFINITY;
        private BigDecimal volume = BigDecimal.ZERO;
        private long tradeCount = 0;

        private long openTs = Long.MAX_VALUE;
        private String openTickId = null;
        private double open;

        private long closeTs = Long.MIN_VALUE;
        private String closeTickId = null;
        private double close;

        synchronized void apply(TradeTick tick) {
            high = Math.max(high, tick.price());
            low = Math.min(low, tick.price());
            volume = volume.add(BigDecimal.valueOf(tick.quantity()));
            tradeCount++;

            // 开盘价：时间戳最小者；平局取 tickId 字典序最小，保证唯一确定
            if (tick.timestamp() < openTs
                    || (tick.timestamp() == openTs && tick.tickId().compareTo(openTickId) < 0)) {
                openTs = tick.timestamp();
                openTickId = tick.tickId();
                open = tick.price();
            }
            // 收盘价：时间戳最大者；平局取 tickId 字典序最大，保证唯一确定
            if (tick.timestamp() > closeTs
                    || (tick.timestamp() == closeTs && tick.tickId().compareTo(closeTickId) > 0)) {
                closeTs = tick.timestamp();
                closeTickId = tick.tickId();
                close = tick.price();
            }
        }

        synchronized Kline snapshot(String symbol, long windowStart, long windowSizeMs) {
            return new Kline(symbol, windowStart, windowStart + windowSizeMs,
                    open, high, low, close, volume.doubleValue(), tradeCount);
        }
    }

    private final KlineProperties properties;
    private final ConcurrentHashMap<WindowKey, KlineState> windows = new ConcurrentHashMap<>();
    /** 幂等去重表：symbol -> 已处理的 tickId 集合 */
    private final ConcurrentHashMap<String, Set<String>> seenTickIds = new ConcurrentHashMap<>();

    public KlineAggregator(KlineProperties properties) {
        this.properties = properties;
    }

    /** 计算成交所属窗口起点（左闭右开）。 */
    public long windowStartOf(long timestamp) {
        long size = properties.getWindowSizeMs();
        return Math.floorDiv(timestamp, size) * size;
    }

    /**
     * 上报一笔成交（调用前须已通过校验）。
     * 幂等：重复 tickId 不重复计入，返回 DUPLICATE。
     */
    public IngestResult ingest(TradeTick tick) {
        Set<String> seen = seenTickIds.computeIfAbsent(tick.symbol(), k -> ConcurrentHashMap.newKeySet());
        if (!seen.add(tick.tickId())) {
            return IngestResult.duplicate(tick, windowStartOf(tick.timestamp()));
        }
        long windowStart = windowStartOf(tick.timestamp());
        WindowKey key = new WindowKey(tick.symbol(), windowStart);
        KlineState state = windows.computeIfAbsent(key, k -> new KlineState());
        state.apply(tick);
        return IngestResult.accepted(tick, windowStart);
    }

    /**
     * 查询某标的某时刻所属窗口的 K 线一致快照。
     */
    public Optional<Kline> klineAt(String symbol, long timestamp) {
        WindowKey key = new WindowKey(symbol, windowStartOf(timestamp));
        KlineState state = windows.get(key);
        if (state == null) {
            return Optional.empty();
        }
        return Optional.of(state.snapshot(symbol, key.windowStart(), properties.getWindowSizeMs()));
    }

    /**
     * 查询某标的全部窗口的 K 线快照（按窗口起点升序）。
     */
    public List<Kline> klines(String symbol) {
        List<Kline> result = new ArrayList<>();
        windows.forEach((key, state) -> {
            if (key.symbol().equals(symbol)) {
                result.add(state.snapshot(symbol, key.windowStart(), properties.getWindowSizeMs()));
            }
        });
        result.sort(Comparator.comparingLong(Kline::windowStart));
        return result;
    }
}
