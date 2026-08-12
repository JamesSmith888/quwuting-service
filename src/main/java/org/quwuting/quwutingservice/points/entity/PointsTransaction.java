package org.quwuting.quwutingservice.points.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.quwuting.quwutingservice.points.enums.PointsSourceType;
import org.quwuting.quwutingservice.points.enums.PointsTargetType;

import java.time.LocalDateTime;

/**
 * 积分流水（**只追加、不可变**——余额对账的唯一事实源）。
 * <p>
 * 语义：
 * <ul>
 *   <li>{@code delta > 0} = 挣取（打卡/采纳/管理调整加分），必带 source_type + source_id
 *       （幂等键，部分唯一索引兜底并发）；</li>
 *   <li>{@code delta < 0} = 赠送（消费），必带 target_type + target_id（"收到积分"
 *       聚合维度）；</li>
 *   <li>{@code balanceAfter} = 该笔后的余额快照——日终对账 {@code SUM(delta)}
 *       与账户 balance 不一致即告警。</li>
 * </ul>
 * <b>不继承 BaseEntity</b>（与 qwt_venue_status_logs 同模式）：账务日志不可变，
 * 无 updatedAt（只写一次）、无 deleted（不做软删）。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_points_transactions", indexes = {
        @Index(name = "qwt_idx_pts_tx_user_created", columnList = "userId, createdAt"),
        @Index(name = "qwt_idx_pts_tx_target", columnList = "targetType, targetId, createdAt")
})
public class PointsTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private Long userId;

    /** 增减量：挣取为正，赠送为负（恒非 0） */
    @Column(nullable = false)
    private long delta;

    /** 该笔后的余额快照（对账） */
    @Column(nullable = false)
    private long balanceAfter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PointsSourceType sourceType;

    /** 幂等键来源记录 ID（checkin_id / feedback_id / 管理调整）；挣取场景恒非空 */
    private Long sourceId;

    /** 赠送目标类型（仅赠送流水非空） */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PointsTargetType targetType;

    /** 赠送目标 ID（仅赠送流水非空） */
    private Long targetId;

    /**
     * 赠送的礼物 code（GiftCatalog 枚举名，2026-08-12 V13 新增——仅赠送流水非空；
     * 存量 V2 积分赠送流水为 NULL）。聚合维度："目标收到什么礼物"（礼物墙展示）。
     */
    @Column(length = 30)
    private String giftCode;

    /** 备注（管理调整理由等） */
    @Column(length = 200)
    private String remark;
}
