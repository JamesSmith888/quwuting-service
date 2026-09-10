package org.quwuting.quwutingservice.bulletin.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;

/**
 * 用户对一条行业快讯的表态（2026-09-10，docs/agents/47-bulletins.md「快讯表态」；V19）。
 * <p>
 * <b>一人一条恒一个表情</b>：唯一约束 {@code (userId, bulletinId)} 就是"一人一票"的
 * 不变量载体——换票 = 原地 UPDATE {@code reactionCode}，取消 = 物理删除该行（同门店
 * Reaction 的"取消即硬删"口径，故 {@code deleted} 恒 false）。
 * <p>
 * 与门店 Reaction（{@code VenueReaction}）的关键差异：
 * <ul>
 *   <li><b>无 reactionDate / 无窗口</b>：快讯是一次性内容，不做"每日一票"（没有次日
 *       再来评一次的场景），计数即全部历史，前端不做窗口切换；</li>
 *   <li><b>无多选</b>：门店域"每日一票"是应用层语义（开关可关、退化为多选），本域
 *       是 DB 唯一键硬约束，不存在开关；</li>
 *   <li><b>无软删 hold 模型历史包袱</b>：本域从第一天起就是硬删 + 唯一键。</li>
 * </ul>
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_bulletin_reactions", indexes = {
        @Index(name = "qwt_idx_br_bulletin", columnList = "bulletinId, deleted")
}, uniqueConstraints = {
        @UniqueConstraint(name = "qwt_uk_br_user_bulletin", columnNames = {"userId", "bulletinId"})
})
public class BulletinReaction extends BaseEntity {

    @Column(nullable = false)
    private Long userId;

    /** 被表态的快讯 id（qwt_announcements 中 category='FLASH' 的条目，写入前经可见性校验） */
    @Column(nullable = false)
    private Long bulletinId;

    /** 对应 {@link org.quwuting.quwutingservice.bulletin.BulletinReactionCode} 的目录 code */
    @Column(nullable = false, length = 30)
    private String reactionCode;
}
