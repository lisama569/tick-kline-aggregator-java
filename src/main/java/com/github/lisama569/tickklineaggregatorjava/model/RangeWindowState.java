package com.github.lisama569.tickklineaggregatorjava.model;

/**
 * 范围查询中单个窗口的可区分状态。
 * 用于让调用方区分“空窗”“有数据”与“系统已不保留这段数据”。
 */
public enum RangeWindowState {
    /** 窗口内有成交，{@link RangeKline#kline()} 非空 */
    HAS_DATA,
    /**
     * 窗口落在该周期已观察到的时间跨度（最旧~最新有数据窗口）之内，
     * 但本身无成交。空窗仅为查询时的确定性占位，不参与存储与回收。
     */
    EMPTY,
    /**
     * 窗口超出当前保留范围：要么早于回收下限（已被回收），
     * 要么该标的/周期尚无任何数据。调用方可据此与 {@link #EMPTY} 区分。
     */
    NOT_RETAINED
}
