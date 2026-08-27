package org.quwuting.quwutingservice.points.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.dancer.entity.DemandRecord;
import org.quwuting.quwutingservice.dancer.enums.DemandStatus;
import org.quwuting.quwutingservice.dancer.repository.DemandRecordRepository;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.points.dto.FulfillmentResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 邀约履约确认服务（2026-08-27，docs/agents/23-user-contribution-and-fulfillment.md
 * 「P1 履约闭环」）。
 * <p>
 * 语义：客人确认完成邀约 → 形成「与舞伴已合作 N 次」的<b>私域履约信号</b>——
 * 舞伴/管理员在邀约场景参考（判断客人是否靠谱），不新增积分、不公开广播
 * （合规：无公开用户主页，见 AGENTS.md「小程序类目合规 UGC 红线」）。
 * <p>
 * 防刷设计：确认不产生积分/收益、不公开排行，仅本人邀约详情 + 管理端邀约单可见；
 * 且仅<b>已获批发放联系方式</b>的邀约（APPROVED/AUTO_RELEASED 或存量 NULL 等价
 * 已发放）可确认——客人无法凭空制造确认。幂等：fulfilled_at 只写一次（锚点记录
 * 语义，同 DemandRecord 只写不删约定），重复确认返回既有数据。
 */
@Service
@RequiredArgsConstructor
public class DemandFulfillmentService {

    private final DemandRecordRepository demandRecordRepository;

    /**
     * 确认履约（POST /points/demands/{id}/confirm，需登录 + 本人）。
     * 归属校验 = userId + id 双重条件（越权 → 1001）；状态校验 = 已获批才可确认
     * （存量 NULL 等价已发放，见 DemandStatus javadoc）。幂等返回 confirmed=false。
     */
    @Transactional
    public FulfillmentResponse confirm(Long userId, Long demandId) {
        DemandRecord record = demandRecordRepository.findByUserIdAndId(userId, demandId)
                .orElseThrow(() -> new BusinessException(1001, "邀约不存在"));
        DemandStatus status = DemandStatus.parseOrNull(record.getStatus());
        if (status != null && !status.released()) {
            throw new BusinessException(1001, "该邀约尚未获批，暂不能确认履约");
        }
        boolean confirmed = false;
        if (record.getFulfilledAt() == null) {
            record.setFulfilledAt(LocalDateTime.now());
            demandRecordRepository.save(record);
            confirmed = true;
        }
        long cooperationCount = demandRecordRepository.countConfirmedByUserAndDancer(
                userId, record.getDancerId());
        return new FulfillmentResponse(confirmed, record.getFulfilledAt(), cooperationCount);
    }

    /** 该客人与该舞伴的履约确认数（「与 TA 已合作 N 次」，含本次；需求单详情/管理端邀约单共用） */
    @Transactional(readOnly = true)
    public long countConfirmed(Long userId, Long dancerId) {
        return demandRecordRepository.countConfirmedByUserAndDancer(userId, dancerId);
    }
}
