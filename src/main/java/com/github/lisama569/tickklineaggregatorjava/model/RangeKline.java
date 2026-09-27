package com.github.lisama569.tickklineaggregatorjava.model;

/**
 * 范围查询结果中的单个窗口。
 *
 * @param state       窗口状态：有数据 / 跨度内空窗 / 超出保留范围
 * @param windowStart 窗口起点（epoch 毫秒）
 * @param windowEnd   窗口终点（epoch 毫秒，左闭右开）
 * @param kline       仅当 state=HAS_DATA 时非空，其余状态为 null
 */
public record RangeKline(
        RangeWindowState state,
        long windowStart,
        long windowEnd,
        Kline kline) {

    public static RangeKline hasData(Kline k) {
        return new RangeKline(RangeWindowState.HAS_DATA, k.windowStart(), k.windowEnd(), k);
    }

    public static RangeKline empty(long windowStart, long windowEnd) {
        return new RangeKline(RangeWindowState.EMPTY, windowStart, windowEnd, null);
    }

    public static RangeKline notRetained(long windowStart, long windowEnd) {
        return new RangeKline(RangeWindowState.NOT_RETAINED, windowStart, windowEnd, null);
    }
}
