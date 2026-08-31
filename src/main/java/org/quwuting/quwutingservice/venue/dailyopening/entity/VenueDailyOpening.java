package org.quwuting.quwutingservice.venue.dailyopening.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;
import org.quwuting.quwutingservice.venue.dailyopening.enums.DailyOpeningConfidence;
import org.quwuting.quwutingservice.venue.dailyopening.enums.DailyOpeningStatus;

import java.time.LocalDate;

/**
 * 门店每日营业快照（信息源自动化更新，2026-08-31，V63）。
 * <p>
 * 语义边界（重要）：
 * <ul>
 *   <li>本表只记录「信息源声称某店某日营业/休息」的<b>当日快照事实</b>（时间序列，
 *       可算营业稳定性），<b>不是</b>平台长期状态（venue.status 的权威仍在 qwt_venues）；</li>
 *   <li>status 的反转（CEASED→OPEN）由 {@code DailyOpeningService} 按置信度把关，
 *       走既有状态变更审计链（VenueStatusLog + 关注者通知 + 热度/列表/详情缓存失效）；</li>
 *   <li>幂等：同店同日报导源至多一条（PG 部分唯一索引 / MySQL 生成列唯一键），
 *       重复 apply = upsert 覆盖（created_at 刷新 = 该源当日最新确认时刻，
 *       口径同 V59 crowd reports 每日一记）。</li>
 * </ul>
 * ⚠️ 时间口径（记忆红线）：created_at/updated_at 由 BaseEntity 的
 * @CreationTimestamp/@UpdateTimestamp 写入 JVM 时区（北京时间），禁止 DB now()。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_venue_daily_openings", indexes = {
        @Index(name = "qwt_idx_daily_openings_date_venue", columnList = "reportDate, venueId")
})
public class VenueDailyOpening extends BaseEntity {

    /** 所属场所 ID（qwt_venues.id） */
    @Column(nullable = false)
    private Long venueId;

    /** 营业归属自然日（信息源声明的营业日期，非写入时刻） */
    @Column(nullable = false)
    private LocalDate reportDate;

    /** 信息源渠道标识（xianbao360 / telegram / …） */
    @Column(nullable = false, length = 50)
    private String sourceId;

    /** 信息源声称的当日状态（OPEN / CLOSED） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DailyOpeningStatus status;

    /** 与平台门店的匹配置信度（EXACT/ALIAS 可反转，CONTAINED/FUZZY 仅快照） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DailyOpeningConfidence confidence;
}
