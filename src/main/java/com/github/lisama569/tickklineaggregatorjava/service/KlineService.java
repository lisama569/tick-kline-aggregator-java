package com.github.lisama569.tickklineaggregatorjava.service;

import com.github.lisama569.tickklineaggregatorjava.model.IngestResult;
import com.github.lisama569.tickklineaggregatorjava.model.Kline;
import com.github.lisama569.tickklineaggregatorjava.model.KlineRange;
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
     * 迟到成交落入已回收窗口抛 WINDOW_EVICTED。
     */
    public IngestResult report(TradeTick tick) {
        validator.validate(tick);
        return aggregator.ingest(tick);
    }

    /** 查询某时刻在主周期所属窗口的 K 线一致快照。 */
    public Optional<Kline> klineAt(String symbol, long timestamp) {
        return aggregator.klineAt(symbol, timestamp);
    }

    /** 查询某时刻在指定周期所属窗口的 K 线一致快照；未配置周期抛 UNKNOWN_PERIOD。 */
    public Optional<Kline> klineAt(String symbol, long timestamp, long windowSizeMs) {
        return aggregator.klineAt(symbol, timestamp, windowSizeMs);
    }

    /** 查询某标的主周期全部保留 K 线（按窗口起点升序）。 */
    public List<Kline> klines(String symbol) {
        return aggregator.klines(symbol);
    }

    /** 查询某标的指定周期全部保留 K 线（按窗口起点升序）。 */
    public List<Kline> klines(String symbol, long windowSizeMs) {
        return aggregator.klines(symbol, windowSizeMs);
    }

    /** 按时间范围查询某标的指定周期的连续 K 线（整批一致视图）。 */
    public KlineRange klinesRange(String symbol, long windowSizeMs, long from, long to) {
        return aggregator.klinesRange(symbol, windowSizeMs, from, to);
    }

    /** 主周期（毫秒），即未显式指定周期时查询使用的默认周期。 */
    public long primaryPeriodMs() {
        return aggregator.primaryPeriodMs();
    }
}
