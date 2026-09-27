package com.github.lisama569.tickklineaggregatorjava.service;

import com.github.lisama569.tickklineaggregatorjava.model.IngestResult;
import com.github.lisama569.tickklineaggregatorjava.model.Kline;
import com.github.lisama569.tickklineaggregatorjava.model.KlineRangeEntry;
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
     * 上报一笔成交。非法输入抛 TickRejectedException；重复提交返回 DUPLICATE；
     * 目标窗口已回收时抛 WINDOW_EVICTED。
     */
    public IngestResult report(TradeTick tick) {
        validator.validate(tick);
        return aggregator.ingest(tick);
    }

    /**
     * 默认（基础）聚合周期，供缺省 periodMs 的查询使用。
     */
    public long defaultPeriodMs() {
        return aggregator.configuredPeriods()[0];
    }

    /**
     * 查询某时刻所属窗口的默认周期 K 线一致快照。
     */
    public Optional<Kline> klineAt(String symbol, long timestamp) {
        return aggregator.klineAt(symbol, timestamp);
    }

    /**
     * 查询某时刻所属窗口、指定周期的 K 线一致快照。
     */
    public Optional<Kline> klineAt(String symbol, long periodMs, long timestamp) {
        return aggregator.klineAt(symbol, periodMs, timestamp);
    }

    /**
     * 查询某标的默认周期的全部保留 K 线（按窗口起点升序）。
     */
    public List<Kline> klines(String symbol) {
        return aggregator.klines(symbol);
    }

    /**
     * 查询某标的指定周期的全部保留 K 线（按窗口起点升序）。
     */
    public List<Kline> klines(String symbol, long periodMs) {
        return aggregator.klines(symbol, periodMs);
    }

    /**
     * 按时间范围 [from, to) 查询某标的指定周期的连续窗口槽位，
     * 整批来自同一时刻的一致视图，逐槽位标注 FILLED / EMPTY / UNAVAILABLE。
     */
    public List<KlineRangeEntry> klinesRange(String symbol, long periodMs, long from, long to) {
        return aggregator.klinesRange(symbol, periodMs, from, to);
    }
}
