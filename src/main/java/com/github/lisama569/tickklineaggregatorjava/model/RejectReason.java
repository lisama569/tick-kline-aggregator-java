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
    /** 目标窗口已按保留上限回收，迟到成交不再接受，避免残缺窗口与重复计入 */
    WINDOW_EVICTED,
    /** 查询/上报使用了未配置的聚合周期 */
    UNSUPPORTED_WINDOW_SIZE,
    /** 范围查询参数非法（如起止颠倒、跨度超上限） */
    INVALID_RANGE,
    /**
     * 大周期窗口自身虽仍有数据，但它覆盖的最小周期窗口已被回收，
     * 无法由仍然保留的小周期数据完整核对，该大周期结果不可信、不可用
     */
    WINDOW_UNVERIFIABLE,
    /** 周期组合非法（如存在不是基础周期整数倍的周期，窗口边界无法对齐），服务拒绝启动 */
    INVALID_PERIOD_CONFIG,
    MALFORMED_REQUEST
}
