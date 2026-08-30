package org.quwuting.quwutingservice.recruitment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.quwuting.quwutingservice.base.BaseEntity;
import org.quwuting.quwutingservice.recruitment.enums.RecruitGenderLimit;
import org.quwuting.quwutingservice.recruitment.enums.RecruitSalaryType;
import org.quwuting.quwutingservice.recruitment.enums.RecruitStatus;
import org.quwuting.quwutingservice.recruitment.enums.RecruitTerm;

import java.time.LocalDateTime;

/**
 * 门店招工信息（2026-08-29，docs/agents/28-recruitments.md，V61）。
 * <p>
 * 仅管理员直发（无用户 UGC 通道，个人主体合规红线）；必挂真实门店（venue_id，
 * 可信度锚点）。定位 = 用工信息展示（黄页），非招聘服务——无投递/报名/简历闭环。
 * <p>
 * 字段模型对标真实招工样例：职位多选（受控枚举 JSON 数组串）/ 招聘人数 / 合作周期 /
 * 性别年龄中性维度 / 薪资（类型 + 自由文本）/ 待遇（住宿 / 路费三态布尔）/ 联系
 * 双通道（电话 + 微信，发布前至少其一）/ 有效期（硬过滤谓词，过期即隐藏）。
 * 刻意不做残疾限制等就业歧视字段。
 * <p>
 * 联系方式真实值只在 POST /recruitments/{id}/contact 按需下发（页面明文恒不下发，
 * hasContact 驱动入口——与舞伴联系方式同一纪律）；获取留痕见
 * {@link RecruitmentContactFetch}（管理端「N 人获取」效果反馈闭环）。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_recruitments", indexes = {
        @Index(name = "qwt_idx_recruitments_status_expires", columnList = "status, expiresAt"),
        @Index(name = "qwt_idx_recruitments_venue", columnList = "venueId")
})
public class Recruitment extends BaseEntity {

    /** 关联门店 ID（必填，发布前校验门店存在且未删除） */
    @Column(nullable = false)
    private Long venueId;

    /** 职位多选（受控枚举 name 的 JSON 数组串，如 ["PARTNER_DANCE","WAITER"]，与 tags 同模式） */
    @Column(nullable = false, length = 200)
    private String positionTypes;

    /** 招聘人数（可空 = 未说明） */
    private Integer headcount;

    /** 合作周期 */
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    @ColumnDefault("'LONG_TERM'")
    private RecruitTerm term = RecruitTerm.LONG_TERM;

    /** 性别限定（中性维度） */
    @Enumerated(EnumType.STRING)
    @Column(length = 10, nullable = false)
    @ColumnDefault("'ANY'")
    private RecruitGenderLimit genderLimit = RecruitGenderLimit.ANY;

    /** 年龄下限（可空） */
    private Integer ageMin;

    /** 年龄上限（可空） */
    private Integer ageMax;

    /** 薪资类型 */
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    @ColumnDefault("'NEGOTIABLE'")
    private RecruitSalaryType salaryType = RecruitSalaryType.NEGOTIABLE;

    /** 薪资自由文本（如「软补 500/天」，可空；空时展示薪资类型 label） */
    @Column(length = 64)
    private String salaryText;

    /** 包住宿（三态：null 未说明 / true 包住 / false 不包住） */
    private Boolean accommodation;

    /** 报销路费（三态：null 未说明 / true 报销 / false 不报销） */
    private Boolean travelPaid;

    /** 工作内容与要求描述（管理员录入，最多 1000 字） */
    @Column(nullable = false, length = 1000)
    private String description;

    /** 联系人称呼（可空） */
    @Column(length = 32)
    private String contactName;

    /** 联系电话（可空；发布前要求与微信号至少其一） */
    @Column(length = 20)
    private String contactPhone;

    /** 微信号（可空；发布前要求与电话至少其一） */
    @Column(length = 64)
    private String contactWechat;

    /** 急聘（置顶 + 徽标，管理员手动）。列名 is_urgent 显式映射（boolean 字段名 urgent 与列名不一致） */
    @Column(name = "is_urgent", nullable = false)
    @ColumnDefault("false")
    private boolean urgent = false;

    /** 状态机（DRAFT / PUBLISHED / OFFLINE） */
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    @ColumnDefault("'DRAFT'")
    private RecruitStatus status = RecruitStatus.DRAFT;

    /** 发布时间（每次发布/重新发布刷新，驱动「最新优先」排序） */
    private LocalDateTime publishedAt;

    /** 有效期截止（必填；用户侧谓词 expires_at > now() 硬过滤，过期可在管理端续期） */
    @Column(nullable = false)
    private LocalDateTime expiresAt;

    /** 创建管理员用户 ID（审计） */
    @Column(nullable = false)
    private Long createdBy;
}
