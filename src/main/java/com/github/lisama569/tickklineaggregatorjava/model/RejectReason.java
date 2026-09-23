package com.github.lisama569.tickklineaggregatorjava.model;

/**
 * 成交被拒绝的可区分原因。
 */
public enum RejectReason {
    INVALID_TICK_ID,
    INVALID_SYMBOL,
    INVALID_PRICE,
    INVALID_QUANTITY,
    INVALID_TIMESTAMP,
    TIMESTAMP_OUT_OF_RANGE,
    MALFORMED_REQUEST
}
