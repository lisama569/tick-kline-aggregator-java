package com.github.lisama569.tickklineaggregatorjava.web;

import com.github.lisama569.tickklineaggregatorjava.domain.KlineBar;
import com.github.lisama569.tickklineaggregatorjava.dto.KlineResponse;
import com.github.lisama569.tickklineaggregatorjava.dto.TradeReportRequest;
import com.github.lisama569.tickklineaggregatorjava.dto.TradeReportResponse;
import com.github.lisama569.tickklineaggregatorjava.service.TradeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 逐笔成交通报与 K 线查询接口。
 *
 * <ul>
 *   <li>POST /api/v1/trades —— 上报一笔逐笔成交（幂等）</li>
 *   <li>GET  /api/v1/symbols/{symbol}/klines?at={epochMillis} —— 查询某时刻所属窗口的 K 线</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class TradeController {

    private final TradeService tradeService;

    public TradeController(TradeService tradeService) {
        this.tradeService = tradeService;
    }

    @PostMapping("/trades")
    public ResponseEntity<TradeReportResponse> report(@RequestBody TradeReportRequest request) {
        TradeReportResponse response = tradeService.report(request);
        // 首次计入 201；幂等重复 200（同一请求重复执行结果一致）
        HttpStatus status = "ACCEPTED".equals(response.status())
                ? HttpStatus.CREATED
                : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping("/symbols/{symbol}/klines")
    public KlineResponse queryKline(@PathVariable String symbol,
                                    @RequestParam("at") Long atMillis) {
        KlineBar bar = tradeService.queryBar(symbol, atMillis);
        return new KlineResponse(
                bar.symbol(),
                bar.windowStartMillis(),
                bar.windowEndMillis(),
                bar.open(),
                bar.high(),
                bar.low(),
                bar.close(),
                bar.volume(),
                bar.tradeCount());
    }
}
