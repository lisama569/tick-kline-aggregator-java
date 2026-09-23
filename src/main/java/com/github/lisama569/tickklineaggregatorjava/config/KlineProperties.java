package com.github.lisama569.tickklineaggregatorjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * K 线聚合配置。
 */
@Component
@ConfigurationProperties(prefix = "kline")
public class KlineProperties {

    /** 窗口长度（毫秒），默认 1 分钟 */
    private long windowSizeMs = 60_000L;

    /** 允许的最大迟到时间（毫秒），超出则拒绝 */
    private long maxLatenessMs = 24L * 3600_000L;

    /** 允许的最大未来偏移（毫秒），超出则拒绝 */
    private long maxFutureMs = 5L * 60_000L;

    public long getWindowSizeMs() {
        return windowSizeMs;
    }

    public void setWindowSizeMs(long windowSizeMs) {
        this.windowSizeMs = windowSizeMs;
    }

    public long getMaxLatenessMs() {
        return maxLatenessMs;
    }

    public void setMaxLatenessMs(long maxLatenessMs) {
        this.maxLatenessMs = maxLatenessMs;
    }

    public long getMaxFutureMs() {
        return maxFutureMs;
    }

    public void setMaxFutureMs(long maxFutureMs) {
        this.maxFutureMs = maxFutureMs;
    }
}
