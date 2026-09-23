package com.github.lisama569.tickklineaggregatorjava.domain;

import java.math.BigDecimal;

/**
 * 解析并校验通过后的一笔逐笔成交（不可变值对象）。
 */
public record Trade(String tradeId,
                    String symbol,
                    BigDecimal price,
                    BigDecimal volume,
                    long timestampMillis) {
}
