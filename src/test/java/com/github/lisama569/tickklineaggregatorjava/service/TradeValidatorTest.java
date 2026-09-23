package com.github.lisama569.tickklineaggregatorjava.service;

import com.github.lisama569.tickklineaggregatorjava.domain.RejectReason;
import com.github.lisama569.tickklineaggregatorjava.domain.Trade;
import com.github.lisama569.tickklineaggregatorjava.dto.TradeReportRequest;
import com.github.lisama569.tickklineaggregatorjava.error.TradeRejectedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 输入校验测试：每种非法输入必须被拒绝且携带唯一可区分的原因码。
 */
class TradeValidatorTest {

    private static final long NOW = 1_700_000_000_000L; // 固定时钟
    private static final long MIN_TS = 1_000_000_000_000L;
    private static final long MAX_SKEW = 5_000L;

    private TradeValidator validator;

    @BeforeEach
    void setUp() {
        AggregatorProperties props = new AggregatorProperties();
        props.setWindowMillis(60_000L);
        props.setMinTimestampMillis(MIN_TS);
        props.setMaxFutureSkewMillis(MAX_SKEW);
        Clock fixed = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
        validator = new TradeValidator(props, fixed);
    }

    private static TradeReportRequest req(String id, String symbol,
                                          String price, String volume, String ts) {
        return new TradeReportRequest(id, symbol, price, volume, ts);
    }

    private void assertRejects(String caseName, TradeReportRequest request,
                               RejectReason expected) {
        System.out.println("[输入] " + caseName + " => " + request);
        TradeRejectedException ex = assertThrows(TradeRejectedException.class,
                () -> validator.validate(request));
        System.out.println("[判定依据] 拒绝原因=" + ex.getReason()
                + "，消息=" + ex.getMessage());
        assertEquals(expected, ex.getReason());
    }

    @Test
    @DisplayName("每类非法输入对应可区分的拒绝原因")
    void rejectReasonsAreDistinguishable() {
        assertRejects("tradeId 缺失",
                req(null, "BTC", "100", "1", "1700000000000"),
                RejectReason.MISSING_TRADE_ID);
        assertRejects("tradeId 为空白",
                req("   ", "BTC", "100", "1", "1700000000000"),
                RejectReason.MISSING_TRADE_ID);
        assertRejects("symbol 缺失",
                req("t1", null, "100", "1", "1700000000000"),
                RejectReason.MISSING_SYMBOL);

        assertRejects("price 不是数字",
                req("t1", "BTC", "abc", "1", "1700000000000"),
                RejectReason.PRICE_NOT_A_NUMBER);
        assertRejects("price 缺失",
                req("t1", "BTC", null, "1", "1700000000000"),
                RejectReason.PRICE_NOT_A_NUMBER);
        assertRejects("price 为 0",
                req("t1", "BTC", "0", "1", "1700000000000"),
                RejectReason.PRICE_NOT_POSITIVE);
        assertRejects("price 为负",
                req("t1", "BTC", "-3.5", "1", "1700000000000"),
                RejectReason.PRICE_NOT_POSITIVE);

        assertRejects("volume 不是数字",
                req("t1", "BTC", "100", "xx", "1700000000000"),
                RejectReason.VOLUME_NOT_A_NUMBER);
        assertRejects("volume 为 0",
                req("t1", "BTC", "100", "0", "1700000000000"),
                RejectReason.VOLUME_NOT_POSITIVE);

        assertRejects("timestamp 不是整数",
                req("t1", "BTC", "100", "1", "not-a-time"),
                RejectReason.TIMESTAMP_NOT_A_NUMBER);
        assertRejects("timestamp 缺失",
                req("t1", "BTC", "100", "1", null),
                RejectReason.TIMESTAMP_NOT_A_NUMBER);
        assertRejects("timestamp 早于下限",
                req("t1", "BTC", "100", "1", String.valueOf(MIN_TS - 1)),
                RejectReason.TIMESTAMP_OUT_OF_RANGE);
        assertRejects("timestamp 超过时钟+容忍偏移",
                req("t1", "BTC", "100", "1", String.valueOf(NOW + MAX_SKEW + 1)),
                RejectReason.TIMESTAMP_OUT_OF_RANGE);
    }

    @Test
    @DisplayName("合法输入解析成功，边界内时间戳通过")
    void validRequestParses() {
        TradeReportRequest request = req("t1", "BTC", "100.25", "3.5",
                String.valueOf(NOW + MAX_SKEW)); // 恰好等于上界也合法
        System.out.println("[输入] 合法请求 => " + request);
        Trade trade = validator.validate(request);
        System.out.println("[判定依据] 解析成功 price=" + trade.price()
                + " volume=" + trade.volume() + " ts=" + trade.timestampMillis());
        assertEquals(new BigDecimal("100.25"), trade.price());
        assertEquals(new BigDecimal("3.5"), trade.volume());
        assertEquals(NOW + MAX_SKEW, trade.timestampMillis());
    }
}
