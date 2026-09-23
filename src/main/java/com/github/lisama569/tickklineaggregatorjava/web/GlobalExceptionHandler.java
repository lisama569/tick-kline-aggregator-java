package com.github.lisama569.tickklineaggregatorjava.web;

import com.github.lisama569.tickklineaggregatorjava.domain.RejectReason;
import com.github.lisama569.tickklineaggregatorjava.dto.ErrorResponse;
import com.github.lisama569.tickklineaggregatorjava.error.TradeRejectedException;
import com.github.lisama569.tickklineaggregatorjava.error.WindowNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常处理：把拒绝原因映射为带可区分原因码的 HTTP 响应，
 * 调用方可以据 reasonCode 判断具体的拒绝原因，而非被静默丢弃。
 *
 * <ul>
 *   <li>422 UNPROCESSABLE_ENTITY：成交字段非法（价格/数量/时间戳等）</li>
 *   <li>404 NOT_FOUND：查询的窗口尚无成交</li>
 *   <li>400 BAD_REQUEST：JSON 无法解析、缺少或无法转换查询参数</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(TradeRejectedException.class)
    public ResponseEntity<ErrorResponse> handleRejected(TradeRejectedException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse("TRADE_REJECTED",
                        ex.getReason().name(), ex.getMessage()));
    }

    @ExceptionHandler(WindowNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(WindowNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("WINDOW_NOT_FOUND",
                        RejectReason.WINDOW_NOT_FOUND.name(), ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMalformed(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("MALFORMED_REQUEST",
                        RejectReason.MALFORMED_JSON.name(),
                        "请求体不是合法 JSON 或字段类型不匹配"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(
            MissingServletRequestParameterException ex) {
        String code = "at".equals(ex.getParameterName())
                ? RejectReason.TIMESTAMP_NOT_A_NUMBER.name()
                : "MISSING_PARAMETER";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("MALFORMED_REQUEST", code,
                        "缺少必需的查询参数: " + ex.getParameterName()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex) {
        String code = "at".equals(ex.getName())
                ? RejectReason.TIMESTAMP_NOT_A_NUMBER.name()
                : "PARAMETER_TYPE_MISMATCH";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("MALFORMED_REQUEST", code,
                        "查询参数无法转换为整数: " + ex.getName()));
    }
}
