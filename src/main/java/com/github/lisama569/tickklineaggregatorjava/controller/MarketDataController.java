package com.github.lisama569.tickklineaggregatorjava.controller;

import com.github.lisama569.tickklineaggregatorjava.model.IngestResult;
import com.github.lisama569.tickklineaggregatorjava.model.Kline;
import com.github.lisama569.tickklineaggregatorjava.model.TradeTick;
import com.github.lisama569.tickklineaggregatorjava.service.KlineService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 行情对外接口：成交上报与 K 线查询。
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

    /** 查询某时刻所属窗口的 K 线一致快照；窗口无数据返回 404。 */
    @GetMapping("/klines/{symbol}/at/{timestamp}")
    public ResponseEntity<Kline> klineAt(@PathVariable String symbol, @PathVariable long timestamp) {
        return klineService.klineAt(symbol, timestamp)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 查询某标的全部 K 线（按窗口起点升序）。 */
    @GetMapping("/klines/{symbol}")
    public List<Kline> klines(@PathVariable String symbol) {
        return klineService.klines(symbol);
    }
}
