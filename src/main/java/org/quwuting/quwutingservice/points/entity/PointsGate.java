package org.quwuting.quwutingservice.points.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;
import org.quwuting.quwutingservice.points.enums.PointsGateTargetType;

/**
 * 积分门槛（"这个内容点需要多少积分才能解锁"，2026-08-14 公共模块）。
 * <p>
 * 语义：<b>cost &gt; 0 才落行</b>——"存在行"即"有门槛"；清除门槛 = 软删行
 * （部分唯一索引 WHERE deleted=false，兼容软删后重建，见 V23 迁移）。
 * 目标唯一性由数据库部分唯一索引保证（实体不声明 @UniqueConstraint——
 * 部分唯一索引无法用 JPA 注解表达，且 ddl-auto=validate 不校验唯一约束）。
 * <p>
 * 权限：设置/更新/清除 = 目标属主（舞伴本人 createdBy）或平台管理员；
 * 解锁 = 任意登录用户（目标对公众可见时）。
 * <p>
 * <b>继承 BaseEntity</b>（与账务流水不同）：门槛是可改可删的业务配置，
 * 有 updatedAt（记录最近调整）与软删（保留审计痕迹）。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_points_gates", indexes = {
        @Index(name = "qwt_idx_points_gates_target_type", columnList = "targetType, targetId, deleted")
})
public class PointsGate extends BaseEntity {

    /** 门槛目标类型（DANCER_PHOTO / DANCER_CONTACT，可扩展） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PointsGateTargetType targetType;

    /** 门槛目标 ID（照片 = qwt_dancer_photos.id；联系方式 = qwt_dancers.id） */
    @Column(nullable = false)
    private Long targetId;

    /** 解锁所需积分（恒 &gt; 0；0/负值不落行，设置 0 语义 = 清除门槛） */
    @Column(nullable = false)
    private int cost;

    /** 门槛设置者（舞伴本人 createdBy 或管理员 ID，审计用） */
    @Column(nullable = false)
    private Long createdBy;

    /** 最近更新者（本人/管理员，审计用；首建与 createdBy 相同） */
    private Long updatedBy;
}
