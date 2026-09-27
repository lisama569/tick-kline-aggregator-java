package com.github.lisama569.tickklineaggregatorjava.model;

import java.util.Map;

/**
 * 成交上报结果。
 *
 * @param status       处理状态
 * @param tickId       成交标识
 * @param symbol       标的
 * @param windowStart  主周期（默认周期）窗口起点，保持与单周期版本完全兼容
 * @param windowStarts 该笔成交在每个已配置周期上归属的窗口起点：周期毫秒数 -> 窗口起点。
 *                     同一笔成交在每个周期只计一次。
 */
public record IngestResult(IngestStatus status, String tickId, String symbol, long windowStart,
                           Map<Long, Long> windowStarts) {

    public static IngestResult accepted(TradeTick tick, long windowStart) {
        return accepted(tick, windowStart, Map.of());
    }

    public static IngestResult accepted(TradeTick tick, long windowStart, Map<Long, Long> windowStarts) {
        return new IngestResult(IngestStatus.ACCEPTED, tick.tickId(), tick.symbol(), windowStart,
                Map.copyOf(windowStarts));
    }

    public static IngestResult duplicate(TradeTick tick, long windowStart) {
        return duplicate(tick, windowStart, Map.of());
    }

    public static IngestResult duplicate(TradeTick tick, long windowStart, Map<Long, Long> windowStarts) {
        return new IngestResult(IngestStatus.DUPLICATE, tick.tickId(), tick.symbol(), windowStart,
                Map.copyOf(windowStarts));
    }
}
