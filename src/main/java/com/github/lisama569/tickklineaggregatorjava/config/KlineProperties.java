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

    /** 默认（基础）窗口长度（毫秒），默认 1 分钟 */
    private long windowSizeMs = 60_000L;

    /** 除默认周期外同时聚合的其他周期（毫秒），如 300000(5分钟)、900000(15分钟)；默认仅单周期 */
    private List<Long> additionalWindowSizesMs = new ArrayList<>();

    /** 每个标的、每个周期保留的窗口数上限，超出后自动回收最旧窗口；默认 10000 */
    private int maxWindowsPerSymbol = 10_000;

    /** 一次范围查询最多返回的窗口数，防止拉取过宽；默认 5000 */
    private int maxRangeWindows = 5_000;

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

    public List<Long> getAdditionalWindowSizesMs() {
        return additionalWindowSizesMs;
    }

    public void setAdditionalWindowSizesMs(List<Long> additionalWindowSizesMs) {
        this.additionalWindowSizesMs = additionalWindowSizesMs;
    }

    public int getMaxWindowsPerSymbol() {
        return maxWindowsPerSymbol;
    }

    public void setMaxWindowsPerSymbol(int maxWindowsPerSymbol) {
        this.maxWindowsPerSymbol = maxWindowsPerSymbol;
    }

    public int getMaxRangeWindows() {
        return maxRangeWindows;
    }

    public void setMaxRangeWindows(int maxRangeWindows) {
        this.maxRangeWindows = maxRangeWindows;
    }
}
