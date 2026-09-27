package com.github.lisama569.tickklineaggregatorjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 多周期/范围查询的对外接口测试（默认配置仅启用 60000ms 周期）。
 * 用例打印输入与判定依据。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MarketDataRangeControllerTests {

    private static final String SYMBOL = "SOLUSDT";

    private static final String SYMBOL2 = "AVAXUSDT";

    @Autowired
    private MockMvc mockMvc;

    private static String tickJson(String id, String symbol, String price, String qty, long ts) {
        return """
                {"tickId":"%s","symbol":"%s","price":%s,"quantity":%s,"timestamp":%d}
                """.formatted(id, symbol, price, qty, ts);
    }

    @Test
    void rangeEndpointDistinguishesFilledEmptyAndUnavailable() throws Exception {
        long now = System.currentTimeMillis();
        long base = Math.floorDiv(now, 60_000L) * 60_000L;
        // 在 w1 放一笔成交，w0/w2 留空
        long ts = base + 60_000L + 1_000L;
        String body = tickJson("range-1", SYMBOL, "42.5", "2", ts);

        System.out.println("[接口范围] 输入: 在窗口 w1 上报一笔, 查询三槽位 w0..w2");
        System.out.println("[接口范围] 判定依据: w1=FILLED 且字段正确; w0/w2=EMPTY; 未知标的槽位=UNAVAILABLE");

        mockMvc.perform(post("/api/market/ticks").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        mockMvc.perform(get("/api/market/klines/{symbol}/range", SYMBOL)
                        .param("from", Long.toString(base))
                        .param("to", Long.toString(base + 3 * 60_000L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].status").value("EMPTY"))
                .andExpect(jsonPath("$[0].kline").doesNotExist())
                .andExpect(jsonPath("$[1].status").value("FILLED"))
                .andExpect(jsonPath("$[1].kline.open").value(42.5))
                .andExpect(jsonPath("$[1].kline.volume").value(2.0))
                .andExpect(jsonPath("$[1].kline.tradeCount").value(1))
                .andExpect(jsonPath("$[2].status").value("EMPTY"));

        // 未知标的：系统中根本没有这段数据 -> UNAVAILABLE
        mockMvc.perform(get("/api/market/klines/{symbol}/range", "NOKNOWN")
                        .param("from", Long.toString(base))
                        .param("to", Long.toString(base + 60_000L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$[0].kline").doesNotExist());
    }

    @Test
    void unsupportedPeriodAndInvalidRangeReturnDistinctReasons() throws Exception {
        long now = System.currentTimeMillis();
        long base = Math.floorDiv(now, 60_000L) * 60_000L;

        System.out.println("[接口范围] 输入1: periodMs=300000 (默认配置未启用该周期)");
        mockMvc.perform(get("/api/market/klines/{symbol}/range", SYMBOL)
                        .param("periodMs", "300000")
                        .param("from", Long.toString(base))
                        .param("to", Long.toString(base + 60_000L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("UNSUPPORTED_WINDOW_SIZE"));

        System.out.println("[接口范围] 输入2: from > to");
        mockMvc.perform(get("/api/market/klines/{symbol}/range", SYMBOL)
                        .param("from", Long.toString(base + 60_000L))
                        .param("to", Long.toString(base)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("INVALID_RANGE"));
    }

    @Test
    void klineAtAcceptsPeriodMsParameter() throws Exception {
        long ts = System.currentTimeMillis() - 1000;
        String body = tickJson("at-1", SYMBOL2, "40", "1", ts);

        System.out.println("[接口周期] 输入: 上报后用 periodMs=60000 显式查询");
        System.out.println("[接口周期] 判定依据: 显式 periodMs 缺省参数语义等价, 字段与单周期一致");

        mockMvc.perform(post("/api/market/ticks").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/market/klines/{symbol}/at/{timestamp}", SYMBOL2, ts)
                        .param("periodMs", "60000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tradeCount").value(1))
                .andExpect(jsonPath("$.open").value(40.0));
    }
}
