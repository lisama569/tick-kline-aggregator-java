package com.github.lisama569.tickklineaggregatorjava.domain;

/**
 * K 线窗口标识：交易品种 + 窗口起始时间（epoch 毫秒，左闭右开区间的左端）。
 */
public record WindowKey(String symbol, long windowStartMillis) {
}
