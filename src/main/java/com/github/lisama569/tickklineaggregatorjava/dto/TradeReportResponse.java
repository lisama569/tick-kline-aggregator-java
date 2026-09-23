package com.github.lisama569.tickklineaggregatorjava.dto;

/**
 * 上报响应：成功计入、幂等重复、或拒绝（携带原因码与可读原因）。
 */
public record TradeReportResponse(String status,
                                  String tradeId,
                                  String symbol,
                                  Long windowStartMillis,
                                  String rejectReason,
                                  String reasonDetail) {
}
