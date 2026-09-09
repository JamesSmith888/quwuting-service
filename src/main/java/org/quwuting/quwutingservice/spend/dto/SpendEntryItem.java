package org.quwuting.quwutingservice.spend.dto;

import java.math.BigDecimal;

/**
 * 同步条目（POST /spend/entries/sync 的数组元素）。
 * <p>
 * 协议约定（44 号文档 §12.1）：客户端为源，重放安全——同 clientEntryId 重复上报
 * 幂等收敛为更新；删除经 deleted=true 承载（软删），无独立删除接口高频往返。
 * ts 为业务发生时刻 epoch 毫秒（结算=停止时刻；手动=记账时刻）。
 */
public record SpendEntryItem(
        String clientEntryId,
        long ts,
        BigDecimal amount,
        String category,
        String source,
        String sourceRefId,
        Long venueId,
        String venueName,
        Integer durationSeconds,
        Boolean deleted
) {
}
