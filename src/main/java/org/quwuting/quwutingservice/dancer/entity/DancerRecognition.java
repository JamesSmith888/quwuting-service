package org.quwuting.quwutingservice.dancer.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;

import java.time.LocalDate;

/**
 * 用户对舞伴的认可记录（每日一记模型：一个用户对一个舞伴每天至多一行）。
 * <p>
 * 设计完全复用 Reaction 快速反馈系统 2026-08 确立的「每日一记」模型（根因见
 * AGENTS.md「Reaction 快速反馈系统」与「舞伴生态体系」）：
 * <ul>
 *   <li>每次点击认可 = 插入一行 {@code recognitionDate = 今天} 的记录——"认可作为用户
 *       近期的支持表达"，用户次日可再次点击贡献 +1（"次日自动恢复可认可状态"），
 *       历史按日天然聚合，避免老数据永久占优势（舞厅/舞伴场景具有明显时间属性）；</li>
 *   <li>取消 = 物理删除当日记录（硬删）——唯一约束 (userId, dancerId, recognitionDate)
 *       保证"同一用户每天只能认可同一舞伴一次"，且取消只可能作用于当日记录，
 *       使 countAll/countToday/count7d/count30d 各窗口本地 ±1 全部精确；</li>
 *   <li>不做永久累加（不允许同一天重复增加）：同日再次点击命中原记录即取消。</li>
 * </ul>
 * {@code createdAt} 保留用于时间窗口统计（7天/30天滚动窗口锚点"此刻"），与
 * {@code recognitionDate}（每日唯一约束与"今日已认可"判定）职责分离，同 VenueReaction。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_dancer_recognitions", indexes = {
        @Index(name = "qwt_idx_dr_dancer_date", columnList = "dancerId, recognitionDate"),
        @Index(name = "qwt_idx_dr_dancer_created", columnList = "dancerId, createdAt"),
        @Index(name = "qwt_idx_dr_user", columnList = "userId")
}, uniqueConstraints = {
        @UniqueConstraint(name = "qwt_uk_dr_user_dancer_date", columnNames = {"userId", "dancerId", "recognitionDate"})
})
public class DancerRecognition extends BaseEntity {

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long dancerId;

    /**
     * 认可发生的自然日（唯一约束维度 + "今日已认可"判定依据）。
     * 窗口统计仍用 {@code createdAt}（滚动锚点），本字段只承载"每日唯一"语义。
     */
    @Column(nullable = false)
    private LocalDate recognitionDate;
}
