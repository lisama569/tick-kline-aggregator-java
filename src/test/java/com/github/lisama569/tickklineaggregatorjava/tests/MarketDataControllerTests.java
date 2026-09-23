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
 * 对外接口测试：成交上报、幂等、K 线查询、非法输入的可区分拒绝原因。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MarketDataControllerTests {

    @Autowired
    private MockMvc mockMvc;

    private static String tickJson(String id, String symbol, String price, String qty, long ts) {
        return """
                {"tickId":"%s","symbol":"%s","price":%s,"quantity":%s,"timestamp":%d}
                """.formatted(id, symbol, price, qty, ts);
    }

    @Test
    void reportAcceptsThenDeduplicates() throws Exception {
        long ts = System.currentTimeMillis() - 1000;
        String body = tickJson("api-1", "ETHUSDT", "2500.5", "3", ts);

        System.out.println("[接口] 输入: " + body.strip());
        System.out.println("[接口] 判定依据: 首次上报 ACCEPTED, 同 tickId 再次上报 DUPLICATE 且不重复计量");

        mockMvc.perform(post("/api/market/ticks").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.tickId").value("api-1"));

        mockMvc.perform(post("/api/market/ticks").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DUPLICATE"));

        mockMvc.perform(get("/api/market/klines/ETHUSDT/at/{ts}", ts))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tradeCount").value(1))
                .andExpect(jsonPath("$.volume").value(3.0))
                .andExpect(jsonPath("$.open").value(2500.5))
                .andExpect(jsonPath("$.close").value(2500.5));
    }

    @Test
    void rejectsInvalidPriceWithDistinctReason() throws Exception {
        long ts = System.currentTimeMillis() - 1000;
        String body = tickJson("bad-price", "ETHUSDT", "-1", "3", ts);
        System.out.println("[拒绝] 输入: price=-1 -> 期望原因 INVALID_PRICE");
        mockMvc.perform(post("/api/market/ticks").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("INVALID_PRICE"));
    }

    @Test
    void rejectsInvalidQuantityWithDistinctReason() throws Exception {
        long ts = System.currentTimeMillis() - 1000;
        String body = tickJson("bad-qty", "ETHUSDT", "100", "0", ts);
        System.out.println("[拒绝] 输入: quantity=0 -> 期望原因 INVALID_QUANTITY");
        mockMvc.perform(post("/api/market/ticks").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("INVALID_QUANTITY"));
    }

    @Test
    void rejectsInvalidTimestampWithDistinctReason() throws Exception {
        String body = tickJson("bad-ts", "ETHUSDT", "100", "1", -5);
        System.out.println("[拒绝] 输入: timestamp=-5 -> 期望原因 INVALID_TIMESTAMP");
        mockMvc.perform(post("/api/market/ticks").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("INVALID_TIMESTAMP"));
    }

    @Test
    void rejectsOutOfRangeTimestampWithDistinctReason() throws Exception {
        long future = System.currentTimeMillis() + 3600_000L; // 超出允许的未来偏移
        String body = tickJson("future-ts", "ETHUSDT", "100", "1", future);
        System.out.println("[拒绝] 输入: timestamp 超出允许未来范围 -> 期望原因 TIMESTAMP_OUT_OF_RANGE");
        mockMvc.perform(post("/api/market/ticks").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("TIMESTAMP_OUT_OF_RANGE"));
    }

    @Test
    void rejectsUnparseableTimestampWithDistinctReason() throws Exception {
        String body = """
                {"tickId":"bad-json","symbol":"ETHUSDT","price":100,"quantity":1,"timestamp":"not-a-number"}
                """;
        System.out.println("[拒绝] 输入: timestamp 为无法解析的字符串 -> 期望原因 MALFORMED_REQUEST");
        mockMvc.perform(post("/api/market/ticks").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("MALFORMED_REQUEST"));
    }

    @Test
    void klineAtUnknownWindowReturns404() throws Exception {
        System.out.println("[查询] 输入: 无数据的窗口 -> 期望 404");
        mockMvc.perform(get("/api/market/klines/NOSUCH/at/{ts}", System.currentTimeMillis()))
                .andExpect(status().isNotFound());
    }
}
