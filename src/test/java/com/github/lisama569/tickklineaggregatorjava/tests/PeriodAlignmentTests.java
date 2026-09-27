package com.github.lisama569.tickklineaggregatorjava.tests;

import com.github.lisama569.tickklineaggregatorjava.config.InvalidPeriodConfigException;
import com.github.lisama569.tickklineaggregatorjava.config.KlineProperties;
import com.github.lisama569.tickklineaggregatorjava.model.IngestStatus;
import com.github.lisama569.tickklineaggregatorjava.model.TradeTick;
import com.github.lisama569.tickklineaggregatorjava.service.KlineAggregator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 周期组合对齐校验测试：不满足整数倍对齐关系的周期组合必须在启动（构造）时
 * 以可区分的 InvalidPeriodConfigException 失败；合法组合不受影响。
 * 每个用例打印输入与判定依据。
 */
class PeriodAlignmentTests {

    private static final long M1 = 60_000L;
    private static final long M5 = 300_000L;
    private static final long M15 = 900_000L;

    private static KlineProperties props(long base, Long... additional) {
        KlineProperties p = new KlineProperties();
        p.setWindowSizeMs(base);
        p.setAdditionalWindowSizesMs(List.of(additional));
        return p;
    }

    @Test
    void misalignedPeriodPairFailsAtStartupWithDistinctReason() {
        KlineProperties p = props(M1, 90_000L); // 90s 不是 60s 的整数倍
        System.out.println("[周期对齐] 输入: 基础周期 60000ms + 额外周期 90000ms");
        System.out.println("[周期对齐] 判定依据: 90000 不是 60000 的整数倍, 窗口网格无法嵌套, "
                + "启动必须以 InvalidPeriodConfigException 失败且原因可区分");

        InvalidPeriodConfigException ex = assertThrows(InvalidPeriodConfigException.class,
                () -> new KlineAggregator(p));
        System.out.println("[周期对齐] 实际: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        assertTrue(ex.getMessage().contains("90000"), "失败信息必须指明违规周期");
        assertTrue(ex.getMessage().contains("60000"), "失败信息必须指明对齐基准");
    }

    @Test
    void transitivelyMisalignedChainFailsAtStartup() {
        // 60s / 120s / 300s：300 是 60 的整数倍，但不是 120 的整数倍 —— 同样不对齐
        KlineProperties p = props(M1, 120_000L, M5);
        System.out.println("[周期对齐] 输入: 60000/120000/300000 (300000 不是 120000 的整数倍)");
        System.out.println("[周期对齐] 判定依据: 相邻周期必须逐一满足整数倍关系, 仅与基础周期对齐不够");

        InvalidPeriodConfigException ex = assertThrows(InvalidPeriodConfigException.class,
                () -> new KlineAggregator(p));
        System.out.println("[周期对齐] 实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("300000") && ex.getMessage().contains("120000"));
    }

    @Test
    void smallerAdditionalPeriodAlsoValidated() {
        // 额外周期比基础周期还小且不能整除基础周期：60s 基础 + 45s 额外
        KlineProperties p = props(M1, 45_000L);
        System.out.println("[周期对齐] 输入: 基础 60000ms + 额外 45000ms (60000 不是 45000 的整数倍)");
        System.out.println("[周期对齐] 判定依据: 排序后相邻周期 45000->60000 不满足整数倍, 必须启动失败");

        InvalidPeriodConfigException ex = assertThrows(InvalidPeriodConfigException.class,
                () -> new KlineAggregator(p));
        System.out.println("[周期对齐] 实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("45000"));
    }

    @Test
    void legalCombinationsAreNotAffected() {
        // 合法组合：单周期、60s+300s、60s+300s+900s、重复配置去重
        long[][] legal = {
                {M1},
                {M1, M5},
                {M1, M5, M15},
                {M1, M5, M5}, // 重复周期去重后仍合法
        };
        for (long[] combo : legal) {
            KlineProperties p = new KlineProperties();
            p.setWindowSizeMs(combo[0]);
            p.setAdditionalWindowSizesMs(
                    java.util.Arrays.stream(combo, 1, combo.length).boxed().toList());
            KlineAggregator agg = new KlineAggregator(p); // 不得抛异常
            long now = System.currentTimeMillis();
            var r = agg.ingest(new TradeTick("A0", "BTCUSDT", 100.0, 1.0, now));
            System.out.println("[周期对齐] 合法组合 " + java.util.Arrays.toString(combo)
                    + " 启动成功, 上报结果=" + r.status());
            assertEquals(IngestStatus.ACCEPTED, r.status());
        }
        System.out.println("[周期对齐] 判定依据: 满足整数倍对齐的组合(含 1m+5m)必须正常工作不受影响");
    }

    @Test
    void nonPositiveWindowSizeStillRejectedAsBefore() {
        KlineProperties p = new KlineProperties();
        p.setWindowSizeMs(0);
        System.out.println("[周期对齐] 输入: 基础周期 0ms");
        System.out.println("[周期对齐] 判定依据: 非正周期仍按原有 IllegalArgumentException 拒绝, 语义不回归");
        assertThrows(IllegalArgumentException.class, () -> new KlineAggregator(p));
    }
}
