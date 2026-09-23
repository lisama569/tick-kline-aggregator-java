package com.github.lisama569.tickklineaggregatorjava.exception;

import com.github.lisama569.tickklineaggregatorjava.model.RejectReason;

/**
 * 成交校验未通过时抛出，携带可区分的拒绝原因。
 */
public class TickRejectedException extends RuntimeException {

    private final RejectReason reason;

    public TickRejectedException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason getReason() {
        return reason;
    }
}
