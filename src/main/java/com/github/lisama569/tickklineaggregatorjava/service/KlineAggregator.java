package com.github.lisama569.tickklineaggregatorjava.service;

import com.github.lisama569.tickklineaggregatorjava.config.KlineProperties;
import com.github.lisama569.tickklineaggregatorjava.exception.TickRejectedException;
import com.github.lisama569.tickklineaggregatorjava.model.IngestResult;
import com.github.lisama569.tickklineaggregatorjava.model.Kline;
import com.github.lisama569.tickklineaggregatorjava.model.KlineRangeEntry;
import com.github.lisama569.tickklineaggregatorjava.model.RejectReason;
import com.github.lisama569.tickklineaggregatorjava.model.TradeTick;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * K 线聚合核心：支持同一标的多周期并行聚合、时间范围查询与窗口保留回收。
 *
 * <p>规则：
 * <ul>
 *   <li>窗口为左闭右开 [windowStart, windowStart + windowSizeMs)，
 *       windowStart = floorDiv(timestamp, windowSizeMs) * windowSizeMs，
 *       恰好落在窗口起点的成交只归属该窗口。</li>
 *   <li>多周期：每个标的为每个已配置周期各维护一份独立窗口表；同一笔成交在每个周期
 *       各计一次且只计一次。由于各周期由同一成交流按相同的确定性规则独立聚合，
 *       大周期窗口的 open/high/low/close/volume/tradeCount 与其覆盖的全部小周期窗口
 *       合起来必然严格一致。</li>
 *   <li>幂等：同一 symbol 下相同 tickId 只计入一次，重复提交返回 DUPLICATE。
 *       去重表不随窗口回收而删除，回收不影响既有幂等判定。</li>
 *   <li>乱序/迟到：open 取窗口内时间戳最小成交的价格，close 取时间戳最大成交的价格；
 *       时间戳相同按 tickId 字典序打破平局，结果与到达顺序无关、唯一确定。</li>
 *   <li>一致性：同一「标的+周期」的所有读写在同一把 store 锁内完成，单窗口更新与
 *       范围查询均为原子操作；范围查询返回的一批窗口来自同一时刻的一致视图，
 *       不会出现部分窗口已计入新成交、部分仍是旧值。</li>
 *   <li>保留回收：每个标的、每个周期最多保留 maxWindowsPerSymbol 个窗口，超出后
 *       按窗口起点升序回收最旧窗口，并推进回收水位线。迟到成交若落入已回收窗口，
 *       在任何状态变更之前即以 WINDOW_EVICTED 显式拒绝，不生成残缺窗口、不重复计入。
 *       回收只由已接受的不同成交驱动，与墙钟无关，结论可复现。</li>
 * </ul>
 */
@Component
public class KlineAggregator {

    /** 计算时间戳所属窗口起点（左闭右开）。 */
    static long windowStartOf(long timestamp, long windowSizeMs) {
        return Math.floorDiv(timestamp, windowSizeMs) * windowSizeMs;
    }

    /** ceil 除法，用于计算范围查询覆盖的网格槽位。 */
    private static long ceilDiv(long x, long y) {
        return -Math.floorDiv(-x, y);
    }

    /**
     * 单个窗口的可变聚合状态。仅在所属 WindowStore 的锁内被读写，无需自带同步。
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

        void apply(TradeTick tick) {
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

        Kline snapshot(String symbol, long windowStart, long windowSizeMs) {
            return new Kline(symbol, windowStart, windowStart + windowSizeMs,
                    open, high, low, close, volume.doubleValue(), tradeCount);
        }
    }

    /**
     * 单一标的、单一周期的窗口表。所有公开方法均在实例锁内执行：
     * 单窗口更新原子，范围查询拿到的是同一时刻的一致视图。
     */
    private final class WindowStore {
        private final long windowSizeMs;
        private final int maxWindows;
        private final TreeMap<Long, KlineState> windows = new TreeMap<>();
        /** 回收水位线：起点小于该值的窗口均已回收；Long.MIN_VALUE 表示尚未发生回收 */
        private long evictedBefore = Long.MIN_VALUE;

        private WindowStore(long windowSizeMs, int maxWindows) {
            this.windowSizeMs = windowSizeMs;
            this.maxWindows = maxWindows;
        }

        /** 窗口是否已被回收；是则抛出可区分的拒绝异常，不改动任何状态。 */
        synchronized void checkNotEvicted(long windowStart) {
            if (windowStart < evictedBefore) {
                throw new TickRejectedException(RejectReason.WINDOW_EVICTED,
                        "window starting at " + windowStart + " (size=" + windowSizeMs
                                + "ms) has been evicted by retention; evictedBefore=" + evictedBefore);
            }
        }

