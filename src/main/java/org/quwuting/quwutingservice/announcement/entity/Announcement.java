package org.quwuting.quwutingservice.announcement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementCategory;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementScope;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementSource;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementStatus;
import org.quwuting.quwutingservice.base.BaseEntity;

import java.time.LocalDateTime;

/**
 * 全局公告（2026-09-01，docs/agents/34-announcements.md 设计定稿）。
 * <p>
 * 双场景统一一套系统：运营公告（MANUAL）+ 数据更新公告（SYSTEM），差异仅
 * {@link #source}；管理面在 Web 管理后台，小程序端只消费（列表/详情/已读）。
 * <p>
 * 大文本列（markdown 原文）用 @Lob + {@code @JdbcTypeCode(LONGVARCHAR)}——
 * MySQL 大文本列先例（venue_sync_reports 同款，见 MEMORY-DEPLOY）。
 * 时间戳由 Java 侧 {@code LocalDateTime.now()} 写入（时间戳红线，禁 DB now()）。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_announcements", indexes = {
        @Index(name = "qwt_idx_ann_status_publish", columnList = "status, publishAt"),
        @Index(name = "qwt_idx_ann_pinned_publish", columnList = "pinned, publishAt")
})
public class Announcement extends BaseEntity {

    /** 标题（≤ 50 字，varchar(100)） */
    @Column(length = 100, nullable = false)
    private String title;

    /** Markdown 原文（公告详情页 towxml 渲染；大文本列） */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false)
    private String content;

    /** 分类（NOTICE 运营公告 / DATA_UPDATE 数据更新） */
    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private AnnouncementCategory category;

    /** 来源（MANUAL 人工 / SYSTEM 系统） */
    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private AnnouncementSource source;

    /** 可见范围（一期仅 ALL，预留 CITY） */
    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    @ColumnDefault("'ALL'")
    private AnnouncementScope scope = AnnouncementScope.ALL;

    /** 状态机（DRAFT → PUBLISHED → OFFLINE，见 {@link AnnouncementStatus}） */
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    @ColumnDefault("'DRAFT'")
    private AnnouncementStatus status = AnnouncementStatus.DRAFT;

    /** 置顶（列表排序权重，倒序在前） */
    @Column(nullable = false)
    @ColumnDefault("0")
    private boolean pinned;

    /** 计划发布时间（定时发布；SYSTEM 公告创建即置 now，同时是同日防重键的日期依据） */
    private LocalDateTime publishAt;

    /** 计划下线时间（到点由 @Scheduled 强转 OFFLINE） */
    private LocalDateTime offlineAt;

    /** 实际发布时间（publish 立即生效或 @Scheduled 定时强转时写入） */
    private LocalDateTime publishedAt;

    /** 实际下线时间（offline 或 @Scheduled 定时强转时写入） */
    private LocalDateTime offlinedAt;

    /** 操作管理员 ID（SYSTEM 来源恒 null = 系统/Agent 生成，对齐审计先例） */
    private Long operatorId;
}
