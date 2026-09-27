package com.github.lisama569.tickklineaggregatorjava.service;

import com.github.lisama569.tickklineaggregatorjava.config.KlineProperties;
import com.github.lisama569.tickklineaggregatorjava.exception.TickRejectedException;
import com.github.lisama569.tickklineaggregatorjava.model.IngestResult;
import com.github.lisama569.tickklineaggregatorjava.model.Kline;
import com.github.lisama569.tickklineaggregatorjava.model.KlineRange;
import com.github.lisama569.tickklineaggregatorjava.model.RangeKline;
import com.github.lisama569.tickklineaggregatorjava.model.RejectReason;
import com.github.lisama569.tickklineaggregatorjava.model.TradeTick;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * K 线聚合核心。
 *
 * <p>规则：
 * <ul>
 *   <li>窗口为左闭右开 [windowStart, windowStart + windowSizeMs)，
 *       windowStart = floorDiv(timestamp, windowSizeMs) * windowSizeMs，
 *       恰好落在窗口起点的成交归属该窗口，不会计入相邻窗口。</li>
 *   <li>多周期：同一笔成交原子地计入每个已配置周期一次，周期各自独立查询；
 *       周期均为最小周期的整数倍，大周期恰好由连续小周期拼成，跨周期可对齐。</li>
 *   <li>幂等：同一 symbol 下相同 tickId 只计入一次，重复提交返回 DUPLICATE。
 *       去重表不随窗口回收而清理，回收不影响幂等判定。</li>
 *   <li>乱序/迟到：open 取窗口内时间戳最小成交的价格，close 取时间戳最大者；
 *       时间戳相同按 tickId 字典序打破平局，结果与到达顺序无关、唯一确定。</li>
 *   <li>保留与回收：每个标的、每个周期最多保留 maxWindowsPerPeriod 个有数据窗口，
 *       超出自动回收最旧窗口；本应落入已回收窗口的迟到成交以 WINDOW_EVICTED 显式拒绝。</li>
 *   <li>一致性：每个标的的全部周期共享一把内部锁，摄入跨周期原子、查询为同一时刻快照；
 *       不会读到半更新，范围查询整批窗口视图一致。</li>
 * </ul>
 */
@Component
public class KlineAggregator {

    /**
     * 单个窗口的可变聚合状态。仅在所属标的的 {@link SymbolState} 锁内访问。
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
     * 单个周期在单个标的上的聚合状态。
     */
    private static final class PeriodState {
        /** 窗口起点 -> 聚合状态，TreeMap 保证按起点有序、回收最旧 */
        final TreeMap<Long, KlineState> windows = new TreeMap<>();
        /**
         * 曾接受过的最大窗口起点（只进不退的高水位）。
         * 回收硬下界 = highWater - (上限-1)×周期；即便之后全是迟到成交，
         * 下界也不会后退，已回收窗口不会被重建，保留窗口数永远不超过上限。
         */
        Long highWater = null;
    }

    /**
     * 单个标的的全部聚合状态。所有字段仅在实例锁内访问：
     * 多周期摄入原子完成，范围查询拿到同一时刻的一致视图。
     */
    private static final class SymbolState {
        /** 周期毫秒数 -> 该周期聚合状态 */
        final Map<Long, PeriodState> byPeriod = new HashMap<>();
        /** 幂等去重表，不随窗口回收清理 */
        final Set<String> seenTickIds = ConcurrentHashMap.newKeySet();

        PeriodState periodState(long periodMs) {
            return byPeriod.computeIfAbsent(periodMs, k -> new PeriodState());
        }
    }

    private final KlineProperties properties;
    /** 已配置周期，升序去重；首个即主周期（默认查询周期，与单周期版本兼容） */
    private final long[] periods;
    private final int maxWindowsPerPeriod;
    private final ConcurrentHashMap<String, SymbolState> symbols = new ConcurrentHashMap<>();

    public KlineAggregator(KlineProperties properties) {
        this.properties = properties;
        this.periods = resolvePeriods(properties);
        this.maxWindowsPerPeriod = properties.getMaxWindowsPerPeriod();
        if (maxWindowsPerPeriod < 1) {
            throw new IllegalArgumentException(
                    "kline.max-windows-per-period must be >= 1, got " + maxWindowsPerPeriod);
        }
    }

    private static long[] resolvePeriods(KlineProperties props) {
        List<Long> configured = props.getWindowSizesMs() == null
                ? List.of() : props.getWindowSizesMs().stream().filter(java.util.Objects::nonNull).toList();
        long[] raw = configured.isEmpty()
                ? new long[]{props.getWindowSizeMs()}
                : configured.stream().mapToLong(Long::longValue).distinct().sorted().toArray();
        for (long p : raw) {
            if (p <= 0) {
                throw new IllegalArgumentException("kline window size must be positive, got " + p);
            }
        }
        long min = raw[0];
        for (long p : raw) {
            if (p % min != 0) {
                // 不整除则大周期无法由整数个连续小周期拼成，跨周期不能严格对齐
                throw new IllegalArgumentException(
                        "kline.window-sizes-ms periods must be integer multiples of the smallest period "
                                + min + "ms, but got " + p + "ms");
            }
        }
        return raw;
    }

