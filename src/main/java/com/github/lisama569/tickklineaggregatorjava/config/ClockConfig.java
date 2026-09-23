package com.github.lisama569.tickklineaggregatorjava.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 注册系统 UTC 时钟为 Bean，便于测试用固定时钟替换，
 * 从而确定性地校验时间戳范围逻辑。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
