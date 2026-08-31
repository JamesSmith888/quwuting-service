package org.quwuting.quwutingservice.venuesync.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.quwuting.quwutingservice.base.BaseEntity;

import java.time.LocalDate;

/**
 * 门店同步报告存档（qwt_venue_sync_reports，2026-08-31）。
 * <p>
 * 管线（quwuting-ops/venue-opening）每次抓取+匹配后把报告上报本表（幂等：同渠道
 * 同报告日覆盖）。Web 管理后台「门店同步」页只读本表：最近报告概览 → 条目列表
 * （按置信度/动作筛选）→ 确认写库（apply 复用 DailyOpeningService.applyBatch）。
 * <ul>
 *   <li>summary：统计摘要 JSON（total_openings/matched/match_rate/by_confidence/
 *       reversed_candidates/closed_candidates/unmatched）</li>
 *   <li>items：条目数组 JSON（管线 MatchResult 镜像：city/source_name/status/
 *       confidence/alias_key/venue）</li>
 * </ul>
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_venue_sync_reports", indexes = {
        @Index(name = "qwt_idx_sync_reports_date", columnList = "reportDate")
})
public class VenueSyncReport extends BaseEntity {

    @Column(nullable = false)
    private LocalDate reportDate;

    @Column(nullable = false, length = 50)
    private String sourceId;

    @Column(nullable = false, length = 100)
    private String sourceLabel = "";

    @Column(nullable = false, length = 500)
    private String reportUrl = "";

    /** 统计摘要（JSON 串，Service 层编解码）。@Lob+@JdbcTypeCode 显式映射 longtext：Hibernate 7 在 MySQL 上裸 @Lob String 会推断 tinytext，与 V5 迁移列类型不一致导致 validate 失败 */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false)
    private String summary;

    /** 条目数组（JSON 串，Service 层编解码）。同上：显式 longtext 与 V5 迁移对齐 */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false)
    private String items;
}
