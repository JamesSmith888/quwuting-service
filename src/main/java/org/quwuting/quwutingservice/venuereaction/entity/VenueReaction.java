package org.quwuting.quwutingservice.venuereaction.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;

import java.time.LocalDate;

/**
 * 用户对场所的 Reaction 记录（每日一记模型：一个用户对一个场所的一个 Reaction 每天至多一行）。
 * <p>
 * 2026-08 从"toggle 软删 hold 模型"迁移为"每日一记"模型（根因见 AGENTS.md「Reaction 快速反馈系统」）：
 * <ul>
 *   <li>每次点击 = 插入一条 {@code reactionDate = 今天} 的记录——"Reaction 作为用户近期体验评价"，
 *       用户次日可再次点击贡献 +1（"次日自动恢复可点击状态"），历史按日天然聚合；</li>
 *   <li>取消 = 物理删除当日记录（硬删）——唯一约束 (userId, venueId, reactionCode, reactionDate)
 *       保证"同一用户每天只能贡献一次同类型 Reaction"，且取消只可能作用于当日记录，
 *       使 countAll/countToday/count7d/count30d 四个窗口的本地 ±1 全部精确（乐观更新零回滚校正）；</li>
 *   <li>不做永久累加（不允许同一天重复增加）：同日再次点击命中原记录即取消，不会产生第二行。</li>
 * </ul>
 * {@code createdAt} 保留用于时间窗口统计（7天/30天滚动窗口锚点"此刻"），与 {@code reactionDate}
 * （每日唯一约束与"今日已参与"判定）职责分离，见 {@code VenueReactionRepository} 注释。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_venue_reactions", indexes = {
        @Index(name = "qwt_idx_vr_venue_code", columnList = "venueId, reactionCode"),
        @Index(name = "qwt_idx_vr_venue_code_created", columnList = "venueId, reactionCode, createdAt"),
        @Index(name = "qwt_idx_vr_user_id", columnList = "userId")
}, uniqueConstraints = {
        @UniqueConstraint(name = "qwt_uk_vr_user_venue_code_date", columnNames = {"userId", "venueId", "reactionCode", "reactionDate"})
})
public class VenueReaction extends BaseEntity {

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long venueId;

    /** 对应 {@link org.quwuting.quwutingservice.venuereaction.ReactionCode} 的枚举名 */
    @Column(nullable = false, length = 30)
    private String reactionCode;

    /**
     * 点击发生的自然日（唯一约束维度 + "今日已参与"判定依据）。
     * 窗口统计仍用 {@code createdAt}（滚动锚点），本字段只承载"每日唯一"语义。
     */
    @Column(nullable = false)
    private LocalDate reactionDate;
}
