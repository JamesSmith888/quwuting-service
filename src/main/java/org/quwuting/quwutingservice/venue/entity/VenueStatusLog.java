package org.quwuting.quwutingservice.venue.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;

import java.time.LocalDateTime;

/**
 * 场所营业状态变迁日志。
 * <p>
 * 每次 status 字段变更时写入一条记录，形成完整审计链。
 * 用于统计"近 N 天暂停营业次数"、"当前状态持续天数"等热度维度。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_venue_status_logs", indexes = {
        @Index(name = "qwt_idx_status_logs_venue_time", columnList = "venueId, createdAt")
})
public class VenueStatusLog {

    /**
     * change_source 值域（本字段的唯一声明处，引用方一律取常量，禁再抄字面量）。
     * <p>
     * 语义 = 「谁写的」，与 {@code Venue.status_source}（「这个值代表谁的判断」= 权威
     * 层级依据）分工不重叠，切勿混用（见 {@link org.quwuting.quwutingservice.venue.enums.VenueStatusSource}）。
     */
    /** Agent + Skill 批量落库（舞讯同步 status-reverse 通道，2026-09-01 V8） */
    public static final String CHANGE_SOURCE_AGENT_BATCH = "AGENT_BATCH";

    /** 系统按门店开业计划自动兑现（2026-09-17 V29，见 docs/agents/50-venue-opening-plan.md） */
    public static final String CHANGE_SOURCE_SCHEDULED = "SCHEDULED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long venueId;

    /** 变更前状态（场所首次创建时为 null） */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private VenueStatus fromStatus;

    /** 变更后状态 */
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private VenueStatus toStatus;

    /** 操作人用户 ID（管理员或认领人） */
    private Long changedBy;

    /**
     * 变更来源标识（2026-09-01，V8；2026-09-17 加 SCHEDULED）：
     * AGENT_BATCH = Agent+Skill 批量落库（舞讯同步 status-reverse 通道）；
     * ADMIN = 管理端人工写库；SCHEDULED = 系统按开业计划自动兑现（V29，changedBy 为 null）；
     * null = 旧数据或其他系统自动变更。
     * 供管理后台「更新记录」区分来源。取值见本类顶部常量。
     */
    @Column(length = 20)
    private String changeSource;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