        /** 计入一笔成交，随后按上限回收最旧窗口（回收顺序确定、与墙钟无关）。 */
        synchronized void apply(TradeTick tick) {
            long windowStart = windowStartOf(tick.timestamp(), windowSizeMs);
            windows.computeIfAbsent(windowStart, k -> new KlineState()).apply(tick);
            while (windows.size() > maxWindows) {
                Map.Entry<Long, KlineState> oldest = windows.pollFirstEntry();
                evictedBefore = oldest.getKey() + windowSizeMs;
            }
        }

        synchronized Optional<Kline> klineAt(String symbol, long timestamp) {
            long windowStart = windowStartOf(timestamp, windowSizeMs);
            if (windowStart < evictedBefore) {
                return Optional.empty();
            }
            KlineState state = windows.get(windowStart);
            return state == null
                    ? Optional.empty()
                    : Optional.of(state.snapshot(symbol, windowStart, windowSizeMs));
        }

        synchronized List<Kline> all(String symbol) {
            List<Kline> result = new ArrayList<>(windows.size());
            for (Map.Entry<Long, KlineState> e : windows.entrySet()) {
                result.add(e.getValue().snapshot(symbol, e.getKey(), windowSizeMs));
            }
            return result; // TreeMap 已按窗口起点升序
        }

        /**
         * 一致视图下的范围查询：返回窗口起点落在 [from, to) 的全部网格槽位，
         * 逐槽位标注 FILLED / EMPTY / UNAVAILABLE。整批在同一把锁内生成。
         */
        synchronized List<KlineRangeEntry> range(String symbol, long from, long to) {
            long firstIndex = ceilDiv(from, windowSizeMs);
            long endIndex = ceilDiv(to, windowSizeMs);
            List<KlineRangeEntry> entries = new ArrayList<>((int) Math.max(0, endIndex - firstIndex));
            for (long i = firstIndex; i < endIndex; i++) {
                long start = Math.multiplyExact(i, windowSizeMs);
                long end = Math.addExact(start, windowSizeMs);
                KlineState state = windows.get(start);
                if (state != null) {
                    entries.add(KlineRangeEntry.filled(state.snapshot(symbol, start, windowSizeMs)));
                } else if (start < evictedBefore) {
                    entries.add(KlineRangeEntry.unavailable(start, end));
                } else {
                    entries.add(KlineRangeEntry.empty(start, end));
                }
            }
            return entries;
        }
    }

    private final KlineProperties properties;
    /** 已配置的全部周期（毫秒），升序、去重；首个为默认周期 */
    private final long[] periodSizes;
    private final long defaultWindowSizeMs;

    /** symbol -> (periodMs -> WindowStore) */
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, WindowStore>> storesBySymbol =
            new ConcurrentHashMap<>();
    /** 幂等去重表：symbol -> 已处理的 tickId 集合（不随窗口回收删除） */
    private final ConcurrentHashMap<String, Set<String>> seenTickIds = new ConcurrentHashMap<>();

    public KlineAggregator(KlineProperties properties) {
        this.properties = properties;
        this.defaultWindowSizeMs = properties.getWindowSizeMs();
        TreeMap<Long, Boolean> periods = new TreeMap<>();
        periods.put(defaultWindowSizeMs, Boolean.TRUE);
        if (properties.getAdditionalWindowSizesMs() != null) {
            for (Long size : properties.getAdditionalWindowSizesMs()) {
                if (size != null) {
                    periods.put(size, Boolean.TRUE);
                }
            }
        }
        this.periodSizes = periods.keySet().stream().mapToLong(Long::longValue).toArray();
        for (long size : periodSizes) {
            if (size <= 0) {
                throw new IllegalArgumentException("window size must be positive, got " + size);
            }
        }
        if (properties.getMaxWindowsPerSymbol() <= 0) {
            throw new IllegalArgumentException(
                    "maxWindowsPerSymbol must be positive, got " + properties.getMaxWindowsPerSymbol());
        }
    }

    /** 已配置的全部聚合周期（毫秒），升序。 */
    public long[] configuredPeriods() {
        return periodSizes.clone();
    }

    /** 计算成交在默认周期下所属窗口起点（左闭右开）。 */
    public long windowStartOf(long timestamp) {
        return windowStartOf(timestamp, defaultWindowSizeMs);
    }

    private WindowStore requireStore(String symbol, long periodMs) {
        ConcurrentHashMap<Long, WindowStore> stores = storesBySymbol.get(symbol);
        if (stores == null) {
            return null;
        }
        WindowStore store = stores.get(periodMs);
        if (store == null) {
            throw new TickRejectedException(RejectReason.UNSUPPORTED_WINDOW_SIZE,
                    "window size " + periodMs + "ms is not configured for symbol " + symbol);
        }
        return store;
    }

    /** 惰性为某标的创建全部周期的窗口表。 */
    private ConcurrentHashMap<Long, WindowStore> storesFor(String symbol) {
        return storesBySymbol.computeIfAbsent(symbol, s -> {
            ConcurrentHashMap<Long, WindowStore> created = new ConcurrentHashMap<>();
            for (long size : periodSizes) {
                created.put(size, new WindowStore(size, properties.getMaxWindowsPerSymbol()));
            }
            return created;
        });
    }

