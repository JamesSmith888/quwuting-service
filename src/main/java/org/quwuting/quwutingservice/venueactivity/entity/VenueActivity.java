package org.quwuting.quwutingservice.venueactivity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;
import org.quwuting.quwutingservice.venueactivity.enums.ActivityBenefitKind;
import org.quwuting.quwutingservice.venueactivity.enums.ActivityOuterSchedule;
import org.quwuting.quwutingservice.venueactivity.enums.ActivityRedemptionMode;
import org.quwuting.quwutingservice.venueactivity.enums.ActivityStatus;

import java.time.LocalDate;

/**
 * 门店营业活动（表结构见 V27，域设计见 docs/agents/49-venue-activities.md）。
 * <p>
 * 字段分三类，**改这个类之前先确认改的是哪一类**：
 * <ol>
 *   <li><b>结构化（被程序理解）</b>：{@code outerType / startDate / endDate}
 *       驱动状态机与自动下线；{@code weekdayMask / windows} 驱动"此刻是否命中"；
 *       {@code benefitKind} 供列表页短标签；{@code redemptionMode} 决定用户动作。
 *       加字段必须同步策略类、admin 表单、协议映射三处。</li>
 *   <li><b>结构化·展示</b>：{@code badgeLabel}（≤4 字，列表页 chip）。</li>
 *   <li><b>自由文本（仅展示，不进任何判定）</b>：{@code title /
 *       benefitSummary / redemptionHint / platformAddon}。</li>
 * </ol>
 * ⛔ 严禁让第 3 类参与判定（例如"文案里含'免票'就当作免门票"）——那是活动模板
 * DSL 的开端，公告域的可点击演示已经踩过一次（只能靠白名单 + 静默降级兜底）。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_venue_activities", indexes = {
        @Index(name = "qwt_idx_venue_activity_venue", columnList = "venueId,status"),
        @Index(name = "qwt_idx_venue_activity_expire", columnList = "status,endDate")
})
public class VenueActivity extends BaseEntity {

    @Column(nullable = false)
    private Long venueId;

    /** 活动名（如"双节双时段 · 男女门票买一送一"），自由文本 */
    @Column(nullable = false, length = 60)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ActivityBenefitKind benefitKind;

    /** 列表页短标签（≤4 字）；运营未填时取 benefitKind 的缺省值 */
    @Column(nullable = false, length = 8)
    private String badgeLabel;

    /** 权益说明（如"票价 30 元/位，赠券限七日内使用"），自由文本 */
    @Column(length = 500)
    private String benefitSummary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ActivityRedemptionMode redemptionMode;

    /** 核销提示（如"到前台报「去舞厅」"），自由文本 */
    @Column(length = 200)
    private String redemptionHint;

    /**
     * 平台专属加项（如"报「去舞厅」多赠一瓶饮料"）。
     * <p>
     * 存在的唯一理由：让"报口令"对平台用户产生**实际差异**。若门店对所有人
     * 都是同一优惠，口令没有信息量，打卡数不能作为平台贡献的证据（归因失效）。
     * 因此这一项建议门店给"成本≈0 的实物小增项"，而不是重复让利。
     */
    @Column(length = 200)
    private String platformAddon;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ActivityOuterSchedule outerType;

    /** outerType = DATE_RANGE 时必填；start = end 即单日 */
    private LocalDate startDate;

    /**
     * 有效期结束日（闭区间）。驱动 30s 调度的自动下线——判据是
     * {@code endDate < today}，即**结束当天仍然有效**。
     */
    private LocalDate endDate;

    /** 生效星期掩码（CSV，ISO 1=周一 … 7=周日）；null/空 = 每天 */
    @Column(length = 16)
    private String weekdayMask;

    /** 生效时段 JSON 列表（{@link org.quwuting.quwutingservice.venueactivity.dto.ActivityWindow}）；null/空 = 全天 */
    @Column(length = 500)
    private String windows;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ActivityStatus status;

    @Column(nullable = false)
    private int sortWeight;
}
