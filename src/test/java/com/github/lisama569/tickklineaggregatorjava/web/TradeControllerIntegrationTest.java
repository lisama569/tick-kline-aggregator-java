package com.github.lisama569.tickklineaggregatorjava.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端接口测试：上报、幂等、拒绝原因码、查询一致视图、窗口缺失 404、坏 JSON 400。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TradeControllerIntegrationTest {

    /** 固定时钟：now=1700000000000；默认 minTs=946684800000，futureSkew=5000。 */
    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    private static String tradeJson(String id, String symbol,
                                   String price, String volume, String ts) {
        return "{\"tradeId\":\"" + id + "\",\"symbol\":\"" + symbol
                + "\",\"price\":\"" + price + "\",\"volume\":\"" + volume
                + "\",\"timestamp\":\"" + ts + "\"}";
    }

    @Test
    @DisplayName("上报-乱序-查询：迟到成交修正开盘，查询返回完整一致 K 线")
    void reportThenQuery_inOrderAndLate() throws Exception {
        // 窗口 1700000000000 = floor(1700000000000 / 60000)*60000 = 1699999980000
        System.out.println("[输入] 先报 ts=1700000003000 price=100 vol=4，"
                + "再迟到 ts=1700000001000 price=90 vol=1");
        mockMvc.perform(post("/api/v1/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tradeJson("w1", "BTC", "100", "4", "1700000003000")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andDo(r -> System.out.println("[判定依据] 首次上报返回 201 CREATED"));

        mockMvc.perform(post("/api/v1/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tradeJson("w0-late", "BTC", "90", "1", "1700000001000")))
                .andExpect(status().isCreated())
                .andDo(r -> System.out.println("[判定依据] 迟到成交计入并触发开盘修正"));

        // 查询一致视图：open/high/low/close/volume/count 必须同时反映两笔
        mockMvc.perform(get("/api/v1/symbols/BTC/klines").param("at", "1700000000000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(90))
                .andExpect(jsonPath("$.high").value(100))
                .andExpect(jsonPath("$.low").value(90))
                .andExpect(jsonPath("$.close").value(100))
                .andExpect(jsonPath("$.volume").value(5))
                .andExpect(jsonPath("$.tradeCount").value(2))
                .andDo(r -> System.out.println("[判定依据] 查询返回 open=90 high=100 "
                        + "low=90 close=100 volume=5 count=2，字段一致更新"));
    }

    @Test
    @DisplayName("重复提交幂等：第二次 200 DUPLICATE，聚合值不变")
    void duplicateReportIsIdempotent() throws Exception {
        System.out.println("[输入] 同一成交 DUP-HTTP 连续 POST 两次");
        mockMvc.perform(post("/api/v1/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tradeJson("DUP-HTTP", "ETH", "50", "2", "1700000001000")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        mockMvc.perform(post("/api/v1/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tradeJson("DUP-HTTP", "ETH", "50", "2", "1700000001000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DUPLICATE"))
                .andExpect(jsonPath("$.rejectReason").doesNotExist())
                .andDo(r -> System.out.println("[判定依据] 第二次返回 200 DUPLICATE"));

        mockMvc.perform(get("/api/v1/symbols/ETH/klines").param("at", "1700000001000"))
                .andExpect(jsonPath("$.volume").value(2))
                .andExpect(jsonPath("$.tradeCount").value(1))
                .andDo(r -> System.out.println("[判定依据] volume 仍为 2、count 仍为 1，未重复计入"));
    }

    @Test
    @DisplayName("非法输入被拒绝并携带可区分原因码")
    void invalidInputRejectedWithDistinctReason() throws Exception {
        System.out.println("[输入] price 非法的成交");
        mockMvc.perform(post("/api/v1/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tradeJson("bad1", "BTC", "n/a", "1", "1700000000000")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reasonCode").value("PRICE_NOT_A_NUMBER"))
                .andDo(r -> System.out.println("[判定依据] HTTP 422 reasonCode=PRICE_NOT_A_NUMBER"));

        System.out.println("[输入] timestamp 超出允许范围（超过固定时钟+5000）");
        mockMvc.perform(post("/api/v1/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tradeJson("bad2", "BTC", "100", "1", "1700000006000")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reasonCode").value("TIMESTAMP_OUT_OF_RANGE"))
                .andDo(r -> System.out.println("[判定依据] HTTP 422 reasonCode=TIMESTAMP_OUT_OF_RANGE"));

        System.out.println("[输入] 无法解析的 JSON");
        mockMvc.perform(post("/api/v1/trades")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reasonCode").value("MALFORMED_JSON"))
                .andDo(r -> System.out.println("[判定依据] HTTP 400 reasonCode=MALFORMED_JSON"));
    }

    @Test
    @DisplayName("窗口无成交返回 404；查询参数非法返回 400")
    void emptyWindow404AndBadQuery400() throws Exception {
        System.out.println("[输入] 查询一个从未上报的品种 DOGE");
        mockMvc.perform(get("/api/v1/symbols/DOGE/klines").param("at", "1700000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reasonCode").value("WINDOW_NOT_FOUND"))
                .andDo(r -> System.out.println("[判定依据] HTTP 404 reasonCode=WINDOW_NOT_FOUND"));

        System.out.println("[输入] at 参数不是整数");
        mockMvc.perform(get("/api/v1/symbols/BTC/klines").param("at", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reasonCode").value("TIMESTAMP_NOT_A_NUMBER"))
                .andDo(r -> System.out.println("[判定依据] HTTP 400 reasonCode=TIMESTAMP_NOT_A_NUMBER"));
    }
}
