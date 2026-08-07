package org.quwuting.quwutingservice.venuefeedback.repository;

import org.quwuting.quwutingservice.venuefeedback.entity.VenueFeedback;
import org.quwuting.quwutingservice.venuefeedback.enums.FeedbackType;
import org.quwuting.quwutingservice.venuefeedback.enums.ReportStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface VenueFeedbackRepository extends JpaRepository<VenueFeedback, Long>,
        JpaSpecificationExecutor<VenueFeedback> {

    /**
     * 按场所查询某状态下报（管理端按场所维度使用）。
     * 平台级列表走 {@link #findAll(org.springframework.data.jpa.domain.Specification, org.springframework.data.domain.Pageable)}
     * 组合筛选（状态/类型可选），不在此派生。
     */
    List<VenueFeedback> findByVenueIdAndStatusOrderByCreatedAtDesc(Long venueId, ReportStatus status);

    /** 按场所查询全部上报（管理端按场所维度使用） */
    List<VenueFeedback> findByVenueIdOrderByCreatedAtDesc(Long venueId);

    /** 当前用户的全部上报（「我的上报记录」个人中心数据源，倒序） */
    List<VenueFeedback> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** 当前用户对某场所的上报（「我的上报记录」详情页弹窗数据源，倒序） */
    List<VenueFeedback> findByUserIdAndVenueIdOrderByCreatedAtDesc(Long userId, Long venueId);

    /**
     * 查找用户对某场所某类型的指定状态记录（2026-08-07 防刷幂等兜底用）：
     * createFeedback 撞 PENDING 部分唯一索引（V2 迁移）后，回查已有待处理记录
     * 幂等返回。命中走 (user_id, venue_id, type, status) 过滤，由既有索引覆盖。
     */
    Optional<VenueFeedback> findByUserIdAndVenueIdAndTypeAndStatus(
            Long userId, Long venueId, FeedbackType type, ReportStatus status);
}
