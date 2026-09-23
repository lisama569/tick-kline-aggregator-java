package com.github.lisama569.tickklineaggregatorjava.error;

/**
 * 查询的时间窗口尚无任何成交时抛出（映射为 404）。
 */
public class WindowNotFoundException extends RuntimeException {

    public WindowNotFoundException(String message) {
        super(message);
    }
}
