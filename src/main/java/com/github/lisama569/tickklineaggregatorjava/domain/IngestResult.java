package com.github.lisama569.tickklineaggregatorjava.domain;

/**
 * 上报处理结果：accepted 表示是否计入；duplicate 表示是否为幂等重复提交；
 * rejectReason 仅在被拒绝时非空。
 */
public record IngestResult(boolean accepted,
                           boolean duplicate,
                           RejectReason rejectReason,
                           WindowKey window) {
}
