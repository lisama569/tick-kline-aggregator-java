package com.github.lisama569.tickklineaggregatorjava.service;

import com.github.lisama569.tickklineaggregatorjava.config.KlineProperties;
import com.github.lisama569.tickklineaggregatorjava.exception.TickRejectedException;
import com.github.lisama569.tickklineaggregatorjava.model.RejectReason;
import com.github.lisama569.tickklineaggregatorjava.model.TradeTick;
import org.springframework.stereotype.Component;

/**
 * 成交合法性校验，非法输入抛出 TickRejectedException（携带可区分原因）。
 */
@Component
public class TickValidator {

    private final KlineProperties properties;

    public TickValidator(KlineProperties properties) {
        this.properties = properties;
    }

    /**
     * 校验一笔成交，非法时抛出 TickRejectedException。
     *
     * @param tick 待校验成交
     * @throws TickRejectedException 校验失败，reason 指明具体原因
     */
    public void validate(TradeTick tick) {
        if (tick == null) {
            throw new TickRejectedException(RejectReason.INVALID_TICK_ID, "tick must not be null");
        }
        if (tick.tickId() == null || tick.tickId().isBlank()) {
            throw new TickRejectedException(RejectReason.INVALID_TICK_ID,
                    "tickId must not be null or blank");
        }
        if (tick.symbol() == null || tick.symbol().isBlank()) {
            throw new TickRejectedException(RejectReason.INVALID_SYMBOL,
                    "symbol must not be null or blank");
        }
        if (!Double.isFinite(tick.price()) || tick.price() <= 0) {
            throw new TickRejectedException(RejectReason.INVALID_PRICE,
                    "price must be a positive finite number, got " + tick.price());
        }
        if (!Double.isFinite(tick.quantity()) || tick.quantity() <= 0) {
            throw new TickRejectedException(RejectReason.INVALID_QUANTITY,
                    "quantity must be a positive finite number, got " + tick.quantity());
        }
        if (tick.timestamp() <= 0) {
            throw new TickRejectedException(RejectReason.INVALID_TIMESTAMP,
                    "timestamp must be a positive epoch millis, got " + tick.timestamp());
        }
        long now = System.currentTimeMillis();
        if (tick.timestamp() > now + properties.getMaxFutureMs()) {
            throw new TickRejectedException(RejectReason.TIMESTAMP_OUT_OF_RANGE,
                    "timestamp " + tick.timestamp() + " is too far in the future (now=" + now + ")");
        }
        if (tick.timestamp() < now - properties.getMaxLatenessMs()) {
            throw new TickRejectedException(RejectReason.TIMESTAMP_OUT_OF_RANGE,
                    "timestamp " + tick.timestamp() + " exceeds max lateness (now=" + now + ")");
        }
    }
}
