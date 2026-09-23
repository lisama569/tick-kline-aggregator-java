package com.github.lisama569.tickklineaggregatorjava.dto;

/**
 * 统一错误响应体。
 */
public record ErrorResponse(String error,
                            String reasonCode,
                            String message) {
}
