package org.quwuting.quwutingservice.dancer.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 舞伴信息核验审计日志（2026-08-14 官方认证）。
 * <p>
 * 认证（「信息已核验」标识）全部状态变迁的唯一历史事实源：谁、何时、从什么状态、
 * 到什么状态、什么原因——撤销原因必须留痕（被撤销舞伴可查原因，延续"被指涉方
 * 有申辩权"治理原则）；「曾认证」判定（撤销后编辑 → 重新待复核闭环）也走本表。
 * <p>
 * <b>不继承 BaseEntity</b>（与 qwt_venue_status_logs / qwt_dancer_ad_views 同模式）：
 * 审计日志只追加、不可撤销、无软删语义。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_dancer_verification_logs", indexes = {
        @Index(name = "qwt_idx_verification_logs_dancer", columnList = "dancerId")
})
public class DancerVerificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreationTimestamp
    private LocalDateTime createdAt;

    /** 舞伴 ID */
    @Column(nullable = false)
    private Long dancerId;

    /** 操作人（admin 授予/撤销；本人编辑触发 = 编辑者 ID） */
    private Long operatorId;

    /** 变更前状态（DancerVerificationStatus.name()） */
    @Column(nullable = false, length = 20)
    private String fromStatus;

    /** 变更后状态（DancerVerificationStatus.name()） */
    @Column(nullable = false, length = 20)
    private String toStatus;

    /** 变更原因（撤销必填；授予/自动降级为说明文案） */
    @Column(length = 200)
    private String reason;
}
