package com.github.lisama569.tickklineaggregatorjava.service;

import com.github.lisama569.tickklineaggregatorjava.domain.IngestResult;
import com.github.lisama569.tickklineaggregatorjava.domain.KlineBar;
import com.github.lisama569.tickklineaggregatorjava.domain.Trade;
import com.github.lisama569.tickklineaggregatorjava.domain.WindowKey;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 线程安全的 K 线聚合状态容器。
 *
 * <p>一致性模型：所有状态变更在同一把锁内一次性完成（去重判定、窗口定位、
 * 开高低收量笔数全部更新），并且窗口状态是不可变对象，更新时整体替换。
 * 因此查询要么读到更新前的完整 K 线，要么读到更新后的完整 K 线，
 * 绝不会读到“成交量已增加但最高价尚未更新”这类半更新视图。</p>
 *
 * <p>乱序/迟到修正：窗口归属仅由成交时间戳做 floor 除法决定，与到达顺序无关。
 * 开盘价取窗口内排序键（时间戳, tradeId）最小的成交价，
 * 收盘价取排序键最大的成交价；同一组成交无论以何种顺序到达，
 * 最终 K 线唯一且可复现。</p>
 *
 * <p>幂等：以全局唯一 tradeId 去重，重复提交（无论并发还是串行）
 * 都返回 duplicate=true 且不改变任何聚合值。</p>
 */
@Component
public class KlineAggregator {

    private final long windowMillis;

    private final Object lock = new Object();
    /** 已计入的成交 ID（全局幂等键）。 */
    private final Set<String> seenTradeIds = new HashSet<>();
    /** 窗口不可变状态表。 */
    private final Map<WindowKey, WindowState> windows = new HashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public KlineAggregator(AggregatorProperties properties) {
        this(properties.getWindowMillis());
    }

    KlineAggregator(long windowMillis) {
        if (windowMillis <= 0) {
            throw new IllegalArgumentException("windowMillis 必须为正数: " + windowMillis);
        }
        this.windowMillis = windowMillis;
    }

    /**
     * 返回成交所属窗口的起始时间（窗口为左闭右开区间
     * [start, start + windowMillis)）。边界点 start + k*windowMillis
     * 归属于右侧窗口，绝不重复计入相邻窗口。
     */
    public long windowStartFor(long timestampMillis) {
        return Math.floorDiv(timestampMillis, windowMillis) * windowMillis;
    }

    /** 返回窗口长度（毫秒）。 */
    public long windowSpanMillis() {
        return windowMillis;
    }

    /**
     * 原子地计入一笔成交。
     *
     * @return accepted=true 表示首次计入；duplicate=true 表示重复提交且状态不变
     */
    public IngestResult ingest(Trade trade) {
        long start = windowStartFor(trade.timestampMillis());
        WindowKey key = new WindowKey(trade.symbol(), start);
        synchronized (lock) {
            if (!seenTradeIds.add(trade.tradeId())) {
                return new IngestResult(false, true, null, key);
            }
            WindowState current = windows.get(key);
            WindowState updated = current == null
                    ? WindowState.first(trade)
                    : current.mergedWith(trade);
            windows.put(key, updated);
            return new IngestResult(true, false, null, key);
        }
    }

    /**
     * 返回指定窗口的一致 K 线快照；timestampMillis 落在哪个窗口就查哪个。
     * 窗口不存在（尚无成交）时返回 null。
     */
    public KlineBar getBar(String symbol, long timestampMillis) {
        long start = windowStartFor(timestampMillis);
        WindowKey key = new WindowKey(symbol, start);
        synchronized (lock) {
            WindowState state = windows.get(key);
            return state == null ? null : state.toBar(symbol, start, start + windowMillis);
        }
    }

    /**
     * 单个窗口的不可变聚合状态。每次合并产生全新实例并整体替换 Map 中的引用，
     * 查询线程拿到的引用要么是旧状态要么是新状态，不存在字段级半更新。
     */
    private record WindowState(String firstId, long firstTs, BigDecimal open,
                               String lastId, long lastTs, BigDecimal close,
                               BigDecimal high, BigDecimal low,
                               BigDecimal volume, int tradeCount) {

        static WindowState first(Trade t) {
            return new WindowState(
                    t.tradeId(), t.timestampMillis(), t.price(),
                    t.tradeId(), t.timestampMillis(), t.price(),
                    t.price(), t.price(), t.volume(), 1);
        }

        WindowState mergedWith(Trade t) {
            // 开盘：排序键（时间戳, tradeId）升序的第一项
            boolean isFirst = t.timestampMillis() < firstTs
                    || (t.timestampMillis() == firstTs && t.tradeId().compareTo(firstId) < 0);
            // 收盘：排序键（时间戳, tradeId）升序的最后一项
            boolean isLast = t.timestampMillis() > lastTs
                    || (t.timestampMillis() == lastTs && t.tradeId().compareTo(lastId) > 0);

            return new WindowState(
                    isFirst ? t.tradeId() : firstId,
                    isFirst ? t.timestampMillis() : firstTs,
                    isFirst ? t.price() : open,
                    isLast ? t.tradeId() : lastId,
                    isLast ? t.timestampMillis() : lastTs,
                    isLast ? t.price() : close,
                    high.max(t.price()),
                    low.min(t.price()),
                    volume.add(t.volume()),
                    tradeCount + 1);
        }

        KlineBar toBar(String symbol, long start, long end) {
            return new KlineBar(symbol, start, end,
                    open, high, low, close, volume, tradeCount);
        }
    }
}
