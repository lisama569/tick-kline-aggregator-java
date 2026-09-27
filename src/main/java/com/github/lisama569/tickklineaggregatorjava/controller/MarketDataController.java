package com.github.lisama569.tickklineaggregatorjava.controller;

import com.github.lisama569.tickklineaggregatorjava.model.IngestResult;
import com.github.lisama569.tickklineaggregatorjava.model.Kline;
import com.github.lisama569.tickklineaggregatorjava.model.KlineRangeEntry;
import com.github.lisama569.tickklineaggregatorjava.model.TradeTick;
import com.github.lisama569.tickklineaggregatorjava.service.KlineService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 行情对外接口：成交上报与 K 线查询（支持多周期与时间范围查询）。
 */
@RestController
@RequestMapping("/api/market")
public class MarketDataController {

    private final KlineService klineService;

    public MarketDataController(KlineService klineService) {
        this.klineService = klineService;
    }

    /** 上报一笔逐笔成交。非法输入返回 400 + 可区分拒绝原因；重复提交返回 200 + DUPLICATE；
     *  目标窗口已回收返回 400 + WINDOW_EVICTED。 */
    @PostMapping("/ticks")
    public IngestResult reportTick(@RequestBody TradeTick tick) {
        return klineService.report(tick);
    }

    /** 查询某时刻所属窗口的 K 线一致快照；窗口无数据返回 404。
     *  可选 periodMs 指定聚合周期（须为已配置周期），缺省为默认周期。 */
    @GetMapping("/klines/{symbol}/at/{timestamp}")
    public ResponseEntity<Kline> klineAt(@PathVariable String symbol,
                                         @PathVariable long timestamp,
                                         @RequestParam(required = false) Long periodMs) {
        var result = periodMs == null
                ? klineService.klineAt(symbol, timestamp)
                : klineService.klineAt(symbol, periodMs, timestamp);
        return result.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 查询某标的全部保留 K 线（按窗口起点升序）。可选 periodMs 指定周期，缺省为默认周期。 */
    @GetMapping("/klines/{symbol}")
    public List<Kline> klines(@PathVariable String symbol,
                              @RequestParam(required = false) Long periodMs) {
        return periodMs == null
                ? klineService.klines(symbol)
                : klineService.klines(symbol, periodMs);
    }

    /** 按时间范围查询连续窗口槽位（窗口起点落在 [from, to)）。
     *  整批来自同一时刻的一致视图；每个槽位带状态 FILLED / EMPTY / UNAVAILABLE，
     *  可区分“窗口无成交”与“系统中已无该段数据”。periodMs 缺省为默认周期。 */
    @GetMapping("/klines/{symbol}/range")
    public List<KlineRangeEntry> klinesRange(@PathVariable String symbol,
                                             @RequestParam long from,
                                             @RequestParam long to,
                                             @RequestParam(required = false) Long periodMs) {
        long period = periodMs == null ? klineService.defaultPeriodMs() : periodMs;
        return klineService.klinesRange(symbol, period, from, to);
    }
}
