package com.github.lisama569.tickklineaggregatorjava.service;

import com.github.lisama569.tickklineaggregatorjava.domain.RejectReason;
import com.github.lisama569.tickklineaggregatorjava.domain.Trade;
import com.github.lisama569.tickklineaggregatorjava.dto.TradeReportRequest;
import com.github.lisama569.tickklineaggregatorjava.error.TradeRejectedException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;

/**
 * 成交请求解析与校验：把字符串字段解析为强类型 Trade，
 * 任何非法输入抛出携带 RejectReason 的 TradeRejectedException。
 *
 * <p>校验顺序固定为：tradeId → symbol → price（解析、正数）→
 * volume（解析、正数）→ timestamp（解析、范围），保证拒绝原因唯一确定。</p>
 */
@Component
public class TradeValidator {

    private final AggregatorProperties properties;
    private final Clock clock;

    public TradeValidator(AggregatorProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public Trade validate(TradeReportRequest request) {
        if (request == null) {
            throw new TradeRejectedException(RejectReason.MALFORMED_JSON,
                    "请求体为空或不是合法的 JSON 对象");
        }

        String tradeId = trimOrNull(request.tradeId());
        if (tradeId == null) {
            throw new TradeRejectedException(RejectReason.MISSING_TRADE_ID,
                    "tradeId 缺失或为空白");
        }

        String symbol = trimOrNull(request.symbol());
        if (symbol == null) {
            throw new TradeRejectedException(RejectReason.MISSING_SYMBOL,
                    "symbol 缺失或为空白");
        }

        BigDecimal price = parsePositiveDecimal(request.price(),
                RejectReason.PRICE_NOT_A_NUMBER,
                RejectReason.PRICE_NOT_POSITIVE, "price");
        BigDecimal volume = parsePositiveDecimal(request.volume(),
                RejectReason.VOLUME_NOT_A_NUMBER,
                RejectReason.VOLUME_NOT_POSITIVE, "volume");

        long timestamp = parseTimestamp(request.timestamp());

        return new Trade(tradeId, symbol, price, volume, timestamp);
    }

    private static String trimOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static BigDecimal parsePositiveDecimal(String raw,
                                                   RejectReason notANumber,
                                                   RejectReason notPositive,
                                                   String fieldName) {
        String value = raw == null ? null : raw.trim();
        if (value == null || value.isEmpty()) {
            throw new TradeRejectedException(notANumber,
                    fieldName + " 缺失或为空，无法解析为数值");
        }
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(value);
        } catch (NumberFormatException ex) {
            throw new TradeRejectedException(notANumber,
                    fieldName + " 不是合法的十进制数值: '" + value + "'");
        }
        if (parsed.compareTo(BigDecimal.ZERO) <= 0) {
            throw new TradeRejectedException(notPositive,
                    fieldName + " 必须为正数，实际为: " + parsed.toPlainString());
        }
        return parsed;
    }

    private long parseTimestamp(String raw) {
        String value = raw == null ? null : raw.trim();
        if (value == null || value.isEmpty()) {
            throw new TradeRejectedException(RejectReason.TIMESTAMP_NOT_A_NUMBER,
                    "timestamp 缺失或为空，无法解析为 epoch 毫秒数");
        }
        long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw new TradeRejectedException(RejectReason.TIMESTAMP_NOT_A_NUMBER,
                    "timestamp 不是合法的 64 位整数（epoch 毫秒）: '" + value + "'");
        }
        long now = clock.millis();
        long min = properties.getMinTimestampMillis();
        long max = now + properties.getMaxFutureSkewMillis();
        if (parsed < min || parsed > max) {
            throw new TradeRejectedException(RejectReason.TIMESTAMP_OUT_OF_RANGE,
                    "timestamp 超出允许范围 [" + min + ", " + max + "]，实际为: " + parsed);
        }
        return parsed;
    }
}
