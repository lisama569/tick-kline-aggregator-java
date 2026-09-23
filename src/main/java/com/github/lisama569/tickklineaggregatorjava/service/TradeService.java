package com.github.lisama569.tickklineaggregatorjava.service;

import com.github.lisama569.tickklineaggregatorjava.domain.IngestResult;
import com.github.lisama569.tickklineaggregatorjava.domain.KlineBar;
import com.github.lisama569.tickklineaggregatorjava.domain.Trade;
import com.github.lisama569.tickklineaggregatorjava.dto.TradeReportRequest;
import com.github.lisama569.tickklineaggregatorjava.dto.TradeReportResponse;
import com.github.lisama569.tickklineaggregatorjava.error.WindowNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 应用服务：串联校验与聚合，并把领域结果转换为对外 DTO。
 */
@Service
public class TradeService {

    private final TradeValidator validator;
    private final KlineAggregator aggregator;

    public TradeService(TradeValidator validator, KlineAggregator aggregator) {
        this.validator = validator;
        this.aggregator = aggregator;
    }

    /**
     * 上报一笔成交。合法但重复的提交幂等返回 status=DUPLICATE，
     * 非法输入由 {@link TradeValidator} 抛出携带原因码的异常。
     */
    public TradeReportResponse report(TradeReportRequest request) {
        Trade trade = validator.validate(request);
        IngestResult result = aggregator.ingest(trade);
        String status = result.duplicate() ? "DUPLICATE" : "ACCEPTED";
        String detail = result.duplicate()
                ? "成交 " + trade.tradeId() + " 此前已计入，本次为幂等重复提交，聚合结果不变"
                : "成交已计入窗口 " + result.window().windowStartMillis();
        return new TradeReportResponse(
                status,
                trade.tradeId(),
                trade.symbol(),
                result.window().windowStartMillis(),
                null,
                detail);
    }

    /** 查询某时刻所属窗口的 K 线；该窗口尚无成交时抛出 {@link WindowNotFoundException}。 */
    public KlineBar queryBar(String symbol, long timestampMillis) {
        KlineBar bar = aggregator.getBar(symbol, timestampMillis);
        if (bar == null) {
            long start = aggregator.windowStartFor(timestampMillis);
            throw new WindowNotFoundException(
                    "品种 " + symbol + " 在窗口 [" + start + ", "
                            + (start + aggregator.windowSpanMillis()) + ") 内尚无成交");
        }
        return bar;
    }

    /** 测试辅助：直接计入已构造好的成交。 */
    public IngestResult ingest(Trade trade) {
        return aggregator.ingest(trade);
    }
}
