package com.github.lisama569.tickklineaggregatorjava.model;

/**
 * 一笔逐笔成交。
 *
 * @param tickId    成交唯一标识（幂等键）
 * @param symbol    交易标的
 * @param price     成交价格（必须为正数）
 * @param quantity  成交数量（必须为正数）
 * @param timestamp 成交时间戳，epoch 毫秒
 */
public record TradeTick(String tickId, String symbol, double price, double quantity, long timestamp) {
}
