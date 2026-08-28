package org.quwuting.quwutingservice.appfeedback.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.quwuting.quwutingservice.appfeedback.AppFeedbackCategory;
import org.quwuting.quwutingservice.base.BaseEntity;
import org.quwuting.quwutingservice.venuefeedback.enums.ReportStatus;

import java.time.LocalDateTime;

/**
 * 平台级意见反馈（2026-08-28 新增，不绑定门店）。
 * <p>
 * 用户对"整个小程序"提交 BUG / 建议 / 夸奖：匿名可提交（user_id 可空 =
 * 匿名，与 qwt_venue_feedbacks 同一匿名决策——匿名可参与、追踪需登录），
 * 管理端在上报管理后台第三 tab（admin-reports）统一处理，处理结果站内信
 * 回传（MessageType.APP_FEEDBACK_RESULT），采纳奖励积分
 * （PointsSourceType.APP_FEEDBACK_REWARD，与门店纠错采纳同额同池）。
 * <p>
 * 处理状态复用 {@link ReportStatus} 状态机（PENDING → ADOPTED /
 * ADOPTED_NO_REWARD / RESOLVED / DISMISSED，终态固定），与门店维度上报
 * 管理端体验完全一致（三动作 + 处理说明回传）。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_app_feedbacks", indexes = {
        @Index(name = "qwt_idx_app_feedbacks_user", columnList = "userId"),
        @Index(name = "qwt_idx_app_feedbacks_status_created", columnList = "status, createdAt")
})
public class AppFeedback extends BaseEntity {

    /** 反馈提交者用户 ID（可空 = 匿名上报，与 venue_feedbacks 同一匿名决策） */
    @Column
    private Long userId;

    /** 反馈分类（BUG / 建议 / 夸奖 / 其他，见 {@link AppFeedbackCategory}） */
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private AppFeedbackCategory category;

    /** 反馈内容（必填，最多 500 字；经 TextSanitizer 清洗入库） */
    @Column(length = 500, nullable = false)
    private String content;

    /** 反馈截图 URL（可选，最多 1 张；Supabase 直传后回填 publicUrl） */
    @Column(length = 512)
    private String imageUrl;

    /** 处理状态（PENDING / ADOPTED / ADOPTED_NO_REWARD / RESOLVED / DISMISSED） */
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    @ColumnDefault("'PENDING'")
    private ReportStatus status = ReportStatus.PENDING;

    /** 处理人用户 ID（管理员标记，未处理为 null） */
    private Long handledBy;

    /** 处理时间（管理员标记，未处理为 null） */
    private LocalDateTime handledAt;

    /** 管理员处理结果说明（可选，最多 500 字；随「我的反馈」回传反馈者） */
    @Column(length = 500)
    private String handleNote;
}