    /** 已配置周期（毫秒），升序；首个为主周期。 */
    public long[] periods() {
        return periods.clone();
    }

    /** 主周期（默认查询周期），与单周期版本的 windowSizeMs 兼容。 */
    public long primaryPeriodMs() {
        return periods[0];
    }

    private long requirePeriod(long windowSizeMs) {
        for (long p : periods) {
            if (p == windowSizeMs) {
                return p;
            }
        }
        throw new TickRejectedException(RejectReason.UNKNOWN_PERIOD,
                "period " + windowSizeMs + "ms is not configured; configured periods: " + java.util.Arrays.toString(periods));
    }

    /** 计算成交在主周期上所属窗口起点（左闭右开）。 */
    public long windowStartOf(long timestamp) {
        return windowStartOf(primaryPeriodMs(), timestamp);
    }

    /** 计算成交在指定周期上所属窗口起点（左闭右开）。 */
    public long windowStartOf(long windowSizeMs, long timestamp) {
        return Math.floorDiv(timestamp, windowSizeMs) * windowSizeMs;
    }

    private SymbolState stateOf(String symbol) {
        return symbols.computeIfAbsent(symbol, k -> new SymbolState());
    }

    /**
     * 上报一笔成交（调用前须已通过基础校验）。
     * 同一笔成交原子计入每个已配置周期；重复 tickId 返回 DUPLICATE；
     * 迟到成交落入任一周期已回收窗口则以 WINDOW_EVICTED 拒绝，且不留任何部分写入。
     */
    public IngestResult ingest(TradeTick tick) {
        SymbolState state = stateOf(tick.symbol());

        // 各周期归属窗口
        Map<Long, Long> windowStarts = new LinkedHashMap<>();
        for (long period : periods) {
            windowStarts.put(period, windowStartOf(period, tick.timestamp()));
        }
        long primaryStart = windowStarts.get(primaryPeriodMs());

        synchronized (state) {
            // 幂等优先：窗口即便已回收，重复提交仍是 DUPLICATE（可复现、不重复计入）
            if (!state.seenTickIds.add(tick.tickId())) {
                return IngestResult.duplicate(tick, primaryStart, windowStarts);
            }

            // 回收预检（全部周期检查通过才写入，保证跨周期原子、无残缺窗口）。
            // 每个周期按各自只进不退的高水位判定：硬下界 = highWater - (上限-1) 个周期。
            // 保留槽位打满后更早的迟到成交被拒绝；稀疏行情下窗口间的正常间隔与补漏不受影响，
            // 迟到成交也不会让下界后退、重建已回收窗口。跨周期之间的嵌套裁剪在写入后进行。
            for (long period : periods) {
                PeriodState ps = state.periodState(period);
                long target = windowStarts.get(period);
                if (ps.highWater != null) {
                    long evictFloor = ps.highWater - (long) (maxWindowsPerPeriod - 1) * period;
                    if (target < evictFloor) {
                        throw new TickRejectedException(RejectReason.WINDOW_EVICTED,
                                "tick " + tick.tickId() + " timestamp " + tick.timestamp()
                                        + " belongs to period " + period + "ms window starting " + target
                                        + ", older than the eviction floor " + evictFloor
                                        + " (high-water window starting " + ps.highWater
                                        + ", retention limit " + maxWindowsPerPeriod + " windows per period)");
                    }
                }
            }

            // 写入每个周期，推进高水位，并按上限回收最旧窗口
            for (long period : periods) {
                PeriodState ps = state.periodState(period);
                long target = windowStarts.get(period);
                ps.windows.computeIfAbsent(target, k -> new KlineState()).apply(tick);
                if (ps.highWater == null || target > ps.highWater) {
                    ps.highWater = target;
                }
                while (ps.windows.size() > maxWindowsPerPeriod) {
                    ps.windows.pollFirstEntry();
                }
            }

            // 跨周期嵌套回收：以最小周期只进不退的高水位推算保留时间下界，并向下对齐到
            // 更大周期自身的边界。起点早于该对齐下界的大窗口，其覆盖范围内会含有落在
            // 保留时间范围之外的小窗口，无法再由现存小周期完整合成，必须一并回收；
            // 起点不小于该下界的大窗口则完整位于范围内、严格可合成，必须保留。
            // 用高水位（而非现存最旧窗口）使得稀疏行情下的空窗不会误触发大窗口回收。
            long minPeriod = periods[0];
            PeriodState minPs = state.periodState(minPeriod);
            if (minPs.highWater != null) {
                for (int i = 1; i < periods.length; i++) {
                    long p = periods[i];
                    long timeFloor = minPs.highWater - (long) (maxWindowsPerPeriod - 1) * minPeriod;
                    long bigFloor = Math.floorDiv(timeFloor, p) * p;
                    PeriodState ps = state.periodState(p);
                    while (!ps.windows.isEmpty() && ps.windows.firstKey() < bigFloor) {
                        ps.windows.pollFirstEntry();
                    }
                }
            }
            return IngestResult.accepted(tick, primaryStart, windowStarts);
        }
    }

