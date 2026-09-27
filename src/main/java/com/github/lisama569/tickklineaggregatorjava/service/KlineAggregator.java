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
import java.util.HashSet;
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
 *       各计一次且只计一次。配置的每个周期必须是最小（基础）周期的<b>整数倍</b>，
 *       否则大周期窗口边界永远无法与小周期对齐，构造时即以
 *       {@code INVALID_PERIOD_CONFIG} 拒绝启动。由于各周期由同一成交流按相同的确定性
 *       规则独立聚合，大周期窗口的 open/high/low/close/volume/tradeCount 与其覆盖的
 *       全部小周期窗口合起来必然严格一致。</li>
 *   <li>幂等：同一 symbol 下相同 tickId 只计入一次，重复提交返回 DUPLICATE。
 *       去重表不随窗口回收而删除，<b>幂等判定先于回收判定</b>：一笔成交只要曾经被
 *       接受过，无论其窗口后来是否被回收，再次上报永远是 DUPLICATE，不会重复计入，
 *       也不会变成 WINDOW_EVICTED。</li>
 *   <li>乱序/迟到：open 取窗口内时间戳最小成交的价格，close 取时间戳最大成交的价格；
 *       时间戳相同按 tickId 字典序打破平局，结果与到达顺序无关、唯一确定。</li>
 *   <li>一致性：同一标的的所有读写在同一把 SymbolEntry 锁内完成。单笔成交的
 *       「幂等判定 → 全周期回收检查 → 全周期写入与回收」是一个原子操作，并发上报
 *       结论与串行完全一致，不会出现部分周期写入；范围查询返回的一批窗口来自同一
 *       时刻的一致视图。</li>
 *   <li>保留回收：每个标的、每个周期最多保留 maxWindowsPerSymbol 个窗口，超出后
 *       按窗口起点升序回收最旧窗口，并推进回收水位线。迟到成交若落入任一已回收窗口，
 *       在任何状态变更之前即以 WINDOW_EVICTED 显式拒绝，不生成残缺窗口、不重复计入。
 *       回收只由已接受的不同成交驱动，与墙钟无关，结论可复现。</li>
 *   <li>跨周期可信性：回收只保证每个周期自身窗口数有界，而相同上限下最小周期水位线
 *       推进最快。大周期窗口只有在其起点不早于最小周期回收水位线时，才能由仍然保留
 *       的全部小周期窗口完整对上；否则即使大周期自身仍有聚合值，也按“不可用”处理：
 *       单点查询以 WINDOW_UNVERIFIABLE 拒绝，范围查询标注 UNVERIFIABLE，列表查询
 *       直接剔除，绝不把对不上的大周期当成正常结果返回。</li>
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
     * 单个窗口的可变聚合状态。仅在所属 SymbolEntry 的锁内被读写，无需自带同步。
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
     * 单一标的、单一周期的窗口表。所有方法都在所属 {@link SymbolEntry} 的锁内被调用。
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

        private long windowSizeMs() {
            return windowSizeMs;
        }

        /** 回收水位线：窗口起点小于该值即已回收。 */
        private long evictedBefore() {
            return evictedBefore;
        }

        /** 窗口是否已被回收；是则抛出可区分的拒绝异常，不改动任何状态。 */
        private void checkNotEvicted(long windowStart) {
            if (windowStart < evictedBefore) {
                throw new TickRejectedException(RejectReason.WINDOW_EVICTED,
                        "window starting at " + windowStart + " (size=" + windowSizeMs
                                + "ms) has been evicted by retention; evictedBefore=" + evictedBefore);
            }
        }

        /** 计入一笔成交，随后按上限回收最旧窗口（回收顺序确定、与墙钟无关）。 */
        private void apply(TradeTick tick) {
            long windowStart = windowStartOf(tick.timestamp(), windowSizeMs);
            windows.computeIfAbsent(windowStart, k -> new KlineState()).apply(tick);
            while (windows.size() > maxWindows) {
                Map.Entry<Long, KlineState> oldest = windows.pollFirstEntry();
                evictedBefore = oldest.getKey() + windowSizeMs;
            }
        }

        private KlineState stateAt(long windowStart) {
            return windows.get(windowStart);
        }

        private List<Kline> all(String symbol, long notBefore) {
            List<Kline> result = new ArrayList<>(windows.size());
            for (Map.Entry<Long, KlineState> e : windows.entrySet()) {
                // 起点早于最小周期回收水位线的大周期窗口无法由保留小周期对上，不返回
                if (e.getKey() >= notBefore) {
                    result.add(e.getValue().snapshot(symbol, e.getKey(), windowSizeMs));
                }
            }
            return result; // TreeMap 已按窗口起点升序
        }
    }

    /**
     * 单一标的的全部状态：各周期窗口表 + 幂等去重表。实例本身即锁对象：
     * 该标的的 ingest 与所有查询都在 synchronized(entry) 内完成，结论与串行一致。
     */
    private final class SymbolEntry {
        private final ConcurrentHashMap<Long, WindowStore> stores;
        private final Set<String> seenTickIds = new HashSet<>();

        private SymbolEntry() {
            ConcurrentHashMap<Long, WindowStore> created = new ConcurrentHashMap<>();
            for (long size : periodSizes) {
                created.put(size, new WindowStore(size, properties.getMaxWindowsPerSymbol()));
            }
            this.stores = created;
        }
    }

    private final KlineProperties properties;
    /** 已配置的全部周期（毫秒），升序、去重；首个即最小（基础）周期 */
    private final long[] periodSizes;
    /** 最小（基础）周期：回收水位线对它最紧，也是跨周期对齐/可信性判定的基准 */
    private final long baseWindowSizeMs;
    private final long defaultWindowSizeMs;

    /** symbol -> 该标的的全部状态 */
    private final ConcurrentHashMap<String, SymbolEntry> entries = new ConcurrentHashMap<>();

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
        this.baseWindowSizeMs = periodSizes[0];
        validatePeriodConfig();
        if (properties.getMaxWindowsPerSymbol() <= 0) {
            throw new IllegalArgumentException(
                    "maxWindowsPerSymbol must be positive, got " + properties.getMaxWindowsPerSymbol());
        }
    }

    /**
     * 周期组合校验：每个周期必须为正，且必须是最小（基础）周期的整数倍。
     * 不满足整数倍时窗口边界永远无法对齐（如 60s + 90s），大周期成交量/笔数
     * 不可能与覆盖的小周期对上，因此在启动时以可区分原因拒绝，服务不带病运行。
     */
    private void validatePeriodConfig() {
        for (long size : periodSizes) {
            if (size <= 0) {
                throw new TickRejectedException(RejectReason.INVALID_PERIOD_CONFIG,
                        "window size must be positive, got " + size);
            }
            if (size % baseWindowSizeMs != 0) {
                throw new TickRejectedException(RejectReason.INVALID_PERIOD_CONFIG,
                        "configured window size " + size + "ms is not an integer multiple of base window size "
                                + baseWindowSizeMs + "ms; window boundaries would never align and multi-period "
                                + "aggregates could not be reconciled");
            }
        }
    }

    /** 已配置的全部聚合周期（毫秒），升序；首个为最小（基础）周期。 */
    public long[] configuredPeriods() {
        return periodSizes.clone();
    }

    /** 计算成交在默认周期下所属窗口起点（左闭右开）。 */
    public long windowStartOf(long timestamp) {
        return windowStartOf(timestamp, defaultWindowSizeMs);
    }

    /** 惰性为某标的创建全部周期的窗口表与去重表。 */
    private SymbolEntry entryFor(String symbol) {
        return entries.computeIfAbsent(symbol, s -> new SymbolEntry());
    }

    /** 取已配置周期的窗口表，未配置周期抛可区分异常。调用时须持有 entry 锁。 */
    private WindowStore requireStore(SymbolEntry entry, long periodMs) {
        WindowStore store = entry.stores.get(periodMs);
        if (store == null) {
            throw new TickRejectedException(RejectReason.UNSUPPORTED_WINDOW_SIZE,
                    "window size " + periodMs + "ms is not configured; configured periods="
                            + describePeriods());
        }
        return store;
    }

    private String describePeriods() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < periodSizes.length; i++) {
            sb.append(i == 0 ? "" : ", ").append(periodSizes[i]);
        }
        return sb.append("]ms").toString();
    }

    /**
     * 上报一笔成交（调用前须已通过校验）。整段在该标的的 entry 锁内原子完成：
     * <ol>
     *   <li>幂等判定 —— 重复 tickId 直接返回 DUPLICATE，先于回收判定，
     *       已接受过的成交即使窗口已回收也永远是重复提交，不引起任何状态变更；</li>
     *   <li>对所有周期检查目标窗口是否已回收 —— 任一周期已回收则 WINDOW_EVICTED 拒绝；</li>
     *   <li>在每个周期各计入一次，再各自按上限回收。</li>
     * </ol>
     */
    public IngestResult ingest(TradeTick tick) {
        SymbolEntry entry = entryFor(tick.symbol());
        synchronized (entry) {
            long defaultWindowStart = windowStartOf(tick.timestamp(), defaultWindowSizeMs);

            // 阶段 1：幂等判定先于一切（含回收检查）。去重表只记录“已接受”的成交，
            // 不随回收删除：曾经接受过的成交即使窗口已回收也永远判 DUPLICATE
            if (entry.seenTickIds.contains(tick.tickId())) {
                return IngestResult.duplicate(tick, defaultWindowStart);
            }

            // 阶段 2：全周期回收检查先于任何写入与去重登记，保证：
            //  (a) 被拒绝的迟到成交不会留下部分周期写入；
            //  (b) 从未被接受的成交不会占用去重表，重复提交仍是 WINDOW_EVICTED（结论可复现）
            for (long size : periodSizes) {
                entry.stores.get(size).checkNotEvicted(windowStartOf(tick.timestamp(), size));
            }

            // 阶段 3：登记幂等并在每个周期各计一次，随后各自回收最旧窗口
            entry.seenTickIds.add(tick.tickId());
            for (long size : periodSizes) {
                entry.stores.get(size).apply(tick);
            }
            return IngestResult.accepted(tick, defaultWindowStart);
        }
    }

    /**
     * 查询某标的某时刻所属窗口的默认周期 K 线一致快照。
     */
    public Optional<Kline> klineAt(String symbol, long timestamp) {
        return klineAt(symbol, defaultWindowSizeMs, timestamp);
    }

    /**
     * 查询某标的某时刻所属窗口、指定周期的 K 线一致快照。
     * 未配置周期抛 UNSUPPORTED_WINDOW_SIZE；大周期窗口虽有数据但覆盖的最小周期窗口
     * 已回收、无法完整核对时抛 WINDOW_UNVERIFIABLE；窗口已回收或无成交返回 empty。
     */
    public Optional<Kline> klineAt(String symbol, long periodMs, long timestamp) {
        SymbolEntry entry = entries.get(symbol);
        if (entry == null) {
            return Optional.empty();
        }
        synchronized (entry) {
            WindowStore store = requireStore(entry, periodMs);
            long windowStart = windowStartOf(timestamp, periodMs);
            KlineState state = store.stateAt(windowStart);
            if (state == null) {
                return Optional.empty();
            }
            if (periodMs != baseWindowSizeMs) {
                long baseEvictedBefore = entry.stores.get(baseWindowSizeMs).evictedBefore();
                if (windowStart < baseEvictedBefore) {
                    throw new TickRejectedException(RejectReason.WINDOW_UNVERIFIABLE,
                            "window starting at " + windowStart + " (period=" + periodMs
                                    + "ms) still holds aggregate data, but some "
                                    + "base-period (" + baseWindowSizeMs + "ms) windows it covers were "
                                    + "evicted (baseEvictedBefore=" + baseEvictedBefore
                                    + "); the aggregate cannot be reconciled and is unavailable");
                }
            }
            return Optional.of(state.snapshot(symbol, windowStart, periodMs));
        }
    }

    /**
     * 查询某标的默认周期全部保留窗口的 K 线快照（按窗口起点升序）。
     */
    public List<Kline> klines(String symbol) {
        return klines(symbol, defaultWindowSizeMs);
    }

    /**
     * 查询某标的指定周期全部保留窗口的 K 线快照（按窗口起点升序）。
     * 大周期中无法由仍然保留的最小周期窗口完整对上的旧窗口直接剔除——
     * 本接口返回的每一个大周期窗口都保证能与现存小周期完整对上。
     */
    public List<Kline> klines(String symbol, long periodMs) {
        SymbolEntry entry = entries.get(symbol);
        if (entry == null) {
            return List.of();
        }
        synchronized (entry) {
            WindowStore store = requireStore(entry, periodMs);
            long notBefore = periodMs == baseWindowSizeMs
                    ? Long.MIN_VALUE
                    : entry.stores.get(baseWindowSizeMs).evictedBefore();
            return store.all(symbol, notBefore);
        }
    }

    /**
     * 按时间范围查询某标的指定周期的连续窗口槽位（窗口起点落在 [from, to)）。
     *
     * <p>整批结果在该标的的 entry 锁内生成，来自同一时刻的一致视图。每个槽位带状态：
     * FILLED（有成交且可由保留小周期对上）、EMPTY（保留范围内无成交）、
     * UNAVAILABLE（该周期自身窗口已回收，或系统无此标的）、
     * UNVERIFIABLE（大周期自身有数据但覆盖的最小周期窗口已回收，数据不可信）。
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
        SymbolEntry entry = entries.get(symbol);
        if (entry == null) {
            // 该标的从未出现：系统中根本没有这段数据，逐槽位明确标注 UNAVAILABLE
            return unavailableSlots(periodMs, from, to, slotCount);
        }
        synchronized (entry) {
            WindowStore store = requireStore(entry, periodMs);
            long baseEvictedBefore = periodMs == baseWindowSizeMs
                    ? Long.MIN_VALUE
                    : entry.stores.get(baseWindowSizeMs).evictedBefore();
            long firstIndex = ceilDiv(from, periodMs);
            long endIndex = ceilDiv(to, periodMs);
            List<KlineRangeEntry> entries = new ArrayList<>((int) Math.max(0, endIndex - firstIndex));
            for (long i = firstIndex; i < endIndex; i++) {
                long start = Math.multiplyExact(i, periodMs);
                long end = Math.addExact(start, periodMs);
                KlineState state = store.stateAt(start);
                if (state != null) {
                    if (start < baseEvictedBefore) {
                        // 大周期有值但覆盖的小周期已回收：不可信，按不可用显式标注
                        entries.add(KlineRangeEntry.unverifiable(start, end));
                    } else {
                        entries.add(KlineRangeEntry.filled(state.snapshot(symbol, start, periodMs)));
                    }
                } else if (start < store.evictedBefore()) {
                    entries.add(KlineRangeEntry.unavailable(start, end));
                } else {
                    entries.add(KlineRangeEntry.empty(start, end));
                }
            }
            return entries;
        }
    }

    private static List<KlineRangeEntry> unavailableSlots(long periodMs, long from, long to, long slotCount) {
        List<KlineRangeEntry> entries = new ArrayList<>((int) slotCount);
        long firstIndex = ceilDiv(from, periodMs);
        long endIndex = ceilDiv(to, periodMs);
        for (long i = firstIndex; i < endIndex; i++) {
            long start = Math.multiplyExact(i, periodMs);
            entries.add(KlineRangeEntry.unavailable(start, Math.addExact(start, periodMs)));
        }
        return entries;
    }
}
