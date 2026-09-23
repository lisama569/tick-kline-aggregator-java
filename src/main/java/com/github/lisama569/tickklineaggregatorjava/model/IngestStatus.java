package com.github.lisama569.tickklineaggregatorjava.model;

/**
 * 成交上报的处理结果状态。
 */
public enum IngestStatus {
    /** 新成交，已计入聚合 */
    ACCEPTED,
    /** 重复提交（幂等命中），未重复计入 */
    DUPLICATE
}
