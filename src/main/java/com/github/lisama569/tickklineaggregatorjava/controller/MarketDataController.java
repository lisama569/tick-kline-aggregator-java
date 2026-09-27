package com.github.lisama569.tickklineaggregatorjava.controller;

import com.github.lisama569.tickklineaggregatorjava.model.IngestResult;
import com.github.lisama569.tickklineaggregatorjava.model.Kline;
import com.github.lisama569.tickklineaggregatorjava.model.KlineRange;
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
 * 行情对外接口：成交上报与 K 线查询（单时刻、整表、时间范围、多周期）。
 */
@RestController
@RequestMapping("/api/market")
public class MarketDataController {

    private final KlineService klineService;

    public MarketDataController(KlineService klineService) {
        this.klineService = klineService;
    }

    /** 上报一笔逐笔成交。非法输入返回 400 + 可区分拒绝原因；重复提交返回 200 + DUPLICATE。 */
    @PostMapping("/ticks")
    public IngestResult reportTick(@RequestBody TradeTick tick) {
        return klineService.report(tick);
    }

    /**
     * 查询某时刻所属窗口的 K 线一致快照；窗口无数据返回 404。
     * 可选参数 windowSizeMs 指定周期，缺省使用主周期（与历史行为一致）；
     * 未配置的周期返回 400 + UNKNOWN_PERIOD。
     */
    @GetMapping("/klines/{symbol}/at/{timestamp}")
    public ResponseEntity<Kline> klineAt(@PathVariable String symbol,
                                         @PathVariable long timestamp,
                                         @RequestParam(required = false) Long windowSizeMs) {
        return (windowSizeMs == null
                ? klineService.klineAt(symbol, timestamp)
                : klineService.klineAt(symbol, timestamp, windowSizeMs))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 查询某标的全部保留 K 线（按窗口起点升序）。
     * 可选参数 windowSizeMs 指定周期，缺省使用主周期。
     */
    @GetMapping("/klines/{symbol}")
    public List<Kline> klines(@PathVariable String symbol,
                              @RequestParam(required = false) Long windowSizeMs) {
        return windowSizeMs == null
                ? klineService.klines(symbol)
                : klineService.klines(symbol, windowSizeMs);
    }

    /**
     * 按时间范围查询连续 K 线，整批来自同一时刻的一致视图；即使全无数据也返回 200，
     * 每个窗口以 state 区分 HAS_DATA / EMPTY（跨度内空窗）/ NOT_RETAINED（已回收或无数据）。
     *
     * @param from         起点 epoch 毫秒（含）
     * @param to           终点 epoch 毫秒（含）
     * @param windowSizeMs 可选周期，缺省使用主周期；未配置返回 400 + UNKNOWN_PERIOD
     */
    @GetMapping("/klines/{symbol}/range")
    public KlineRange klinesRange(@PathVariable String symbol,
                                  @RequestParam long from,
                                  @RequestParam long to,
                                  @RequestParam(required = false) Long windowSizeMs) {
        long period = windowSizeMs == null
                ? klineService.primaryPeriodMs()
                : windowSizeMs;
        return klineService.klinesRange(symbol, period, from, to);
    }
}
