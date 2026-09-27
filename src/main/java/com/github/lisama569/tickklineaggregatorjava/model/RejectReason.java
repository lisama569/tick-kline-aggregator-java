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
    MALFORMED_REQUEST,
    /** 成交本应落入的窗口已超出保留上限被回收，拒绝迟到写入 */
    WINDOW_EVICTED,
    /** 范围查询参数非法（from/to 非正或 from > to） */
    INVALID_RANGE,
    /** 查询指定的周期未在 kline.window-sizes-ms 中配置 */
    UNKNOWN_PERIOD
}
