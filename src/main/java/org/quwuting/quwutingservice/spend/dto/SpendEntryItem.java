package org.quwuting.quwutingservice.spend.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 同步条目（POST /spend/entries/sync 的数组元素）。
 * <p>
 * 协议约定（44 号文档 §12.1）：客户端为源，重放安全——同 clientEntryId 重复上报
 * 幂等收敛为更新；删除经 deleted=true 承载（软删），无独立删除接口高频往返。
 * ts 为业务发生时刻 epoch 毫秒（结算=停止时刻；手动=记账时刻）。
 * direction 为可空方向（EXPENSE/INCOME，宽容大小写）——缺省 = EXPENSE
 * （存量/老客户端语义；非空但不可识别判该条非法，禁猜默认值，见 §24）。
 * companions 为可空的「一同计时的人」展示快照（2026-10-09，V47）：<b>null = 不带（保留库里已有值）</b>，
 * 非 null（含空数组）= 整体替换；它是元数据，非法项在服务端被丢弃而不是拒掉整条账目。
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
        Boolean deleted,
        String direction,
        List<SpendCompanionItem> companions
) {
}
