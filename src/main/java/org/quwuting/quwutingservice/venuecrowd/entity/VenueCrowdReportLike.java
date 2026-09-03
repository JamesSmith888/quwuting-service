package org.quwuting.quwutingservice.venuecrowd.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;

/**
 * 今晚热度上报行级点赞（2026-09-03「人际认可」层，docs/agents/27-venue-crowd-report.md
 * 「行级点赞」）：任何用户（含本人——自赞放开但不自动点亮）对单条上报行的「有用」
 * 一票制精神激励。
 * <p>
 * 与确认后积分的边界：积分 = 系统认可（算法判「报得准」才发，报错档位的少数派无）；
 * 本表 = 人际认可（零成本、即时、人人可得）——补上激励闭环缺的「随手一条这条有用」。
 * <p>
 * 防刷 = 唯一约束 (liker_id, report_id)（MySQL 全量唯一 + 软删 toggle，对齐
 * qwt_favorites 收藏 toggle：取消后再赞 = UPDATE 恢复原行）——每人每行至多 1 票，
 * 再点取消；被赞通知只在该对「首次赞」（INSERT affected=1）且非自赞时履行一次，
 * 由 DB 受影响行数派生，无额外标志列（YAGNI）。
 * <p>
 * 🚫 红线：赞数<b>永不进算法</b>——不进可信度加权/置信度分层/列表角标/热度公式
 * （自赞可刷，一旦进算法必死），纯展示层精神激励、不产生积分。
 * 上报行被管理端删除（软删）后不再出现在 summary/history → 赞随行不可见，
 * 无需级联清理（行保留审计）；删除后当日重报 = 新行 0 赞（内容已变，赞不迁移）。
 * like/unlike 仅允许 6h 窗口内行（后端 createdAt 窗口校验，业务码 1020）。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_venue_crowd_report_likes",
        uniqueConstraints = @UniqueConstraint(name = "qwt_uk_crowd_like_liker_report",
                columnNames = {"likerId", "reportId"}),
        indexes = @Index(name = "qwt_idx_crowd_likes_report", columnList = "reportId"))
public class VenueCrowdReportLike extends BaseEntity {

    /** 被赞的上报行 ID（qwt_venue_crowd_reports.id） */
    @Column(nullable = false)
    private Long reportId;

    /** 点赞者用户 ID（需登录；可为自己上报的行点赞） */
    @Column(nullable = false)
    private Long likerId;
}
