package com.github.lisama569.tickklineaggregatorjava.dto;

import java.math.BigDecimal;

/**
 * K 线查询响应。
 */
public record KlineResponse(String symbol,
                            long windowStartMillis,
                            long windowEndMillis,
                            BigDecimal open,
                            BigDecimal high,
                            BigDecimal low,
                            BigDecimal close,
                            BigDecimal volume,
                            long tradeCount) {
}
