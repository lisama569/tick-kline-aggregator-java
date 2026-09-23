package com.github.lisama569.tickklineaggregatorjava.error;

import com.github.lisama569.tickklineaggregatorjava.domain.RejectReason;

/**
 * 成交上报被拒绝时抛出，携带可区分的原因码。
 */
public class TradeRejectedException extends RuntimeException {

    private final RejectReason reason;

    public TradeRejectedException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason getReason() {
        return reason;
    }
}
