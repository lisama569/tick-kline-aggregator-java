package com.github.lisama569.tickklineaggregatorjava.service;

import com.github.lisama569.tickklineaggregatorjava.model.IngestResult;
import com.github.lisama569.tickklineaggregatorjava.model.Kline;
import com.github.lisama569.tickklineaggregatorjava.model.TradeTick;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 对外门面：先校验，再聚合。校验失败抛出 TickRejectedException。
 */
@Service
public class KlineService {

    private final TickValidator validator;
    private final KlineAggregator aggregator;

    public KlineService(TickValidator validator, KlineAggregator aggregator) {
        this.validator = validator;
        this.aggregator = aggregator;
    }

    /**
     * 上报一笔成交。非法输入抛 TickRejectedException；重复提交返回 DUPLICATE。
     */
    public IngestResult report(TradeTick tick) {
        validator.validate(tick);
        return aggregator.ingest(tick);
    }

    /**
     * 查询某时刻所属窗口的 K 线一致快照。
     */
    public Optional<Kline> klineAt(String symbol, long timestamp) {
        return aggregator.klineAt(symbol, timestamp);
    }

    /**
     * 查询某标的全部 K 线（按窗口起点升序）。
     */
    public List<Kline> klines(String symbol) {
        return aggregator.klines(symbol);
    }
}
