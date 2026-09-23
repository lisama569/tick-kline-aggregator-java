package com.github.lisama569.tickklineaggregatorjava.domain;

import java.math.BigDecimal;

/**
 * 单个时间窗口聚合完成后的 K 线不可变快照。
 */
public record KlineBar(String symbol,
                       long windowStartMillis,
                       long windowEndMillis,
                       BigDecimal open,
                       BigDecimal high,
                       BigDecimal low,
                       BigDecimal close,
                       BigDecimal volume,
                       long tradeCount) {
}
