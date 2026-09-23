package com.github.lisama569.tickklineaggregatorjava.model;

/**
 * 成交上报结果。
 */
public record IngestResult(IngestStatus status, String tickId, String symbol, long windowStart) {

    public static IngestResult accepted(TradeTick tick, long windowStart) {
        return new IngestResult(IngestStatus.ACCEPTED, tick.tickId(), tick.symbol(), windowStart);
    }

    public static IngestResult duplicate(TradeTick tick, long windowStart) {
        return new IngestResult(IngestStatus.DUPLICATE, tick.tickId(), tick.symbol(), windowStart);
    }
}
