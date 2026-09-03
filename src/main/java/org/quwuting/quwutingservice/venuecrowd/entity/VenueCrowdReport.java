package org.quwuting.quwutingservice.venuecrowd.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;

import java.time.LocalDate;

/**
 * 门店热度上报（舞友上报今晚在店舞伴 / 男客情况，2026-08-29；2026-08-31 细粒度重构
 * 去中文定性词为 8 档——本文件字段注释与构造注释随之更新，勿回退为 1-4/1-3 口径）。
 * <p>
 * 双维实时众包信号：{@code femaleLevel}（1-8，主信号）+ {@code maleLevel}
 * （1-8，次信号，可空；细粒度档位同女，0-20/约30/…/约300+）。每日一记（每人每店
 * 每天一票）——唯一约束经 V59 部分唯一索引（WHERE deleted=false）保证，应用层
 * MySQL ON DUPLICATE KEY 幂等 upsert。
 * <p>
 * 与 {@link org.quwuting.quwutingservice.venuestatusreport.entity.VenueStatusReport}
 * 的边界：status report = 突发事件（暂停/恢复/临检，2 天公示期，管理端处置）；
 * 本表 = 常态实时信号（6 小时窗口自动过期；全部历史走独立历史接口
 * /venues/{id}/crowd-reports/history 分页全量供回看）。2026-09-03 起管理端
 * 「热度管理」可删除不合理单条（软删），删除后用户当日可重新上报。不混用、
 * 不塞进 ReportType（突发事件语义），见 docs/agents/27-venue-crowd-report.md。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_venue_crowd_reports", indexes = {
        @Index(name = "qwt_idx_crowd_reports_venue_created", columnList = "venueId, createdAt")
})
public class VenueCrowdReport extends BaseEntity {

    /** 所属场所 ID（qwt_venues.id） */
    @Column(nullable = false)
    private Long venueId;

    /** 上报者用户 ID（需登录） */
    @Column(nullable = false)
    private Long userId;

    /** 在店舞伴（女）数量档位（1-8，见 CrowdFemaleLevel） */
    @Column(nullable = false)
    private Integer femaleLevel;

    /** 男客数量档位（1-8，细粒度档位同女，见 CrowdMaleLevel；null = 跳过未观察） */
    @Column
    private Integer maleLevel;

    /** 上报归属自然日（每日一记键的一部分；凌晨营业时段跨夜仍按自然日归属） */
    @Column(nullable = false)
    private LocalDate reportDate;

    /** 同日修改次数（审计：管理端可见「谁在同一天反复改」） */
    @Column(nullable = false)
    private Integer modifyCount = 0;
}
