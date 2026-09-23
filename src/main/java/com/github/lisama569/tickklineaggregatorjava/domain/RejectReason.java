package com.github.lisama569.tickklineaggregatorjava.domain;

/**
 * 成交被拒绝的可区分原因码。
 */
public enum RejectReason {
    MALFORMED_JSON,
    MISSING_TRADE_ID,
    MISSING_SYMBOL,
    PRICE_NOT_A_NUMBER,
    PRICE_NOT_POSITIVE,
    VOLUME_NOT_A_NUMBER,
    VOLUME_NOT_POSITIVE,
    TIMESTAMP_NOT_A_NUMBER,
    TIMESTAMP_OUT_OF_RANGE,
    WINDOW_NOT_FOUND
}