    /**
     * 查询某标的某时刻在主周期所属窗口的 K 线一致快照。
     */
    public Optional<Kline> klineAt(String symbol, long timestamp) {
        return klineAt(symbol, timestamp, primaryPeriodMs());
    }

    /**
     * 查询某标的某时刻在指定周期所属窗口的 K 线一致快照；未配置周期返回 UNKNOWN_PERIOD。
     */
    public Optional<Kline> klineAt(String symbol, long timestamp, long windowSizeMs) {
        long period = requirePeriod(windowSizeMs);
        SymbolState state = symbols.get(symbol);
        if (state == null) {
            return Optional.empty();
        }
        long start = windowStartOf(period, timestamp);
        synchronized (state) {
            PeriodState ps = state.byPeriod.get(period);
            KlineState ws = ps == null ? null : ps.windows.get(start);
            return ws == null ? Optional.empty()
                    : Optional.of(ws.snapshot(symbol, start, period));
        }
    }

    /**
     * 查询某标的主周期全部保留窗口的 K 线快照（按窗口起点升序）。
     */
    public List<Kline> klines(String symbol) {
        return klines(symbol, primaryPeriodMs());
    }

    /**
     * 查询某标的指定周期全部保留窗口的 K 线快照（按窗口起点升序）。
     */
    public List<Kline> klines(String symbol, long windowSizeMs) {
        long period = requirePeriod(windowSizeMs);
        List<Kline> result = new ArrayList<>();
        SymbolState state = symbols.get(symbol);
        if (state == null) {
            return result;
        }
        synchronized (state) {
            PeriodState ps = state.byPeriod.get(period);
            if (ps != null) {
                ps.windows.forEach((start, ws) -> result.add(ws.snapshot(symbol, start, period)));
            }
        }
        result.sort(Comparator.comparingLong(Kline::windowStart));
        return result;
    }

    /**
     * 按时间范围查询某标的主周期的连续 K 线，整批来自同一时刻的一致视图。
     *
     * <p>窗口状态可区分：HAS_DATA（有成交）、EMPTY（已观察跨度内的无成交窗口）、
     * NOT_RETAINED（早于回收下限或尚无数据）。空窗仅为查询时占位，不落库、不参与回收。
     */
    public KlineRange klinesRange(String symbol, long from, long to) {
        return klinesRange(symbol, primaryPeriodMs(), from, to);
    }

    /**
     * 按时间范围查询某标的指定周期的连续 K 线。from/to 为 epoch 毫秒，均为含；
     * 返回与 [from, to] 相交的全部完整窗口，按起点升序，整批视图一致。
     */
    public KlineRange klinesRange(String symbol, long windowSizeMs, long from, long to) {
        long period = requirePeriod(windowSizeMs);
        if (from <= 0 || to <= 0 || from > to) {
            throw new TickRejectedException(RejectReason.INVALID_RANGE,
                    "range requires 0 < from <= to, got from=" + from + ", to=" + to);
        }

        long firstStart = windowStartOf(period, from);
        if (firstStart < from) {
            firstStart += period; // 与 from 相交的首个窗口
        }
        long lastStart = windowStartOf(period, to); // 容纳 to 的窗口（与 to 相交的最后窗口）

        List<RangeKline> windows = new ArrayList<>();
        Long oldest = null;
        Long newest = null;
        SymbolState state = symbols.get(symbol);
        if (state != null) {
            synchronized (state) {
                PeriodState ps = state.byPeriod.get(period);
                TreeMap<Long, KlineState> map = ps == null ? null : ps.windows;
                if (map != null && !map.isEmpty()) {
                    oldest = map.firstKey();
                    newest = map.lastKey();
                }
                for (long start = firstStart; start <= lastStart; start += period) {
                    KlineState ws = map == null ? null : map.get(start);
                    windows.add(classify(symbol, period, start, ws, oldest, newest));
                }
            }
        } else {
            for (long start = firstStart; start <= lastStart; start += period) {
                windows.add(RangeKline.notRetained(start, start + period));
            }
        }
        return new KlineRange(symbol, period, from, to, oldest, newest, windows);
    }

    private RangeKline classify(String symbol, long period, long start, KlineState ws,
                                Long oldest, Long newest) {
        if (ws != null) {
            return RangeKline.hasData(ws.snapshot(symbol, start, period));
        }
        if (oldest != null && start >= oldest && start <= newest) {
            return RangeKline.empty(start, start + period);
        }
        return RangeKline.notRetained(start, start + period);
    }
}
