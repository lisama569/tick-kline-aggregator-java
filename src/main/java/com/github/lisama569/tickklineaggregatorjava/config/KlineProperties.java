package com.github.lisama569.tickklineaggregatorjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * K 线聚合配置。
 */
@Component
@ConfigurationProperties(prefix = "kline")
public class KlineProperties {

    /** 主窗口长度（毫秒），默认 1 分钟。兼容历史配置键 kline.window-size-ms。 */
    private long windowSizeMs = 60_000L;

    /**
     * 同时聚合的周期列表（毫秒）。为空时退化为仅 {@link #windowSizeMs} 单周期，
     * 与历史行为完全一致。多周期时要求每个周期都是最小周期的整数倍，
     * 以保证大周期恰好由整数个连续小周期拼成、跨周期可对齐。
     */
    private List<Long> windowSizesMs = new ArrayList<>();

    /** 允许的最大迟到时间（毫秒），超出则拒绝 */
    private long maxLatenessMs = 24L * 3600_000L;

    /** 允许的最大未来偏移（毫秒），超出则拒绝 */
    private long maxFutureMs = 5L * 60_000L;

    /**
     * 每个标的、每个周期保留的窗口数上限。超出后自动回收最旧窗口；
     * 不影响幂等判定（已见 tickId 不随窗口回收）。
     */
    private int maxWindowsPerPeriod = 10_000;

    public long getWindowSizeMs() {
        return windowSizeMs;
    }

    public void setWindowSizeMs(long windowSizeMs) {
        this.windowSizeMs = windowSizeMs;
    }

    public List<Long> getWindowSizesMs() {
        return windowSizesMs;
    }

    public void setWindowSizesMs(List<Long> windowSizesMs) {
        this.windowSizesMs = windowSizesMs;
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

    public int getMaxWindowsPerPeriod() {
        return maxWindowsPerPeriod;
    }

    public void setMaxWindowsPerPeriod(int maxWindowsPerPeriod) {
        this.maxWindowsPerPeriod = maxWindowsPerPeriod;
    }
}
