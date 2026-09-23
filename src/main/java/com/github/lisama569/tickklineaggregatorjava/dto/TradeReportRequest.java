package com.github.lisama569.tickklineaggregatorjava.dto;

/**
 * 逐笔成交通报请求体。价格/数量/时间戳使用字符串，
 * 以便区分“无法解析”和“值非法”两种拒绝原因。
 */
public record TradeReportRequest(String tradeId,
                                 String symbol,
                                 String price,
                                 String volume,
                                 String timestamp) {
}
