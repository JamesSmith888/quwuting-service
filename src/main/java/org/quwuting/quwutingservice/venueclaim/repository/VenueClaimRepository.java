package org.quwuting.quwutingservice.venueclaim.repository;

import org.quwuting.quwutingservice.venueclaim.entity.VenueClaim;
import org.quwuting.quwutingservice.venueclaim.enums.ClaimStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

/**
 * 门店认领申请工单仓储。
 * <p>
 * 防重复申请的强约束在库内（V12 部分唯一索引 (user_id, venue_id) WHERE
 * status='PENDING'），应用层通过 {@code findByUserIdAndVenueIdAndStatus}
 * 先行幂等 + 冲突时 catch DataIntegrityViolationException（23505）回查，
 * 与 venuefeedback 的 V2/V8 去重模式一致。
 */
public interface VenueClaimRepository extends JpaRepository<VenueClaim, Long>, JpaSpecificationExecutor<VenueClaim> {

    /** 我的认领记录（按提交时间倒序） */
    List<VenueClaim> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** 用户对某门店的认领记录（按提交时间倒序，最新一条 = 当前状态） */
    List<VenueClaim> findByUserIdAndVenueIdOrderByCreatedAtDesc(Long userId, Long venueId);

    /** 用户对某门店处于待审核的申请（幂等 / 菜单态判定用） */
    Optional<VenueClaim> findFirstByUserIdAndVenueIdAndStatusOrderByCreatedAtDesc(
            Long userId, Long venueId, ClaimStatus status);

    /** 管理端分页列表（Specification 组合状态筛选） */
    Page<VenueClaim> findAll(org.springframework.data.jpa.domain.Specification<VenueClaim> spec, Pageable pageable);
}
