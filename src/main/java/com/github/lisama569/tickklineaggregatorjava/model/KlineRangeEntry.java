package com.github.lisama569.tickklineaggregatorjava.model;

/**
 * 范围查询结果中的一个窗口槽位（左闭右开 [windowStart, windowEnd)）。
 *
 * @param windowStart 窗口起点
 * @param windowEnd   窗口终点
 * @param status      该窗口的数据状态：FILLED / EMPTY / UNAVAILABLE
 * @param kline       仅当 status=FILLED 时非空，为该窗口的不可变一致快照
 */
public record KlineRangeEntry(long windowStart, long windowEnd, WindowStatus status, Kline kline) {

    public static KlineRangeEntry filled(Kline kline) {
        return new KlineRangeEntry(kline.windowStart(), kline.windowEnd(), WindowStatus.FILLED, kline);
    }

    public static KlineRangeEntry empty(long windowStart, long windowEnd) {
        return new KlineRangeEntry(windowStart, windowEnd, WindowStatus.EMPTY, null);
    }

    public static KlineRangeEntry unavailable(long windowStart, long windowEnd) {
        return new KlineRangeEntry(windowStart, windowEnd, WindowStatus.UNAVAILABLE, null);
    }

    /**
     * 大周期窗口自身仍有聚合值，但覆盖的小周期窗口已被回收、无法完整核对：
     * 不携带 K 线，按不可用处理，避免调用方把不可信大周期当成正常结果。
     */
    public static KlineRangeEntry unverifiable(long windowStart, long windowEnd) {
        return new KlineRangeEntry(windowStart, windowEnd, WindowStatus.UNVERIFIABLE, null);
    }
}
