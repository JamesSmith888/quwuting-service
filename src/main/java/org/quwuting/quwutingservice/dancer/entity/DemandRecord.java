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
 * 联系方式需求记录（2026-08-24 需求 5 风控留痕，qwt_demand_records）。
 * <p>
 * 语义：用户在获取某舞伴联系方式前选择了本次需求（服务 ≤2 + 时间 ≤2 + 时长可选），
 * 服务端生成添加好友需求描述并随本记录落库——支撑骚扰投诉排查（按 dancer 维度
 * 反查"哪些需求在找 TA"）与异常访问识别（按 user 维度统计单日解锁频次）。
 * <p>
 * <b>隐私克制</b>：只存枚举/服务 id（service_ids = qwt_dancer_services.id 逗号串、
 * time_slots = 具体日期（YYYY-MM-DD，2026-08-25 改版：今天起 7 天窗口）逗号串、
 * duration = DemandDuration 枚举）——
 * 需求 = 用户行为数据，非身份信息；message 为服务端拼接的完整需求描述（审计用，
 * 与用户微信添加好友时粘贴的内容一致）。
 * <p>
 * <b>不继承 BaseEntity</b>（锚点记录只写一次，无更新/软删语义，同 PointsUnlock 模式）。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_demand_records", indexes = {
        @Index(name = "idx_qwt_demand_records_user", columnList = "user_id, created_at"),
        @Index(name = "idx_qwt_demand_records_dancer", columnList = "dancer_id, created_at")
})
public class DemandRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreationTimestamp
    private LocalDateTime createdAt;

    /** 提出需求的用户 ID */
    @Column(nullable = false)
    private Long userId;

    /** 目标舞伴 ID */
    @Column(nullable = false)
    private Long dancerId;

    /** 选中的服务 id 列表（qwt_dancer_services.id 逗号串，≤2；风控只存 id 不存文本） */
    @Column(nullable = false, length = 100)
    private String serviceIds;

    /** 选中的时间（具体日期 YYYY-MM-DD 逗号串，恰好 1；2026-08-25 改版） */
    @Column(nullable = false, length = 50)
    private String timeSlots;

    /** 时长（DemandDuration 枚举；可空 = 未选） */
    @Column(length = 20)
    private String duration;

    /**
     * 用户位置表态（2026-08-26，UserLocationOption 枚举 code：SAME_CITY 同城 /
     * WILL_TRAVEL 自行前往；可空 = 舞伴未开启「加好友需告知位置」）。
     * 只存枚举 code（相对关系非真实地址，隐私克制，见 UserLocationOption javadoc）。
     */
    @Column(length = 20)
    private String userLocation;

    /** 服务端拼接的完整需求描述（与用户微信添加好友时粘贴的内容一致，审计用） */
    @Column(nullable = false, length = 120)
    private String message = "";

    /**
     * 邀约状态（2026-08-26 新增，V50；DemandStatus 枚举 code，可空）。
     * <p>
     * 语义：邀约中转状态机（docs/agents/22）——开启中转开关（contact_relay）的
     * 舞伴，邀约提交后 = PENDING，管理员人工转发给舞伴后按舞伴回复置
     * APPROVED/REJECTED，24h 无回复按 auto_release 置 AUTO_RELEASED/EXPIRED。
     * <b>NULL = 存量锚点记录</b>（V42 前无状态语义，历史客人在当时已拿到微信，
     * 等价 APPROVED，前端徽标兼容不渲染）。本表语义 = 锚点记录只写一次，status
     * 是唯一允许更新的列（发放/拒绝/降级写路径，条件更新 WHERE status='PENDING'
     * 天然幂等）。
     */
    @Column(length = 20)
    private String status;
}
