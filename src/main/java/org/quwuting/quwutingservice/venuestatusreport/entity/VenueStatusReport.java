package org.quwuting.quwutingservice.venuestatusreport.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;
import org.quwuting.quwutingservice.venuestatusreport.enums.ReportReason;

import java.time.LocalDateTime;

/**
 * 用户实时上报的场所暂停状态报告。
 * <p>
 * 与 {@link org.quwuting.quwutingservice.venuefeedback.entity.VenueFeedback} 的区别：
 * feedback 是异步纠错（管理员人工审核队列），status report 是实时众包信号（TTL 自动过期，
 * 直接影响详情页展示与 StatusConfidence）。两表独立，职责边界见 AGENTS.md。
 * <p>
 * 联合唯一约束 (userId, venueId)：同一用户对同一场所只保留一条活跃报告，
 * 再次报告为 upsert（覆盖更新 reason/occurredAt/note，刷新 updatedAt）。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_venue_status_reports", indexes = {
        @Index(name = "qwt_idx_status_reports_venue_created", columnList = "venueId, createdAt"),
        @Index(name = "qwt_idx_status_reports_user", columnList = "userId")
}, uniqueConstraints = {
        @UniqueConstraint(name = "qwt_uk_status_report_user_venue", columnNames = {"userId", "venueId"})
})
public class VenueStatusReport extends BaseEntity {

    /** 所属场所 ID（qwt_venues.id） */
    @Column(nullable = false)
    private Long venueId;

    /** 报告者用户 ID（需登录） */
    @Column(nullable = false)
    private Long userId;

    /** 暂停原因（极速上报时默认 UNKNOWN） */
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private ReportReason reason = ReportReason.UNKNOWN;

    /**
     * 用户陈述的事件发生时间（可选）。
     * null = 事件发生在报告时刻（即 createdAt）。
     * 与 createdAt 区分：createdAt 是报告行为时间（系统记录），
     * occurredAt 是用户主观估计的事件发生时间，仅供管理端参考。
     */
    @Column
    private LocalDateTime occurredAt;

    /**
     * 补充说明（可选，最多 500 字）。
     * 仅管理端可见，前端不公开展示——规避用户自由文本的微信审核风险。
     */
    @Column(length = 500)
    private String note;
}
