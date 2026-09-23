package com.github.lisama569.tickklineaggregatorjava.model;

/**
 * 一个时间窗口的 K 线不可变快照。
 * 窗口为左闭右开 [windowStart, windowEnd)。
 */
public record Kline(
        String symbol,
        long windowStart,
        long windowEnd,
        double open,
        double high,
        double low,
        double close,
        double volume,
        long tradeCount) {
}
