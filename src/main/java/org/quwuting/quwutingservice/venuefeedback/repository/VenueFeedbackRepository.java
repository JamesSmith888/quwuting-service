package org.quwuting.quwutingservice.venuefeedback.repository;

import org.quwuting.quwutingservice.venuefeedback.entity.VenueFeedback;
import org.quwuting.quwutingservice.venuefeedback.enums.FeedbackField;
import org.quwuting.quwutingservice.venuefeedback.enums.FeedbackType;
import org.quwuting.quwutingservice.venuefeedback.enums.ReportStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface VenueFeedbackRepository extends JpaRepository<VenueFeedback, Long>,
        JpaSpecificationExecutor<VenueFeedback> {

    /**
     * 按状态计数（2026-08-10 首页 FAB「上报管理」红点数据源）。
     * 轻量 COUNT：管理端未读徽标只关心待处理量，不拉列表——与 message 模块
     * unread-count 同模式；status 谓词由 V2 部分唯一索引（WHERE status='PENDING'）
     * 覆盖，避免全表扫描。
     */
    long countByStatus(ReportStatus status);

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
     * 仅用于非纠错场景（field = null 的行，去重单位 = type，见 V8 迁移）。
     */
    Optional<VenueFeedback> findByUserIdAndVenueIdAndTypeAndStatus(
            Long userId, Long venueId, FeedbackType type, ReportStatus status);

    /**
     * 按纠错字段回查指定状态记录（2026-08-10，V8 拆分唯一索引后新增）：
     * 纠错场景（field IS NOT NULL）的去重单位升级为 (user_id, venue_id, type,
     * field)（见 V8 迁移）——撞唯一索引后按同一字段回查已有 PENDING 记录幂等返回，
     * 避免把"同场所同类型但不同字段"的纠错误当成重复上报。
     */
    Optional<VenueFeedback> findByUserIdAndVenueIdAndTypeAndFieldAndStatus(
            Long userId, Long venueId, FeedbackType type, FeedbackField field, ReportStatus status);
}
