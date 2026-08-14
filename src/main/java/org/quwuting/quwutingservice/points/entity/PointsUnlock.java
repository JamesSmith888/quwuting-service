package org.quwuting.quwutingservice.points.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.quwuting.quwutingservice.points.enums.PointsGateTargetType;

import java.time.LocalDateTime;

/**
 * 积分解锁记录（"谁在什么时间解锁了什么"，2026-08-14）。
 * <p>
 * 领域不变量：<b>一人一目标只扣一次费</b>——UNIQUE(user_id, target_type, target_id)
 * 在库内兜底（SQLState 23505 幂等返回已解锁），防止重复扣费与并发竞态。
 * 解锁后永久可查看（不设时效），故不存在"重复解锁"业务语义。
 * <p>
 * <b>不继承 BaseEntity</b>（与 qwt_daily_checkins 同模式）：锚点记录只写一次，
 * 无 updatedAt、无软删（解锁行为不可撤销——积分已单向消耗）。
 * transaction_id 关联扣费流水（qwt_points_transactions.id），审计闭环。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_points_unlocks", indexes = {
        @Index(name = "qwt_idx_points_unlocks_target", columnList = "targetType, targetId")
})
public class PointsUnlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreationTimestamp
    private LocalDateTime createdAt;

    /** 解锁者用户 ID */
    @Column(nullable = false)
    private Long userId;

    /** 被解锁的目标类型 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PointsGateTargetType targetType;

    /** 被解锁的目标 ID */
    @Column(nullable = false)
    private Long targetId;

    /** 扣费流水 ID（qwt_points_transactions.id，审计闭环） */
    @Column(nullable = false)
    private Long transactionId;
}
