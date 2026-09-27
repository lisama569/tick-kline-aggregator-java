package com.github.lisama569.tickklineaggregatorjava.model;

import java.util.List;

/**
 * 某标的某周期一段时间范围的一致 K 线视图。
 *
 * <p>整批 {@link #windows()} 来自同一时刻的一致快照：不会出现部分窗口已计入新成交、
 * 部分仍是旧值的矛盾情况。
 *
 * @param symbol      标的
 * @param windowSizeMs 查询所用周期
 * @param from        查询起点（epoch 毫秒，含）
 * @param to          查询终点（epoch 毫秒，含）
 * @param oldestWindowStart 该周期当前保留的最旧有数据窗口起点；无任何数据时为 null
 * @param newestWindowStart 该周期当前保留的最新有数据窗口起点；无任何数据时为 null
 * @param windows     范围覆盖的全部窗口，按起点升序，状态可区分
 */
public record KlineRange(
        String symbol,
        long windowSizeMs,
        long from,
        long to,
        Long oldestWindowStart,
        Long newestWindowStart,
        List<RangeKline> windows) {
}
