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
 * 回收后多周期查询的对外接口语义（默认配置仅启用 60000ms，故本类通过命令行/属性无法改配置，
 * 这里验证单周期下既有接口不回归，以及未配置周期仍可区分拒绝）。
 *
 * <p>多周期回收（1m+5m）的不可信语义核心覆盖见
 * {@link MultiPeriodRetentionTests}（直接驱动聚合器，不受默认单周期配置限制）。
 * 本类额外用一条 5m 未配置请求确认接口路径在新原因码下仍稳定。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MultiPeriodRetentionControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void defaultSinglePeriodStillServesFilledAndEmptyAnd404() throws Exception {
        long now = System.currentTimeMillis();
        long base = Math.floorDiv(now, 60_000L) * 60_000L;
        String body = """
                {"tickId":"mpr-1","symbol":"DOGEUSDT","price":0.1,"quantity":4,"timestamp":%d}
                """.formatted(base + 1_000L);

        System.out.println("[接口多周期] 输入: 默认单周期上报一笔并查询, 判定依据: 既有 FILLED/404 语义不回归");
        mockMvc.perform(post("/api/market/ticks").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        mockMvc.perform(get("/api/market/klines/DOGEUSDT/at/{ts}", base + 1_000L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tradeCount").value(1));

        mockMvc.perform(get("/api/market/klines/DOGEUSDT/range")
                        .param("from", Long.toString(base))
                        .param("to", Long.toString(base + 2 * 60_000L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("FILLED"))
                .andExpect(jsonPath("$[1].status").value("EMPTY"));

        mockMvc.perform(get("/api/market/klines/DOGEUSDT/at/{ts}", base + 3 * 60_000L))
                .andExpect(status().isNotFound());

        // 5m 未在默认配置启用 -> 仍是可区分的 UNSUPPORTED_WINDOW_SIZE，而非新原因码
        mockMvc.perform(get("/api/market/klines/DOGEUSDT/at/{ts}", base + 1_000L)
                        .param("periodMs", "300000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("UNSUPPORTED_WINDOW_SIZE"));
    }
}