    /**
     * 上报一笔成交（调用前须已通过校验）。
     *
     * <p>处理顺序（任一步失败都不会留下部分写入）：
     * <ol>
     *   <li>对所有周期检查目标窗口是否已回收 —— 任一周期已回收则 WINDOW_EVICTED 拒绝；</li>
     *   <li>幂等去重 —— 重复 tickId 返回 DUPLICATE；</li>
     *   <li>在每个周期各计入一次，再各自按上限回收。</li>
     * </ol>
     */
    public IngestResult ingest(TradeTick tick) {
        ConcurrentHashMap<Long, WindowStore> stores = storesFor(tick.symbol());

        // 阶段 1：回收检查先于一切状态变更，保证不会在部分周期写入
        for (long size : periodSizes) {
            stores.get(size).checkNotEvicted(windowStartOf(tick.timestamp(), size));
        }

        // 阶段 2：幂等（去重表不随回收删除）
        Set<String> seen = seenTickIds.computeIfAbsent(tick.symbol(),
                k -> ConcurrentHashMap.newKeySet());
        long defaultWindowStart = windowStartOf(tick.timestamp(), defaultWindowSizeMs);
        if (!seen.add(tick.tickId())) {
            return IngestResult.duplicate(tick, defaultWindowStart);
        }

        // 阶段 3：每个周期各计一次，随后各自回收最旧窗口
        for (long size : periodSizes) {
            stores.get(size).apply(tick);
        }
        return IngestResult.accepted(tick, defaultWindowStart);
    }

    /**
     * 查询某标的某时刻所属窗口的默认周期 K 线一致快照。
     */
    public Optional<Kline> klineAt(String symbol, long timestamp) {
        return klineAt(symbol, defaultWindowSizeMs, timestamp);
    }

    /**
     * 查询某标的某时刻所属窗口、指定周期的 K 线一致快照。
     * 未配置周期抛 UNSUPPORTED_WINDOW_SIZE；窗口已回收或无成交返回 empty。
     */
    public Optional<Kline> klineAt(String symbol, long periodMs, long timestamp) {
        WindowStore store = requireStore(symbol, periodMs);
        return store == null ? Optional.empty() : store.klineAt(symbol, timestamp);
    }

    /**
     * 查询某标的默认周期全部保留窗口的 K 线快照（按窗口起点升序）。
     */
    public List<Kline> klines(String symbol) {
        return klines(symbol, defaultWindowSizeMs);
    }

    /**
     * 查询某标的指定周期全部保留窗口的 K 线快照（按窗口起点升序）。
     */
    public List<Kline> klines(String symbol, long periodMs) {
        WindowStore store = requireStore(symbol, periodMs);
        return store == null ? List.of() : store.all(symbol);
    }

    /**
     * 按时间范围查询某标的指定周期的连续窗口槽位（窗口起点落在 [from, to)）。
     *
     * <p>整批结果在同一 store 锁内生成，来自同一时刻的一致视图。每个槽位带状态：
     * FILLED（有成交）、EMPTY（保留范围内无成交）、UNAVAILABLE（已回收或系统无此段数据）。
     * 起止颠倒或跨度超过 kline.max-range-windows 时以 INVALID_RANGE 拒绝；
     * 未配置周期以 UNSUPPORTED_WINDOW_SIZE 拒绝。
     */
    public List<KlineRangeEntry> klinesRange(String symbol, long periodMs, long from, long to) {
        if (from >= to) {
            throw new TickRejectedException(RejectReason.INVALID_RANGE,
                    "from must be strictly before to, got from=" + from + ", to=" + to);
        }
        if (from < 0 || to < 0) {
            throw new TickRejectedException(RejectReason.INVALID_RANGE,
                    "from/to must be non-negative epoch millis, got from=" + from + ", to=" + to);
        }
        long slotCount = ceilDiv(to, periodMs) - ceilDiv(from, periodMs);
        if (slotCount > properties.getMaxRangeWindows()) {
            throw new TickRejectedException(RejectReason.INVALID_RANGE,
                    "range covers " + slotCount + " windows of " + periodMs + "ms, exceeds limit "
                            + properties.getMaxRangeWindows());
        }
        WindowStore store = requireStore(symbol, periodMs);
        if (store == null) {
            // 该标的从未出现：系统中根本没有这段数据，逐槽位明确标注 UNAVAILABLE
            List<KlineRangeEntry> entries = new ArrayList<>((int) slotCount);
            long firstIndex = ceilDiv(from, periodMs);
            long endIndex = ceilDiv(to, periodMs);
            for (long i = firstIndex; i < endIndex; i++) {
                long start = Math.multiplyExact(i, periodMs);
                entries.add(KlineRangeEntry.unavailable(start, Math.addExact(start, periodMs)));
            }
            return entries;
        }
        return store.range(symbol, from, to);
    }
}
