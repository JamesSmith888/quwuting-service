package org.quwuting.quwutingservice.timershare.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;

/**
 * 给同行者点赞（qwt_timer_share_likes，2026-10-12，V48；文档 = docs/agents/54-timer-share.md §十五）。
 * <p>
 * 一行 = 「某张分享会话里，A 赞了 B」。唯一键 (share_id, liker_id, liked_user_id)：每对至多一行，
 * 取消 = 软删、再赞 = 原行复活（对齐 {@code qwt_venue_crowd_report_likes} 的 toggle 先例——被赞通知只在
 * 「首次赞」触发一次，由 INSERT 的受影响行数派生，不需要标志列）。
 * <p>
 * 🚫 <b>红线：赞无任何经济价值、永不进算法</b>——不产生积分、不累计到人、不进榜、不进任何主页、不改
 * 任何排序或统计口径；只在这一场里被双方看见。管理端行为目录里它是 EXTENDED 档（轨迹可见、不计活跃）。
 * <p>
 * 所有列名显式写 {@code @Column(name = ...)}（同 {@link TimerShare} 的理由：让实体 ↔ 迁移的对应关系一眼可查）。
 * 索引与唯一约束只在 V48 迁移里声明（{@code ddl-auto=validate} 不校验索引，实体上再写一份只会多一处可漂移的副本）。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_timer_share_likes",
        uniqueConstraints = @UniqueConstraint(name = "qwt_uk_tsl_share_pair",
                columnNames = {"share_id", "liker_id", "liked_user_id"}))
public class TimerShareLike extends BaseEntity {

    /** 分享会话（qwt_timer_shares.id） */
    @Column(name = "share_id", nullable = false, updatable = false)
    private Long shareId;

    /** 点赞者（qwt_users.id）。列名 {@code liker_id} 与热度点赞同名——行为目录的「用户列」归因口径 */
    @Column(name = "liker_id", nullable = false, updatable = false)
    private Long likerId;

    /** 被赞者（qwt_users.id）。仅服务端内部用，<b>不下发客户端</b>（客户端用 角色 + seq 指明对方） */
    @Column(name = "liked_user_id", nullable = false, updatable = false)
    private Long likedUserId;

    /** 行内码；现在恒为 {@code LIKE}（见 TimerSharePolicy#LIKE_CODE） */
    @Column(name = "code", nullable = false, length = 16)
    private String code;
}
