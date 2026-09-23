package com.github.lisama569.tickklineaggregatorjava.exception;

import com.github.lisama569.tickklineaggregatorjava.model.RejectReason;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一错误处理：将拒绝原因以可区分的方式返回给调用方，绝不静默丢弃。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 拒绝响应体：reason 为可机读的区分原因，message 为人读细节。
     */
    public record RejectionResponse(RejectReason reason, String message) {
    }

    /** 业务校验拒绝（价格/数量/时间戳/范围等）。 */
    @ExceptionHandler(TickRejectedException.class)
    public ResponseEntity<RejectionResponse> handleRejected(TickRejectedException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new RejectionResponse(ex.getReason(), ex.getMessage()));
    }

    /** 请求体无法解析（如时间戳为非数字、JSON 结构损坏）。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<RejectionResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new RejectionResponse(RejectReason.MALFORMED_REQUEST,
                        "request body cannot be parsed: " + ex.getMessage()));
    }
}
