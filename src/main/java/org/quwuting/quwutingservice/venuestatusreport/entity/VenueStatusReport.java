package org.quwuting.quwutingservice.venuestatusreport.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.quwuting.quwutingservice.base.BaseEntity;
import org.quwuting.quwutingservice.venuestatusreport.enums.AdminAction;
import org.quwuting.quwutingservice.venuestatusreport.enums.ReportType;

import java.time.LocalDateTime;

/**
 * 用户实时上报的门店突发事件（紧急公告）信号。
 * <p>
 * 与 {@link org.quwuting.quwutingservice.venuefeedback.entity.VenueFeedback} 的区别：
 * feedback 是异步纠错（管理员人工审核队列），status report 是实时众包信号
 * （统一公示期自动过期，直接影响详情页紧急公告区展示与 StatusConfidence）。
 * 两表独立，职责边界见 AGENTS.md。
 * <p>
 * 2026-08-11 泛化（V11）：原"暂停营业专用"泛化为 8 类突发事件（{@link ReportType}）。
 * <ul>
 *   <li>{@code type}：事件类型（替代原 reason 维度）；</li>
 *   <li>{@code expiresAt}：公示期（2026-08-21 起统一 2 天，原按类型 TTL 退役）计算的
 *       过期时刻（TTL 唯一事实源，所有"活跃"判定统一判 {@code expiresAt > now()}）；</li>
 *   <li>{@code adminAction}：管理端处置标记（ADOPTED/REMOVED），null = 活跃信号。</li>
 * </ul>
 * 2026-08-20 追加式模型（V34）：<b>每次上报 = 一条新记录</b>（无活跃报告时 INSERT
 * 新行，历史多条）；同一用户对同一门店<b>同时至多一条活跃报告</b>（并发约束由应用层
 * pg_advisory_xact_lock 串行化保证，见 StatusReportService.submitReport）——「补充
 * 详情」= 更新该活跃行（不产生新记录），已被采纳/撤销/已过期的记录不再被恢复，
 * 用户可再次上报产生新行。旧 upsert 模型（UNIQUE(user_id, venue_id) 覆盖更新/恢复
 * 软删）已废弃。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_venue_status_reports", indexes = {
        @Index(name = "qwt_idx_status_reports_venue_created", columnList = "venueId, createdAt"),
        @Index(name = "qwt_idx_status_reports_venue_expires", columnList = "venueId, expiresAt"),
        @Index(name = "qwt_idx_status_reports_user", columnList = "userId")
})
public class VenueStatusReport extends BaseEntity {

    /** 所属场所 ID（qwt_venues.id） */
    @Column(nullable = false)
    private Long venueId;

    /** 报告者用户 ID（需登录） */
    @Column(nullable = false)
    private Long userId;

    /** 突发事件类型（2026-08-11 泛化，替代原 reason；列默认值通道 = @ColumnDefault） */
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    @ColumnDefault("'SUSPENDED'")
    private ReportType type = ReportType.SUSPENDED;

    /**
     * 信号过期时刻（2026-08-11 新增，NOT NULL）：写入时 = 报告时刻 + {@code type} 的
     * TTL。TTL 唯一事实源 = 本列——所有"活跃"判定（热度计数 / 公开列表 / 管理端
     * 列表 / hasMyReport / 公告区聚合）统一判 {@code expiresAt > now()}，替代旧的
     * {@code createdAt >= now - 4h} 单窗口（旧窗口无法表达按类型分级 TTL）。
     * upsert 续期 = 覆盖写入 {@code createdAt} + {@code expiresAt}（经 JPQL 批量更新，
     * 见 StatusReportRepository.renewReport 根因注记）。
     */
    @Column(nullable = false)
    private LocalDateTime expiresAt;

    /**
     * 管理端处置标记（2026-08-11 新增，可空）：null = 活跃信号；ADOPTED = 已采纳
     * （公告区保留展示至 TTL 过期，带"已核实"标记）；REMOVED = 已移除（公开视图
     * 即时消失）。处置后该记录 soft delete（V34 追加式模型），用户再次上报 =
     * 新记录（本条不再被恢复）。
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private AdminAction adminAction;

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
     * 例外：SITUATION_UNCLEAR（情况不明）类型提交时必须携带（信息量最低，强制
     * 说明约束），服务层校验。
     */
    @Column(length = 500)
    private String note;
}
