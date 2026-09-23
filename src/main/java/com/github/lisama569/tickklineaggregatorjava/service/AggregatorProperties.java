package com.github.lisama569.tickklineaggregatorjava.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 聚合器可配置参数：窗口大小、允许的时间戳范围。
 */
@ConfigurationProperties(prefix = "aggregator")
public class AggregatorProperties {

    /** K 线窗口大小（毫秒），默认 1 分钟。 */
    private long windowMillis = 60_000L;

    /** 允许的最早成交时间戳（epoch 毫秒），默认 2000-01-01T00:00:00Z。 */
    private long minTimestampMillis = 946684800000L;

    /** 允许成交时间戳超过当前时钟的最大毫秒数（容忍轻微时钟漂移）。 */
    private long maxFutureSkewMillis = 5_000L;

    public long getWindowMillis() {
        return windowMillis;
    }

    public void setWindowMillis(long windowMillis) {
        this.windowMillis = windowMillis;
    }

    public long getMinTimestampMillis() {
        return minTimestampMillis;
    }

    public void setMinTimestampMillis(long minTimestampMillis) {
        this.minTimestampMillis = minTimestampMillis;
    }

    public long getMaxFutureSkewMillis() {
        return maxFutureSkewMillis;
    }

    public void setMaxFutureSkewMillis(long maxFutureSkewMillis) {
        this.maxFutureSkewMillis = maxFutureSkewMillis;
    }
}
