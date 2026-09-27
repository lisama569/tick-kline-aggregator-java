package com.github.lisama569.tickklineaggregatorjava.tests;

import com.github.lisama569.tickklineaggregatorjava.config.KlineProperties;
import com.github.lisama569.tickklineaggregatorjava.exception.TickRejectedException;
import com.github.lisama569.tickklineaggregatorjava.model.RejectReason;
import com.github.lisama569.tickklineaggregatorjava.service.KlineAggregator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 周期组合合法性测试：配置的每个周期必须是最小（基础）周期的整数倍，
 * 否则窗口边界永远无法对齐；不合法组合在聚合器构造时（即服务启动时）
 * 以可区分原因 INVALID_PERIOD_CONFIG 失败，服务绝不带着错配运行。
 * 1m + 5m（+15m）这类合法组合不受影响。每个用例打印输入与判定依据。
 */
class PeriodConfigValidationTests {

    private KlineProperties props(long base, List<Long> additional) {
        KlineProperties p = new KlineProperties();
        p.setWindowSizeMs(base);
        p.setAdditionalWindowSizesMs(additional);
        return p;
    }

    private void assertInvalidConfig(long base, List<Long> additional) {
        System.out.println("[周期配置] 输入: base=" + base + "ms, additional=" + additional + "ms");
        TickRejectedException ex = assertThrows(TickRejectedException.class,
                () -> new KlineAggregator(props(base, additional)));
        System.out.println("[周期配置] 判定依据: 存在不是基础周期整数倍的周期, 必须以 INVALID_PERIOD_CONFIG 拒绝");
        System.out.println("[周期配置] 实际: reason=" + ex.getReason() + ", message=" + ex.getMessage());
        assertEquals(RejectReason.INVALID_PERIOD_CONFIG, ex.getReason());
    }

    @Test
    void nonIntegerMultipleMessageExplainsAlignment() {
        System.out.println("[周期配置] 输入: base=60000ms, additional=[90000]ms");
        TickRejectedException ex = assertThrows(TickRejectedException.class,
                () -> new KlineAggregator(props(60_000L, List.of(90_000L))));
        System.out.println("[周期配置] 判定依据: 失败信息必须点明 90000 与整数倍对齐要求");
        System.out.println("[周期配置] 实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("90000"));
        assertTrue(ex.getMessage().contains("integer multiple"));
    }

    @Test
    void nonIntegerMultiplePeriodsAreRejectedAtStartup() {
        // 题目场景：基础 60s + 额外 90s —— 边界对不齐，必须拒绝启动
        assertInvalidConfig(60_000L, List.of(90_000L));
        // 100s 不是 60s 的整数倍
        assertInvalidConfig(60_000L, List.of(100_000L));
        // 混合配置里只要有一个非法即拒绝（合法的 300s 不洗白 90s）
        assertInvalidConfig(60_000L, List.of(300_000L, 90_000L));
        // 以最小周期为基准：90s 基础 + 120s 额外，120 不是 90 的整数倍
        assertInvalidConfig(90_000L, List.of(120_000L));
        // 非正周期
        assertInvalidConfig(60_000L, List.of(0L));
    }

    @Test
    void legalPeriodCombinationsStillStart() {
        // 1m + 5m / 15m 等整数倍组合必须正常构造，结果周期升序
        KlineAggregator agg1 = new KlineAggregator(props(60_000L, List.of(300_000L, 900_000L)));
        assertArrayEquals(new long[]{60_000L, 300_000L, 900_000L}, agg1.configuredPeriods());
        System.out.println("[周期配置] 合法输入: 60s+300s+900s -> configuredPeriods="
                + java.util.Arrays.toString(agg1.configuredPeriods()));

        // 2m + 6m(360000)：360000 是 120000 的 3 倍，合法
        KlineAggregator agg2 = new KlineAggregator(props(120_000L, List.of(360_000L)));
        assertArrayEquals(new long[]{120_000L, 360_000L}, agg2.configuredPeriods());

        // 单周期（无额外周期）仍然合法
        KlineAggregator agg3 = new KlineAggregator(props(60_000L, List.of()));
        assertArrayEquals(new long[]{60_000L}, agg3.configuredPeriods());
        System.out.println("[周期配置] 判定依据: 整数倍组合与单周期不受新校验影响, 服务正常启动");
    }

    @Test
    void springContextFailsToStartWithInvalidPeriodCombination() {
        // 端到端：错配必须让 Spring Boot 启动失败（Bean 创建期抛出），而不是带病提供服务。
        // 通过命令行属性参数提供配置（等价于 application.properties / 环境变量的绑定效果）。
        System.out.println("[周期配置] 输入(启动): kline.window-size-ms=60000, additional=90000");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> {
            try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(
                    com.github.lisama569.tickklineaggregatorjava.Application.class)
                    .web(org.springframework.boot.WebApplicationType.NONE)
                    .run("--kline.window-size-ms=60000",
                            "--kline.additional-window-sizes-ms=90000")) {
                System.out.println("[周期配置] 意外: 上下文启动成功");
            }
        });
        boolean foundTickRejected = findInChain(ex, TickRejectedException.class);
        boolean mentionsAlignment = messageChainMentions(ex, "integer multiple")
                && messageChainMentions(ex, "90000");
        System.out.println("[周期配置] 判定依据: ApplicationContext 启动失败, 异常链包含可区分原因");
        System.out.println("[周期配置] 实际: " + rootSummary(ex));
        assertTrue(foundTickRejected && mentionsAlignment,
                "启动失败原因链中必须能定位到整数倍对齐失败(INVALID_PERIOD_CONFIG)");
    }

    @Test
    void springContextStartsWithLegalPeriodCombination() {
        // 对照：合法组合 1m+5m 必须正常启动并绑定成功
        System.out.println("[周期配置] 输入(启动-对照): base=60000, additional=300000");
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(
                com.github.lisama569.tickklineaggregatorjava.Application.class)
                .web(org.springframework.boot.WebApplicationType.NONE)
                .run("--kline.window-size-ms=60000",
                        "--kline.additional-window-sizes-ms=300000")) {
            KlineAggregator agg = ctx.getBean(KlineAggregator.class);
            assertArrayEquals(new long[]{60_000L, 300_000L}, agg.configuredPeriods());
            System.out.println("[周期配置] 判定依据(对照): 合法组合正常启动, periods="
                    + java.util.Arrays.toString(agg.configuredPeriods()));
        }
    }

    private static boolean findInChain(Throwable t, Class<?> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }

    private static boolean messageChainMentions(Throwable t, String text) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c.getMessage() != null && c.getMessage().contains(text)) {
                return true;
            }
        }
        return false;
    }

    private static String rootSummary(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }
}
