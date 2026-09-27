package com.github.lisama569.tickklineaggregatorjava.config;

/**
 * 周期组合配置非法（不满足整数倍对齐关系）时抛出，导致服务启动失败。
 *
 * <p>独立的异常类型使启动失败原因可区分：调用方/运维可据此明确识别是
 * 「周期组合不对齐」而非其他配置错误（如非正窗口长度、非正保留上限）。
 */
public class InvalidPeriodConfigException extends RuntimeException {

    public InvalidPeriodConfigException(String message) {
        super(message);
    }
}
