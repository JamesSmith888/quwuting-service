package org.quwuting.quwutingservice.venueclaim.repository;

import org.quwuting.quwutingservice.venueclaim.entity.VenueClaim;
import org.quwuting.quwutingservice.venueclaim.enums.ClaimStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 门店认领申请工单仓储。
 * <p>
 * 防重复申请的强约束在库内（V12 部分唯一索引 (user_id, venue_id) WHERE
 * status='PENDING'），应用层经原子 upsert（{@link #upsertPending}）收口并发
 * （2026-08-20 确定性化，替代旧「save + catch 23505 + 同事务回查」：PG 语句失败
 * 后事务中止 25P02，catch 内回查必然 HTTP 500，见 15-governance 错误表）。
 */
public interface VenueClaimRepository extends JpaRepository<VenueClaim, Long>, JpaSpecificationExecutor<VenueClaim> {

    /** 我的认领记录（按提交时间倒序） */
    List<VenueClaim> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** 我的认领记录按状态过滤（2026-08-28 管理端用户详情下钻，docs/agents/23：
     *  「认领 N 次」/「认领分布」统计点击查看每条明细的数据源，status 可选） */
    List<VenueClaim> findByUserIdAndStatusOrderByCreatedAtDesc(Long userId, ClaimStatus status);

    /** 用户对某门店的认领记录（按提交时间倒序，最新一条 = 当前状态） */
    List<VenueClaim> findByUserIdAndVenueIdOrderByCreatedAtDesc(Long userId, Long venueId);

    /** 用户对某门店处于待审核的申请（幂等 / 菜单态判定用） */
    Optional<VenueClaim> findFirstByUserIdAndVenueIdAndStatusOrderByCreatedAtDesc(
            Long userId, Long venueId, ClaimStatus status);

    /**
     * PENDING 认领工单的<b>确定性原子写入</b>（2026-08-20 根因修复）。
     * 命中 V12 部分唯一索引 {@code qwt_uk_claims_user_venue_pending}（同一用户对
     * 同一门店最多一条 PENDING 申请）时 DO NOTHING，调用方随后回查幂等返回既有
     * 工单——恒 1 次往返零异常，替代「save + catch 23505 + 同事务回查」的不可靠
     * 模式。冲突目标 = 列清单 + 完整索引谓词（部分唯一索引推断要求）。
     *
     * @return 受影响行数：1 = 新工单；0 = 已有 PENDING 工单（幂等跳过）
     */
    @Modifying
    @Query(value = "INSERT INTO qwt_venue_claims " +
                   "(venue_id, user_id, real_name, contact_phone, contact_wechat, license_urls, note, " +
                   " status, handled_by, handle_note, handled_at, created_at, updated_at, deleted) " +
                   "VALUES (:venueId, :userId, :realName, :contactPhone, :contactWechat, :licenseUrls, :note, " +
                   " 'PENDING', NULL, NULL, NULL, :now, :now, false) " +
                   "ON CONFLICT (user_id, venue_id) WHERE status = 'PENDING' DO NOTHING",
           nativeQuery = true)
    int upsertPending(@Param("venueId") Long venueId,
                      @Param("userId") Long userId,
                      @Param("realName") String realName,
                      @Param("contactPhone") String contactPhone,
                      @Param("contactWechat") String contactWechat,
                      @Param("licenseUrls") String licenseUrls,
                      @Param("note") String note,
                      @Param("now") LocalDateTime now);

    /** 管理端分页列表（Specification 组合状态筛选） */
    Page<VenueClaim> findAll(org.springframework.data.jpa.domain.Specification<VenueClaim> spec, Pageable pageable);

    /**
     * 批量统计：指定用户集某状态的认领工单数（2026-08-27 贡献档案/管理端用户列表
     * 聚合，docs/agents/23）：认领贡献只计 APPROVED（通过 = 真实贡献；PENDING/
     * REJECTED/WITHDRAWN 不计）。返回 Object[]{userId, count}；无匹配用户不出现在
     * 结果（调用方按 0 兜底）。
     */
    @Query("SELECT c.userId, COUNT(c) FROM VenueClaim c " +
           "WHERE c.userId IN :userIds AND c.status = :status GROUP BY c.userId")
    List<Object[]> countGroupByUserIdsAndStatus(@Param("userIds") Collection<Long> userIds,
                                                @Param("status") ClaimStatus status);

    /**
     * 批量统计：指定用户集 × 认领状态的分布（2026-08-27 用户管理增强——详情页
     * 认领概览按状态分布：PENDING/APPROVED/REJECTED/WITHDRAWN）。
     * 返回 Object[]{userId, status, count}。
     */
    @Query("SELECT c.userId, c.status, COUNT(c) FROM VenueClaim c " +
           "WHERE c.userId IN :userIds GROUP BY c.userId, c.status")
    List<Object[]> countByUserAndStatusGroup(@Param("userIds") Collection<Long> userIds);
}
