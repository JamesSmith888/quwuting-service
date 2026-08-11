package org.quwuting.quwutingservice.venueclaim.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.quwuting.quwutingservice.base.BaseEntity;
import org.quwuting.quwutingservice.venueclaim.enums.ClaimStatus;

import java.time.LocalDateTime;

/**
 * 门店认领申请工单（2026-08-11 新增，需求「认领舞厅」）。
 * <p>
 * 一个工单 = 一个用户对一个门店的认领申请，含申请材料（真实姓名/手机号/
 * 微信号/营业执照/补充说明）与审核结果（处理人/备注/时间）。
 * <p>
 * 与 venuefeedback（信息纠错上报）语义区分：反馈是"数据有错"的异步上报；
 * 认领是"身份归属"的权限申请。认领审核通过后由 Service 层置
 * qwt_venues.claimed_by = userId——canManage 判定自动生效（认领人或平台
 * 管理员，见 {@link org.quwuting.quwutingservice.security.UserContext#requireManageOrAdmin}）。
 * <p>
 * 隐私约定（2026-08-11 决策 D1）：realName / contactPhone / contactWechat
 * 仅存本工单表（一次性身份核验材料），不写入 qwt_users——用户资料表保持最小
 * 必要，认领无关的隐私字段不扩散。审核完成后材料保留供追溯（匿名化策略由
 * 运营决定，前端承诺文案不涉及）。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_venue_claims", indexes = {
        @Index(name = "qwt_idx_claims_venue_id", columnList = "venueId"),
        @Index(name = "qwt_idx_claims_user_id", columnList = "userId"),
        @Index(name = "qwt_idx_claims_status", columnList = "status")
})
public class VenueClaim extends BaseEntity {

    /** 目标门店 ID（qwt_venues.id） */
    @Column(nullable = false)
    private Long venueId;

    /** 申请人用户 ID（qwt_users.id），认领必须登录，恒非空 */
    @Column(nullable = false)
    private Long userId;

    /** 真实姓名（必填，身份核验材料） */
    @Column(length = 50, nullable = false)
    private String realName;

    /** 手机号（必填，审核联系用） */
    @Column(length = 20, nullable = false)
    private String contactPhone;

    /** 微信号（选填） */
    @Column(length = 50)
    private String contactWechat;

    /**
     * 营业执照照片 URL 列表（JSON 数组字符串，如 ["url1","url2","url3"]，
     * 最多 3 张，与 Venue.photos 同模式；选填）。
     */
    @Column(length = 2000)
    private String licenseUrls;

    /** 补充说明（选填，最多 500 字；入库前经 TextSanitizer 清洗） */
    @Column(length = 500)
    private String note;

    /** 审核状态（唯一事实源；列默认值声明通道 = @ColumnDefault） */
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    @ColumnDefault("'PENDING'")
    private ClaimStatus status = ClaimStatus.PENDING;

    /** 审核人用户 ID（管理员处理时写入，未处理为 null） */
    private Long handledBy;

    /** 审核结果说明（如拒绝原因，随「我的认领」回传申请人；入库前经 TextSanitizer 清洗） */
    @Column(length = 500)
    private String handleNote;

    /** 审核时间（管理员处理时写入，未处理为 null） */
    private LocalDateTime handledAt;
}
