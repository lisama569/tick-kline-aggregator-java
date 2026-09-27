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

    @Test
    void sameTicksAggregateIndependentlyAcrossConfiguredPeriods() throws Exception {
        long now = System.currentTimeMillis();
        long minute = 60_000L;
        long bigBase = (now / (5 * minute)) * (5 * minute);
        long ts0 = bigBase;                 // Bx 的第 1 个小窗
        long ts1 = bigBase + minute + 100;  // 第 2 个小窗
        mockMvc.perform(post("/api/market/ticks").contentType(MediaType.APPLICATION_JSON)
                        .content(tickJson("mp-1", "MPUSDT", "100", "2", ts0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.windowStarts.60000").value(bigBase))
                .andExpect(jsonPath("$.windowStarts.300000").value(bigBase));
        mockMvc.perform(post("/api/market/ticks").contentType(MediaType.APPLICATION_JSON)
                        .content(tickJson("mp-2", "MPUSDT", "110", "3", ts1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windowStarts.60000").value(bigBase + minute))
                .andExpect(jsonPath("$.windowStarts.300000").value(bigBase));

        System.out.println("[接口多周期] 输入: 2 笔成交落在 60000ms 两窗、300000ms 同一窗");
        System.out.println("[接口多周期] 判定依据: 每笔每周期只计一次, 大周期笔数=2/量=5");

        mockMvc.perform(get("/api/market/klines/MPUSDT/at/{ts}", ts1).param("windowSizeMs", "60000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tradeCount").value(1));
        mockMvc.perform(get("/api/market/klines/MPUSDT/at/{ts}", ts1).param("windowSizeMs", "300000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tradeCount").value(2))
                .andExpect(jsonPath("$.volume").value(5.0))
                .andExpect(jsonPath("$.open").value(100.0))
                .andExpect(jsonPath("$.close").value(110.0));

        // 不带 windowSizeMs 仍使用主周期 60000（兼容性）
        mockMvc.perform(get("/api/market/klines/MPUSDT/at/{ts}", ts1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tradeCount").value(1));

        // 未配置周期 -> 400 + UNKNOWN_PERIOD
        mockMvc.perform(get("/api/market/klines/MPUSDT/at/{ts}", ts1).param("windowSizeMs", "12345"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("UNKNOWN_PERIOD"));
    }

    @Test
    void rangeQueryReturnsConsistentBatchWithDistinguishableWindowStates() throws Exception {
        long minute = 60_000L;
        long base = (System.currentTimeMillis() / minute) * minute;
        mockMvc.perform(post("/api/market/ticks").contentType(MediaType.APPLICATION_JSON)
                .content(tickJson("rg-1", "RGUSDT", "100", "1", base))).andExpect(status().isOk());
        mockMvc.perform(post("/api/market/ticks").contentType(MediaType.APPLICATION_JSON)
                .content(tickJson("rg-2", "RGUSDT", "120", "2", base + 2 * minute))).andExpect(status().isOk());

        System.out.println("[接口范围] 输入: 成交@w0/@w2, 范围覆盖 w0..w2");
        System.out.println("[接口范围] 判定依据: 状态序列 HAS_DATA,EMPTY,HAS_DATA; 空窗可与 NOT_RETAINED 区分");

        mockMvc.perform(get("/api/market/klines/RGUSDT/range")
                        .param("from", String.valueOf(base))
                        .param("to", String.valueOf(base + 2 * minute)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windowSizeMs").value(60000))
                .andExpect(jsonPath("$.oldestWindowStart").value(base))
                .andExpect(jsonPath("$.newestWindowStart").value(base + 2 * minute))
                .andExpect(jsonPath("$.windows.length()").value(3))
                .andExpect(jsonPath("$.windows[0].state").value("HAS_DATA"))
                .andExpect(jsonPath("$.windows[0].kline.open").value(100.0))
                .andExpect(jsonPath("$.windows[1].state").value("EMPTY"))
                .andExpect(jsonPath("$.windows[1].kline").doesNotExist())
                .andExpect(jsonPath("$.windows[2].state").value("HAS_DATA"))
                .andExpect(jsonPath("$.windows[2].kline.volume").value(2.0));

        // 更早的范围：NOT_RETAINED，且保留边界字段如实回报
        mockMvc.perform(get("/api/market/klines/RGUSDT/range")
                        .param("windowSizeMs", "60000")
                        .param("from", String.valueOf(base - 2 * minute))
                        .param("to", String.valueOf(base - minute)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windows[0].state").value("NOT_RETAINED"))
                .andExpect(jsonPath("$.windows[1].state").value("NOT_RETAINED"))
                .andExpect(jsonPath("$.oldestWindowStart").value(base));
    }

    @Test
    void rangeQueryValidatesParamsWithDistinctReasons() throws Exception {
        long ts = System.currentTimeMillis();
        System.out.println("[接口范围] 输入: from>to / 非数字 to / 缺 from");
        System.out.println("[接口范围] 判定依据: INVALID_RANGE / INVALID_RANGE / INVALID_RANGE");

        mockMvc.perform(get("/api/market/klines/RGUSDT/range")
                        .param("from", String.valueOf(ts + 10)).param("to", String.valueOf(ts)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("INVALID_RANGE"));
        mockMvc.perform(get("/api/market/klines/RGUSDT/range")
                        .param("from", String.valueOf(ts)).param("to", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("INVALID_RANGE"));
        mockMvc.perform(get("/api/market/klines/RGUSDT/range")
                        .param("to", String.valueOf(ts)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("INVALID_RANGE"));
    }
}
