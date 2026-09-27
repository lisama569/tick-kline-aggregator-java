package com.github.lisama569.tickklineaggregatorjava.model;

/**
 * 范围查询中单个窗口的数据状态，用于区分“窗口无成交”与“系统中已无该段数据”。
 */
public enum WindowStatus {
    /** 窗口内有成交，{@code kline} 非空且为一致快照 */
    FILLED,
    /** 窗口处于保留范围内但无任何成交，{@code kline} 为空，属于确定可预测的空窗口 */
    EMPTY,
    /** 窗口数据已按保留上限回收（或该标的在系统中根本没有数据），{@code kline} 为空 */
    UNAVAILABLE,
    /**
     * 大周期窗口自身有聚合值，但其覆盖的最小周期窗口已（全部或部分）被回收，
     * 大周期结果无法由保留的小周期完整对上，按“不可用”处理而非正常结果；
     * 与 {@link #UNAVAILABLE} 区分：后者连大周期自身数据都已不存在
     */
    UNVERIFIABLE
}
